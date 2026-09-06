-- Persist one avatar path per user. The files are bundled by user-web under
-- /avatars/users/avatar-01.png through avatar-13.png.
ALTER TABLE adi_user
    ADD COLUMN IF NOT EXISTS avatar character varying(160) DEFAULT '' NOT NULL;

COMMENT ON COLUMN adi_user.avatar IS 'Public path of the user avatar';

-- Existing users receive an independently selected avatar. The blank guard
-- keeps this migration idempotent if it is inspected or replayed deliberately.
UPDATE adi_user
SET avatar = '/avatars/users/avatar-'
    || lpad(((floor(random() * 13) + 1)::integer)::text, 2, '0')
    || '.png'
WHERE avatar = '';
