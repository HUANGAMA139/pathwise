package com.example.smartdataqa.tools.hybrid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 迭代 16 · 纯 Okapi BM25，跑在<b>字符二元组</b>上。
 *
 * <h3>为什么自己写</h3>
 * Spring AI 1.1.0 里**没有任何关键词检索**（模块树里搜不到 BM25）。
 * 而关键词通道是这个项目从 迭代 12 起就一直在说的东西，现在终于要把它做正规。
 * 顺带一句：{@code spring-ai-rag} 里的 {@code DocumentJoiner} 也只有「拼接去重」一个实现，
 * RRF 同样要自己写——**这两个空白正好落在项目的原创点上**。
 *
 * <h3>它的前身是 迭代 8 那个「2-gram 粗匹配」</h3>
 * {@code MockDocumentTool.crudeScore} 当年也是数二元组命中比例，但缺了三样东西：
 * <ul>
 *   <li><b>IDF</b>——没考虑「这个词有多稀有」。全语料都有的词（「的」「销售」）不该和罕见词同权</li>
 *   <li><b>词频饱和</b>（{@code k1}）——同一个词出现 10 次不该是出现 1 次的 10 倍</li>
 *   <li><b>长度归一化</b>（{@code b}）——长片段天然更容易命中，要按长度打折</li>
 * </ul>
 * 补上这三样，就是 BM25。
 *
 * <h3>为什么用字符二元组而不是中文分词</h3>
 * 中文没有空格，BM25 需要先把文本切成「词」。两条路：引分词器（jieba/HanLP）或切二元组。
 * 这里选<b>二元组</b>：零依赖，而且在中文上效果可接受。
 * 代价要认清——<b>它切出来的不是词</b>：「销售额」会变成「销售」+「售额」两个 term，
 * 语义上有点怪，但检索层能用（命中的是同样的二元组）。
 *
 * <h3>两个容易写错的地方</h3>
 * <ol>
 *   <li><b>切词不能跨越标点。</b>先把文本按标点/空白切开、在每段内部再切二元组。
 *       否则「…说明。销售额…」去掉句号后会切出一个根本不存在的「明销」。</li>
 *   <li><b>并列时排序必须确定。</b>只按分数排的话，同分文档的顺序取决于哈希表遍历顺序，
 *       同一份数据两次跑会给出不同结果——迭代 15 已经在路由上吃过一次「非确定性」的亏
 *       （同一题两次跑出不同路由），这里不能再犯。所以同分时按文档 id 升序。</li>
 * </ol>
 */
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    /** 词频饱和系数。取值来自 BM25 的常用默认值。 */
    private static final double K1 = 1.2;

    /** 长度归一化强度，0 = 不归一化，1 = 完全归一化。 */
    private static final double B = 0.75;

    /**
     * 切分边界：空白、ASCII 标点、常见中文标点。
     *
     * <p>注意 {@code ·} 和 {@code —} 这些也在里面——语料里的「订单金额 - 退货金额」用的就是这类字符。
     */
    private static final Pattern BOUNDARY = Pattern.compile(
            "[\\s\\p{Punct}，。？！、；：（）《》【】「」“”‘’—…·|]+");

    private final Map<String, Document> byId = new HashMap<>();
    private final Map<String, Integer> lengthById = new HashMap<>();
    private final Map<String, Map<String, Integer>> tfById = new HashMap<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double avgLength;

    public Bm25Index(List<Document> documents) {
        double total = 0;
        for (Document d : documents) {
            List<String> terms = tokenize(d.getText());
            byId.put(d.getId(), d);
            lengthById.put(d.getId(), terms.size());
            total += terms.size();

            Map<String, Integer> tf = new HashMap<>();
            for (String t : terms) {
                tf.merge(t, 1, Integer::sum);
            }
            tfById.put(d.getId(), tf);
            for (String t : tf.keySet()) {
                docFreq.merge(t, 1, Integer::sum);
            }
        }
        this.avgLength = documents.isEmpty() ? 0.0 : total / documents.size();
        log.info("BM25 索引就绪：{} 个片段，词表 {} 个 term（字符二元组），平均长度 {}",
                documents.size(), docFreq.size(), String.format("%.1f", avgLength));
    }

    /**
     * 索引里的全部文档。
     *
     * <p>迭代 17 的 A/B 要用它：把这一批片段一次性向量化，就能算出「返回的片段之间有多重复」
     * （多样性指标），而不必为每个结果集重复调 embedding。
     */
    public List<Document> documents() {
        return new ArrayList<>(byId.values());
    }

    /**
     * 检索。
     *
     * @return 命中的文档，分数是 BM25 分（越大越相关）。<b>注意这个分和余弦相似度不可比</b>——
     *         所以上面做融合时要走 RRF（只比名次，不比分数）
     */
    public List<Document> search(String query, int topK) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty() || byId.isEmpty()) {
            return List.of();
        }
        // 同一个 term 在查询里出现多次不重复计分（BM25 的常见做法，也是标准公式的写法）
        Set<String> distinct = new LinkedHashSet<>(queryTerms);

        int n = byId.size();
        Map<String, Double> scores = new HashMap<>();
        for (String term : distinct) {
            Integer df = docFreq.get(term);
            if (df == null) {
                continue;   // 语料里没有这个 term → 对排序没有贡献
            }
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            for (Map.Entry<String, Map<String, Integer>> e : tfById.entrySet()) {
                int tf = e.getValue().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                double dl = lengthById.get(e.getKey());
                double norm = avgLength == 0 ? 1.0 : dl / avgLength;
                double denom = tf + K1 * (1 - B + B * norm);
                scores.merge(e.getKey(), idf * tf * (K1 + 1) / denom, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))   // 同分按 id 升序，保证两次跑结果一致
                .limit(topK)
                .map(e -> withScore(byId.get(e.getKey()), e.getValue()))
                .toList();
    }

    /**
     * 切词：按标点/空白分段，段内切字符二元组。
     *
     * <p>段长小于 2 的直接跳过——保证 term 空间纯粹是二元组，不混进单个字。
     */
    static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return terms;
        }
        for (String segment : BOUNDARY.split(text)) {
            for (int i = 0; i + 2 <= segment.length(); i++) {
                terms.add(segment.substring(i, i + 2));
            }
        }
        return terms;
    }

    /** 把 BM25 分写进 Document 的 score 字段（原 Document 的 score 是向量相似度，不适用了）。 */
    private static Document withScore(Document doc, double score) {
        return Document.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(doc.getMetadata())
                .score(score)
                .build();
    }
}
