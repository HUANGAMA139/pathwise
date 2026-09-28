package com.example.smartdataqa.agent;

import com.example.smartdataqa.context.ContextCompactor;
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
 * 迭代 4 · 上下文与记忆演示。
 *
 * <p><b>这个演示不只测「token 有没有降下来」，更测「信息有没有丢」。</b>
 * 减少 token 很容易——直接砍掉历史就行，但那样 Agent 就失忆了。
 * 压缩的真正难点是：<b>用更少的 token 保住关键信息。</b>
 *
 * <p>做法：让用户在 15 轮里陆陆续续说出 11 个条件（预算、不吃辣、不喜欢爬山……），
 * 这些条件散落在很早的消息里。然后问最后一个问题——
 * 「根据我上面说的所有条件，帮我排个行程」。
 * <b>如果压缩把早期条件丢了，最后这个回答就会露馅。</b>
 *
 * <p>同时每轮打印服务端返回的真实 {@code prompt_tokens}（不是估算，是它实际计费的数），
 * 结尾画一张走势图，你就能看到压缩之后曲线是平下去还是继续往上冲。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp4.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp4.enabled", havingValue = "true")
public class MemoryDemo {

    private static final Logger log = LoggerFactory.getLogger(MemoryDemo.class);

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen-plus";

    /** 超过 14 条消息就压缩，最近 6 条永远保留。 */
    private static final int MAX_MESSAGES = 14;
    private static final int KEEP_RECENT = 6;

    private static final String SYSTEM_PROMPT = "你是一个旅行规划助手，回答简洁务实，不要长篇大论。";

    /**
     * 15 轮对话。条件故意打散、一次只说一个——
     * 越分散，越能考验摘要有没有把关键信息捞住。
     */
    private static final List<String> TURNS = List.of(
            "我下个月想去杭州玩三天。",
            "预算大概 5000 块。",
            "我不喜欢爬山，太累了。",
            "跟我一起去的还有我女朋友。",
            "她特别喜欢拍照。",
            "我们想住得离西湖近一点。",
            "交通打算坐高铁去。",
            "对了，我不吃辣。",
            "想尝尝当地的特色小吃。",
            "三天里想安排一天去博物馆。",
            "我们起得比较晚，早上 10 点前不想安排活动。",
            "我女朋友对猫过敏，所以要避开猫咖这类地方。",
            "想找一天去周边的古镇看看。",
            "预算里包含住宿和吃饭，但不含来回的高铁票。",
            "希望行程别太赶，留点闲逛的时间。");

    /** 最后的检验题：答案里应该体现出上面 11 个条件。 */
    private static final String FINAL_QUESTION =
            "根据我上面说的所有条件，帮我排一个三天的行程，并说明你是怎么用到我提到的这些条件的。";

    /**
     * 粗略检查答案覆盖了哪些条件。
     *
     * <p><b>注意：这东西只是个提示，不是合格的评测。</b>
     * 上一次跑它就误判过一次——条件写的是「10 点」，模型写的是「10点」，
     * 中间少一个空格就判成「缺失」。所以每个条件配了几个别名，减少这类误伤。
     *
     * <p>但它有个<b>修不好的根本缺陷</b>：关键词匹配分不清
     * 「模型把某件事当成用户的要求」和「模型只是建议了某件事」。
     * 演示里模型声称用户要「3 家湖景咖啡馆」，而关键词检查完全抓不到——
     * 因为「咖啡馆」这三个字出现了，检查就认为没问题。
     * 要抓这类错误，得让模型来当裁判（迭代 23 会做），关键词做不到。
     */
    private static final Map<String, String[]> CHECKLIST = new LinkedHashMap<>();

    static {
        CHECKLIST.put("预算 5000", new String[]{"5000", "5,000", "五千", "5千"});
        CHECKLIST.put("不喜欢爬山", new String[]{"爬山", "登山"});
        CHECKLIST.put("女朋友拍照", new String[]{"拍照", "出片", "机位"});
        CHECKLIST.put("住西湖附近", new String[]{"西湖"});
        CHECKLIST.put("坐高铁", new String[]{"高铁"});
        CHECKLIST.put("不吃辣", new String[]{"辣"});
        CHECKLIST.put("特色小吃", new String[]{"小吃"});
        CHECKLIST.put("博物馆", new String[]{"博物馆", "浙博"});
        CHECKLIST.put("10 点后活动", new String[]{"10点", "10 点", "十点", "10:00"});
        CHECKLIST.put("避开猫", new String[]{"猫"});
        CHECKLIST.put("周边古镇", new String[]{"古镇"});
    }

    /** 一次模型调用的结果。prompt_tokens 是服务端真实统计的，不是估算。 */
    private record ChatResult(String content, int promptTokens) {
    }

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.create();

    private final List<Integer> tokenTrail = new ArrayList<>();
    private int compactionCount = 0;

    @Bean
    ApplicationRunner exp4MemoryDemo() {
        return args -> {
            ContextCompactor compactor = new ContextCompactor(this::summarize, MAX_MESSAGES, KEEP_RECENT);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(msg("system", SYSTEM_PROMPT));

            log.info("========== 迭代 4 上下文压缩演示 ==========");
            log.info("阈值：消息超过 {} 条触发压缩，最近 {} 条不动", MAX_MESSAGES, KEEP_RECENT);
            log.info("");

            // ---------- 前 15 轮：陆陆续续说条件 ----------
            for (int turn = 1; turn <= TURNS.size(); turn++) {
                String question = TURNS.get(turn - 1);
                messages.add(msg("user", question));

                messages = maybeCompact(compactor, messages);

                ChatResult result = callModel(messages);
                messages.add(msg("assistant", result.content()));
                tokenTrail.add(result.promptTokens());

                log.info("第 {} 轮 | 消息 {} 条 | prompt_tokens = {} | 用户：{}",
                        pad(String.valueOf(turn), 4), pad(String.valueOf(messages.size()), 4),
                        pad(String.valueOf(result.promptTokens()), 6), shorten(question, 22));
            }

            // ---------- 最后一轮：检验信息有没有丢 ----------
            log.info("");
            log.info("================ 检验：早期条件还在不在 ================");
            messages.add(msg("user", FINAL_QUESTION));
            messages = maybeCompact(compactor, messages);

            ChatResult finalResult = callModel(messages);
            messages.add(msg("assistant", finalResult.content()));
            tokenTrail.add(finalResult.promptTokens());

            log.info("最终提问：{}", FINAL_QUESTION);
            log.info("消息 {} 条 | prompt_tokens = {}", messages.size(), finalResult.promptTokens());
            log.info("");
            log.info("---------- 模型的最终回答 ----------");
            log.info("{}", finalResult.content());
            log.info("------------------------------------");

            // ---------- 自动粗查：11 个条件提到了几个 ----------
            log.info("");
            log.info("================ 条件覆盖粗查 ================");
            String answer = finalResult.content();
            int hit = 0;
            for (Map.Entry<String, String[]> e : CHECKLIST.entrySet()) {
                boolean found = false;
                for (String alias : e.getValue()) {
                    if (answer.contains(alias)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    hit++;
                }
                log.info("  [{}] {}", found ? "命中" : "缺失", e.getKey());
            }
            log.info("  → 11 个条件命中 {} 个", hit);
            log.info("  （关键词匹配只能当提示。它抓不到「模型把建议说成用户要求」这类错，");
            log.info("   也抓不到编造——凡是关键词出现的，它一律认为没问题。）");

            // ---------- token 走势 ----------
            log.info("");
            log.info("================ prompt_tokens 走势 ================");
            printTrail();
            log.info("");
            log.info("共压缩 {} 次。如果曲线在中途「平下去」再缓慢爬升，说明压缩有效；", compactionCount);
            log.info("如果一路直线上冲，说明阈值或保留条数需要调。");
        };
    }

    /** 需要就压，并打日志。 */
    private List<Map<String, Object>> maybeCompact(ContextCompactor compactor,
                                                   List<Map<String, Object>> messages) {
        if (!compactor.needsCompaction(messages)) {
            return messages;
        }
        int before = messages.size();
        List<Map<String, Object>> compacted = compactor.compact(messages);
        if (compacted.size() < before) {
            compactionCount++;
            log.info("  >>> 触发第 {} 次压缩：{} 条 → {} 条",
                    compactionCount, before, compacted.size());
        }
        return compacted;
    }

    /**
     * 摘要实现。本轮由「自己发 HTTP」提供；
     * 迭代 6 换成 Spring AI 后只要换掉这个 lambda，ContextCompactor 不用动。
     *
     * <p><b>注意提示词里那条「只记录用户说过的内容」</b>——它不是客套话。
     * 不加这条时，模型会把<b>自己之前的建议</b>也写进摘要，之后又把这些建议
     * 当成用户的要求。这正是演示里「模型声称用户要 3 家咖啡馆」那类幻觉的来源。
     */
    private String summarize(List<Map<String, Object>> messagesToSummarize) {
        List<Map<String, Object>> prompt = new ArrayList<>();
        prompt.add(msg("system", """
                下面是一段较早的对话记录。请把它压缩成简洁摘要，遵守以下规则：

                1. 只记录【用户】明确说过的内容——偏好、条件、数字、时间限制。
                2. 助手自己的推荐、建议、举例，一律不要写进摘要，也不要用它们
                   推断用户的意图。用户没说过的东西，摘要里就不能出现。
                3. 数字必须原样保留（金额、时间点、数量），不要概括成「有预算限制」这种说法。
                4. 保留限定条件，例如「预算 5000，但不含来回高铁票」里的后半句。
                5. 记下还没解决的问题。

                只输出摘要正文，不要任何前缀、标题或解释。
                """));
        prompt.add(msg("user", renderHistory(messagesToSummarize)));

        try {
            ChatResult r = callModel(prompt);
            // 打印摘要：这是诊断「信息丢了还是被编造了」的唯一依据，别省这一步
            log.info("  >>> 摘要内容：{}", r.content());
            return r.content();
        }
        catch (Exception e) {
            log.error("  >>> 摘要调用失败：{}（这次放弃压缩）", e.getMessage());
            return null;
        }
    }

    /** 把待压缩的消息拼成一段文本给摘要模型看。 */
    private String renderHistory(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            sb.append('[').append(m.get("role")).append("] ")
              .append(m.get("content")).append('\n');
        }
        return sb.toString();
    }

    /** 发一次请求，返回内容 + 服务端统计的 prompt_tokens。 */
    private ChatResult callModel(List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", messages);
        body.put("temperature", 0.3);

        String raw = http.post()
                .uri(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = mapper.readTree(raw);
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        String text = (content.isMissingNode() || content.isNull()) ? "" : content.asText();

        int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
        return new ChatResult(text, promptTokens);
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 按显示宽度补空格。
     *
     * <p>注意：这里之所以要手动补，是因为 SLF4J 只认 {@code {}} 占位符，
     * 不支持 {@code String.format} 那套 {@code %-6d} / {@code {:>4}} 对齐语法。
     * 如果你想让日志自带对齐，得先 format 好再当参数传进来，
     * 或者用 logback 的 format modifier（那个支持中文宽度，但配置更绕）。
     */
    private String pad(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return " ".repeat(width - s.length()) + s;
    }

    /** 用方块画一张 token 走势图，一眼看趋势。 */
    private void printTrail() {
        int max = tokenTrail.stream().mapToInt(Integer::intValue).max().orElse(1);
        for (int i = 0; i < tokenTrail.size(); i++) {
            int v = tokenTrail.get(i);
            int bar = (int) Math.round(v * 40.0 / max);
            String label = (i < TURNS.size()) ? ("第 " + (i + 1) + " 轮") : "最终提问";
            log.info("  {} |{}| {}", padLabel(label, 12), "█".repeat(Math.max(bar, 1)), v);
        }
    }

    /**
     * 粗略估算字符串的显示宽度：中日韩字符占两列，其余占一列。
     *
     * <p>为什么需要这个？因为 {@code String.length()} 数的是<b>字符个数</b>，
     * 不是屏幕占几列。「第 1 轮」是 5 个字符，但占了 7 列。
     * 直接按 length 补空格，画出来的柱状图就是歪的。
     * 这个坑在任何要「对齐中文」的地方都会遇到。
     */
    private int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += (s.charAt(i) >= 0x2E80) ? 2 : 1;
        }
        return w;
    }

    /** 左对齐到指定显示宽度（标签在左，空格补在右）。 */
    private String padLabel(String s, int width) {
        int w = displayWidth(s);
        return w >= width ? s : s + " ".repeat(width - w);
    }
}
