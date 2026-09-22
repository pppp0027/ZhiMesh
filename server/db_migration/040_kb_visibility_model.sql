-- Visibility model: isPublic is retired. Visibility derives entirely from the
-- ownership tier: PERSONAL stays private to the owner, TEAM is visible to team
-- members only, and COMPANY is split by company_scope (STAFF = everyone,
-- EXECUTIVE = administrators only). Legacy public personal knowledge bases are
-- upgraded to COMPANY/STAFF so their readers keep access; owner_* columns keep
-- recording the original creator for audit.

ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS company_scope varchar(16) default 'STAFF' not null;

-- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS; guard with a catalog probe so
-- the migration stays idempotent.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kb_company_scope') THEN
        ALTER TABLE adi_knowledge_base ADD CONSTRAINT ck_kb_company_scope
            CHECK (company_scope IN ('STAFF', 'EXECUTIVE')
                AND (owner_type = 'COMPANY' OR company_scope = 'STAFF'));
    END IF;
END $$;

-- Convert legacy public personal knowledge bases into company-wide ones BEFORE
-- dropping the column: public used to mean "readable by everyone", which is
-- exactly COMPANY/STAFF. team_id is reset to satisfy ck_kb_owner_team.
UPDATE adi_knowledge_base
SET owner_type    = 'COMPANY',
    team_id       = 0,
    company_scope = 'STAFF'
WHERE is_public
  AND COALESCE(owner_type, 'PERSONAL') = 'PERSONAL';

ALTER TABLE adi_knowledge_base
    DROP COLUMN IF EXISTS is_public;

-- Partial index: the executive-tier rows are the rare non-default ones.
CREATE INDEX IF NOT EXISTS idx_kb_company_exec ON adi_knowledge_base (company_scope)
    WHERE owner_type = 'COMPANY' AND company_scope = 'EXECUTIVE';

COMMENT ON COLUMN adi_knowledge_base.company_scope IS
    'Company-tier visibility: STAFF (all employees) or EXECUTIVE (administrators only); forced to STAFF for non-COMPANY tiers';
