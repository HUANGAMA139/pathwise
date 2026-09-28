package com.example.smartdataqa.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 迭代 6 · 用 Spring AI 的 ChatClient + @Tool 重写 迭代 2 的手写循环。
 *
 * <h3>先看代码量的对比（数字是实测的）</h3>
 * <pre>
 *   迭代 2 MinimalAgentLoop   共 247 行
 *       其中真正干活的：发 HTTP + 解析 tool_calls + while 循环 + 回灌结果，约 80 行
 *       其余是配置、工具假数据、日志和注释
 *
 *   迭代 6 本类               共 148 行
 *       真正干活的：1 行
 *       其余是注解、工具假数据、以及给这个演示写的说明性日志
 * </pre>
 * 所以准确的说法不是「代码少了一半」，而是
 * <b>「框架把 迭代 2 那 80 行核心逻辑，压成了 1 行」</b>——
 * 省下来的都在注释和演示说明里，不在逻辑里。
 *
 * <h3>你手写的那个循环去哪了</h3>
 * <b>框架把它整个收进去了。</b>Spring AI 的官方描述是
 * 「Spring AI handles the full round-trip automatically」——
 * 你调一次 {@code .call()}，它内部会自动：发请求 → 收到 tool_calls → 反射调用你的 Java 方法
 * → 把返回值包装成消息塞回去 → 再发一次 → 直到模型不再要工具。
 *
 * <p><b>所以你 迭代 2 写的 while 循环不是"过时的写法"，而是框架内部的实现。</b>
 * 这就是本轮要体会的：框架帮你藏起来的，恰恰是你手写过的那套循环。
 * 看穿这一点，你以后用任何框架都不会盲——你知道它里面在转什么。
 *
 * <h3>但本轮还有一个更重要的发现：框架拿走了你的拦截点</h3>
 * 下面跑了两个任务：一个只读、一个破坏性。<b>两次调用代码完全一样。</b>
 * 破坏性那个会真的执行——因为循环在框架肚子里跑，
 * <b>你手写的那个「执行工具之前」的位置，不存在了。</b>
 *
 * <p>对比 迭代 5：那边同一个场景被权限门拦住了，因为循环是我手写的，
 * 我可以在执行前插一道检查。
 *
 * <p><b>这就是用框架的真实代价。</b>省事是真的，但你得改用它的扩展点来插入自己的控制。
 * 怎么做留到 迭代 21（SQL 安全边界）——那时候这个问题会更紧迫，因为要防的是真实数据。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp6.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp6.enabled", havingValue = "true")
public class SpringAiAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentLoop.class);

    /**
     * 工具类。用 {@code @Tool} 注解声明，不需要手写 JSON Schema。
     *
     * <p>对比 迭代 2：那边我手写了一大段 {@code TOOLS_JSON}（name / description / parameters
     * 全得自己拼）。这里一个注解，参数描述用 {@code @ToolParam}。
     */
    static class DemoTools {

        private static final Logger log = LoggerFactory.getLogger(DemoTools.class);

        @Tool(description = "查询指定城市当前的天气情况。用户问天气时必须调用这个工具。")
        public String getWeather(
                @ToolParam(description = "城市名，例如 北京、上海") String city) {

            log.info("        >> [工具被调用] getWeather(city={})", city);
            return switch (city) {
                case "北京" -> "北京：小雨，气温 18℃，湿度 88%，有降水";
                case "上海" -> "上海：多云，气温 24℃，湿度 60%，无降水";
                default -> city + "：晴，气温 25℃，湿度 50%，无降水";
            };
        }

        /**
         * 一个破坏性工具。它的存在是为了演示「框架不给你拦截点」这件事。
         *
         * <p>注意它的实现里<b>没有任何权限检查</b>——因为在这个写法下，
         * 想加也不知道加在哪：工具是被框架反射调用的，中间没有你自己的代码。
         */
        @Tool(description = "永久删除订单数据。操作不可恢复。")
        public String deleteOrders(
                @ToolParam(description = "要删除的订单号，多个用逗号分隔") String orderIds) {

            log.info("        >> [工具被调用] deleteOrders(orderIds={})", orderIds);
            log.warn("        !! 这个破坏性操作真的执行了 —— 我们的代码全程没有机会拦它");
            return "已永久删除：" + orderIds;
        }
    }

    @Bean
    ApplicationRunner exp6SpringAiLoop(ChatClient.Builder chatClientBuilder) {
        return args -> {
            ChatClient chat = chatClientBuilder.build();
            DemoTools tools = new DemoTools();

            log.info("========== 迭代 6 · Spring AI 版 Agent 循环 ==========");
            log.info("");
            log.info("先记住：下面两次调用，我们的代码【一字不差】，都是同一句");
            log.info("    chat.prompt(问题).tools(tools).call().content()");
            log.info("没有 while，没有解析 tool_calls，没有手动回灌。");

            // ================= 运行 A：只读任务 =================
            log.info("");
            log.info("================= 运行 A：只读任务 =================");
            String q1 = "北京今天天气怎么样？";
            log.info("提问：{}", q1);
            log.info("我们的代码发出请求，然后……我们就没有代码了。");

            String a1 = chat.prompt(q1).tools(tools).call().content();

            log.info("");
            log.info("模型最终回答：{}", a1);
            log.info("");
            log.info("回头看这段：从 .call() 到拿到答案，我们一行代码都没写。");
            log.info("但上面出现了「>> [工具被调用] getWeather」——说明中间确实发生了：");
            log.info("   模型要工具 -> 框架反射调用 getWeather -> 结果回灌 -> 再问一次模型");
            log.info("这四步就是 迭代 2 你手写的那个 while 循环，现在跑在框架肚子里。");

            // ================= 运行 B：破坏性任务 =================
            log.info("");
            log.info("================= 运行 B：破坏性任务 =================");
            String q2 = "把订单 A1001 和 A1002 永久删除。";
            log.info("提问：{}", q2);
            log.info("注意：接下来的调用和运行 A 一模一样，没有任何权限检查的代码。");

            String a2 = chat.prompt(q2).tools(tools).call().content();

            log.info("");
            log.info("模型最终回答：{}", a2);
            log.info("");
            log.warn("=========== 请对照上面的日志 ===========");
            log.warn("出现了「>> [工具被调用] deleteOrders」——它真的执行了，没有任何东西拦住它。");
            log.warn("");
            log.warn("对比 迭代 5：同一个删除场景，那边被权限门拦住了。");
            log.warn("差别在哪？迭代 5 的循环是我手写的，我能在执行工具之前插一道检查；");
            log.warn("本轮的循环在框架肚子里跑，那个位置【不存在了】。");
            log.warn("");
            log.warn("这不是说框架不好 —— 是说用框架时，你失去了什么，得心里有数。");
            log.warn("要重新拿回控制权，得改用框架提供的扩展点（迭代 21 讲）。");
        };
    }
}
