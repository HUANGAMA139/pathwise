package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.FewShotExamples;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import com.example.smartdataqa.tools.text2sql.Text2SqlDatabaseTool;
import com.example.smartdataqa.tools.text2sql.Text2SqlDiagnostic;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 20 · <b>few-shot 消融 + 错误自修复验收</b>。
 *
 * <h3>为什么这两件事放一个 demo 里</h3>
 * 因为它们解决的是<b>同一类问题的两个不同症状</b>：
 * <pre>
 *   few-shot    治「SQL 跑通了但答案是错的」——值域、归期列这些 迭代 18 实测出来的坑
 *   错误自修复  治「SQL 压根没跑成」——字段名写错、语法错
 * </pre>
 * 迭代 18 那 9 道题里，**这两种症状同时存在**，所以要一起量、分开看。
 *
 * <h3>第一部分：few-shot 消融（0 / 1 / 3 / 7 条）</h3>
 * <b>跑的是 迭代 18 的配置</b>——不筛表、不自修复，唯一的变量是 few-shot 条数。
 * 所以 n=0 那一行就是 迭代 18 的基线，可以和它逐位对比。
 *
 * <p><b>[已验证]</b> 实跑：n=0 得到 0 通过 / 7 失败 / 2 人工，**与 迭代 18 记下的数字逐位一致**
 * —— 这是「旧基线可复现」这条纪律第一次被真正验证，而不只是声称。
 *
 * <h3>[注意] 这条纪律有个前提：库不能变</h3>
 * 代码侧一直有「回到旧基线」的开关（{@code retrieval.mode} / {@code rerank.mode} /
 * {@code table-select.mode} / {@code database.mode}），<b>但数据侧没有开关</b>。
 * {@code docker/init-business-db-extra.sql}（扩表到 20 张）已经躺在仓库里却还没执行；
 * 一旦执行，迭代 18 的基线就<b>再也复现不出来</b>，而且这件事<b>不会报错</b>——
 * 你只会看到「加了 few-shot 之后数字变好了」，其中混着扩表的功劳。
 *
 * <p><b>所以要跑 迭代 19 之前，先把要做的前后对比做完，或者留一份 5 张表的老库。</b>
 *
 * <h3>第二部分：错误自修复验收（同一个问题、同一条被改坏的 SQL，修 vs 不修）</h3>
 * 做法是<b>控制变量</b>：
 * <pre>
 *   1) 先正常跑一次 D1，拿到模型写出来的那条 SQL（这一步不该报错）
 *   2) 从这条 SQL 里挑一个真实的列名，改坏它 —— 这就是「故意注入一个错误」
 *   3) 同一条坏 SQL：repair=0 跑一次（应当失败）、repair=2 跑一次（应当自己改回来）
 * </pre>
 * 为什么不靠「等模型偶然写错」来验证：那是碰运气，而且没法重复。
 * <b>能稳定地制造一个错，才叫验收。</b>
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp20.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp20.enabled", havingValue = "true")
public class SqlRepairDemo {

    private static final Logger log = LoggerFactory.getLogger(SqlRepairDemo.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 消融的 few-shot 条数。
     *
     * <p>0 是 迭代 18 的基线（必须留着）；1 看「只给最有价值的那一条够不够」；
     * 3 覆盖「值域 / JOIN / 归档表」三个核心坑；7 是全部。
     */
    private static final int[] FEW_SHOT_SIZES = {0, 1, 3, 7};

    /**
     * 挑一个「真实但会被改坏」的目标：优先改<b>列名</b>（这是最常见的现实错误），
     * 改完的值保证不存在，于是数据库一定报 {@code column ... does not exist}。
     *
     * <p>因为它是在<b>模型真的写出来的那条 SQL</b> 上挑的，所以不会出现「注入没生效」。
     */
    private static final List<String> FAULT_CANDIDATES = List.of(
            "order_amount", "sales_orders", "refund_amount", "completed_at", "region", "status");

    @Bean
    ApplicationRunner exp20FewShotAndRepair(BusinessDb businessDb,
                                            ChatClient.Builder chatClientBuilder,
                                            @Value("${sdaq.text2sql.max-context-rows:50}") int maxContextRows,
                                            @Value("${sdaq.text2sql.repair-attempts:2}") int repairAttempts,
                                            @Value("${sdaq.eval.fewshot-metrics}") String metricsPath) {

        return args -> {
            JdbcTemplate jdbc = businessDb.jdbc();
            SchemaRenderer renderer = new SchemaRenderer(jdbc);
            ChatClient chat = chatClientBuilder.build();

            FewShotExamples examples;
            try {
                examples = FewShotExamples.load();
            }
            catch (RuntimeException e) {
                log.error("few-shot 样例加载失败，先看 {} 的格式：{}", FewShotExamples.RESOURCE, e.getMessage());
                return;
            }

            log.info("========== 迭代 20 · few-shot + 错误自修复 ==========");
            log.info("few-shot 样例：{} 条（{}）", examples.size(), FewShotExamples.RESOURCE);
            log.info("诊断题：{} 道（迭代 18 那 9 道，题目与判据一字未动）", Text2SqlDiagnostic.CASES.size());
            log.info("消融的条数：{}", java.util.Arrays.toString(FEW_SHOT_SIZES));
            log.info("自修复次数上限：{}", repairAttempts);
            log.info("");
            log.info("【n=0 就是 迭代 18 的配置】不筛表、不自修复、无 few-shot。");
            log.info("[已验证] 实跑：n=0 得到 0 通过 / 7 失败 / 2 人工，");
            log.info("         与 迭代 18 记下的数字【逐位一致】—— 旧基线确实可复现。");
            log.info("[但有个前提] 库不能变。docker/init-business-db-extra.sql（扩表到 20 张）已经躺在仓库里");
            log.info("         却还没执行；一旦执行，迭代 18 的基线就再也复现不出来（数据侧没有 mode 可以切回去）。");
            log.info("         ★ 要跑 迭代 19 之前，先把要做的前后对比做完，或者留一份 5 张表的老库。");

            // ---------- 样例长什么样 ----------
            log.info("");
            log.info("================= 这 {} 条样例在教什么 =================", examples.size());
            int i = 1;
            for (FewShotExamples.Example e : examples.examples()) {
                log.info("  {}. {}", i++, e.question());
                log.info("     {}", e.note() == null ? "（无要点）" : e.note());
            }

            // ---------- 第一部分：few-shot 消融 ----------
            String runStamp = LocalDateTime.now().format(STAMP);
            List<Row> rows = new ArrayList<>();
            Map<Integer, Map<String, String>> verdictsBySize = new LinkedHashMap<>();

            for (int size : FEW_SHOT_SIZES) {
                Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(jdbc, chat, renderer,
                        options(maxContextRows, examples.render(size), 0, null));
                Map<String, String> verdicts = new LinkedHashMap<>();
                log.info("");
                log.info("################ few-shot = {} 条 ################", size);
                for (Text2SqlDiagnostic.Case c : Text2SqlDiagnostic.CASES) {
                    String verdict;
                    QueryResult result = null;
                    try {
                        result = tool.query(c.question());
                        verdict = Text2SqlDiagnostic.judge(c, result);
                    }
                    catch (RuntimeException e) {
                        verdict = "[X] 工具异常：" + e.getClass().getSimpleName();
                    }
                    verdicts.put(c.id(), verdict);
                    rows.add(new Row(runStamp, size, c.id(), verdict, result == null ? -1 : result.rowCount()));
                    log.info("  {} {} {}",
                            ConsoleText.padRight(c.id(), 5),
                            ConsoleText.padRight(shorten(verdict, 46), 48),
                            result == null ? "" : ("(" + result.rowCount() + " 行)"));
                }
                verdictsBySize.put(size, verdicts);
            }

            printAblation(verdictsBySize);

            // ---------- 第二部分：错误自修复验收 ----------
            log.info("");
            log.info("================= 错误自修复：控制变量的验收 =================");
            Text2SqlDatabaseTool plain = new Text2SqlDatabaseTool(jdbc, chat, renderer,
                    options(maxContextRows, examples.render(examples.size()), 0, null));

            Text2SqlDiagnostic.Case c1 = Text2SqlDiagnostic.CASES.get(0);   // D1
            log.info("用「{}」这道题来验（{}）", c1.id(), c1.question());
            QueryResult baseline = plain.query(c1.question());
            String goodSql = baseline.sql() == null ? "" : baseline.sql();
            log.info("第 1 步 · 先正常跑一次，拿到模型写的 SQL：");
            log.info("  {}", oneLine(goodSql));

            Text2SqlDatabaseTool.SqlFault fault = pickFault(goodSql);
            if (fault == null) {
                log.warn("  [!] 这条 SQL 里没找到可改坏的候选标识（{}），本次验收跳过", FAULT_CANDIDATES);
            }
            else {
                log.info("第 2 步 · 故意改坏一个标识：{}", fault.describe());
                runAcceptance(jdbc, chat, renderer, maxContextRows, examples, c1, fault, 0);
                runAcceptance(jdbc, chat, renderer, maxContextRows, examples, c1, fault, repairAttempts);
            }

            writeCsv(Path.of(metricsPath), rows);
            printConclusion(examples.size(), repairAttempts);
        };
    }

    /**
     * 验收的单次运行：同一条被改坏的 SQL，{@code repairAttempts=0} 时应当失败、大于 0 时应当自愈。
     */
    private void runAcceptance(JdbcTemplate jdbc, ChatClient chat, SchemaRenderer renderer,
                               int maxContextRows, FewShotExamples examples,
                               Text2SqlDiagnostic.Case c, Text2SqlDatabaseTool.SqlFault fault,
                               int repairAttempts) {
        Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(jdbc, chat, renderer,
                options(maxContextRows, examples.render(examples.size()), repairAttempts, fault));
        String label = repairAttempts == 0 ? "关闭自修复" : "开启自修复（最多 " + repairAttempts + " 次）";
        log.info("");
        log.info("  ---------- {} ----------", label);
        try {
            QueryResult result = tool.query(c.question());
            String sql = result.sql() == null ? "" : result.sql();
            boolean repaired = sql.contains("[自修复");
            boolean stillFailed = sql.contains("[执行失败]");
            log.info("  {}", stillFailed ? "结果：仍然失败" : "结果：成功了");
            for (String line : result.render().split("\n")) {
                log.info("    {}", line);
            }
            if (repairAttempts == 0) {
                log.info("  [期望] 应当失败 —— {}", stillFailed ? "符合" : "不符合（说明注入没生效？）");
            }
            else {
                log.info("  [期望] 应当自愈 —— {}", repaired && !stillFailed ? "符合（痕迹：" + firstLine(sql) + "）" : "不符合");
            }
        }
        catch (RuntimeException e) {
            log.error("  [!] 工具抛了异常，不属于设计内的路径：{}", e.toString());
        }
    }

    /**
     * 这个 demo 只关心 few-shot 和自修复两件事，表筛选和安全项都用默认
     * （不筛表、护栏照开、不脱敏）。
     *
     * <p><b>为什么抽成一个方法，而不是三处直接写记录构造器</b>：
     * 迭代 21 把 {@code Options} 从 6 个字段加到了 8 个，这三处当时就全挂了——
     * 而**这种错沙箱里的静态自检抓不到**（它查括号、占位符、未定义方法，但不查构造函数参数个数）。
     * 让调用点只跟一个「具名形状」走，下次再加字段只用改这一处。
     */
    private static Text2SqlDatabaseTool.Options options(int maxContextRows,
                                                        String fewShotBlock,
                                                        int repairAttempts,
                                                        Text2SqlDatabaseTool.SqlFault fault) {
        return new Text2SqlDatabaseTool.Options(
                maxContextRows, 0, null, 0, fewShotBlock, repairAttempts, fault, null, null);
    }

    /** 从模型实际写出的 SQL 里挑一个改坏目标。挑不到返回 null（如实报告，不硬编一个）。 */
    private static Text2SqlDatabaseTool.SqlFault pickFault(String sql) {
        for (String candidate : FAULT_CANDIDATES) {
            if (sql.contains(candidate)) {
                return new Text2SqlDatabaseTool.SqlFault(candidate, candidate + "_typo");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 打印
    // ------------------------------------------------------------------

    private static final List<String> ABLATION_HEADER = List.of(
            "run", "few_shot_size", "case_id", "verdict", "row_count");

    private record Row(String run, int fewShotSize, String caseId, String verdict, int rowCount) {
    }

    private void printAblation(Map<Integer, Map<String, String>> verdictsBySize) {
        log.info("");
        log.info("================= few-shot 消融：逐题判定 =================");
        StringBuilder header = new StringBuilder(ConsoleText.padRight("题", 5));
        for (Integer size : verdictsBySize.keySet()) {
            header.append(ConsoleText.padRight("n=" + size, 9));
        }
        log.info(header.toString());

        for (Text2SqlDiagnostic.Case c : Text2SqlDiagnostic.CASES) {
            StringBuilder line = new StringBuilder(ConsoleText.padRight(c.id(), 5));
            for (Map<String, String> verdicts : verdictsBySize.values()) {
                String v = verdicts.get(c.id());
                line.append(ConsoleText.padRight(mark(v), 9));
            }
            log.info(line.toString());
        }

        log.info("");
        log.info("================= few-shot 消融：汇总 =================");
        log.info("{} {} {} {}",
                ConsoleText.padRight("few-shot", 10),
                ConsoleText.padRight("通过", 8),
                ConsoleText.padRight("失败", 8),
                "需人工判断");
        for (Map.Entry<Integer, Map<String, String>> e : verdictsBySize.entrySet()) {
            long ok = e.getValue().values().stream().filter(Text2SqlDiagnostic::passed).count();
            long bad = e.getValue().values().stream().filter(Text2SqlDiagnostic::failed).count();
            long manual = e.getValue().size() - ok - bad;
            log.info("{} {} {} {}",
                    ConsoleText.padRight(e.getKey() + " 条", 10),
                    ConsoleText.padRight(ok + " / " + e.getValue().size(), 8),
                    ConsoleText.padRight(String.valueOf(bad), 8),
                    manual);
        }
        log.info("");
        log.info("（判据同 迭代 18：诊断可以有人工档，指标不能 —— 执行准确率是 迭代 22 的事）");
    }

    /** 把一个 verdict 压成两个字的标记，好在矩阵里对齐。 */
    private static String mark(String verdict) {
        if (verdict == null) {
            return "-";
        }
        if (Text2SqlDiagnostic.passed(verdict)) {
            return "[OK]";
        }
        if (Text2SqlDiagnostic.failed(verdict)) {
            return "[X]";
        }
        return "[人工]";
    }

    private void writeCsv(Path path, List<Row> rows) {
        try {
            for (Row r : rows) {
                Csv.appendRow(path, ABLATION_HEADER, List.of(
                        r.run(),
                        String.valueOf(r.fewShotSize()),
                        r.caseId(),
                        r.verdict(),
                        r.rowCount() < 0 ? "" : String.valueOf(r.rowCount())));
            }
            log.info("");
            log.info("结果已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    /**
     * 结语。
     *
     * <p><b>依旧不写预测</b>：迭代 18 的结语里我事先断言了失败模式，被实跑当场推翻。
     * 所以这里只说方法与判据，数字留给上面的表和 docs。
     */
    private void printConclusion(int exampleCount, int repairAttempts) {
        log.info("");
        log.info("================= 本轮得到了什么 =================");
        log.info("1. few-shot：{} 条「问题 → SQL」样例，放在 {}，条数由 sdaq.text2sql.few-shot-size 控制。",
                exampleCount, FewShotExamples.RESOURCE);
        log.info("   它要补的是 迭代 18 实测出来的最大失分点：【值域】——");
        log.info("   库里存的是 '华东' 而模型写成 '华东区'，查询合法、返回 0 行、数据库一声不响。");
        log.info("");
        log.info("2. 错误自修复：执行报错时把「失败的 SQL + 数据库报错」回灌给模型让它改，最多 {} 次。", repairAttempts);
        log.info("   这是 12-Factor Agents 里「把错误压缩进上下文」那一条：");
        log.info("   **别把错误当异常抛给上层，把它当成一次新的输入。**");
        log.info("   也是「Agent 比固定工作流强」最直观的例子——固定流水线遇到 SQL 报错就整条挂掉，");
        log.info("   而模型本来就看懂「column xxx does not exist」并改对，缺的只是有人把这句话递给它。");
        log.info("");
        log.info("3. 四条纪律（每条都对应一种「修歪了」的方式），见 Text2SqlDatabaseTool.executeWithRepair：");
        log.info("   · 只有执行报错才修，**0 行不修** —— 0 行不是错误，修它就是瞎猜；");
        log.info("   · 修好的语句仍要过只读护栏；");
        log.info("   · 修不出可用 SQL 就收手，不硬撑；");
        log.info("   · 修复痕迹留在返回的 SQL 里（[自修复 N 次后成功]）—— 不然看不出这个答案是重试来的。");
        log.info("");
        log.info("4. 一个必须说清楚的边界：few-shot 把业务口径编进了 prompt，");
        log.info("   这和「口径该在文档里」有点冲突。区别在【时机】：");
        log.info("   口径作为【回答时的依据】在文档里；而写 SQL 时必须先知道口径才能写出对的 WHERE。");
        log.info("   所以 few-shot 不是替代文档，它是把少数「生成时就必须遵守」的规则做成了样式。");
        log.info("   —— 但样例一多，prompt 就会变成一份会漂移的第二真相。这个代价要记着。");
        log.info("");
        log.info("5. 结果写进了 {}。", "eval/fewshot-compare.csv");
        log.info("   逐题明细与结论写在 docs/20-few-shot与错误自修复.md。");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String shorten(String s, int max) {
        return ConsoleText.shorten(s, max);
    }

    private static String oneLine(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) {
            flat = flat.replace("  ", " ");
        }
        return flat.length() <= 220 ? flat : flat.substring(0, 220) + "…";
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }
}
