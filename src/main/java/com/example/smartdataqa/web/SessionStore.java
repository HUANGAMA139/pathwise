package com.example.smartdataqa.web;

import com.example.smartdataqa.agent.ConversationalAgent;
import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.context.ContextCompactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 迭代 29 · 会话表：{@code sessionId → 一个多轮 Agent}。
 *
 * <p>迭代 28 造出了 {@link ConversationalAgent}，但它**只有 demo 在用**——
 * 对外服务（MCP / REST）还是单轮直调 {@link SmartQaAgent}。
 * 这个类补的就是那一环：让一个 HTTP 客户端能带着 sessionId 连续提问。
 *
 * <h3>三个必须说清楚的取舍</h3>
 * <ol>
 *   <li><b>会话存在内存里，重启即清空。</b>
 *       单机演示够用；要跨重启就得落到 Redis / JDBC
 *       （这正是 examples 里 {@code spring-ai-alibaba-starter-memory-jdbc} / {@code -redis} 在干的事）。
 *       本期不引那个依赖，理由和缓存不用 Caffeine 一样：多一个依赖就多一套要对齐的版本矩阵。</li>
 *   <li><b>会话有上限，最久没用的先回收。</b>
 *       否则「每次请求都带一个新 sessionId」就是一个内存泄漏入口——
 *       每次调用都新建一个持有一整段历史的对象，永远不释放。</li>
 *   <li><b>同一个会话的提问必须串行。</b>
 *       {@code ConversationalAgent} 的历史是可变状态，并发进来会互相踩。
 *       所以 {@link Session#ask} 上了 {@code synchronized}——
 *       <b>代价是同一会话不能并发，这是对的：多轮本来就有顺序语义。</b></li>
 * </ol>
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    /** 一个会话。外部只能通过 {@link #ask} 提问，保证串行。 */
    public static final class Session {

        private final String id;
        private final ConversationalAgent agent;
        private int turns;
        private volatile long lastUsedMillis;

        private Session(String id, ConversationalAgent agent) {
            this.id = id;
            this.agent = agent;
            this.lastUsedMillis = System.currentTimeMillis();
        }

        /** 提问一轮。<b>synchronized</b>：同一会话的两次提问不能并发。 */
        public synchronized ConversationalAgent.Turn ask(String question) {
            ConversationalAgent.Turn turn = agent.ask(++turns, question);
            this.lastUsedMillis = System.currentTimeMillis();
            return turn;
        }

        public String id() {
            return id;
        }

        public synchronized int turns() {
            return turns;
        }

        /** 这个会话一共触发过几次上下文压缩。 */
        public int compactions() {
            return agent.compactions();
        }

        public long lastUsedMillis() {
            return lastUsedMillis;
        }
    }

    private final SmartQaAgent agent;
    private final ChatClient chatClient;
    /** {@code null} = 不做压缩（每轮都带全量历史）。 */
    private final ContextCompactor compactor;
    private final int maxSessions;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionStore(SmartQaAgent agent, ChatClient chatClient,
                        ContextCompactor compactor, int maxSessions) {
        this.agent = agent;
        this.chatClient = chatClient;
        this.compactor = compactor;
        this.maxSessions = Math.max(1, maxSessions);
    }

    /**
     * 取会话；没有就建一个。{@code sessionId} 为空时**自动生成一个**并回给调用方
     * ——客户端第一次不带 id 提问，也能从响应里拿到 id 接着往下聊。
     */
    public Session getOrCreate(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();

        Session existing = sessions.get(id);
        if (existing != null) {
            return existing;
        }
        evictIfNeeded();
        Session created = new Session(id, new ConversationalAgent(agent, chatClient, compactor, true));
        Session raced = sessions.putIfAbsent(id, created);
        // putIfAbsent 而不是 put：两个请求同时带同一个新 id 时，只留先到的那个，
        // 否则后到的会把前一个的历史丢掉（而且不报错）。
        return raced != null ? raced : created;
    }

    /** 主动结束一个会话（客户端想「重新开始」时调）。 */
    public boolean reset(String sessionId) {
        return sessionId != null && sessions.remove(sessionId.trim()) != null;
    }

    public int size() {
        return sessions.size();
    }

    public void clear() {
        sessions.clear();
    }

    private void evictIfNeeded() {
        if (sessions.size() < maxSessions) {
            return;
        }
        while (sessions.size() >= maxSessions) {
            Optional<String> oldest = sessions.values().stream()
                    .min(Comparator.comparingLong(Session::lastUsedMillis))
                    .map(Session::id);
            if (oldest.isEmpty()) {
                return;
            }
            log.info("  [会话] 已达上限 {}，回收最久未用的：{}", maxSessions, oldest.get());
            sessions.remove(oldest.get());
        }
    }
}
