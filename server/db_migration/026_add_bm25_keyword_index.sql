-- PostgreSQL-backed BM25 storage over canonical chunks.
-- Ranking is implemented by the application; this migration intentionally does
-- not use PostgreSQL ts_rank/ts_rank_cd.

\set ON_ERROR_STOP on

BEGIN;

ALTER TABLE public.adi_knowledge_base_item
    ADD COLUMN IF NOT EXISTS fulltext_chunk_set_uuid varchar(32) NOT NULL DEFAULT '';

ALTER TABLE public.adi_knowledge_base_index_build
    DROP CONSTRAINT IF EXISTS ck_kb_index_build_type;
ALTER TABLE public.adi_knowledge_base_index_build
    DROP CONSTRAINT IF EXISTS ck_kb_index_build_graph_scope;
ALTER TABLE public.adi_knowledge_base_index_build
    ADD CONSTRAINT ck_kb_index_build_type
        CHECK (index_type IN ('EMBEDDING', 'GRAPH', 'FULLTEXT')),
    ADD CONSTRAINT ck_kb_index_build_graph_scope CHECK (
        (index_type = 'GRAPH' AND graph_release_uuid <> '' AND graph_namespace <> '') OR
        (index_type IN ('EMBEDDING', 'FULLTEXT')
            AND graph_release_uuid = '' AND graph_namespace = '')
    );

CREATE TABLE IF NOT EXISTS public.adi_knowledge_base_bm25_document (
    chunk_uuid varchar(32) PRIMARY KEY,
    index_build_uuid varchar(32) NOT NULL,
    analyzer_version varchar(64) NOT NULL,
    kb_uuid varchar(32) NOT NULL,
    kb_item_uuid varchar(32) NOT NULL,
    chunk_set_uuid varchar(32) NOT NULL,
    document_length integer NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_kb_bm25_document_length CHECK (document_length >= 0)
);

CREATE INDEX IF NOT EXISTS idx_kb_bm25_document_build
    ON public.adi_knowledge_base_bm25_document(index_build_uuid);
CREATE INDEX IF NOT EXISTS idx_kb_bm25_document_kb_chunk_set
    ON public.adi_knowledge_base_bm25_document(kb_uuid, chunk_set_uuid);
CREATE INDEX IF NOT EXISTS idx_kb_bm25_document_item_chunk_set
    ON public.adi_knowledge_base_bm25_document(kb_item_uuid, chunk_set_uuid);

CREATE TABLE IF NOT EXISTS public.adi_knowledge_base_bm25_posting (
    index_build_uuid varchar(32) NOT NULL,
    chunk_uuid varchar(32) NOT NULL,
    term varchar(512) NOT NULL,
    term_frequency integer NOT NULL,
    CONSTRAINT pk_kb_bm25_posting
        PRIMARY KEY (index_build_uuid, term, chunk_uuid),
    CONSTRAINT ck_kb_bm25_posting_frequency CHECK (term_frequency > 0)
);

CREATE INDEX IF NOT EXISTS idx_kb_bm25_posting_term
    ON public.adi_knowledge_base_bm25_posting(term, index_build_uuid, chunk_uuid);
CREATE INDEX IF NOT EXISTS idx_kb_bm25_posting_chunk
    ON public.adi_knowledge_base_bm25_posting(chunk_uuid);

DROP TRIGGER IF EXISTS trigger_kb_bm25_document_update_time
    ON public.adi_knowledge_base_bm25_document;
CREATE TRIGGER trigger_kb_bm25_document_update_time
    BEFORE UPDATE ON public.adi_knowledge_base_bm25_document
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();

COMMENT ON TABLE public.adi_knowledge_base_bm25_document IS
    'BM25 document statistics keyed by canonical chunk UUID; content remains in adi_knowledge_base_chunk';
COMMENT ON TABLE public.adi_knowledge_base_bm25_posting IS
    'BM25 term frequency postings for one FULLTEXT index build and canonical chunk';

COMMIT;
