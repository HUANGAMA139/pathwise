package com.example.smartdataqa.tools.rerank;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentChunks;
import com.example.smartdataqa.tools.DocumentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 迭代 17 · <b>两阶段检索</b>：粗排召回 → 精排打分。
 *
 * <pre>
 *   {召回器}  ──取 N 个候选──>  {DocumentPostProcessor}  ──重排──>  取前 K 个
 *    vector                       LlmReranker（相关性）
 *    或 hybrid                    MmrDocumentPostProcessor（多样性）
 *    （迭代 14/16）                （迭代 17）
 * </pre>
 *
 * <h3>为什么要两阶段，而不是一步到位</h3>
 * 精排很贵（要读正文、要调模型），<b>不可能对全库做</b>；而粗排很快但不够准。
 * 所以业界的形状固定是「粗排多召回一些，精排挑出最好的」——<b>召回看速度，精排看质量</b>。
 * 这也解释了 {@link #candidates} 这个参数为什么重要：<b>精排再好也救不回没被召回的片段</b>，
 * 候选池是精排的天花板。
 *
 * <h3>为什么不直接改召回器</h3>
 * 这个类<b>只做包装</b>，召回逻辑一行不碰。好处是「召回方式」和「后处理方式」正交——
 * {@code sdaq.retrieval.mode}（vector/hybrid）× {@code sdaq.rerank.mode}（none/llm/mmr）
 * 六种组合都能装出来，<b>任意一天的基线都能一键复现</b>。
 * 这是 迭代 15/16 那两条教训（旧基线必须跑得回来）在这里的延续。
 */
public class RerankedDocumentTool implements DocumentTool {

    private static final Logger log = LoggerFactory.getLogger(RerankedDocumentTool.class);

    private final DocumentTool recaller;
    private final DocumentPostProcessor postProcessor;
    private final int candidates;

    public RerankedDocumentTool(DocumentTool recaller, DocumentPostProcessor postProcessor, int candidates) {
        this.recaller = recaller;
        this.postProcessor = postProcessor;
        this.candidates = candidates;
    }

    @Override
    public List<Chunk> search(String query, int topK) {
        log.info("      [DocumentTool] 两阶段检索：粗排取 {} → 精排取 {}", candidates, topK);

        List<Chunk> recalled = this.recaller.search(query, this.candidates);
        if (recalled.isEmpty()) {
            return recalled;
        }

        List<Document> processed = this.postProcessor.process(
                new Query(query), DocumentChunks.toDocuments(recalled));
        List<Document> top = processed.subList(0, Math.min(topK, processed.size()));

        // 重排前后的顺序都打出来——这是判断精排到底有没有起作用的第一手证据。
        // 只打分数看不出来：重排把 score 的含义换成了名次分（见 LlmReranker），跨阶段比分数没有意义。
        log.info("      [两阶段] 粗排顺序：{}", join(recalled.stream().map(Chunk::id).toList()));
        log.info("      [两阶段] 精排顺序：{}", join(top.stream().map(Document::getId).toList()));
        return DocumentChunks.toChunks(top);
    }

    /**
     * 把一串片段 id 压成短形式便于阅读：{@code 01-销售数据统计口径说明#2} → {@code 01#2}。
     *
     * <p>10 个候选的全名加起来几百字符，日志会完全没法看；
     * 而压缩后保留的「文档编号 + 片段序号」正好是判断顺序变没变所需要的信息。
     */
    private static String join(List<String> ids) {
        return ids.stream().map(RerankedDocumentTool::compact).collect(Collectors.joining(", "));
    }

    static String compact(String chunkId) {
        int hash = chunkId.indexOf('#');
        if (hash <= 0) {
            return chunkId;
        }
        String doc = chunkId.substring(0, hash);
        int dash = doc.indexOf('-');
        return (dash > 0 ? doc.substring(0, dash) : doc) + chunkId.substring(hash);
    }
}
