package com.example.smartdataqa.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

/**
 * 迭代 28 · 把 {@link ContextCompactor.Summarizer} 换成 <b>Spring AI 实现</b>。
 *
 * <p><b>这个类本身说明了一件事</b>：迭代 4 写 {@code ContextCompactor} 时把摘要能力抽成了
 * 函数式接口（当时是自己发 HTTP），并写下一句「迭代 6 换成 Spring AI 之后，只要换一个 lambda，
 * 这个类一行都不用改」。**本轮兑现了**——{@code ContextCompactor} 一个字没改，只多了一个实现。
 *
 * <h3>两条纪律</h3>
 * <ol>
 *   <li><b>摘要必须保真</b>：提示词里明确「不要加入原文没有的信息」。
 *       摘要加入原文没有的东西，等于往历史里塞幻觉，而且它以「已知背景」的身份进入后续每一轮。</li>
 *   <li><b>temperature=0</b>：同一段历史两次压出不同摘要，评测就没法复现。</li>
 * </ol>
 */
public class ChatSummarizer implements ContextCompactor.Summarizer {

    private static final Logger log = LoggerFactory.getLogger(ChatSummarizer.class);

    private static final String SYSTEM_PROMPT = """
            你在压缩一段多轮对话的历史。请把它压成一段简短的摘要。

            必须保留：
            1. 用户问过哪些问题（特别是【被后续问题指代的那个对象】——比如「客单价」）；
            2. 得到的【关键结论与口径】（例如「分母只计状态为 COMPLETED 的订单」）；
            3. 出现过的【关键数字】。

            禁止：不要加入原文没有的信息。拿不准的一律不写。
            """;

    private final ChatClient chatClient;

    public ChatSummarizer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String summarize(List<Map<String, Object>> messagesToSummarize) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messagesToSummarize) {
            Object role = m.get("role");
            Object content = m.get("content");
            if (content == null) {
                continue;
            }
            sb.append(role).append("：").append(content).append('\n');
        }
        try {
            String out = this.chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("【要压缩的历史】\n" + sb)
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
            String summary = out == null ? "" : out.trim();
            log.info("      [压缩] {} 条消息 → {} 字摘要", messagesToSummarize.size(), summary.length());
            return summary;
        }
        catch (RuntimeException e) {
            // 压不了就返回空 —— ContextCompactor 见到空摘要会放弃这次压缩（宁可多花 token 也不丢信息）
            log.warn("      [压缩] 摘要调用失败，放弃本次压缩：{}", e.getMessage());
            return "";
        }
    }
}
