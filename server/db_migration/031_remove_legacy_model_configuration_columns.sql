-- Remove model configuration columns that are no longer accepted or read by the application.
--
-- Irreversible data migration: take a database backup before running in production.
-- Apply only after deploying code that no longer maps these columns, or immediately
-- before that deployment while the application is stopped.

BEGIN;

-- Preserve the only legacy credential case that the old runtime supported:
-- if api_key was empty, secret_key acted as its fallback.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'adi_model_platform'
          AND column_name = 'secret_key'
    ) THEN
        UPDATE public.adi_model_platform
        SET api_key = secret_key
        WHERE btrim(api_key) = ''
          AND btrim(secret_key) <> '';
    END IF;
END
$$;

ALTER TABLE public.adi_model_platform
    DROP COLUMN IF EXISTS secret_key;

ALTER TABLE public.adi_ai_model
    DROP COLUMN IF EXISTS setting,
    DROP COLUMN IF EXISTS context_window,
    DROP COLUMN IF EXISTS max_output_tokens;

COMMIT;

-- IDEA result-pane verification: this query must return zero rows.
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND (table_name, column_name) IN (
      ('adi_model_platform', 'secret_key'),
      ('adi_ai_model', 'setting'),
      ('adi_ai_model', 'context_window'),
      ('adi_ai_model', 'max_output_tokens')
  )
ORDER BY table_name, column_name;
