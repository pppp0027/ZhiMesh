-- Persist answer-level BM25 provenance so keyword hits remain inspectable in chat history.

\set ON_ERROR_STOP on

BEGIN;

ALTER TABLE public.adi_character_message
    ADD COLUMN IF NOT EXISTS is_ref_bm25 boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS public.adi_character_message_ref_bm25 (
    id               bigserial PRIMARY KEY,
    message_id       bigint           NOT NULL DEFAULT 0,
    query_terms      text             NOT NULL DEFAULT '[]',
    chunk_uuid       varchar(32)      NOT NULL DEFAULT '',
    kb_uuid          varchar(32)      NOT NULL DEFAULT '',
    kb_item_uuid     varchar(32)      NOT NULL DEFAULT '',
    content_snapshot text             NOT NULL DEFAULT '',
    score            double precision NOT NULL DEFAULT 0,
    hit_rank         integer          NOT NULL DEFAULT 0,
    user_id          bigint           NOT NULL DEFAULT 0,
    CONSTRAINT uq_character_message_ref_bm25_message_chunk UNIQUE (message_id, chunk_uuid),
    CONSTRAINT ck_character_message_ref_bm25_rank CHECK (hit_rank > 0)
);

CREATE INDEX IF NOT EXISTS idx_character_message_ref_bm25_message
    ON public.adi_character_message_ref_bm25(message_id, hit_rank);

COMMENT ON TABLE public.adi_character_message_ref_bm25 IS
    '回答实际使用的 BM25 关键词检索命中溯源；保存查询词、分块快照、分数和排名。 | Answer-level BM25 provenance with query terms, chunk snapshot, score, and rank.';
COMMENT ON COLUMN public.adi_character_message_ref_bm25.query_terms IS
    'JSON array of tokenizer terms actually submitted to BM25 retrieval';
COMMENT ON COLUMN public.adi_character_message_ref_bm25.content_snapshot IS
    'Matched canonical chunk content copied at answer time for durable provenance';
COMMENT ON COLUMN public.adi_character_message_ref_bm25.score IS
    'Raw Okapi BM25 score returned at retrieval time';

COMMIT;
