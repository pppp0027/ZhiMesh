-- System knowledge bases are resolved dynamically from character presets.
-- Earlier user-side edits could copy those IDs into adi_character.kb_ids.
-- Remove only IDs that currently refer to system knowledge bases; retain the
-- user's own IDs and their original ordering.
UPDATE adi_character character
SET kb_ids = COALESCE((
    SELECT string_agg(trim(item.kb_id), ',' ORDER BY item.ordinality)
    FROM unnest(string_to_array(COALESCE(character.kb_ids, ''), ','))
         WITH ORDINALITY AS item(kb_id, ordinality)
    WHERE NOT (
        trim(item.kb_id) ~ '^[0-9]+$'
        AND EXISTS (
            SELECT 1
            FROM adi_knowledge_base knowledge_base
            WHERE knowledge_base.id = trim(item.kb_id)::bigint
              AND COALESCE(knowledge_base.is_system, false) = true
        )
    )
), '')
WHERE COALESCE(character.kb_ids, '') <> '';
