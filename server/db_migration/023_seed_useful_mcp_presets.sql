-- Seed practical MCP templates and role presets.
--
-- The two parameterless MCPs are enabled automatically when a user first uses
-- a preset. GitHub and Brave Search remain configurable because they require a
-- user-owned credential.

-- Upgrade an earlier draft of this seed, which used a non-existent npm
-- package for Fetch MCP. This is intentionally safe to run repeatedly.
UPDATE adi_mcp
SET stdio_command = 'uvx',
    stdio_arg = '--from mcp-server-fetch --with mcp<2 mcp-server-fetch',
    preset_params = '[{"name":"PYTHONIOENCODING","value":"utf-8"}]'::jsonb,
    remark = '抓取公开网页内容并提取正文，适合研究、资料整理和知识库准备。首次运行需要安装 uv/uvx，并使用 mcp<2 约束启动（官方包与 mcp SDK 2.x 暂不兼容）。'
WHERE title = '网页抓取';

INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), '网页抓取', 'stdio', 'uvx',
       '--from mcp-server-fetch --with mcp<2 mcp-server-fetch', 'local', '[{"name":"PYTHONIOENCODING","value":"utf-8"}]'::jsonb,
       'https://github.com/modelcontextprotocol/servers',
       '抓取公开网页内容并提取正文，适合研究、资料整理和知识库准备。首次运行需要安装 uv/uvx，并使用 mcp<2 约束启动（官方包与 mcp SDK 2.x 暂不兼容）。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = '网页抓取');

INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), '顺序思考', 'stdio', 'npx',
       '-y @modelcontextprotocol/server-sequential-thinking', 'local', '[]'::jsonb,
       'https://github.com/modelcontextprotocol/servers',
       '把复杂问题拆成可修正的连续推理步骤，适合分析、规划、排错和决策。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = '顺序思考');

INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'GitHub', 'stdio', 'npx',
       '-y @modelcontextprotocol/server-github', 'local',
       '[{"name":"GITHUB_PERSONAL_ACCESS_TOKEN","title":"GitHub Personal Access Token","require_encrypt":true}]'::jsonb,
       'https://github.com/modelcontextprotocol/servers',
       '读取仓库、Issue、Pull Request 和代码内容。请在用户 MCP 配置中填写具备最小必要权限的 GitHub Token。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = 'GitHub');

INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'Brave 搜索', 'stdio', 'npx',
       '-y @modelcontextprotocol/server-brave-search', 'local',
       '[{"name":"BRAVE_API_KEY","title":"Brave Search API Key","require_encrypt":true}]'::jsonb,
       'https://brave.com/search/api/',
       '提供网页和新闻搜索。请在用户 MCP 配置中填写 Brave Search API Key，并按需设置查询额度。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = 'Brave 搜索');

INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT replace(gen_random_uuid()::text, '-', ''),
       '联网研究员',
       '先查证公开资料，再给出带来源和不确定性说明的研究结论。',
       '你是一名严谨的联网研究员。遇到需要事实、最新信息或外部资料的问题，优先使用网页抓取和 Brave 搜索；区分已验证事实、推断和未知信息。输出结论、证据摘要、来源链接和下一步建议，不要编造来源。',
       '',
       (SELECT string_agg(id::text, ',' ORDER BY id) FROM adi_mcp WHERE title IN ('网页抓取', 'Brave 搜索')),
       'professional', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE title = '联网研究员');

INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT replace(gen_random_uuid()::text, '-', ''),
       '软件开发助手',
       '帮助定位代码问题、阅读仓库上下文并制定可执行的修复方案。',
       '你是一名软件开发助手。先用顺序思考拆解问题，再用网页抓取查阅官方文档；如果用户配置了 GitHub MCP，可以读取仓库、Issue 和 Pull Request。明确区分观察到的代码事实与推测，给出最小可验证改动、测试方法和风险。',
       '',
       (SELECT string_agg(id::text, ',' ORDER BY id) FROM adi_mcp WHERE title IN ('网页抓取', '顺序思考', 'GitHub')),
       'technology', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE title = '软件开发助手');

INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT replace(gen_random_uuid()::text, '-', ''),
       '产品与市场分析师',
       '从公开信息中整理竞品、用户需求和市场变化，形成结构化建议。',
       '你是一名产品与市场分析师。先明确分析目标、范围、时间窗口和判断标准；使用网页抓取与 Brave 搜索收集公开证据，标注来源日期。输出市场事实、竞品对比、机会与风险、建议行动和需要继续验证的假设。',
       '',
       (SELECT string_agg(id::text, ',' ORDER BY id) FROM adi_mcp WHERE title IN ('网页抓取', 'Brave 搜索', '顺序思考')),
       'business', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE title = '产品与市场分析师');

INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT replace(gen_random_uuid()::text, '-', ''),
       '技术文档助手',
       '把零散资料整理成准确、可维护、面向读者的技术文档。',
       '你是一名技术文档助手。先确认读者、目标和版本，再使用网页抓取核对官方资料，使用顺序思考组织章节和示例。输出可直接落地的文档结构、术语定义、步骤、限制条件和验证清单；不确定内容必须明确标记。',
       '',
       (SELECT string_agg(id::text, ',' ORDER BY id) FROM adi_mcp WHERE title IN ('网页抓取', '顺序思考')),
       'technology', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE title = '技术文档助手');

INSERT INTO adi_character_preset
    (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT replace(gen_random_uuid()::text, '-', ''),
       '知识库构建助手',
       '协助筛选资料、提炼主题、设计知识库目录和后续维护规则。',
       '你是一名知识库构建助手。先定义知识库的使用场景和边界，再用网页抓取收集公开资料，使用顺序思考去重、分层和发现缺口。输出推荐资料清单、目录结构、元数据字段、切分建议、引用规范和待人工确认事项。不要把未经核实的内容当作事实。',
       '',
       (SELECT string_agg(id::text, ',' ORDER BY id) FROM adi_mcp WHERE title IN ('网页抓取', '顺序思考')),
       'utility', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE title = '知识库构建助手');
