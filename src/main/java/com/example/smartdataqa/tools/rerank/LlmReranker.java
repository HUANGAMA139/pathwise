package com.example.smartdataqa.tools.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 17 · <b>精排（rerank）</b>：用模型逐个比较候选与问题的相关性，重新排序。
 *
 * <h3>它和 RRF 解决的是完全不同的问题（这是高频易混考点）</h3>
 * <pre>
 *              RRF                          Rerank（本类）
 * 作用对象    跨通道：把向量和 BM25 合成一路    单一路结果集：给一批候选重排名次
 * 依据        只有名次，不读内容和分数         真的读问题和候选正文，判断相关性
 * 成本        零成本（纯计算）                 每次要调模型，有延迟和费用
 * 能解决什么  两路分数不可比、各有偏好          「召回了但排不对」——
 *                                             包括「相关但不回答问题」的片段
 * </pre>
 * <b>两者不冲突，可以叠加</b>：RRF 先把多路合成一个候选池（粗排），rerank 再对这个池精排。
 * 这也是本轮流水线的形状。
 *
 * <h3>为什么只调一次模型，而不是每个候选打一次分</h3>
 * 两种做法都可以。逐个打分是「绝对分」，但 N 个候选要 N 次调用，而且不同次调用的分数
 * <b>标准可能漂移</b>（模型心里的 7 分和下一轮的 7 分未必同一回事）。
 * 一次给出<b>相对排序</b>更便宜、也更稳——它本来就只需要序，不需要分。
 *
 * <h3>两处工程上的必要处理</h3>
 * <ol>
 *   <li><b>temperature 设 0。</b>1.1.0 的官方 RAG 文档明确建议：用 LLM 做这类判断时温度要低，
 *       否则结果不可复现。而「可复现」是这两天好不容易建立起来的量测纪律。</li>
 *   <li><b>解析失败不能静默。</b>模型不按格式回、少回了几个编号，都会让「重排」变成
 *       「悄悄退回原序」——那是<b>不报错的错误</b>：指标看起来正常，实际重排根本没生效。
 *       所以这里：解析出来的编号补全成完整排列、丢掉非法编号，并且<b>原始输出一定打日志</b>。</li>
 * </ol>
 */
public class LlmReranker implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    /** 每个候选给模型看多少字符。太短看不清，太长爆上下文。 */
    private static final int PREVIEW_CHARS = 120;

    /** 抽取响应里的编号。 */
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final ChatClient chatClient;

    public LlmReranker(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.size() <= 1) {
            return documents;
        }

        String prompt = buildPrompt(query.text(), documents);
        String raw;
        try {
            raw = this.chatClient.prompt()
                    .user(prompt)
                    // 温度 0：这类判断要的是稳定，不是发挥
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
        }
        catch (RuntimeException e) {
            log.warn("      [Rerank] 调用失败，退回原序：{}", e.toString());
            return documents;
        }

        String response = raw == null ? "" : raw.trim();
        log.info("      [Rerank] 原始输出：{}", response.length() <= 80 ? response : response.substring(0, 80) + "…");

        List<Integer> order = parseOrder(response, documents.size());
        if (order.isEmpty()) {
            log.warn("      [Rerank] 输出里没解析出任何合法编号，**退回原序**（这次重排等于没做）");
            return documents;
        }
        return reorder(documents, order);
    }

    private static String buildPrompt(String question, List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        sb.append("下面是一批检索到的资料片段，还有用户的问题。\n");
        sb.append("请按「对回答这个问题的帮助程度」从高到低排序。\n\n");
        sb.append("【问题】\n").append(question).append("\n\n【资料片段】\n");
        for (int i = 0; i < documents.size(); i++) {
            String text = documents.get(i).getText();
            if (text == null) {
                text = "";
            }
            String preview = text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS) + "…";
            sb.append(i + 1).append("：").append(preview.replace("\n", " ")).append('\n');
        }
        sb.append("\n只输出编号，用逗号分隔，从最相关到最不相关。");
        sb.append("必须把上面 ").append(documents.size()).append(" 个编号全部列出，不要输出任何其他内容。");
        return sb.toString();
    }

    /**
     * 把响应解析成一个编号序列。
     *
     * <p><b>补全比严查更重要</b>：模型少列一个编号是常事，这时直接判失败会让整次重排白做。
     * 所以策略是——取所有合法编号、按出现顺序去重、剩下的按原序补在后面。
     * 这样即使模型答得不完整，也还是拿到了一个全排列（不完整的那部分保守地留在后面）。
     */
    static List<Integer> parseOrder(String response, int size) {
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(response);
        while (m.find()) {
            int idx = Integer.parseInt(m.group());
            if (idx >= 1 && idx <= size) {
                seen.add(idx);
            }
        }
        if (seen.isEmpty()) {
            return List.of();
        }
        for (int i = 1; i <= size; i++) {
            seen.add(i);
        }
        return new ArrayList<>(seen);
    }

    /**
     * 按编号重排，并把 score 换成「名次分」{@code 1/名次}。
     *
     * <p><b>score 的含义到这里变了第三次</b>：2-gram 命中比例（迭代 8）→ 余弦相似度（迭代 14）
     * → RRF 融合分（迭代 16）→ 现在是重排名次。这么设计是因为重排之后
     * <b>原来的相似度已经不再决定顺序了</b>，让它继续挂在 score 上只会误导下游。
     */
    private static List<Document> reorder(List<Document> documents, List<Integer> order) {
        List<Document> out = new ArrayList<>(documents.size());
        for (int i = 0; i < order.size(); i++) {
            Document d = documents.get(order.get(i) - 1);
            out.add(Document.builder()
                    .id(d.getId())
                    .text(d.getText())
                    .metadata(d.getMetadata())
                    .score(1.0 / (i + 1))
                    .build());
        }
        return out;
    }
}
