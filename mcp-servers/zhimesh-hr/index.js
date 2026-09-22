// zhimesh-hr —— 智聘科技人事系统 mock MCP server（stdio，CommonJS）
// 错误处理哲学：所有校验失败一律作为正常工具结果返回「错误：…」开头的文本，不抛异常，
// 模型读到错误文本可自行纠正参数或向用户转述（与 ZhiMesh 内置工具 run_workflow 的 preflight 拒绝策略一致）。
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { z } = require("zod");
const data = require("./data.json");

const LEAVE_TYPES = ["年假", "病假", "事假"];
let leaveCounter = 0; // 请假申请单进程内自增序号，从 001 起

const text = (t) => ({ content: [{ type: "text", text: t }] });
const findEmployee = (name) => data.employees.find((e) => e.name === name);

const server = new McpServer({ name: "zhimesh-hr", version: "1.0.0" });

// 1. 按姓名/工号/职位关键字模糊查询员工花名册，可按部门过滤
server.registerTool(
  "search_employee",
  {
    title: "查询员工",
    description: "按姓名/工号/职位关键字查询员工花名册，可按部门过滤",
    inputSchema: {
      keyword: z.string().describe("姓名、工号或职位的关键字"),
      department: z.string().optional().describe("部门名称过滤，可选"),
    },
  },
  async ({ keyword, department }) => {
    const kw = String(keyword).trim().toLowerCase();
    let list = data.employees.filter((e) =>
      [e.name, e.employeeId, e.title].some((v) => String(v).toLowerCase().includes(kw))
    );
    if (department && department.trim()) {
      const dep = department.trim();
      list = list.filter((e) => e.department.includes(dep) || dep.includes(e.department));
    }
    if (list.length === 0) {
      return text(`未找到与「${keyword}」匹配的员工${department ? `（部门过滤：${department}）` : ""}，请更换关键字重试。`);
    }
    const lines = list.map(
      (e) => `${e.employeeId} | ${e.name} | ${e.department} | ${e.title} | 入职 ${e.hireDate} | ${e.email} | ${e.status}`
    );
    return text(`共匹配 ${list.length} 名员工：\n` + lines.join("\n"));
  }
);

// 2. 全公司部门树：部门、负责人、人数
server.registerTool(
  "get_department_tree",
  {
    title: "查询部门树",
    description: "返回全公司部门树：部门、负责人、在职人数",
    inputSchema: {},
  },
  async () => {
    const total = data.departments.reduce((s, d) => s + d.headcount, 0);
    const lines = data.departments.map(
      (d, i) => `${i === data.departments.length - 1 ? "└─" : "├─"} ${d.name} | 负责人：${d.manager} | ${d.headcount} 人`
    );
    return text(`${data.company}部门树（在职共 ${total} 人）\n` + lines.join("\n"));
  }
);

// 3. 查询员工各假种额度：年假/病假/事假的总额、已用、剩余
server.registerTool(
  "get_leave_balance",
  {
    title: "查询请假额度",
    description: "查询指定员工各假种（年假/病假/事假）的总额、已用、剩余天数",
    inputSchema: {
      employee_name: z.string().describe("员工姓名"),
    },
  },
  async ({ employee_name }) => {
    const emp = findEmployee(employee_name);
    if (!emp) return text(`错误：未找到员工「${employee_name}」，请确认姓名（可先用 search_employee 查询）。`);
    const bal = data.leaveBalances[employee_name];
    const lines = Object.entries(bal).map(
      ([t, v]) => `- ${t}：总额 ${v.total} 天，已用 ${v.used} 天，剩余 ${v.total - v.used} 天`
    );
    return text(`${emp.name}（${emp.employeeId}，${emp.department}，${emp.status}）请假额度：\n` + lines.join("\n"));
  }
);

// 4. 考勤异常记录，可按员工/月份过滤，都空返回全部
server.registerTool(
  "list_attendance_anomalies",
  {
    title: "查询考勤异常",
    description: "查询考勤异常记录（类型：迟到/早退/缺卡/旷工），可按员工姓名与月份（YYYY-MM）过滤，不传参数返回全部",
    inputSchema: {
      employee_name: z.string().optional().describe("员工姓名过滤，可选"),
      month: z.string().optional().describe("月份过滤，格式 YYYY-MM，可选"),
    },
  },
  async ({ employee_name, month }) => {
    let list = data.attendanceAnomalies;
    if (employee_name && employee_name.trim()) list = list.filter((a) => a.employeeName === employee_name.trim());
    if (month && month.trim()) list = list.filter((a) => a.date.startsWith(month.trim()));
    if (list.length === 0) {
      return text(`未找到符合条件的考勤异常记录${employee_name ? `（员工：${employee_name}）` : ""}${month ? `（月份：${month}）` : ""}。`);
    }
    const lines = list.map((a) => `${a.date} | ${a.employeeName} | ${a.type} | ${a.note}`);
    return text(`共 ${list.length} 条考勤异常：\n` + lines.join("\n"));
  }
);

// 5. 提交请假申请（写操作）：单号 LV-YYYYMMDD-NNN，NNN 为进程内自增
server.registerTool(
  "submit_leave_request",
  {
    title: "提交请假申请",
    description: "为员工提交请假申请。假种支持 年假/病假/事假；天数不能超过该假种剩余额度。校验失败返回「错误：…」说明文本。",
    inputSchema: {
      employee_name: z.string().describe("员工姓名"),
      leave_type: z.string().describe("假种，合法值：年假 | 病假 | 事假"),
      start_date: z.string().describe("开始日期，格式 YYYY-MM-DD"),
      days: z.number().describe("请假天数，正整数"),
      reason: z.string().describe("请假事由"),
    },
  },
  async ({ employee_name, leave_type, start_date, days, reason }) => {
    const emp = findEmployee(employee_name);
    if (!emp) return text(`错误：未找到员工「${employee_name}」，请确认姓名（可先用 search_employee 查询）。`);
    if (emp.status !== "在职") {
      return text(`错误：员工 ${emp.name}（${emp.employeeId}）状态为${emp.status}，无法提交请假申请。`);
    }
    if (!LEAVE_TYPES.includes(leave_type)) {
      return text(`错误：假种「${leave_type}」不合法，仅支持：${LEAVE_TYPES.join("、")}。`);
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(start_date)) {
      return text(`错误：开始日期「${start_date}」格式不合法，应为 YYYY-MM-DD。`);
    }
    if (!Number.isInteger(days) || days <= 0) {
      return text(`错误：请假天数必须为正整数，当前为 ${days}。`);
    }
    const bal = data.leaveBalances[employee_name][leave_type];
    const remaining = bal.total - bal.used;
    if (days > remaining) {
      return text(
        `错误：申请 ${days} 天${leave_type}超出剩余额度，${emp.name}的${leave_type}总额 ${bal.total} 天、已用 ${bal.used} 天、剩余 ${remaining} 天，请调整天数或改约 HR 线下处理。`
      );
    }
    bal.used += days; // 进程内即时扣减额度
    leaveCounter += 1;
    const sn = `LV-${data.today.replace(/-/g, "")}-${String(leaveCounter).padStart(3, "0")}`;
    return text(
      [
        "请假申请已提交。",
        `申请单号：${sn}`,
        `申请人：${emp.name}（${emp.employeeId}，${emp.department}）`,
        `假种：${leave_type}`,
        `开始日期：${start_date}`,
        `天数：${days} 天`,
        `事由：${reason}`,
        `提交后${leave_type}剩余：${bal.total - bal.used} 天`,
      ].join("\n")
    );
  }
);

async function main() {
  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  console.error("zhimesh-hr server 启动失败:", err);
  process.exit(1);
});
