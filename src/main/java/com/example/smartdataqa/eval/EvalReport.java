package com.example.smartdataqa.eval;

import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.rag.BasicRagPipeline;
import com.example.smartdataqa.router.RouteDecision;
import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.util.Amounts;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 迭代 23 · <b>评测体系固化</b>：一条命令跑完全部用例，出一张表。
 *
 * <h3>它要解决的问题：前面的数字都是真的，但它们散在六个文件里</h3>
 * 迭代 15/16/17/19/20/22 各写了一个 demo、各写了一个 CSV。
 * 每个都对，但没人能一眼回答「这个系统现在到底怎么样」——
 * 路由 0.89、检索 0.63、忠实性 0.93、执行准确率 0.38……这些数在哪张表里？
 * <b>没有一个能拿去放进 README 的东西。</b>本轮把它做出来。
 *
 * <h3>六类指标，一张表</h3>
 * <pre>
 *   路由准确率        四值分类对不对（原创指标）
 *   路由混淆矩阵      谁被错分成了谁 —— 只有矩阵才看得出「错误是往哪个方向偏的」
 *   检索指标          context 精确率 / 召回率（文档层 + LLM 层）
 *   生成指标          faithfulness / answer_relevancy（框架自带两个 Evaluator）
 *   执行准确率        数据库支路专用：查出来的数据对不对
 *   端到端正确率      用户视角：最终答案对不对
 * </pre>
 *
 * <h3>为什么「混淆矩阵」比「准确率」值钱</h3>
 * 一个 0.89 不告诉你错在哪。矩阵会：迭代 15 那 3 处错<b>全是从 document 掉进 database 或 refuse</b>，
 * 而按 迭代 25 的代价分析，这恰恰是<b>最坏的那个方向</b>（会产生用户察觉不到的假数字）。
 * <b>准确率是一维的，失败模式不是。</b>
 *
 * <h3>三个诚实性设计（沿用 迭代 15，都别再踩）</h3>
 * <ol>
 *   <li><b>判分没解析出来记 {@link LlmJudge#UNPARSED}，不计入平均</b>，并在备注里报数。
 *       把它当 0 会让「判分器坏了」伪装成「答案很差」。</li>
 *   <li><b>某项指标在所有用例上都是同一个极端值会报警</b>（全 0.00 或全 1.00 都查）。</li>
 *   <li><b>答案原文在「未满分」或「两个判分器打架」时打出来</b>——看不见中间产物就没法诊断。</li>
 * </ol>
 *
 * <p><b>与 迭代 15 的 {@link EvalRunner} 的关系：</b>本类是它的接任者（多算了混淆矩阵与执行准确率，
 * 并产出 README 可用的报告）。EvalRunner 保留着，是为了让 {@code exp15-baseline} 的历史行仍可复现。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp23.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp23.enabled", havingValue = "true")
public class EvalReport {

    private static final Logger log = LoggerFactory.getLogger(EvalReport.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用例数少于这个值不做「极端值」报警——样本太小，「全通过」本来就是正常的。 */
    private static final int MIN_CASES_TO_WARN = 10;

    /** 四值路由的固定顺序。混淆矩阵的行、列、以及各处的展示都按它排，保证每次跑的位置一致。 */
    private static final List<String> ROUTE_ORDER = List.of("document", "database", "both", "none");

    /** 一个用例在一条链路上的完整结果。{@code null} 表示「这题不适用该指标」。 */
    private record CaseScore(String id,
                             String expectedRoute,
                             String category,
                             String actualRoute,
                             boolean routeOk,
                             double latencyMs,
                             Double answerCorrect,
                             Double faithfulness,
                             Double answerRelevancy,
                             Double ctxRecallDoc,
                             Double ctxPrecisionDoc,
                             Double ctxRecallLlm,
                             Double ctxPrecisionLlm,
                             Double execAccuracy,
                             String answer) {
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)   // 必须等入库跑完（见 AgentConfig.ingestCorpusRunner）
    ApplicationRunner exp23EvalReport(
            SmartQaAgent agent,
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.limit:0}") int limit,
            @Value("${sdaq.eval.experiment:exp23-report}") String experiment,
            @Value("${sdaq.eval.report:eval/report.md}") String reportPath,
            @Value("${sdaq.eval.report-csv:eval/report.csv}") String reportCsvPath) {

        return args -> run(agent, vectorStore, chatClientBuilder, testsetPath, limit,
                experiment, reportPath, reportCsvPath);
    }

    private void run(SmartQaAgent agent, VectorStore vectorStore, ChatClient.Builder builder,
                     String testsetPath, int limit, String experiment,
                     String reportPath, String reportCsvPath) throws Exception {

        List<EvalCase> cases = loadCases(testsetPath, limit);
        String label = limit > 0 ? experiment + "-limit" + limit : experiment;
        String ts = LocalDateTime.now().format(TS);

        log.info("========== 迭代 23 · 评测报告（一条命令，六类指标） ==========");
        log.info("实验名：{}｜用例：{} 条｜链路：agent + basic-rag 对照", label, cases.size());

        ChatClient plain = builder.build();
        ChatClient.Builder judgeBuilder = builder.clone().defaultAdvisors(new JudgeLoggingAdvisor());
        BasicRagPipeline basicRag = new BasicRagPipeline(plain, vectorStore);
        LlmJudge judge = new LlmJudge(plain);
        RelevancyEvaluator relevancy = new RelevancyEvaluator(judgeBuilder);
        // FactCheckingEvaluator 的构造器是 protected，只能走公开的 builder
        FactCheckingEvaluator factCheck = FactCheckingEvaluator.builder(judgeBuilder).build();

        // ---------- 链路 1：agent（跑全部用例，因为要算路由准确率与混淆矩阵） ----------
        List<CaseScore> agentScores = new ArrayList<>();
        for (EvalCase c : cases) {
            agentScores.add(runAgent(agent, c, judge, relevancy, factCheck));
        }

        // ---------- 链路 2：basic-rag（只跑 document 用例，它没有路由能力） ----------
        List<CaseScore> ragScores = new ArrayList<>();
        for (EvalCase c : cases) {
            if ("document".equals(c.expectedRoute())) {
                ragScores.add(runBasicRag(basicRag, c, judge, relevancy, factCheck));
            }
        }

        List<CaseScore> agentDocOnly = agentScores.stream()
                .filter(s -> "document".equals(s.expectedRoute()))
                .toList();

        printReport(agentScores, agentDocOnly, ragScores);
        warnIfSuspicious(agentScores);

        writeMarkdown(Path.of(reportPath), label, ts, agentScores, agentDocOnly, ragScores);
        writeCsv(Path.of(reportCsvPath), label, ts, agentScores, agentDocOnly, ragScores);

        log.info("========== 报告结束 ==========");
    }

    // ==================================================================
    // 两条链路的执行
    // ==================================================================

    private CaseScore runAgent(SmartQaAgent agent, EvalCase c, LlmJudge judge,
                               RelevancyEvaluator relevancy, FactCheckingEvaluator factCheck) {
        log.info("");
        log.info("---- [agent] {}｜{}", c.id(), c.question());
        long t0 = System.currentTimeMillis();
        SmartQaAgent.Answer a = agent.answer(c.question());
        long ms = System.currentTimeMillis() - t0;

        RouteDecision.Route route = a.decision().route();
        boolean routeOk = route == c.route();
        log.info("     路由 期望={} 实际={} {}", c.expectedRoute(), route.code(), routeOk ? "[OK]" : "[X]");

        List<Chunk> chunks = a.chunks() == null ? List.of() : a.chunks();
        String dbCtx = a.dbResult() == null ? "" : a.dbResult().render();
        CaseScore s = score(c, route, routeOk, ms, a.text(), chunks, dbCtx, a.dbResult(),
                judge, relevancy, factCheck);
        logNotable(s);
        return s;
    }

    private CaseScore runBasicRag(BasicRagPipeline rag, EvalCase c, LlmJudge judge,
                                  RelevancyEvaluator relevancy, FactCheckingEvaluator factCheck) {
        log.info("");
        log.info("---- [basic-rag] {}｜{}", c.id(), c.question());
        long t0 = System.currentTimeMillis();
        BasicRagPipeline.Answer a = rag.answer(c.question());
        long ms = System.currentTimeMillis() - t0;
        // basic-rag 不做路由，actualRoute 用 null；它也没有数据库支路
        CaseScore s = score(c, null, false, ms, a.text(), a.chunks(), "", null,
                judge, relevancy, factCheck);
        logNotable(s);
        return s;
    }

    /**
     * 两条链路共用的判分流程。
     *
     * @param dbContext 数据库结果的文本形式（没有就传空串）。
     *                  <b>必须并进「上下文」</b>——否则 {@code both} 用例会出假指标：
     *                  答案引用了库里的数字，而上下文里只有文档片段，
     *                  严格的事实核查会判「这个数字不被材料支持」（迭代 15 全量跑就这么错过一次）。
     * @param dbResult  数据库结果本身，用来算<b>执行准确率</b>（没有就传 null）。
     */
    private CaseScore score(EvalCase c, RouteDecision.Route actualRoute, boolean routeOk, long ms,
                            String answer, List<Chunk> chunks, String dbContext, QueryResult dbResult,
                            LlmJudge judge, RelevancyEvaluator relevancy, FactCheckingEvaluator factCheck) {

        String ctx = joinedContext(chunks);
        if (dbContext != null && !dbContext.isBlank()) {
            ctx = ctx.isBlank() ? dbContext : ctx + "\n\n" + dbContext;
        }
        String safeAnswer = answer == null ? "" : answer;
        String actualLabel = actualRoute == null ? "-" : actualRoute.code();

        // ---- 不用模型的两项：文档级 context ----
        Double recallDoc = null;
        Double precisionDoc = null;
        if (c.usesDocument() && !chunks.isEmpty()) {
            List<String> want = c.sources();
            long hit = chunks.stream().filter(ch -> want.contains(docIdOf(ch.id()))).count();
            precisionDoc = (double) hit / chunks.size();
            if (!want.isEmpty()) {
                long covered = want.stream()
                        .filter(w -> chunks.stream().anyMatch(ch -> docIdOf(ch.id()).equals(w)))
                        .count();
                recallDoc = (double) covered / want.size();
            }
        }

        // ---- 执行准确率：数据库支路专用，且只在这个用例真的有期望数字时才算 ----
        Double execAccuracy = null;
        if (dbResult != null && c.hasExpectedAnswer()) {
            List<Double> want = Amounts.inText(c.expectedAnswer());
            if (!want.isEmpty()) {
                execAccuracy = Amounts.containsAll(want, Amounts.inResult(dbResult), 0.01) ? 1.0 : 0.0;
            }
        }

        // ---- 要用模型的五项 ----
        Double answerCorrect = null;
        Double faithfulness = null;
        Double answerRelevancy = null;
        Double ctxRecallLlm = null;
        Double ctxPrecisionLlm = null;

        boolean refusedBoth = c.route() == RouteDecision.Route.REFUSE
                && actualRoute == RouteDecision.Route.REFUSE;
        if (refusedBoth && c.hasExpectedAnswer()) {
            // 拒答对了就是对的，不值得再花一次模型调用去问
            answerCorrect = 1.0;
        }
        else if (c.hasExpectedAnswer() && !safeAnswer.isBlank()) {
            answerCorrect = judge.gradeAnswer(c.question(), c.expectedAnswer(), safeAnswer);
        }

        if (!chunks.isEmpty()) {
            ctxPrecisionLlm = judge.gradeContextPrecision(c.question(),
                    chunks.stream().map(Chunk::content).toList());
        }
        if (!chunks.isEmpty() && c.hasExpectedAnswer()) {
            ctxRecallLlm = judge.gradeContextRecall(c.expectedAnswer(), ctx);
        }
        if (!chunks.isEmpty() && !safeAnswer.isBlank()) {
            faithfulness = frameworkFaithfulness(factCheck, ctx, safeAnswer);
            answerRelevancy = frameworkRelevancy(relevancy, c.question(), chunks, safeAnswer);
        }

        return new CaseScore(c.id(), c.expectedRoute(), c.category(), actualLabel, routeOk, ms,
                answerCorrect, faithfulness, answerRelevancy, recallDoc, precisionDoc,
                ctxRecallLlm, ctxPrecisionLlm, execAccuracy, safeAnswer);
    }

    /** 框架忠实性判分。上下文必须进 {@code dataList}（{@code {document}} 只从那里面取）。 */
    private Double frameworkFaithfulness(FactCheckingEvaluator evaluator, String context, String answer) {
        if (context == null || context.isBlank()) {
            log.warn("      [判分] 忠实性跳过：上下文为空");
            return null;
        }
        try {
            return evaluator.evaluate(new EvaluationRequest(List.of(new Document(context)), answer)).isPass()
                    ? 1.0 : 0.0;
        }
        catch (RuntimeException e) {
            log.warn("      [判分] 框架忠实性判分异常：{}", e.toString());
            return LlmJudge.UNPARSED;
        }
    }

    /** 框架相关性判分：答案是否与「问题 + 上下文」一致。 */
    private Double frameworkRelevancy(RelevancyEvaluator evaluator, String question,
                                      List<Chunk> chunks, String answer) {
        List<Document> data = chunks.stream().map(ch -> new Document(ch.content())).toList();
        try {
            return evaluator.evaluate(new EvaluationRequest(question, data, answer)).isPass() ? 1.0 : 0.0;
        }
        catch (RuntimeException e) {
            log.warn("      [判分] 框架相关性判分异常：{}", e.toString());
            return LlmJudge.UNPARSED;
        }
    }

    /**
     * 值得记一笔的用例：把答案原文打出来。
     *
     * <p>只在两种情况打，所以不会变成噪声：<b>两个判分器打架</b>，或<b>答案没拿满分</b>。
     * 没有这行日志，迭代 15 那个「参数传错」就会被误解释成「框架判得太严」并写进文档。
     */
    private void logNotable(CaseScore s) {
        if (s.answer().isBlank()) {
            return;
        }
        boolean disagree = s.answerCorrect() != null && s.faithfulness() != null
                && ((s.answerCorrect() >= 1.0 && s.faithfulness() == 0.0)
                    || (s.answerCorrect() == 0.0 && s.faithfulness() == 1.0));
        if (disagree) {
            log.warn("      [分歧] {} 答案判定={} 忠实性={} —— 答案原文（前 200 字）：{}",
                    s.id(), fmt(s.answerCorrect()), fmt(s.faithfulness()), head(s.answer()));
            return;
        }
        if (s.answerCorrect() != null && s.answerCorrect() < 1.0) {
            log.info("      [未满分] {} 答案判定={} —— 答案原文（前 200 字）：{}",
                    s.id(), fmt(s.answerCorrect()), head(s.answer()));
        }
    }

    // ==================================================================
    // 汇总与打印
    // ==================================================================

    private void printReport(List<CaseScore> all, List<CaseScore> docOnly, List<CaseScore> rag) {
        List<CaseScore> dbCases = all.stream().filter(s -> s.execAccuracy() != null).toList();

        log.info("");
        log.info("================= 六类指标（这就是 README 的核心资产） =================");
        log.info("{} {}",
                ConsoleText.padRight("指标", 22),
                "值");
        log.info("{} {}", ConsoleText.padRight("路由准确率", 22), rate(all, CaseScore::routeOk));
        log.info("{} {}", ConsoleText.padRight("端到端正确率", 22), avg(all, CaseScore::answerCorrect));
        log.info("{} {}", ConsoleText.padRight("执行准确率(database)", 22), rate(dbCases, s -> s.execAccuracy() > 0));
        log.info("{} {}", ConsoleText.padRight("检索·精确(文档层)", 22), avg(all, CaseScore::ctxPrecisionDoc));
        log.info("{} {}", ConsoleText.padRight("检索·召回(文档层)", 22), avg(docOnly, CaseScore::ctxRecallDoc));
        log.info("{} {}", ConsoleText.padRight("检索·精确(LLM)", 22), avg(all, CaseScore::ctxPrecisionLlm));
        log.info("{} {}", ConsoleText.padRight("检索·召回(LLM)", 22), avg(docOnly, CaseScore::ctxRecallLlm));
        log.info("{} {}", ConsoleText.padRight("生成·忠实性", 22), avg(all, CaseScore::faithfulness));
        log.info("{} {}", ConsoleText.padRight("生成·相关性", 22), avg(all, CaseScore::answerRelevancy));
        log.info("{} {} ms", ConsoleText.padRight("平均耗时", 22), String.format("%.0f", avgLatency(all)));

        printConfusion(all);
        printByCategory(docOnly);
        printRagCompare(docOnly, rag);
    }

    /** 混淆矩阵：行=期望，列=实际。 */
    private void printConfusion(List<CaseScore> scores) {
        log.info("");
        log.info("================= 路由混淆矩阵（行=期望，列=实际） =================");
        StringBuilder header = new StringBuilder(ConsoleText.padRight("期望 \\ 实际", 14));
        for (String r : ROUTE_ORDER) {
            header.append(ConsoleText.padRight(r, 11));
        }
        log.info(header.toString());

        for (String exp : ROUTE_ORDER) {
            StringBuilder row = new StringBuilder(ConsoleText.padRight(exp, 14));
            for (String act : ROUTE_ORDER) {
                int n = countPair(scores, exp, act);
                String cell = (n == 0) ? "." : String.valueOf(n);
                if (exp.equals(act)) {
                    cell = n == 0 ? "." : n + " [OK]";
                }
                row.append(ConsoleText.padRight(cell, 11));
            }
            log.info(row.toString());
        }
        // 把「最坏方向」单独点出来 —— 这是矩阵比准确率值钱的地方
        int docToDb = countPair(scores, "document", "database");
        log.info("");
        log.info("★ 最该盯的一格：document→database = {} 条（会产生用户察觉不到的假数字，迭代 25 代价分析的主角）",
                docToDb);
    }

    private void printByCategory(List<CaseScore> docOnly) {
        Map<String, List<CaseScore>> byCat = new LinkedHashMap<>();
        for (CaseScore s : docOnly) {
            byCat.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
        }
        log.info("");
        log.info("================= 按 category 分组（agent，document 用例） =================");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("分类", 14), ConsoleText.padRight("条数", 6),
                ConsoleText.padRight("答案", 8), ConsoleText.padRight("忠实", 8), "检索精确LLM");
        for (Map.Entry<String, List<CaseScore>> e : byCat.entrySet()) {
            List<CaseScore> v = e.getValue();
            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(e.getKey(), 14), ConsoleText.padRight(String.valueOf(v.size()), 6),
                    ConsoleText.padRight(avg(v, CaseScore::answerCorrect), 8),
                    ConsoleText.padRight(avg(v, CaseScore::faithfulness), 8),
                    avg(v, CaseScore::ctxPrecisionLlm));
        }
        log.info("注意：条数少的分类不能当结论（3 条里错 1 条就是 0.33 的差）。");
    }

    private void printRagCompare(List<CaseScore> agentDoc, List<CaseScore> rag) {
        log.info("");
        log.info("================= agent vs basic-rag（同一批 document 用例） =================");
        log.info("{} {} {} {}",
                ConsoleText.padRight("链路", 12), ConsoleText.padRight("答案", 8),
                ConsoleText.padRight("忠实", 8), "检索精确LLM");
        log.info("{} {} {} {}",
                ConsoleText.padRight("agent", 12), ConsoleText.padRight(avg(agentDoc, CaseScore::answerCorrect), 8),
                ConsoleText.padRight(avg(agentDoc, CaseScore::faithfulness), 8),
                avg(agentDoc, CaseScore::ctxPrecisionLlm));
        log.info("{} {} {} {}",
                ConsoleText.padRight("basic-rag", 12), ConsoleText.padRight(avg(rag, CaseScore::answerCorrect), 8),
                ConsoleText.padRight(avg(rag, CaseScore::faithfulness), 8),
                avg(rag, CaseScore::ctxPrecisionLlm));
        log.info("（agent 的真正优势是能答 basic-rag 答不了的另外 13 题；这张表只比它们重合的那批）");
    }

    // ==================================================================
    // 产出：Markdown 报告 + CSV
    // ==================================================================

    private void writeMarkdown(Path path, String label, String ts, List<CaseScore> all,
                               List<CaseScore> docOnly, List<CaseScore> rag) {
        List<CaseScore> dbCases = all.stream().filter(s -> s.execAccuracy() != null).toList();
        StringBuilder md = new StringBuilder();

        md.append("# 智能数据问答助手 · 评测报告\n\n");
        md.append("> 实验：`").append(label).append("` ｜ 生成于 ").append(ts)
          .append(" ｜ 用例 ").append(all.size()).append(" 条 ｜ 链路 agent + basic-rag\n\n");
        md.append("> 一条命令产出：`mvn spring-boot:run \"-Dspring-boot.run.arguments=--sdaq.exp23.enabled=true\"`\n\n");

        md.append("## 一、六类指标\n\n");
        md.append("| 指标 | 值 | 说明 |\n|---|---|---|\n");
        md.append("| **路由准确率** | ").append(rate(all, CaseScore::routeOk))
          .append(" | 四值分类（document/database/both/none） |\n");
        md.append("| **端到端正确率** | ").append(avg(all, CaseScore::answerCorrect))
          .append(" | 用户视角：最终答案对不对 |\n");
        md.append("| **执行准确率** | ").append(rate(dbCases, s -> s.execAccuracy() > 0))
          .append(" | 数据库支路专用：查出来的数据对不对 |\n");
        md.append("| 检索·context 精确率 | ").append(avg(all, CaseScore::ctxPrecisionLlm))
          .append(" | LLM 判：召回片段里有多少真的有助于回答 |\n");
        md.append("| 检索·context 召回率 | ").append(avg(docOnly, CaseScore::ctxRecallLlm))
          .append(" | 标准答案的要点有多少能被上下文支持 |\n");
        md.append("| 生成·faithfulness | ").append(avg(all, CaseScore::faithfulness))
          .append(" | 答案是否被材料支持 |\n");
        md.append("| 生成·answer_relevancy | ").append(avg(all, CaseScore::answerRelevancy))
          .append(" | 答案是否切题（已知区分度低，见备注） |\n");
        md.append("| 平均耗时 | ").append(String.format("%.0f", avgLatency(all))).append(" ms | |\n\n");

        md.append("## 二、路由混淆矩阵\n\n");
        md.append("行 = 期望，列 = 实际；`[OK]` 是对角线（判对）。\n\n");
        md.append("| 期望 \\ 实际 |");
        for (String r : ROUTE_ORDER) {
            md.append(" ").append(r).append(" |");
        }
        md.append("\n|---|");
        for (int i = 0; i < ROUTE_ORDER.size(); i++) {
            md.append("---|");
        }
        md.append("\n");
        for (String exp : ROUTE_ORDER) {
            md.append("| **").append(exp).append("** |");
            for (String act : ROUTE_ORDER) {
                int n = countPair(all, exp, act);
                md.append(" ").append(n == 0 ? "·" : String.valueOf(n)).append(" |");
            }
            md.append("\n");
        }
        md.append("\n★ 最该盯的一格：**document → database** = ")
          .append(countPair(all, "document", "database"))
          .append(" 条——它会产生用户察觉不到的假数字。\n\n");

        md.append("## 三、按 category 分组（agent，document 用例）\n\n");
        Map<String, List<CaseScore>> byCat = new LinkedHashMap<>();
        for (CaseScore s : docOnly) {
            byCat.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
        }
        md.append("| 分类 | 条数 | 答案 | 忠实性 | 检索精确(LLM) |\n|---|---|---|---|---|\n");
        for (Map.Entry<String, List<CaseScore>> e : byCat.entrySet()) {
            List<CaseScore> v = e.getValue();
            md.append("| ").append(e.getKey()).append(" | ").append(v.size())
              .append(" | ").append(avg(v, CaseScore::answerCorrect))
              .append(" | ").append(avg(v, CaseScore::faithfulness))
              .append(" | ").append(avg(v, CaseScore::ctxPrecisionLlm))
              .append(" |\n");
        }
        md.append("\n> 条数少的分类不能当结论。\n\n");

        md.append("## 四、agent vs basic-rag（同一批 document 用例）\n\n");
        md.append("| 链路 | 答案 | 忠实性 | 检索精确(LLM) |\n|---|---|---|---|\n");
        md.append("| agent | ").append(avg(docOnly, CaseScore::answerCorrect))
          .append(" | ").append(avg(docOnly, CaseScore::faithfulness))
          .append(" | ").append(avg(docOnly, CaseScore::ctxPrecisionLlm)).append(" |\n");
        md.append("| basic-rag | ").append(avg(rag, CaseScore::answerCorrect))
          .append(" | ").append(avg(rag, CaseScore::faithfulness))
          .append(" | ").append(avg(rag, CaseScore::ctxPrecisionLlm)).append(" |\n\n");

        md.append("## 五、备注与已知局限\n\n");
        md.append("- 判分未解析的项**不计入平均**（避免把「判分器坏了」伪装成「答案很差」）。\n");
        md.append("- 判分噪声有量级：迭代 15 实测同一输入送两次可差 0.20（=1 个片段/5）→ ")
          .append("**15 条上的平均差异 <0.05 不可区分**。\n");
        md.append("- `answer_relevancy` 已知**区分度低**（迭代 15：43 次判分只否 1 次），保留只作透明。\n");
        md.append("- 执行准确率只看「期望的大额数字能否在结果里找到」，受**题面歧义**影响（见 迭代 22 发现 2）。\n");
        md.append("- 路由准确率自身有噪声：28 条上 **±1 条 ≈ 0.036**，小于 2 条（0.07）的提升不可声称。\n");

        try {
            Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
            log.info("");
            log.info("Markdown 报告已写入 {}", path.toAbsolutePath());
        }
        catch (IOException e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    private static final List<String> REPORT_HEADER = List.of(
            "experiment", "ts", "cases",
            "route_accuracy", "route_correct", "e2e_correct", "exec_accuracy", "exec_cases",
            "ctx_precision_llm", "ctx_recall_llm", "faithfulness", "answer_relevancy",
            "latency_ms_avg", "confusion", "note");

    private void writeCsv(Path path, String label, String ts, List<CaseScore> all,
                          List<CaseScore> docOnly, List<CaseScore> rag) {
        List<CaseScore> dbCases = all.stream().filter(s -> s.execAccuracy() != null).toList();
        try {
            Csv.appendRow(path, REPORT_HEADER, List.of(
                    label, ts, String.valueOf(all.size()),
                    rate(all, CaseScore::routeOk),
                    String.valueOf(all.stream().filter(CaseScore::routeOk).count()),
                    avg(all, CaseScore::answerCorrect),
                    rate(dbCases, s -> s.execAccuracy() > 0),
                    String.valueOf(dbCases.size()),
                    avg(all, CaseScore::ctxPrecisionLlm),
                    avg(docOnly, CaseScore::ctxRecallLlm),
                    avg(all, CaseScore::faithfulness),
                    avg(all, CaseScore::answerRelevancy),
                    String.format("%.0f", avgLatency(all)),
                    confusionCompact(all),
                    "迭代 23 一条命令出全部指标；详见 eval/report.md"));
            log.info("CSV 汇总已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    /** 混淆矩阵压成一行（只写非零格），便于 CSV 历史里对比。 */
    private static String confusionCompact(List<CaseScore> scores) {
        StringBuilder sb = new StringBuilder();
        for (String exp : ROUTE_ORDER) {
            for (String act : ROUTE_ORDER) {
                int n = countPair(scores, exp, act);
                if (n > 0) {
                    if (sb.length() > 0) {
                        sb.append(";");
                    }
                    sb.append(exp).append("->").append(act).append(":").append(n);
                }
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static int countPair(List<CaseScore> scores, String expected, String actual) {
        int n = 0;
        for (CaseScore s : scores) {
            if (s.expectedRoute().equals(expected) && normalize(s.actualRoute()).equals(actual)) {
                n++;
            }
        }
        return n;
    }

    /** 把实际的 refuse 归到测试集的 none 那一列，两套词只在这一点上对齐。 */
    private static String normalize(String code) {
        if (code == null) {
            return "-";
        }
        return "refuse".equals(code) ? "none" : code;
    }

    private static String rate(List<CaseScore> scores, java.util.function.Predicate<CaseScore> p) {
        if (scores.isEmpty()) {
            return "-";
        }
        long ok = scores.stream().filter(p).count();
        return String.format("%.2f (%d/%d)", (double) ok / scores.size(), ok, scores.size());
    }

    /** 求平均，<b>排除 null（不适用）与 UNPARSED（未解析）</b>。 */
    private static String avg(List<CaseScore> scores, Function<CaseScore, Double> get) {
        List<Double> vals = scores.stream().map(get).filter(v -> v != null && v >= 0).toList();
        if (vals.isEmpty()) {
            return "-";
        }
        return String.format("%.2f", vals.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double avgLatency(List<CaseScore> scores) {
        // latencyMs 是 double（记录里就是 double），所以走 mapToDouble —— 用 mapToLong 会编译不过
        return scores.stream().mapToDouble(CaseScore::latencyMs).average().orElse(0);
    }

    private static long countUnparsed(List<CaseScore> scores) {
        return scores.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.answerCorrect(), s.faithfulness(),
                        s.answerRelevancy(), s.ctxRecallLlm(), s.ctxPrecisionLlm()))
                .filter(v -> v != null && v == LlmJudge.UNPARSED)
                .count();
    }

    /** 分数可疑时报警——写在文件里的数字不会自己喊救命。 */
    private void warnIfSuspicious(List<CaseScore> scores) {
        if (scores.size() < MIN_CASES_TO_WARN) {
            return;
        }
        Map<String, Double> vals = new LinkedHashMap<>();
        vals.put("answer_correct", avgOf(scores, CaseScore::answerCorrect));
        vals.put("faithfulness", avgOf(scores, CaseScore::faithfulness));
        vals.put("answer_relevancy", avgOf(scores, CaseScore::answerRelevancy));
        vals.put("ctx_precision_llm", avgOf(scores, CaseScore::ctxPrecisionLlm));
        vals.put("ctx_recall_llm", avgOf(scores, CaseScore::ctxRecallLlm));
        for (Map.Entry<String, Double> e : vals.entrySet()) {
            Double v = e.getValue();
            if (v != null && (v == 0.0 || v == 1.0)) {
                log.warn("  [可疑] 指标 {} 在 {} 条用例上都是 {}——全部同一个极端值。"
                                + "先翻上面的判分原始输出确认判分器没坏，再按这个数字下结论",
                        e.getKey(), scores.size(), String.format("%.2f", v));
            }
        }
        long unparsed = countUnparsed(scores);
        if (unparsed > 0) {
            log.warn("  [判分] 有 {} 项判分未解析（已排除在平均之外）", unparsed);
        }
    }

    private static Double avgOf(List<CaseScore> scores, Function<CaseScore, Double> get) {
        List<Double> vals = scores.stream().map(get).filter(v -> v != null && v >= 0).toList();
        if (vals.isEmpty()) {
            return null;
        }
        return vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static String joinedContext(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (Chunk ch : chunks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(ch.content());
        }
        return sb.toString();
    }

    private List<EvalCase> loadCases(String path, int limit) throws Exception {
        List<List<String>> rows = Csv.read(Path.of(path));
        if (rows.isEmpty()) {
            throw new IllegalStateException("测试集是空的：" + path);
        }
        List<String> header = rows.get(0);
        if (!header.equals(EvalCase.HEADER)) {
            throw new IllegalStateException("测试集表头不对。期望 " + EvalCase.HEADER + "，实际 " + header);
        }
        List<EvalCase> cases = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() != EvalCase.HEADER.size()) {
                throw new IllegalStateException("第 " + (i + 1) + " 行字段数是 " + r.size()
                        + "，应为 " + EvalCase.HEADER.size() + "：" + r);
            }
            cases.add(EvalCase.fromRow(r));
        }
        if (limit > 0 && cases.size() > limit) {
            log.info("sdaq.eval.limit={} → 只跑前 {} 条（调试用；正式跑请去掉这个配置）", limit, limit);
            return cases.subList(0, limit);
        }
        return cases;
    }

    /** 从片段 id（形如 {@code 01-销售数据统计口径说明#2}）里取回文档 id。 */
    private static String docIdOf(String chunkId) {
        int hash = chunkId.indexOf('#');
        return hash > 0 ? chunkId.substring(0, hash) : chunkId;
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

    private static String head(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
