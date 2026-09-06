CREATE TABLE IF NOT EXISTS adi_conversation (
    id bigserial PRIMARY KEY,
    uuid varchar(32) NOT NULL,
    user_id bigint NOT NULL,
    character_id bigint NOT NULL,
    title varchar(100) NOT NULL DEFAULT '',
    status smallint NOT NULL DEFAULT 1,
    is_default boolean NOT NULL DEFAULT false,
    last_message_time timestamp,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_uuid
    ON adi_conversation (uuid);
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_default_character
    ON adi_conversation (user_id, character_id)
    WHERE is_default = true AND is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_conversation_user_update_time
    ON adi_conversation (user_id, update_time DESC)
    WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_conversation_character_user
    ON adi_conversation (character_id, user_id)
    WHERE is_deleted = false;

COMMENT ON TABLE adi_conversation IS 'Independent chat session under a persistent Character';
COMMENT ON COLUMN adi_conversation.is_default IS 'The unique default chat session for one user under one Character';
COMMENT ON COLUMN adi_conversation.status IS 'Conversation status: 1=active, 2=archived';

DROP TRIGGER IF EXISTS trigger_conversation_update_time ON adi_conversation;
CREATE TRIGGER trigger_conversation_update_time
    BEFORE UPDATE ON adi_conversation
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
