package com.example.smartdataqa.tools.vector;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentChunks;
import com.example.smartdataqa.tools.DocumentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * 迭代 14 · 文档检索支路的<b>真实实现</b>：pgvector 向量检索。
 *
 * <h3>换掉了什么、没换什么</h3>
 * 接口 {@link DocumentTool} 一个字没改——Agent 主干、路由、MCP 的代码都不知道底层换了。
 * 这是 迭代 8 把契约抽出来的第一次真正兑现：<b>换实现不改调用方</b>。
 *
 * <p>被换掉的是 {@code MockDocumentTool} 里那段 2-gram 字符匹配：
 * 它比的是「这些字有没有出现过」，现在比的是<b>语义距离</b>。
 *
 * <h3>三件必须知道的事</h3>
 * <ol>
 *   <li><b>得分语义变了，跨实现比较分数没有意义。</b>
 *       字符匹配返回的是「查询的 2-gram 有多少出现在片段里」，分布很散；
 *       余弦相似度返回的是 1 - 余弦距离，虽然值域也是 0~1，但<b>分布高度聚集</b>
 *       （一堆片段都挤在 0.6~0.8 之间）。所以「最高分 0.83」在两种实现下
 *       完全不是一回事——这条直接影响 迭代 25 设阈值：<b>阈值必须在新实现上重新标定</b>。</li>
 *   <li><b>片段 id 要从元数据里取，不能取 {@link Document#getId()}。</b>
 *       因为 pgvector 的主键列是 uuid，迭代 13 的 {@code 文档id#序号} 当主键插不进去，
 *       所以入库时把业务 id 放在了元数据里。详见 {@link CorpusIngestor}。</li>
 *   <li><b>topK 走 {@link SearchRequest}，没有 {@code similaritySearch(query, k)} 这种重载。</b>
 *       那是 0.x 的写法，1.x 已经收进 builder 了。</li>
 * </ol>
 */
public class VectorDocumentTool implements DocumentTool {

    private static final Logger log = LoggerFactory.getLogger(VectorDocumentTool.class);

    private final VectorStore vectorStore;

    public VectorDocumentTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<Chunk> search(String query, int topK) {
        log.info("      [DocumentTool] 向量检索：query=\"{}\" topK={}", shorten(query), topK);

        List<Document> hits = this.vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());

        if (hits.isEmpty()) {
            log.info("      [DocumentTool] 没有命中");
            return List.of();
        }

        log.info("      [DocumentTool] 命中 {} 个片段，最高分 {}",
                hits.size(), String.format("%.4f", DocumentChunks.scoreOf(hits.get(0))));
        return hits.stream().map(VectorDocumentTool::toChunk).toList();
    }

    /**
     * 把向量库返回的 {@link Document} 翻译回项目的 {@link Chunk} 契约。
     *
     * <p>迭代 17 起实现搬到了 {@link DocumentChunks}——这份映射到本轮已经出现三次
     * （检索结果翻译回来、交给 joiner 融合、交给 post-processor 重排），
     * 按项目里那条「三次法则」抽成了一处。这里保留这个方法只是为了让调用点不用改。
     */
    public static Chunk toChunk(Document doc) {
        return DocumentChunks.toChunk(doc);
    }

    private String shorten(String s) {
        return s == null ? "" : (s.length() <= 24 ? s : s.substring(0, 24) + "…");
    }
}
