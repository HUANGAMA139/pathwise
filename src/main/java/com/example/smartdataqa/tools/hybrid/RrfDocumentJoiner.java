package com.example.smartdataqa.tools.hybrid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 16 · <b>RRF（Reciprocal Rank Fusion，倒数排名融合）</b>，实现成 Spring AI 的
 * {@link DocumentJoiner}。
 *
 * <h3>为什么框架里没有</h3>
 * {@code spring-ai-rag} 只提供了 {@code ConcatenationDocumentJoiner}——它把多路结果拼起来、
 * 按 id 去重、按分数排序。**那是「拼接」，不是「融合」**：它假设各路的分数可以直接比大小。
 * 而我们的两路分数<b>根本不可比</b>：
 * <pre>
 *   向量：1 - 余弦距离，挤在 0.6~0.8（迭代 14 实测：有一题 top5 跨度只有 0.0378）
 *   BM25：IDF 加权求和，量纲完全不同（0~10 那种量级）
 * </pre>
 * 直接比大小等于让 BM25 的分数量纲决定一切。**这是「拼接」和「融合」的分界线。**
 *
 * <h3>RRF 的核心：只看名次，不看分数</h3>
 * <pre>
 *   RRF(d) = Σ_over_each_retriever  1 / (k + rank_i(d))
 * </pre>
 * 只用到「这个文档在某一路里排第几」。所以：
 * <ul>
 *   <li><b>天然免疫量纲问题</b>——不需要把两路分数归一化，也不用调权重</li>
 *   <li><b>对「某一路强烈认为它第一」特别敏感</b>——rank=1 贡献 1/(k+1) ≈ 0.0164，
 *       rank=5 只有 1/(k+5) ≈ 0.0154，差得不多；所以<b>两路都排前面</b>的文档才会浮上来。
 *       这个「两路共识」正是我们要的东西</li>
 * </ul>
 *
 * <h3>k 是什么、为什么取 60</h3>
 * {@code k} 决定「名次靠前」的边际收益有多大。k 越小，第一名越占绝对优势。
 * 60 是 RRF 原论文用的值，也是业界默认——**k 大到 60 之后，名次的边际差异被压平，
 * 融合结果就不再被任何一路的极端排名左右**。这是「稳健」而非「最优」的取舍。
 *
 * <h3>一条 迭代 14 的伏笔在这里兑现</h3>
 * 迭代 14 发现：<b>关键词通道能把 {@code XR-2000B} 和 {@code XR-2000C} 分开，
 * 靠的不是分数更高（它俩得分完全相同），而是「命中的是哪个片段」。</b>
 * 当时我就写了「融合时用 RRF 比用分数加权更对」——因为分数加权正好会把这个唯一的优势抹掉。
 * 本轮这一条成了实现。
 */
public class RrfDocumentJoiner implements DocumentJoiner {

    private static final Logger log = LoggerFactory.getLogger(RrfDocumentJoiner.class);

    /** RRF 论文的取值，业界默认。 */
    public static final int DEFAULT_K = 60;

    private final int k;

    public RrfDocumentJoiner() {
        this(DEFAULT_K);
    }

    public RrfDocumentJoiner(int k) {
        this.k = k;
    }

    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        Map<String, Double> fused = new HashMap<>();
        Map<String, Document> firstSeen = new HashMap<>();

        for (List<List<Document>> perQuery : documentsForQuery.values()) {
            for (List<Document> ranked : perQuery) {
                for (int i = 0; i < ranked.size(); i++) {
                    Document doc = ranked.get(i);
                    // 名次从 1 开始，所以是 k + i + 1
                    fused.merge(doc.getId(), 1.0 / (k + i + 1), Double::sum);
                    firstSeen.putIfAbsent(doc.getId(), doc);
                }
            }
        }

        if (fused.isEmpty()) {
            log.info("      [RRF] 两路都没有命中");
            return List.of();
        }

        int shared = countSharedAcrossChannels(documentsForQuery);
        log.info("      [RRF] 融合 {} 个候选（其中 {} 个被两路同时命中）",
                fused.size(), shared);

        return fused.entrySet().stream()
                // 同分按 id 升序 —— 和 BM25 一样，非确定的排序会让两次跑结果不一致
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> Document.builder()
                        .id(e.getKey())
                        .text(firstSeen.get(e.getKey()).getText())
                        .metadata(firstSeen.get(e.getKey()).getMetadata())
                        .score(e.getValue())
                        .build())
                .toList();
    }

    /**
     * 数一下有多少文档<b>同时</b>被两路以上命中。
     *
     * <p>这个数字是个很好的自检：**如果它接近 0，说明两路的 id 空间没对上**
     * （比如一边用 uuid、一边用业务 id），RRF 会把「共识」变成「谁都没共识」，
     * 融合反而比单路更差——而且不报错，只是数字难看。
     */
    private static int countSharedAcrossChannels(Map<Query, List<List<Document>>> documentsForQuery) {
        Map<String, Integer> hitBy = new HashMap<>();
        for (List<List<Document>> perQuery : documentsForQuery.values()) {
            for (List<Document> ranked : perQuery) {
                for (String id : new ArrayList<>(ranked.stream().map(Document::getId).toList())) {
                    hitBy.merge(id, 1, Integer::sum);
                }
            }
        }
        return (int) hitBy.values().stream().filter(v -> v > 1).count();
    }
}
