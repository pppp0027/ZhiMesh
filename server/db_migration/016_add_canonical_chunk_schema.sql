CREATE TABLE IF NOT EXISTS adi_knowledge_base_chunk_set (
    id bigserial PRIMARY KEY,
    uuid varchar(32) NOT NULL,
    kb_id bigint NOT NULL,
    kb_uuid varchar(32) NOT NULL,
    kb_item_id bigint NOT NULL,
    kb_item_uuid varchar(32) NOT NULL,
    source_content_hash varchar(64) NOT NULL,
    split_strategy varchar(32) NOT NULL,
    max_segment_size integer NOT NULL,
    overlap integer NOT NULL,
    custom_separator text NOT NULL DEFAULT '',
    token_estimator varchar(32) NOT NULL,
    splitter_version varchar(64) NOT NULL,
    preprocessor_version varchar(64) NOT NULL,
    split_config_hash varchar(64) NOT NULL,
    chunk_count integer NOT NULL DEFAULT 0,
    total_tokens integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL,
    is_active boolean NOT NULL DEFAULT false,
    error_type varchar(128),
    error_message text,
    started_at timestamp,
    completed_at timestamp,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_kb_chunk_set_active_status CHECK ((status = 'ACTIVE') = is_active)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk_set_uuid ON adi_knowledge_base_chunk_set(uuid);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_set_item_active ON adi_knowledge_base_chunk_set(kb_item_uuid, is_active, is_deleted);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk_set_source_config
    ON adi_knowledge_base_chunk_set(kb_item_uuid, source_content_hash, split_config_hash)
    WHERE is_deleted = false;
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk_set_active_item
    ON adi_knowledge_base_chunk_set(kb_item_uuid)
    WHERE is_active = true AND is_deleted = false;

CREATE TABLE IF NOT EXISTS adi_knowledge_base_chunk (
    id bigserial PRIMARY KEY,
    uuid varchar(32) NOT NULL,
    chunk_set_id bigint NOT NULL,
    chunk_set_uuid varchar(32) NOT NULL,
    kb_id bigint NOT NULL,
    kb_uuid varchar(32) NOT NULL,
    kb_item_id bigint NOT NULL,
    kb_item_uuid varchar(32) NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    content_hash varchar(64) NOT NULL,
    token_count integer NOT NULL,
    char_start integer,
    char_end integer,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk_uuid ON adi_knowledge_base_chunk(uuid);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk_set_index
    ON adi_knowledge_base_chunk(chunk_set_uuid, chunk_index) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_item ON adi_knowledge_base_chunk(kb_item_uuid, chunk_set_uuid);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_content_hash ON adi_knowledge_base_chunk(content_hash);

CREATE TABLE IF NOT EXISTS adi_knowledge_base_index_build (
    id bigserial PRIMARY KEY,
    uuid varchar(32) NOT NULL,
    kb_id bigint NOT NULL,
    kb_uuid varchar(32) NOT NULL,
    kb_item_id bigint NOT NULL,
    kb_item_uuid varchar(32) NOT NULL,
    chunk_set_uuid varchar(32) NOT NULL,
    index_type varchar(16) NOT NULL,
    model_id bigint NOT NULL,
    model_identity varchar(255) NOT NULL,
    build_key_hash varchar(64) NOT NULL,
    prompt_version varchar(64) NOT NULL DEFAULT '',
    graph_release_uuid varchar(32) NOT NULL DEFAULT '',
    graph_namespace varchar(128) NOT NULL DEFAULT '',
    status varchar(16) NOT NULL,
    attempt integer NOT NULL DEFAULT 1,
    is_active boolean NOT NULL DEFAULT false,
    error_type varchar(128),
    error_message text,
    started_at timestamp,
    completed_at timestamp,
    activated_at timestamp,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_kb_index_build_type CHECK (index_type IN ('EMBEDDING', 'GRAPH')),
    CONSTRAINT ck_kb_index_build_graph_scope CHECK (
        (index_type = 'GRAPH' AND graph_release_uuid <> '' AND graph_namespace <> '') OR
        (index_type = 'EMBEDDING' AND graph_release_uuid = '' AND graph_namespace = '')
    ),
    CONSTRAINT ck_kb_index_build_active_status CHECK ((status = 'ACTIVE') = is_active)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_index_build_uuid ON adi_knowledge_base_index_build(uuid);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_index_build_active
    ON adi_knowledge_base_index_build(kb_item_uuid, index_type) WHERE is_active = true AND is_deleted = false;
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_index_build_inflight_target
    ON adi_knowledge_base_index_build(kb_item_uuid, index_type, build_key_hash)
    WHERE status IN ('PENDING', 'BUILDING', 'READY', 'ACTIVE') AND is_deleted = false;
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_graph_release_item_build
    ON adi_knowledge_base_index_build(graph_release_uuid, kb_item_uuid)
    WHERE index_type = 'GRAPH' AND is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_kb_index_build_recovery
    ON adi_knowledge_base_index_build(status, update_time) WHERE is_deleted = false;

CREATE TABLE IF NOT EXISTS adi_knowledge_base_graph_release (
    id bigserial PRIMARY KEY,
    uuid varchar(32) NOT NULL,
    kb_id bigint NOT NULL,
    kb_uuid varchar(32) NOT NULL,
    namespace varchar(128) NOT NULL,
    base_release_uuid varchar(32) NOT NULL DEFAULT '',
    manifest_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    expected_item_count integer NOT NULL DEFAULT 0,
    ready_item_count integer NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT false,
    started_at timestamp,
    completed_at timestamp,
    activated_at timestamp,
    error_message text,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_kb_graph_release_active_status CHECK ((status = 'ACTIVE') = is_active)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_graph_release_uuid ON adi_knowledge_base_graph_release(uuid);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_graph_release_namespace ON adi_knowledge_base_graph_release(namespace);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_graph_release_active
    ON adi_knowledge_base_graph_release(kb_uuid) WHERE is_active = true AND is_deleted = false;

ALTER TABLE adi_knowledge_base_item
    ADD COLUMN IF NOT EXISTS active_chunk_set_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS embedding_chunk_set_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS graphical_chunk_set_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS embedding_model_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS graphical_model_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS embedding_started_at timestamp,
    ADD COLUMN IF NOT EXISTS embedding_completed_at timestamp,
    ADD COLUMN IF NOT EXISTS graphical_started_at timestamp,
    ADD COLUMN IF NOT EXISTS graphical_completed_at timestamp;

ALTER TABLE adi_knowledge_base_graph_segment
    ADD COLUMN IF NOT EXISTS chunk_set_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS chunk_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS graph_model_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS graph_index_version_uuid varchar(32) NOT NULL DEFAULT '';

ALTER TABLE adi_knowledge_base_graph_element_source
    ADD COLUMN IF NOT EXISTS chunk_set_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS chunk_uuid varchar(32) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS graph_model_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS graph_index_version_uuid varchar(32) NOT NULL DEFAULT '';

DROP TRIGGER IF EXISTS trigger_kb_chunk_set_update_time ON adi_knowledge_base_chunk_set;
CREATE TRIGGER trigger_kb_chunk_set_update_time BEFORE UPDATE ON adi_knowledge_base_chunk_set
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
DROP TRIGGER IF EXISTS trigger_kb_chunk_update_time ON adi_knowledge_base_chunk;
CREATE TRIGGER trigger_kb_chunk_update_time BEFORE UPDATE ON adi_knowledge_base_chunk
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
DROP TRIGGER IF EXISTS trigger_kb_index_build_update_time ON adi_knowledge_base_index_build;
CREATE TRIGGER trigger_kb_index_build_update_time BEFORE UPDATE ON adi_knowledge_base_index_build
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
DROP TRIGGER IF EXISTS trigger_kb_graph_release_update_time ON adi_knowledge_base_graph_release;
CREATE TRIGGER trigger_kb_graph_release_update_time BEFORE UPDATE ON adi_knowledge_base_graph_release
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
