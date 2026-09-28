package com.example.smartdataqa.rag.pipeline;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentChunks;
import com.example.smartdataqa.tools.DocumentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

/**
 * 迭代 26 · <b>把 RAG 五件套显式串起来</b>。以前这条链是「套娃」——{@code RerankedDocumentTool}
 * 包着召回器，再被 {@code SmartQaAgent} 拿着；<b>层数看得见，但层次看不出来</b>。
 *
 * <pre>
 *   ①  改写器    QueryRewriter           off | llm            ← 本轮新增
 *   ②  检索器    DocumentTool            vector | bm25 | hybrid
 *   ③  融合      （在 hybrid 内部）       RRF（Bm25Index + RrfDocumentJoiner）
 *   ④  重排器    DocumentPostProcessor   none | llm | mmr
 *   ⑤  注入器    ContextAugmenter        default             ← 本轮从主干里抽出来
 *      →  检索结果 + 拼好的材料
 * </pre>
 *
 * <h3>★ 第 ③ 层为什么没有独立字段</h3>
 * <b>融合不是一个可以「关掉」的独立动作——它只在两路都开的时候才存在。</b>
 * 所以它体现为检索器档位的一个取值：{@code hybrid} 就是「两路 + RRF」，
 * {@code vector}/{@code bm25} 就是「单路，不融合」。
 * <b>把融合单独做成一个开关会造出一个无意义的组合</b>（单路 + 融合）。
 * 消融表里「融合」这一维就是 <code>vector/bm25/hybrid</code> 的对比——这也正是 迭代 16 量过的那个负结果。
 *
 * <h3>★ 为什么不直接用 Spring AI 的 {@code RetrievalAugmentationAdvisor}</h3>
 * 因为它<b>拥有整个请求生命周期</b>（改写→检索→注入→<b>生成</b>），
 * 而本项目的生成前面还横着一个四值路由和一条数据库支路：
 * <ul>
 *   <li>{@code both} 时它只能管文档那一半，另一半的数据库结果要另外并进来；</li>
 *   <li>它会把「检索 + 生成」绑成一个不可分的整体，而本项目需要在这两步之间
 *       <b>插入「有没有真查过」的证据</b>（迭代 21 那条防线的落点）。</li>
 * </ul>
 * <b>所以这里显式自组装、只借框架的接口（{@code Query} / {@code DocumentPostProcessor}），
 * 不借它的编排。</b>这不是「不用框架」，是「只让框架管它该管的层」——
 * 和 迭代 17「框架给插槽、插槽里放什么才是对业务的理解」是同一条判断。
 */
public class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);

    /** 一次检索的完整产物：改写后的查询 + 片段 + 拼好的材料。 */
    public record Result(String searchQuery, List<Chunk> chunks, String context) {
    }

    private final QueryRewriter rewriter;
    private final DocumentTool retriever;
    private final DocumentPostProcessor postProcessor;
    private final int candidates;
    private final ContextAugmenter augmenter;

    /**
     * @param rewriter      ① 改写器（不可为 null，用 {@link NoOpQueryRewriter} 表示关）
     * @param retriever     ②③ 检索器（含融合）
     * @param postProcessor ④ 重排器；<b>{@code null} 表示不重排</b>
     * @param candidates    ④ 的候选池大小（重排只能在这个池子里挑）
     * @param augmenter     ⑤ 注入器
     */
    public RagPipeline(QueryRewriter rewriter, DocumentTool retriever,
                       DocumentPostProcessor postProcessor, int candidates,
                       ContextAugmenter augmenter) {
        this.rewriter = rewriter;
        this.retriever = retriever;
        this.postProcessor = postProcessor;
        this.candidates = candidates;
        this.augmenter = augmenter;
    }

    /** 拼出一行人类可读的装配说明——进日志和文档，避免「代码里装了哪几层」要靠读源码。 */
    public String describe() {
        return "①改写=" + this.rewriter.name()
                + " → ②检索(含③融合)=" + (this.retriever.getClass().getSimpleName())
                + " → ④重排=" + (this.postProcessor == null ? "none" : this.postProcessor.getClass().getSimpleName())
                + " → ⑤注入=" + this.augmenter.name();
    }

    /**
     * 跑一遍五层。
     *
     * @param question 用户原始问题
     * @param finalK   最终交给模型的片段数
     */
    public Result retrieve(String question, int finalK) {
        // ① 改写
        String query = this.rewriter.rewrite(question);

        // ②③ 检索（含融合）。要重排就多取候选——精排再好也救不回没召回的片段。
        int recallK = this.postProcessor == null ? finalK : Math.max(finalK, this.candidates);
        List<Chunk> recalled = this.retriever.search(query, recallK);

        // ④ 重排
        List<Chunk> chunks = recalled;
        if (this.postProcessor != null && !recalled.isEmpty()) {
            List<Document> processed = this.postProcessor.process(
                    new Query(query), DocumentChunks.toDocuments(recalled));
            List<Document> top = processed.subList(0, Math.min(finalK, processed.size()));
            chunks = DocumentChunks.toChunks(top);
            log.info("      [Pipeline] 粗排 {} → 精排 {}", recalled.size(), chunks.size());
        }

        // ⑤ 注入
        String context = this.augmenter.augment(chunks);
        return new Result(query, chunks, context);
    }
}
