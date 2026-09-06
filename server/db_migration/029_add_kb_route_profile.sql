-- Knowledge-base route profiles for the low-latency preflight relevance gate.
--
-- This script is PostgreSQL-compatible and can be executed directly from an
-- IntelliJ IDEA database console. It intentionally contains no psql-only
-- meta commands.

BEGIN;

ALTER TABLE public.adi_knowledge_base
    ADD COLUMN IF NOT EXISTS route_profile_status varchar(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS route_profile_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS route_profile_active_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS route_profile_set_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS route_profile_source_hash varchar(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS route_profile_model_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS route_profile_model_identity varchar(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS route_profile_status_change_time timestamp;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_kb_route_profile_status'
          AND conrelid = 'public.adi_knowledge_base'::regclass
    ) THEN
        ALTER TABLE public.adi_knowledge_base
            ADD CONSTRAINT ck_kb_route_profile_status
            CHECK (route_profile_status IN ('NONE', 'STALE', 'BUILDING', 'READY', 'FAILED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_kb_route_profile_generation'
          AND conrelid = 'public.adi_knowledge_base'::regclass
    ) THEN
        ALTER TABLE public.adi_knowledge_base
            ADD CONSTRAINT ck_kb_route_profile_generation
            CHECK (
                route_profile_generation >= 0
                AND route_profile_active_generation >= 0
                AND route_profile_active_generation <= route_profile_generation
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_kb_route_profile_ready'
          AND conrelid = 'public.adi_knowledge_base'::regclass
    ) THEN
        ALTER TABLE public.adi_knowledge_base
            ADD CONSTRAINT ck_kb_route_profile_ready
            CHECK (
                route_profile_status <> 'READY'
                OR (
                    route_profile_generation = route_profile_active_generation
                    AND route_profile_active_generation > 0
                    AND route_profile_set_uuid <> ''
                    AND route_profile_model_id >= 0
                    AND route_profile_model_identity <> ''
                )
            );
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS public.adi_knowledge_base_route_profile_set (
    id                       bigserial PRIMARY KEY,
    uuid                     varchar(32)  NOT NULL,
    kb_id                    bigint       NOT NULL,
    kb_uuid                  varchar(32)  NOT NULL,
    generation               bigint       NOT NULL,
    source_manifest_hash     varchar(64)  NOT NULL,
    embedding_model_id       bigint       NOT NULL,
    embedding_model_identity varchar(255) NOT NULL,
    embedding_dimension      integer      NOT NULL,
    generator_version        varchar(64)  NOT NULL,
    profile_limit            integer      NOT NULL,
    profile_count            integer      NOT NULL DEFAULT 0,
    status                   varchar(16)  NOT NULL,
    is_active                boolean      NOT NULL DEFAULT false,
    error_type               varchar(128),
    error_message            text,
    started_at               timestamp,
    completed_at             timestamp,
    activated_at             timestamp,
    create_time              timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time              timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_kb_route_profile_set_status
        CHECK (status IN ('BUILDING', 'READY', 'ACTIVE', 'SUPERSEDED', 'FAILED')),
    CONSTRAINT ck_kb_route_profile_set_active
        CHECK ((status = 'ACTIVE') = is_active),
    CONSTRAINT ck_kb_route_profile_set_generation
        CHECK (generation > 0),
    CONSTRAINT ck_kb_route_profile_set_dimension
        CHECK (embedding_dimension > 0),
    CONSTRAINT ck_kb_route_profile_set_count
        CHECK (
            profile_limit BETWEEN 1 AND 64
            AND profile_count BETWEEN 0 AND profile_limit
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_route_profile_set_uuid
    ON public.adi_knowledge_base_route_profile_set(uuid);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_route_profile_set_generation
    ON public.adi_knowledge_base_route_profile_set(kb_uuid, generation);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_route_profile_set_active
    ON public.adi_knowledge_base_route_profile_set(kb_uuid)
    WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_kb_route_profile_set_recovery
    ON public.adi_knowledge_base_route_profile_set(status, update_time);

CREATE TABLE IF NOT EXISTS public.adi_knowledge_base_route_profile (
    id                  bigserial PRIMARY KEY,
    uuid                varchar(32)  NOT NULL,
    profile_set_id      bigint       NOT NULL,
    profile_set_uuid    varchar(32)  NOT NULL,
    kb_id               bigint       NOT NULL,
    kb_uuid             varchar(32)  NOT NULL,
    profile_type        varchar(16)  NOT NULL,
    profile_key         varchar(128) NOT NULL,
    profile_text        text         NOT NULL,
    profile_embedding   real[]       NOT NULL,
    embedding_dimension integer      NOT NULL,
    source_refs         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    ordinal             integer      NOT NULL,
    create_time         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_kb_route_profile_type
        CHECK (profile_type IN ('OVERVIEW', 'DOCUMENT', 'TOPIC')),
    CONSTRAINT ck_kb_route_profile_embedding
        CHECK (
            embedding_dimension > 0
            AND cardinality(profile_embedding) = embedding_dimension
        ),
    CONSTRAINT ck_kb_route_profile_text
        CHECK (btrim(profile_text) <> ''),
    CONSTRAINT ck_kb_route_profile_ordinal
        CHECK (ordinal >= 0),
    CONSTRAINT ck_kb_route_profile_source_refs
        CHECK (jsonb_typeof(source_refs) = 'array')
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_route_profile_uuid
    ON public.adi_knowledge_base_route_profile(uuid);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_route_profile_key
    ON public.adi_knowledge_base_route_profile(profile_set_uuid, profile_key);

CREATE INDEX IF NOT EXISTS idx_kb_route_profile_set
    ON public.adi_knowledge_base_route_profile(profile_set_uuid, ordinal);

CREATE INDEX IF NOT EXISTS idx_kb_route_profile_kb
    ON public.adi_knowledge_base_route_profile(kb_uuid, profile_set_uuid);

DROP TRIGGER IF EXISTS trigger_kb_route_profile_set_update_time
    ON public.adi_knowledge_base_route_profile_set;
CREATE TRIGGER trigger_kb_route_profile_set_update_time
    BEFORE UPDATE ON public.adi_knowledge_base_route_profile_set
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();

DROP TRIGGER IF EXISTS trigger_kb_route_profile_update_time
    ON public.adi_knowledge_base_route_profile;
CREATE TRIGGER trigger_kb_route_profile_update_time
    BEFORE UPDATE ON public.adi_knowledge_base_route_profile
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();

COMMENT ON COLUMN public.adi_knowledge_base.route_profile_generation IS
    'Latest generation requested by an explicit vector, graph, or BM25 indexing operation.';
COMMENT ON COLUMN public.adi_knowledge_base.route_profile_active_generation IS
    'Generation of the currently serving profile bundle. It may be older while a new generation is rebuilding.';
COMMENT ON COLUMN public.adi_knowledge_base.route_profile_set_uuid IS
    'Currently serving immutable route-profile set. Retained during stale-while-revalidate rebuilds.';
COMMENT ON COLUMN public.adi_knowledge_base.route_profile_model_identity IS
    'Authoritative configured embedding-model identity; built-in local models may have model_id zero.';
COMMENT ON TABLE public.adi_knowledge_base_route_profile_set IS
    'Immutable build/release record for one generation of a knowledge-base route profile.';
COMMENT ON TABLE public.adi_knowledge_base_route_profile IS
    'Bounded overview/document/topic vectors used only by the preflight route gate, never as answer evidence.';
COMMENT ON COLUMN public.adi_knowledge_base_route_profile.profile_embedding IS
    'Dimension-agnostic float4 array persisted as the source of truth; online comparisons use the Redis copy.';

COMMIT;

-- Optional post-checks for the IDEA result pane:
SELECT column_name, data_type, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'adi_knowledge_base'
  AND column_name LIKE 'route_profile%'
ORDER BY ordinal_position;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
      'adi_knowledge_base_route_profile_set',
      'adi_knowledge_base_route_profile'
  )
ORDER BY table_name;
