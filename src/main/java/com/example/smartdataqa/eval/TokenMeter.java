package com.example.smartdataqa.eval;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 迭代 24 · <b>token 计数器</b>。口径是「生成 token」（prompt + completion）。
 *
 * <h3>为什么必须有它</h3>
 * 迭代 24 的对比表要回答的是「多花这些 token，值不值」。
 * <b>没有 token 数的「成本」列就只是个说法</b>——和 迭代 9 那句「LLM 比关键词好」
 * （当时也没有数据）是同一个毛病。
 *
 * <p>它<b>不</b>累计 embedding：embedding 在另一条计费线上、而且小得多，
 * 混在一起会模糊「生成花多少」这个关键数字。策略 D 的 embedding 调用在报告里单列。
 *
 * <p>用法：<b>每次 decide 前后 {@link #reset()} / 读快照</b>，于是它表示「一次调用的用量」。
 * 所以它不该被并发共享——本轮只有一个线程在跑。
 */
public final class TokenMeter {

    private final AtomicLong prompt = new AtomicLong();
    private final AtomicLong completion = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong missingUsage = new AtomicLong();

    /** 开始量一次新的调用。 */
    public void reset() {
        prompt.set(0);
        completion.set(0);
        calls.set(0);
        missingUsage.set(0);
    }

    public void add(long promptTokens, long completionTokens) {
        prompt.addAndGet(Math.max(0L, promptTokens));
        completion.addAndGet(Math.max(0L, completionTokens));
        calls.incrementAndGet();
    }

    /**
     * 一次调用没有拿到 usage。
     *
     * <p><b>这件事必须能被看见</b>：拿不到 usage 时 token 记 0，
     * 而「成本 0」看上去和「没花钱」一模一样——正是本项目一路在抓的那类错。
     * 所以单独计数，报告里会报出来。
     */
    public void missingUsage() {
        missingUsage.incrementAndGet();
    }

    public long promptTokens() {
        return prompt.get();
    }

    public long completionTokens() {
        return completion.get();
    }

    public long totalTokens() {
        return prompt.get() + completion.get();
    }

    public long calls() {
        return calls.get();
    }

    public long missingUsageCount() {
        return missingUsage.get();
    }
}
