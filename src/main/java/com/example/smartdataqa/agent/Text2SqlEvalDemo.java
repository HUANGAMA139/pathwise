package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.eval.EvalCase;
import com.example.smartdataqa.eval.LlmJudge;
import com.example.smartdataqa.router.RouteDecision;
import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.FewShotExamples;
import com.example.smartdataqa.tools.text2sql.HybridTableSelector;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import com.example.smartdataqa.tools.text2sql.SqlText;
import com.example.smartdataqa.tools.text2sql.TableSelector;
import com.example.smartdataqa.tools.text2sql.Text2SqlDatabaseTool;
import com.example.smartdataqa.tools.text2sql.Text2SqlDiagnostic;
import com.example.smartdataqa.util.Amounts;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代 22 · <b>Text2SQL 支路的评测（第二个基线数字）</b>。
 *
 * <h3>为什么是「第二个」</h3>
 * 第一个基线数字是 迭代 15 给 <b>RAG 支路</b>的（28 条用例、路由 0.89、答案 0.89 …）。
 * 本轮给<b>数据库支路</b>一个同样性质的数字——<b>一条命令跑完、结果是可复现的比率，不是诊断</b>。
 * 这是阶段四的收口：双支路都有真实能力，且各自有一组能拿出去说的数字。
 *
 * <h3>诊断（迭代 18/20）和评测（本轮）的区别就一条</h3>
 * <pre>
 *   迭代 18/20  跑 9 道题 → 打印 SQL / 结果 / 判定   ← 允许「需人工判断」这一档
 *   迭代 22     跑一批题 → 给出比率                  ← 【不许】有人工档
 * </pre>
 * 「人工档」在诊断里是诚实的（结论要靠人补）；在指标里是致命的——分母里挂着
 * 一堆「没结论」的题，算出来的比率不能和别的比率比。<b>所以本轮必须把每一档都判到底。</b>
 *
 * <h3>两个指标，必须分开报（计划里点名，也是面试会问的）</h3>
 * <table border="1">
 *   <tr><th>指标</th><th>问的是</th><th>怎么判</th></tr>
 *   <tr><td><b>可执行率</b></td><td>这条 SQL 跑起来了吗（有没有报错 / 被护栏拦）</td>
 *       <td>确定性：看 {@code QueryResult.sql()} 里有没有失败标记</td></tr>
 *   <tr><td><b>SQL 正确率</b></td><td>这条 SQL 的<b>形状</b>对不对（表、列、聚合、筛选条件）</td>
 *       <td>LLM 判意图（{@link LlmJudge#gradeSqlIntent}）</td></tr>
 *   <tr><td><b>执行准确率</b></td><td>查出来的<b>数据</b>对不对 —— <b>这个才是用户关心的</b></td>
 *       <td>有确切期望值的自动比；口径/文本题用 LLM 补判</td></tr>
 * </table>
 * 三档不是包含关系，最典型的分叉是<b>「SQL 形状对、但取值域写错」</b>
 * （迭代 18 实测：把 {@code '华东'} 写成 {@code '华东区'}，SQL 合法、执行成功、返回 0 行）——
 * 它可执行（跑通了）、SQL 意图判 A（形状没毛病）、执行准确率判 0（数据错）。
 * 只报一个数就会漏掉这一档。
 *
 * <h3>顺带量两件一直没量的事</h3>
 * <ol>
 *   <li><b>筛表 vs 不筛表，对最终答案的影响。</b>迭代 19 量的是「选表准不准」，
 *       本轮量的是「选表这件事到底改没改答案」。<b>这个对比只有在库扩到 20 张表之后才有意义</b>——
 *       5 张表 + top-K 8 时，筛选等于没筛（全选中）。</li>
 *   <li><b>自修复的延迟成本。</b>它只在执行报错时才动，所以平时量不到——
 *       本轮用故障注入（控制变量）稳定地制造一个错，量「修 vs 不修」的耗时差。</li>
 * </ol>
 *
 * <h3>两套题，别混</h3>
 * <pre>
 *   支路集（set=branch）  诊断集 9 题（Text2SqlDiagnostic.CASES，D1—D9）
 *                        直接喂 Text2SqlDatabaseTool，【绕过路由】，量支路本身
 *   端到端（set=e2e）     testset 里 expected_route ∈ {database, both} 的 8 题（B01—B04 / X01—X04）
 *                        走完整 SmartQaAgent（含路由 + 文档），量用户视角看到的数字
 * </pre>
 * <b>期望值不能手写。</b>支路集用的是 迭代 18 那份脚本算出来的期望值；端到端集用的是
 * 迭代 18 重标注过的 testset 期望答案——两处都是「数字从种子数据来」，不是人填的。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp22.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp22.enabled", havingValue = "true")
public class Text2SqlEvalDemo {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlEvalDemo.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 支路集的四个配置。设计成<b>受控对照</b>，两两成对：
     * <pre>
     *   筛表的效果 = filtered  vs no-filter   （其余三项相同）
     *   自修复的效果 = full     vs filtered    （其余三项相同）
     *   exp18-form 是「什么都没有」的参照点
     * </pre>
     * <b>★ exp18-form 不等于 迭代 18 的那组数字。</b>迭代 18 跑在 <b>5 张表</b>的库上，
     * 而现在库是 20 张——「不筛表」意味着往 prompt 里塞 20 张表的表结构。
     * 它记下来只能叫「20 张表下的 v1 形态」，<b>不能和 迭代 18 的 0/7/2 直接比</b>。
     */
    private record Arm(String name, String desc, boolean filter, int fewShot, int repair) {
    }

    private static final List<Arm> ARMS = List.of(
            new Arm("exp18-form", "不筛表、无 few-shot、不自修复（20 张表的 v1 形态）", false, 0, 0),
            new Arm("no-filter", "不筛表 + few-shot 7", false, 7, 0),
            new Arm("filtered", "筛表(hybrid,top-8) + few-shot 7", true, 7, 0),
            new Arm("full", "筛表 + few-shot 7 + 自修复 2", true, 7, 2));

    /** 挑「真实但会被改坏」的目标，用于自修复的延迟微基准（同 迭代 20）。 */
    private static final List<String> FAULT_CANDIDATES = List.of(
            "order_amount", "sales_orders", "refund_amount", "completed_at", "region", "status");

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)   // 端到端那一半要等语料入库跑完（同 迭代 15 的 EvalRunner）
    ApplicationRunner exp22Text2SqlEval(
            BusinessDb businessDb,
            ChatClient.Builder chatClientBuilder,
            SmartQaAgent agent,
            EmbeddingModel embeddingModel,
            FewShotExamples fewShotExamples,
            @Value("${sdaq.text2sql.max-context-rows:50}") int maxContextRows,
            @Value("${sdaq.table-select.top-k:8}") int tableTopK,
            @Value("${sdaq.table-select.candidates:10}") int candidates,
            @Value("${sdaq.table-select.rrf-k:30}") int rrfK,
            @Value("${sdaq.table-select.mode:hybrid}") String tableSelectMode,
            @Value("${sdaq.text2sql.few-shot-size:0}") int wiredFewShot,
            @Value("${sdaq.text2sql.repair-attempts:2}") int repairAttempts,
            @Value("${sdaq.database.mode:text2sql}") String databaseMode,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.text2sql-metrics:eval/text2sql-eval.csv}") String metricsPath,
            @Value("${sdaq.eval.text2sql-summary:eval/text2sql-eval-summary.csv}") String summaryPath) {

        return args -> {
            JdbcTemplate jdbc = businessDb.jdbc();
            SchemaRenderer renderer = new SchemaRenderer(jdbc);
            ChatClient chat = chatClientBuilder.build();
            LlmJudge judge = new LlmJudge(chat);
            HybridTableSelector selector = new HybridTableSelector(
                    renderer::catalog, embeddingModel, candidates, rrfK);

            log.info("========== 迭代 22 · Text2SQL 支路评测（第二个基线数字） ==========");
            log.info("判据分三档：可执行率（确定性）· SQL 正确率（LLM 判意图）· 执行准确率（用户关心的那个）");
            log.info("支路集：诊断 9 题（D1—D9），绕过路由；端到端：testset 里 database/both 的题，走完整 Agent");
            log.info("few-shot 样例：{} 条；库表数：{}", fewShotExamples.size(), tableCount(renderer));
            log.info("主链路当前形态：database.mode={}, table-select.mode={}, few-shot-size={}, repair-attempts={}",
                    databaseMode, tableSelectMode, wiredFewShot, repairAttempts);
            if (!"hybrid".equals(tableSelectMode) && !"none".equals(tableSelectMode)) {
                log.warn("  注意：主链路 table-select.mode={}，但端到端那一半量的是【接线后的真实行为】，"
                        + "下面的支路矩阵则固定用 hybrid", tableSelectMode);
            }
            warnIfNotExpanded(renderer);

            String runStamp = LocalDateTime.now().format(STAMP);
            List<Score> scores = new ArrayList<>();

            // ---------- 第一部分：支路集（9 题 × 4 配置） ----------
            runBranchSet(jdbc, chat, renderer, selector, fewShotExamples, judge, maxContextRows,
                    tableTopK, runStamp, scores);

            // ---------- 第二部分：端到端（8 题，走完整 Agent） ----------
            runEndToEndSet(agent, judge, testsetPath, runStamp, scores);

            // ---------- 汇总 + 落盘 ----------
            printSummary(scores);
            writeCsv(Path.of(metricsPath), scores);
            writeSummaryCsv(Path.of(summaryPath), scores, runStamp);

            // ---------- 第三部分：自修复的延迟成本（受控微基准） ----------
            repairLatency(jdbc, chat, renderer, selector, tableTopK, maxContextRows,
                    fewShotExamples, repairAttempts);

            printConclusion(repairAttempts);
        };
    }

    // ==================================================================
    // 第一部分：支路集（绕过路由，直接喂工具）
    // ==================================================================

    private void runBranchSet(JdbcTemplate jdbc, ChatClient chat, SchemaRenderer renderer,
                              TableSelector selector, FewShotExamples examples, LlmJudge judge,
                              int maxContextRows, int tableTopK, String runStamp, List<Score> scores) {

        List<Text2SqlDiagnostic.Case> cases = Text2SqlDiagnostic.CASES;

        for (Arm arm : ARMS) {
            log.info("");
            log.info("################ 配置 {} —— {} ################", arm.name(), arm.desc());
            Text2SqlDatabaseTool tool = tool(jdbc, chat, renderer,
                    arm.filter() ? selector : null, tableTopK, maxContextRows,
                    examples.render(arm.fewShot()), arm.repair(), null);

            for (Text2SqlDiagnostic.Case c : cases) {
                long t0 = System.currentTimeMillis();
                QueryResult result = null;
                String note = "";
                try {
                    result = tool.query(c.question());
                }
                catch (RuntimeException e) {
                    note = "工具抛了异常（不属于设计内路径）：" + e.getClass().getSimpleName();
                    log.error("  {} [X] {}", c.id(), note);
                }
                long ms = System.currentTimeMillis() - t0;
                if (result == null) {
                    scores.add(new Score("branch", arm.name(), c.id(), c.question(),
                            "", "", false, null, 0.0, -1, ms, "", note, 0));
                    continue;
                }

                boolean runnable = executable(result);
                Double intent = safeIntent(judge, c.question(), result.sql());
                Double exec = execAccuracy(c, result, judge);

                int schemaChars = arm.filter()
                        ? injectedChars(renderer, selector, c.question(), tableTopK)
                        : renderer.render().length();

                scores.add(new Score("branch", arm.name(), c.id(), c.question(),
                        "", "", runnable, intent, exec, result.rowCount(), ms, result.sql(), "", schemaChars));

                log.info("  {} 可执行={} SQL意图={} 执行准确={} ({} 行, {} ms)  {}",
                        ConsoleText.padRight(c.id(), 5),
                        ConsoleText.padRight(runnable ? "[是]" : "[否]", 7),
                        ConsoleText.padRight(fmt(intent), 6),
                        ConsoleText.padRight(fmt(exec), 7),
                        result.rowCount(), ms,
                        runnable ? "" : firstLine(result.sql()));
            }
        }
    }

    /**
     * 执行准确率。
     *
     * <p><b>人工档必须在这里被判掉。</b>诊断的判据（{@link Text2SqlDiagnostic#judge}）
     * 对口径/文本题返回「需人工判断」——诊断里那是对的，指标里不行。
     * 这里把那一档交给 LLM 补判（拿渲染后的结果和期望答案比），于是 9 道题全有结论。
     */
    private double execAccuracy(Text2SqlDiagnostic.Case c, QueryResult result, LlmJudge judge) {
        String verdict = Text2SqlDiagnostic.judge(c, result);
        if (Text2SqlDiagnostic.passed(verdict)) {
            return 1.0;
        }
        if (Text2SqlDiagnostic.failed(verdict)) {
            return 0.0;
        }
        // 走到这里就是「需人工判断」那一档 —— 用 LLM 判分把它变成指标
        return judge.gradeAnswer(c.question(), c.expectedText(), result.render());
    }

    /** SQL 意图判分；SQL 为空（例如模型直接拒答）时给 {@link LlmJudge#UNPARSED}。 */
    private static Double safeIntent(LlmJudge judge, String question, String sql) {
        if (sql == null || sql.isBlank() || SqlText.isOnlyComments(sql)) {
            return LlmJudge.UNPARSED;
        }
        try {
            return judge.gradeSqlIntent(question, sql);
        }
        catch (RuntimeException e) {
            log.warn("      [判分] SQL 意图判分异常：{}", e.toString());
            return LlmJudge.UNPARSED;
        }
    }

    // ==================================================================
    // 第二部分：端到端（走完整 Agent）
    // ==================================================================

    private void runEndToEndSet(SmartQaAgent agent, LlmJudge judge, String testsetPath,
                                String runStamp, List<Score> scores) throws Exception {
        List<EvalCase> cases = loadE2eCases(testsetPath);
        log.info("");
        log.info("################ 端到端：{} 道 database/both 用例（走完整 Agent） ################", cases.size());
        log.info("（执行准确率 = 期望答案里的每个大额数字，都能在【真实查询结果】里找到；±1% 容差）");

        for (EvalCase c : cases) {
            long t0 = System.currentTimeMillis();
            SmartQaAgent.Answer a;
            try {
                a = agent.answer(c.question());
            }
            catch (RuntimeException e) {
                log.error("  {} [X] Agent 抛异常：{}", c.id(), e.toString());
                scores.add(new Score("e2e", "wired", c.id(), c.question(),
                        c.expectedRoute(), "-", false, null, 0.0, -1,
                        System.currentTimeMillis() - t0, "", "Agent 异常", 0));
                continue;
            }
            long ms = System.currentTimeMillis() - t0;

            RouteDecision.Route route = a.decision().route();
            boolean routeOk = route == RouteDecision.Route.DATABASE || route == RouteDecision.Route.BOTH;
            QueryResult db = a.dbResult();
            boolean runnable = executable(db);

            List<Double> want = Amounts.inText(c.expectedAnswer());
            // 只从【数据行】里取数字，不从 render() 整段取——render 里带着那条 SQL，
            // 万一句子里出现数字常量就会被误当成结果（迭代 21「量具要和真实行为一致」的同一个道理）。
            List<Double> got = Amounts.inResult(db);
            boolean numbersOk = !want.isEmpty() && Amounts.containsAll(want, got, 0.01);
            double exec = (runnable && numbersOk) ? 1.0 : 0.0;
            Double intent = (db == null) ? LlmJudge.UNPARSED
                    : safeIntent(judge, c.question(), db.sql());

            String note = routeOk ? "" : ("路由到了 " + route.name());
            if (runnable && !numbersOk) {
                note = (note.isBlank() ? "" : note + "；") + "结果里缺期望数字 " + Amounts.format(want);
            }
            scores.add(new Score("e2e", "wired", c.id(), c.question(),
                    c.expectedRoute(), route.name(), runnable, intent, exec,
                    db == null ? -1 : db.rowCount(), ms, db == null ? "" : db.sql(), note, 0));

            log.info("  {} 路由 期望={} 实际={} {}｜可执行={}｜执行准确={} ({} ms) {}",
                    ConsoleText.padRight(c.id(), 5),
                    ConsoleText.padRight(c.expectedRoute(), 9),
                    ConsoleText.padRight(route.name(), 9),
                    routeOk ? "[OK]" : "[X]",
                    runnable ? "[是]" : "[否]",
                    exec > 0 ? "[是]" : "[否]",
                    ms, note.isBlank() ? "" : ("← " + note));
            // 把期望数字的抽取结果打出来 —— 抽取规则本身也可能错，要能被人一眼核对
            log.info("      期望数字 {}；结果数字 {}", Amounts.format(want), Amounts.format(got));
        }
    }

    private List<EvalCase> loadE2eCases(String path) throws Exception {
        List<List<String>> rows = Csv.read(Path.of(path));
        if (rows.isEmpty()) {
            throw new IllegalStateException("测试集是空的：" + path);
        }
        List<String> header = rows.get(0);
        if (!header.equals(EvalCase.HEADER)) {
            throw new IllegalStateException("测试集表头不对。期望 " + EvalCase.HEADER + "，实际 " + header);
        }
        List<EvalCase> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() != EvalCase.HEADER.size()) {
                throw new IllegalStateException("第 " + (i + 1) + " 行字段数是 " + r.size()
                        + "，应为 " + EvalCase.HEADER.size() + "：" + r);
            }
            EvalCase c = EvalCase.fromRow(r);
            if ("database".equals(c.expectedRoute()) || "both".equals(c.expectedRoute())) {
                out.add(c);
            }
        }
        return out;
    }

    // ==================================================================
    // 第三部分：自修复的延迟成本（受控微基准）
    // ==================================================================

    /**
     * <b>自修复花多少钱、买到什么。</b>
     *
     * <p>为什么要用故障注入而不是「看评测里有多少条触发了自修复」：
     * few-shot 开到 7 之后，那 9 道题基本不报错了 —— <b>自修复平时根本不触发，你量不到它。</b>
     * 所以用 迭代 20 那套控制变量：先正常跑一次拿到模型真写的 SQL，挑一个真标识改坏，
     * 同一条坏 SQL 分别以 repair=0 / repair=N 跑，量耗时差。
     *
     * <p>这是「把成本摆出来」而不是「说它好」。<b>一次自修复 ≈ 多一次模型调用 + 重新执行一次</b>，
     * 值不值取决于那一次失败会不会变成正确答案。
     */
    private void repairLatency(JdbcTemplate jdbc, ChatClient chat, SchemaRenderer renderer,
                               TableSelector selector, int tableTopK, int maxContextRows,
                               FewShotExamples examples, int repairAttempts) {
        log.info("");
        log.info("================= 自修复的延迟成本（故障注入，控制变量） =================");

        Text2SqlDiagnostic.Case c = Text2SqlDiagnostic.CASES.get(0);   // D1
        String fewShotBlock = examples.render(examples.size());
        log.info("用「{}」这道题（{}），配置固定为「筛表 + few-shot {} 条」", c.id(), c.question(), examples.size());

        long t0 = System.currentTimeMillis();
        QueryResult base = tool(jdbc, chat, renderer, selector, tableTopK, maxContextRows,
                fewShotBlock, 0, null).query(c.question());
        long baseMs = System.currentTimeMillis() - t0;
        String goodSql = base.sql() == null ? "" : base.sql();
        log.info("参照跑（不自修复、无注入）：{} ms", baseMs);
        log.info("  模型写的 SQL：{}", oneLine(goodSql));

        Text2SqlDatabaseTool.SqlFault fault = pickFault(goodSql);
        if (fault == null) {
            log.warn("  [!] 这条 SQL 里没找到可改坏的候选标识（{}），本节跳过（如实跳过，不硬编）", FAULT_CANDIDATES);
            return;
        }
        log.info("故意改坏一个标识：{}（这一条一定执行失败：column ... does not exist）", fault.describe());

        long fail0;
        long failN;
        QueryResult r0;
        QueryResult rn;
        {
            Text2SqlDatabaseTool noRepair = tool(jdbc, chat, renderer, selector, tableTopK, maxContextRows,
                    fewShotBlock, 0, fault);
            Text2SqlDatabaseTool withRepair = tool(jdbc, chat, renderer, selector, tableTopK, maxContextRows,
                    fewShotBlock, repairAttempts, fault);

            long s0 = System.currentTimeMillis();
            r0 = noRepair.query(c.question());
            fail0 = System.currentTimeMillis() - s0;

            long s1 = System.currentTimeMillis();
            rn = withRepair.query(c.question());
            failN = System.currentTimeMillis() - s1;
        }
        boolean healed = rn.sql() != null && rn.sql().contains("[自修复") && !rn.sql().contains("[执行失败]");

        log.info("");
        log.info("{} {} {} {}",
                ConsoleText.padRight("情形", 22), ConsoleText.padRight("耗时(ms)", 10),
                ConsoleText.padRight("结果", 10), "说明");
        log.info("{} {} {} {}",
                ConsoleText.padRight("参照（无错）", 22), ConsoleText.padRight(String.valueOf(baseMs), 10),
                ConsoleText.padRight("成功", 10), "一次生成 + 一次执行");
        log.info("{} {} {} {}",
                ConsoleText.padRight("注入错误 repair=0", 22), ConsoleText.padRight(String.valueOf(fail0), 10),
                ConsoleText.padRight(hasFailure(r0) ? "失败" : "?", 10), "一次生成 + 一次失败执行（快，但没答案）");
        log.info("{} {} {} {}",
                ConsoleText.padRight("注入错误 repair=" + repairAttempts, 22), ConsoleText.padRight(String.valueOf(failN), 10),
                ConsoleText.padRight(healed ? "自愈" : "仍失败", 10), "多一次模型调用 + 重新执行");
        log.info("");
        log.info("  ★ 代价 = {} ms（约多一次模型调用）；买到的是：{}",
                failN - fail0, healed ? "把一次硬失败变成一个正确答案" : "这次没买成（模型没修对，如实记录）");
        log.info("  ★ 注意这是【最坏情形】的耗时（真报错了才走这条路）——平时它一分钱不花。");
    }

    // ==================================================================
    // 汇总与输出
    // ==================================================================

    /** 一条评测结果（CSV 的一行）。 */
    private record Score(String setId, String arm, String caseId, String question,
                         String expectedRoute, String actualRoute,
                         boolean executable, Double sqlIntent, Double execAccuracy,
                         int rowCount, long latencyMs, String sql, String note, int schemaChars) {
    }

    private void printSummary(List<Score> scores) {
        log.info("");
        log.info("================= 汇总（这就是「第二个基线数字」） =================");
        log.info("{} {} {} {} {} {} {}",
                ConsoleText.padRight("集合", 8),
                ConsoleText.padRight("配置", 12),
                ConsoleText.padRight("题数", 5),
                ConsoleText.padRight("可执行率", 9),
                ConsoleText.padRight("SQL正确率", 10),
                ConsoleText.padRight("执行准确率", 11),
                "平均耗时(ms)／注入字符");
        for (Score k : keys(scores)) {
            List<Score> group = groupOf(scores, k.setId(), k.arm());
            log.info("{} {} {} {} {} {} {}",
                    ConsoleText.padRight(k.setId(), 8),
                    ConsoleText.padRight(k.arm(), 12),
                    ConsoleText.padRight(String.valueOf(group.size()), 5),
                    ConsoleText.padRight(rate(group, Score::executable), 9),
                    ConsoleText.padRight(avgStr(group.stream().map(Score::sqlIntent).toList()), 10),
                    ConsoleText.padRight(avgStr(group.stream().map(Score::execAccuracy).toList()), 11),
                    String.format("%.0f / %.0f", avgLong(group), avgInt(group)));
        }
        log.info("");
        log.info("说明：可执行率 = 真的跑成了一条 SQL（模型主动拒答不算）；");
        log.info("      SQL 正确率 = LLM 判这条 SQL 的形状对不对；执行准确率 = 查出来的数据对不对；");
        log.info("      三者不是包含关系 —— 典型分叉是「跑通了、形状对、但取值域写错 → 0 行」。");
    }

    private static final List<String> CSV_HEADER = List.of(
            "run", "set", "arm", "case_id", "question", "expected_route", "actual_route",
            "executable", "sql_intent", "exec_accuracy", "row_count", "latency_ms",
            "schema_chars", "sql", "note");

    private void writeCsv(Path path, List<Score> scores) {
        try {
            for (Score s : scores) {
                Csv.appendRow(path, CSV_HEADER, List.of(
                        ts(), s.setId(), s.arm(), s.caseId(), s.question(),
                        s.expectedRoute(), s.actualRoute(),
                        s.executable() ? "1" : "0",
                        num(s.sqlIntent()), num(s.execAccuracy()),
                        s.rowCount() < 0 ? "" : String.valueOf(s.rowCount()),
                        String.valueOf(s.latencyMs()),
                        String.valueOf(s.schemaChars()),
                        s.sql() == null ? "" : s.sql(),
                        s.note()));
            }
            log.info("");
            log.info("逐题结果已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    private static final List<String> SUMMARY_HEADER = List.of(
            "run", "set", "arm", "cases", "executable_rate", "sql_intent",
            "exec_accuracy", "avg_latency_ms", "avg_schema_chars", "note");

    private void writeSummaryCsv(Path path, List<Score> scores, String runStamp) {
        try {
            for (Score k : keys(scores)) {
                List<Score> group = groupOf(scores, k.setId(), k.arm());
                Csv.appendRow(path, SUMMARY_HEADER, List.of(
                        runStamp, k.setId(), k.arm(), String.valueOf(group.size()),
                        rate(group, Score::executable),
                        avgStr(group.stream().map(Score::sqlIntent).toList()),
                        avgStr(group.stream().map(Score::execAccuracy).toList()),
                        String.format("%.0f", avgLong(group)),
                        String.valueOf(Math.round(avgInt(group))),
                        "branch=绕过路由测支路；e2e=走完整 Agent"));
            }
            log.info("汇总已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    // ==================================================================
    // 判据小工具
    // ==================================================================

    /**
     * 可执行 = 真的跑成了一条查询。
     *
     * <p>三种都算「没跑成」：报错（{@code [执行失败]}）、被护栏拦（{@code [已阻止]}）、
     * 以及<b>模型主动用纯注释拒答</b>（那是它的选择，不是一次执行）。
     * 把拒答也算进来会让「可执行率」虚高——它明明一行 SQL 都没跑。
     */
    private static boolean executable(QueryResult r) {
        if (r == null) {
            return false;
        }
        String sql = r.sql() == null ? "" : r.sql();
        if (sql.isBlank() || SqlText.isOnlyComments(sql)) {
            return false;
        }
        if (sql.contains("[执行失败]") || sql.contains("[已阻止]")) {
            return false;
        }
        return !sql.startsWith("-- 读取表结构失败")
                && !sql.startsWith("-- 生成 SQL 失败")
                && !sql.startsWith("-- 模型没有给出可执行的 SQL");
    }

    private static boolean hasFailure(QueryResult r) {
        return r != null && r.sql() != null && r.sql().contains("[执行失败]");
    }

    // 迭代 23：「抽金额数字 + 容差比对」这套量具抽成了 util/Amounts（报告与支路评测共用一份）。
    // 它原来长在这里（含那次「小数位被当成大数字」的边界 bug 与修复说明），现整体搬走，
    // 免得两份实现悄悄漂移——量具漂移了不会报错，只会给出一个错的准确率。

    /** 这次真正会注入多少字符。选空集时工具会回退全量，这里也要如实算（同 迭代 19 的教训）。 */
    private static int injectedChars(SchemaRenderer renderer, TableSelector selector, String question, int topK) {
        try {
            List<String> selected = selector.select(question, topK);
            if (selected.isEmpty()) {
                return renderer.render().length();
            }
            return renderer.render(new LinkedHashSet<>(selected)).length();
        }
        catch (RuntimeException e) {
            return renderer.render().length();
        }
    }

    /** 单一构造点：任何一天想改 Options 的字段，只改这里（避免「加一个字段、几个调用点全忘」）。 */
    private static Text2SqlDatabaseTool tool(JdbcTemplate jdbc, ChatClient chat, SchemaRenderer renderer,
                                             TableSelector selector, int tableTopK, int maxContextRows,
                                             String fewShotBlock, int repair,
                                             Text2SqlDatabaseTool.SqlFault fault) {
        return new Text2SqlDatabaseTool(jdbc, chat, renderer,
                new Text2SqlDatabaseTool.Options(
                        maxContextRows, 0,
                        selector, selector == null ? 0 : tableTopK,
                        fewShotBlock, repair, fault, null, null));
    }

    private static Text2SqlDatabaseTool.SqlFault pickFault(String sql) {
        for (String candidate : FAULT_CANDIDATES) {
            if (sql.contains(candidate)) {
                return new Text2SqlDatabaseTool.SqlFault(candidate, candidate + "_typo");
            }
        }
        return null;
    }

    private static int tableCount(SchemaRenderer renderer) {
        try {
            return renderer.tableNames().size();
        }
        catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 库没扩表就报警 —— 否则「筛表 vs 不筛表」的对比会给出一个<b>不可解释</b>的结果。
     *
     * <p>和 迭代 19 覆盖率自检同一个理由：5 张表时 top-8 等于全选，
     * 于是 filtered 和 no-filter 必然一模一样，你会以为「筛表没用」。
     */
    private static void warnIfNotExpanded(SchemaRenderer renderer) {
        int n = tableCount(renderer);
        if (n < 0) {
            log.error("[X] 连不上业务库 —— 先 `docker compose up -d business-db`。");
            return;
        }
        if (n <= 5) {
            log.warn("");
            log.warn("  [X] 库里只有 {} 张表（未扩表）。", n);
            log.warn("      -> 【筛表 vs 不筛表这一对比将不可解释】：top-8 会选中全部 {} 张表，", n);
            log.warn("         两档的注入内容完全相同，数字必然一样。这不是「筛表没用」，是「没得筛」。");
            log.warn("         先灌扩表脚本再跑本评测（PowerShell 不支持 < 重定向，用 cp 两步法）：");
            log.warn("           docker compose cp docker/init-business-db-extra.sql business-db:/tmp/extra.sql");
            log.warn("           docker compose exec business-db psql -U sdaq -d business_db -f /tmp/extra.sql");
            log.warn("      -> 其余数字（可执行率 / 执行准确率）仍有效：D1—D9 只碰原来那 5 张表。");
            log.warn("");
        }
    }

    // ==================================================================
    // 打印辅助
    // ==================================================================

    /** 保持出现顺序的去重键（setId, arm）。 */
    private static List<Score> keys(List<Score> scores) {
        List<Score> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Score s : scores) {
            String key = s.setId() + "|" + s.arm();
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<Score> groupOf(List<Score> scores, String setId, String arm) {
        List<Score> out = new ArrayList<>();
        for (Score s : scores) {
            if (s.setId().equals(setId) && s.arm().equals(arm)) {
                out.add(s);
            }
        }
        return out;
    }

    private static String rate(List<Score> group, java.util.function.Predicate<Score> p) {
        if (group.isEmpty()) {
            return "-";
        }
        long ok = group.stream().filter(p).count();
        return String.format("%.2f (%d/%d)", (double) ok / group.size(), ok, group.size());
    }

    /** 求平均，<b>排除 null（不适用）与 UNPARSED（未解析）</b>——同 迭代 15 的自律。 */
    private static String avgStr(List<Double> vals) {
        List<Double> ok = vals.stream().filter(v -> v != null && v >= 0).toList();
        if (ok.isEmpty()) {
            return "-";
        }
        return String.format("%.2f", ok.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double avgLong(List<Score> group) {
        return group.stream().mapToLong(Score::latencyMs).average().orElse(0);
    }

    private static double avgInt(List<Score> group) {
        return group.stream().mapToInt(Score::schemaChars).average().orElse(0);
    }

    private static String num(Double v) {
        if (v == null) {
            return "";
        }
        if (v == LlmJudge.UNPARSED) {
            return "unparsed";
        }
        return String.format("%.2f", v);
    }

    private static String fmt(Double v) {
        if (v == null) {
            return "-";
        }
        if (v == LlmJudge.UNPARSED) {
            return "未解析";
        }
        return String.format("%.2f", v);
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String ts() {
        return LocalDateTime.now().format(TS);
    }

    // 迭代 23：resultNumbers 也搬进了 util/Amounts（inResult）。

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) {
            flat = flat.replace("  ", " ");
        }
        return flat.length() <= 220 ? flat : flat.substring(0, 220) + "…";
    }

    /**
     * 结语。<b>依旧只写方法与判据，不写预测</b>——
     * 迭代 18 我事先断言了失败模式，被实跑当场推翻，这条规矩从那天起立住了。
     */
    private void printConclusion(int repairAttempts) {
        log.info("");
        log.info("================= 本轮得到了什么 =================");
        log.info("1. 数据库支路的第一个可复现数字出来了（第二个基线数字）。它和 RAG 支路 迭代 15 的那个同级——");
        log.info("   一条命令跑完、结果是比率，不是「需人工判断」的诊断。");
        log.info("");
        log.info("2. 三个指标分开报，因为它们往不同方向分叉：");
        log.info("   可执行率  —— 跑没跑通（确定性）；低了说明 SQL 写法/护栏有问题");
        log.info("   SQL 正确率 —— 形状对不对（LLM 判）");
        log.info("   执行准确率 —— 数据对不对（用户关心的那个）");
        log.info("   ★ 最该记住的一档：SQL 形状对、执行也成功、但取值域写错 → 返回 0 行。");
        log.info("     三个数一起看才看得见它，只看『可执行率』会以为系统很好。");
        log.info("");
        log.info("3. 筛表 vs 不筛表，本轮量的是【对答案的影响】（迭代 19 量的是选表准不准）。");
        log.info("   注意它的前提：库必须扩到 20 张表，否则 top-8 等于全选、两档必然一样。");
        log.info("");
        log.info("4. 自修复的延迟成本（最坏情形）：多一次模型调用（repair 上限 {} 次）。", repairAttempts);
        log.info("   它平时一分钱不花——只在执行报错时才动。这也是它值得默认开着的原因。");
        log.info("");
        log.info("5. 与 迭代 18/20 的关系：那两天是【诊断】（允许人工档），本轮是【指标】（不许人工档）。");
        log.info("   题目用的还是同一批（Text2SqlDiagnostic.CASES），但判定补齐到了每一档。");
        log.info("");
        log.info("6. 结果写进 eval/text2sql-eval.csv（逐题）与 eval/text2sql-eval-summary.csv（每个配置一行）。");
    }
}
