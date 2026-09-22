-- 042: character agentic mode becomes the product default
-- 角色对话 Agentic 模式成为产品默认：与知识库问答的固定 RAG 管线形成差异化，
-- 新建与存量角色一律默认开启（未绑知识库、无可运行工作流的角色无工具可调，
-- 行为与普通对话一致，不受影响）。幂等，可重复执行。
-- <p>
-- Idempotent: safe to re-run. Existing characters are enabled wholesale and the
-- column default flips to true so direct inserts bypassing the application get
-- the same default.
UPDATE adi_character SET is_agentic = true WHERE is_agentic = false;

ALTER TABLE adi_character ALTER COLUMN is_agentic SET DEFAULT true;
