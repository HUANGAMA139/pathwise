package com.example.smartdataqa.trace;

import com.example.smartdataqa.util.ConsoleText;

import java.util.List;

/**
 * 迭代 29 · 一次问答的完整轨迹（结构化对象，不是一行行日志）。
 *
 * <h3>它和 slf4j 日志的分工</h3>
 * 日志是<b>流式</b>的：散在几十行里、多条请求交错、只进控制台。
 * 轨迹是<b>按请求收拢</b>的一份对象：能整段打出来，也能随 REST / MCP 的响应回给调用方。
 *
 * <p>只有日志的时候，「一次提问到底走了哪几步、每步多久」要靠人拿时间戳去拼——
 * 并发一来就拼不出来了。这就是轨迹存在的理由。
 *
 * <h3>{@code cached} 这个标记必须说清楚</h3>
 * 命中缓存时，这一步<b>根本没有执行</b>，但轨迹里的 {@code steps} 又是真发生过的
 * （是<b>首次调用</b>时发生的）。
 * 所以缓存命中返回的轨迹是「首次调用的轨迹 + 一个 cached 标记」，
 * {@link #render()} 会把这句写在第一行——<b>否则读者会把那 5829 ms 当成这一次的耗时。</b>
 * 本次请求真正的耗时由调用方（{@code QaService} / REST 响应）单独报。
 *
 * @param requestId   本次请求的短 id（8 位十六进制），把日志和轨迹对上号用
 * @param question    用户原问题
 * @param steps       按发生顺序排列的每一步
 * @param totalMillis 首次调用从头到尾的毫秒数
 * @param cached      这份答案是不是从缓存里取的
 */
public record Trace(String requestId, String question, List<TraceStep> steps,
                    long totalMillis, boolean cached) {

    /** 标记为「来自缓存」。steps 和 totalMillis 沿用首次调用的，不改写、不伪装。 */
    public Trace asCached() {
        return new Trace(requestId, question, steps, totalMillis, true);
    }

    /** 某个阶段一共花了多少毫秒（同一阶段出现多次时会累加）。 */
    public long millisOf(TraceStage stage) {
        long sum = 0;
        for (TraceStep s : steps) {
            if (s.stage() == stage) {
                sum += s.millis();
            }
        }
        return sum;
    }

    /**
     * 渲染成可以直接打日志、也可以直接放进响应体的多行文本。
     *
     * <p><b>GBK 安全</b>：只用 {@code ├ └ ─ ★ —} 这些在 GBK 里存在的符号；
     * 那两个勾叉（U+2713 / U+2717）不在 GBK 里，会变成 {@code ?}，见「技术坑」表——
     * 所以本文件里连<b>提到</b>它们都用码位写，不写字符本身。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("轨迹 ").append(requestId)
                .append("  总 ").append(totalMillis).append(" ms");
        if (cached) {
            sb.append("   ★ 缓存命中：本次没有调用模型，下面是【首次调用】时的轨迹");
        }
        sb.append('\n');
        for (int i = 0; i < steps.size(); i++) {
            TraceStep s = steps.get(i);
            sb.append(i == steps.size() - 1 ? "  └─ " : "  ├─ ")
                    .append(ConsoleText.padRight(s.stage().label(), 12))
                    .append(String.format("%6d ms  ", s.millis()))
                    .append(s.detail())
                    .append('\n');
        }
        return sb.toString();
    }
}
