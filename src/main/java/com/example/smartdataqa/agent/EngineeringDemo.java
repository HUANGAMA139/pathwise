package com.example.smartdataqa.agent;

import com.example.smartdataqa.service.AnswerCache;
import com.example.smartdataqa.service.QaService;
import com.example.smartdataqa.trace.Trace;
import com.example.smartdataqa.trace.TraceStage;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 29 · <b>工程化自检</b>：把「结构化轨迹」和「答案缓存」这两件事跑给人看。
 *
 * <p>为什么要有这个 demo，而不是只写文档说「我做了」——
 * 因为这个项目从 迭代 20 起就定了一条：<b>写进文档的每个「发现」都必须能被某条实测输出指出来。</b>
 * 轨迹和缓存都属于「不做出来看不出、做错了也不报错」的那类东西，
 * 所以必须有一条命令能把它们<b>打出来</b>。
 *
 * <p>它演示三件事：
 * <ol>
 *   <li><b>轨迹</b>：每一题都把「路由 → 检索/查库 → 拼材料 → 综合」摊开，带每步耗时。
 *       答案错了，一眼能看出是哪一步先偏的。</li>
 *   <li><b>缓存</b>：同一个问题第二遍问，来源变成「缓存」、耗时掉到接近 0、且不调模型。
 *       连「同一个问题首尾多几个空格」也算同一条（key 归一化）。</li>
 *   <li><b>边界</b>：这些收益<b>只落在对外服务上</b>——迭代 15/23/28 的评测直接调
 *       {@link SmartQaAgent}，永远走不到缓存，所以历史基线一条都没动。</li>
 * </ol>
 *
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp29.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp29.enabled", havingValue = "true")
public class EngineeringDemo {

    private static final Logger log = LoggerFactory.getLogger(EngineeringDemo.class);

    /**
     * 三题覆盖三条路径：文档 / 数据库 / 拒答。
     *
     * <p>最后一题是闲聊，走的是 {@code refuse} 那条不调模型的出口——
     * 故意放进来，是为了让轨迹里能看到「拒答也是一步」，
     * 而不是什么都没有（没有轨迹和没有这一步，在日志里长得一样）。
     */
    private static final List<String> QUESTIONS = List.of(
            "销售额是怎么算的？",
            "2026年Q2华东区销售额是多少？",
            "今天天气怎么样？");

    /** 汇总表的一行。带上 {@code trace} 是为了演示「轨迹是结构化的，不只是一段文字」。 */
    private record Row(String question, String route, boolean cached, long ms, boolean ok, Trace trace) {
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner exp29Engineering(QaService qaService) {
        return args -> run(qaService);
    }

    private void run(QaService qa) {
        log.info("========== 迭代 29 · 工程化：结构化轨迹 + 答案缓存 ==========");
        log.info("答案缓存：{}", qa.cacheEnabled() ? "开" : "关（sdaq.cache.enabled=false）");
        log.info("下面每题的【第一遍】都打印完整轨迹；然后把第 1 题原样再问一遍，验证缓存命中。");
        log.info("");

        List<Row> rows = new ArrayList<>();

        for (int i = 0; i < QUESTIONS.size(); i++) {
            log.info("---------- 第 {} 题：{} ----------", i + 1, QUESTIONS.get(i));
            rows.add(askOnce(qa, QUESTIONS.get(i)));
            log.info("");
        }

        String first = QUESTIONS.get(0);

        log.info("---------- 回马枪：把第 1 题【原样】再问一遍 ----------");
        Row repeat = askOnce(qa, first);
        rows.add(repeat);
        log.info("");

        log.info("---------- 再试一次：同一个问题，但首尾多打了几个空格 ----------");
        rows.add(askOnce(qa, "   " + first + "   "));
        log.info("");

        printSummary(rows, rows.get(0).ms(), repeat.ms());
        printCacheStats(qa);
        printTakeaways();
    }

    /** 问一次，把轨迹打出来。失败也如实上报，不编一个答案糊过去。 */
    private Row askOnce(QaService qa, String question) {
        try {
            QaService.Served served = qa.ask(question);
            log.info("{}", served.trace() == null ? "  （没有轨迹）" : served.trace().render());
            log.info("  本次耗时 {} ms{}", served.latencyMillis(),
                    served.fromCache() ? "   ← 命中缓存，没有调用模型" : "");
            return new Row(question.trim(),
                    served.answer().decision() == null ? "-" : served.answer().decision().brief(),
                    served.fromCache(), served.latencyMillis(), true, served.trace());
        }
        catch (RuntimeException e) {
            // 业务库没起、或者模型不可用时，这里会走到——【如实上报】比编一个答案好
            log.warn("  这一题失败了（如实上报，不编）：{}", e.toString());
            return new Row(question.trim(), "-", false, 0L, false, null);
        }
    }

    private void printSummary(List<Row> rows, long firstMs, long repeatMs) {
        log.info("================= 汇总 =================");
        log.info("{} {} {} {}",
                ConsoleText.padRight("问题", 34),
                ConsoleText.padRight("路由", 24),
                ConsoleText.padRight("本次耗时", 12),
                "来源");
        for (Row r : rows) {
            log.info("{} {} {} {}",
                    ConsoleText.padRight(ConsoleText.shorten(r.question(), 32), 34),
                    ConsoleText.padRight(ConsoleText.shorten(r.route(), 22), 24),
                    ConsoleText.padRight(r.ms() + " ms", 12),
                    r.cached() ? "缓存" : (r.ok() ? "模型" : "失败"));
        }
        log.info("");
        log.info("★ 第 1 题：第一遍 {} ms → 再问一遍 {} ms（同一句话，第二遍没调模型）",
                firstMs, repeatMs);

        // 轨迹是【结构化】的，不是几行文字——所以能直接按阶段聚合。
        // 这一句就是「为什么要做成对象、而不是多打几行日志」的实证。
        rows.stream()
                .filter(r -> r.trace() != null && !r.trace().cached())
                .findFirst()
                .ifPresent(r -> {
                    Trace t = r.trace();
                    log.info("★ 同一条轨迹还能按阶段统计（这是「结构化」与「几行日志」的区别）：");
                    log.info("   路由 {} ms · 文档检索 {} ms · 数据库查询 {} ms · 拼材料 {} ms · 模型综合 {} ms（合计 {} ms）",
                            t.millisOf(TraceStage.ROUTE), t.millisOf(TraceStage.RETRIEVE),
                            t.millisOf(TraceStage.SQL), t.millisOf(TraceStage.PREPARE),
                            t.millisOf(TraceStage.SYNTHESIZE), t.totalMillis());
                });
    }

    private void printCacheStats(QaService qa) {
        AnswerCache.Stats st = qa.cacheStats();
        if (st == null) {
            log.info("缓存未开启，没有统计可报。");
            return;
        }
        log.info("缓存统计：命中 {} / 未命中 {}，命中率 {}，当前 {} 条（上限 {}，TTL {} 秒）",
                st.hits(), st.misses(),
                String.format("%.0f%%", st.hitRate() * 100),
                st.size(), st.maxEntries(), st.ttlSeconds());
    }

    private void printTakeaways() {
        log.info("");
        log.info("================= 这张表怎么读 =================");
        log.info("1. 轨迹把每一步摊开了：路由选了哪条、检索到几段、SQL 是什么、各花了多少毫秒。");
        log.info("   答案不对时，先看轨迹里【哪一步】的输出就已经偏了——不用去猜。");
        log.info("2. 同一个问题第二遍是【缓存】来源，耗时掉到接近 0，而且没有调用模型。");
        log.info("   首尾多打空格也算同一条（key 做了归一化），但【只做这一层】——");
        log.info("   再往下（去标点、改词序）就开始改变语义了，那是改写器的活。");
        log.info("3. 缓存只落在【对外服务】上：迭代 15/23/28 的评测直接调 SmartQaAgent，");
        log.info("   永远走不到缓存，所以那些历史基线一条都没动。");
        log.info("   —— 同一件事放在哪一层，决定了它会不会变成一个【不响的错】。");
    }
}
