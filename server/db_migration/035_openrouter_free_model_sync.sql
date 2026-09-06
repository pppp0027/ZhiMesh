-- OpenRouter free-model automatic synchronization schema.
--
-- Target database: PostgreSQL 16
-- Prerequisite: migrations 001-034 have already been applied.
-- Safe to re-run after a successful execution.
--
-- This migration does NOT update adi_model_platform.api_key and does NOT
-- enable/disable any existing model. Runtime model changes are owned by the
-- application sync job introduced with this feature.

BEGIN;

-- Fail before any DDL if the target schema is not the expected ZhiMesh schema.
DO $preflight$
DECLARE
    duplicate_models text;
BEGIN
    IF to_regclass('public.adi_ai_model') IS NULL THEN
        RAISE EXCEPTION 'Required table public.adi_ai_model does not exist';
    END IF;

    IF to_regclass('public.adi_model_platform') IS NULL THEN
        RAISE EXCEPTION 'Required table public.adi_model_platform does not exist';
    END IF;

    IF to_regprocedure('public.update_modified_column()') IS NULL THEN
        RAISE EXCEPTION 'Required trigger function public.update_modified_column() does not exist';
    END IF;

    SELECT string_agg(
                   format('%s / %s (%s rows)', platform, name, row_count),
                   E'\n'
                   ORDER BY platform, name
           )
    INTO duplicate_models
    FROM (
        SELECT platform, name, count(*) AS row_count
        FROM public.adi_ai_model
        GROUP BY platform, name
        HAVING count(*) > 1
        ORDER BY platform, name
        LIMIT 20
    ) duplicates;

    IF duplicate_models IS NOT NULL THEN
        RAISE EXCEPTION
            'Duplicate (platform, name) model rows must be merged before migration 035:%',
            E'\n' || duplicate_models;
    END IF;
END
$preflight$;

-- OpenRouter IDs contain provider, slug and variant suffix. varchar(45) is too
-- small for the complete provider-defined identity.
ALTER TABLE public.adi_ai_model
    ALTER COLUMN name TYPE varchar(255),
    ALTER COLUMN title TYPE varchar(255);

-- The application and the sync job identify a model by platform + provider ID.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_model_platform_name
    ON public.adi_ai_model (platform, name);

-- One row per synchronization execution. summary contains only sanitized
-- decisions and before/after state; credentials must never be stored here.
CREATE TABLE IF NOT EXISTS public.adi_openrouter_sync_run
(
    id             bigserial primary key,
    uuid           varchar(32)   not null,
    trigger_type   varchar(24)   not null,
    status         varchar(32)   not null,
    catalog_count  integer       not null default 0,
    free_count     integer       not null default 0,
    eligible_count integer       not null default 0,
    probed_count   integer       not null default 0,
    added_count    integer       not null default 0,
    updated_count  integer       not null default 0,
    enabled_count  integer       not null default 0,
    disabled_count integer       not null default 0,
    skipped_count  integer       not null default 0,
    started_at     timestamp     not null,
    completed_at   timestamp,
    error_code     varchar(64)   not null default '',
    error_message  varchar(1000) not null default '',
    summary        jsonb         not null default '{}'::jsonb,
    create_time    timestamp     not null default CURRENT_TIMESTAMP,
    update_time    timestamp     not null default CURRENT_TIMESTAMP,
    CONSTRAINT uk_openrouter_sync_run_uuid UNIQUE (uuid)
);

CREATE INDEX IF NOT EXISTS idx_openrouter_sync_run_started_at
    ON public.adi_openrouter_sync_run (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_openrouter_sync_run_status
    ON public.adi_openrouter_sync_run (status, started_at DESC);

DROP TRIGGER IF EXISTS trigger_openrouter_sync_run_update_time
    ON public.adi_openrouter_sync_run;

CREATE TRIGGER trigger_openrouter_sync_run_update_time
    BEFORE UPDATE
    ON public.adi_openrouter_sync_run
    FOR EACH ROW
EXECUTE FUNCTION public.update_modified_column();

COMMENT ON TABLE public.adi_openrouter_sync_run IS
    'One audit row for each OpenRouter catalog sync, lightweight health check, or recovery execution';
COMMENT ON COLUMN public.adi_openrouter_sync_run.trigger_type IS
    'SCHEDULED, MANUAL, STARTUP_CATCH_UP, STARTUP_RECOVERY, HEALTH_CHECK, or HEALTH_CHECK_RECOVERY';
COMMENT ON COLUMN public.adi_openrouter_sync_run.status IS
    'QUEUED, RUNNING, SUCCESS, PARTIAL_RATE_LIMIT, FAILED, or SKIPPED_LOCKED';
COMMENT ON COLUMN public.adi_openrouter_sync_run.summary IS
    'Sanitized model decisions and before/after state; must not contain credentials';

-- Provider-specific catalog and probe state. model_id is nullable so a model
-- rejected before import can still retain an auditable discovery record.
CREATE TABLE IF NOT EXISTS public.adi_openrouter_model_state
(
    id                       bigserial primary key,
    platform                 varchar(45)   not null,
    model_name               varchar(255)  not null,
    model_id                 bigint,
    is_managed               boolean       not null default true,
    lifecycle_status         varchar(32)   not null default 'DISCOVERED',
    catalog_status           varchar(32)   not null default 'UNKNOWN',
    probe_status             varchar(32)   not null default 'NOT_RUN',
    last_decision            varchar(64)   not null default '',
    disable_reason           varchar(1000) not null default '',
    catalog_latency_p50_ms   integer,
    catalog_throughput_p50   numeric(12, 2),
    catalog_uptime_1d        numeric(7, 3),
    actual_ttft_ms           integer,
    actual_total_latency_ms  integer,
    consecutive_failures     integer       not null default 0,
    last_error_code          varchar(64)   not null default '',
    last_error_message       varchar(1000) not null default '',
    last_seen_at             timestamp,
    last_probe_at            timestamp,
    last_success_at          timestamp,
    last_disabled_at         timestamp,
    raw_metadata             jsonb         not null default '{}'::jsonb,
    create_time              timestamp     not null default CURRENT_TIMESTAMP,
    update_time              timestamp     not null default CURRENT_TIMESTAMP,
    CONSTRAINT uk_openrouter_model_state_identity UNIQUE (platform, model_name),
    CONSTRAINT fk_openrouter_model_state_model
        FOREIGN KEY (model_id)
        REFERENCES public.adi_ai_model (id)
        ON DELETE SET NULL,
    CONSTRAINT ck_openrouter_model_state_failure_count
        CHECK (consecutive_failures >= 0),
    CONSTRAINT ck_openrouter_model_state_catalog_latency
        CHECK (catalog_latency_p50_ms IS NULL OR catalog_latency_p50_ms >= 0),
    CONSTRAINT ck_openrouter_model_state_catalog_throughput
        CHECK (catalog_throughput_p50 IS NULL OR catalog_throughput_p50 >= 0),
    CONSTRAINT ck_openrouter_model_state_catalog_uptime
        CHECK (catalog_uptime_1d IS NULL OR
               (catalog_uptime_1d >= 0 AND catalog_uptime_1d <= 100)),
    CONSTRAINT ck_openrouter_model_state_actual_ttft
        CHECK (actual_ttft_ms IS NULL OR actual_ttft_ms >= 0),
    CONSTRAINT ck_openrouter_model_state_actual_total_latency
        CHECK (actual_total_latency_ms IS NULL OR actual_total_latency_ms >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_openrouter_model_state_model_id
    ON public.adi_openrouter_model_state (model_id)
    WHERE model_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_openrouter_model_state_lifecycle
    ON public.adi_openrouter_model_state (platform, lifecycle_status);

CREATE INDEX IF NOT EXISTS idx_openrouter_model_state_last_seen
    ON public.adi_openrouter_model_state (last_seen_at DESC);

DROP TRIGGER IF EXISTS trigger_openrouter_model_state_update_time
    ON public.adi_openrouter_model_state;

CREATE TRIGGER trigger_openrouter_model_state_update_time
    BEFORE UPDATE
    ON public.adi_openrouter_model_state
    FOR EACH ROW
EXECUTE FUNCTION public.update_modified_column();

COMMENT ON TABLE public.adi_openrouter_model_state IS
    'Persistent OpenRouter catalog eligibility, probe performance, and lifecycle state';
COMMENT ON COLUMN public.adi_openrouter_model_state.model_name IS
    'Exact OpenRouter model ID, including provider prefix and :free suffix';
COMMENT ON COLUMN public.adi_openrouter_model_state.model_id IS
    'Nullable reference to adi_ai_model; null means discovered but not imported';
COMMENT ON COLUMN public.adi_openrouter_model_state.is_managed IS
    'Whether the automatic sync is authorized to enable, disable, or update this model';
COMMENT ON COLUMN public.adi_openrouter_model_state.catalog_latency_p50_ms IS
    'OpenRouter endpoint p50 latency normalized to milliseconds';
COMMENT ON COLUMN public.adi_openrouter_model_state.actual_ttft_ms IS
    'Time to first content or reasoning delta measured from the ZhiMesh server';
COMMENT ON COLUMN public.adi_openrouter_model_state.raw_metadata IS
    'Sanitized OpenRouter catalog and endpoint metadata; must not contain credentials';

COMMIT;

-- ---------------------------------------------------------------------------
-- IDEA result-pane verification. Review every result set after execution.
-- ---------------------------------------------------------------------------

-- Must return two rows with character_maximum_length = 255.
SELECT column_name, data_type, character_maximum_length
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'adi_ai_model'
  AND column_name IN ('name', 'title')
ORDER BY column_name;

-- Must return two non-null table names.
SELECT to_regclass('public.adi_openrouter_model_state') AS model_state_table,
       to_regclass('public.adi_openrouter_sync_run') AS sync_run_table;

-- Must return the composite unique model index.
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN (
      'uk_ai_model_platform_name',
      'uk_openrouter_model_state_model_id'
  )
ORDER BY indexname;

-- Must return both update_time triggers with enabled = O (origin/local enabled).
SELECT event_object_table, trigger_name, action_timing, event_manipulation
FROM information_schema.triggers
WHERE trigger_schema = 'public'
  AND trigger_name IN (
      'trigger_openrouter_model_state_update_time',
      'trigger_openrouter_sync_run_update_time'
  )
ORDER BY trigger_name;

-- Must return zero rows.
SELECT platform, name, count(*)
FROM public.adi_ai_model
GROUP BY platform, name
HAVING count(*) > 1;
