// smoke —— 验证 zhimesh-hr、zhimesh-helpdesk 与 zhimesh-finance 三个 stdio MCP server
// 每条断言输出一行 PASS/FAIL，末尾汇总；任何 FAIL 进程退出码 1。
const path = require("path");
const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { StdioClientTransport } = require("@modelcontextprotocol/sdk/client/stdio.js");

const results = [];
function check(desc, ok) {
  results.push(ok);
  console.log(`${ok ? "PASS" : "FAIL"} ${desc}`);
}

// 依次连接被测 server，测完关闭
async function withServer(serverDir, fn) {
  const transport = new StdioClientTransport({
    command: process.execPath, // 保证用当前 node 本体拉起子进程
    args: [path.join(__dirname, serverDir, "index.js")],
  });
  const client = new Client({ name: "smoke-test", version: "1.0.0" });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

const textOf = (res) => (res.content || []).map((c) => c.text || "").join("\n");
const call = (client, name, args = {}) => client.callTool({ name, arguments: args }).then(textOf);
const sorted = (arr) => [...arr].sort();

async function testHr() {
  await withServer("zhimesh-hr", async (c) => {
    // 工具名集合与契约完全一致（不多不少）
    const { tools } = await c.listTools();
    check(
      `hr listTools 集合一致（${tools.map((t) => t.name).join(",")}）`,
      JSON.stringify(sorted(tools.map((t) => t.name))) ===
        JSON.stringify(sorted(["search_employee", "get_department_tree", "get_leave_balance", "list_attendance_anomalies", "submit_leave_request"]))
    );

    // 锚点：张三 → ZP1008
    const r = await call(c, "search_employee", { keyword: "张三" });
    check('hr search_employee("张三") 命中 ZP1008 与 zhangsan@zhimesh.com', r.includes("ZP1008") && r.includes("zhangsan@zhimesh.com"));

    // keyword + department 组合过滤
    const r2 = await call(c, "search_employee", { keyword: "ZP", department: "技术部" });
    check("hr search_employee 工号+部门过滤（含张三、不含李四）", r2.includes("张三") && !r2.includes("李四"));

    // 部门树
    const r3 = await call(c, "get_department_tree");
    check("hr get_department_tree 返回技术部/负责人陈建国/在职 13 人", r3.includes("技术部") && r3.includes("陈建国") && r3.includes("13 人"));

    // 锚点：张三年假剩余 8 天
    const r4 = await call(c, "get_leave_balance", { employee_name: "张三" });
    check('hr get_leave_balance("张三") 年假剩余 8 天', r4.includes("年假：总额 10 天，已用 2 天，剩余 8 天"));

    // 员工不存在 → 错误文本
    const r5 = await call(c, "get_leave_balance", { employee_name: "查无此人" });
    check('hr get_leave_balance 员工不存在返回「错误：」', r5.startsWith("错误："));

    // 考勤异常：王五 2026-08 恰好 2 条（1 迟到 + 1 缺卡）
    const r6 = await call(c, "list_attendance_anomalies", { employee_name: "王五", month: "2026-08" });
    check("hr list_attendance_anomalies(王五,2026-08) 共 2 条且含迟到/缺卡", r6.includes("共 2 条") && r6.includes("迟到") && r6.includes("缺卡"));

    // 不传参数返回全部
    const r7 = await call(c, "list_attendance_anomalies");
    check("hr list_attendance_anomalies() 无参返回全部 6 条", r7.includes("共 6 条"));

    // 写操作校验失败：超额（写断言放在读断言之后，避免扣减额度影响前面结果）
    const e1 = await call(c, "submit_leave_request", { employee_name: "张三", leave_type: "年假", start_date: "2026-09-20", days: 999, reason: "smoke 超额校验" });
    check("hr submit_leave_request 999 天年假被拒（「错误：」且提示剩余 8 天）", e1.startsWith("错误：") && e1.includes("剩余 8 天"));

    // 写操作校验失败：days 非正整数
    const e2 = await call(c, "submit_leave_request", { employee_name: "张三", leave_type: "病假", start_date: "2026-09-20", days: 0, reason: "smoke 天数校验" });
    check("hr submit_leave_request days=0 被拒（「错误：」）", e2.startsWith("错误："));

    // 写操作校验失败：假种非法
    const e3 = await call(c, "submit_leave_request", { employee_name: "张三", leave_type: "婚假", start_date: "2026-09-20", days: 1, reason: "smoke 假种校验" });
    check("hr submit_leave_request 假种非法被拒（「错误：」）", e3.startsWith("错误："));

    // 写操作 happy path：首张单号 LV-20260913-001（today=2026-09-13，进程内自增初始 001）
    const w1 = await call(c, "submit_leave_request", { employee_name: "张三", leave_type: "年假", start_date: "2026-09-20", days: 1, reason: "smoke 正常提交" });
    check("hr submit_leave_request 成功返回 LV-20260913-001 与摘要", w1.includes("LV-20260913-001") && w1.includes("请假申请已提交") && w1.includes("剩余：7 天"));
  });
}

async function testHelpdesk() {
  await withServer("zhimesh-helpdesk", async (c) => {
    const { tools } = await c.listTools();
    check(
      `helpdesk listTools 集合一致（${tools.map((t) => t.name).join(",")}）`,
      JSON.stringify(sorted(tools.map((t) => t.name))) ===
        JSON.stringify(sorted(["search_tickets", "create_ticket", "get_account_status", "get_software_catalog"]))
    );

    // 全量工单
    const r1 = await call(c, "search_tickets");
    check("helpdesk search_tickets() 返回 10 张且含 TK-20260908-001", r1.includes("共 10 张") && r1.includes("TK-20260908-001") && r1.includes("VPN 无法连接公司内网"));

    // 状态 + 关键字组合过滤
    const r2 = await call(c, "search_tickets", { status: "处理中", keyword: "VPN" });
    check("helpdesk search_tickets(处理中,VPN) 仅命中张三那条", r2.includes("TK-20260908-001") && !r2.includes("TK-20260906-001"));

    // 状态过滤
    const r3 = await call(c, "search_tickets", { status: "待处理" });
    check("helpdesk search_tickets(待处理) 共 2 张", r3.includes("共 2 张"));

    // 锚点：张三密码 45 天后过期
    const r4 = await call(c, "get_account_status", { employee_name: "张三" });
    check('helpdesk get_account_status("张三") 账号/VPN/邮箱正常且密码 45 天后过期', r4.includes("域账号：正常") && r4.includes("VPN：正常") && r4.includes("邮箱：正常") && r4.includes("45 天后过期"));

    // 不在账号表 → 错误文本
    const r5 = await call(c, "get_account_status", { employee_name: "查无此人" });
    check("helpdesk get_account_status 未知员工返回「错误：」", r5.startsWith("错误："));

    // 软件目录
    const r6 = await call(c, "get_software_catalog");
    check("helpdesk get_software_catalog 共 10 项且含 IntelliJ IDEA/向日葵", r6.includes("共 10 项") && r6.includes("IntelliJ IDEA") && r6.includes("向日葵"));

    // 写操作校验失败：非法类别
    const e1 = await call(c, "create_ticket", { title: "smoke 非法类别", category: "外星人", urgency: "普通", description: "smoke", reporter: "张三" });
    check("helpdesk create_ticket 非法类别被拒（「错误：」）", e1.startsWith("错误：") && e1.includes("类别"));

    // 写操作校验失败：reporter 不在账号表
    const e2 = await call(c, "create_ticket", { title: "smoke 未知报告人", category: "VPN", urgency: "普通", description: "smoke", reporter: "曹操" });
    check("helpdesk create_ticket 报告人不在账号表被拒（「错误：」）", e2.startsWith("错误：") && e2.includes("报告人"));

    // 写操作校验失败：reporter 账号已禁用（离职员工谢鹏）
    const e3 = await call(c, "create_ticket", { title: "smoke 离职报告人", category: "VPN", urgency: "普通", description: "smoke", reporter: "谢鹏" });
    check("helpdesk create_ticket 账号已禁用的报告人被拒（「错误：」）", e3.startsWith("错误：") && e3.includes("已禁用"));

    // 写操作 happy path：首张单号 TK-20260913-001
    const w1 = await call(c, "create_ticket", { title: "smoke 新建工单验证", category: "VPN", urgency: "普通", description: "smoke 正常创建", reporter: "张三" });
    check("helpdesk create_ticket 成功返回 TK-20260913-001 与摘要", w1.includes("TK-20260913-001") && w1.includes("工单已创建"));

    // 同进程内建单后立即可查（写回内存列表的回归断言）
    const w2 = await call(c, "search_tickets", { keyword: "TK-20260913-001" });
    check("helpdesk create 后 search 立即可查新工单（待处理）", w2.includes("TK-20260913-001") && w2.includes("待处理") && w2.includes("smoke 新建工单验证"));
  });
}

async function testFinance() {
  await withServer("zhimesh-finance", async (c) => {
    // 工具名集合与契约完全一致（不多不少）
    const { tools } = await c.listTools();
    check(
      `finance listTools 集合一致（${tools.map((t) => t.name).join(",")}）`,
      JSON.stringify(sorted(tools.map((t) => t.name))) ===
        JSON.stringify(sorted(["search_expense_reports", "submit_expense_report", "get_budget_balance", "get_reimbursement_summary"]))
    );

    // 全量报销单（读断言统一排在写断言之前，避免 submit 写回影响结果）
    const r1 = await call(c, "search_expense_reports");
    check("finance search_expense_reports() 返回 9 条且含锚点 EX-20260910-001/待审批", r1.includes("共 9 条") && r1.includes("EX-20260910-001") && r1.includes("待审批"));

    // 状态 + 报销人组合过滤：恰命中张三那单
    const r2 = await call(c, "search_expense_reports", { status: "待审批", employee_name: "张三" });
    check("finance search_expense_reports(待审批,张三) 恰命中 1 条且含 ¥480.00", r2.includes("共 1 条") && r2.includes("EX-20260910-001") && r2.includes("¥480.00"));

    // 预算：全部部门，技术部剩余 = 50000 - 32400 = 17600
    const r3 = await call(c, "get_budget_balance");
    check("finance get_budget_balance() 含技术部与剩余 ¥17600.00", r3.includes("技术部") && r3.includes("剩余 ¥17600.00"));

    // 预算：部门不存在 → 错误文本
    const r4 = await call(c, "get_budget_balance", { department: "战略部" });
    check('finance get_budget_balance("战略部") 部门不存在返回「错误：」', r4.startsWith("错误："));

    // 锚点：张三年度汇总 = 3 单共 2380、待审批 480（必须在 submit 写操作之前断言）
    const r5 = await call(c, "get_reimbursement_summary", { employee_name: "张三" });
    check('finance get_reimbursement_summary("张三") 3 单/¥2380.00/待审批 ¥480.00', r5.includes("总单数：3 单") && r5.includes("¥2380.00") && r5.includes("待审批金额：¥480.00"));

    // 员工不存在 → 错误文本
    const r6 = await call(c, "get_reimbursement_summary", { employee_name: "查无此人" });
    check('finance get_reimbursement_summary 员工不存在返回「错误：」', r6.startsWith("错误："));

    // 写操作校验失败：差旅住宿 600 超类别单笔上限 500
    const e1 = await call(c, "submit_expense_report", { employee_name: "张三", category: "差旅住宿", amount: 600, expense_date: "2026-09-10", description: "smoke 超上限校验", invoice_count: 1 });
    check("finance submit 差旅住宿 600 超上限被拒（「错误：」且提到上限 500）", e1.startsWith("错误：") && e1.includes("500"));

    // 写操作校验失败：离职员工曹阳
    const e2 = await call(c, "submit_expense_report", { employee_name: "曹阳", category: "差旅交通", amount: 200, expense_date: "2026-09-10", description: "smoke 离职校验", invoice_count: 1 });
    check("finance submit 离职员工被拒（「错误：」且提示线下处理）", e2.startsWith("错误：") && e2.includes("离职"));

    // 写操作校验失败：invoice_count 0
    const e3 = await call(c, "submit_expense_report", { employee_name: "张三", category: "办公用品", amount: 100, expense_date: "2026-09-12", description: "smoke 发票校验", invoice_count: 0 });
    check("finance submit invoice_count=0 被拒（「错误：」且提到发票）", e3.startsWith("错误：") && e3.includes("发票"));

    // 写操作 happy path：首张单号 EX-20260914-001（today=2026-09-14，进程内自增初始 001）
    const w1 = await call(c, "submit_expense_report", { employee_name: "张三", category: "差旅交通", amount: 86.5, expense_date: "2026-09-12", description: "smoke 正常提交", invoice_count: 2 });
    check("finance submit 成功返回 EX-20260914-001/¥86.50/待审批", w1.includes("EX-20260914-001") && w1.includes("¥86.50") && w1.includes("待审批") && w1.includes("报销单已提交"));

    // 同进程内建单后立即可查（写回内存列表的回归断言）
    const w2 = await call(c, "search_expense_reports", { status: "待审批" });
    check("finance submit 后 search 立即可查新报销单（EX-20260914-001 待审批）", w2.includes("EX-20260914-001") && w2.includes("待审批"));
  });
}

(async () => {
  await testHr();
  await testHelpdesk();
  await testFinance();
  const pass = results.filter(Boolean).length;
  const fail = results.length - pass;
  console.log(`\n汇总：共 ${results.length} 条断言，PASS ${pass}，FAIL ${fail}`);
  process.exit(fail > 0 ? 1 : 0);
})().catch((err) => {
  console.error("SMOKE 运行异常:", err);
  process.exit(1);
});
