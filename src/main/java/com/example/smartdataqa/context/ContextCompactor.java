package com.example.smartdataqa.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 4 · 上下文压缩器。
 *
 * <p><b>这不是练习代码，是你项目里要真用的组件。</b>
 * 你的项目要往上下文里塞两类极占空间的东西——RAG 检索回来的文档片段、
 * Text2SQL 要用的表结构说明——不压缩的话多轮对话必然爆上下文。
 *
 * <h3>它解决什么问题</h3>
 * 模型每一轮都要重读<b>整份消息列表</b>（模型是无状态的）。所以对话越长：
 * <ul>
 *   <li>token 成本线性上涨</li>
 *   <li>模型被大量无关的历史干扰，回答质量下降</li>
 *   <li>最后撞上上下文上限，直接报错</li>
 * </ul>
 *
 * <h3>压缩策略：三段切分</h3>
 * <pre>
 *   压缩前:  [system] [很早的对话 ....... ][ 最近的对话 ]
 *               ↓            ↓                  ↓
 *   压缩后:  [system] [    一段摘要      ][ 最近的对话 ]   ← 原样保留
 *               ↑            ↑                  ↑
 *            永不压缩    交给模型总结        永不压缩
 * </pre>
 *
 * <p><b>为什么 system 不压？</b>它是模型的角色和能力说明，压掉等于换了个人。
 *
 * <p><b>为什么最近的对话不压？</b>因为用户刚说的话、刚拿到的工具结果，
 * 是接下来这几轮最需要的信息。只压远处，才有「摘要」的价值。
 *
 * <h3>一个容易被忽略的坑</h3>
 * 如果历史里有工具调用，<b>不能随便切</b>——切开后会出现
 * 「assistant 说了要调工具，但它的结果不在」「出现一条没有请求的 tool 结果」
 * 这类结构错误，服务端会直接报 400。所以 {@link #adjustToSafeBoundary} 会把切点
 * 挪到安全位置。迭代 2 你见过的 {@code tool_call_id} 配对规则，在这里变成了
 * 一个真实的切分约束。
 *
 * <h3>为什么摘要能力要注入进来，而不是写死在里面</h3>
 * {@link Summarizer} 是个函数式接口。本轮它由「自己发 HTTP」实现，
 * 迭代 6 换成 Spring AI 之后，只要换一个 lambda，这个类<b>一行都不用改</b>。
 * 这就是把「变化的部分」隔离开的价值——你项目后面还会用到同样的手法。
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 待压缩的一段如果太短，压了反而亏（摘要本身也要占 token），就先不动。 */
    private static final int MIN_BLOCK_TO_COMPRESS = 4;

    /** 摘要能力的抽象。本轮用 HTTP 实现，迭代 6 换成 Spring AI 实现，这里不用改。 */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(List<Map<String, Object>> messagesToSummarize);
    }

    private final Summarizer summarizer;
    private final int maxMessages;
    private final int keepRecent;

    /**
     * @param summarizer 怎么把一段对话变成摘要（由调用方提供）
     * @param maxMessages 消息条数超过这个值就触发压缩
     * @param keepRecent  最近的多少条永远不动
     */
    public ContextCompactor(Summarizer summarizer, int maxMessages, int keepRecent) {
        if (keepRecent < 2) {
            throw new IllegalArgumentException("keepRecent 至少要是 2，否则等于没有近期上下文");
        }
        if (maxMessages <= keepRecent) {
            throw new IllegalArgumentException("maxMessages 必须大于 keepRecent，否则永远压不动");
        }
        this.summarizer = summarizer;
        this.maxMessages = maxMessages;
        this.keepRecent = keepRecent;
    }

    /** 要不要压。放在调用模型之前判断。 */
    public boolean needsCompaction(List<Map<String, Object>> messages) {
        return messages.size() > maxMessages;
    }

    /**
     * 执行压缩。返回一个<b>新的</b>列表，不改动传入的那个——
     * 这样调用方可以随时对比压缩前后，也避免出现「改到一半失败」的中间状态。
     */
    public List<Map<String, Object>> compact(List<Map<String, Object>> messages) {
        if (!needsCompaction(messages)) {
            return messages;
        }

        int size = messages.size();
        int tailStart = adjustToSafeBoundary(messages, size - keepRecent);

        // 中间那段就是要被总结掉的
        List<Map<String, Object>> middle = messages.subList(1, tailStart);
        if (middle.size() < MIN_BLOCK_TO_COMPRESS) {
            log.debug("待压缩部分只有 {} 条，太短不划算，这次先跳过", middle.size());
            return messages;
        }

        String summary = summarizer.summarize(middle);
        if (summary == null || summary.isBlank()) {
            log.warn("摘要返回空，放弃这次压缩（宁可多花 token，也不能丢信息）");
            return messages;
        }

        List<Map<String, Object>> compacted = new ArrayList<>();
        compacted.add(messages.get(0));          // system 原样保留
        compacted.add(summaryMessage(summary));  // 中间那段变成一条摘要
        compacted.addAll(messages.subList(tailStart, size)); // 最近的原样保留

        return compacted;
    }

    /**
     * 把切点挪到安全位置，保证不把「工具调用请求」和「工具返回结果」拆开。
     *
     * <p>两种情况要修：
     * <ul>
     *   <li>切点正好落在一条 {@code role=tool} 上 —— 它的请求被切到了前面，得把它也推回中间</li>
     *   <li>切点前面一条是带 {@code tool_calls} 的 assistant —— 它的结果会被切到后面，得把切点往前挪</li>
     * </ul>
     */
    private int adjustToSafeBoundary(List<Map<String, Object>> messages, int tailStart) {
        int size = messages.size();
        // 加个 guard 防止极端情况下死循环
        for (int guard = 0; guard < 20; guard++) {
            if (tailStart <= 1 || tailStart >= size) {
                break;
            }
            boolean cutStartsOnToolMsg = "tool".equals(messages.get(tailStart).get("role"));
            boolean prevIsToolCallRequest = messages.get(tailStart - 1).containsKey("tool_calls");

            if (cutStartsOnToolMsg) {
                tailStart++;
            }
            else if (prevIsToolCallRequest) {
                tailStart--;
            }
            else {
                break;
            }
        }
        return tailStart;
    }

    /**
     * 摘要以什么身份回到消息列表里。
     *
     * <p>这里用 {@code role=user} 并加一个显眼的前缀，原因有二：
     * 一是兼容性最好（有些服务端对「对话中途出现 system 消息」处理不一致），
     * 二是前缀让模型一眼看出这是压缩过的二手信息，而不是用户真说过的话——
     * 避免它把摘要里的话当成新的用户指令。
     */
    private Map<String, Object> summaryMessage(String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", "【以下是较早对话的摘要，原始消息已被压缩，请把它当作已知背景】\n" + summary);
        return m;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public int getKeepRecent() {
        return keepRecent;
    }
}
