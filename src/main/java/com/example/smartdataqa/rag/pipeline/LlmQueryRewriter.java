package com.example.smartdataqa.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 迭代 26 · <b>改写器的「llm」档</b>：用模型把口语化的问题改写成更适合检索的查询。
 *
 * <h3>它要补的是什么</h3>
 * 用户说「退了货以后钱什么时候从销售额里减掉」，文档里写「退货金额扣减时点」——
 * 两句话**没有一个共同的 2-gram 长的词**，BM25 抓不住；向量虽然能抓到一些，
 * 但改写一遍能让两边的用词更接近（迭代 15 那批低重合题正是为了量这件事）。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>失败退回原问题</b>，不让链路挂掉（同路由/表筛选的降级处理），并且<b>打日志</b>。</li>
 *   <li><b>同一问题只改写一次（记忆化）</b>。改写只跟问题有关、跟下游配置无关，
 *       所以消融实验里 9 个格子可以共用一次改写——否则 15 道题 × 9 格 = 135 次调用，
 *       白白多花 9 倍的钱。这不是优化，是<b>量纲错了</b>：本该量「改写这一层值多少」，
 *       却把它的成本乘了 9 倍。</li>
 *   <li><b>temperature=0</b>：改写要的是稳定复现，不是发挥。</li>
 * </ol>
 */
public final class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。下面这句话要拿去做【文档检索】，请把它改写成一句更适合检索的查询。

            要求：
            1. 只输出改写后的查询本身。不要解释、不要加引号、不要输出 markdown。
            2. 保留问题里的关键实体：区域名、产品型号、时间范围、指标名。
            3. 把口语化的说法换成文档里更可能出现的书面说法
               （例如「退了货」→「退货」、「钱什么时候减掉」→「退货金额扣减时点」）。
            4. 不要回答问题，也不要添加问题里没有的信息。
            """;

    private final ChatClient chatClient;
    /** 记忆化：同一问题只改写一次。见类注释第 2 条。 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public LlmQueryRewriter(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String rewrite(String question) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String cached = this.cache.get(question);
        if (cached != null) {
            return cached;
        }
        try {
            String raw = this.chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question)
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
            String rewritten = clean(raw);
            if (rewritten.isBlank()) {
                log.warn("      [改写] 模型给了空结果，退回原问题：{}", question);
                return question;
            }
            this.cache.put(question, rewritten);
            log.info("      [改写] {}  →  {}", question, rewritten);
            return rewritten;
        }
        catch (RuntimeException e) {
            // 改写是优化不是必需步骤：挂了就退回原问题，链路照走（同 迭代 19 表筛选降级）
            log.warn("      [改写] 调用失败，退回原问题：{}", e.getMessage());
            return question;
        }
    }

    /** 剥掉模型爱加的引号/反引号/换行。它很爱把结果包一层。 */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        // 只取第一段非空行（模型偶尔会多写一句解释）
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                s = t;
                break;
            }
        }
        s = s.replace("`", "").trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\""))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    @Override
    public String name() {
        return "llm";
    }
}
