-- Seed additional practical MCP tools (time, memory, filesystem, git, tavily, firecrawl).
-- Idempotent: safe to run repeatedly; existing rows are left untouched.

-- 1. 时间与时区 | Time & Timezone
INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     preset_params, customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), '时间与时区', 'stdio', 'uvx',
       '--from mcp-server-time --with mcp<2 mcp-server-time', 'local',
       '[{"name":"PYTHONIOENCODING","value":"utf-8"}]'::jsonb,
       '[]'::jsonb,
       'https://github.com/modelcontextprotocol/servers/tree/main/src/time',
       '获取当前时间并进行 IANA 时区换算，适合跨时区会议、定时任务和时间计算。首次运行需要安装 uv/uvx。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = '时间与时区');

-- 2. 长期记忆 | Long-term Memory
INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     preset_params, customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), '长期记忆', 'stdio', 'npx',
       '-y @modelcontextprotocol/server-memory', 'local', '[]'::jsonb, '[]'::jsonb,
       'https://github.com/modelcontextprotocol/servers/tree/main/src/memory',
       '跨对话的知识图谱记忆：记住用户偏好、项目事实和实体关系，适合个人助理类角色。默认存储在后端服务器 memory.jsonl。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = '长期记忆');

-- 3. 文件系统 | Filesystem
INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     preset_params, customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), '文件系统', 'stdio', 'npx',
       '-y @modelcontextprotocol/server-filesystem', 'local', '[]'::jsonb,
       '[{"name":"ALLOWED_DIRECTORY","title":"允许访问的目录","cli_arg":true}]'::jsonb,
       'https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem',
       '在限定目录内读取、写入、搜索和整理文件。需要在用户 MCP 配置中填写服务器上允许访问的目录路径（Windows 与 Linux 均可）。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = '文件系统');

-- 4. Git 仓库分析 | Git Repository
INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     preset_params, customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'Git 仓库分析', 'stdio', 'uvx',
       '--from mcp-server-git --with mcp<2 mcp-server-git', 'local',
       '[{"name":"PYTHONIOENCODING","value":"utf-8"}]'::jsonb,
       '[{"name":"GIT_REPOSITORY","title":"Git 仓库路径","cli_arg":true,"cli_prefix":"--repository"}]'::jsonb,
       'https://github.com/modelcontextprotocol/servers/tree/main/src/git',
       '查看仓库状态、提交历史、差异和分支，并可执行提交/分支操作。需要在用户 MCP 配置中填写服务器上的 Git 仓库路径。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = 'Git 仓库分析');

-- 5. Tavily 搜索 | Tavily Search
INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     preset_params, customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'Tavily 搜索', 'stdio', 'npx',
       '-y tavily-mcp', 'local', '[]'::jsonb,
       '[{"name":"TAVILY_API_KEY","title":"Tavily API Key","require_encrypt":true}]'::jsonb,
       'https://github.com/tavily-ai/tavily-mcp',
       '面向 LLM 优化的网页搜索，支持搜索、提取正文、批量爬取和深度研究。需要在用户 MCP 配置中填写 Tavily API Key（有免费额度）。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = 'Tavily 搜索');

-- 6. Firecrawl 深度抓取 | Firecrawl
INSERT INTO adi_mcp
    (uuid, title, transport_type, stdio_command, stdio_arg, install_type,
     preset_params, customized_param_definitions, website, remark, is_enable)
SELECT replace(gen_random_uuid()::text, '-', ''), 'Firecrawl 抓取', 'stdio', 'npx',
       '-y firecrawl-mcp', 'local', '[]'::jsonb,
       '[{"name":"FIRECRAWL_API_KEY","title":"Firecrawl API Key","require_encrypt":true}]'::jsonb,
       'https://github.com/mendableai/firecrawl-mcp-server',
       '深度网页抓取：支持 JS 渲染页面、整站爬取、结构化数据提取和站点监控，比基础网页抓取更强。需要在用户 MCP 配置中填写 Firecrawl API Key。', true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE title = 'Firecrawl 抓取');

