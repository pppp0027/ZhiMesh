-- Read-only schema verification for the current application version.
-- Run after all_ddl.sql or after incremental migrations 001 through 044.

WITH required_tables(table_name) AS (
    VALUES
        ('adi_agent_pending_checkpoint'),
        ('adi_ai_model'),
        ('adi_character'),
        ('adi_character_message'),
        ('adi_character_message_ref_embedding'),
        ('adi_character_message_ref_graph'),
        ('adi_character_message_ref_memory_embedding'),
        ('adi_character_message_ref_bm25'),
        ('adi_character_message_tool_call'),
        ('adi_character_preset'),
        ('adi_character_preset_rel'),
        ('adi_conversation'),
        ('adi_conversation_backfill'),
        ('adi_draw'),
        ('adi_draw_comment'),
        ('adi_draw_star'),
        ('adi_file'),
        ('adi_knowledge_base'),
        ('adi_knowledge_base_bm25_document'),
        ('adi_knowledge_base_bm25_posting'),
        ('adi_knowledge_base_chunk'),
        ('adi_knowledge_base_chunk_set'),
        ('adi_knowledge_base_graph_element_source'),
        ('adi_knowledge_base_graph_release'),
        ('adi_knowledge_base_graph_segment'),
        ('adi_knowledge_base_index_build'),
        ('adi_knowledge_base_item'),
        ('adi_knowledge_base_qa'),
        ('adi_knowledge_base_qa_ref_embedding'),
        ('adi_knowledge_base_qa_ref_graph'),
        ('adi_knowledge_base_route_profile'),
        ('adi_knowledge_base_route_profile_set'),
        ('adi_knowledge_base_star'),
        ('adi_llm_call_record'),
        ('adi_mcp'),
        ('adi_model_platform'),
        ('adi_openrouter_model_state'),
        ('adi_openrouter_sync_run'),
        ('adi_prompt'),
        ('adi_sys_config'),
        ('adi_team'),
        ('adi_team_member'),
        ('adi_user'),
        ('adi_user_day_cost'),
        ('adi_user_ext_api_key'),
        ('adi_user_mcp'),
        ('adi_workflow'),
        ('adi_workflow_component'),
        ('adi_workflow_edge'),
        ('adi_workflow_node'),
        ('adi_workflow_runtime'),
        ('adi_workflow_runtime_node')
), missing AS (
    SELECT r.table_name
    FROM required_tables r
    LEFT JOIN information_schema.tables t
      ON t.table_schema = 'public' AND t.table_name = r.table_name
    WHERE t.table_name IS NULL
)
SELECT count(*) AS missing_required_table_count,
       coalesce(string_agg(table_name, ', ' ORDER BY table_name), '') AS missing_required_tables
FROM missing;

WITH required_indexes(index_name) AS (
    VALUES
        ('idx_wf_runtime_open_update_time'),
        ('idx_wf_runtime_workflow_user_update'),
        ('idx_wf_runtime_workflow_update'),
        ('idx_wf_runtime_node_runtime_id'),
        ('uk_ai_model_platform_name'),
        ('uk_openrouter_model_state_model_id'),
        ('uk_team_uuid'),
        ('uk_team_member')
), missing AS (
    SELECT required.index_name
    FROM required_indexes required
    LEFT JOIN pg_indexes installed
      ON installed.schemaname = 'public'
     AND installed.indexname = required.index_name
    WHERE installed.indexname IS NULL
)
SELECT count(*) AS missing_required_index_count,
       coalesce(string_agg(index_name, ', ' ORDER BY index_name), '') AS missing_required_indexes
FROM missing;

WITH removed_columns(table_name, column_name) AS (
    VALUES
        ('adi_model_platform', 'secret_key'),
        ('adi_ai_model', 'setting'),
        ('adi_ai_model', 'context_window'),
        ('adi_ai_model', 'max_output_tokens')
)
SELECT count(*) AS legacy_model_column_count,
       coalesce(string_agg(r.table_name || '.' || r.column_name, ', '
                           ORDER BY r.table_name, r.column_name), '') AS legacy_model_columns
FROM removed_columns r
JOIN information_schema.columns c
  ON c.table_schema = 'public'
 AND c.table_name = r.table_name
 AND c.column_name = r.column_name;

WITH required_columns(table_name, column_name) AS (
    VALUES
        ('adi_user', 'avatar'),
        ('adi_knowledge_base_item', 'fulltext_chunk_set_uuid'),
        ('adi_knowledge_base_item', 'fulltext_status'),
        ('adi_knowledge_base_item', 'fulltext_status_change_time'),
        ('adi_knowledge_base_item', 'fulltext_started_at'),
        ('adi_knowledge_base_item', 'fulltext_completed_at'),
        ('adi_character', 'is_agentic'),
        ('adi_character', 'tool_policy'),
        ('adi_character_message', 'is_ref_bm25'),
        ('adi_character_preset', 'tool_policy'),
        ('adi_knowledge_base', 'route_profile_status'),
        ('adi_knowledge_base', 'route_profile_generation'),
        ('adi_knowledge_base', 'route_profile_active_generation'),
        ('adi_knowledge_base', 'route_profile_set_uuid'),
        ('adi_knowledge_base', 'route_profile_model_identity'),
        ('adi_knowledge_base', 'owner_type'),
        ('adi_knowledge_base', 'team_id'),
        ('adi_knowledge_base', 'company_scope')
), missing AS (
    SELECT r.table_name, r.column_name
    FROM required_columns r
    LEFT JOIN information_schema.columns c
      ON c.table_schema = 'public'
     AND c.table_name = r.table_name
     AND c.column_name = r.column_name
    WHERE c.column_name IS NULL
)
SELECT count(*) AS missing_required_column_count,
       coalesce(string_agg(table_name || '.' || column_name, ', ' ORDER BY table_name, column_name), '')
           AS missing_required_columns
FROM missing;

SELECT count(*) AS invalid_model_identity_column_count,
       coalesce(string_agg(column_name || '=' || coalesce(character_maximum_length::text, 'null'), ', '
                           ORDER BY column_name), '') AS invalid_model_identity_columns
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'adi_ai_model'
  AND column_name IN ('name', 'title')
  AND character_maximum_length IS DISTINCT FROM 255;

SELECT extname AS installed_extension, extversion
FROM pg_extension
WHERE extname IN ('vector', 'age')
ORDER BY extname;

SELECT table_name AS runtime_embedding_table
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name LIKE 'zhimesh\_%\_embedding%' ESCAPE '\'
ORDER BY table_name;
