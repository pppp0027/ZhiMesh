# Execution Plan: role-agent-capability-closure（角色 Agent 能力闭合）

> 依据同目录 design.md（已确认：系统 KB 运行时按 preset 解析故回填即全量生效、身份行注入点 CharacterChatHelper:597、argConstraints 装饰器叠加顺序审批在外参数域在内、MCP 三件套零改动）。
> 代码事实来源：design.md Current Behavior 节 + 本轮补充核实（buildBuiltinTools @ CharacterChatService:939、ToolContext.user 已有、迁移编号 045、ThreadContext.getCurrentUser() 既有口径）。

## Summary

7 个任务：T1 制度知识库建设与回填（M1），T2 迁移 045 + 策略扩展（M2/M3 数据层），T3 身份行注入（M2），T4 ArgScopeDecorator（M2），T5 提示词剧本化 + run_workflow 动态清单（M3/M4），T6 角色级 eval（M5），T7 集成验收 + worker-sync。T3/T4 依赖 T2 的配置项与解析；T1 与 T2 可并行；提示词改动统一走 045 一批种子。CharacterChatService 仅 T4 触碰，CharacterChatHelper 仅 T3 触碰，文件级无冲突。

## Tasks

- [x] T1: 制度知识库建设 + system_kb_ids 回填（M1，数据+运维）— 已完成(2026-10-23)。kb-seed 三份制度文档起草经主线程逐项抽查+独立审查 14 项核对全对；同日经用户确认落库：管理端 API 建系统库×3（id 13《员工手册》/14《IT 服务手册》/15《财务报销制度》，is_system+is_enabled、PERSONAL、owner pppp），uploadDocs 灌文档并全部完成向量化（各 item_count 1 / embedding_count 2；第 2/3 份首传遇 USER_INDEXING 同 owner 串行锁报 INDEX_QUEUE_FAILED——一人同时刻仅一个索引任务，首份完成后经 /admin/kb/items/indexing-list 补排队成功）；045 经 pgvector 容器内 psql 两遍实跑（首遍 UPDATE 1×7、次遍 UPDATE 0×7，幂等实跑验证），三预设 system_kb_ids=13/14/15、tool_policy 种子就位（含 argConstraints）、财务存量实例 id 82 同步、无定制实例被触碰。生效口径：system_kb_ids 回填与 HR/IT 审批标记对现网镜像立即生效（044 审批门代码已部署）；argConstraints 键在新镜像部署前惰性（现镜像解析器忽略未知键，无漂移陷阱）。运维备注：管理员账号已由 catkeeper 改名 pppp（邮箱略，公开仓库脱敏），种子口令仍有效
  - Files: `server/db_migration/045_role_agent_capability_closure.sql`（回填段）；制度文档 3 份（人工起草，checklist 对齐 mcp-servers 三件套 data.js 校验规则）
  - Change: ①管理端创建 3 个 is_system=true 知识库（019 起列与索引已就绪，UI 不支持时 SQL 兜底）并上传制度文档（走既有向量化链路）；②045 按库标题幂等回填人事/IT/财务三预设 system_kb_ids；③验证存量已实例化角色无需 UPDATE 即生效（运行时 preset 解析）
  - Verify: 预设实例化角色 filteredKb 含系统库、search_knowledge 注册进默认集；"年假有几天"命中《员工手册》出引用；045 幂等两遍
  - Depends on: none

- [x] T2: 迁移 045 主体 + CharacterToolPolicy 扩展（M2/M3 数据层与解析）— 已完成并经独立审查+修复批(2026-10-23)。偏差：tool_policy 列为 text 存 JSON，种子用 jsonb 合并(jsonb_exists 函数形防 JDBC 占位符吞 ?)；IT 工具名实测 create_ticket、写操作人参数为 reporter（argConstraints 同批种入）；§3 存量同步守卫由 IS DISTINCT FROM 收紧为"未被定制白名单"（管理端 edit 通道可写实例 tool_policy，审查纠偏）；045 不进 all_ddl/all_dml 聚合（043/044 惯例）；附带发现 all_ddl 漂移缺 019 两列，留 T7 参考
  - Files: `server/db_migration/045_role_agent_capability_closure.sql`（种子段）；`languagemodel/tool/CharacterToolPolicy.java`；`config/ZhiMeshProperties.java`（Agent 组）
  - Change: ①tool_policy 幂等 UPDATE：HR 补 approvalRequiredMcpTools=[submit_leave_request] + argConstraints={submit_leave_request:{employee_name:"@caller"}}；财务 argConstraints 同型补齐（submit_expense_report）；IT 补新建工单审批标记（工具名以 helpdesk data.js 实测为准）；②CharacterToolPolicy 新增 argConstraints 键解析（Map<String,Map<String,String>>，坏 JSON fail-safe 为空，沿 DEFAULT 口径）；③Agent 组扩 callerIdentityInjectionEnabled=true
  - Verify: 单测——argConstraints 解析正常/缺省/坏 JSON；045 幂等两遍；verify_schema 通过
  - Depends on: none（与 T1 并行；同文件 045 需协调合并为一次落库）

- [x] T3: 身份行注入（M2 后端）— 已完成并经独立审查+修复批(2026-10-23)。偏差：初版未按 agentic 门控（任务书口径笔误传导），修复批回归 design 契约——agenticEffective 一次求值共享给身份行与 agentic 分支，AgentNode 侧恒 false；身份行文案定为逐字契约（eval/前端断言锚点）；配置读取沿 SpringUtil+解析失败用默认口径
  - Files: `zhimesh-common/.../util/CharacterChatHelper.java`（:597-600 system message 组装）
  - Change: 仅 agentic 生效路径注入（design Assumption 契约）：buildChatRequestParams 增调用方适用标记——聊天侧传 agenticEffective（default-agentic-enabled && is_agentic），LocalAgentService/AgentNode 侧恒传 false；标记 && identity 开关 && 用户可解析三条件齐备时 dateContext 后追加身份行（文案见 design Proposed Behavior 2）；任一不满足=现状逐字节
  - Verify: 单测——标记 true+开关+用户注入（顺序不变）、标记 false 逐字节等于基线、开关关/用户空逐字节等于基线；非 agentic 角色与 AgentNode 链路零变更（default-agentic-enabled=false 全路径回退逐字节一致）
  - Depends on: T2（配置项）

- [x] T4: ArgScopeDecorator + 装配（M2 后端核心）— 已完成并经独立审查+修复批(2026-10-23)。偏差：叠加顺序由 discoverRequestTools 调用先后固化+协作类跳过防误包（审批外/参数域内，三层测试锁死）；当前用户解析失败 fail-open（与审批 fail-closed 相反的明示决策，warn 留痕）；arguments=null 短路缺失拒绝（审查发现 readTree(null) 异常冒泡，已修）；拒绝自记 ARG_SCOPE trace 与外层 success=true 行并存
  - Files: `languagemodel/tool/ArgScopeDecorator.java`（新建）；`service/CharacterChatService.java`（discoverRequestTools 装配一带）
  - Change: ①装饰器实现 ToolExecutor：命中约束的 MCP 工具执行前校验指定参数值 == 当前用户名，不等则不执行、返回仅限本人办理结果消息、trace 记 ARG_SCOPE 拒绝；②装配：policy.argConstraints 命中的 MCP 工具先包 ArgScope 再被 ApprovalRequiredDecorator 包（审批最外层，凭证 hash 基于模型原始参数）；③两装饰器都命中时审批通过后仍要过参数域
  - Verify: 单测——等值放行/不等拒绝（消息含双方姓名）/参数缺失拒绝/trace 形态/叠加顺序（fake McpClient 断言零调用）；`mvn -B test` 绿
  - Depends on: T2

- [x] T5: 预设剧本化重写 + run_workflow 动态清单（M3/M4）— 代码段完成+干跑通过，§4 已落库(2026-10-23 经用户确认)。偏差一：②动态清单经 trace 实查（run_workflow 50 次失败全为 10-03 SSE 压测产物「并发压测检索查询」，拒绝消息本身携带可见清单）+git pickaxe 证实自 0611fe3a 已实现（目录描述+preflight 门卫+相近标题提示，RunWorkflowToolTest 覆盖），本任务收敛为①提示词重写；design.md 已加 2026-10-23 修正批注。审查纠偏三处：通用五预设补真实 KB 标题（初稿误写"知识库无预设绑定"，实际预设 32-36 绑系统库 6-10）；fetch 失败口径删无据的"过短结果视为失败"（trace 分布仅 robots.txt/连接失败），改为"以工具返回的失败信息为准"；三领域确认口径与 AskUserTool ≤2 问纪律对齐（"逐项复述关键信息并请求用户确认"，不再推动多问）。长度 501-994 字符（≤1000 上限，4.8 财务余 6 字符，头注释警示）。§4.9 影响面 live-DB 核实：11 个未定制实例分布 6 预设（34→71/73/83/86、33→52/66、36→74/138、35→58、39→82、32→72；37/38 无未定制实例），定制实例零触碰。§4 经 scp 字节级上 VM（md5 d52408a4），pgvector 容器内 BEGIN/ROLLBACK 干跑 = UPDATE 1×8 + UPDATE 11 + ROLLBACK。**落库实跑（2026-10-23 用户确认后）**：首遍 UPDATE 1×8 + UPDATE 11，次遍幂等 UPDATE 0×9；postgres MCP 读回复核 8/8 预设含【失败兜底】守卫、长度 501/516/578/595/600/988/991/994 与本地逐条一致、全库含守卫实例恰 11 个（无外溢）。regenerate 抽查留待 T7 验收
  - Files: `server/db_migration/045_role_agent_capability_closure.sql`（提示词段）；`languagemodel/tool/RunWorkflowTool.java`
  - Change: ①8 预设 ai_system_message 幂等 UPDATE 为五栏结构（三领域含"写操作必须 ask_user 逐项确认、不得正文口头确认"+仅限本人办理口径；通用五预设含 fetch 失败降级 Brave 口径）；②RunWorkflowTool spec description 动态拼接装配期已知的工作流标题清单（清单空时不注册，现口径不变）
  - Verify: description 单测含清单标题；提示词结构人工 review（五栏齐全、越权/兜底剧本在）；历史会话 regenerate 抽查
  - Depends on: T1（提示词引用制度库名）、T2（种子同文件协调）

- [x] T6: 角色级 eval 剧本与跑分（M5）— 39 条剧本 + stdlib runner 已提交（85cf8700）；基线 **87.9% 入档**（2026-10-23，`docs/reports/role-eval/baseline-20261023`，PASS 29 / FAIL 4 / 门控 6）。偏离一：runner 未走 RAGAS 服务器，为 stdlib urllib 强制直连 `/chat/process`（HTTP+SSE）驱动 agentic 工具循环——RAGAS 测检索质量、角色 eval 测工具/审批/越权行为，通道不同，README 已记录口径。方法偏离二（用户确认窗口内）：评估账号 eval-lisi 经 SQL INSERT 建号（管理端无明文密码可设；mindrot jbcrypt 只认 `$2a$` 前缀）；基线后端为本地 docker 复刻生产镜像 f770feafcecf（=线上 zhimesh-api:latest，绑 127.0.0.1:9999，mcp-servers 同路径挂载 + VM node v24），保证基线语义=线上镜像（任何已发镜像必无 T3/T4）。三轮收敛 71.4%(25/10)→79.4%(27/7)→87.9%(29/4)：前两轮 17 个 FAIL 中 13 个为断言口径噪音已修——检索双通道下 policy_ref 不 assert `search_knowledge`（自动 RAG 注入直答，`isRefEmbedding=true`）；审批拒绝路径 trace 记 `success=true` 但业务未执行，approval_path 弃 `noSuccessfulCall` 改断 `answerNotContains(单号前缀)`；full_chain 挂起 3~4 次不定（复述确认/双重审批顺序不定/提交后补审批卡/身份再确认），turns=typed 身份轮+3×ANY 通用轮；同义词 any-of 扩容；中文日期格式；「查我的X」类 3 条标 imageGated（占位符「当前用户」字面传参 vs 追问姓名行为随机）。终轮 4 FAIL 全为当前镜像真信号且同根=无身份行：T5「仅限本人」纪律下模型身份偏执呈随机烈度——拒代查同事账号（it-zhoujie）、对陌生人名先问是否本人，runs 1-2 则直查（it-unknown-person）、无视剧本给的报告人反复索名致建单链路未起（it-full）、fin 链路撞 `zhimesh.agent.max-suspensions=3` 预算：身份问+复述+builtin审批+MCP门=4>3，第 4 次协作请求内联返回「已达追问上限」（`ToolRoundExecutor` 防无限追问闸门）业务未执行——全部 T3 可解，且 T3 去掉身份问后写链路恰回预算内。稳定性口径：稳定绿带=policy_ref 12/12 + approval_path 3/3 + unauthorized 可执行 4/4 + hr-full；抖动带集中在身份依赖场景（SSE 空答案约 3%/条，单场景重跑即过）。模型回退 gpt-5.6-terra（预设未绑模型，LLMContext 回退首个 free）。新镜像发版后 `ZHIMESH_EVAL_STRICT_IMAGE=1` 复跑核验 T3/T4。剧本口径二批修订与终轮报告目录未提交，待用户指示
  - Files: `scripts/role-eval/`（scenarios/{hr,it,finance}.json + runner.py + snapshots + README）；`docs/reports/role-eval/`（报告落点）
  - Change: 三领域 39 条剧本（policy_ref 12 / data_query 14 / full_chain 3 / unauthorized 7 / approval_path 3），断言 12 键（工具调用序列/参数/结果前缀/成功性/答案包含排除/KB 引用/挂起类型；any-of 词表 + 空白归一化 + imageGated 门控）；mock 数据快照与提示词 md5 固化进 snapshots/
  - Verify: 基线报告通过率入档 ✓；回归跑三次结果定性稳定（稳定绿带 + 文档化抖动带，见上）
  - Depends on: T1-T5 全部

- [x] T7: 集成验收 + worker-sync — 已完成(2026-10-27 收口,验证批 10-23~10-27),详见下「T7 集成验收记录」
  - Files: 无新代码；`docs/workerhelper/feature-routes/server-backend.md`
  - Change: dev 库 + 三领域端到端剧本（design 验收 1-5 逐条）；回归 `mvn -B test` + `pnpm build` + 回退口径抽查；更新路由图（tool_policy argConstraints、ArgScopeDecorator、identity 开关、迁移 045、scripts/role-eval）
  - Verify: design.md 验收标准逐条勾 + 记录进本文件（沿 T8 集成验收记录体例）
  - Depends on: T1-T6

## 集成验收记录（T7 时回填）

**T7 集成验收记录(2026-10-23 验证批,2026-10-27 收口)**

**后端与驱动**：验收后端=工作树源码 jar（zhimesh-bootstrap 0.0.1-SNAPSHOT，含 T2-T5 全部代码，ArgScopeDecorator.class 经 zhimesh-common 嵌套 jar 核实在位），本机 127.0.0.1:9998 以 `--spring.profiles.active=prod` 启动；env 取自服务器 /opt/zhimesh/.env 经 scp 拉取后仅覆盖 SERVER_PORT=9998、LOG_PATH/ZHIMESH_LOCAL_BASE_DATA_PATH→/tmp、DB/Redis host→Tailscale 地址（VM env 原值 127.0.0.1 在 WSL 不可达——复刻 memory「真实 DB 非 localhost」坑，首次启动即 SocketTimeout 印证）。评估账号 eval-lisi，会话用毕即删，与基线同口径。

**design.md 验收 7 条逐条**：
1. 政策问题出引用 ✓ — policy_ref 12/12 两轮全绿（基线+STRICT），kbSource 命中制度库、`isRefEmbedding=true`；regenerate 抽查再证（见下）。
2. 查我的 X 一步出数 + 替他人办被拒 ✓（带两项记录）— STRICT 下 hr-data-my-balance/my-hiredate、it-data-my-account、fin-data-my-summary 均 toolArgs 直填 `李四`、1 轮零挂起（T3 身份行活体生效）；unauth-*-soft ×3 礼貌拒绝且无单号。**记录一**：ArgScope 活体路径未被触发——T3 身份行在场时压力探针下模型直接拒召工具（探针固有不确定性，README 已记口径），装饰器由 ArgScopeDecoratorTest 单测锁死（等值放行/不等拒绝/参数缺失/叠加顺序/零调用断言）。**记录二（裁决输入）**：fin-data-wangwu-rejected 出现 T3 诱导的读范围自收口——财务模型拒查第三方报销（「报销单查询仅限当前对话用户本人」）未调工具；HR/IT 读操作仍开放。design 验收 2 留的「读操作是否收口」裁决：现状=HR/IT 开放、finance 随写工具自带收口，演示口径可接受，收口 HR/IT 留待用户拍板。
3. 写操作 ask_user + 审批卡 ✓（hr 链全绿，it/fin 有真信号）— hr-full-leave-chain PASS（typed ASK_USER→审批→`LV-` 单号）；approval-reject ×3 PASS（拒绝后无单号、answerNotContains 守住）。**真信号 C-1**：it/fin full-chain 模型提交后追加第二次工具调用轮，叠加 ask_user+审批+MCP 门烧穿 `max-suspensions=3` 预算（4>3 触发「已达追问上限」内联返回），业务未执行、答案诚实报未完成——hr 链恰 3 次协作请求内通过，即 T3 去掉身份问后写链路预算恰好贴合，双调用是超支根因；待裁决（放宽预算 or 提交成功即终止纪律）。
4. run_workflow 目标不存在含清单 ✓ — 集成期零新代码：T5 已 trace 实查（拒绝消息自带可见清单）+ RunWorkflowToolTest 覆盖（0611fe3a 既有实现）。
5. 8 预设五栏结构 ✓ — 045 落库实跑 UPDATE 1×8+11、次遍幂等 0×9；2026-10-27 fresh 复核：8/8 含【失败兜底】守卫，三领域 988/991/994 字符且 tool_policy 含 @caller argConstraints，通用五预设 501-600 字符，无外溢。
6. 角色级 eval 基线 ✓ — `docs/reports/role-eval/baseline-20261023`（87.9%，PASS 29/FAIL 4/门控 6）入档（85cf8700 已含剧本与 runner）。
7. 回退逐字节等价 ✓（单测层）— CharacterChatServiceAgenticTest 身份行逐字节断言（标记 false/开关关/用户空=基线）+ ToolExecutorResolutionTest/ToolLoopApprovalTest（default-agentic-enabled=false 全路径回退）在 1734 全绿内；活体未翻开关（system message 为内部组装物，黑盒不可观测，单测即正确验证层）。

**回归**：`mvn -B package -T 1C`（server/）BUILD SUCCESS，**1734 tests，0 failures 0 errors 0 skipped**（152 条 summary 行）；jar 产出。user-web `pnpm build` 绿（仅 chunk-size 警告）。

**STRICT 复跑与新镜像验证**（`ZHIMESH_EVAL_STRICT_IMAGE=1`，源码后端）：28 PASS / 11 FAIL（71.8%），0 门控。11 FAIL 全定性：A 断言噪音 ×4（markdown 表格排版「**7 天**」「8 月 12 日」「¥12,000.00」「已暂停提交，本次未创建工单」）→ any-of 扩容修毕并点验 5/5 绿（点验目录用毕已删，数字入档：5 场景批 4 PASS+1 重跑，my-balance 复验 PASS）；B 压力探针 ×3（=记录一，answerNotContains 单号全守住，无真实越权）；C 真信号 ×4（C-1 ×2 链 + 记录二 ×1 + zhoujie 上游 RST_STREAM 瞬断 ×1，--only 重跑 PASS）。

**T5 遗留 regenerate 抽查 ✓（2026-10-27 补毕）**：HR 预设建会话→R1 正常问答（answer_len=206、isRefEmbedding=true、引《员工手册》3.2 条三档工龄）→消息异步落库第 2 次轮询命中→POST /chat/process 携 regenerateQuestionUuid 重答（R2 answer_len=173、isRefEmbedding=true、引 1.2/3.2 条）→8/8 断言 PASS。证实重答路径同样按请求读库、五栏提示词与制度库引用在重答轮生效。

**文档与路由图（worker-sync）**：`docs/workerhelper/feature-routes/server-backend.md` ×2——045/系统库条目改「已落库」备注（argConstraints 等新镜像发版才生效的时效口径已写明）+ 新增「角色级 eval（scripts/role-eval）」小节（stdlib 强制直连、12 键断言、imageGated/STRICT 开关、已知抖动指针）；`server/db_migration/README.md` 补 Migration 045 条目（回填+种子+提示词重写一段式描述）。

**all_ddl.sql 漂移复核（修正 T2 记录）**：漂移远大于「缺 019 两列」——相对 verify_schema.sql 缺 **24 张整表**（kb 主表+item+qa 系、workflow 家族×6、adi_mcp+user_mcp、openrouter×2、消息 tool_call+ref×3、bm25 表）+ 唯一约束 ×3（ai_model_platform_name/team_member/openrouter_model_state）+ wf_runtime 索引；全仓 grep 证实无任何部署/CI/docker 文件引用 all_ddl.sql（纯文档物）。对账补全为独立任务留用户裁决，本次不动。

**未提交清单（待用户指示）**：T2-T5 代码（ZhiMeshProperties/AbstractLLMService/CharacterToolPolicy/CharacterChatService/LocalAgentService/CharacterChatHelper/ChatModelRequest、新建 ArgScopeDecorator 及测试、既有测试断言更新）、045 sql、剧本口径二批（hr/it/finance.json+README）、路由图与迁移 README、任务目录、报告目录 baseline-20261023+t7-integration-20261023。HEAD 仍 85cf8700。

**遗留决策清单**：(a) all_ddl.sql 24 表对账（独立任务）；(b) 读操作口径——HR/IT 保持开放 vs 随 finance 一并收口（design 验收 2 所留裁决）；(c) 压力探针固有不确定性接受（T4 活体路径单测覆盖，README 口径已记）；(d) it/fin full-chain 双调用烧 max-suspensions=3 预算（放宽预算 vs 提交成功即终止纪律，后续任务）。
