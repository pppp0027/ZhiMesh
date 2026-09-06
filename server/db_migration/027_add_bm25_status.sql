-- Expose the per-item BM25/FULLTEXT lifecycle beside vector and graph status.
\set ON_ERROR_STOP on

BEGIN;

ALTER TABLE public.adi_knowledge_base_item
    ADD COLUMN IF NOT EXISTS fulltext_status integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS fulltext_status_change_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS fulltext_started_at timestamp,
    ADD COLUMN IF NOT EXISTS fulltext_completed_at timestamp;

COMMENT ON COLUMN public.adi_knowledge_base_item.fulltext_status IS
    'BM25/FULLTEXT status: 1=Not indexed, 2=Indexing, 3=Indexed, 4=Failed';
COMMENT ON COLUMN public.adi_knowledge_base_item.fulltext_status_change_time IS
    'Last BM25/FULLTEXT status change time';
COMMENT ON COLUMN public.adi_knowledge_base_item.fulltext_started_at IS
    'BM25/FULLTEXT build start time';
COMMENT ON COLUMN public.adi_knowledge_base_item.fulltext_completed_at IS
    'BM25/FULLTEXT build completion or failure time';

COMMIT;
