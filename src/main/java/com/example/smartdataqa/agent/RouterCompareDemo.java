package com.example.smartdataqa.agent;

import com.example.smartdataqa.router.KeywordQueryRouter;
import com.example.smartdataqa.router.LlmQueryRouter;
import com.example.smartdataqa.router.QueryRouter;
import com.example.smartdataqa.router.RouteDecision;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.mock.MockDatabaseTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 9 · 两个路由策略的对比。
 *
 * <p>这是 <b>迭代 24「路由 v2 多策略对比」的预演</b>：同一批用例、同一套判定标准，
 * 跑两个策略，看准确率和延迟各是多少。
 *
 * <h3>为什么现在就要做对比</h3>
 * 因为「LLM 比关键词好」这句话如果没有数据支撑，就只是个说法。
 * 而且更重要的：<b>「期望路由」这一列就是 迭代 15 评测集里 {@code expected_route} 字段的雏形。</b>
 * 本轮你手写 8 条，迭代 15 会扩到 20 条以上，并加上检索、生成等指标。
 *
 * <h3>最后有一段验证</h3>
 * 迭代 8 发现「误判成 both 会让答案里多出没人问过的数字」。
 * 本轮跑完对比后，用 LLM 路由重跑同一个问题，
 * <b>检查那个数字是不是消失了</b>——这是可证伪的，不是「感觉好多了」。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp9.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp9.enabled", havingValue = "true")
public class RouterCompareDemo {

    private static final Logger log = LoggerFactory.getLogger(RouterCompareDemo.class);

    /** 迭代 8 那次误判里多出来的数字。用它来验证污染是否消失。 */
    private static final String STRAY_NUMBER = "128,450,000";

    /** 一条用例：问题 + 人工标注的期望路由。 */
    private record Case(String question, RouteDecision.Route expected) {
    }

    /**
     * 8 条用例。
     *
     * <p>后三条是专门加进来压关键词路由的——它们的答案很明确，
     * 但关键词表覆盖不到，规则只能判成 refuse。
     */
    private static final List<Case> CASES = List.of(
            new Case("区域划分规则是什么？", RouteDecision.Route.DOCUMENT),
            new Case("2026年Q2华东区销售额是多少？", RouteDecision.Route.DATABASE),
            new Case("上季度华东区销售额是多少？顺便说下口径是怎么定的", RouteDecision.Route.BOTH),
            new Case("今天天气怎么样？", RouteDecision.Route.REFUSE),

            // 迭代 8 的失手用例：规则判成了 both，正确是 document
            new Case("销售额是怎么算的？", RouteDecision.Route.DOCUMENT),

            // 下面三条关键词表完全覆盖不到
            new Case("退货要多久才能到账？", RouteDecision.Route.DOCUMENT),
            new Case("哪个区卖得最好？", RouteDecision.Route.DATABASE),
            new Case("你好", RouteDecision.Route.REFUSE));

    private record Result(String question,
                          RouteDecision.Route expected,
                          RouteDecision kw,
                          long kwMs,
                          RouteDecision llm,
                          long llmMs) {

        boolean kwOk() {
            return kw != null && kw.route() == expected;
        }

        boolean llmOk() {
            return llm != null && llm.route() == expected;
        }
    }

    @Bean
    ApplicationRunner exp9RouterCompare(ChatClient.Builder chatClientBuilder,
                                       @Qualifier("mockDocumentTool") DocumentTool documentTool) {
        return args -> {
            ChatClient chat = chatClientBuilder.build();

            QueryRouter keyword = new KeywordQueryRouter();
            QueryRouter llm = new LlmQueryRouter(chat, keyword);

            log.info("========== 迭代 9 · 路由策略对比 ==========");
            log.info("策略 A：{}（迭代 8 的规则版）", keyword.strategyName());
            log.info("策略 B：{}（本轮新写的，用结构化输出）", llm.strategyName());
            log.info("用例数：{}，带人工标注的期望路由", CASES.size());
            log.info("");

            List<Result> results = new ArrayList<>();

            for (int i = 0; i < CASES.size(); i++) {
                Case c = CASES.get(i);
                log.info("---------- 用例 {} ----------", i + 1);
                log.info("问题：{}", c.question());
                log.info("期望：{}", c.expected().code());

                long t0 = System.currentTimeMillis();
                RouteDecision kw = keyword.decide(c.question());
                long kwMs = System.currentTimeMillis() - t0;

                long t1 = System.currentTimeMillis();
                RouteDecision l = llm.decide(c.question());
                long llmMs = System.currentTimeMillis() - t1;

                log.info("  规则：{}  {}", kw.route().code(),
                        kw.route() == c.expected() ? "[OK]" : "[X] 应为 " + c.expected().code());
                log.info("  LLM ：{}  {}  —— 理由：{}", l.route().code(),
                        l.route() == c.expected() ? "[OK]" : "[X] 应为 " + c.expected().code(),
                        l.reason());
                log.info("");

                results.add(new Result(c.question(), c.expected(), kw, kwMs, l, llmMs));
            }

            // ---------------- 对比表 ----------------
            printComparison(results, keyword, llm);

            // ---------------- 验证：误判污染是否消失 ----------------
            log.info("");
            log.info("================= 验证：迭代 8 的污染消失了吗 =================");
            String probe = "销售额是怎么算的？";
            log.info("用同一个问题「{}」跑完整的 Agent，用 LLM 路由：", probe);
            log.info("");

            SmartQaAgent agent = new SmartQaAgent(llm, documentTool,
                    new MockDatabaseTool(), chat);
            SmartQaAgent.Answer answer = agent.answer(probe);

            log.info("");
            log.info("  ---------- 答案 ----------");
            log.info("  {}", answer.text().replace("\n", "\n  "));
            log.info("  --------------------------");
            log.info("");
            log.info("  实际走的支路：{}", describeBranches(answer));
            log.info("  答案里是否出现那个多余的数字（{}）：{}",
                    STRAY_NUMBER,
                    answer.text().contains(STRAY_NUMBER)
                            ? "出现了 [X] —— 污染还在"
                            : "没有出现 [OK] —— 污染消失了");

            log.info("");
            log.info("================= 结论 =================");
            log.info("迭代 8 我原本以为「误判成 both 只是浪费一次查询」。");
            log.info("实际是它会往上下文里塞一个没人问过的数字，污染答案。");
            log.info("本轮 LLM 路由把这类问题判回了 document，那道支路不再被走，");
            log.info("数字也就不出现在答案里了。");
            log.info("");
            log.info("但注意：LLM 路由不是免费的——看上面的延迟对比。");
            log.info("迭代 24 会把「规则 / LLM / 混合」三条路摆在一起比，");
            log.info("到时候要回答的是：多花这些延迟，值不值。");
        };
    }

    private void printComparison(List<Result> results, QueryRouter keyword, QueryRouter llm) {
        log.info("================= 对比 =================");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("问题", 34),
                ConsoleText.padRight("期望", 11),
                ConsoleText.padRight("规则", 11),
                ConsoleText.padRight("LLM", 11),
                "判定");

        int kwCorrect = 0;
        int llmCorrect = 0;
        long kwTotal = 0;
        long llmTotal = 0;

        for (Result r : results) {
            kwCorrect += r.kwOk() ? 1 : 0;
            llmCorrect += r.llmOk() ? 1 : 0;
            kwTotal += r.kwMs();
            llmTotal += r.llmMs();

            String verdict;
            if (r.kwOk() && r.llmOk()) {
                verdict = "都对";
            }
            else if (!r.kwOk() && r.llmOk()) {
                verdict = "← 规则错，LLM 对";
            }
            else if (r.kwOk()) {
                verdict = "← LLM 错，规则对";
            }
            else {
                verdict = "两个都错";
            }

            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(ConsoleText.shorten(r.question(), 32), 34),
                    ConsoleText.padRight(r.expected().code(), 11),
                    ConsoleText.padRight(r.kw().route().code(), 11),
                    ConsoleText.padRight(r.llm().route().code(), 11),
                    verdict);
        }

        log.info("");
        log.info("准确率：{} {} / {}     {} {} / {}",
                ConsoleText.padRight(keyword.strategyName(), 20),
                kwCorrect, results.size(),
                ConsoleText.padRight(llm.strategyName(), 20),
                llmCorrect, results.size());

        // 规则路由是纯内存计算，延迟基本为 0；这里给个更诚实的展示方式
        long kwAvg = results.isEmpty() ? 0 : kwTotal / results.size();
        long llmAvg = results.isEmpty() ? 0 : llmTotal / results.size();
        log.info("平均延迟：{}{} ms    {}{} ms",
                ConsoleText.padRight(keyword.strategyName(), 20), kwAvg,
                ConsoleText.padRight(llm.strategyName(), 20), llmAvg);
        log.info("");
        log.info("注意一个不公平的地方：这个延迟对比里没有算规则路由的「人力成本」——");
        log.info("关键词表是我一条条手写的，而且每加一个业务领域就要补一批词。");
        log.info("规则的优势是零运行时成本，劣势是零泛化能力。");
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
