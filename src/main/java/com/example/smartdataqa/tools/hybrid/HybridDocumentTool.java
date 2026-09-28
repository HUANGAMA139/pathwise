package com.example.smartdataqa.tools.hybrid;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentChunks;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.vector.CorpusIngestor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 迭代 16 · <b>双通道检索</b>：向量（语义）+ BM25（关键词），用 RRF 融合。
 *
 * <h3>为什么现在才做</h3>
 * 关键词这一路 迭代 12 就点了名——当时是字符 2-gram 粗匹配，迭代 14 换成向量时它被「替换」掉了。
 * 但那是个错误的理解：<b>它们不是替代关系</b>。迭代 14 实测留了个尾巴：
 * {@code XR-2000B} / {@code XR-2000C} 被向量混成了一个片段（得分只差 0.000662），
 * 而字符匹配能分开它们——关键词通道要回来，而且是用正规的 BM25 回来。
 *
 * <h3>两个通道必须共享同一个 id 空间</h3>
 * 这是融合的前提（{@code DocumentJoiner} 按 {@code Document::getId()} 去重）。麻烦在于两边的天然 id 不一样：
 * <pre>
 *   pgvector 表的 id 列是 uuid（迭代 14 的坑）；我们的业务 id 是 {@code 01-...#2}，存在元数据里
 * </pre>
 * 所以这里<b>把两路都归一到业务 id</b>——不是「碰巧一样」，而是<b>构造上强制一样</b>。
 * 如果两边各用各的 id，RRF 会把「两路共识」变成「谁都没共识」，融合比单路还差，而且不报错。
 *
 * <h3>四个入口，其中三个是给对比实验的</h3>
 * {@link #search} 是产品路径；{@link #searchVector} / {@link #searchBm25} / {@link #fuse}
 * 是给 迭代 16 的对比实验用的——<b>没有单路对照，就说不出「融合好多少」；
 * 不能换融合参数，就分不清「方法不行」和「参数不行」。</b>
 */
public class HybridDocumentTool implements DocumentTool {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentTool.class);

    /** 融合前每路取多少候选。取太少，某一路的正确答案可能根本进不了候选池。 */
    private final int candidates;

    /** RRF 的 k。越小，名次靠前的边际收益越大。 */
    private final int rrfK;

    private final VectorStore vectorStore;
    private final Supplier<Bm25Index> indexSupplier;

    /**
     * <b>BM25 索引是懒加载的，这是被一个顺序 bug 逼出来的。</b>
     *
     * <p>Spring 的 bean 在<b>容器刷新时</b>创建，而语料入库是个 {@code ApplicationRunner}
     * （迭代 14 那条 {@code @Order(HIGHEST_PRECEDENCE)} 只保证 runner 之间谁先跑）。
     * 所以「bean 构造」永远早于「入库」——如果在构造时读库建索引，
     * <b>全新数据库上会读到 0 行，然后这个索引永远是空的</b>，而且不报错，
     * 只是混合检索退化成一个只剩向量在干活的哑巴。
     *
     * <p>懒加载把顺序问题消掉了：真正第一次检索时，入库早就跑完了。
     */
    private volatile Bm25Index bm25;

    public HybridDocumentTool(VectorStore vectorStore, Supplier<Bm25Index> indexSupplier,
                              int candidates, int rrfK) {
        this.vectorStore = vectorStore;
        this.indexSupplier = indexSupplier;
        this.candidates = candidates;
        this.rrfK = rrfK;
    }

    private Bm25Index index() {
        Bm25Index local = this.bm25;
        if (local == null) {
            synchronized (this) {
                local = this.bm25;
                if (local == null) {
                    local = this.indexSupplier.get();
                    this.bm25 = local;
                }
            }
        }
        return local;
    }

    /**
     * 从向量库同一张表里读出片段，建 BM25 索引（懒加载，理由见 {@link #bm25}）。
     *
     * <p><b>为什么不重新用 CorpusLoader + DocumentChunker 切一遍：</b>那样两路看到的就是
     * 两份东西了——万一分块参数变了、或者库里的数据是旧版本，向量和关键词就在两批不同的片段上
     * 检索，融合出来的结果没有意义。读同一张表，两份视角在数据上是同一批。
     */
    public static HybridDocumentTool fromDatabase(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                                                  String tableName, int candidates, int rrfK) {
        return new HybridDocumentTool(vectorStore,
                () -> loadIndex(jdbcTemplate, tableName), candidates, rrfK);
    }

    private static Bm25Index loadIndex(JdbcTemplate jdbcTemplate, String tableName) {
        List<Document> chunks = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        jdbcTemplate.query(
                "SELECT id::text AS row_id, content, metadata::text AS meta FROM " + tableName,
                rs -> {
                    String rowId = rs.getString("row_id");
                    Map<String, Object> meta = parseMetadata(mapper, rs.getString("meta"), rowId);
                    chunks.add(Document.builder()
                            .id(businessId(meta, rowId))
                            .text(rs.getString("content"))
                            .metadata(meta)
                            .build());
                });

        log.info("BM25 侧建索引：读了 {} 个片段（表 {}），id 用元数据里的 chunk_id",
                chunks.size(), tableName);
        return new Bm25Index(chunks);
    }

    // ==================== 四个入口 ====================

    /** 产品路径：向量 + BM25 双通道，按配置的候选数与 k 做 RRF 融合。 */
    @Override
    public List<Chunk> search(String query, int topK) {
        log.info("      [DocumentTool] 混合检索：query=\"{}\" topK={}（候选 {} ×2，k={}）",
                shorten(query), topK, candidates, rrfK);
        List<Chunk> v = searchVector(query, candidates);
        List<Chunk> b = searchBm25(query, candidates);
        List<Chunk> fused = fuse(query, v, b, topK, rrfK);
        log.info("      [DocumentTool] 向量 {} 段 + BM25 {} 段 → 融合后取 {} 段",
                v.size(), b.size(), fused.size());
        return fused;
    }

    /** 只走向量。给对比实验用。 */
    public List<Chunk> searchVector(String query, int topK) {
        log.info("      [DocumentTool] 只向量：query=\"{}\" topK={}", shorten(query), topK);
        return toChunks(vectorDocs(query, topK));
    }

    /** 只走 BM25。给对比实验用。 */
    public List<Chunk> searchBm25(String query, int topK) {
        log.info("      [DocumentTool] 只BM25：query=\"{}\" topK={}", shorten(query), topK);
        return toChunks(bm25Docs(query, topK));
    }

    /**
     * 融合两路<b>已经取好的</b>候选。
     *
     * <p>公开出来是因为对比实验要拿<b>同一批候选</b>去试不同的 {@code (候选数, k)} 组合——
     * 否则每换一组参数就要重新检索一次，既慢，又会让「同一道题在不同组合下的输入不同」，
     * 那对比就不干净了。
     */
    public List<Chunk> fuse(String query, List<Chunk> vectorHits, List<Chunk> bm25Hits,
                            int topK, int rrfK) {
        List<Document> fused = new RrfDocumentJoiner(rrfK).join(
                Map.of(new Query(query), List.of(toDocuments(vectorHits), toDocuments(bm25Hits))));
        List<Document> top = fused.subList(0, Math.min(topK, fused.size()));
        return toChunks(top);
    }

    // ==================== 内部：两路各自拿 Document（带归一化后的 id） ====================

    private List<Document> vectorDocs(String query, int topK) {
        List<Document> raw = this.vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
        List<Document> out = new ArrayList<>(raw.size());
        for (Document d : raw) {
            // 归一化：把 id 换成业务 id（chunk_id），这样和 BM25 那路对得上
            out.add(Document.builder()
                    .id(businessId(d.getMetadata(), d.getId()))
                    .text(d.getText())
                    .metadata(d.getMetadata())
                    .score(d.getScore())
                    .build());
        }
        return out;
    }

    private List<Document> bm25Docs(String query, int topK) {
        return index().search(query, topK);
    }

    /** 索引里的全部片段（= 向量库同一张表里的那批）。给 迭代 17 的多样性指标用。 */
    public List<Chunk> indexedChunks() {
        return toChunks(index().documents());
    }

    // ==================== 工具 ====================

    private static List<Chunk> toChunks(List<Document> docs) {
        return DocumentChunks.toChunks(docs);
    }

    /** 反过来：把片段包回 Document，好交给 {@code DocumentJoiner}。迭代 17 起也走 {@link DocumentChunks}。 */
    private static List<Document> toDocuments(List<Chunk> chunks) {
        return DocumentChunks.toDocuments(chunks);
    }

    /** 元数据里有 chunk_id 就用它，没有才退回到行 id。 */
    private static String businessId(Map<String, Object> metadata, String fallback) {
        Object cid = metadata.get(CorpusIngestor.META_CHUNK_ID);
        return cid == null ? fallback : cid.toString();
    }

    private static Map<String, Object> parseMetadata(ObjectMapper mapper, String json, String fallbackId) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (Exception e) {
            // 元数据坏了不该让整个检索挂掉——记一条警告，按没有元数据处理
            log.warn("元数据解析失败（row id {}）：{}", fallbackId, e.toString());
            return Map.of();
        }
    }

    private String shorten(String s) {
        return s == null ? "" : (s.length() <= 24 ? s : s.substring(0, 24) + "…");
    }
}
