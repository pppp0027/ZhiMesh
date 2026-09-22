-- 将 mcp-servers 演示三件套（人事 / IT 服务台 / 财务报销）从用户自建角色
-- （adi_character，user_id=1 名下）转为系统预设角色（adi_character_preset，
-- is_system=true），使其出现在用户端「添加角色 → 预设角色」列表并对所有用户可用。
--
-- 转换说明：
--   1) 以原角色 uuid 为幂等键，把标题 / 描述 / 提示词 / MCP 绑定复制为系统预设。
--      三个 mock MCP（人事 15 / IT 服务台 16 / 财务 17）均无自定义参数，用户首次
--      使用预设时会自动启用（CharacterService#ensureParameterlessPresetMcps）。
--   2) system_kb_ids 留空：制度文档知识库尚未创建。后续由管理员在知识库管理中
--      创建系统知识库（is_system=true）并回填本字段，所有用户经预设实例化的角色
--      即在运行时获得检索授权（CharacterService#getCurrentSystemKbIds）。
--   3) 删除 user_id=1 名下三个旧自建角色及其关联数据，清理范围对齐应用端
--      CharacterService#softDel（preset_rel、conversation、message，消息的
--      tool_call 痕迹一并级联）。旧角色仅有少量演示消息，转换后由用户从
--      「预设角色」重新启用获得全新实例。
--
-- 幂等说明：可重复执行。预设以 uuid 为键不存在才插入；删除语句按 uuid 定位，
-- 角色已不存在时自然空转。注意必须先插入预设（复制自旧角色行）再删除旧角色。

-- ------------------------------------------------------------
-- 1. 人事助手（小智）→ 系统预设
-- ------------------------------------------------------------
INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT c.uuid, c.title, c.remark, c.ai_system_message, '', c.mcp_ids, 'professional', true
FROM adi_character c
WHERE c.uuid = 'c40ac107cb1fb7e615288a4d119ce7c9'
  AND NOT EXISTS (SELECT 1 FROM adi_character_preset p WHERE p.uuid = 'c40ac107cb1fb7e615288a4d119ce7c9');

-- ------------------------------------------------------------
-- 2. IT 服务台助手 → 系统预设
-- ------------------------------------------------------------
INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT c.uuid, c.title, c.remark, c.ai_system_message, '', c.mcp_ids, 'service', true
FROM adi_character c
WHERE c.uuid = '0c044fcaf29eda3ef3027919f28b3b18'
  AND NOT EXISTS (SELECT 1 FROM adi_character_preset p WHERE p.uuid = '0c044fcaf29eda3ef3027919f28b3b18');

-- ------------------------------------------------------------
-- 3. 财务报销助手 → 系统预设
-- ------------------------------------------------------------
INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT c.uuid, c.title, c.remark, c.ai_system_message, '', c.mcp_ids, 'professional', true
FROM adi_character c
WHERE c.uuid = 'e68a3d15c2f749b0a1e8d4c3f52b7096'
  AND NOT EXISTS (SELECT 1 FROM adi_character_preset p WHERE p.uuid = 'e68a3d15c2f749b0a1e8d4c3f52b7096');

-- ------------------------------------------------------------
-- 4. 删除旧自建角色及其关联数据（先子后父）
-- ------------------------------------------------------------
DELETE FROM adi_character_message_tool_call
WHERE message_id IN (
    SELECT m.id FROM adi_character_message m
    JOIN adi_character c ON m.character_id = c.id
    WHERE c.uuid IN ('c40ac107cb1fb7e615288a4d119ce7c9', '0c044fcaf29eda3ef3027919f28b3b18', 'e68a3d15c2f749b0a1e8d4c3f52b7096'));

DELETE FROM adi_character_message
WHERE character_id IN (
    SELECT id FROM adi_character
    WHERE uuid IN ('c40ac107cb1fb7e615288a4d119ce7c9', '0c044fcaf29eda3ef3027919f28b3b18', 'e68a3d15c2f749b0a1e8d4c3f52b7096'));

DELETE FROM adi_conversation
WHERE character_id IN (
    SELECT id FROM adi_character
    WHERE uuid IN ('c40ac107cb1fb7e615288a4d119ce7c9', '0c044fcaf29eda3ef3027919f28b3b18', 'e68a3d15c2f749b0a1e8d4c3f52b7096'));

DELETE FROM adi_character_preset_rel
WHERE user_character_id IN (
    SELECT id FROM adi_character
    WHERE uuid IN ('c40ac107cb1fb7e615288a4d119ce7c9', '0c044fcaf29eda3ef3027919f28b3b18', 'e68a3d15c2f749b0a1e8d4c3f52b7096'));

DELETE FROM adi_character
WHERE uuid IN ('c40ac107cb1fb7e615288a4d119ce7c9', '0c044fcaf29eda3ef3027919f28b3b18', 'e68a3d15c2f749b0a1e8d4c3f52b7096');
