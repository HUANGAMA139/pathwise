package com.example.smartdataqa.agent;

import com.example.smartdataqa.router.KeywordQueryRouter;
import com.example.smartdataqa.router.QueryRouter;
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
 * 迭代 8 · 双支路骨架的端到端演示。
 *
 * <p>从本轮起，「一个横跨两个数据源的问题」第一次真的能跑通。
 *
 * <h3>五个问题，覆盖四条路</h3>
 * 前四个各打一条路，验证契约是完整的。
 * <b>第五个是故意留的，它暴露规则路由的弱点</b>——那是 迭代 9 换 LLM 路由的动机。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp8.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp8.enabled", havingValue = "true")
public class SkeletonDemo {

    private static final Logger log = LoggerFactory.getLogger(SkeletonDemo.class);

    /** 前四个各打一条路。 */
    private static final List<String> CASES = List.of(
            // 期望 document：只问规则，库里没有答案
            // 注意这里刻意避开了「统计」二字——它在数据类词表里，
            // 一出现就会把问题拉去 both（这个毛病在第 5 个用例里专门演示）
            "区域划分规则是什么？",

            // 期望 database：只问数字，文档里没有
            "2026年Q2华东区销售额是多少？",

            // 期望 both：数字 + 口径都要，缺一个答案就不完整
            "上季度华东区销售额是多少？顺便说下口径是怎么定的",

            // 期望 refuse：两边都不该走
            "今天天气怎么样？");

    /**
     * 第五个问题，单独拿出来演示规则的弱点。
     *
     * <p>它问的是「销售额怎么算」——答案只在文档里，数据库帮不上忙。
     * 但关键词表里「销售额」属于数据类词、「怎么算」属于文档类词，
     * 于是规则会判成 {@code both}，白白多查一次库。
     */
    private static final String RULE_WEAKNESS_CASE = "销售额是怎么算的？";

    @Bean
    ApplicationRunner exp8SkeletonDemo(ChatClient.Builder chatClientBuilder,
                                       @Qualifier("mockDocumentTool") DocumentTool documentTool) {
        return args -> {
            // 组装骨架。
            // 文档工具从容器里拿：迭代 13 起它需要语料 + 分块才能构造，没法「new 一下就行」。
            // 迭代 14 起容器里有**两个** DocumentTool——主链路那个是向量检索，
            // 而这个 demo 要的是「字符匹配」那个（第 5 个用例的结论就是关于字符匹配的局限），
            // 所以显式指名 mockDocumentTool。数据库工具还是无状态的，直接 new 不影响。
            QueryRouter router = new KeywordQueryRouter();
            SmartQaAgent agent = new SmartQaAgent(
                    router,
                    documentTool,
                    new MockDatabaseTool(),
                    chatClientBuilder.build());

            log.info("========== 迭代 8 · 双支路骨架 ==========");
            log.info("路由策略：{}（迭代 9 会换成 LLM 意图分类）", router.strategyName());
            log.info("两个工具：DocumentTool / DatabaseTool，都是 mock 实现");
            log.info("");

            List<SmartQaAgent.Answer> results = new ArrayList<>();

            for (int i = 0; i < CASES.size(); i++) {
                String q = CASES.get(i);
                log.info("");
                log.info("################ 用例 {} / {} ################", i + 1, CASES.size());
                SmartQaAgent.Answer answer = agent.answer(q);
                results.add(answer);
                printAnswer(answer);
            }

            // ---------- 第五个：规则的弱点 ----------
            log.info("");
            log.info("################ 用例 5：故意暴露规则的弱点 ################");
            log.info("这个问题只在文档里有答案（「销售额怎么算」），数据库帮不上忙。");
            log.info("但关键词表里「销售额」是数据类词、「怎么算」是文档类词，");
            log.info("所以规则会判成 both —— 白白多查一次库。");
            log.info("");
            SmartQaAgent.Answer weak = agent.answer(RULE_WEAKNESS_CASE);
            printAnswer(weak);

            // ---------- 汇总 ----------
            log.info("");
            log.info("================= 路由汇总 =================");
            log.info("{} {} {} {}", ConsoleText.padRight("问题", 36), ConsoleText.padRight("路由", 12),
                    ConsoleText.padRight("置信度", 10), "实际走的支路");
            for (SmartQaAgent.Answer a : results) {
                String branches = (a.usedDocument() ? "文档 " : "")
                        + (a.usedDatabase() ? "数据库" : "");
                if (branches.isEmpty()) {
                    branches = "（未走支路）";
                }
                log.info("{} {} {} {}",
                        ConsoleText.padRight(ConsoleText.shorten(a.question(), 32), 36),
                        ConsoleText.padRight(a.decision().route().code(), 12),
                        ConsoleText.padRight(String.format("%.2f", a.decision().confidence()), 10),
                        branches);
            }
            log.info("{} {} {} {}",
                    ConsoleText.padRight(ConsoleText.shorten(RULE_WEAKNESS_CASE, 32), 36),
                    ConsoleText.padRight(weak.decision().route().code(), 12),
                    ConsoleText.padRight(String.format("%.2f", weak.decision().confidence()), 10),
                    (weak.usedDocument() ? "文档 " : "") + (weak.usedDatabase() ? "数据库" : ""));

            log.info("");
            log.info("================= 本轮得到了什么 =================");
            log.info("1. 四值路由的契约立住了：document / database / both / refuse 都能走到");
            log.info("2. 双支路是 mock，但【数据形态和真实形态一致】——");
            log.info("   迭代 14 换向量检索、迭代 18 换 Text2SQL 时，Agent 主干一行都不用改");
            log.info("3. 上面最后一行是规则的弱点：它把「销售额怎么算」判成了 both，");
            log.info("   多查了一次没用的库。规则给不出「我有多确定」，只能给「匹配到了没」。");
            log.info("   这就是 迭代 9 换 LLM 路由的动机。");
        };
    }

    private void printAnswer(SmartQaAgent.Answer a) {
        log.info("");
        log.info("  ---------- 最终答案 ----------");
        log.info("  {}", a.text().replace("\n", "\n  "));
        log.info("  ------------------------------");
    }

    // 对齐中文表格的辅助方法已抽到 com.example.smartdataqa.util.ConsoleText
    // （因为 迭代 9 的对比表又要用一次，第三次出现就该抽出来）
}
