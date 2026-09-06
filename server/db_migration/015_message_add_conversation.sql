ALTER TABLE adi_character_message
    ADD COLUMN IF NOT EXISTS conversation_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS conversation_uuid varchar(32) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_character_message_conversation_page
    ON adi_character_message (conversation_id, parent_message_id, id DESC)
    WHERE is_deleted = false;

COMMENT ON COLUMN adi_character_message.conversation_id IS 'Owning adi_conversation.id; 0 denotes legacy unassigned data';
COMMENT ON COLUMN adi_character_message.conversation_uuid IS 'Owning conversation UUID, denormalized for compatibility and audit';

CREATE TABLE IF NOT EXISTS adi_conversation_backfill (
    id bigserial PRIMARY KEY,
    uuid varchar(32) NOT NULL,
    user_id bigint NOT NULL,
    character_id bigint NOT NULL,
    conversation_id bigint NOT NULL,
    high_water_message_id bigint NOT NULL DEFAULT 0,
    last_processed_message_id bigint NOT NULL DEFAULT 0,
    scanned_count bigint NOT NULL DEFAULT 0,
    updated_count bigint NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    error_message text,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_conversation_backfill_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_backfill_uuid
    ON adi_conversation_backfill (uuid);
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_backfill_character
    ON adi_conversation_backfill (user_id, character_id)
    WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_conversation_backfill_recovery
    ON adi_conversation_backfill (status, update_time)
    WHERE is_deleted = false;

DROP TRIGGER IF EXISTS trigger_conversation_backfill_update_time ON adi_conversation_backfill;
CREATE TRIGGER trigger_conversation_backfill_update_time
    BEFORE UPDATE ON adi_conversation_backfill
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
