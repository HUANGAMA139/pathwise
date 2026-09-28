package com.example.smartdataqa.tools;

import com.example.smartdataqa.tools.corpus.CorpusLoader;
import com.example.smartdataqa.tools.vector.CorpusIngestor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 17 · 项目契约 {@link Chunk} 与 Spring AI 的 {@link Document} 之间的翻译。
 *
 * <h3>为什么抽出来</h3>
 * 这份映射到本轮是<b>第三次</b>要写了：
 * <pre>
 *   迭代 14  VectorDocumentTool：向量库的 Document → Chunk（检索结果翻译回项目契约）
 *   迭代 16  HybridDocumentTool：Chunk → Document（交给 DocumentJoiner 做融合）
 *   迭代 17  RerankedDocumentTool：两个方向都要（交给 DocumentPostProcessor 做重排）
 * </pre>
 * 项目里早就用过这个判据（第一次写、第二次忍、第三次抽——{@code ConsoleText} 就是这么来的）。
 * <b>更实际的理由是「一致性」</b>：这份映射定义了「元数据里的哪个键是业务 id」，
 * 两边各写一份迟早写岔，而写岔的后果是融合或重排<b>悄悄对不上号</b>——迭代 16 那个
 * 「0 个被两路同时命中」的自检就是为这件事准备的。
 */
public final class DocumentChunks {

    private DocumentChunks() {
    }

    /**
     * Document → Chunk。
     *
     * <p>id 优先取元数据里的 {@code chunk_id}（业务 id），而不是 {@code Document#getId()}——
     * 因为 pgvector 的主键是 uuid，业务 id 只能从元数据里找（迭代 14 的坑）。
     */
    public static Chunk toChunk(Document doc) {
        String id = String.valueOf(
                doc.getMetadata().getOrDefault(CorpusIngestor.META_CHUNK_ID, doc.getId()));
        String source = String.valueOf(
                doc.getMetadata().getOrDefault(CorpusLoader.META_SOURCE, ""));
        return new Chunk(id, doc.getText(), source, scoreOf(doc));
    }

    /**
     * Chunk → Document。两个元数据键都带上，这样再翻回来时 id 和出处都还在。
     *
     * <p>用 {@code HashMap} 而不是 {@code Map.of}：后者不接受 null 值，
     * 而 {@code source} 万一为空会直接抛 NPE——翻译函数不该成为新的崩溃点。
     */
    public static Document toDocument(Chunk chunk) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(CorpusIngestor.META_CHUNK_ID, chunk.id());
        meta.put(CorpusLoader.META_SOURCE, chunk.source() == null ? "" : chunk.source());
        return Document.builder().id(chunk.id()).text(chunk.content()).metadata(meta).build();
    }

    public static List<Chunk> toChunks(List<Document> docs) {
        List<Chunk> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            out.add(toChunk(d));
        }
        return out;
    }

    public static List<Document> toDocuments(List<Chunk> chunks) {
        List<Document> out = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            out.add(toDocument(c));
        }
        return out;
    }

    /** {@link Document#getScore()} 是可空类型：不是每次检索都会带分数。 */
    public static double scoreOf(Document doc) {
        Double s = doc.getScore();
        return s == null ? 0.0 : s;
    }
}
