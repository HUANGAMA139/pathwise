package com.example.smartdataqa.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 迭代 29 · 轨迹收集器。一次问答一个实例，边走边记。
 *
 * <p>用法就两种，都很短：
 * <pre>
 *   // ① 有返回值、要计时的一段
 *   RouteDecision d = rec.timed(TraceStage.ROUTE, () -> router.decide(q), RouteDecision::brief);
 *
 *   // ② 只记一笔（比如收尾的那一步）
 *   long t0 = rec.mark();
 *   ...
 *   rec.stepSince(TraceStage.SYNTHESIZE, "答案 210 字", t0);
 * </pre>
 *
 * <p><b>{@link #timed} 在抛异常时也会记一笔</b>（detail 写成「失败：…」）再原样抛出去——
 * 因为「哪一步炸的、炸之前跑了多久」恰恰是排查时最需要的信息，
 * 失败路径不记轨迹等于把最该看的那一段丢掉。
 *
 * <p>线程安全：<b>不安全，也不需要安全</b>。一次问答一个实例，在同一个线程里从路由走到综合。
 * （共享的 {@code SmartQaAgent} 每次调用都新起一个 recorder。）
 */
public class TraceRecorder {

    private final String requestId;
    private final String question;
    private final List<TraceStep> steps = new ArrayList<>();
    private final long startedAt;

    public TraceRecorder(String question) {
        this.requestId = UUID.randomUUID().toString().substring(0, 8);
        this.question = question;
        this.startedAt = System.nanoTime();
    }

    public String requestId() {
        return requestId;
    }

    /** 记时起点。配合 {@link #stepSince} 用。 */
    public long mark() {
        return System.nanoTime();
    }

    public static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * 量一段、记一步、原样返回结果。
     *
     * @param detailOf 从返回值里抽一句证据摘要（可为 null = 不写摘要）
     */
    public <T> T timed(TraceStage stage, Supplier<T> body, Function<T, String> detailOf) {
        long t0 = System.nanoTime();
        try {
            T v = body.get();
            add(stage, detailOf == null ? "" : safe(detailOf.apply(v)), millisSince(t0));
            return v;
        }
        catch (RuntimeException e) {
            // 失败也要留痕：哪一步炸的、炸之前花了多久
            add(stage, "失败：" + e.getMessage(), millisSince(t0));
            throw e;
        }
    }

    /** 收尾用：把一个已经量好的时间段记成一步。 */
    public void stepSince(TraceStage stage, String detail, long startedNanos) {
        add(stage, detail, millisSince(startedNanos));
    }

    /** 不计时地记一步（拒答这种没有耗时意义的）。 */
    public void step(TraceStage stage, String detail) {
        add(stage, detail, 0L);
    }

    /** 收口成不可变的 {@link Trace}。 */
    public Trace finish() {
        return new Trace(requestId, question, List.copyOf(steps), millisSince(startedAt), false);
    }

    private void add(TraceStage stage, String detail, long millis) {
        steps.add(new TraceStep(stage, safe(detail), millis));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
