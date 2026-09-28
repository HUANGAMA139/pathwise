package com.example.smartdataqa.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 2 · 手写 Agent 循环 —— 整个 30 天里最重要的一天。
 *
 * <p><b>这里刻意不用任何 Agent 框架。</b>不用 Spring AI 的 ChatClient、不用 @Tool、
 * 不用 Spring AI Alibaba 的 ReactAgent。只有一个 HTTP 调用 + 一个 while 循环。
 *
 * <p>目的：把框架平时帮你藏起来的东西摊开看一遍。
 * 等 迭代 6 换成 Spring AI 时，你会立刻认出「原来它做的就是这件事」。
 *
 * <p>这个循环的结构（所有 Agent 框架都是它的变体）：
 * <pre>
 *   把消息列表发给模型
 *        ↓
 *   模型返回消息。它可能要求调用工具（tool_calls），也可能直接回答
 *        ↓
 *   如果要求调工具 → 执行工具 → 把结果作为一条新消息塞回列表 → 回到第一步
 *   如果没要求     → 循环结束，这条消息就是最终回答
 * </pre>
 *
 * <p>关键认知：<b>循环的骨架是我写死的，但走哪条分支不是。</b>
 * 什么时候调工具、调哪个、调几次，全由模型在运行时决定。
 *
 * <p>开启方式（默认关闭）：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp2.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp2.enabled", havingValue = "true")
public class MinimalAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(MinimalAgentLoop.class);

    /**
     * 阿里云百炼的 OpenAI 兼容地址（华北2·北京）。
     * 官方 Base URL 总览确认：这个域名支持跨业务空间的 API Key，不需要 WorkspaceId。
     * 见 https://help.aliyun.com/zh/model-studio/base-url
     */
    private static final String API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /** qwen-plus：便宜、稳、支持函数调用，适合学习阶段反复试。 */
    private static final String MODEL = "qwen-plus";

    /** 防止模型犯轴无限调工具——生产环境必须有这种上限。 */
    private static final int MAX_STEPS = 8;

    private static final String SYSTEM_PROMPT = """
            你是一个助手。当用户询问天气时，你必须调用 get_weather 工具去取数据，
            绝对不要凭记忆或猜测回答天气问题。
            """;

    private static final String USER_QUESTION = "北京今天天气怎么样？";

    /**
     * 工具定义。注意这是给<b>模型看的说明书</b>，不是给我自己看的代码——
     * 模型靠 name / description / parameters 这三样判断「这个问题我该不该调它、怎么传参」。
     */
    private static final String TOOLS_JSON = """
            [
              {
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "description": "查询指定城市当前的天气情况。用户问天气时必须调用。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "city": {
                        "type": "string",
                        "description": "城市名，例如 北京、上海"
                      }
                    },
                    "required": ["city"]
                  }
                }
              }
            ]
            """;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Bean
    ApplicationRunner exp2MinimalAgentLoop() {
        return args -> {
            JsonNode tools = mapper.readTree(TOOLS_JSON);

            // 消息列表 —— 整个 Agent 的「记忆」就是这个，没有别的
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", USER_QUESTION));

            log.info("========== 迭代 2 手写 Agent 循环 ==========");
            log.info("用户提问：{}", USER_QUESTION);
            log.info("初始消息列表：{} 条", messages.size());

            for (int step = 1; step <= MAX_STEPS; step++) {
                log.info("");
                log.info("----------- 第 {} 轮：把 {} 条消息发给模型 -----------", step, messages.size());

                JsonNode message = callModel(messages, tools);

                // 助手这一轮说的话原样存回历史。它可能包含 tool_calls，
                // 而下一轮请求必须把 tool_calls 一起带上，否则服务端会因为
                // 「后面的 tool 消息找不到对应的请求」而报 400。
                messages.add(toAssistantMessage(message));

                JsonNode toolCalls = message.path("tool_calls");

                // ↓↓↓ 循环的出口：模型没要求调工具，说明它认为可以答了 ↓↓↓
                if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                    log.info("模型没有要求调用工具 → 循环结束");
                    log.info("");
                    log.info("模型最终回答：{}", text(message, "content"));
                    log.info("========== 循环正常退出，共 {} 轮 ==========", step);
                    return;
                }

                log.info("模型要求调用 {} 个工具", toolCalls.size());

                for (JsonNode call : toolCalls) {
                    String callId = call.path("id").asText();
                    String toolName = call.path("function").path("name").asText();
                    String argsJson = call.path("function").path("arguments").asText();

                    log.info("  → 决定调用工具 [{}]，参数 {}", toolName, argsJson);

                    String result = runTool(toolName, argsJson);
                    log.info("  ← 工具执行结果：{}", result);

                    // 把工具结果作为一条 role=tool 的消息塞回去。
                    // tool_call_id 必须和上面那个 id 对上——这是模型分辨
                    // 「这个结果对应我刚才哪一次请求」的唯一依据。
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", result
                    ));
                }

                log.info("工具结果已回灌，消息列表变成 {} 条，进入下一轮", messages.size());
            }

            log.warn("达到最大轮数 {} 仍未结束，强制停下（生产环境必须有这个兜底）", MAX_STEPS);
        };
    }

    /** 真正发 HTTP 请求的地方。就这一处，非常简单。 */
    private JsonNode callModel(List<Map<String, Object>> messages, JsonNode tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", messages);
        body.put("tools", tools);

        String raw = http.post()
                .uri(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        return mapper.readTree(raw).path("choices").path(0).path("message");
    }

    /**
     * 模型这一轮的消息 → 可以塞回消息列表的 Map。
     *
     * <p>这里<b>显式只带上该带的三个字段</b>，而不是把服务端返回的对象原样转过去。
     * 原因：原样回传可能把一些只该出现在响应里的字段也发回去，部分服务端会报 400。
     * 而且显式写出来，你一眼就能看到「回灌的到底是什么」。
     *
     * <p>另一个坑：模型决定调工具时 content 往往是 null，而
     * {@code JsonNode.asText()} 对 null 节点会返回字符串 "null"。所以必须单独处理。
     */
    private Map<String, Object> toAssistantMessage(JsonNode message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "assistant");
        out.put("content", text(message, "content"));

        // tool_calls 必须原样带上。下一轮请求里如果没有它，后面的 role=tool 消息
        // 就会「找不到对应的请求」，服务端直接报 400。
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray() && !calls.isEmpty()) {
            out.put("tool_calls", mapper.convertValue(calls, List.class));
        }
        return out;
    }

    /** 安全取文本：字段不存在或为 null 时返回空串。 */
    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? "" : v.asText();
    }

    /**
     * 执行工具。现在只有一个，用 switch 就够；
     * 等工具多了应该做成 Map&lt;String, Function&gt; 或者干脆交给框架。
     */
    private String runTool(String name, String argsJson) {
        if (!"get_weather".equals(name)) {
            return "错误：没有名为 " + name + " 的工具";
        }
        try {
            String city = mapper.readTree(argsJson).path("city").asText();
            return fakeWeather(city);
        }
        catch (Exception e) {
            return "错误：解析工具参数失败 —— " + e.getMessage();
        }
    }

    /**
     * 假的天气数据。迭代 2 只关心循环通不通，不关心天气真不真——
     * 所以故意不接外部 API，避免把时间花在网络请求上。
     */
    private String fakeWeather(String city) {
        return switch (city) {
            case "北京" -> "北京：晴，气温 26℃，湿度 40%，无降水";
            case "上海" -> "上海：小雨，气温 22℃，湿度 85%，有降水";
            case "广州" -> "广州：多云，气温 30℃，湿度 70%，无降水";
            default -> city + "：晴，气温 25℃，湿度 50%，无降水";
        };
    }
}
