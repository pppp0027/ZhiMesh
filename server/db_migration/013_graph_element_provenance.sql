CREATE TABLE IF NOT EXISTS adi_knowledge_base_graph_element_source (
    id bigserial PRIMARY KEY,
    kb_uuid varchar(32) NOT NULL,
    kb_item_uuid varchar(32) NOT NULL,
    graph_segment_uuid varchar(32) NOT NULL,
    element_type varchar(16) NOT NULL CHECK (element_type IN ('vertex', 'edge')),
    element_id varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false,
    UNIQUE (kb_item_uuid, graph_segment_uuid, element_type, element_id)
);
CREATE INDEX IF NOT EXISTS idx_kb_graph_element_source_item ON adi_knowledge_base_graph_element_source (kb_item_uuid, element_type);
CREATE INDEX IF NOT EXISTS idx_kb_graph_element_source_element ON adi_knowledge_base_graph_element_source (element_type, element_id);
CREATE TRIGGER trigger_kb_graph_element_source_update_time BEFORE UPDATE ON adi_knowledge_base_graph_element_source FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
