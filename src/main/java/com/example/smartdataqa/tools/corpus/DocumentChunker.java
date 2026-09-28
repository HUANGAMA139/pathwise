package com.example.smartdataqa.tools.corpus;

import com.example.smartdataqa.tools.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 13 · 文档分块器。用 Spring AI 的 {@link TokenTextSplitter}。
 *
 * <h3>分块为什么是 RAG 里最被低估的环节</h3>
 * 它决定了<b>检索的最小单位</b>。后面所有优化——混合检索、重排、查询改写——
 * 都是在这个单位上做文章。块切得不对，后面做什么都救不回来。
 *
 * <p>两个方向的代价是相反的：
 * <ul>
 *   <li><b>块太大</b>：一个块里混着答案和一堆无关内容。检索命中了，
 *       但把噪声一起喂给了模型（迭代 12 的「整篇文档当一个片段」就是这个极端）</li>
 *   <li><b>块太小</b>：一个块失去上下文就讲不清自己。
 *       比如表格的某一行脱离了表头，读者不知道那一列是什么</li>
 * </ul>
 *
 * <h3>两个参数的真实含义 —— 读 1.1.0 源码核实过</h3>
 * 这两条都跟「一般说法」不一致，是这次读源码才弄清的：
 * <ol>
 *   <li><b>{@code minChunkSizeChars} 不是「小于它的块会被合并」。</b>
 *       1.1.0 的 {@code doSplit} 里它只出现一次：作为<b>标点截断的字符下标下界</b>——
 *       只有当「窗口内最后一个标点」的位置大于它，才允许在该标点处截断。
 *       所以它偏大时（框架默认 350），小块永远够不到这个下界，
 *       只能落在 token 边界上被<b>硬切</b>。表现出来像是「chunkSize 没生效」，
 *       实际是截断被这个下界挡住了。<b>要切小块，必须把它一起调小。</b></li>
 *   <li><b>1.1.0 认识的标点是硬编码的：{@code . ? !} 和换行，没有中文标点。</b>
 *       而且这个版本<b>没有</b>「自定义标点」的构造参数——
 *       带 {@code punctuationMarks} 的构造器是 <b>1.1.3 才加的</b>。
 *       本项目锁在 1.1.0（spring-ai-alibaba 1.1.0.0 把 {@code spring-ai.version}
 *       定死为 1.1.0），所以这个版本拿不到中文标点支持。</li>
 * </ol>
 *
 * <h3>那中文语料怎么办 —— 这里做的取舍</h3>
 * 6 份语料都是 <b>Markdown</b>：标题、列表、表格行、空行，换行非常密。
 * 而换行恰好是 1.1.0 认识的标点之一，所以对它来说
 * 「按换行切」约等于「按 Markdown 的结构块切」。对这个语料形态已经够用，
 * 而且实验的唯一变量（chunkSize）仍能被干净地量出来。
 *
 * <p><b>这是有意做的取舍，不是疏忽。</b>想要真正「按中文句号切」，只有两条路：
 * 把 spring-ai 升到 1.1.3 用它的 {@code withPunctuationMarks}，
 * 或者绕开 {@link TokenTextSplitter}、用同一个 jtokkit 分词器自己按句切。
 * 两条路都会动到已验证的版本矩阵，所以留到 迭代 15 之后再评估。
 *
 * <h3>还有一件事</h3>
 * {@code chunkSize} 的单位是 <b>token</b>，不是字符。
 * 中文的 token 换算和英文差别很大（一个汉字往往对应 1 个以上 token），
 * 所以「设 500」到底切出多少字，得看实际结果。
 * 本类在日志里<b>打印真实字符数</b>，就是为了让这个差异可见——
 * 不要拿 token 数当字符数去估算上下文占用。
 */
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    /** 默认分块大小（token）。取中间值，跑完对比实验再定最终值。 */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 10;
    private static final int MAX_NUM_CHUNKS = 10000;
    private static final boolean KEEP_SEPARATOR = true;

    private final int chunkSize;
    private final int minChunkSizeChars;
    private final TokenTextSplitter splitter;

    /**
     * @param chunkSize         目标块大小（token）
     * @param minChunkSizeChars 标点截断的字符下标下界（见类注释第 1 条）。
     *                          <b>要切小块就必须一起调小</b>，否则截断触发不了，
     *                          块会在 token 边界上被硬切
     */
    public DocumentChunker(int chunkSize, int minChunkSizeChars) {
        if (minChunkSizeChars > chunkSize) {
            throw new IllegalArgumentException(
                    "minChunkSizeChars(" + minChunkSizeChars + ") 不该大于 chunkSize("
                            + chunkSize + ")，否则对小窗口来说下界永远够不到，标点截断形同虚设");
        }
        this.chunkSize = chunkSize;
        this.minChunkSizeChars = minChunkSizeChars;
        // 只有 5 参构造器：1.1.0 不支持自定义标点（punctuationMarks 是 1.1.3 才有的）
        this.splitter = new TokenTextSplitter(
                chunkSize, minChunkSizeChars, MIN_CHUNK_LENGTH_TO_EMBED,
                MAX_NUM_CHUNKS, KEEP_SEPARATOR);
    }

    /**
     * 按目标块大小创建，minChunkSizeChars 自动取 chunkSize 的一半。
     *
     * <p>这个比例是个经验值：既不让小块够不到截断下界，也不至于切出大量碎片。
     */
    public static DocumentChunker of(int chunkSize) {
        return new DocumentChunker(chunkSize, minChunkSizeCharsFor(chunkSize));
    }

    /** {@link #of(int)} 用的经验公式，单独露出来方便日志和后续调参。 */
    public static int minChunkSizeCharsFor(int chunkSize) {
        return Math.max(chunkSize / 2, 20);
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int minChunkSizeChars() {
        return minChunkSizeChars;
    }

    /**
     * 把文档切成片段。
     *
     * <p><b>逐篇切，而不是一次切一批。</b>Spring AI 的 {@code apply(List)} 支持批量，
     * 但那样切完之后要判断「这个片段来自哪篇文档」就得靠元数据是否被保留——
     * 依赖这个不如逐篇处理来得确定。
     * 逐篇切的代价只是多几次循环，换来的是「片段与出处一一对应」这个确定性。
     *
     * @return 片段列表，id 形如 {@code 文档id#序号}，出处沿用原文档的标题
     */
    public List<Chunk> split(List<Document> documents) {
        List<Chunk> chunks = new ArrayList<>();

        for (Document doc : documents) {
            String source = String.valueOf(
                    doc.getMetadata().getOrDefault(CorpusLoader.META_SOURCE, doc.getId()));

            List<Document> parts = splitter.apply(List.of(doc));
            for (int i = 0; i < parts.size(); i++) {
                chunks.add(new Chunk(
                        doc.getId() + "#" + i,
                        parts.get(i).getText(),
                        source,
                        0.0));
            }
        }

        log.info("分块完成：chunkSize={} token / minChunkSizeChars={} → {} 份文档切成 {} 个片段",
                chunkSize, minChunkSizeChars, documents.size(), chunks.size());
        return chunks;
    }
}
