import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZhiMesh MCP 演示种子数据执行器（Java 17 单文件源码启动）。
 *
 * 用法（在 mcp-servers 目录下执行；连接参数全部来自命令行，本文件不含任何 IP、账号或密码）：
 *   java -cp "<postgresql-42.6.1.jar 路径>" demo/JdbcSeedRunner.java
 *        jdbc:postgresql://<host>:5432/<db> <user> <password> demo/seed.sql
 *
 * 行为：
 *   1. 按 UTF-8 读取 seed.sql；
 *   2. 剥离不在 dollar-quote 区域内的 -- 注释行；
 *   3. 按行累积语句，仅当一行以分号结尾、且该行结束时不在 dollar-quote（如 $prompt$ ... $prompt$）
 *      区域内时切分——简单状态机跟踪 $tag$ 的开闭，因此提示词内的换行、分号不会被误切；
 *   4. 逐条执行，每条打印「OK 第N条: 语句前 40 字符」；任何一条失败则打印完整错误并以退出码 1 结束；
 *   5. 全部执行完后打印成功条数汇总。
 *
 * 编码说明：字符串字面量中的中文一律写成 unicode 转义序列（反斜杠加 u 加 4 位十六进制），
 * 保证本文件（UTF-8 保存）在任意平台默认编码（如 Windows JDK 17 的 GBK）下通过单文件源码
 * 启动编译后，输出仍是正确中文；注释中的中文不做转义，即使被错误解码也不影响编译。
 */
public class JdbcSeedRunner {

    // dollar-quote 记号：$$ 或 $tag$（tag 为字母/下划线开头的标识符，$prompt$ 也匹配）
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    public static void main(String[] args) {
        // 输出统一走 UTF-8（Git Bash / VS Code / Windows Terminal 直接正常显示；
        // 老版 cmd 若乱码可先执行 chcp 65001，不影响执行结果本身）
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        if (args.length != 4) {
            System.err.println(
                    "\u7528\u6cd5: java -cp <postgresql-jar> demo/JdbcSeedRunner.java"
                            + " <jdbc-url> <user> <password> <seed.sql>");
            System.err.println("\u53c2\u6570\u4e2a\u6570\u4e0d\u7b26\uff0c\u5b9e\u9645\u4e3a: " + args.length);
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        Path seedFile = Path.of(args[3]);

        // 1. 解析 seed 文件（UTF-8 + 剥离注释行 + dollar-quote 感知的分号切分）
        List<String> statements;
        try {
            statements = splitStatements(seedFile);
        } catch (IOException e) {
            System.err.println("\u8bfb\u53d6 seed \u6587\u4ef6\u5931\u8d25: " + seedFile.toAbsolutePath());
            e.printStackTrace();
            System.exit(2);
            return;
        }
        if (statements.isEmpty()) {
            System.err.println("no executable statement found in: " + seedFile.toAbsolutePath());
            System.exit(2);
            return;
        }
        System.out.println("seed file: " + seedFile.toAbsolutePath() + ", statements: " + statements.size());

        // 2. 加载驱动（需通过 -cp 提供 postgresql-*.jar）
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println(
                    "\u672a\u627e\u5230 PostgreSQL JDBC \u9a71\u52a8\uff0c\u8bf7\u7528 -cp \u6307\u5b9a postgresql-*.jar");
            System.exit(2);
            return;
        }

        // 3. 建立连接（参数全部来自命令行）
        Connection conn;
        try {
            conn = DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            System.err.println("JDBC \u8fde\u63a5\u5931\u8d25: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // 4. 逐条执行
        int ok = 0;
        try (conn) {
            for (String sql : statements) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
                ok++;
                System.out.println("OK \u7b2c" + ok + "\u6761: " + preview(sql, 40));
            }
        } catch (SQLException e) {
            String failing = ok < statements.size() ? statements.get(ok) : "";
            System.err.println("\u6267\u884c\u5931\u8d25\uff08\u7b2c " + (ok + 1) + " \u6761\u8bed\u53e5\uff09:");
            System.err.println(preview(failing, Integer.MAX_VALUE));
            System.err.println("SQLState=" + e.getSQLState() + ", ErrorCode=" + e.getErrorCode());
            e.printStackTrace();
            System.exit(1);
            return;
        }
        System.out.println("seed \u6267\u884c\u5b8c\u6210\uff1a\u6210\u529f " + ok + " \u6761\u8bed\u53e5");
    }

    /**
     * 按 UTF-8 读取 SQL 文件并切分为语句列表。
     * 规则：
     *   - 当前不在 dollar-quote 内、且整行以 -- 开头 => 注释行，整行丢弃；
     *   - 扫描本行出现的所有 $tag$ 记号维护 dollar-quote 开闭状态；
     *   - 仅当一行结束时不在 dollar-quote 内、且该行（去首尾空白）以分号结尾 => 语句结束；
     *   - 切分出的语句去掉首尾空白，空白语句跳过；文件末尾的残余非空内容也作为一条语句。
     */
    static List<String> splitStatements(Path file) throws IOException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        String quoteTag = null;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!inDollarQuote && line.stripLeading().startsWith("--")) {
                continue;
            }
            Matcher m = DOLLAR_TAG.matcher(line);
            while (m.find()) {
                if (!inDollarQuote) {
                    inDollarQuote = true;
                    quoteTag = m.group();
                } else if (m.group().equals(quoteTag)) {
                    inDollarQuote = false;
                    quoteTag = null;
                }
            }
            current.append(line).append('\n');
            if (!inDollarQuote && line.strip().endsWith(";")) {
                String sql = current.toString().strip();
                if (!sql.isBlank()) {
                    statements.add(sql);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isBlank()) {
            statements.add(tail);
        }
        return statements;
    }

    // 把语句压成单行（空白折叠为单个空格）并截取前 max 个字符，便于日志展示
    private static String preview(String sql, int max) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
