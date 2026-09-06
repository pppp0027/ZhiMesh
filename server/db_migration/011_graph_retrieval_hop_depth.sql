ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS graph_hop_depth integer NOT NULL DEFAULT 1;

ALTER TABLE adi_knowledge_base
    ADD CONSTRAINT chk_adi_knowledge_base_graph_hop_depth
    CHECK (graph_hop_depth IN (1, 2));

COMMENT ON COLUMN adi_knowledge_base.graph_hop_depth IS
    'Knowledge graph retrieval traversal depth: 1=direct neighbors, 2=second-hop neighbors';
