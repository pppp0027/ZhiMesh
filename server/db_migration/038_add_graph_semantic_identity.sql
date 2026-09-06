-- Preserve semantic identity and structured extraction contributions so graph
-- elements can be rebuilt correctly when one document is re-indexed or removed.
ALTER TABLE adi_knowledge_base_graph_element_source
    ADD COLUMN IF NOT EXISTS contribution_canonical_name varchar(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS contribution_aliases text NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS contribution_properties text NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS contribution_salience double precision,
    ADD COLUMN IF NOT EXISTS relation_type varchar(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS relation_polarity boolean,
    ADD COLUMN IF NOT EXISTS relation_status varchar(32) NOT NULL DEFAULT '';

COMMENT ON COLUMN adi_knowledge_base_graph_element_source.contribution_canonical_name
    IS 'Source-backed canonical entity name; empty for relationship contributions';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.contribution_aliases
    IS 'JSON array of source-backed entity aliases';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.contribution_properties
    IS 'JSON object of entity or relationship properties';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.contribution_salience
    IS 'Entity salience from 1 to 10; null for relationship contributions';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.relation_type
    IS 'Normalized uppercase SNAKE_CASE relationship predicate';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.relation_polarity
    IS 'True for positive assertions and false for explicitly negated relationships';
COMMENT ON COLUMN adi_knowledge_base_graph_element_source.relation_status
    IS 'ASSERTED, PLANNED, PROPOSED, HISTORICAL, DISPUTED or CONDITIONAL';
