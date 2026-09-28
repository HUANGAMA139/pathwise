package com.example.smartdataqa.tools.vector;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.corpus.CorpusLoader;
import com.example.smartdataqa.tools.corpus.DocumentChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 迭代 14 · 语料入库器：把 迭代 13 切好的片段灌进 pgvector。
 *
 * <h3>它补上了 ETL 缺的第三段</h3>
 * 迭代 13 之后我们有了 Reader（{@link CorpusLoader}）和 Transformer（{@link DocumentChunker}），
 * 但 Writer 一直是个占位——片段只在内存里过了一遍检索工具，进程一退就没了。
 * 本轮这一段真正落地：<b>写进数据库，下次启动还在</b>。
 *
 * <h3>三个必须处理的现实问题</h3>
 * <ol>
 *   <li><b>embedding 接口有单次条数上限。</b>DashScope 官方 FAQ 的原话是
 *       「单次 API 调用最多接受 10 条文本，数据量更大时拆分为每批 10 条」。
 *       迭代 13 切出 19 个块，一次全送会直接报 InvalidParameter。
 *       所以这里<b>自己控批大小</b>，不指望框架代劳。</li>
 *   <li><b>pgvector 的主键列是 uuid。</b>{@code vector_store.id} 的类型是 uuid，
 *       而我们的块 id 长这样：{@code 01-销售数据统计口径说明#0}——插进去会报
 *       "invalid input syntax for type uuid"。办法是<b>不指定 id</b>
 *       （让 Spring AI 生成 uuid 主键），把业务 id 塞进元数据，检索出来再取回去。
 *       这是个很典型的「存储层的主键约束反过来改你的数据模型」。</li>
 *   <li><b>别重复入库。</b>embedding 是收费的，而且每次启动都重灌既慢又没必要。
 *       所以<b>表为空才灌</b>；要重灌就把表清掉（见 docs/14-向量检索接通.md）。</li>
 * </ol>
 */
public class CorpusIngestor {

    private static final Logger log = LoggerFactory.getLogger(CorpusIngestor.class);

    /**
     * 元数据键：迭代 13 的块 id（形如 {@code 文档id#序号}）。
     *
     * <p>业务 id 为什么放元数据而不放主键：见类注释第 2 条。
     */
    public static final String META_CHUNK_ID = "chunk_id";

    /**
     * 每次调 embedding 接口的批大小。
     *
     * <p>DashScope 的上限是 10，这里就取 10——调大不会更快，只会 400。
     */
    private static final int EMBED_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final int chunkSize;
    private final String tableName;

    public CorpusIngestor(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                          int chunkSize, String tableName) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.chunkSize = chunkSize;
        this.tableName = tableName;
    }

    /**
     * 表为空才灌。
     *
     * @return 实际灌入的片段数；跳过时返回 0
     */
    public int ingestIfEmpty() {
        int existing = countRows();
        if (existing > 0) {
            log.info("向量库已有 {} 个片段，跳过入库（想重灌见 docs/14-向量检索接通.md）", existing);
            return 0;
        }

        List<Chunk> chunks = DocumentChunker.of(this.chunkSize).split(CorpusLoader.loadDocuments());
        List<Document> docs = chunks.stream().map(this::toDocument).toList();

        int batches = (docs.size() + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE;
        log.info("准备入库：{} 个片段（chunkSize={} token），分 {} 批（每批 ≤{} 条）",
                docs.size(), this.chunkSize, batches, EMBED_BATCH_SIZE);

        long t0 = System.currentTimeMillis();
        int added = 0;

        // 分批，而不是一次 add 全部——原因见类注释第 1 条
        for (int i = 0; i < docs.size(); i += EMBED_BATCH_SIZE) {
            List<Document> batch = docs.subList(i, Math.min(i + EMBED_BATCH_SIZE, docs.size()));
            this.vectorStore.add(batch);   // 这一步内部会调 embedding 接口
            added += batch.size();
            log.info("  已入库 {}/{}", added, docs.size());
        }

        log.info("入库完成：{} 个片段，耗时 {} ms", added, System.currentTimeMillis() - t0);
        return added;
    }

    /**
     * 片段 → 向量库文档。
     *
     * <p><b>刻意不传 id。</b>{@code new Document(text, metadata)} 会让 Spring AI
     * 生成一个 uuid 当主键，业务 id 进元数据。原因见类注释第 2 条。
     */
    private Document toDocument(Chunk chunk) {
        Map<String, Object> metadata = Map.of(
                META_CHUNK_ID, chunk.id(),
                CorpusLoader.META_SOURCE, chunk.source());
        return new Document(chunk.content(), metadata);
    }

    /**
     * 数一下表里有多少行。
     *
     * <p>用 JdbcTemplate 直接问数据库，比「检索一下看有没有结果」更准也更便宜——
     * 后者要付一次 embedding 调用，而且「没结果」不等于「表里没数据」。
     */
    private int countRows() {
        Long n = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + this.tableName, Long.class);
        return n == null ? 0 : n.intValue();
    }
}
