package com.example.smartdataqa.service;

import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.trace.Trace;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 迭代 29 · 应用层答案缓存。同一个问题第二次来，不再调模型。
 *
 * <h3>为什么手写、不引 Caffeine</h3>
 * 需求只有三条：<b>按 TTL 过期、有容量上限、能报命中率</b>。
 * 引一个缓存库就要多对一套版本矩阵（本项目锁 Spring Boot 3.5.7 / Spring AI 1.1.0，
 * 每加一个依赖都要重新确认兼容性），而这里 60 行就够了。
 * ——同 迭代 16 手写 BM25、迭代 17 手写 MMR、迭代 21 手写 SqlGuard 是同一个判断。
 *
 * <h3>三条必须写清楚的边界</h3>
 * <ol>
 *   <li><b>它只该被服务层用，绝不能被评测路径用到。</b>
 *       缓存的 {@code Answer} 里带着 SQL 和片段——一旦 迭代 15/23/28 的评测命中缓存，
 *       耗时和结果都会变，<b>而且不报错</b>，历史基线当场作废。
 *       所以它被放在 {@link QaService} 里，而不是塞进 {@code SmartQaAgent}。</li>
 *   <li><b>它是内存缓存，重启即失效。</b>
 *       改了语料（重新分块入库）或改了业务库表结构之后，旧答案就过期了——
 *       <b>这一步没有自动检测，靠重启。</b>（想要更细的失效，得给 key 加一个语料/表结构的版本号。）</li>
 *   <li><b>它缓存的是「最终答案」，包括拒答。</b>
 *       拒答也要一次路由调用，缓存它同样省钱。</li>
 * </ol>
 *
 * <p>线程安全：靠 {@code ConcurrentHashMap} 的原子操作，不额外加锁。
 * <b>不做「同一个问题并发只算一次」</b>（single-flight）——那要在 bin 锁里跑几秒的模型调用，
 * 会连累同一桶里的其他 key。宁可并发时多算一次。
 */
public class AnswerCache {

    /** 一条缓存：答案 + 它那次的轨迹 + 存入时刻。 */
    private record Entry(SmartQaAgent.Answer answer, Trace trace, long storedAtMillis) {
    }

    /** 命中情况。命中率是「缓存到底有没有用」的唯一证据，所以要能报出来。 */
    public record Stats(long hits, long misses, int size, int maxEntries, long ttlSeconds) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }

    /** 命中时返回的东西：答案 + 首次调用那次的轨迹 + 存入时刻。 */
    public record Cached(SmartQaAgent.Answer answer, Trace trace, long storedAtMillis) {
    }

    private final int maxEntries;
    private final long ttlMillis;
    private final Map<String, Entry> map = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public AnswerCache(int maxEntries, long ttlSeconds) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
    }

    public Optional<Cached> get(String question) {
        String key = key(question);
        Entry e = map.get(key);
        if (e == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (expired(e)) {
            map.remove(key);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(new Cached(e.answer(), e.trace(), e.storedAtMillis()));
    }

    public void put(String question, SmartQaAgent.Answer answer, Trace trace) {
        evictIfNeeded();
        map.put(key(question), new Entry(answer, trace, System.currentTimeMillis()));
    }

    public Stats stats() {
        return new Stats(hits.get(), misses.get(), map.size(), maxEntries, ttlMillis / 1000L);
    }

    public void clear() {
        map.clear();
        hits.set(0);
        misses.set(0);
    }

    // ------------------------------------------------------------------

    private boolean expired(Entry e) {
        return System.currentTimeMillis() - e.storedAtMillis() >= ttlMillis;
    }

    /** 先清过期的；还超上限就按「最久没被存进来的先走」。 */
    private void evictIfNeeded() {
        if (map.size() < maxEntries) {
            return;
        }
        map.entrySet().removeIf(en -> expired(en.getValue()));
        while (map.size() >= maxEntries) {
            Optional<String> oldest = map.entrySet().stream()
                    .min(Comparator.comparingLong(en -> en.getValue().storedAtMillis()))
                    .map(Map.Entry::getKey);
            if (oldest.isEmpty()) {
                return;
            }
            map.remove(oldest.get());
        }
    }

    /**
     * 同一个问题的不同写法（首尾空格、中间连着几个空格）应当算同一条。
     * <b>只做这一层归一化</b>——再往下（去标点、改词序）就开始改变语义了，
     * 那属于「改写器」的活，不是缓存该干的事。
     */
    private static String key(String question) {
        return question == null ? "" : question.trim().replaceAll("\\s+", " ");
    }
}
