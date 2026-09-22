-- Three-tier knowledge-base ownership: personal, team and company knowledge bases.
-- Adds the team tables (adi_team, adi_team_member) and the ownership columns on
-- adi_knowledge_base. Existing rows become PERSONAL ownership; no data backfill.

CREATE TABLE IF NOT EXISTS adi_team
(
    id          bigserial primary key,
    uuid        varchar(32)  default ''                not null,
    name        varchar(100) default ''                not null,
    remark      varchar(500) default ''                not null,
    creator_id  bigint       default 0                 not null,
    create_time timestamp    default CURRENT_TIMESTAMP not null,
    update_time timestamp    default CURRENT_TIMESTAMP not null,
    constraint ck_team_name_not_empty check (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_team_uuid ON adi_team (uuid);
CREATE INDEX IF NOT EXISTS idx_team_creator ON adi_team (creator_id);

CREATE TABLE IF NOT EXISTS adi_team_member
(
    id          bigserial primary key,
    team_id     bigint       default 0                 not null,
    user_id     bigint       default 0                 not null,
    role        varchar(16)  default 'CONTRIBUTOR'     not null,
    create_time timestamp    default CURRENT_TIMESTAMP not null,
    update_time timestamp    default CURRENT_TIMESTAMP not null,
    constraint ck_team_member_role check (role in ('OWNER', 'CONTRIBUTOR', 'READER'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_team_member ON adi_team_member (team_id, user_id);
CREATE INDEX IF NOT EXISTS idx_team_member_user ON adi_team_member (user_id);

ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS owner_type varchar(16) default 'PERSONAL' not null;

ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS team_id bigint default 0 not null;

-- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS; guard with a catalog probe so
-- the migration stays idempotent.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kb_owner_type') THEN
        ALTER TABLE adi_knowledge_base ADD CONSTRAINT ck_kb_owner_type
            CHECK (owner_type IN ('PERSONAL', 'TEAM', 'COMPANY'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kb_owner_team') THEN
        ALTER TABLE adi_knowledge_base ADD CONSTRAINT ck_kb_owner_team
            CHECK ((owner_type = 'TEAM' AND team_id > 0) OR (owner_type <> 'TEAM' AND team_id = 0));
    END IF;
END $$;

-- Partial indexes: the hot workspace queries filter on the non-default values.
CREATE INDEX IF NOT EXISTS idx_kb_team ON adi_knowledge_base (team_id) WHERE team_id > 0;
CREATE INDEX IF NOT EXISTS idx_kb_owner_type ON adi_knowledge_base (owner_type) WHERE owner_type <> 'PERSONAL';

COMMENT ON TABLE adi_team IS 'Team for collaborative knowledge-base ownership';
COMMENT ON COLUMN adi_team.uuid IS 'Team UUID (32 hex chars)';
COMMENT ON COLUMN adi_team.name IS 'Team name';
COMMENT ON COLUMN adi_team.remark IS 'Team description';
COMMENT ON COLUMN adi_team.creator_id IS 'User id of the team creator';
COMMENT ON COLUMN adi_team.create_time IS 'Creation time';
COMMENT ON COLUMN adi_team.update_time IS 'Last update time';

COMMENT ON TABLE adi_team_member IS 'Team membership; access to team knowledge bases derives from these rows';
COMMENT ON COLUMN adi_team_member.team_id IS 'adi_team id';
COMMENT ON COLUMN adi_team_member.user_id IS 'adi_user id';
COMMENT ON COLUMN adi_team_member.role IS 'Team role: OWNER, CONTRIBUTOR or READER';
COMMENT ON COLUMN adi_team_member.create_time IS 'Membership creation time';
COMMENT ON COLUMN adi_team_member.update_time IS 'Last update time';

COMMENT ON COLUMN adi_knowledge_base.owner_type IS 'Ownership tier: PERSONAL, TEAM or COMPANY';
COMMENT ON COLUMN adi_knowledge_base.team_id IS 'adi_team id for TEAM ownership, 0 otherwise';

DROP TRIGGER IF EXISTS trigger_team_update_time ON adi_team;
CREATE TRIGGER trigger_team_update_time
    BEFORE UPDATE ON adi_team
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();

DROP TRIGGER IF EXISTS trigger_team_member_update_time ON adi_team_member;
CREATE TRIGGER trigger_team_member_update_time
    BEFORE UPDATE ON adi_team_member
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
