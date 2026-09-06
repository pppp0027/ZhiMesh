-- Store one reversible description/weight contribution per graph segment.
-- adi_knowledge_base_graph_element_source becomes the source of truth used to
-- rebuild shared AGE/Neo4j element properties when a document is re-indexed.
ALTER TABLE adi_knowledge_base_graph_element_source
    ADD COLUMN IF NOT EXISTS contribution_description text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS contribution_weight double precision;

COMMENT ON COLUMN adi_knowledge_base_graph_element_source.contribution_description
    IS 'Description contributed by this graph segment; used to rebuild shared graph elements';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.contribution_weight
    IS 'Relationship strength contributed by this graph segment; null for vertices';
