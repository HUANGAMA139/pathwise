package com.example.smartdataqa.web;

import com.example.smartdataqa.agent.ConversationalAgent;
import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.service.AnswerCache;
import com.example.smartdataqa.service.QaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 迭代 29 · 对外的 HTTP 入口。到这一天，项目才第一次有了「能一键起、能直接用」的服务面。
 *
 * <pre>
 *   POST /chat              单轮问答（走服务层 → 带缓存）
 *   POST /chat {sessionId}  多轮问答（走会话表 → 带指代消解和上下文压缩）
 *   DELETE /chat/{id}       结束一个会话
 *   GET  /health            健康检查 + 缓存命中率 + 会话数
 *   GET  /                  用法说明
 * </pre>
 *
 * <h3>为什么单轮和多轮分成两条路</h3>
 * <ul>
 *   <li><b>不带 sessionId</b> = 一次独立问答，含义只由这句话决定
 *       → 可以安全地走 {@link QaService}，<b>命中缓存</b>。</li>
 *   <li><b>带 sessionId</b> = 这句话要靠历史才能读懂（「它的分母…」）
 *       → 必须走 {@link SessionStore}，<b>不进缓存</b>。</li>
 * </ul>
 * <p><b>「多轮为什么不缓存」值得写清楚</b>：改写后的自足问题确实是会话无关的，
 * 按它做 key 是可行的；但那样省下的只是一次综合调用，而<b>改写本身仍然要调模型</b>，
 * 收益小、语义却更容易错（同一句话在不同会话里指代不同，一旦改写有偏差就会张冠李戴）。
 * 所以本期<b>只对「一次问答」做缓存</b>，这是有意的范围选择，不是漏了。
 *
 * <h3>轨迹随响应回带</h3>
 * 每个响应都带 {@code trace}——外部调用方拿到的不是一个孤立答案，
 * 而是「路由走了哪条、检索到什么、SQL 是什么、哪一步花了多久」。
 * 迭代 8 定 {@code QueryResult.sql} 时的那条原则（<b>让「有没有真查过」可验证</b>），
 * 到这一天从「答案里附 SQL」扩成了「整条链路可核对」。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /**
     * JSON 响应**显式带上 {@code charset=UTF-8}**。
     *
     * <p>按 RFC 8259，JSON 本来就该是 UTF-8，{@code application/json} 也不定义 charset 参数——
     * <b>但 PowerShell 5.1 的 {@code Invoke-RestMethod} 不认这条</b>：响应头里没有 charset 时，
     * 它按 ISO-8859-1 解码，于是每个汉字都被拆成几个西欧重音字母，<b>而且不报任何错</b>——
     * 数据是对的，只是客户端看见的是坏的。
     *
     * <p><b>这是 迭代 29 实跑时真撞到的</b>：同一个响应换一种读法就是好的
     * （响应体本身是合法 UTF-8），所以问题只出在「响应头没告诉客户端用什么编码」。
     *
     * <p>取舍：多一个非标准参数，换掉一整类「不响的错」。
     * <b>能跑但看起来是坏的，比直接报错更耗人。</b>
     *
     * <p>（这段注释里刻意不写乱码长什么样——因为「提到某个字符」和「把字符写进文件」
     * 在源码里是同一件事：本轮我已经因为这个被静态检查抓过两次了。）
     */
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

    /** 请求体。两个字段都可空：只给 question = 单轮；再给 sessionId = 接着上一轮聊。 */
    public record ChatRequest(String sessionId, String question) {
    }

    /**
     * 响应体。
     *
     * @param standalone   多轮才有：改写后那句「单独拿出来也能看懂」的问题。
     *                     它同时是排查指代消解有没有生效的唯一抓手。
     * @param sql          走了数据库支路时，实际执行的 SQL（空串 = 没查库）
     * @param trace        整条链路的轨迹文本（见 {@code Trace#render()}）
     * @param fromCache    这条答案是不是缓存里拿的
     * @param latencyMs    <b>这一次请求</b>的耗时（命中缓存时接近 0）
     * @param turns        多轮才有：这是第几轮
     * @param compactions  多轮才有：这个会话一共触发过几次压缩
     * @param historyChars 多轮才有：本轮结束时历史占多少字符（压缩后的）
     */
    public record ChatResponse(
            String sessionId,
            boolean multiTurn,
            String standalone,
            String route,
            String answer,
            String sql,
            String trace,
            boolean fromCache,
            long latencyMs,
            Integer turns,
            Integer compactions,
            Integer historyChars) {
    }

    private final QaService qa;
    private final SessionStore sessions;

    public ChatController(QaService qa, SessionStore sessions) {
        this.qa = qa;
        this.sessions = sessions;
    }

    @PostMapping(path = "/chat", produces = JSON_UTF8)
    public ChatResponse chat(@RequestBody(required = false) ChatRequest req) {
        String question = req == null ? null : req.question();
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        String sessionId = req.sessionId();

        // ---------- 单轮：一次独立问答，走服务层（有缓存） ----------
        if (sessionId == null || sessionId.isBlank()) {
            log.info("[REST] 单轮：{}", question);
            QaService.Served served = qa.ask(question);
            SmartQaAgent.Answer a = served.answer();
            return new ChatResponse(
                    null, false, question, routeOf(a), a.text(),
                    sqlOf(a), traceOf(a),
                    served.fromCache(), served.latencyMillis(),
                    null, null, null);
        }

        // ---------- 多轮：靠历史消解指代，走会话表（不缓存） ----------
        log.info("[REST] 多轮 session={}：{}", sessionId, question);
        long t0 = System.nanoTime();
        SessionStore.Session session = sessions.getOrCreate(sessionId);
        ConversationalAgent.Turn turn = session.ask(question);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        SmartQaAgent.Answer a = turn.result();
        return new ChatResponse(
                session.id(), true, turn.standalone(), routeOf(a), turn.answer(),
                sqlOf(a), traceOf(a),
                false, ms,
                session.turns(), session.compactions(), turn.contextChars());
    }

    /** 结束一个会话（客户端想「重新开始」）。 */
    @DeleteMapping(path = "/chat/{sessionId}", produces = JSON_UTF8)
    public Map<String, Object> reset(@PathVariable String sessionId) {
        boolean removed = sessions.reset(sessionId);
        log.info("[REST] 结束会话 session={}（{}）", sessionId, removed ? "存在" : "本来就没有");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("reset", removed);
        out.put("sessions", sessions.size());
        return out;
    }

    /**
     * 健康检查。Docker 的 healthcheck 也打这个口——
     * <b>所以它绝不能只是「进程还在」：要能看出缓存有没有在工作、会话堆了多少。</b>
     */
    @GetMapping(path = "/health", produces = JSON_UTF8)
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("sessions", sessions.size());
        out.put("cacheEnabled", qa.cacheEnabled());
        AnswerCache.Stats st = qa.cacheStats();
        if (st != null) {
            out.put("cacheHits", st.hits());
            out.put("cacheMisses", st.misses());
            out.put("cacheHitRate", String.format("%.2f", st.hitRate()));
            out.put("cacheSize", st.size());
            out.put("cacheMaxEntries", st.maxEntries());
            out.put("cacheTtlSeconds", st.ttlSeconds());
        }
        return out;
    }

    /** 用法说明。故意用 text/plain + 显式 UTF-8 —— 不写 charset 的话中文会乱码。 */
    @GetMapping(path = "/", produces = "text/plain;charset=UTF-8")
    public String index() {
        return """
               智能数据问答服务 · 已启动

               接口：
                 POST /chat                       单轮问答（有缓存）
                   {"question":"销售额是怎么算的？"}
                 POST /chat                       多轮问答（带 sessionId）
                   {"sessionId":"abc","question":"它的分母为什么必须用已完成订单数？"}
                 DELETE /chat/{sessionId}         结束一个会话
                 GET  /health                     健康检查 + 缓存命中率
                 POST /mcp                        MCP 协议入口（给 Claude Desktop 等客户端）

               每个响应都回带 trace（路由 / 检索 / SQL / 各步耗时）。
               """;
    }

    // ------------------------------------------------------------------

    private static String routeOf(SmartQaAgent.Answer a) {
        return a == null || a.decision() == null ? "" : a.decision().brief();
    }

    private static String sqlOf(SmartQaAgent.Answer a) {
        if (a == null || a.dbResult() == null || a.dbResult().sql() == null) {
            return "";
        }
        return a.dbResult().sql();
    }

    private static String traceOf(SmartQaAgent.Answer a) {
        return a == null || a.trace() == null ? "" : a.trace().render();
    }
}
