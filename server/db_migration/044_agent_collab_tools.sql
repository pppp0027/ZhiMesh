-- 044: agent collaboration tools — data layer (T1 of design.md agent-collab-tools).
-- 角色 Agent 协作化数据层，四个部分：
--   1) adi_character / adi_character_preset 新增 tool_policy（text，JSON 字符串）：
--      {"builtinDenylist":["run_workflow"]} —— 内置工具禁用清单；
--      {"approvalRequiredMcpTools":["submit_expense_report"]} —— 调用前需人工审批的
--      MCP 工具名清单。NULL 或非法 JSON = 默认策略（内置工具全允许、无审批门），
--      运行时按 fail-safe 解析（见后续 CharacterToolPolicy 任务）。
--   2) 存量兜底复读：adi_character.is_agentic 全量置 true。042 已完成存量全开与
--      列默认值翻转，此处只捕获 042 之后显式关闭的行。
--   3) 新表 adi_agent_pending_checkpoint：Agent 挂起-恢复检查点。ask_user / 审批
--      挂起时保存完整消息链快照与预算计数，用户下一轮消息据此恢复工具循环。
--   4) 043 财务报销预设补 tool_policy：submit_expense_report 标记需审批，并回填
--      已从该预设实例化的存量角色（经 adi_character_preset_rel 定位）。
-- <p>
-- Idempotent: safe to re-run. ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS,
-- the trigger is guarded by a catalog probe (039 pattern), and the UPDATEs are keyed
-- on uuid with an already-applied guard so a second run touches nothing.
-- 注意：is_agentic 全开不设计数据回滚；回退路径是配置开关
-- zhimesh.agent.default-agentic-enabled=false（见 ZhiMeshProperties.Agent）。

-- ------------------------------------------------------------
-- 1. tool_policy columns
-- ------------------------------------------------------------
ALTER TABLE adi_character
    ADD COLUMN IF NOT EXISTS tool_policy text;

COMMENT ON COLUMN adi_character.tool_policy IS
    '角色工具策略 JSON 字符串：builtinDenylist=内置工具禁用清单；approvalRequiredMcpTools=调用前需人工审批的 MCP 工具名清单。NULL 或非法 JSON = 默认策略（内置工具全允许、无审批门）。 | Character tool policy JSON string: builtin denylist plus MCP tools requiring human approval before execution; NULL or invalid JSON falls back to the default policy.';

-- 预设列只是策略的存放处：用户经预设实例化角色时随 mcp_ids 一同复制到
-- adi_character.tool_policy，运行时不直接读预设列。
ALTER TABLE adi_character_preset
    ADD COLUMN IF NOT EXISTS tool_policy text;

COMMENT ON COLUMN adi_character_preset.tool_policy IS
    '预设角色工具策略 JSON（结构同 adi_character.tool_policy）；用户经预设实例化角色时复制到 adi_character.tool_policy | Preset tool policy JSON (same shape as adi_character.tool_policy); copied into the user character on preset instantiation';

-- ------------------------------------------------------------
-- 2. Wholesale agentic catch-up (042 did the original flip)
-- ------------------------------------------------------------
-- 回退不靠数据回滚：zhimesh.agent.default-agentic-enabled=false 时全体角色回到
-- 非_agentic 现状（配置兜底，见 042/044 迁移说明与 ZhiMeshProperties.Agent）。
UPDATE adi_character SET is_agentic = true WHERE is_agentic = false;

-- ------------------------------------------------------------
-- 3. Agent pending-checkpoint table
-- ------------------------------------------------------------
-- created_at / updated_at 刻意不跟随 BaseEntity 的 create_time / update_time 命名：
-- 检查点契约（快照/恢复）钉死了这两个名字，因此本表用局部触发改写 updated_at，
-- 而非复用写死 update_time 的共享 update_modified_column() 触发器。
CREATE TABLE IF NOT EXISTS adi_agent_pending_checkpoint
(
    id                 bigserial primary key,
    uuid               varchar(64)  default ''               not null,
    conversation_id    bigint       default 0                not null,
    character_id       bigint       default 0                not null,
    user_id            bigint       default 0                not null,
    kind               varchar(32)  default ''               not null,
    pending_tool_name  varchar(128) default ''               not null,
    pending_request_id varchar(128) default ''               not null,
    payload            text,
    messages_snapshot  text                                  not null,
    tool_call_depth    integer      default 0                not null,
    suspension_count   integer      default 0                not null,
    approval_grant     text,
    status             varchar(16)  default 'ACTIVE'         not null,
    created_at         timestamp    default CURRENT_TIMESTAMP not null,
    updated_at         timestamp    default CURRENT_TIMESTAMP not null,
    constraint uk_agent_pending_checkpoint_uuid unique (uuid)
);

CREATE INDEX IF NOT EXISTS idx_agent_pending_checkpoint_conversation_status
    ON adi_agent_pending_checkpoint (conversation_id, status);

COMMENT ON TABLE adi_agent_pending_checkpoint IS
    'Agent 挂起-恢复检查点：ask_user / 审批挂起时保存完整消息链快照与预算计数，用户下一轮消息据此恢复工具循环；单会话同一时刻最多一个 ACTIVE（应用层保证，非库约束）。 | Agent suspension checkpoint: full message-chain snapshot plus budget counters stored when the tool loop suspends for ask_user or approval; the next user message resumes the loop from this row. At most one ACTIVE row per conversation, enforced by the application.';
COMMENT ON COLUMN adi_agent_pending_checkpoint.uuid IS '检查点 uuid | Checkpoint UUID';
COMMENT ON COLUMN adi_agent_pending_checkpoint.conversation_id IS 'adi_conversation.id，挂起发生的会话';
COMMENT ON COLUMN adi_agent_pending_checkpoint.character_id IS 'adi_character.id，挂起时对话的角色';
COMMENT ON COLUMN adi_agent_pending_checkpoint.user_id IS 'adi_user.id，对话用户';
COMMENT ON COLUMN adi_agent_pending_checkpoint.kind IS '挂起类型：ASK_USER / APPROVAL / MCP_APPROVAL';
COMMENT ON COLUMN adi_agent_pending_checkpoint.pending_tool_name IS '触发挂起的协作类工具名（ask_user、request_human_approval，或被审批装饰器包装的 MCP 工具名）';
COMMENT ON COLUMN adi_agent_pending_checkpoint.pending_request_id IS '挂起的 toolExecutionRequest id，恢复时用于配对 ToolExecutionResultMessage';
COMMENT ON COLUMN adi_agent_pending_checkpoint.payload IS '挂起载荷 JSON：ASK_USER=问题文本与选项列表；APPROVAL / MCP_APPROVAL=action / summary / risk_level';
COMMENT ON COLUMN adi_agent_pending_checkpoint.messages_snapshot IS '挂起时完整消息链快照 JSON（SystemMessage / UserMessage / AiMessage（含 toolExecutionRequests）/ ToolExecutionResultMessage 四类，含同轮已执行同伴工具的结果消息）';
COMMENT ON COLUMN adi_agent_pending_checkpoint.tool_call_depth IS '挂起时已消耗的工具循环迭代数，恢复时继承该预算';
COMMENT ON COLUMN adi_agent_pending_checkpoint.suspension_count IS '同一工具链内已挂起次数（含本次），达到 zhimesh.agent.max-suspensions 上限后不再挂起';
COMMENT ON COLUMN adi_agent_pending_checkpoint.approval_grant IS '审批凭证 JSON（toolName + argsHash），仅本恢复链有效，供后续审批任务使用；ASK_USER 挂起为空';
COMMENT ON COLUMN adi_agent_pending_checkpoint.status IS '状态：ACTIVE=待恢复；CONSUMED=已消费；EXPIRED=惰性过期（读取时按 created_at + TTL 判定）；SUPERSEDED=被新挂起或重问覆盖；DELETED=会话删除级联';
COMMENT ON COLUMN adi_agent_pending_checkpoint.created_at IS '创建时间（挂起时刻），惰性 TTL 过期以它为基准';
COMMENT ON COLUMN adi_agent_pending_checkpoint.updated_at IS '更新时间（状态流转时刻）';

CREATE OR REPLACE FUNCTION agent_pending_checkpoint_touch_updated_at()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- PostgreSQL 没有 CREATE TRIGGER IF NOT EXISTS；用目录探针守住幂等（039 的约束守卫同款）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgname = 'trigger_agent_pending_checkpoint_updated_at'
                     AND NOT tgisinternal) THEN
        CREATE TRIGGER trigger_agent_pending_checkpoint_updated_at
            BEFORE UPDATE
            ON adi_agent_pending_checkpoint
            FOR EACH ROW
        EXECUTE PROCEDURE agent_pending_checkpoint_touch_updated_at();
    END IF;
END $$;

-- ------------------------------------------------------------
-- 4. Seed tool_policy on the 043 finance preset (uuid-keyed)
-- ------------------------------------------------------------
-- 043 财务报销助手 uuid：e68a3d15c2f749b0a1e8d4c3f52b7096（系统预设）。
-- IS DISTINCT FROM 守卫让重跑成为空操作（也避免无谓触碰 update_time 触发器）。
UPDATE adi_character_preset
SET tool_policy = '{"approvalRequiredMcpTools":["submit_expense_report"]}'
WHERE uuid = 'e68a3d15c2f749b0a1e8d4c3f52b7096'
  AND tool_policy IS DISTINCT FROM '{"approvalRequiredMcpTools":["submit_expense_report"]}';

-- 回填已从财务预设实例化的存量角色，让审批门覆盖既有演示角色；
-- 新实例化角色的复制由 CharacterService#addByPresetCharacter 完成。
UPDATE adi_character c
SET tool_policy = '{"approvalRequiredMcpTools":["submit_expense_report"]}'
FROM adi_character_preset p
JOIN adi_character_preset_rel r ON r.preset_character_id = p.id
WHERE p.uuid = 'e68a3d15c2f749b0a1e8d4c3f52b7096'
  AND r.user_character_id = c.id
  AND c.tool_policy IS NULL;
