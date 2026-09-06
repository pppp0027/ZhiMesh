ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS rerank_model_id bigint NOT NULL DEFAULT 0;

ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS rerank_top_n integer NOT NULL DEFAULT 5;

ALTER TABLE adi_knowledge_base
    ADD CONSTRAINT chk_adi_knowledge_base_rerank_top_n
    CHECK (rerank_top_n BETWEEN 1 AND 10);

COMMENT ON COLUMN adi_knowledge_base.rerank_model_id IS
    'Optional rerank model ID; configure bge-reranker-base here';

COMMENT ON COLUMN adi_knowledge_base.rerank_top_n IS
    'Maximum contexts kept after reranking';