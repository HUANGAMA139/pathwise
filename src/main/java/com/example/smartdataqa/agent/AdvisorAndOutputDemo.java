package com.example.smartdataqa.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * 迭代 7 · Advisor、结构化输出、流式。
 *
 * <p>三件事，但有一件是主线，另外两件是给你项目铺路。
 *
 * <h3>主线：Advisor 是扩展点 —— 但它在哪一层？</h3>
 * 昨天（迭代 6）你发现框架接管循环之后「拦截点没了」。Advisor 就是框架给的扩展点。
 *
 * <p>但本轮第一段演示会证明一个容易误解的事：
 * <b>Advisor 在「整个模型调用」这一层，不在「单次工具调用」那一层。</b>
 *
 * <p>证据看日志顺序就明白——一次 advisor 的进入和返回之间，
 * 夹着工具被调用的日志。说明工具循环跑在它<b>里面</b>，而不是由它逐步编排。
 *
 * <p>这不是缺陷，是分层。但你要清楚：<b>想在「每个工具执行前」插检查，
 * Advisor 做不到</b>（Spring AI 2.0 才把工具循环提到 advisor 链上）。
 * 1.1.0 里要拦单个工具，得包 ToolCallback——迭代 21 会做。
 *
 * <h3>另外两件：给你项目铺路</h3>
 * <ul>
 *   <li><b>结构化输出</b>——让模型直接返回 Java 对象。迭代 3 你用正则解析文本
 *       （还得容错中文冒号、markdown 包裹），本轮这个是那个问题的正解。
 *       它是 迭代 9 路由决策的技术基础。</li>
 *   <li><b>流式</b>——首字延迟从 2 秒降到 0.3 秒左右。用户感知的等待时间短很多。</li>
 * </ul>
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp7.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp7.enabled", havingValue = "true")
public class AdvisorAndOutputDemo {

    private static final Logger log = LoggerFactory.getLogger(AdvisorAndOutputDemo.class);

    // ==================================================================
    // 一个自定义 Advisor
    // ==================================================================

    /**
     * 追踪 Advisor。功能很朴素：进来看一眼请求、出去记一下耗时。
     *
     * <p><b>它的意义不在功能，而在位置。</b>它包在整个模型调用的外面，
     * 所以「改写请求」（比如往 prompt 里注入检索到的文档）和
     * 「观察响应」（比如记日志、算耗时、做重试）这类<b>横切关注点</b>，
     * 都适合放在这里。
     *
     * <p>这正是 迭代 26 模块化 RAG 的基础——RAG 的检索注入、对话记忆、
     * 可观测性，全都是通过 Advisor 挂上去的。
     *
     * <p>实现 {@link CallAdvisor} 需要三个方法：{@code adviseCall}（hook 本体）、
     * {@code getOrder}（链上顺序，小的先执行）、{@code getName}（唯一名）。
     * 不想手写链式调用的话，也可以用 {@code BaseAdvisor}，它有 before/after 两个钩子。
     */
    static class TracingAdvisor implements CallAdvisor {

        private static final Logger log = LoggerFactory.getLogger(TracingAdvisor.class);

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            long t0 = System.currentTimeMillis();
            log.info("    [Advisor] >>> 进入。这里能拿到未封装的请求，可以改写它");

            // 关键：调用链上的下一个。所有 advisor 都在这行之前/之后做自己的事。
            ChatClientResponse response = chain.nextCall(request);

            log.info("    [Advisor] <<< 返回。整段耗时 {} ms", System.currentTimeMillis() - t0);
            return response;
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public String getName() {
            return "TracingAdvisor";
        }
    }

    // ==================================================================
    // 结构化输出的目标类型
    // ==================================================================

    /**
     * 路由决策。
     *
     * <p>这就是你项目最终要的那个东西——模型不返回一段话，
     * 而是直接返回一个带字段的对象，代码拿到就能用，不用解析文本。
     *
     * <p>注意它应该是个 {@code record}：不可变、字段明确、Jackson 能直接反序列化。
     *
     * <p>迭代 8 会把它提到 {@code router} 包做成正式契约（那时字段可能还会调整）。
     */
    record RouteDecision(String route, double confidence, String reason) {
    }

    // ==================================================================
    // 演示用的工具
    // ==================================================================

    static class DemoTools {

        private static final Logger log = LoggerFactory.getLogger(DemoTools.class);

        @Tool(description = "查询指定城市当前的天气情况。用户问天气时必须调用。")
        public String getWeather(
                @ToolParam(description = "城市名，例如 北京、上海") String city) {
            log.info("      >> [工具被调用] getWeather(city={})", city);
            return "北京".equals(city) ? "北京：小雨，气温 18℃，湿度 88%" : city + "：晴，气温 25℃";
        }
    }

    // ==================================================================

    @Bean
    ApplicationRunner exp7AdvisorAndOutput(ChatClient.Builder chatClientBuilder) {
        return args -> {
            // 把自定义 Advisor 挂在默认链上
            ChatClient chat = chatClientBuilder.defaultAdvisors(new TracingAdvisor()).build();
            DemoTools tools = new DemoTools();

            log.info("========== 迭代 7 · Advisor / 结构化输出 / 流式 ==========");

            // ============ 第 1 段：Advisor 在哪一层 ============
            log.info("");
            log.info("============== 第 1 段：Advisor 挂在哪一层 ==============");
            log.info("提问：北京今天天气怎么样？（需要调工具）");
            log.info("调用：chat.prompt(问题).tools(工具).call().content()");

            String a1 = chat.prompt("北京今天天气怎么样？").tools(tools).call().content();

            log.info("");
            log.info("模型回答：{}", a1);
            log.info("");
            log.info("------ 看上面日志的嵌套关系 ------");
            log.info("[Advisor] >>> 进入");
            log.info("      >> [工具被调用] getWeather     <- 夹在中间");
            log.info("[Advisor] <<< 返回");
            log.info("");
            log.info("一次 Advisor 的进入/返回，把整个工具往返【整段包住】了。");
            log.info("所以：Advisor 在「整个模型调用」这一层，不在「单次工具调用」那一层。");
            log.info("它能看到请求和最终响应，但看不到中间每一次工具调用。");
            log.info("");
            log.info("换句话说：想在「每个工具执行前」插检查，Advisor 做不到。");
            log.info("（Spring AI 2.0 才把工具循环提到 advisor 链上；1.1.0 得包 ToolCallback）");
            log.info("它擅长的是另一类事：RAG 检索注入、对话记忆、日志追踪、重试——");
            log.info("都是「整次调用之前/之后」的横切逻辑。迭代 26 的模块化 RAG 全靠它。");

            // ============ 第 2 段：结构化输出 ============
            log.info("");
            log.info("============== 第 2 段：结构化输出 ==============");
            log.info("对比 迭代 3：那边的循环让模型输出文本，我写正则去解析，");
            log.info("还得容错中文冒号、markdown 包裹、模型自编 Observation……");
            log.info("本轮让模型直接返回一个 Java 对象：");

            String routeQuestion = """
                    判断下面这个问题该走哪条路，并给出你的判断依据。

                    可用路径：
                    - document：答案在制度文档里（例如口径、定义、规则）
                    - database：答案在业务数据里（例如金额、数量、排名）
                    - both：两边都需要
                    - refuse：不属于以上任何一类

                    用户问题：上季度华东区销售额是多少？口径是怎么定的？
                    """;

            RouteDecision d = chat.prompt(routeQuestion).call().entity(RouteDecision.class);

            log.info("");
            log.info("直接拿到的对象：");
            log.info("    route      = {}", d.route());
            log.info("    confidence = {}", d.confidence());
            log.info("    reason     = {}", d.reason());
            log.info("");
            log.info("没有正则、没有容错、没有解析失败的兜底。");
            log.info("Spring AI 的做法：自动给 prompt 追加一段格式说明，再把返回的 JSON 反序列化成这个 record。");
            log.info("字段名和类型对不上会直接报错，而不是悄悄给你一个错值——这是它比手写解析强的地方。");
            log.info("");
            log.info("这正是 迭代 9 路由要用的机制：模型不回答「我觉得该查库」，");
            log.info("而是返回一个带 route / confidence / reason 的对象，代码直接拿 route 做分支。");

            // ============ 第 3 段：流式 ============
            log.info("");
            log.info("============== 第 3 段：流式输出 ==============");
            String streamQuestion = "用三句话介绍杭州西湖。";

            // 先跑非流式，量总耗时作对照
            long t0 = System.currentTimeMillis();
            String whole = chat.prompt(streamQuestion).call().content();
            long wholeCost = System.currentTimeMillis() - t0;
            log.info("非流式：等了 {} ms 才看到第一个字，总长 {} 字", wholeCost, whole.length());
            log.info("  用户这 {} ms 里看到的是一片空白。", wholeCost);

            // 再跑流式
            log.info("");
            log.info("流式：");
            StringBuilder sb = new StringBuilder();
            long t1 = System.currentTimeMillis();
            Long firstChunkAt = null;
            int chunks = 0;

            Flux<String> flux = chat.prompt(streamQuestion).stream().content();
            for (String piece : flux.toIterable()) {
                if (firstChunkAt == null) {
                    firstChunkAt = System.currentTimeMillis();
                    log.info("  >>> 首字到达！等了 {} ms", firstChunkAt - t1);
                }
                chunks++;
                sb.append(piece);
            }
            long totalCost = System.currentTimeMillis() - t1;

            log.info("  总耗时 {} ms，共 {} 个片段", totalCost, chunks);
            log.info("");
            log.info("对比：");
            log.info("  非流式 —— 用户盯着空白等 {} ms，然后一次性看到全文", wholeCost);
            log.info("  流式   —— 用户等 {} ms 就开始看到字，边生成边看", firstChunkAt - t1);
            log.info("");
            log.info("总耗时其实差不多（模型该生成多久还是多久），");
            log.info("但【感知等待时间】差了好几倍。这是流式唯一但足够重要的价值。");
            log.info("");
            log.info("提醒一个坑：上面那个 TracingAdvisor 只在 .call() 时生效。");
            log.info("要让它也包住 .stream()，得实现 StreamAdvisor 而不是 CallAdvisor——");
            log.info("否则你的日志/限流/重试在流式路径上会【静默失效】，不报错、只是不执行。");
        };
    }
}
