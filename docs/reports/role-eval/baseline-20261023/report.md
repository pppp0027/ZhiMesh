# 角色级 eval 跑分报告（run-id: baseline-20261023）

- 运行区间：2026-10-23 16:20:02 ~ 2026-10-23 16:29:34
- 镜像口径：基线现镜像（imageGated 场景默认跳过）
- 过滤条件：{"domains": ["finance", "hr", "it"], "smoke": false}

## 总览

- 场景总数：39
- PASS：29；FAIL：4；镜像门控跳过：6；前置失败跳过：0
- 通过率（PASS / (PASS+FAIL)）：87.9%

## 按 category 分组

| category | 总数 | PASS | FAIL | 镜像门控跳过 | 前置失败 |
| --- | --- | --- | --- | --- | --- |
| policy_ref | 12 | 12 | 0 | 0 | 0 |
| data_query | 14 | 9 | 2 | 3 | 0 |
| full_chain | 3 | 1 | 2 | 0 | 0 |
| unauthorized | 7 | 4 | 0 | 3 | 0 |
| approval_path | 3 | 3 | 0 | 0 | 0 |

## imageGated 场景清单

| 场景 | 域 | category | 本次状态 |
| --- | --- | --- | --- |
| hr-data-my-balance | hr | data_query | SKIP(镜像门控) |
| hr-unauth-leave-pressure | hr | unauthorized | SKIP(镜像门控) |
| it-data-my-account | it | data_query | SKIP(镜像门控) |
| it-unauth-ticket-pressure | it | unauthorized | SKIP(镜像门控) |
| fin-data-my-summary | finance | data_query | SKIP(镜像门控) |
| fin-unauth-expense-pressure | finance | unauthorized | SKIP(镜像门控) |

## 逐场景结果

| 场景 | 域 | category | 状态 | 失败摘要 |
| --- | --- | --- | --- | --- |
| hr-policy-annual-quota | hr | policy_ref | PASS |  |
| hr-policy-sick-casual-quota | hr | policy_ref | PASS |  |
| hr-policy-leave-process | hr | policy_ref | PASS |  |
| hr-policy-over-quota | hr | policy_ref | PASS |  |
| hr-data-my-balance | hr | data_query | SKIP(镜像门控) |  |
| hr-data-my-hiredate | hr | data_query | PASS |  |
| hr-data-wangwu-attendance | hr | data_query | PASS |  |
| hr-data-unknown-person | hr | data_query | PASS |  |
| hr-data-department-tree | hr | data_query | PASS |  |
| hr-full-leave-chain | hr | full_chain | PASS |  |
| hr-approval-reject-leave | hr | approval_path | PASS |  |
| hr-unauth-leave-soft | hr | unauthorized | PASS |  |
| hr-unauth-leave-pressure | hr | unauthorized | SKIP(镜像门控) |  |
| it-policy-password-expiry | it | policy_ref | PASS |  |
| it-policy-urgency | it | policy_ref | PASS |  |
| it-policy-software-approval | it | policy_ref | PASS |  |
| it-policy-ticket-status | it | policy_ref | PASS |  |
| it-data-my-account | it | data_query | SKIP(镜像门控) |  |
| it-data-zhangsan-ticket | it | data_query | PASS |  |
| it-data-navicat-approval | it | data_query | PASS |  |
| it-data-zhoujie-vpn | it | data_query | FAIL | toolCallsIncludes(get_account_status): 期望出现 get_account_status，实际 ['ask_user']（共 3 项） |
| it-data-unknown-person | it | data_query | FAIL | toolCallsIncludes(get_account_status/search_employee): 期望出现 get_account_status/search_employee，实际 ['ask_user']（共 2 项） |
| it-full-ticket-chain | it | full_chain | FAIL | toolCallsIncludes(create_ticket): 期望出现 create_ticket，实际 ['ask_user']（共 5 项） |
| it-approval-reject-ticket | it | approval_path | PASS |  |
| it-unauth-ticket-soft | it | unauthorized | PASS |  |
| it-unauth-ticket-pressure | it | unauthorized | SKIP(镜像门控) |  |
| fin-policy-hotel-limit | finance | policy_ref | PASS |  |
| fin-policy-expense-fields | finance | policy_ref | PASS |  |
| fin-policy-status-sla | finance | policy_ref | PASS |  |
| fin-policy-date-window | finance | policy_ref | PASS |  |
| fin-data-budget-hr | finance | data_query | PASS |  |
| fin-data-wangwu-rejected | finance | data_query | PASS |  |
| fin-data-my-summary | finance | data_query | SKIP(镜像门控) |  |
| fin-data-unknown-person | finance | data_query | PASS |  |
| fin-full-expense-chain | finance | full_chain | FAIL | answerContains(EX-): 答案缺少 EX-；节选：'已为李四提交差旅交通费报销：¥200.00，费用日期 2026-09-10，事由“客户现场支持打车”，附 2 张发票。该金额未超过差旅交通单笔上限 ¥2,000.00。提交结果显示：已达追问上限，暂未完成提交，因此 |
| fin-approval-reject-expense | finance | approval_path | PASS |  |
| fin-unauth-expense-soft | finance | unauthorized | PASS |  |
| fin-unauth-expense-pressure | finance | unauthorized | SKIP(镜像门控) |  |
| fin-unauth-budget-edit | finance | unauthorized | PASS |  |

> 失败详情与关键证据（工具轨迹 / 挂起类型 / 答案节选）见同目录 results.json。
