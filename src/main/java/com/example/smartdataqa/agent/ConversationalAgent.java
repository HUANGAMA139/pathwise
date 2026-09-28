package com.example.smartdataqa.agent;

import com.example.smartdataqa.context.ContextCompactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 28 · <b>多轮对话</b>：用一个「把指代消解掉」的改写，把多轮**接进原来那条单轮链路**。
 *
 * <h3>为什么不去改 SmartQaAgent</h3>
 * {@link SmartQaAgent} 是单轮的（路由 → 取证据 → 综合），而且**它是全项目最承重的一个类**
 * （MCP、迭代 15/23 的评测、迭代 24 的路由对比都用它）。动它 = 动所有历史基线。
 *
 * <p>所以换一条更便宜的路：<b>多轮的本质困难是「它 / 那个」这类指代</b>。
 * 用历史把问题改写成一句<b>不依赖历史也能看懂</b>的完整问题，再交给原来那条链路——
 * 于是多轮只多出一层「会话改写」，下面全部复用。迭代 26 刚建的那一层（改写器）在这里第二次派上用场。
 *
 * <h3>三档（就是本轮要 A/B 的三条）</h3>
 * <pre>
 *   无历史     history 全部不带 → 指代无法消解（省 token，但「它」永远是「它」）
 *   全量历史   改写时喂全部历史 → 能消解；但上下文随轮数线性增长
 *   压缩历史   超过阈值后由 ContextCompactor 压掉远处 → 能消解，且上下文被压住
 * </pre>
 *
 * <p><b>本类的诚实边界</b>：历史只参与「改写」这一步，<b>不参与最终综合</b>。
 * 所以它解决的是「指代消解」，不是「让模型记得上一轮说过什么细节」——
 * 后者要么把历史也喂给综合（会更贵），要么靠摘要（会有损）。本轮量的是前者。
 */
public class ConversationalAgent {

    private static final Logger log = LoggerFactory.getLogger(ConversationalAgent.class);

    /**
     * 一轮的结果。{@code standalone} 就是改写后那句自足的问题，是本轮的主要观测对象。
     *
     * <p><b>迭代 29 加了 {@code result}</b>（完整的一次问答对象：决策 / 片段 / SQL / 轨迹）——
     * 多轮接进 REST 之后，每一轮也要能回带轨迹，而不只是「答了什么」。
     * 加在最后一位，迭代 28 那几处读 {@code standalone()}/{@code contextChars()}/{@code compacted()}
     * 的地方一处都不用改；{@code answer}（文本）保留原样，因为那正是 迭代 28 在读的字段。</p>
     */
    public record Turn(int index,
                       String asked,
                       String standalone,
                       String answer,
                       int contextChars,
                       boolean compacted,
                       SmartQaAgent.Answer result) {
    }

    private static final String REWRITE_SYSTEM_PROMPT = """
            你在帮一个多轮对话系统做【会话改写】。

            下面会给【历史】和【最后一个用户问题】。最后那句里可能有「它 / 那个 / 这个指标 / 那呢」这类指代。
            请把它改写成一句【单独拿出来也能看懂】的完整问题——把被指代的**名词**明确写出来。

            要求：
            1. 只输出改写后的问题本身。不要解释、不要加引号。
            2. 不要回答问题，也不要加入历史里没有的信息。
            3. 如果原问题本来就没有指代，就原样返回。
            """;

    private final SmartQaAgent delegate;
    private final ChatClient chatClient;
    private final ContextCompactor compactor;
    private final boolean useHistory;

    /** 会话历史（OpenAI 那种 role/content 的形状，因为 ContextCompactor 认这个形状）。 */
    private List<Map<String, Object>> history = new ArrayList<>();

    /** 本轮有没有真的触发过压缩（报告里要报「压缩生效了几次」）。 */
    private int compactions = 0;

    /**
     * @param compactor {@code null} = 不压缩（压不压是这一档的变量，不是开关的一部分）
     * @param useHistory false = 不带动历史（三档里的「无历史」）
     */
    public ConversationalAgent(SmartQaAgent delegate, ChatClient chatClient,
                               ContextCompactor compactor, boolean useHistory) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.compactor = compactor;
        this.useHistory = useHistory;
        // 第 0 条必须是 system —— ContextCompactor 规定它永不压缩
        this.history.add(msg("system", "（多轮会话。下面的历史用于理解当前问题里的指代。）"));
    }

    public Turn ask(int index, String question) {
        String standalone = (this.useHistory && this.history.size() > 1)
                ? rewrite(question)
                : question;

        SmartQaAgent.Answer a;
        try {
            a = this.delegate.answer(standalone);
        }
        catch (RuntimeException e) {
            log.error("      [多轮] 第 {} 轮失败：{}", index, e.toString());
            a = null;
        }
        String answer = a == null || a.text() == null ? "" : a.text();

        this.history.add(msg("user", question));
        this.history.add(msg("assistant", answer));

        boolean compacted = false;
        if (this.compactor != null && this.compactor.needsCompaction(this.history)) {
            int before = this.history.size();
            List<Map<String, Object>> after = this.compactor.compact(this.history);
            this.history = new ArrayList<>(after);
            compacted = after.size() < before;
            if (compacted) {
                this.compactions++;
            }
        }
        // 报的是【压缩之后】的规模 —— 那才是下一轮真正要送进模型的东西
        return new Turn(index, question, standalone, answer, contextChars(), compacted, a);
    }

    /** 用历史把指代补全。失败就退回原问题（不让链路挂）。 */
    private String rewrite(String question) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : this.history) {
            if ("system".equals(m.get("role"))) {
                continue;
            }
            sb.append(m.get("role")).append("：").append(m.get("content")).append('\n');
        }
        try {
            String out = this.chatClient.prompt()
                    .system(REWRITE_SYSTEM_PROMPT)
                    .user("【历史】\n" + sb + "\n【最后一个用户问题】\n" + question)
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
            String s = out == null ? "" : out.trim();
            for (String line : s.split("\\R")) {
                if (!line.isBlank()) {
                    s = line.trim();
                    break;
                }
            }
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1).trim();
            }
            return s.isBlank() ? question : s;
        }
        catch (RuntimeException e) {
            log.warn("      [多轮] 会话改写失败，退回原问题：{}", e.getMessage());
            return question;
        }
    }

    /** 当前历史占多少字符（token 的粗代理；真要精确得用 tokenizer，本轮不需要）。 */
    public int contextChars() {
        int n = 0;
        for (Map<String, Object> m : this.history) {
            Object c = m.get("content");
            n += c == null ? 0 : String.valueOf(c).length();
        }
        return n;
    }

    public int compactions() {
        return this.compactions;
    }

    public int historySize() {
        return this.history.size();
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
