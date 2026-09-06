-- Built-in local embedding models are configuration-owned and may have no adi_ai_model row.
-- Keep model_id as optional metadata and use the configured identity as the compatibility fence.

BEGIN;

ALTER TABLE public.adi_knowledge_base
    ADD COLUMN IF NOT EXISTS route_profile_model_identity varchar(255) NOT NULL DEFAULT '';

ALTER TABLE public.adi_knowledge_base
    DROP CONSTRAINT IF EXISTS ck_kb_route_profile_ready;

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

COMMENT ON COLUMN public.adi_knowledge_base.route_profile_model_identity IS
    'Authoritative configured embedding-model identity; built-in local models may have model_id zero.';

COMMIT;

SELECT column_name, data_type, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'adi_knowledge_base'
  AND column_name IN ('route_profile_model_id', 'route_profile_model_identity')
ORDER BY column_name;
