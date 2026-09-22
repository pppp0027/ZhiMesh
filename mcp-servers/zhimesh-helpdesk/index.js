// zhimesh-helpdesk —— 智聘科技 IT 服务台 mock MCP server（stdio，CommonJS）
// 错误处理哲学：所有校验失败一律作为正常工具结果返回「错误：…」开头的文本，不抛异常，
// 模型读到错误文本可自行纠正参数或向用户转述（与 ZhiMesh 内置工具 run_workflow 的 preflight 拒绝策略一致）。
// 类别/紧急度等枚举参数在 zod 层宽松接收为 string，由 handler 内白名单校验，
// 这样非法值走「错误：…」文本而不是协议层校验失败。
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { z } = require("zod");
const data = require("./data.json");

const CATEGORIES = ["VPN", "账号", "硬件", "软件", "网络", "邮箱"];
const URGENCIES = ["普通", "紧急", "危急"];
const STATUSES = ["待处理", "处理中", "已解决", "已关闭"];
let ticketCounter = 0; // 新建工单进程内自增序号，从 001 起

const text = (t) => ({ content: [{ type: "text", text: t }] });

const server = new McpServer({ name: "zhimesh-helpdesk", version: "1.0.0" });

// 1. 工单查询，可按状态与关键字过滤
server.registerTool(
  "search_tickets",
  {
    title: "查询工单",
    description: "查询 IT 服务台工单列表，可按状态与关键字（编号/标题/类别/报告人）过滤，不传参数返回全部",
    inputSchema: {
      status: z.string().optional().describe(`状态过滤，合法值：${STATUSES.join(" | ")}，可选`),
      keyword: z.string().optional().describe("关键字，匹配工单编号/标题/类别/报告人，可选"),
    },
  },
  async ({ status, keyword }) => {
    let list = data.tickets;
    if (status && status.trim()) {
      if (!STATUSES.includes(status.trim())) {
        return text(`错误：状态「${status}」不合法，仅支持：${STATUSES.join("、")}。`);
      }
      list = list.filter((t) => t.status === status.trim());
    }
    if (keyword && keyword.trim()) {
      const kw = keyword.trim().toLowerCase();
      list = list.filter((t) =>
        [t.id, t.title, t.category, t.reporter].some((v) => String(v).toLowerCase().includes(kw))
      );
    }
    if (list.length === 0) {
      return text(`未找到符合条件的工单${status ? `（状态：${status}）` : ""}${keyword ? `（关键字：${keyword}）` : ""}。`);
    }
    const lines = list.map(
      (t) => `${t.id} | ${t.title} | ${t.category} | ${t.urgency} | ${t.status} | 报告人：${t.reporter} | 创建时间：${t.createdAt}`
    );
    return text(`共 ${list.length} 张工单：\n` + lines.join("\n"));
  }
);

// 2. 新建工单（写操作）：工单号 TK-YYYYMMDD-NNN，NNN 为进程内自增
server.registerTool(
  "create_ticket",
  {
    title: "新建工单",
    description: "向 IT 服务台提交新工单。类别支持 VPN/账号/硬件/软件/网络/邮箱；紧急度支持 普通/紧急/危急；报告人必须是账号表中的员工。校验失败返回「错误：…」说明文本。",
    inputSchema: {
      title: z.string().describe("工单标题，简明描述问题"),
      category: z.string().describe(`类别，合法值：${CATEGORIES.join(" | ")}`),
      urgency: z.string().describe(`紧急度，合法值：${URGENCIES.join(" | ")}`),
      description: z.string().describe("问题详细描述"),
      reporter: z.string().describe("报告人姓名（须为在职账号表中员工）"),
    },
  },
  async ({ title, category, urgency, description, reporter }) => {
    if (!title || !String(title).trim()) return text("错误：工单标题（title）不能为空。");
    if (!description || !String(description).trim()) return text("错误：问题描述（description）不能为空。");
    if (!CATEGORIES.includes(category)) {
      return text(`错误：类别「${category}」不合法，仅支持：${CATEGORIES.join("、")}。`);
    }
    if (!URGENCIES.includes(urgency)) {
      return text(`错误：紧急度「${urgency}」不合法，仅支持：${URGENCIES.join("、")}。`);
    }
    const account = data.accounts.find((a) => a.employeeName === reporter);
    if (!account) {
      return text(`错误：报告人「${reporter}」不在账号表中，请确认员工姓名（可先用 get_account_status 查询）。`);
    }
    if (account.account !== "正常") {
      return text(`错误：报告人「${reporter}」（${account.employeeId}）的域账号状态为「${account.account}」，无法作为工单报告人提交。`);
    }
    ticketCounter += 1;
    const id = `TK-${data.today.replace(/-/g, "")}-${String(ticketCounter).padStart(3, "0")}`;
    // 新工单写回内存列表：同一进程内（同一轮对话）create 后 search 立即可查
    // 注意：后端每次聊天请求都会重新拉起本进程，跨轮不保留（mock 定位，README 已注明）
    data.tickets.push({
      id,
      title,
      category,
      urgency,
      status: "待处理",
      reporter: account.employeeName,
      createdAt: `${data.today} 09:00`,
    });
    return text(
      [
        "工单已创建。",
        `工单号：${id}`,
        `标题：${title}`,
        `类别：${category}｜紧急度：${urgency}`,
        `报告人：${account.employeeName}（${account.employeeId}）`,
        `问题描述：${description}`,
        "状态：待处理（IT 服务台将在工作时间响应）",
      ].join("\n")
    );
  }
);

// 3. 查询员工账号/VPN/邮箱状态与密码过期天数
server.registerTool(
  "get_account_status",
  {
    title: "查询账号状态",
    description: "查询指定员工的域账号、VPN、企业邮箱状态以及密码过期剩余天数",
    inputSchema: {
      employee_name: z.string().describe("员工姓名"),
    },
  },
  async ({ employee_name }) => {
    const a = data.accounts.find((x) => x.employeeName === employee_name);
    if (!a) {
      return text(`错误：未找到员工「${employee_name}」的账号信息，请确认姓名（可先用 search_employee 查询花名册）。`);
    }
    return text(
      [
        `${a.employeeName}（${a.employeeId}）账号状态：`,
        `- 域账号：${a.account}`,
        `- VPN：${a.vpn}`,
        `- 企业邮箱：${a.email}`,
        `- 密码：${a.passwordExpiresInDays} 天后过期${a.passwordExpiresInDays <= 7 ? "（即将过期，请尽快修改）" : ""}`,
      ].join("\n")
    );
  }
);

// 4. 可申请软件清单
server.registerTool(
  "get_software_catalog",
  {
    title: "查询软件目录",
    description: "返回公司可申请安装的软件清单：名称、类别、是否需审批、说明",
    inputSchema: {},
  },
  async () => {
    const lines = data.softwareCatalog.map(
      (s) => `${s.name} | ${s.category} | ${s.approvalRequired ? "需审批" : "免审批"} | ${s.note}`
    );
    return text(`可申请软件清单（共 ${data.softwareCatalog.length} 项）：\n` + lines.join("\n"));
  }
);

async function main() {
  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  console.error("zhimesh-helpdesk server 启动失败:", err);
  process.exit(1);
});
