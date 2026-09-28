package com.example.smartdataqa.eval;

import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.rag.BasicRagPipeline;
import com.example.smartdataqa.tools.Chunk;
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

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 迭代 15 · 评测脚手架。跑 <b>28 条用例 × 2 条链路</b>，把指标追加进 {@code eval/metrics.csv}。
 *
 * <h3>两条链路为什么要都跑</h3>
 * <ul>
 *   <li>{@code agent}：现有产品路径（四值路由 + 手写拼装 + 防编造提示词）</li>
 *   <li>{@code basic-rag}：{@link BasicRagPipeline}，教科书版「检索→注入→生成」</li>
 * </ul>
 * 只跑一条的话，数字出来后你没法判断「这个分数算好还是差」。两条一起跑，第一个数字才有参照物。
 *
 * <h3>哪些指标不用花模型调用</h3>
 * <pre>
 *   route_accuracy      实际路由 vs 用例的 expected_route
 *   ctx_recall_doc      期望出处文档有没有被召回（文档级）
 *   ctx_precision_doc   召回片段里有多少来自期望出处（文档级）
 * </pre>
 * 其余五项要用模型判分：{@code answer_correct}、{@code ctx_recall_llm}、{@code ctx_precision_llm}
 * 是自己写的判分器；{@code faithfulness}、{@code answer_relevancy} 用框架自带的两个 Evaluator。
 *
 * <h3>四个诚实性设计</h3>
 * <ol>
 *   <li><b>判分没解析出来记 {@link LlmJudge#UNPARSED}，不计入平均</b>，并在 note 里报数。
 *       把它当 0 会让「判分器坏了」伪装成「答案很差」。</li>
 *   <li><b>某项指标整体为 0.00 会报警。</b>28 条用例全 0 几乎不可能——真出现，八成是判分解析坏了。
 *       框架自带的 {@code RelevancyEvaluator} 尤其可疑：它用 {@code "yes".equalsIgnoreCase(整个响应)}
 *       判分，且<b>丢掉原始输出</b>（我们看不到它到底说了什么）。</li>
 *   <li><b>basic-rag 只在 {@code expected_route=document} 的用例上跑。</b>
 *       它没有路由能力，硬跑 database/both 用例是在评「它做不到的事」。</li>
 *   <li><b>两条链路取到的上下文统一成「片段正文拼接」</b>，不带出处标注。
 *       agent 实际看到的材料里是有「【片段 id｜出处】」的，这里刻意剥掉——
 *       否则两条链路的 context 指标不可比。</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp15.enabled", havingValue = "true")
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    /** metrics.csv 的列。一次实验写 3 行（agent/all、agent/document、basic-rag/document）。 */
    private static final List<String> METRICS_HEADER = List.of(
            "experiment", "ts", "pipeline", "scope", "cases", "judged", "route_accuracy",
            "answer_correct", "faithfulness", "answer_relevancy",
            "ctx_recall_doc", "ctx_precision_doc", "ctx_recall_llm", "ctx_precision_llm",
            "latency_ms_avg", "note");

    /** 用模型判分的那几列在 {@link #METRICS_HEADER} 里的下标——写死成常量，避免"7 + i"那种错位。 */
    private static final int COL_ANSWER_CORRECT = 7;
    private static final int COL_FAITHFULNESS = 8;
    private static final int COL_ANSWER_RELEVANCY = 9;
    private static final int COL_CTX_RECALL_LLM = 12;
    private static final int COL_CTX_PRECISION_LLM = 13;

    private static final Map<Integer, String> JUDGED_COLUMNS = Map.of(
            COL_ANSWER_CORRECT, "answer_correct",
            COL_FAITHFULNESS, "faithfulness",
            COL_ANSWER_RELEVANCY, "answer_relevancy",
            COL_CTX_RECALL_LLM, "ctx_recall_llm",
            COL_CTX_PRECISION_LLM, "ctx_precision_llm");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用例数少于这个值时不做「极端值」报警——样本太小，「全通过」本来就是正常的。 */
    private static final int MIN_CASES_TO_WARN = 10;

    // 迭代 23：这个「把框架判分器原始输出打出来」的 advisor 抽成了独立的 JudgeLoggingAdvisor
    // （同包），迭代 23 的报告也用它，免得两个判分场景各留一份日志逻辑。

    /** 一个用例在一条链路上的完整结果。{@code Double} 字段为 {@code null} 表示「这题不适用该指标」。 */
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
                             String answer) {
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)   // 必须等入库跑完（见 AgentConfig.ingestCorpusRunner）
    ApplicationRunner exp15Eval(
            SmartQaAgent agent,
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.metrics:eval/metrics.csv}") String metricsPath,
            @Value("${sdaq.eval.limit:0}") int limit,
            @Value("${sdaq.eval.experiment:exp15-baseline}") String experiment) {

        return args -> run(agent, vectorStore, chatClientBuilder, testsetPath, metricsPath, limit, experiment);
    }

    private void run(SmartQaAgent agent, VectorStore vectorStore, ChatClient.Builder builder,
                     String testsetPath, String metricsPath, int limit, String experiment) throws Exception {

        List<EvalCase> cases = loadCases(testsetPath, limit);

        // 调试跑（limit>0）自动改名，免得把 n=3 的脏数据混进正式实验的历史里
        String label = limit > 0 ? experiment + "-limit" + limit : experiment;

        log.info("========== 迭代 15 · 评测 ==========");
        log.info("实验名：{}｜用例：{} 条｜链路：agent + basic-rag", label, cases.size());

        // 先建「干净」的客户端（业务链路 + 自写判分用它），再克隆一个挂日志 advisor 的给框架判分器用。
        // 顺序很重要：万一 clone() 不是真隔离，已经建好的 plain 也不受影响。
        ChatClient plain = builder.build();
        ChatClient.Builder judgeBuilder = builder.clone().defaultAdvisors(new JudgeLoggingAdvisor());

        BasicRagPipeline basicRag = new BasicRagPipeline(plain, vectorStore);
        LlmJudge judge = new LlmJudge(plain);
        RelevancyEvaluator relevancy = new RelevancyEvaluator(judgeBuilder);
        // 注意：FactCheckingEvaluator 的构造器是 protected，只能走公开的 builder
        FactCheckingEvaluator factCheck = FactCheckingEvaluator.builder(judgeBuilder).build();

        // ---------- 链路 1：agent（跑全部用例，因为要算路由准确率） ----------
        List<CaseScore> agentScores = new ArrayList<>();
        for (EvalCase c : cases) {
            agentScores.add(runAgent(agent, c, judge, relevancy, factCheck));
        }

        // ---------- 链路 2：basic-rag（只跑 document 用例） ----------
        List<CaseScore> ragScores = new ArrayList<>();
        for (EvalCase c : cases) {
            if ("document".equals(c.expectedRoute())) {
                ragScores.add(runBasicRag(basicRag, c, judge, relevancy, factCheck));
            }
        }

        List<CaseScore> agentDocOnly = agentScores.stream()
                .filter(s -> "document".equals(s.expectedRoute()))
                .toList();

        printDetail(agentScores, ragScores);
        printByCategory(agentDocOnly);

        String ts = LocalDateTime.now().format(TS);
        List<List<String>> rows = List.of(
                metricsRow(label, ts, "agent", "all", agentScores, true,
                        "路由准确率在此行；其余指标只在适用的用例上算"),
                metricsRow(label, ts, "agent", "document", agentDocOnly, false,
                        "与 basic-rag 可比的那一档（同一批 document 用例）"),
                metricsRow(label, ts, "basic-rag", "document", ragScores, false,
                        "教科书版 QuestionAnswerAdvisor；无路由能力，且会丢掉片段出处"));

        Path out = Path.of(metricsPath);
        for (List<String> row : rows) {
            Csv.appendRow(out, METRICS_HEADER, row);
        }
        log.info("");
        log.info("指标已追加到 {}（3 行）", out.toAbsolutePath());
        warnIfSuspicious(rows);
        log.info("========== 评测结束 ==========");
    }

    // ==================== 两条链路的执行 ====================

    private CaseScore runAgent(SmartQaAgent agent, EvalCase c,
                               LlmJudge judge, RelevancyEvaluator relevancy, FactCheckingEvaluator factCheck) {
        log.info("");
        log.info("---- [agent] {}｜{}", c.id(), c.question());
        long t0 = System.currentTimeMillis();
        SmartQaAgent.Answer a = agent.answer(c.question());
        long ms = System.currentTimeMillis() - t0;

        String actual = a.decision().route() == null ? "" : a.decision().route().name();
        boolean routeOk = a.decision().route() == c.route();
        log.info("     路由 期望={} 实际={} {}", c.expectedRoute(), actual, routeOk ? "[OK]" : "[X]");

        List<Chunk> chunks = a.chunks() == null ? List.of() : a.chunks();
        // 数据库结果也要进上下文——否则 both 用例的忠实性/覆盖会被算成「材料不支持」
        String dbCtx = a.dbResult() == null ? "" : a.dbResult().render();
        CaseScore s = score(c, actual, routeOk, ms, a.text(), chunks, dbCtx, judge, relevancy, factCheck);
        logNotableCase(s);
        return s;
    }

    private CaseScore runBasicRag(BasicRagPipeline rag, EvalCase c,
                                  LlmJudge judge, RelevancyEvaluator relevancy, FactCheckingEvaluator factCheck) {
        log.info("");
        log.info("---- [basic-rag] {}｜{}", c.id(), c.question());
        long t0 = System.currentTimeMillis();
        BasicRagPipeline.Answer a = rag.answer(c.question());
        long ms = System.currentTimeMillis() - t0;
        // basic-rag 不做路由，所以 actualRoute 用 "-"；它也没有数据库支路，dbContext 传空
        CaseScore s = score(c, "-", false, ms, a.text(), a.chunks(), "", judge, relevancy, factCheck);
        logNotableCase(s);
        return s;
    }

    /**
     * 两条链路共用的判分流程。
     *
     * @param dbContext 数据库结果的文本形式（没有就传空串）。
     *                  <b>它必须并进「上下文」</b>——否则 {@code both} 用例会出假指标：
     *                  答案引用了库里的数字，而上下文里只有文档片段，
     *                  严格的事实核查就会判「这个数字不被材料支持」。
     *                  迭代 15 全量跑就这么错过一次（X01/X02/X03 忠实性全是 0.00）。
     */
    private CaseScore score(EvalCase c, String actualRoute, boolean routeOk, long ms,
                            String answer, List<Chunk> chunks, String dbContext,
                            LlmJudge judge, RelevancyEvaluator relevancy, FactCheckingEvaluator factCheck) {

        String ctx = joinedContext(chunks);
        if (dbContext != null && !dbContext.isBlank()) {
            ctx = ctx.isBlank() ? dbContext : ctx + "\n\n" + dbContext;
        }
        String safeAnswer = answer == null ? "" : answer;

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

        // ---- 要用模型的五项 ----
        Double answerCorrect = null;
        Double faithfulness = null;
        Double answerRelevancy = null;
        Double ctxRecallLlm = null;
        Double ctxPrecisionLlm = null;

        boolean refusedBoth = "REFUSE".equals(c.route().name()) && "REFUSE".equals(actualRoute);
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

        return new CaseScore(c.id(), c.expectedRoute(), c.category(), actualRoute, routeOk, ms,
                answerCorrect, faithfulness, answerRelevancy, recallDoc, precisionDoc,
                ctxRecallLlm, ctxPrecisionLlm, safeAnswer);
    }

    /**
     * 值得记一笔的用例：把答案原文打出来。
     *
     * <p><b>为什么非要打原文：</b>光看分数是没法诊断的。
     * <ul>
     *   <li>迭代 15 出现过「自写判分 1.00、框架忠实性 0.00」——**答案原文**才让我看出
     *       是我的参数传错（上下文没进 dataList），而不是框架判得严。没有那行日志，
     *       我会把一个错误结论写进文档。</li>
     *   <li>后来又出现「basic-rag 答案判定 0.50、agent 1.00」——如果连答案长什么样都不知道，
     *       「0.67」就永远只是个数字，解释不了、也就改进不了。</li>
     * </ul>
     *
     * <p>只在两种情况打，所以不会变成噪声：<b>两个判分器打架</b>，或<b>答案没拿满分</b>。
     */
    private void logNotableCase(CaseScore s) {
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

    private static String head(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
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

    /**
     * 框架自带的忠实性判分（{@code FactCheckingEvaluator}）。
     *
     * <p>按官方用法：第一个参数放<b>上下文</b>，第三个参数放<b>待验证的断言</b>（即答案）。
     * 注意它内部同样是「拿模型回答跟 yes 做精确比较」——中文模型上有风险，见类注释第 2 条。
     */
    private Double frameworkFaithfulness(FactCheckingEvaluator evaluator, String context, String answer) {
        if (context == null || context.isBlank()) {
            // 空上下文下这个判分没有任何意义——模型只能瞎猜。
            // 迭代 15 踩过：把上下文传进了 userText、dataList 留空，于是 {document} 是空的，
            // 同一份空材料下时而 yes 时而 no，看起来像「模型判得严」，其实是在乱答。
            // 宁可不给分（null = 不适用），也不给一个假分。
            log.warn("      [判分] 忠实性跳过：上下文为空");
            return null;
        }
        try {
            // 注意：上下文必须放进 dataList（List<Document>），不能放进 userText。
            // FactCheckingEvaluator 的 {document} 来自 Evaluator.doGetSupportingData(request)，
            // 而那个方法只读 getDataList()——传 userText 等于给它一份空材料。
            return evaluator.evaluate(new EvaluationRequest(List.of(new Document(context)), answer)).isPass()
                    ? 1.0 : 0.0;
        }
        catch (RuntimeException e) {
            log.warn("      [判分] 框架忠实性判分异常：{}", e.toString());
            return LlmJudge.UNPARSED;
        }
    }

    /**
     * 框架自带的相关性判分（{@code RelevancyEvaluator}）：答案是否与「问题 + 上下文」一致。
     *
     * <p>{@code EvaluationRequest} 的 dataList 要的是 {@code List<Document>}——
     * 官方 testing 文档写的是 {@code List<Content>}，<b>和源码不符</b>（实测编译不过）。
     * 我们的 {@link Chunk} 是项目自己的契约，在这里翻译一次；
     * 用单参构造 {@code new Document(text)} 就够——评分只看正文，不看元数据。
     */
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

    // ==================== 汇总与输出 ====================

    private List<String> metricsRow(String experiment, String ts, String pipeline, String scope,
                                    List<CaseScore> scores, boolean withRoute, String note) {
        long judged = scores.stream().filter(s -> s.answerCorrect() != null).count();
        String routeAcc = withRoute ? rate(scores) : "-";

        List<String> row = new ArrayList<>(List.of(
                experiment, ts, pipeline, scope,
                String.valueOf(scores.size()), String.valueOf(judged), routeAcc,
                avg(scores, CaseScore::answerCorrect),
                avg(scores, CaseScore::faithfulness),
                avg(scores, CaseScore::answerRelevancy),
                avg(scores, CaseScore::ctxRecallDoc),
                avg(scores, CaseScore::ctxPrecisionDoc),
                avg(scores, CaseScore::ctxRecallLlm),
                avg(scores, CaseScore::ctxPrecisionLlm),
                avgLatency(scores)));

        long unparsed = countUnparsed(scores);
        row.add(note + (unparsed > 0 ? "；判分未解析 " + unparsed + " 项（已排除在平均之外）" : ""));
        return row;
    }

    /** 求平均，<b>排除 {@code null}（不适用）和 {@link LlmJudge#UNPARSED}（未解析）</b>。 */
    private static String avg(List<CaseScore> scores, Function<CaseScore, Double> get) {
        List<Double> vals = scores.stream().map(get).filter(v -> v != null && v >= 0).toList();
        if (vals.isEmpty()) {
            return "-";
        }
        return String.format("%.2f", vals.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static String rate(List<CaseScore> scores) {
        if (scores.isEmpty()) {
            return "-";
        }
        long ok = scores.stream().filter(CaseScore::routeOk).count();
        return String.format("%.2f", (double) ok / scores.size());
    }

    private static String avgLatency(List<CaseScore> scores) {
        if (scores.isEmpty()) {
            return "-";
        }
        return String.format("%.0f", scores.stream().mapToDouble(CaseScore::latencyMs).average().orElse(0));
    }

    /**
     * 数一下有多少项判分没解析出来。
     *
     * <p>注意这里<b>不能用 {@code List.of(...)}</b>——它不接受 null 元素，而这些字段本来就可能是 null
     * （「这题不适用」），会直接抛 NPE。用 {@code Stream.of} 才是安全的。
     */
    private static long countUnparsed(List<CaseScore> scores) {
        return scores.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.answerCorrect(), s.faithfulness(),
                        s.answerRelevancy(), s.ctxRecallLlm(), s.ctxPrecisionLlm()))
                .filter(v -> v != null && v == LlmJudge.UNPARSED)
                .count();
    }

    /**
     * 分数可疑时报警——写在文件里的数字不会自己喊救命。
     *
     * <p>挑的是最容易「判分坏了但看起来正常」的情况：<b>某项指标在所有用例上都是同一个极端值</b>
     * （全 0.00 或全 1.00）。全 1.00 同样要查——「一个都不通过」和「一个都不失败」
     * 在判分器不透明的时候一样可疑。
     *
     * <p><b>两条自律，都是被实测打出来的：</b>
     * <ul>
     *   <li><b>用例太少不报。</b>n=3 的时候「全通过」完全正常，报了纯是噪声
     *       （迭代 15 第一次跑 limit=3，这个报警刷了 10 条）。所以少于 {@value #MIN_CASES_TO_WARN} 条直接不查。</li>
     *   <li><b>同一个指标 + 同一个值只报一次。</b>{@code agent/all} 和 {@code agent/document}
     *       两行是同一批分数换了个 scope 再聚合一遍，值必然相同——不按值去重就会报两遍。</li>
     * </ul>
     */
    private void warnIfSuspicious(List<List<String>> rows) {
        Set<String> seen = new HashSet<>();
        for (List<String> row : rows) {
            int n = Integer.parseInt(row.get(4));
            if (n < MIN_CASES_TO_WARN) {
                continue;
            }
            for (Map.Entry<Integer, String> e : JUDGED_COLUMNS.entrySet()) {
                String v = row.get(e.getKey());
                if (("0.00".equals(v) || "1.00".equals(v)) && seen.add(e.getValue() + "=" + v)) {
                    log.warn("  [可疑] 指标 {} 在 {} 条用例上都是 {}——全部用例同一个极端值。"
                                    + "先翻上面的「[框架判分]/[判分] 原始输出」确认判分器没坏，再按这个数字下结论",
                            e.getValue(), n, v);
                }
            }
        }
    }

    /** 逐条打印，方便和 metrics.csv 对账。 */
    private void printDetail(List<CaseScore> agentScores, List<CaseScore> ragScores) {
        log.info("");
        log.info("========== 逐条明细（agent） ==========");
        log.info("{} {} {} {} {} {} {} {}",
                ConsoleText.padRight("用例", 6), ConsoleText.padRight("期望路由", 10),
                ConsoleText.padRight("实际路由", 10), ConsoleText.padRight("答案", 6),
                ConsoleText.padRight("相关", 6), ConsoleText.padRight("忠实", 6),
                ConsoleText.padRight("覆盖LLM", 9), "检索精确LLM");
        for (CaseScore s : agentScores) {
            log.info("{} {} {} {} {} {} {} {}",
                    ConsoleText.padRight(s.id(), 6), ConsoleText.padRight(s.expectedRoute(), 10),
                    ConsoleText.padRight(s.actualRoute(), 10), ConsoleText.padRight(fmt(s.answerCorrect()), 6),
                    ConsoleText.padRight(fmt(s.answerRelevancy()), 6),
                    ConsoleText.padRight(fmt(s.faithfulness()), 6),
                    ConsoleText.padRight(fmt(s.ctxRecallLlm()), 9), fmt(s.ctxPrecisionLlm()));
        }
        log.info("");
        log.info("========== 逐条明细（basic-rag，只跑 document 用例） ==========");
        log.info("{} {} {} {} {} {}",
                ConsoleText.padRight("用例", 6), ConsoleText.padRight("答案", 6),
                ConsoleText.padRight("相关", 6), ConsoleText.padRight("忠实", 6),
                ConsoleText.padRight("覆盖LLM", 9), "检索精确LLM");
        for (CaseScore s : ragScores) {
            log.info("{} {} {} {} {} {}",
                    ConsoleText.padRight(s.id(), 6), ConsoleText.padRight(fmt(s.answerCorrect()), 6),
                    ConsoleText.padRight(fmt(s.answerRelevancy()), 6),
                    ConsoleText.padRight(fmt(s.faithfulness()), 6),
                    ConsoleText.padRight(fmt(s.ctxRecallLlm()), 9), fmt(s.ctxPrecisionLlm()));
        }
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

    /**
     * 按 category 分组看指标——「评测要分层」的落地：一个总分看不出问题在哪。
     *
     * <p><b>最该盯的是「语义」那一族。</b>那几条题与语料的字面重合度低于 0.3
     * （见 {@code eval/testset-check.md}），是唯一能真正区分「语义检索」和「字面匹配」的用例。
     * 迭代 16 做混合检索时，改进就该主要体现在这一族上——
     * 如果总分涨了这一族没动，那涨的不是检索能力。
     */
    private void printByCategory(List<CaseScore> scores) {
        Map<String, List<CaseScore>> byCat = new LinkedHashMap<>();
        for (CaseScore s : scores) {
            byCat.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
        }
        log.info("");
        log.info("========== 按 category 分组（agent，document 用例） ==========");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("分类", 16), ConsoleText.padRight("条数", 6),
                ConsoleText.padRight("答案", 8), ConsoleText.padRight("忠实", 8), "检索精确LLM");
        for (Map.Entry<String, List<CaseScore>> e : byCat.entrySet()) {
            List<CaseScore> v = e.getValue();
            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(e.getKey(), 16), ConsoleText.padRight(String.valueOf(v.size()), 6),
                    ConsoleText.padRight(avg(v, CaseScore::answerCorrect), 8),
                    ConsoleText.padRight(avg(v, CaseScore::faithfulness), 8),
                    avg(v, CaseScore::ctxPrecisionLlm));
        }
        log.info("");
        log.info("注意：条数少的分类不能当结论（3 条里错 1 条就是 0.33 的差）。");
    }

    // ==================== 工具 ====================

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

    /**
     * 从片段 id（形如 {@code 01-销售数据统计口径说明#2}）里取回「文档 id」。
     *
     * <p>返回的是文档 id 本身（{@code 01-销售数据统计口径说明}），因为测试集的
     * {@code expected_source} 就是用这个形式写的。表名（{@code sales_orders}）
     * 永远不会出现在片段 id 里，所以文档级指标天然只统计文档那一部分——不需要对表名特判。
     */
    private static String docIdOf(String chunkId) {
        int hash = chunkId.indexOf('#');
        return hash > 0 ? chunkId.substring(0, hash) : chunkId;
    }
}
