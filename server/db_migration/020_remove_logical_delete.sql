-- Migration: permanently purge soft-deleted rows and remove the is_deleted mechanism.
--
-- Preconditions (must be completed before this file is executed):
--   1. The application and all async workers are stopped.
--   2. A verified pg_dump/pg_restore recovery rehearsal has passed.
--   3. The preflight in docs/database/physical-delete-migration-runbook.zh-CN.md
--      has been rerun against the target database.
--
-- Execute only with psql, so that ON_ERROR_STOP prevents partial execution:
--   psql -v ON_ERROR_STOP=1 -f 020_remove_logical_delete.sql
--
-- This migration is intentionally irreversible: every row currently marked
-- is_deleted = true is permanently deleted.

\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '10min';

-- The target database was preflighted on 2026-08-09: it declares no foreign
-- keys, so the following fixed order cannot violate database FK constraints.
-- Do not run this script if a new preflight finds foreign keys.
DELETE FROM public.adi_ai_model WHERE is_deleted = true;
DELETE FROM public.adi_character WHERE is_deleted = true;
DELETE FROM public.adi_character_message WHERE is_deleted = true;
DELETE FROM public.adi_character_preset WHERE is_deleted = true;
DELETE FROM public.adi_character_preset_rel WHERE is_deleted = true;
DELETE FROM public.adi_conversation WHERE is_deleted = true;
DELETE FROM public.adi_conversation_backfill WHERE is_deleted = true;
DELETE FROM public.adi_draw WHERE is_deleted = true;
DELETE FROM public.adi_draw_comment WHERE is_deleted = true;
DELETE FROM public.adi_draw_star WHERE is_deleted = true;
DELETE FROM public.adi_file WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_chunk WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_chunk_set WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_graph_element_source WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_graph_release WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_graph_segment WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_index_build WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_item WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_qa WHERE is_deleted = true;
DELETE FROM public.adi_knowledge_base_star WHERE is_deleted = true;
DELETE FROM public.adi_llm_call_record WHERE is_deleted = true;
DELETE FROM public.adi_mcp WHERE is_deleted = true;
DELETE FROM public.adi_model_platform WHERE is_deleted = true;
DELETE FROM public.adi_prompt WHERE is_deleted = true;
DELETE FROM public.adi_sys_config WHERE is_deleted = true;
DELETE FROM public.adi_user WHERE is_deleted = true;
DELETE FROM public.adi_user_day_cost WHERE is_deleted = true;
DELETE FROM public.adi_user_ext_api_key WHERE is_deleted = true;
DELETE FROM public.adi_user_mcp WHERE is_deleted = true;
DELETE FROM public.adi_workflow WHERE is_deleted = true;
DELETE FROM public.adi_workflow_component WHERE is_deleted = true;
DELETE FROM public.adi_workflow_edge WHERE is_deleted = true;
DELETE FROM public.adi_workflow_node WHERE is_deleted = true;
DELETE FROM public.adi_workflow_runtime WHERE is_deleted = true;
DELETE FROM public.adi_workflow_runtime_node WHERE is_deleted = true;

-- Drop the column in every actual target table. PostgreSQL automatically drops
-- indexes and constraints that depend on the dropped column; their replacement
-- definitions are recreated below.
ALTER TABLE public.adi_ai_model DROP COLUMN is_deleted;
ALTER TABLE public.adi_character DROP COLUMN is_deleted;
ALTER TABLE public.adi_character_message DROP COLUMN is_deleted;
ALTER TABLE public.adi_character_preset DROP COLUMN is_deleted;
ALTER TABLE public.adi_character_preset_rel DROP COLUMN is_deleted;
ALTER TABLE public.adi_conversation DROP COLUMN is_deleted;
ALTER TABLE public.adi_conversation_backfill DROP COLUMN is_deleted;
ALTER TABLE public.adi_draw DROP COLUMN is_deleted;
ALTER TABLE public.adi_draw_comment DROP COLUMN is_deleted;
ALTER TABLE public.adi_draw_star DROP COLUMN is_deleted;
ALTER TABLE public.adi_file DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_chunk DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_chunk_set DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_graph_element_source DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_graph_release DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_graph_segment DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_index_build DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_item DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_qa DROP COLUMN is_deleted;
ALTER TABLE public.adi_knowledge_base_star DROP COLUMN is_deleted;
ALTER TABLE public.adi_llm_call_record DROP COLUMN is_deleted;
ALTER TABLE public.adi_mcp DROP COLUMN is_deleted;
ALTER TABLE public.adi_model_platform DROP COLUMN is_deleted;
ALTER TABLE public.adi_prompt DROP COLUMN is_deleted;
ALTER TABLE public.adi_sys_config DROP COLUMN is_deleted;
ALTER TABLE public.adi_user DROP COLUMN is_deleted;
ALTER TABLE public.adi_user_day_cost DROP COLUMN is_deleted;
ALTER TABLE public.adi_user_ext_api_key DROP COLUMN is_deleted;
ALTER TABLE public.adi_user_mcp DROP COLUMN is_deleted;
ALTER TABLE public.adi_workflow DROP COLUMN is_deleted;
ALTER TABLE public.adi_workflow_component DROP COLUMN is_deleted;
ALTER TABLE public.adi_workflow_edge DROP COLUMN is_deleted;
ALTER TABLE public.adi_workflow_node DROP COLUMN is_deleted;
ALTER TABLE public.adi_workflow_runtime DROP COLUMN is_deleted;
ALTER TABLE public.adi_workflow_runtime_node DROP COLUMN is_deleted;

-- Recreate every index/constraint discovered by the preflight that depended on
-- is_deleted, preserving the same business meaning without logical deletion.
CREATE INDEX idx_character_message_conversation_page
    ON public.adi_character_message (conversation_id, parent_message_id, id DESC);
CREATE INDEX idx_conversation_character_user
    ON public.adi_conversation (character_id, user_id);
CREATE INDEX idx_conversation_user_update_time
    ON public.adi_conversation (user_id, update_time DESC);
CREATE UNIQUE INDEX uk_conversation_default_character
    ON public.adi_conversation (user_id, character_id) WHERE is_default = true;
CREATE INDEX idx_conversation_backfill_recovery
    ON public.adi_conversation_backfill (status, update_time);
CREATE UNIQUE INDEX uk_conversation_backfill_character
    ON public.adi_conversation_backfill (user_id, character_id);
CREATE INDEX idx_adi_knowledge_base_system_enabled
    ON public.adi_knowledge_base (is_system, is_enabled);
CREATE UNIQUE INDEX uk_kb_chunk_set_index
    ON public.adi_knowledge_base_chunk (chunk_set_uuid, chunk_index);
CREATE INDEX idx_kb_chunk_set_item_active
    ON public.adi_knowledge_base_chunk_set (kb_item_uuid, is_active);
CREATE UNIQUE INDEX uk_kb_chunk_set_active_item
    ON public.adi_knowledge_base_chunk_set (kb_item_uuid) WHERE is_active = true;
CREATE UNIQUE INDEX uk_kb_chunk_set_source_config
    ON public.adi_knowledge_base_chunk_set (kb_item_uuid, source_content_hash, split_config_hash);
CREATE UNIQUE INDEX uk_kb_graph_release_active
    ON public.adi_knowledge_base_graph_release (kb_uuid) WHERE is_active = true;
CREATE INDEX idx_kb_index_build_recovery
    ON public.adi_knowledge_base_index_build (status, update_time);
CREATE UNIQUE INDEX uk_kb_graph_release_item_build
    ON public.adi_knowledge_base_index_build (graph_release_uuid, kb_item_uuid)
    WHERE index_type = 'GRAPH';
CREATE UNIQUE INDEX uk_kb_index_build_active
    ON public.adi_knowledge_base_index_build (kb_item_uuid, index_type) WHERE is_active = true;
CREATE UNIQUE INDEX uk_kb_index_build_inflight_target
    ON public.adi_knowledge_base_index_build (kb_item_uuid, index_type, build_key_hash)
    WHERE status IN ('PENDING', 'BUILDING', 'READY', 'ACTIVE');
CREATE UNIQUE INDEX udx_user_resource_type
    ON public.adi_user_ext_api_key (user_id, resource_type);
ALTER TABLE public.adi_workflow_component
    ADD CONSTRAINT uk_workflow_component_name UNIQUE (name);

COMMIT;

-- Run only after the transaction has committed successfully. It refreshes
-- planner statistics and makes dead space reusable; it does not shrink table
-- files immediately.
VACUUM (ANALYZE);
