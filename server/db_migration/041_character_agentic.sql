-- Character chat goes agentic: adi_character gains an is_agentic switch that
-- opts a character into a tool-calling loop instead of the single-shot RAG
-- reply. Every tool invocation executed while producing one assistant message
-- is recorded in adi_character_message_tool_call, so the agentic trace stays
-- inspectable in chat history. message_id points at adi_character_message.id;
-- like the sibling provenance tables (adi_character_message_ref_*) no hard
-- foreign key is declared.

ALTER TABLE adi_character
    ADD COLUMN IF NOT EXISTS is_agentic boolean default false not null;

CREATE TABLE IF NOT EXISTS adi_character_message_tool_call
(
    id             bigserial primary key,
    message_id     bigint       default 0                 not null,
    tool_name      varchar(128)                           not null,
    args           text,
    result_summary text,
    duration_ms    bigint       default 0                 not null,
    success        boolean      default true              not null,
    seq            integer      default 0                 not null,
    create_time    timestamp    default CURRENT_TIMESTAMP not null
);

CREATE INDEX IF NOT EXISTS idx_msg_tool_call_message
    ON adi_character_message_tool_call (message_id);

COMMENT ON COLUMN adi_character.is_agentic IS
    '是否启用 Agentic 模式：开启后角色回答走工具调用循环，关闭则保持原有单次 RAG 问答 | Whether agentic tool-calling mode is enabled for this character';

COMMENT ON TABLE adi_character_message_tool_call IS
    '角色对话 Agentic 工具调用轨迹（溯源表）：生成一条助手消息过程中实际执行的每次工具调用各占一行，记录工具名、入参、结果摘要、耗时与成败。姊妹表：adi_character_message_ref_embedding（KB 向量命中）、adi_character_message_ref_graph（KB 图谱命中）、adi_character_message_ref_memory_embedding（记忆命中）、adi_character_message_ref_bm25（BM25 命中）。 | Agentic tool-call trace for character chat: one row per tool invocation executed while producing an assistant message, with name, args, result summary, duration and outcome.';
COMMENT ON COLUMN adi_character_message_tool_call.message_id IS
    'adi_character_message.id，本次工具调用所属的助手消息（与消息溯源姊妹表保持一致，不建外键）';
COMMENT ON COLUMN adi_character_message_tool_call.tool_name IS
    '工具名称或标识，如 MCP 工具名、内置检索工具名';
COMMENT ON COLUMN adi_character_message_tool_call.args IS
    '工具入参，JSON 字符串，可为空';
COMMENT ON COLUMN adi_character_message_tool_call.result_summary IS
    '工具结果摘要，可为空（调用失败或结果过长被截断时为空）';
COMMENT ON COLUMN adi_character_message_tool_call.duration_ms IS
    '工具调用耗时，毫秒';
COMMENT ON COLUMN adi_character_message_tool_call.success IS
    '工具调用是否成功';
COMMENT ON COLUMN adi_character_message_tool_call.seq IS
    '同一轮回答内的工具调用序号，从 0 开始递增';
COMMENT ON COLUMN adi_character_message_tool_call.create_time IS
    '记录创建时间';
