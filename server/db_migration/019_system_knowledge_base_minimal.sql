-- Phase 1: additive system knowledge-base capability.
-- This migration intentionally performs no DELETE, UPDATE, or data rewrite.
ALTER TABLE IF EXISTS adi_knowledge_base
    ADD COLUMN IF NOT EXISTS is_system boolean NOT NULL DEFAULT false;

ALTER TABLE IF EXISTS adi_knowledge_base
    ADD COLUMN IF NOT EXISTS is_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE IF EXISTS adi_character_preset
    ADD COLUMN IF NOT EXISTS system_kb_ids varchar(1000) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_adi_knowledge_base_system_enabled
    ON adi_knowledge_base (is_system, is_enabled, is_deleted);

COMMENT ON COLUMN adi_knowledge_base.is_system IS
    'Admin-managed knowledge base that can be bound to system character presets';
COMMENT ON COLUMN adi_knowledge_base.is_enabled IS
    'Disabled system knowledge bases remain stored but are excluded from role retrieval';
COMMENT ON COLUMN adi_character_preset.system_kb_ids IS
    'Comma-separated enabled system knowledge-base IDs; legacy kb_title remains as fallback';
