package com.example.smartdataqa.tools.text2sql;

import com.example.smartdataqa.tools.hybrid.Bm25Index;
import com.example.smartdataqa.tools.hybrid.RrfDocumentJoiner;
import com.example.smartdataqa.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 迭代 19 · 表筛选的<b>混合检索</b>实现：BM25 + 向量，RRF 融合。
 *
 * <h3>这个类里最重要的是「哪些是借来的、哪些是新的」</h3>
 * <pre>
 *   借来的（一行没改）：{@link Bm25Index} · {@link RrfDocumentJoiner}
 *   借来的（迭代 17 抽出来的）：{@link Vectors} 的余弦与分批
 *   新写的：只有「把 40 行文档检索的接线，改成表目录的接线」这一段
 * </pre>
 * 新代码之所以这么短，是因为 迭代 16 那两个类当初就没把自己写死成「文档检索专用」——
 * 它们只要求输入是「有 id、有正文、能排序的东西」。**这就是「理解原理」和「抄步骤」的差别。**
 *
 * <h3>三个入口，其中两个是给对比实验的</h3>
 * {@link #select} 是产品路径（两路融合）；{@link #selectBm25} / {@link #selectVector}
 * 是给 迭代 19 的对比实验用的——<b>没有单路对照，就说不出「融合好多少」</b>，
 * 也分不清「方法不行」和「参数不行」。这个形状是照抄 迭代 16 的 {@code HybridDocumentTool} 的。
 *
 * <h3>两处工程细节</h3>
 * <ol>
 *   <li><b>目录向量在内存里，不灌向量库。</b>理由见 {@link TableSelector} 类注释
 *       （灌进 RAG 那张 pgvector 表会让 迭代 14—17 的基线当场作废，而且不报错）。</li>
 *   <li><b>懒加载 + 缓存，而且目录是「供应商」不是「现成列表」。</b>这个形状是照抄
 *       迭代 16 的 {@code HybridDocumentTool.fromDatabase(…, Supplier)} 的，理由也一样：
 *       <b>bean 构造永远早于「数据就绪」</b>。表目录要读业务库元数据，如果在构造时就读，
 *       业务库没起的话应用启动直接挂——而 迭代 18 是特意设计成「业务库不在也能启动」的。
 *       所以目录、BM25 索引、目录向量三者全部推迟到第一次真正筛表时才建。</li>
 * </ol>
 */
public class HybridTableSelector implements TableSelector {

    private static final Logger log = LoggerFactory.getLogger(HybridTableSelector.class);

    /** 表目录的来源（懒加载，见类注释第 2 条）。 */
    private final Supplier<List<Document>> catalogSupplier;
    private final EmbeddingModel embeddingModel;

    /** 融合前每路取多少候选。取太少，某一路选中的正确表可能根本进不了候选池。 */
    private final int candidates;

    /** RRF 的 k。 */
    private final int rrfK;

    private volatile List<Document> catalog;
    private volatile Bm25Index bm25;
    private volatile List<float[]> catalogVectors;

    public HybridTableSelector(Supplier<List<Document>> catalogSupplier,
                               EmbeddingModel embeddingModel,
                               int candidates,
                               int rrfK) {
        this.catalogSupplier = catalogSupplier;
        this.embeddingModel = embeddingModel;
        this.candidates = candidates;
        this.rrfK = rrfK;
        log.info("表筛选装配完成：每路候选 {}，RRF k={}（表目录首次筛表时才读）", candidates, rrfK);
    }

    /** 目录里有多少张表。给 demo 做对账用（会触发懒加载）。 */
    public int catalogSize() {
        return catalog().size();
    }

    // ==================== 三个入口 ====================

    /** 产品路径：BM25 + 向量双通道，RRF 融合。 */
    @Override
    public List<String> select(String question, int topK) {
        List<Document> bm25Hits = bm25Docs(question, this.candidates);
        List<Document> vectorHits = vectorDocs(question, this.candidates);
        List<Document> fused = new RrfDocumentJoiner(this.rrfK).join(
                Map.of(new Query(question), List.of(vectorHits, bm25Hits)));
        List<String> picked = names(fused, topK);
        log.info("      [表筛选] 混合：BM25 {} 张 + 向量 {} 张 → 融合取 {} 张：{}",
                bm25Hits.size(), vectorHits.size(), picked.size(), picked);
        return picked;
    }

    /** 只走 BM25。给对比实验用。 */
    public List<String> selectBm25(String question, int topK) {
        List<String> picked = names(bm25Docs(question, topK), topK);
        log.info("      [表筛选] 只BM25：{}", picked);
        return picked;
    }

    /** 只走向量。给对比实验用。 */
    public List<String> selectVector(String question, int topK) {
        List<String> picked = names(vectorDocs(question, topK), topK);
        log.info("      [表筛选] 只向量：{}", picked);
        return picked;
    }

    // ==================== 两路 ====================

    private List<Document> bm25Docs(String question, int topK) {
        return index().search(question, topK);
    }

    /**
     * 向量那一路：目录向量在内存里，问题向量每次现算。
     *
     * <p>排序同分时按 id 升序——和 迭代 16 的约定一致。
     * <b>非确定的排序会让两次跑给出不同结果</b>，迭代 15 已经在路由上吃过这个亏。
     */
    private List<Document> vectorDocs(String question, int topK) {
        List<Document> docs = catalog();
        List<float[]> vectors = vectors();
        float[] queryVector = this.embeddingModel.embed(question);

        List<Scored> scored = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            scored.add(new Scored(docs.get(i), Vectors.cosine(queryVector, vectors.get(i))));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> s.document().getId()))
                .limit(topK)
                .map(Scored::document)
                .toList();
    }

    private record Scored(Document document, double score) {
    }

    // ==================== 懒加载（三样都推迟到第一次筛表） ====================

    /** 表目录。见类注释第 2 条：不能在 bean 构造时读，否则业务库没起就启动失败。 */
    private List<Document> catalog() {
        List<Document> local = this.catalog;
        if (local == null) {
            synchronized (this) {
                local = this.catalog;
                if (local == null) {
                    long t0 = System.currentTimeMillis();
                    local = this.catalogSupplier.get();
                    this.catalog = local;
                    log.info("      [表筛选] 表目录就绪：{} 条，耗时 {} ms",
                            local.size(), System.currentTimeMillis() - t0);
                }
            }
        }
        return local;
    }

    private Bm25Index index() {
        Bm25Index local = this.bm25;
        if (local == null) {
            synchronized (this) {
                local = this.bm25;
                if (local == null) {
                    local = new Bm25Index(catalog());
                    this.bm25 = local;
                }
            }
        }
        return local;
    }

    private List<float[]> vectors() {
        List<float[]> local = this.catalogVectors;
        if (local == null) {
            synchronized (this) {
                local = this.catalogVectors;
                if (local == null) {
                    long t0 = System.currentTimeMillis();
                    List<Document> docs = catalog();
                    List<String> texts = new ArrayList<>(docs.size());
                    for (Document d : docs) {
                        texts.add(d.getText() == null ? "" : d.getText());
                    }
                    local = Vectors.embedInBatches(this.embeddingModel, texts);
                    this.catalogVectors = local;
                    log.info("      [表筛选] 表目录向量化完成：{} 条，耗时 {} ms",
                            local.size(), System.currentTimeMillis() - t0);
                }
            }
        }
        return local;
    }

    // ==================== 工具 ====================

    /** 取前 topK 个的表名。id 就是表名（见 {@link SchemaRenderer#catalog()}）。 */
    private static List<String> names(List<Document> documents, int topK) {
        List<String> out = new ArrayList<>(Math.min(topK, documents.size()));
        for (Document d : documents) {
            if (out.size() >= topK) {
                break;
            }
            Object table = d.getMetadata().get(SchemaRenderer.META_TABLE);
            out.add(table == null ? d.getId() : table.toString());
        }
        return out;
    }
}
