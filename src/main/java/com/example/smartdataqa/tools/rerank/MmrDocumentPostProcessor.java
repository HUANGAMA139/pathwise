package com.example.smartdataqa.tools.rerank;

import com.example.smartdataqa.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代 17 · <b>MMR（Maximal Marginal Relevance，最大边际相关性）</b>。
 *
 * <h3>它要解决的不是「相关性」，是「重复」</h3>
 * 纯按相关性排序有个必然会遇到的问题：<b>前几名往往在说同一件事</b>。
 * 我们的语料里，一条规则常常在多个片段里被反复提到（同一份文档切出来的相邻片段、
 * 或者不同文档互相引用），所以 top-5 里可能有三段是同一条信息的复述——
 * <b>等于把上下文额度浪费掉了</b>。
 *
 * <p>MMR 的贪心准则正是为此：
 * <pre>
 *   每次挑一个 d，使   λ · sim(d, 问题)  -  (1-λ) · max sim(d, 已选中的那些)
 *                      └── 相关性 ──┘      └────── 和已选的重复度 ──────┘
 * </pre>
 * 第一项说「要跟问题相关」，第二项说「别跟已经选过的像」。
 * <b>λ 就是这两者的权重</b>：λ=1 退化成纯相关性排序，λ=0 会挑出和问题最不搭的东西。
 *
 * <h3>它和 rerank 是两种不同的「精排」</h3>
 * <pre>
 *   Rerank：对每个候选问「你回不回答得了这个问题」——判的是**相关性**
 *   MMR   ：在相关性之外还要问「你是不是重复了已经选中的」——判的是**多样性**
 * </pre>
 * 所以这两个不是替代关系：rerank 让<b>对的片段往前</b>，MMR 让<b>前几名别重复</b>。
 * 本轮 A/B 就是要把这两件事的量分开。
 *
 * <h3>两处工程上的必要处理</h3>
 * <ol>
 *   <li><b>向量要自己分批取。</b>{@code EmbeddingModel.embed(List<String>)} <b>不做分批</b>，
 *       而 DashScope 单次只收 10 条（迭代 14 踩过）。1.1.0 里只有
 *       {@code embed(List<Document>, options, batchingStrategy)} 那个重载才分批。
 *       所以这里按固定批大小切——理由是确定的，不用赌某个类的行为。
 *       <b>迭代 19 起这段搬去了 {@link Vectors}</b>（同一个坑要写第三遍，按三次法则收口）。</li>
 *   <li><b>同分要确定性地打破。</b>MMR 在数值上很容易出现并列（内容相近的片段算出来的分完全一样），
 *       只按分数排会让两次跑的顺序不同。这里同分按文档 id 升序——和 迭代 16 的处理一致。</li>
 * </ol>
 */
public class MmrDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MmrDocumentPostProcessor.class);

    /** 浮点并列的判定阈值。 */
    private static final double EPS = 1e-12;

    private final EmbeddingModel embeddingModel;
    private final double lambda;

    public MmrDocumentPostProcessor(EmbeddingModel embeddingModel, double lambda) {
        this.embeddingModel = embeddingModel;
        this.lambda = lambda;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.size() <= 1) {
            return documents;
        }

        float[] queryVector = this.embeddingModel.embed(query.text());
        List<float[]> vectors = embedInBatches(documents);

        double[] similarityToQuery = new double[documents.size()];
        for (int i = 0; i < documents.size(); i++) {
            similarityToQuery[i] = cosine(queryVector, vectors.get(i));
        }

        List<Integer> order = greedyMmr(documents, vectors, similarityToQuery);
        log.info("      [MMR] λ={} → 排序完成（首条={}）", lambda, documents.get(order.get(0)).getId());
        return reorder(documents, order);
    }

    /**
     * 贪心挑 MMR 最大的那个，挑完全部候选（返回的是<b>完整排列</b>，由调用方截断到 top-K）。
     *
     * <p>返回全排列而不是直接截断，是因为「取几段」是流水线的事，不是后处理器的事。
     * 分工清楚，A/B 时才能自由换 top-K。
     */
    private List<Integer> greedyMmr(List<Document> documents, List<float[]> vectors, double[] similarityToQuery) {
        int n = documents.size();
        List<Integer> selected = new ArrayList<>(n);
        Set<Integer> used = new HashSet<>();

        while (selected.size() < n) {
            int best = -1;
            double bestScore = 0;

            for (int i = 0; i < n; i++) {
                if (used.contains(i)) {
                    continue;
                }
                double redundancy = 0.0;
                for (int s : selected) {
                    redundancy = Math.max(redundancy, cosine(vectors.get(i), vectors.get(s)));
                }
                double mmr = this.lambda * similarityToQuery[i] - (1 - this.lambda) * redundancy;

                if (best < 0 || mmr > bestScore + EPS
                        || (Math.abs(mmr - bestScore) <= EPS
                            && documents.get(i).getId().compareTo(documents.get(best).getId()) < 0)) {
                    best = i;
                    bestScore = mmr;
                }
            }
            selected.add(best);
            used.add(best);
        }
        return selected;
    }

    /** 取候选的向量（分批与限流的处理都在 {@link Vectors} 里）。 */
    private List<float[]> embedInBatches(List<Document> documents) {
        List<String> texts = new ArrayList<>(documents.size());
        for (Document d : documents) {
            texts.add(d.getText() == null ? "" : d.getText());
        }
        return Vectors.embedInBatches(this.embeddingModel, texts);
    }

    /**
     * 余弦相似度。
     *
     * <p>迭代 19 起实现在 {@link Vectors}（同一个定义第三次出现，按三次法则抽走了）。
     * 这个方法留着只是<b>为了不改 迭代 17 A/B 的调用点</b>，行为一字未变。
     */
    public static double cosine(float[] a, float[] b) {
        return Vectors.cosine(a, b);
    }

    private static List<Document> reorder(List<Document> documents, List<Integer> order) {
        List<Document> out = new ArrayList<>(documents.size());
        for (int i = 0; i < order.size(); i++) {
            Document d = documents.get(order.get(i));
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
