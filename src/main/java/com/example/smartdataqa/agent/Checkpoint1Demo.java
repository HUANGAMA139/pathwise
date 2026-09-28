package com.example.smartdataqa.agent;

import com.example.smartdataqa.router.RouteDecision;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 11 · 检查点①：双支路端到端 demo。
 *
 * <p><b>本轮不学新东西。</b>把 迭代 8—10 的东西串起来，跑通完整链路：
 * <pre>
 *   提问 → 路由决策 → 走对应支路（mock 数据）→ 流式生成回答
 * </pre>
 *
 * <h3>这一天在整条路线里的位置</h3>
 * 阶段二到这里收口。往后每个阶段都是「把某根假管道换成真的」——
 * 迭代 14 换向量检索、迭代 18 换 Text2SQL、迭代 26 换模块化 RAG。
 * <b>但 Agent 主干、路由契约、两条支路的接口，从本轮起就定住了。</b>
 *
 * <h3>验收标准（本阶段唯一的硬标准）</h3>
 * 随机 10 个问题，路由能给出决策并说出理由，链路不报错。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp11.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp11.enabled", havingValue = "true")
public class Checkpoint1Demo {

    private static final Logger log = LoggerFactory.getLogger(Checkpoint1Demo.class);

    private record Case(String question, RouteDecision.Route expected) {
    }

    /**
     * 10 个问题，覆盖四条路。
     *
     * <p><b>这 10 条就是 迭代 15 评测集的雏形。</b>
     * 那时会扩到 20 条以上，加上检索指标和生成指标；
     * 但「问题 + 期望路由」这个两列结构从本轮起就定了。
     */
    private static final List<Case> CASES = List.of(
            // ---------- 文档支路：问定义、规则、流程 ----------
            new Case("区域划分规则是什么？", RouteDecision.Route.DOCUMENT),
            new Case("销售额是怎么算的？", RouteDecision.Route.DOCUMENT),
            new Case("退货要多久才能到账？", RouteDecision.Route.DOCUMENT),

            // ---------- 数据库支路：问具体数值 ----------
            new Case("2026年Q2华东区销售额是多少？", RouteDecision.Route.DATABASE),
            new Case("哪个区卖得最好？", RouteDecision.Route.DATABASE),
            new Case("各区域的销售额排名是怎样的？", RouteDecision.Route.DATABASE),

            // ---------- 双支路：数字和口径都要 ----------
            new Case("上季度华东区销售额是多少？顺便说下口径是怎么定的", RouteDecision.Route.BOTH),
            new Case("华东区销售额是多少？另外区域是怎么划分的？", RouteDecision.Route.BOTH),

            // ---------- 拒答 ----------
            new Case("今天天气怎么样？", RouteDecision.Route.REFUSE),
            new Case("你好", RouteDecision.Route.REFUSE));

    private record Result(String question,
                          RouteDecision.Route expected,
                          RouteDecision decision,
                          long firstChunkMs,
                          long totalMs) {

        boolean routeOk() {
            return decision != null && decision.route() == expected;
        }
    }

    @Bean
    ApplicationRunner exp11Checkpoint(SmartQaAgent agent) {
        return args -> {
            log.info("========== 迭代 11 · 检查点① 端到端 demo ==========");
            log.info("链路：提问 → 路由决策 → 走支路（mock）→ 流式生成回答");
            log.info("路由：LLM 意图分类，失败降级关键词规则");
            // [注意] 这行原本写的是「都是 mock 数据」，从 迭代 14 起就不准了（DocumentTool 换了真向量检索），
            // 迭代 18 起 DatabaseTool 默认也是真 Text2SQL。改成如实描述，别再放一个「不报错的错」。
            log.info("支路：DocumentTool / DatabaseTool（迭代 11 当时是 mock；现为真实实现 —— "
                    + "要复现当时形态：DocumentTool 指名 mockDocumentTool，DatabaseTool 设 sdaq.database.mode=mock）");
            log.info("问题数：{}，带人工标注的期望路由", CASES.size());

            List<Result> results = new ArrayList<>();

            for (int i = 0; i < CASES.size(); i++) {
                Case c = CASES.get(i);
                log.info("");
                log.info("################ 问题 {} / {} ################", i + 1, CASES.size());
                log.info("问：{}", c.question());
                log.info("期望路由：{}", c.expected().code());

                long t0 = System.currentTimeMillis();
                Long[] firstChunkAt = {null};

                // 流式输出用 System.out 而不是日志——日志每条都带时间戳和前缀，
                // 逐片段打出来会糊成一片，看不出「边生成边出字」的效果。
                System.out.print("答：");

                SmartQaAgent.Answer answer = agent.answerStreaming(c.question(), piece -> {
                    if (firstChunkAt[0] == null) {
                        firstChunkAt[0] = System.currentTimeMillis();
                    }
                    System.out.print(piece);
                });
                System.out.println();

                long total = System.currentTimeMillis() - t0;
                long first = firstChunkAt[0] == null ? total : firstChunkAt[0] - t0;

                String verdict = answer.decision().route() == c.expected() ? "[OK]" : "[X]";
                log.info("");
                log.info("实际路由：{}  {}", answer.decision().brief(), verdict);
                log.info("理由：{}", answer.decision().reason());
                log.info("走的支路：{}", describeBranches(answer));
                log.info("首字延迟 {} ms ｜ 总耗时 {} ms", first, total);

                results.add(new Result(c.question(), c.expected(), answer.decision(), first, total));
            }

            printSummary(results);
        };
    }

    /**
     * 验收汇总。
     *
     * <p>这张表的结构——「问题 / 期望 / 实际 / 判定」——就是 迭代 24
     * 「多策略对比」和 迭代 15「评测报告」的样子，只是那时列会更多。
     */
    private void printSummary(List<Result> results) {
        log.info("");
        log.info("================= 验收汇总 =================");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("问题", 34),
                ConsoleText.padRight("期望", 11),
                ConsoleText.padRight("实际", 11),
                ConsoleText.padRight("置信度", 9),
                "判定");

        int ok = 0;
        long firstTotal = 0;
        long totalTotal = 0;

        for (Result r : results) {
            ok += r.routeOk() ? 1 : 0;
            firstTotal += r.firstChunkMs();
            totalTotal += r.totalMs();

            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(ConsoleText.shorten(r.question(), 32), 34),
                    ConsoleText.padRight(r.expected().code(), 11),
                    ConsoleText.padRight(r.decision().route().code(), 11),
                    ConsoleText.padRight(String.format("%.2f", r.decision().confidence()), 9),
                    r.routeOk() ? "[OK]" : "[X] 错");
        }

        int n = results.size();
        log.info("");
        log.info("路由准确率：{} / {}", ok, n);
        log.info("平均首字延迟：{} ms", firstTotal / n);
        log.info("平均总耗时：{} ms", totalTotal / n);

        log.info("");
        if (ok == n) {
            log.info("★ 验收通过：{} 条路由全部正确，链路无异常。", n);
        }
        else {
            log.warn("★ 验收未过：{} / {} 条路由正确，需要看看错的那几条。", ok, n);
        }

        log.info("");
        log.info("================= 关于那个首字延迟 =================");
        log.info("对比 迭代 7 单独测流式时的 681 ms —— 现在明显更长了。");
        log.info("原因：流式只覆盖链路的后半段。前半段是阻塞的，而且路由本身就占一次模型调用");
        log.info("（约 1.5 秒）。所以：");
        log.info("    首字延迟 = 路由耗时 + 取证据耗时 + 首字生成耗时");
        log.info("这解释了为什么「我给模型加了流式，用户体验怎么还是慢」——");
        log.info("流式优化的是生成阶段，但如果前置阶段占了大头，整体感知改善有限。");
        log.info("迭代 24 比较路由策略时，延迟会重新变成关键指标。");
    }

    private String describeBranches(SmartQaAgent.Answer a) {
        List<String> branches = new ArrayList<>();
        if (a.usedDocument()) {
            branches.add("文档");
        }
        if (a.usedDatabase()) {
            branches.add("数据库");
        }
        return branches.isEmpty() ? "（未走支路）" : String.join(" + ", branches);
    }
}
