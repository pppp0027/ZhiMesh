# snapshots/ —— 断言锚点快照

本目录是角色级 eval 剧本（`../scenarios/*.json`）所依赖的**领域事实基准快照**，
与跑分无关的文件不要放进来。

## 文件清单

| 快照文件 | 源文件（mcp-servers/） | 内容 |
| --- | --- | --- |
| `zhimesh-hr-data.json` | `zhimesh-hr/data.json` | 人事花名册 / 部门 / 假期余额 / 考勤异常 |
| `zhimesh-helpdesk-data.json` | `zhimesh-helpdesk/data.json` | IT 账号状态 / 工单 / 软件目录 |
| `zhimesh-finance-data.json` | `zhimesh-finance/data.json` | 财务在册员工 / 部门预算 / 报销单 |
| `prompt-hashes.json` | 生产库 `adi_character_preset` | 三领域预设五栏提示词 md5 + tool_policy |

## 用途

1. **剧本事实对账**：剧本断言里的数字与关键词（如李四年假 10/3/7、人事部预算
   12000/4100/7900、王五差旅住宿 680 已驳回等）全部取自这三份 mock 数据与三份
   制度文档（`docs/workerhelper/2026-10-22-role-agent-capability-closure/kb-seed/`）。
   评分失败时先对照快照判断是「模型/链路回归」还是「数据漂移」。
2. **数据漂移告警**：mock 数据一旦变更（如重置花名册、调整预算），必须先更新
   本快照、再修订剧本断言、然后重跑基线，否则新旧通过率不可比。
3. **提示词口径锚定**：`prompt-hashes.json` 记录预设提示词 md5。若 DB 提示词
   被重写而剧本未同步，跑分结论不再有效——重跑前先核对 md5。

## 如何重算快照

```bash
# 1. 刷新三份 mock 数据快照（从仓库源文件复制，保持字节一致）
cp mcp-servers/zhimesh-hr/data.json       scripts/role-eval/snapshots/zhimesh-hr-data.json
cp mcp-servers/zhimesh-helpdesk/data.json scripts/role-eval/snapshots/zhimesh-helpdesk-data.json
cp mcp-servers/zhimesh-finance/data.json  scripts/role-eval/snapshots/zhimesh-finance-data.json

# 2. 校验与源文件一致
md5sum mcp-servers/*/data.json scripts/role-eval/snapshots/*-data.json

# 3. 重算预设提示词 md5（对目标库执行只读 SQL，连接信息见个人环境，勿入库）
#    SELECT uuid, md5(ai_system_message) AS prompt_md5,
#           length(ai_system_message) AS prompt_len, tool_policy, system_kb_ids
#    FROM adi_character_preset
#    WHERE uuid IN ('c40ac107cb1fb7e615288a4d119ce7c9',
#                   '0c044fcaf29eda3ef3027919f28b3b18',
#                   'e68a3d15c2f749b0a1e8d4c3f52b7096');
#    将结果手工同步进 prompt-hashes.json（promptMd5 / promptLength / toolPolicy）。
```

注意：mock 数据中 `submit_leave_request` / `submit_expense_report` 等写操作在
MCP 进程内存中即时扣减，但后端每次聊天会重新拉起 mock 进程（跨轮不保留），
因此剧本对「提交后的余额数字」不做断言，只断言单号前缀（`LV-` / `TK-` / `EX-`）。
