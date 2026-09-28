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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 3 · ReAct：把「想」和「做」拆开。
 *
 * <p><b>本轮和 迭代 2 最大的不同：不用原生 tool_calls 了，改用纯文本格式。</b>
 *
 * <p>迭代 2 里模型输出的是结构化的 {@code tool_calls} 字段，框架/服务端帮你保证了格式。
 * 本轮改成让模型输出一段<b>约定好的文本</b>，然后我自己用正则去解析：
 * <pre>
 *   Thought: 我需要先查一下北京的天气
 *   Action: get_weather
 *   Action Input: 北京
 * </pre>
 * 我执行完工具，再把结果作为 {@code Observation: ...} 拼回去，循环继续。
 * 当模型觉得可以回答了，就改用：
 * <pre>
 *   Thought: 我已经知道天气了
 *   Final Answer: 北京本轮小雨，记得带伞
 * </pre>
 *
 * <p><b>为什么要绕这一圈？</b>因为「显式写出推理」这件事本身就值钱：
 * <ul>
 *   <li>你能看到它为什么这么选——出问题时知道它「在想什么」（可解释性）</li>
 *   <li>模型把推理写出来之后，下一步决策往往更准</li>
 * </ul>
 * 这也是 ReAct 论文的核心做法。你项目里后面要做路由决策，「能说清为什么选这条路」是硬需求。
 *
 * <p><b>但这条路有个明确的代价，你要亲身体会一下：格式很脆。</b>
 * 模型可能用中文冒号、可能自己加上 Observation、可能包一层 markdown 代码块、
 * 可能一次输出两个 Action。所以我底下写了一个容错的 parser，
 * 并且在解析失败时把原文打出来——那个失败现场，就是你理解
 * 「为什么后来大家都改用原生 tool_calls」的最好材料。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp3.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp3.enabled", havingValue = "true")
public class ReActAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentLoop.class);

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen-plus";
    private static final int MAX_STEPS = 8;

    /**
     * 两步任务：必须先查天气，才知道要不要带伞。
     * 模型没法一步做完，所以你能看到两轮 Thought + 两次 Action。
     */
    private static final String USER_QUESTION = "北京今天天气怎么样？如果下雨就提醒我带伞。";

    /**
     * ReAct 的格式约定，靠这段 system prompt 立规矩。
     * 注意：这些字全部会成为模型的提示词，所以「严格」「不要输出其它内容」这类话必须写。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个会用工具解决问题的助手。

            你可以使用以下两个工具：

            1. get_weather
               作用：查询指定城市当前的天气
               输入：城市名，例如 北京

            2. recommend_umbrella
               作用：根据是否需要带伞给出出行建议
               输入：yes 或 no

            你必须严格按下面的格式回答，每次只输出一个步骤，不要输出其它任何内容：

            Thought: 你的推理过程
            Action: 工具名
            Action Input: 工具输入

            当你已经能回答用户时，把最后一步换成：

            Thought: 你的推理过程
            Final Answer: 给用户的最终回答

            注意：Observation 是工具返回的结果，由我提供给你，你不要自己编造 Observation。
            """;

    private static final Pattern P_THOUGHT = Pattern.compile("Thought\\s*[:：]");
    private static final Pattern P_ACTION = Pattern.compile("Action\\s*[:：]");
    private static final Pattern P_INPUT = Pattern.compile("Action\\s*Input\\s*[:：]");
    private static final Pattern P_FINAL = Pattern.compile("Final\\s*Answer\\s*[:：]");

    /** 解析出来的一步。三个字段哪个有值，就说明模型这一步想干什么。 */
    private record Step(String thought, String action, String actionInput, String finalAnswer) {
    }

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.create();

    @Bean
    ApplicationRunner exp3ReActLoop() {
        return args -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", USER_QUESTION));

            log.info("========== 迭代 3 ReAct：显式推理循环 ==========");
            log.info("用户提问：{}", USER_QUESTION);

            for (int round = 1; round <= MAX_STEPS; round++) {
                log.info("");
                log.info("=============== 第 {} 轮 ===============", round);

                String reply = callModel(messages);

                // 助手这一轮的整段文本原样存回历史。
                // 它自己写的 Thought 也要留着——不然下一轮它看不见自己刚才想了什么。
                messages.add(Map.of("role", "assistant", "content", reply));

                Step step = parse(reply);

                if (step.thought() != null && !step.thought().isBlank()) {
                    log.info("[想] Thought: {}", step.thought());
                }

                // ---- 出口一：模型认为可以回答了 ----
                if (step.finalAnswer() != null) {
                    log.info("[答] Final Answer: {}", step.finalAnswer());
                    log.info("");
                    log.info("========== 循环结束，共 {} 轮 ==========", round);
                    return;
                }

                // ---- 解析失败：把原文打出来，这是理解格式脆弱性的第一现场 ----
                if (step.action() == null) {
                    log.warn("[!] 模型输出不符合 ReAct 格式，没法解析。原文如下：");
                    log.warn("--------------------------------------------------");
                    log.warn(reply);
                    log.warn("--------------------------------------------------");
                    log.warn("这就是「靠文本约定格式」的代价：模型一旦不守规矩，循环就断了。");
                    log.warn("对比 迭代 2 的原生 tool_calls —— 那种方式由服务端保证结构，不存在这个问题。");
                    return;
                }

                // ---- 出口二：执行工具，把 Observation 回灌 ----
                log.info("[做] Action: {}  |  Action Input: {}", step.action(), step.actionInput());

                String observation = runTool(step.action(), step.actionInput());
                log.info("[看] Observation: {}", observation);

                // 文本式 ReAct 里，工具结果就是作为一轮「用户消息」塞回去的
                messages.add(Map.of("role", "user", "content", "Observation: " + observation));
            }

            log.warn("达到最大轮数 {} 仍未结束，强制停下", MAX_STEPS);
        };
    }

    /** 发 HTTP 请求，拿回助手这一轮的整段文本。 */
    private String callModel(List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", messages);
        // 注意：这里【不传 tools】。本轮靠的是文本约定，不是原生工具调用。
        body.put("temperature", 0);

        String raw = http.post()
                .uri(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode message = mapper.readTree(raw).path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        return (content.isMissingNode() || content.isNull()) ? "" : content.asText();
    }

    // ------------------------------------------------------------------
    // 解析：把模型输出的一段自由文本，拆成结构化的 Step
    // ------------------------------------------------------------------

    private Step parse(String raw) {
        String text = normalize(raw);
        String finalAnswer = section(text, P_FINAL);

        // Thought 的结束位置可能是 Final Answer / Action / Action Input 中最早出现的那个
        String thought = section(text, P_THOUGHT, P_FINAL, P_ACTION, P_INPUT);
        String action = section(text, P_ACTION, P_INPUT, P_FINAL);
        String actionInput = section(text, P_INPUT, P_FINAL);

        return new Step(thought, action, actionInput, finalAnswer);
    }

    /**
     * 清洗模型输出。这三种情况几乎每次都会遇到一两个：
     * <ul>
     *   <li>包了一层 markdown 代码块 ```...```</li>
     *   <li>首尾有多余空行</li>
     *   <li>模型自作主张把 Observation 也写出来了（要砍掉）</li>
     * </ul>
     */
    private String normalize(String raw) {
        String t = raw.replace("```", "");
        // 模型有时候会模仿格式，自己编一个 Observation，从那里截断
        int obs = t.indexOf("Observation:");
        if (obs >= 0) {
            t = t.substring(0, obs);
        }
        return t.strip();
    }

    /**
     * 取出某个关键字后面的内容，遇到任何一个 end 关键字就停。
     *
     * <p>用「最早出现的那个 end」而不是固定顺序，是为了容忍模型偶尔把
     * Action 写在 Thought 前面这类小偏差。
     */
    private String section(String text, Pattern start, Pattern... ends) {
        Matcher m = start.matcher(text);
        if (!m.find()) {
            return null;
        }
        int from = m.end();
        int to = text.length();
        for (Pattern e : ends) {
            Matcher em = e.matcher(text);
            if (em.find(from)) {
                to = Math.min(to, em.start());
            }
        }
        String out = text.substring(from, to).strip();
        return out.isEmpty() ? null : out;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String runTool(String name, String input) {
        return switch (name.strip()) {
            case "get_weather" -> fakeWeather(input.strip());
            case "recommend_umbrella" -> umbrellaAdvice(input.strip());
            default -> "错误：没有名为 " + name + " 的工具";
        };
    }

    /**
     * 假天气数据。故意把北京设成下雨，好让第二步的带伞判断真的被触发——
     * 不然这个两步任务就看不出依赖关系了。
     */
    private String fakeWeather(String city) {
        return switch (city) {
            case "北京" -> "北京：小雨，气温 18℃，湿度 88%，有降水";
            case "上海" -> "上海：多云，气温 24℃，湿度 60%，无降水";
            case "广州" -> "广州：雷阵雨，气温 28℃，湿度 80%，有降水";
            default -> city + "：晴，气温 25℃，湿度 50%，无降水";
        };
    }

    private String umbrellaAdvice(String yesOrNo) {
        return switch (yesOrNo.toLowerCase()) {
            case "yes", "是", "true" -> "本轮有降水，建议带伞。";
            case "no", "否", "false" -> "本轮没有降水，不用带伞。";
            default -> "无法判断，输入应为 yes 或 no。";
        };
    }
}
