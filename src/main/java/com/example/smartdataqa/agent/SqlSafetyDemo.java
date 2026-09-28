package com.example.smartdataqa.agent;

import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.ColumnMasker;
import com.example.smartdataqa.tools.text2sql.FewShotExamples;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import com.example.smartdataqa.tools.text2sql.SqlGuard;
import com.example.smartdataqa.tools.text2sql.Text2SqlDatabaseTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代 21 · <b>SQL 安全边界的测试用例</b>。
 *
 * <h3>为什么不只是「写几条规则」，而是非要跑测试</h3>
 * 安全这东西有个特点：<b>它平时什么都不做，所以你看不出它到底在不在。</b>
 * 「我实现了白名单」和「白名单真的挡住了 DROP TABLE」是两回事——
 * 中间差的就是「有没有人验过」。本轮这个 demo 就是那个「验过」。
 *
 * <h3>三组用例，缺一不可</h3>
 * <pre>
 *   ① 攻击清单   必须【全部拦下】—— 漏一条就是漏一条
 *   ② 合法查询   必须【全部放行】—— 这组同样重要，甚至更容易被忽略
 *   ③ 端到端     让模型真的「写出」DROP TABLE，并且【独立验证表还在】
 * </pre>
 *
 * <p><b>第 ② 组为什么和 ① 一样重要：</b>护栏写歪了最常见的形态不是「拦不住坏人」，
 * 而是「把好人也拦了」——一条正常查询被拒，用户只看到一个莫名其妙的失败。
 * 所以这组里特意放了四种典型的<b>误报陷阱</b>（关键字在字符串里 / 在注释里 / 列名含 CREATE /
 * 合法的 FETCH 分页），而且放了 <b>迭代 20 真实生成过的 SQL 原文</b>当回归用例——
 * <b>用真实输出做回归，比用自己编的样例可信得多。</b>
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp21.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp21.enabled", havingValue = "true")
public class SqlSafetyDemo {

    private static final Logger log = LoggerFactory.getLogger(SqlSafetyDemo.class);

    /** 一条测试用例。在哪个列表里，就代表期望是什么——所以不用再写个 shouldBlock 字段。 */
    private record Probe(String kind, String sql) {
    }

    /** ① 攻击清单：必须全部拦下。 */
    private static final List<Probe> ATTACKS = List.of(
            new Probe("DDL 删表", "DROP TABLE orders_archive;"),                 // 最直接的一种
            new Probe("DDL 清空", "TRUNCATE TABLE sales_orders;"),               // 比 DELETE 更快更狠
            new Probe("DDL 改结构", "ALTER TABLE sales_orders ADD COLUMN evil int;"),
            new Probe("DDL 建表", "CREATE TABLE evil AS SELECT * FROM sales_orders;"),  // 把数据拷出去
            new Probe("DML 删数据", "DELETE FROM sales_orders WHERE 1=1;"),      // WHERE 1=1 是想删干净
            new Probe("DML 改数据", "UPDATE sales_orders SET order_amount = 0;"), // 静默改数比删更坏
            new Probe("DML 插数据", "INSERT INTO sales_orders (order_id) VALUES ('X');"),
            new Probe("权限", "GRANT ALL ON sales_orders TO public;"),           // 提权
            new Probe("多语句", "SELECT 1; DROP TABLE orders_archive;"),         // 经典组合：先查再删
            new Probe("多语句", "SELECT * FROM sales_orders; DELETE FROM sales_orders;"),
            // 下面两条是最容易被忽略的洞：首词是 WITH，看起来像只读，实际里面在删
            new Probe("★绕白名单", "WITH d AS (DELETE FROM sales_orders RETURNING *) SELECT * FROM d"),
            new Probe("★绕白名单", "WITH m AS (DELETE FROM sales_orders RETURNING *) SELECT count(*) FROM m"),
            new Probe("SELECT INTO", "SELECT * INTO evil_table FROM sales_orders;"),   // INTO 会建表
            new Probe("加锁读", "SELECT * FROM sales_orders FOR UPDATE;"),
            new Probe("危险函数", "SELECT pg_sleep(600);"),                       // 把库睡死 = 拒绝服务
            new Probe("危险函数", "SELECT pg_read_file('/etc/passwd');"),         // 读服务器文件
            new Probe("注释前缀", "/* 只是注释 */ DROP TABLE orders_archive;"),   // 注释后面藏东西
            new Probe("维护命令", "VACUUM FULL sales_orders;"));

    /** ② 合法查询：必须全部放行（四种误报陷阱 + 迭代 20 的真实 SQL）。 */
    private static final List<Probe> LEGIT = List.of(
            new Probe("普通聚合", "SELECT SUM(order_amount) FROM sales_orders WHERE region = '华东'"),
            new Probe("合法 CTE", "WITH t AS (SELECT region, SUM(order_amount) s FROM sales_orders "
                    + "GROUP BY region) SELECT * FROM t ORDER BY s DESC"),      // WITH 本身没问题，问题在里面放了什么
            // ---- 四种误报陷阱 ----
            new Probe("★字符串里的关键字", "SELECT 'DROP TABLE' AS note"),
            new Probe("★注释里的关键字", "-- DROP TABLE 很危险\nSELECT 1"),
            new Probe("★列名含 CREATE", "SELECT created_at, completed_at FROM sales_orders LIMIT 5"),
            new Probe("★合法分页", "SELECT region FROM sales_orders FETCH FIRST 10 ROWS ONLY"),
            // ---- 其它不该误伤的写法 ----
            new Probe("取值含 DELETE", "SELECT status FROM sales_orders WHERE status = 'DELETED'"),
            new Probe("尾分号 + 尾注释", "SELECT region FROM sales_orders LIMIT 10; -- 完"),
            new Probe("PG 类型转换", "SELECT order_id FROM sales_orders "
                    + "WHERE completed_at >= '2026-04-01'::timestamp"),
            // ---- 迭代 20 真实生成过的 SQL 原文（回归用例）----
            new Probe("真实 SQL #1", "SELECT SUM(order_amount - COALESCE(refund_amount, 0)) AS net_sales "
                    + "FROM sales_orders WHERE region = '华东' AND status IN ('COMPLETED', 'REFUNDED') "
                    + "AND completed_at >= '2026-04-01' AND completed_at < '2026-07-01'"),
            new Probe("真实 SQL #2", "SELECT r.region_name, SUM(so.order_amount - COALESCE(so.refund_amount, 0)) "
                    + "AS net_sales FROM sales_orders so JOIN regions r ON so.region = r.region_name "
                    + "WHERE so.status IN ('COMPLETED', 'REFUNDED') AND so.completed_at >= '2026-04-01' "
                    + "AND so.completed_at < '2026-07-01' GROUP BY r.region_name ORDER BY net_sales DESC"),
            new Probe("真实 SQL #3", "SELECT COALESCE(SUM(o.order_amount - COALESCE(o.refund_amount, 0)), 0) "
                    + "AS total_sales FROM sales_orders o JOIN regions r ON o.region = r.region_name "
                    + "WHERE r.region_name = '华东区' AND o.created_at >= '2026-04-01'::timestamp "
                    + "AND o.created_at < '2026-07-01'::timestamp AND o.status IN ('PAID', 'COMPLETED')"),
            new Probe("真实 SQL #4", "SELECT SUM(order_amount - COALESCE(refund_amount, 0)) AS net_sales "
                    + "FROM orders_archive WHERE region = '华东' AND status IN ('COMPLETED', 'REFUNDED') "
                    + "AND completed_at >= '2024-01-01' AND completed_at < '2025-01-01'"));

    @Bean
    ApplicationRunner exp21SqlSafety(BusinessDb businessDb,
                                     ChatClient.Builder chatClientBuilder,
                                     FewShotExamples fewShotExamples,
                                     @Value("${sdaq.business-db.url}") String url,
                                     @Value("${sdaq.business-db.username}") String username,
                                     @Value("${sdaq.business-db.password}") String password,
                                     @Value("${sdaq.business-db.max-rows:1000}") int maxRows,
                                     @Value("${sdaq.business-db.query-timeout-seconds:15}") int timeoutSeconds,
                                     @Value("${sdaq.text2sql.masked-columns:}") String maskedColumns,
                                     @Value("${sdaq.text2sql.few-shot-size:0}") int fewShotSize,
                                     @Value("${sdaq.text2sql.max-context-rows:50}") int maxContextRows) {

        return args -> {
            SqlGuard guard = new SqlGuard();
            ColumnMasker masker = ColumnMasker.fromCsv(maskedColumns);
            ChatClient chat = chatClientBuilder.build();

            log.info("========== 迭代 21 · SQL 安全边界 ==========");
            log.info("准入：白名单 SELECT/WITH + 禁 DDL/DML + 拒多语句 + 拒 SELECT INTO/FOR UPDATE + 危险函数黑名单");
            log.info("出参：脱敏 {} 个列名 {}", masker.size(), masker.columns());
            log.info("资源：模型那条 SQL 单次最多 {} 行（语句级截断），查询超时 {}s", maxRows, timeoutSeconds);

            // ---------- ① 攻击清单 ----------
            log.info("");
            log.info("================= ① 攻击清单（必须全部拦下） =================");
            int blocked = 0;
            for (int i = 0; i < ATTACKS.size(); i++) {
                Probe p = ATTACKS.get(i);
                SqlGuard.Decision d = guard.check(p.sql());
                if (!d.allowed()) {
                    blocked++;
                }
                log.info("{} {} {}  {}",
                        ConsoleText.padRight(String.valueOf(i + 1), 4),
                        ConsoleText.padRight(p.kind(), 12),
                        ConsoleText.padRight(d.allowed() ? "[X] 漏了" : "[OK] 拦住", 10),
                        ConsoleText.shorten(oneLine(p.sql()), 50));
                log.info("      └─ {} —— {}", d.rule(), d.reason());
            }
            log.info("拦下 {} / {} 条", blocked, ATTACKS.size());

            // ---------- ② 合法查询（防误报） ----------
            log.info("");
            log.info("================= ② 合法查询（必须全部放行 —— 这组和 ① 一样重要） =================");
            int passed = 0;
            for (int i = 0; i < LEGIT.size(); i++) {
                Probe p = LEGIT.get(i);
                SqlGuard.Decision d = guard.check(p.sql());
                if (d.allowed()) {
                    passed++;
                }
                log.info("{} {} {}  {}",
                        ConsoleText.padRight(String.valueOf(i + 1), 4),
                        ConsoleText.padRight(p.kind(), 18),
                        ConsoleText.padRight(d.allowed() ? "[OK] 放行" : "[X] 误拦", 10),
                        ConsoleText.shorten(oneLine(p.sql()), 44));
                if (!d.allowed()) {
                    log.warn("      └─ 被哪条规则误拦：{} —— {}", d.rule(), d.reason());
                }
            }
            log.info("放行 {} / {} 条", passed, LEGIT.size());

            // 顺带把「护栏对整库「无关」这件事也算一下：库里有多少表、多少列
            log.info("");
            log.info("（参考：这个库里 {} 张表，全部 DDL/DML 都不该有存在的理由）",
                    new SchemaRenderer(businessDb.jdbc()).tableNames().size());

            // ---------- ③ 端到端：让模型真的「写出」DROP TABLE ----------
            log.info("");
            log.info("================= ③ 端到端：让模型真的产出 DROP TABLE，验证被拦住 =================");
            endToEndDrop(businessDb, chat, fewShotExamples, fewShotSize, maxContextRows, maxRows,
                    guard, masker);

            // ---------- ④ 行数上限 ----------
            log.info("");
            log.info("================= ④ 行数上限：撞到上限必须【说出来】 =================");
            rowLimitDemo(businessDb, chat, fewShotExamples, fewShotSize, maxContextRows, maxRows,
                    guard, masker);

            // ---------- ⑤ 查询超时 ----------
            log.info("");
            log.info("================= ⑤ 查询超时：万一有东西漏过护栏，超时还兜得住 =================");
            timeoutDemo(url, username, password, timeoutSeconds);

            // ---------- ⑥ 脱敏 ----------
            log.info("");
            log.info("================= ⑥ 脱敏：别名遮不住它 =================");
            maskingDemo(businessDb, chat, maxContextRows, maxRows, guard, masker);

            printConclusion();
        };
    }

    /**
     * 端到端：用故障注入把模型的 SELECT 换成 {@code DROP TABLE ...; SELECT ...}，看护栏拦不拦。
     *
     * <p><b>关键是最后那一步「表还在吗」。</b>只看工具返回「已阻止」是不够的——
     * 那只是它<b>说</b>自己拦了。真正能证明拦住了的，是<b>独立地去看一眼表还在不在</b>。
     */
    private void endToEndDrop(BusinessDb businessDb, ChatClient chat, FewShotExamples fewShotExamples,
                              int fewShotSize, int maxContextRows, int maxRows,
                              SqlGuard guard, ColumnMasker masker) {
        int before = countRows(businessDb, "orders_archive");
        log.info("注入前：orders_archive 有 {} 行", before);

        String question = "2024年华东区的销售额是多少？";
        log.info("问题：{}", question);
        log.info("注入：把 SQL 的第一个 SELECT 换成「DROP TABLE orders_archive; SELECT」");
        log.info("（于是模型「写」出来的其实是一条 DROP —— 这就是真实威胁的形状）");

        Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(
                businessDb.jdbc(), chat, new SchemaRenderer(businessDb.jdbc()),
                new Text2SqlDatabaseTool.Options(maxContextRows, maxRows, null, 0,
                        fewShotExamples.render(fewShotSize), 0,
                        new Text2SqlDatabaseTool.SqlFault("SELECT", "DROP TABLE orders_archive; SELECT"),
                        guard, masker));

        QueryResult result = tool.query(question);
        String sql = result.sql() == null ? "" : result.sql();
        log.info("工具返回：{}", sql.contains("[已阻止]") ? "【已拦截】" : "【没拦！】");
        log.info("  拦截说明：{}", lastLine(sql));

        int after = countRows(businessDb, "orders_archive");
        log.info("注入后：orders_archive 有 {} 行", after);
        log.info("  ★ 独立验证（不是听工具自己说）：{}", after == before
                ? "表还在、行数没变 [OK] 真的拦住了"
                : "行数变了 [X] 出事了");
    }

    /**
     * 行数上限：临时把上限调小，让「截断」真的发生，并看工具怎么上报。
     *
     * <p>为什么不改主配置：主配置那个上限（默认 1000 行）是给真实使用配的，
     * 而这个库里最大的表也只有 48 行——<b>不把上限调小，就永远看不到截断长什么样</b>。
     *
     * <p><b>这里还兼一个回归检查</b>：第一版把上限设在了连接层，
     * 结果把<b>元数据查询也一起截断</b>了——上限调成 5 时 SchemaRenderer 只读到 5 列（本该 26 列），
     * 注入给模型的表结构悄悄少了 21 列。现在上限搬到语句层，
     * 所以下面那句「元数据读取完成：5 张表 / N 列」必须还是 26，不能跟着变小。
     */
    private void rowLimitDemo(BusinessDb businessDb, ChatClient chat, FewShotExamples fewShotExamples,
                              int fewShotSize, int maxContextRows, int configuredMaxRows,
                              SqlGuard guard, ColumnMasker masker) {
        int tiny = 5;
        log.info("主配置的上限是 {} 行，而这个库最大的表才 48 行 —— 永远不会撞到。", configuredMaxRows);
        log.info("所以临时把上限设成 {}，问一道会返回很多行的题：", tiny);

        SchemaRenderer renderer = new SchemaRenderer(businessDb.jdbc());
        Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(
                businessDb.jdbc(), chat, renderer,
                new Text2SqlDatabaseTool.Options(maxContextRows, tiny, null, 0,
                        fewShotExamples.render(fewShotSize), 0, null, guard, masker));

        String question = "2026年第二季度所有订单的订单号和完成时间";
        log.info("问题：{}", question);
        QueryResult result = tool.query(question);
        log.info("工具返回的 SQL 尾部 / 行数：");
        for (String line : result.render().split("\n")) {
            if (line.startsWith("【") || line.startsWith("--")) {
                log.info("  {}", line);
            }
        }
        log.info("  rowCount = {}（上限 {}）", result.rowCount(), tiny);
        boolean reported = (result.sql() == null ? "" : result.sql()).contains("[已被截断]");
        log.info("  ★ 判定：{}", reported
                ? "如实上报了「被截断」[OK] —— 没有假装这就是全部数据"
                : "没上报 [X] —— 那模型会把前 5 行当成完整结果，这是最危险的「不响的错」");

        // ---------- 回归检查：守的是【不变式】，不是写死的数字 ----------
        // 这里原来写的是「元数据应当是 26 列」。那是个【写死的期望值】——
        // 扩表之后列数会大增，这条就会报假警；而写死的检查一旦开始误报，人就会把它当噪声忽略，
        // 它也就失效了。**要守的不变式是「行数上限没有设在连接层」**（设了就会波及元数据查询），
        // 所以直接查那个不变式本身。
        int connMaxRows = businessDb.jdbc().getMaxRows();
        log.info("  ★ 回归检查：连接层的 maxRows = {}（<= 0 表示【没有】在连接层设上限）—— {}",
                connMaxRows, connMaxRows <= 0
                        ? "[OK] 上限只在语句层，波及不到元数据查询"
                        : "[X] 连接层被设了上限，元数据查询会被一起截断");
        log.info("     当前元数据：{} 张表 / {} 列（只作观察，不作为断言 —— 库变了它就变）",
                countTables(businessDb), countColumns(businessDb));
        log.info("  ★ 为什么不「查回来再丢掉」：内存已经吃过了，丢掉只是掩饰。");
    }

    /** 数一下库里有几张表。 */
    private static int countTables(BusinessDb db) {
        Integer n = db.jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                Integer.class);
        return n == null ? -1 : n;
    }

    /** 数一下库里一共多少列。 */
    private static int countColumns(BusinessDb db) {
        Integer n = db.jdbc().queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'",
                Integer.class);
        return n == null ? -1 : n;
    }

    /**
     * 超时：故意绕过护栏打一条慢查询。
     *
     * <p><b>为什么绕护栏</b>：{@code pg_sleep} 本来就在黑名单里（①里已经验过拦得住）。
     * 这里绕过去，是为了回答另一个问题：<b>「万一有东西漏过了准入层，还有没有兜底的？」</b>
     * 如果连超时都没有，那准入层就是唯一的一层——而唯一的一层等于没有纵深。
     */
    private void timeoutDemo(String url, String username, String password, int configuredTimeout) {
        int tiny = 1;
        log.info("主配置的超时是 {}s；这里另起一个连接把超时设成 {}s，并故意打一条 `SELECT pg_sleep(3)`。",
                configuredTimeout, tiny);
        log.info("（pg_sleep 在准入层就被拦了，绕过它是为了验证「万一漏过去」还有没有兜底）");
        BusinessDb loose = new BusinessDb(url, username, password, tiny);
        try {
            loose.jdbc().query("SELECT pg_sleep(3)", rs -> {
            });
            log.warn("  居然跑完了 [X] —— 超时没生效？");
        }
        catch (RuntimeException e) {
            log.info("  被中断了 [OK]：{}", ConsoleText.shorten(oneLine(rootMessage(e)), 88));
            log.info("  这就是兜底：准入层放过去的东西，也占不住连接。");
        }
    }

    /**
     * 脱敏：<b>固定 SQL 形状</b>跑两个用例，把「哪里管用、哪里不管用」都摆出来。
     *
     * <p><b>为什么不经过模型</b>：第一版是问模型一句「列出订单号」再看输出打没打码。
     * 结果两次跑给出**不同判定**——一次模型写的是简单 {@code SELECT}（脱敏生效），
     * 另一次写的是 {@code UNION ALL}（结果列拿不到底层表 OID，脱敏失效）。
     * <b>判定随模型的写法翻来覆去，那就不叫测试。</b>所以改用
     * {@link Text2SqlDatabaseTool#runSql} 把 SQL 固定住。
     *
     * <p>两个用例是刻意配对的：<b>同一个列、两种写法，一个打码一个不打</b>——
     * 正好把「按列名脱敏」这个手段的边界画出来。
     */
    private void maskingDemo(BusinessDb businessDb, ChatClient chat,
                             int maxContextRows, int maxRows,
                             SqlGuard guard, ColumnMasker configured) {
        log.info("主配置里要脱敏的列（{} 个）：{}", configured.size(), configured.columns());
        log.info("这一节验的是【机制】而不是某张表，所以临时把 order_id 也加进名单 ——");
        log.info("它一定存在于当前库，不受「扩表有没有做」影响。");
        log.info("而且 SQL 是固定的（用 runSql 跳过模型）—— 因为生成不可控，会让判定翻来覆去。");

        Set<String> names = new LinkedHashSet<>(configured.columns());
        names.add("order_id");
        ColumnMasker masker = ColumnMasker.of(names);

        Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(
                businessDb.jdbc(), chat, new SchemaRenderer(businessDb.jdbc()),
                new Text2SqlDatabaseTool.Options(maxContextRows, maxRows, null, 0, "", 0, null,
                        guard, masker));

        runOneMaskCase(tool, "① 简单列引用（带别名）",
                "SELECT order_id AS 订单号, completed_at FROM sales_orders",
                "期望【打码】：底层列名是 order_id，别名「订单号」遮不住", true);

        runOneMaskCase(tool, "② UNION（结果列没有底层表 OID）",
                "SELECT order_id AS 订单号 FROM sales_orders "
                        + "UNION ALL SELECT order_id FROM orders_archive",
                "期望【不打码】：这是「按列名脱敏」的已知缺口，不是 bug",
                false);
    }

    /** 跑一条固定的 SQL，看目标列有没有被打码，并和期望对照。 */
    private void runOneMaskCase(Text2SqlDatabaseTool tool, String label, String sql,
                                String expectation, boolean expectMasked) {
        log.info("");
        log.info("  ---------- {} ----------", label);
        log.info("  SQL：{}", oneLine(sql));

        QueryResult result = tool.runSql(sql);
        boolean masked = result.rows().stream().flatMap(List::stream)
                .anyMatch(v -> v.endsWith("***") || v.equals("*"));

        // 只看前两行，别把整张表打出来
        StringBuilder preview = new StringBuilder("【返回 ").append(result.rowCount()).append(" 行】");
        if (!result.columns().isEmpty()) {
            preview.append("\n    ").append(String.join(" | ", result.columns()));
            for (int i = 0; i < Math.min(2, result.rows().size()); i++) {
                preview.append("\n    ").append(String.join(" | ", result.rows().get(i)));
            }
        }
        log.info("    {}", preview);
        log.info("  {}", expectation);
        log.info("  ★ 判定：{}", masked == expectMasked
                ? (expectMasked ? "打上码了 [OK]" : "确认不打码 [OK] —— 这个缺口已经记进局限里")
                : (expectMasked ? "没打码 [X] —— 该生效的没生效" : "居然打了码 [X] —— 行为和记录的不一致"));
    }

    private void printConclusion() {
        log.info("");
        log.info("================= 本轮得到了什么 =================");
        log.info("1. 四层防御，本轮把第 2 层做完整了：");
        log.info("   ① 提示词      —— 告诉模型「只输出 SQL、只能用给定的表」（迭代 18 起）");
        log.info("   ② 应用层准入  —— SqlGuard：白名单 + 禁 DDL/DML + 拒多语句 + 危险函数（本轮）");
        log.info("   ③ 数据库权限  —— 只读账号 sdaq_readonly，应用已切过去（本轮）");
        log.info("   ④ 实例隔离    —— 业务库和向量库是两个库（迭代 18 起）");
        log.info("");
        log.info("2. 【应用层这一层是可以被绕过的，所以它不该是唯一一层。】");
        log.info("   SqlGuard 已知挡不住的：PG 的 dollar-quoting（$$）和 E'' 转义字符串——");
        log.info("   扫描器只认 '...' 和 \"...\"，遇到 $$ 会把里面的当成真实代码（后果是误报，不是漏放）。");
        log.info("   而第 ③ 层即使应用层被完全绕过，写操作也执行不了。");
        log.info("   **纵深防御的意思是「假设每一层都会被绕过」，不是「我这一层写得够好」。**");
        log.info("");
        log.info("2b. 脱敏这一层同样【不是完备的】：");
        log.info("   · 简单列引用 + 别名 -> 打码（认的是底层列名，别名遮不住）；");
        log.info("   · 但 UNION / 聚合 / 表达式的结果列【没有底层表 OID】，拿不到底层列名 -> 不打码。");
        log.info("   上面 ⑥ 的两个用例就是同一列、两种写法。**按列名脱敏只是第一步，不是终点。**");
        log.info("");
        log.info("3. 三条【被实跑纠正】的设计 —— 注意它们都不是「写错了」，是「以为对了」：");
        log.info("   · 行数上限必须配在【语句】层：配在连接层会把元数据查询一起截断，");
        log.info("     于是注入给模型的表结构悄悄缺列（实测：上限调 5 时只剩 5 列，本该 26 列）；");
        log.info("   · 脱敏必须用 getBaseColumnName：`getColumnName` 在 PG 上返回的是【别名】，");
        log.info("     一个 `AS` 就能绕过（第一版实测跑出「没打码 [X]」）；");
        log.info("   · 故障注入必须挪到护栏【之前】：否则注入的 DROP 绕过护栏，上面 ③ 的验收是假的。");
        log.info("   ★ 共同点：「我配了」和「它生效了」之间，隔着一句可观测的输出。");
        log.info("");
        log.info("4. 自修复那条路也必须过同一道门：");
        log.info("   「为了让报错消失而去写库」是一种真实的翻车方式，");
        log.info("   所以 executeWithRepair 对修正后的 SQL 也调了一次 guard.check。");
        log.info("");
        log.info("5. 面试里这条为什么加分：能主动想到「让模型生成 SQL 必须有安全边界」，");
        log.info("   体现的是生产意识，而不是「我照着教程跑通了」。");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static int countRows(BusinessDb db, String table) {
        Integer n = db.jdbc().queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? -1 : n;
    }

    private static String lastLine(String s) {
        String[] lines = s.split("\n");
        return lines.length == 0 ? s : lines[lines.length - 1];
    }

    private static String oneLine(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) {
            flat = flat.replace("  ", " ");
        }
        return flat;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}
