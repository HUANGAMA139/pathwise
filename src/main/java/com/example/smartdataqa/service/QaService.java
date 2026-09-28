package com.example.smartdataqa.service;

import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.trace.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 迭代 29 · 服务层：把「缓存」摆在 Agent <b>外面</b>。
 *
 * <h3>本类唯一重要的设计决定：缓存的摆放位置</h3>
 * 需求是「相同问题不重复调模型」。最省事的做法是把它塞进 {@link SmartQaAgent}——
 * 那样所有调用方（含评测）都自动受益。
 * <b>但那样会毁掉历史基线：</b>迭代 15 / 23 / 28 的评测全部直接调 {@code SmartQaAgent}，
 * 一旦命中缓存，耗时变了、端到端可能也变了，<b>而且不会报任何错</b>——
 * 项目从 迭代 14 起一直在守的「旧基线一键复现」当场失效。
 *
 * <p>所以缓存放在这一层：
 * <pre>
 *   REST /chat ─┐
 *               ├─→ QaService（查缓存 → 未命中才调 Agent → 回填）─→ SmartQaAgent
 *   MCP 工具   ─┘                                                  ↑
 *   迭代 15/23/28 的评测 ───────────────────────────────────────────┘（直接调，永远不带缓存）
 * </pre>
 *
 * <p><b>这就是「同一件事放在哪一层，决定了它会不会变成一个不响的错」。</b>
 * 缓存本身没变，只是换了位置：评测走不到它，线上用得到它。
 *
 * <h3>轨迹怎么办</h3>
 * 未命中时用 Agent 当场产出的轨迹；命中时用<b>首次调用那次的轨迹</b>，
 * 并把它标记成 {@link Trace#asCached()}（{@code render()} 第一行会写明这是首次调用的），
 * 同时把本次真正的耗时（几乎为 0）通过 {@link Served#latencyMillis()} 单独报出来。
 */
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    /**
     * 一次「对外问答」的结果。
     *
     * @param fromCache     这次答案是不是从缓存拿的
     * @param latencyMillis <b>这一次请求</b>的真实耗时（命中缓存时接近 0）
     */
    public record Served(SmartQaAgent.Answer answer, Trace trace,
                         boolean fromCache, long latencyMillis) {
    }

    private final SmartQaAgent agent;
    /** {@code null} = 缓存关闭（{@code sdaq.cache.enabled=false}）。 */
    private final AnswerCache cache;

    public QaService(SmartQaAgent agent, AnswerCache cache) {
        this.agent = agent;
        this.cache = cache;
    }

    public Served ask(String question) {
        long t0 = System.nanoTime();
        String key = question == null ? "" : question;

        if (cache != null) {
            Optional<AnswerCache.Cached> hit = cache.get(key);
            if (hit.isPresent()) {
                AnswerCache.Cached c = hit.get();
                long ms = elapsed(t0);
                log.info("  [缓存] 命中：{}（本次 {} ms，未调用模型）", brief(key), ms);
                return new Served(c.answer(), c.trace().asCached(), true, ms);
            }
        }

        SmartQaAgent.Answer answer = agent.answer(question);
        long ms = elapsed(t0);

        if (cache != null && answer.trace() != null) {
            cache.put(key, answer, answer.trace());
            log.info("  [缓存] 未命中，已回填：{}（本次 {} ms）", brief(key), ms);
        }
        return new Served(answer, answer.trace(), false, ms);
    }

    /** 缓存开关的状态（给 /health 和 demo 用）。 */
    public boolean cacheEnabled() {
        return cache != null;
    }

    public AnswerCache.Stats cacheStats() {
        return cache == null ? null : cache.stats();
    }

    public void clearCache() {
        if (cache != null) {
            cache.clear();
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String brief(String s) {
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }
}
