package com.example.smartdataqa.agent;

import com.example.smartdataqa.rag.pipeline.RagPipeline;
import com.example.smartdataqa.router.QueryRouter;
import com.example.smartdataqa.router.RouteDecision;
import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DatabaseTool;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.trace.Trace;
import com.example.smartdataqa.trace.TraceRecorder;
import com.example.smartdataqa.trace.TraceStage;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * 迭代 8 · Agent 主干。整个项目的骨架。
 *
 * <p>它干的事很简单：<b>问路由 → 按路由走支路 → 拿证据去综合回答</b>。
 * 但这是第一次，一个「问题横跨两个数据源」的系统真的能跑起来。
 *
 * <pre>
 *   用户问题
 *      ↓
 *   QueryRouter ──→ RouteDecision{route, confidence, reason}
 *      ↓
 *      ├─ needsDocument → DocumentTool.search() → List&lt;Chunk&gt;
 *      └─ needsDatabase → DatabaseTool.query()  → QueryResult
 *      ↓
 *   把证据拼进 prompt，让模型综合
 *      ↓
 *   Answer（含完整轨迹）
 * </pre>
 *
 * <h3>两个刻意的设计</h3>
 *
 * <p><b>第一，{@link Answer} 把整条轨迹都带出来了</b>——路由决策、检索到什么、
 * 查到什么、最终答案，全都在一个对象里。
 * 这不是为了好看：迭代 15 建评测时，你要评的就是这几个环节各自对不对；
 * 迭代 25 分析路由失败时，你要看的就是 {@code decision.reason()}。
 * <b>看不见的东西没法评测，也没法调试。</b>
 *
 * <p><b>第二，refuse 不调用模型。</b>既然已经判定「这问题不该我答」，
 * 就没必要再花一次 token 去组织语言。拒绝的价值之一就是省钱。
 *
 * <p><b>第三（最重要），综合回答的 prompt 里写死了防编造规则。</b>
 * 尤其是「数据库结果为空时必须说没查到，不许估计」——
 * 这一条直接对应本项目最危险的风险（迭代 4 见过模型编造「我打过电话」）。
 */
public class SmartQaAgent {

    private static final Logger log = LoggerFactory.getLogger(SmartQaAgent.class);

    /** 文档支路取几段。迭代 20 做 rerank 时会重新调这个值。 */
    private static final int TOP_K = 5;

    /**
     * 综合回答的系统提示词。
     *
     * <p>这段不是随便写的客套话，每一条都对应一个已经踩过的坑：
     * <ul>
     *   <li>第 1 条 → 防「凭常识补充」</li>
     *   <li>第 2 条 → 防「数字不给出处」，用户无法核实</li>
     *   <li>第 3 条 → <b>防「静默的假数字」</b>，本项目最需要防的那条</li>
     *   <li>第 4 条 → 防「口径说得像是常识」，实际出自某份制度</li>
     * </ul>
     */
    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            你是一个数据问答助手。用户提问后，我会给你两类材料：
            - 【文档片段】：来自制度、口径说明、规则等文档
            - 【数据库结果】：来自业务数据，附带实际执行的 SQL

            必须遵守的回答规则：
            1. 只用材料里出现的信息回答。材料里没有的，直接说「材料里没有」，
               不要用常识或推测补充。
            2. 引用数字时必须说明它来自哪条查询的结果，让用户可以核对。
            3. 如果数据库结果是空的，明确告诉用户「没有查到数据」。
               绝对不要估计、推测或编造一个数字。
            4. 涉及口径、定义时，注明出自哪份文档。
            5. 回答简洁，不要客套话。
            """;

    /**
     * 一次问答的完整轨迹。
     *
     * <p>把中间产物全部暴露出来——这是 迭代 4 学到的原则：
     * 「A 的输出喂给 B」的链路里，中间产物是错误放大器，
     * 不打印出来就永远只能看到最终结果坏掉。
     *
     * <p><b>迭代 29 补上了最后一块</b>：从 迭代 8 起这个 record 就叫「完整轨迹」，
     * 但它只带了<b>结果</b>（决策、片段、SQL、答案），没带<b>过程</b>
     * ——哪一步先跑的、各花了多久，只能靠人去拼日志时间戳。
     * 现在多了 {@link Trace}：按步记、带耗时、能整段打出来，也能随 HTTP/MCP 响应回给调用方。
     *
     * @param trace 迭代 29 起新增。<b>是最后一个字段</b>，前面的字段顺序一个没动——
     *              这样所有读 {@code decision()}/{@code chunks()}/… 的老代码（迭代 15/23/28 的评测）都不用改。
     */
    public record Answer(
            String question,
            RouteDecision decision,
            List<Chunk> chunks,
            QueryResult dbResult,
            String text,
            Trace trace) {

        /** 有没有走文档支路。 */
        public boolean usedDocument() {
            return chunks != null && !chunks.isEmpty();
        }

        /** 有没有走数据库支路。 */
        public boolean usedDatabase() {
            return dbResult != null;
        }
    }

    private final QueryRouter router;
    private final DocumentTool documentTool;
    private final DatabaseTool databaseTool;
    private final ChatClient chatClient;

    /**
     * 迭代 26 · 可选的<b>显式五层 RAG pipeline</b>。{@code null} = 走原来的老路径。
     *
     * <p><b>为什么做成可选而不是直接换掉</b>：换掉会动主链路，而 迭代 15/23 的所有基线
     * 都建立在这条链路的输出上。做成可选之后，迭代 26 的消融 demo 可以<b>逐题比对新老两条路的片段 id</b>，
     * 先证明这是纯重构，再由 {@code sdaq.rag.pipeline.enabled} 决定要不要接。
     */
    private final RagPipeline pipeline;

    public SmartQaAgent(QueryRouter router,
                        DocumentTool documentTool,
                        DatabaseTool databaseTool,
                        ChatClient chatClient) {
        this(router, documentTool, databaseTool, chatClient, null);
    }

    public SmartQaAgent(QueryRouter router,
                        DocumentTool documentTool,
                        DatabaseTool databaseTool,
                        ChatClient chatClient,
                        RagPipeline pipeline) {
        this.router = router;
        this.documentTool = documentTool;
        this.databaseTool = databaseTool;
        this.chatClient = chatClient;
        this.pipeline = pipeline;
    }

    /** 拒答时的统一说法。抽出来是因为它有两条出口（阻塞和流式）。 */
    private static final String REFUSE_MESSAGE =
            "这个问题不在我的能力范围内——我只能回答业务数据相关的问题，"
                    + "以及数据口径、统计规则这类制度性问题。";

    /**
     * 「路由 + 取证据」的结果。
     *
     * <p>抽出来是因为<b>阻塞回答和流式回答的前半段完全一样</b>——
     * 只有最后「怎么把材料交给模型」那一步不同。
     * 不抽的话，两边会各写一份路由和取证据的代码，改一处忘一处。
     */
    private record Preparation(RouteDecision decision,
                               List<Chunk> chunks,
                               QueryResult dbResult,
                               String prompt) {

        boolean refused() {
            return decision.route() == RouteDecision.Route.REFUSE;
        }
    }

    /**
     * 回答一个问题（阻塞式：等模型全部生成完再返回）。
     *
     * @param question 用户原始问题
     * @return 完整轨迹 + 最终答案
     */
    public Answer answer(String question) {
        TraceRecorder rec = new TraceRecorder(question);
        Preparation p = prepare(question, rec);

        if (p.refused()) {
            rec.step(TraceStage.REFUSE, "判定为拒答，不调用模型（省一次 token）");
            return new Answer(question, p.decision(), null, null, REFUSE_MESSAGE, rec.finish());
        }

        log.info("  [Agent] 交给模型综合（阻塞式）");
        long t0 = rec.mark();
        String text = chatClient.prompt()
                .system(SYNTHESIS_SYSTEM_PROMPT)
                .user(p.prompt())
                .call()
                .content();
        rec.stepSince(TraceStage.SYNTHESIZE, "答案 " + len(text) + " 字", t0);

        return new Answer(question, p.decision(), p.chunks(), p.dbResult(), text, rec.finish());
    }

    /**
     * 回答一个问题（流式：边生成边把片段交出去）。
     *
     * <p>和 {@link #answer} 走的是<b>同一条链路</b>，区别只在最后一步——
     * 这里用 {@code .stream()} 代替 {@code .call()}，每收到一个片段就回调一次。
     *
     * <p><b>但注意一个现实：流式只覆盖了链路的后半段。</b>
     * 前半段（路由、检索、查库）是阻塞的，而且路由本身就要一次模型调用。
     * 所以真正的「首字延迟」= 路由耗时 + 取证据耗时 + 首字生成耗时，
     * 比 迭代 7 单独测流式时那个 681ms 要长得多。
     * <b>这个数字值得量一下——它决定用户在真实使用中感觉快不快。</b>
     *
     * @param question 用户原始问题
     * @param onChunk  每收到一个片段回调一次。调用方用它来实时显示。
     * @return 和阻塞版同样的完整轨迹，其中 text 是累积后的全文
     */
    public Answer answerStreaming(String question, Consumer<String> onChunk) {
        TraceRecorder rec = new TraceRecorder(question);
        Preparation p = prepare(question, rec);

        if (p.refused()) {
            // 拒答本来就没有模型参与，直接一次给完
            rec.step(TraceStage.REFUSE, "判定为拒答，不调用模型（省一次 token）");
            onChunk.accept(REFUSE_MESSAGE);
            return new Answer(question, p.decision(), null, null, REFUSE_MESSAGE, rec.finish());
        }

        log.info("  [Agent] 交给模型综合（流式）");
        long t0 = rec.mark();
        StringBuilder accumulated = new StringBuilder();
        Flux<String> flux = chatClient.prompt()
                .system(SYNTHESIS_SYSTEM_PROMPT)
                .user(p.prompt())
                .stream()
                .content();

        for (String piece : flux.toIterable()) {
            accumulated.append(piece);
            onChunk.accept(piece);
        }
        rec.stepSince(TraceStage.SYNTHESIZE, "答案 " + accumulated.length() + " 字（流式）", t0);

        return new Answer(question, p.decision(), p.chunks(), p.dbResult(), accumulated.toString(), rec.finish());
    }

    /**
     * 前半段：路由 + 走支路取证据 + 拼材料。两条回答路径共用。
     */
    private Preparation prepare(String question, TraceRecorder rec) {
        log.info("  [Agent] 收到问题：{}", question);

        // ---------- 第 1 步：路由 ----------
        RouteDecision decision = rec.timed(TraceStage.ROUTE,
                () -> router.decide(question),
                d -> d.brief() + " —— 理由：" + ConsoleText.shorten(d.reason(), 60));
        log.info("  [Agent] 路由决策：{} —— 理由：{}", decision.brief(), decision.reason());

        if (decision.route() == RouteDecision.Route.REFUSE) {
            log.info("  [Agent] 判定为拒答，不调用模型（省一次 token）");
            return new Preparation(decision, null, null, null);
        }

        // ---------- 第 2 步：走支路取证据 ----------
        List<Chunk> chunks = null;
        QueryResult dbResult = null;
        String docSection = null;   // 迭代 26：由 pipeline 的注入器层拼材料时用它

        if (decision.needsDocument()) {
            if (this.pipeline != null) {
                RagPipeline.Result r = rec.timed(TraceStage.RETRIEVE,
                        () -> this.pipeline.retrieve(question, TOP_K),
                        x -> "pipeline topK=" + TOP_K + " → " + x.chunks().size() + " 段");
                chunks = r.chunks();
                docSection = r.context();
            }
            else {
                chunks = rec.timed(TraceStage.RETRIEVE,
                        () -> documentTool.search(question, TOP_K),
                        cs -> "topK=" + TOP_K + " → " + cs.size() + " 段" + sources(cs));
            }
        }
        if (decision.needsDatabase()) {
            dbResult = rec.timed(TraceStage.SQL,
                    () -> databaseTool.query(question),
                    r -> r.rowCount() + " 行" + sqlHint(r));
        }

        // ---------- 第 3 步：拼材料 ----------
        long t0 = rec.mark();
        String evidence = buildEvidence(chunks, dbResult, docSection);
        String prompt = "用户问题：" + question + "\n\n可用的材料：\n" + evidence;
        log.info("  [Agent] 材料已拼好（{} 字）", evidence.length());
        rec.stepSince(TraceStage.PREPARE, "材料 " + evidence.length() + " 字（含出处）", t0);

        return new Preparation(decision, chunks, dbResult, prompt);
    }

    /**
     * 把两路证据拼成一段材料。
     *
     * <p>注意每一路都带上了<b>出处标识</b>——文档片段带文件名，数据库结果带 SQL。
     * 这样模型在回答时才有东西可引用，用户也才有东西可核实。
     *
     * <p><b>迭代 26：文档那一半可以交给 pipeline 的注入器层</b>（{@code docSection != null} 时）。
     * 两条路的输出<b>逐字节一致</b>（{@link com.example.smartdataqa.rag.pipeline.DefaultContextAugmenter}
     * 就是照这三行写的），所以切开关不会让 迭代 15/23 的基线漂移。
     */
    private String buildEvidence(List<Chunk> chunks, QueryResult dbResult, String docSection) {
        StringBuilder sb = new StringBuilder();

        if (docSection != null) {
            sb.append(docSection);
        }
        else if (chunks != null && !chunks.isEmpty()) {
            sb.append("========== 【文档片段】 ==========\n");
            for (Chunk c : chunks) {
                sb.append(c.render()).append('\n');
            }
        }
        else if (chunks != null) {
            sb.append("========== 【文档片段】 ==========\n（没有检索到相关文档）\n");
        }

        if (dbResult != null) {
            sb.append("\n========== 【数据库结果】 ==========\n");
            sb.append(dbResult.render());
        }

        return sb.toString();
    }

    // ==================================================================
    // 迭代 29：轨迹里那几行摘要怎么拼
    //
    // 原则是「一眼能看出是哪一步先偏的」——所以写的是**证据摘要**，
    // 不是「执行成功」这种放在哪一步都成立的话。
    // ==================================================================

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** 这次召回命中了几份文档（去重、取 id 里 '#' 之前那段）。 */
    private static String sources(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Chunk c : chunks) {
            String id = c.id() == null ? "" : c.id();
            int cut = id.indexOf('#');
            set.add(ConsoleText.shorten(cut > 0 ? id.substring(0, cut) : id, 24));
        }
        return "（来源：" + String.join(",", set) + "）";
    }

    /** SQL 压成一行再截断——轨迹里要的是「能认出是哪条」，不是全文。 */
    private static String sqlHint(QueryResult r) {
        if (r == null || r.sql() == null || r.sql().isBlank()) {
            return "";
        }
        return "  SQL: " + ConsoleText.shorten(r.sql().replaceAll("\\s+", " ").trim(), 70);
    }
}
