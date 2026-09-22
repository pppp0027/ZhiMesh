// zhimesh-finance —— 智聘科技财务报销 mock MCP server（stdio，CommonJS）
// 错误处理哲学：所有校验失败一律作为正常工具结果返回「错误：…」开头的文本，不抛异常，
// 模型读到错误文本可自行纠正参数或向用户转述（与 ZhiMesh 内置工具 run_workflow 的 preflight 拒绝策略一致）。
// 状态/类别等枚举参数在 zod 层宽松接收为 string，由 handler 内白名单校验，
// 这样非法值走「错误：…」文本而不是协议层校验失败。
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { z } = require("zod");
const data = require("./data.json");

const STATUSES = ["待审批", "已报销", "已驳回"];
const CATEGORY_LIMITS = { 差旅住宿: 500, 差旅交通: 2000, 出差餐补: 100, 办公用品: 1000, 培训费: 5000 };
const CATEGORIES = Object.keys(CATEGORY_LIMITS);
const DATE_MIN = "2026-01-01"; // 可报销费用日期下限，上限为 data.today
let expenseCounter = 0; // 新报销单进程内自增序号，从 001 起

const text = (t) => ({ content: [{ type: "text", text: t }] });
const fmt = (n) => `¥${Number(n).toFixed(2)}`; // 金额统一 ¥ + 两位小数，不加千位分隔符
const findEmployee = (name) => data.employees.find((e) => e.name === name);

const server = new McpServer({ name: "zhimesh-finance", version: "1.0.0" });

// 1. 报销单查询，可按状态与报销人过滤，都空返回全部
server.registerTool(
  "search_expense_reports",
  {
    title: "查询报销单",
    description: "查询员工费用报销单列表，可按状态与报销人姓名过滤，不传参数返回全部",
    inputSchema: {
      status: z.string().optional().describe(`状态过滤，合法值：${STATUSES.join(" | ")}，可选`),
      employee_name: z.string().optional().describe("报销人姓名过滤，可选"),
    },
  },
  async ({ status, employee_name }) => {
    let list = data.expenseReports;
    if (status && status.trim()) {
      if (!STATUSES.includes(status.trim())) {
        return text(`错误：状态「${status}」不合法，仅支持：${STATUSES.join("、")}。`);
      }
      list = list.filter((r) => r.status === status.trim());
    }
    if (employee_name && employee_name.trim()) {
      list = list.filter((r) => r.employeeName === employee_name.trim());
    }
    if (list.length === 0) {
      return text(`未找到符合条件的报销单${status ? `（状态：${status}）` : ""}${employee_name ? `（报销人：${employee_name}）` : ""}。`);
    }
    const lines = list.map(
      (r) => `${r.id} | ${r.employeeName} | ${r.category} | ${fmt(r.amount)} | 发票${r.invoiceCount}张 | ${r.status} | ${r.submittedDate} | ${r.note}`
    );
    return text(`共 ${list.length} 条报销单：\n` + lines.join("\n"));
  }
);

// 2. 提交报销单（写操作）：单号 EX-YYYYMMDD-NNN，NNN 为进程内自增
server.registerTool(
  "submit_expense_report",
  {
    title: "提交报销单",
    description: `为员工提交费用报销单。类别支持 ${CATEGORIES.join("/")}，各类别单笔上限：差旅住宿 ¥500、差旅交通 ¥2000、出差餐补 ¥100、办公用品 ¥1000、培训费 ¥5000；报销人必须是在册在职员工；费用日期须在 ${DATE_MIN} 至 ${data.today} 之间；至少附 1 张发票。校验失败返回「错误：…」说明文本。`,
    inputSchema: {
      employee_name: z.string().describe("报销人姓名（须为在册在职员工）"),
      category: z.string().describe(`费用类别，合法值：${CATEGORIES.join(" | ")}`),
      amount: z.number().describe("报销金额（元），须大于 0 且不超过类别单笔上限"),
      expense_date: z.string().describe(`费用日期，格式 YYYY-MM-DD，须在 ${DATE_MIN} 至 ${data.today} 之间`),
      description: z.string().describe("费用事由说明"),
      invoice_count: z.number().describe("发票张数，至少 1"),
    },
  },
  async ({ employee_name, category, amount, expense_date, description, invoice_count }) => {
    // 校验顺序固定：在册 → 在职 → 类别 → 日期 → 金额 → 发票，先命中先返回
    const emp = findEmployee(employee_name);
    if (!emp) return text(`错误：未找到员工「${employee_name}」，请确认员工姓名。`);
    if (emp.status !== "在职") {
      return text(`错误：员工 ${emp.name}（${emp.employeeId}）状态为${emp.status}，无法提交报销，请联系 HR 线下处理。`);
    }
    if (!CATEGORIES.includes(category)) {
      return text(`错误：类别「${category}」不合法，仅支持：${CATEGORIES.join("、")}。`);
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(expense_date)) {
      return text(`错误：费用日期「${expense_date}」格式不合法，应为 YYYY-MM-DD。`);
    }
    if (expense_date < DATE_MIN || expense_date > data.today) {
      return text(`错误：费用日期 ${expense_date} 超出可报销范围（${DATE_MIN} 至 ${data.today}），请核对后重试。`);
    }
    if (!(typeof amount === "number" && amount > 0)) {
      return text(`错误：报销金额必须大于 0，当前为 ${amount}。`);
    }
    const limit = CATEGORY_LIMITS[category];
    if (amount > limit) {
      return text(`错误：${category}报销金额 ${fmt(amount)} 超出类别单笔上限 ${fmt(limit)}，如需特批请走线下流程。`);
    }
    if (!(Number.isInteger(invoice_count) && invoice_count >= 1)) {
      return text(`错误：报销需附发票，至少 1 张，当前为 ${invoice_count} 张。`);
    }
    expenseCounter += 1;
    const id = `EX-${data.today.replace(/-/g, "")}-${String(expenseCounter).padStart(3, "0")}`;
    // 新报销单写回内存列表：同一进程内（同一轮对话）submit 后 search 立即可查
    // 注意：后端每次聊天请求都会重新拉起本进程，跨轮不保留（mock 定位，README 已注明）
    data.expenseReports.push({
      id,
      employeeName: emp.name,
      category,
      amount,
      invoiceCount: invoice_count,
      status: "待审批",
      submittedDate: data.today,
      note: description,
    });
    return text(
      [
        "报销单已提交。",
        `单号：${id}`,
        `报销人：${emp.name}（${emp.employeeId}，${emp.status}）`,
        `类别：${category}`,
        `金额：${fmt(amount)}`,
        `费用日期：${expense_date}`,
        `发票：${invoice_count} 张`,
        `事由：${description}`,
        "状态：待审批（财务将在 3 个工作日内审批）",
      ].join("\n")
    );
  }
);

// 3. 部门季度预算查询：总额/已用/剩余，不传部门返回全部
server.registerTool(
  "get_budget_balance",
  {
    title: "查询部门预算",
    description: `查询各部门 ${data.quarter} 预算的总额、已用与剩余，不传部门返回全部`,
    inputSchema: {
      department: z.string().optional().describe("部门名称过滤，可选"),
    },
  },
  async ({ department }) => {
    let list = data.budgets;
    if (department && department.trim()) {
      list = list.filter((b) => b.department === department.trim());
      if (list.length === 0) {
        return text(`错误：未找到部门「${department}」的预算信息，合法部门：${data.budgets.map((b) => b.department).join("、")}。`);
      }
    }
    const lines = list.map(
      (b) => `${b.department} | 总额 ${fmt(b.total)} | 已用 ${fmt(b.used)} | 剩余 ${fmt(b.total - b.used)}`
    );
    return text(`${data.company}${data.quarter}部门预算（共 ${list.length} 个部门）：\n` + lines.join("\n"));
  }
);

// 4. 员工 2026 年度报销汇总：总单数、总金额、待审批金额 + 最近 3 单简表
server.registerTool(
  "get_reimbursement_summary",
  {
    title: "查询个人报销汇总",
    description: "查询指定员工 2026 年度报销汇总：总单数、总金额、待审批金额，以及最近 3 单简表",
    inputSchema: {
      employee_name: z.string().describe("员工姓名"),
    },
  },
  async ({ employee_name }) => {
    const emp = findEmployee(employee_name);
    if (!emp) return text(`错误：未找到员工「${employee_name}」，请确认员工姓名。`);
    const list = data.expenseReports.filter((r) => r.employeeName === employee_name);
    if (list.length === 0) {
      return text(`${emp.name}（${emp.employeeId}，${emp.status}）2026 年度暂无报销单。`);
    }
    const totalAmount = list.reduce((s, r) => s + r.amount, 0);
    const pendingAmount = list
      .filter((r) => r.status === "待审批")
      .reduce((s, r) => s + r.amount, 0);
    const recent = [...list].sort((a, b) => (a.submittedDate < b.submittedDate ? 1 : -1)).slice(0, 3);
    const lines = recent.map(
      (r) => `${r.id} | ${r.category} | ${fmt(r.amount)} | 发票${r.invoiceCount}张 | ${r.status} | ${r.submittedDate}`
    );
    return text(
      [
        `${emp.name}（${emp.employeeId}，${emp.status}）2026 年度报销汇总：`,
        `- 总单数：${list.length} 单`,
        `- 总金额：${fmt(totalAmount)}`,
        `- 待审批金额：${fmt(pendingAmount)}`,
        `最近 ${recent.length} 单：`,
        ...lines,
      ].join("\n")
    );
  }
);

async function main() {
  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  console.error("zhimesh-finance server 启动失败:", err);
  process.exit(1);
});
