package com.example.smartdataqa.config;

import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.context.ChatSummarizer;
import com.example.smartdataqa.context.ContextCompactor;
import com.example.smartdataqa.rag.pipeline.DefaultContextAugmenter;
import com.example.smartdataqa.rag.pipeline.LlmQueryRewriter;
import com.example.smartdataqa.rag.pipeline.NoOpQueryRewriter;
import com.example.smartdataqa.rag.pipeline.QueryRewriter;
import com.example.smartdataqa.rag.pipeline.RagPipeline;
import com.example.smartdataqa.router.DataSourceManifest;
import com.example.smartdataqa.router.KeywordQueryRouter;import com.example.smartdataqa.router.LlmQueryRouter;
import com.example.smartdataqa.router.QueryRouter;
import com.example.smartdataqa.service.AnswerCache;
import com.example.smartdataqa.service.QaService;
import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DatabaseTool;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.corpus.CorpusLoader;
import com.example.smartdataqa.tools.corpus.DocumentChunker;
import com.example.smartdataqa.tools.hybrid.HybridDocumentTool;
import com.example.smartdataqa.tools.mock.MockDatabaseTool;
import com.example.smartdataqa.tools.mock.MockDocumentTool;
import com.example.smartdataqa.tools.rerank.LlmReranker;
import com.example.smartdataqa.tools.rerank.MmrDocumentPostProcessor;
import com.example.smartdataqa.tools.rerank.RerankedDocumentTool;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.ColumnMasker;
import com.example.smartdataqa.tools.text2sql.FewShotExamples;
import com.example.smartdataqa.tools.text2sql.HybridTableSelector;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import com.example.smartdataqa.tools.text2sql.SqlGuard;
import com.example.smartdataqa.tools.text2sql.TableSelector;
import com.example.smartdataqa.tools.text2sql.Text2SqlDatabaseTool;
import com.example.smartdataqa.tools.vector.CorpusIngestor;
import com.example.smartdataqa.tools.vector.VectorDocumentTool;
import com.example.smartdataqa.web.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 迭代 10 · 把核心组件交给 Spring 管理。
 *
 * <h3>为什么要加这个类</h3>
 * 前面几天这些组件都是在各自的演示类里 {@code new} 出来的——
 * 那样对「一次性跑一遍看日志」是合适的，`SkeletonDemo` 独立可读。
 *
 * <p>但本轮不一样：<b>MCP Server 是一个常驻能力</b>，它需要拿到工具和 Agent。
 * 常驻组件不该自己 new 依赖，应该由容器注入。迭代 11 的对外接口也会需要同一个 Agent 实例。
 *
 * <h3>顺带解决的一件事：路由器的选择集中在一处</h3>
 * 「用哪个路由实现」原本散在演示代码里。现在收到 {@link #queryRouter} 一个方法里——
 * 迭代 24 做多策略对比时，要换的也只是这一个地方。
 *
 * <p>注意 {@code LlmQueryRouter} 的降级链也是在这里装配的：
 * 主用 LLM，失败落到关键词路由。这是「把变化的部分隔离开」那个手法第三次出现
 * （前两次是 {@code ContextCompactor.Summarizer} 和 {@code QueryRouter} 本身）。
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /**
     * 文档支路（迭代 16 起）：检索方式由 {@code sdaq.retrieval.mode} 决定。
     *
     * <pre>
     *   vector = 只向量（迭代 14 的基线，**可复现**）
     *   bm25   = 只关键词（BM25）
     *   hybrid = 双通道 + RRF 融合（默认）
     * </pre>
     *
     * <p><b>为什么要做成开关而不是直接换掉：</b>这是一个「靠对比数字说话」的项目——
     * 一旦旧基线跑不了了，以后任何一次改进都失去了参照。迭代 15 那条
     * 「metrics.csv 追加而不是覆盖」是同一个道理，只是这次管的是代码。
     *
     * <p>整条装配是：
     * <pre>
     *   启动时一次：CorpusLoader.loadDocuments() → DocumentChunker.split() → 入库 → VectorStore
     *   每次提问：  向量通道 + BM25 通道 → RRF 融合 → DocumentTool
     * </pre>
     */
    /**
     * 文档支路：<b>召回方式 × 精排方式</b>两个正交的开关决定。
     *
     * <pre>
     *   召回 sdaq.retrieval.mode  vector | bm25 | hybrid
     *   精排 sdaq.rerank.mode     none   | llm  | mmr
     * </pre>
     *
     * <p><b>为什么做成两个正交的开关，而不是一个「当前最佳配置」：</b>
     * 这是「靠对比数字说话」的项目——迭代 15 的基线是 vector+none，迭代 16 是 hybrid+none，
     * 本轮要测的是「在召回之上再加精排能带来什么」。
     * <b>如果配置只有一档，前面任意一天的基线就再也跑不回来了，对比也就无从谈起。</b>
     *
     * <p>两阶段的分工：<b>召回多取一些（{@code candidates}），精排挑出最好的 top-K</b>。
     * 精排再好也救不回没被召回的片段——候选池是精排的天花板。
     *
     * <p>默认把精排设成 {@code llm}（迭代 17 的交付物就是把它接进来）；
     * 如果 A/B 显示它赚不回那一次模型调用的成本，把 {@code sdaq.rerank.mode} 改回 {@code none} 即可
     * ——<b>这是配置的事，不是改代码的事。</b>
     */
    @Bean
    @Primary
    public DocumentTool documentTool(VectorStore vectorStore,
                                     HybridDocumentTool hybridDocumentTool,
                                     EmbeddingModel embeddingModel,
                                     ChatClient.Builder chatClientBuilder,
                                     @Value("${sdaq.retrieval.mode:hybrid}") String retrievalMode,
                                     @Value("${sdaq.rerank.mode:llm}") String rerankMode,
                                     @Value("${sdaq.rerank.candidates:10}") int rerankCandidates,
                                     @Value("${sdaq.rerank.mmr-lambda:0.7}") double mmrLambda) {

        DocumentTool recaller = buildRecaller(retrievalMode, vectorStore, hybridDocumentTool);
        DocumentPostProcessor processor = buildProcessor(rerankMode, embeddingModel, chatClientBuilder, mmrLambda);

        if (processor == null) {
            log.info("文档支路装配完成：召回 = {}，不做精排", retrievalMode);
            return recaller;
        }
        log.info("文档支路装配完成：召回 = {} ＋ 精排 = {}（粗排取 {} 个候选）",
                retrievalMode, rerankMode, rerankCandidates);
        return new RerankedDocumentTool(recaller, processor, rerankCandidates);
    }

    /**
     * 迭代 26：把「召回器」和「重排器」的构造抽成两个私有方法。
     *
     * <p><b>为什么抽</b>：{@code documentTool}（老装配）和 {@code ragPipeline}（新五层）都要造这两样东西。
     * 各造一份的话，两边的档位解析迟早写岔——而写岔不会报错，
     * 只会让「新老两条路」比出来的东西根本不是一回事。
     */
    private static DocumentTool buildRecaller(String mode, VectorStore vectorStore, HybridDocumentTool hybrid) {
        return switch (mode) {
            case "vector" -> new VectorDocumentTool(vectorStore);
            case "bm25" -> hybrid::searchBm25;   // DocumentTool 是函数式接口，直接引用即可
            case "hybrid" -> hybrid;
            default -> throw new IllegalStateException(
                    "未知的 sdaq.retrieval.mode：" + mode + "（可选 vector | bm25 | hybrid）");
        };
    }

    private static DocumentPostProcessor buildProcessor(String mode, EmbeddingModel embeddingModel,
                                                        ChatClient.Builder chatClientBuilder, double mmrLambda) {
        return switch (mode) {
            case "none" -> null;
            case "llm" -> new LlmReranker(chatClientBuilder.build());
            case "mmr" -> new MmrDocumentPostProcessor(embeddingModel, mmrLambda);
            default -> throw new IllegalStateException(
                    "未知的 sdaq.rerank.mode：" + mode + "（可选 none | llm | mmr）");
        };
    }

    /**
     * 迭代 26 · <b>显式五层 RAG pipeline</b>（改写 → 检索/融合 → 重排 → 注入）。
     *
     * <p><b>为什么「构建了但默认不接进主干」</b>：它和 {@link #documentTool} 用的是同一套档位，
     * 所以建它是廉价的；而接进主干会改动 RAG 主链路——<b>凡是会动主链路的事，都要先证明它是纯重构</b>。
     * 迭代 26 的消融 demo 里有一段回归检查专门做这件事；确认通过后再打开
     * {@code sdaq.rag.pipeline.enabled}。<b>量过才开</b>（同 迭代 20 的 few-shot-size）。
     */
    @Bean
    public RagPipeline ragPipeline(VectorStore vectorStore,
                                   HybridDocumentTool hybridDocumentTool,
                                   EmbeddingModel embeddingModel,
                                   ChatClient.Builder chatClientBuilder,
                                   @Value("${sdaq.retrieval.mode:hybrid}") String retrievalMode,
                                   @Value("${sdaq.rerank.mode:llm}") String rerankMode,
                                   @Value("${sdaq.rerank.candidates:10}") int rerankCandidates,
                                   @Value("${sdaq.rerank.mmr-lambda:0.7}") double mmrLambda,
                                   @Value("${sdaq.rag.rewrite.mode:none}") String rewriteMode) {

        QueryRewriter rewriter = switch (rewriteMode) {
            case "none" -> new NoOpQueryRewriter();
            case "llm" -> new LlmQueryRewriter(chatClientBuilder.build());
            default -> throw new IllegalStateException(
                    "未知的 sdaq.rag.rewrite.mode：" + rewriteMode + "（可选 none | llm）");
        };
        RagPipeline pipeline = new RagPipeline(
                rewriter,
                buildRecaller(retrievalMode, vectorStore, hybridDocumentTool),
                buildProcessor(rerankMode, embeddingModel, chatClientBuilder, mmrLambda),
                rerankCandidates,
                new DefaultContextAugmenter());
        log.info("显式五层 pipeline 已构建：{}", pipeline.describe());
        return pipeline;
    }

    /**
     * 混合检索工具（向量 + BM25 + RRF）。单独一个 bean，因为
     * {@link #documentTool} 要按配置决定用不用它，而 迭代 16 的对比实验三种方式都要用。
     *
     * <p>BM25 索引是**懒加载**的，所以即使 {@code mode=vector} 也不会白读一次库。
     */
    @Bean
    public HybridDocumentTool hybridDocumentTool(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
            @Value("${sdaq.hybrid.candidates:10}") int candidates,
            @Value("${sdaq.hybrid.rrf-k:30}") int rrfK) {
        log.info("混合检索参数：每路候选 {} 个，RRF k={}", candidates, rrfK);
        return HybridDocumentTool.fromDatabase(vectorStore, jdbcTemplate, tableName, candidates, rrfK);
    }

    /**
     * 历史 demo 专用的 mock 文档支路（迭代 8 / 9 / 12 显式指名用它）。
     *
     * <p><b>为什么还留着它：</b>迭代 12 那个 demo 演示的正是「字符匹配的局限」，
     * 而那正好是 迭代 14（向量检索）和 迭代 16（混合检索）存在的理由。
     * 要是让它也跟着换成向量检索，它记录的结论就和实际输出对不上了——
     * 拿新实现去污染历史记录，是不划算的。
     *
     * <p>注意这两个 bean 的区别只在「检索方式」；入库用的分块参数必须一致，
     * 否则两边看到的片段集合不同，对比就没意义了。
     *
     * @param chunkSize 分块大小（token），与 {@link #ingestCorpusRunner} 用同一个配置项
     */
    @Bean
    public DocumentTool mockDocumentTool(
            @Value("${sdaq.corpus.chunk-size:" + DocumentChunker.DEFAULT_CHUNK_SIZE + "}") int chunkSize) {

        List<Document> docs = CorpusLoader.loadDocuments();
        DocumentChunker chunker = DocumentChunker.of(chunkSize);
        List<Chunk> chunks = chunker.split(docs);

        log.info("mock 文档支路装配完成（仅历史 demo 使用）：{} 份文档 → {} 个片段",
                docs.size(), chunks.size());
        return new MockDocumentTool(chunks);
    }

    /**
     * 启动时把语料灌进向量库（<b>表空才灌</b>，理由见 {@link CorpusIngestor}）。
     *
     * <p><b>为什么标 {@code @Order(HIGHEST_PRECEDENCE)}：</b>
     * 迭代 14 的对比 demo 一启动就要检索，必须排在其他 {@code ApplicationRunner} 之前。
     * 其余 demo 用的是 mock，不受影响——顺序只影响真正读向量库的那个。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner ingestCorpusRunner(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            @Value("${sdaq.corpus.chunk-size:" + DocumentChunker.DEFAULT_CHUNK_SIZE + "}") int chunkSize,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {

        return args -> new CorpusIngestor(vectorStore, jdbcTemplate, chunkSize, tableName).ingestIfEmpty();
    }

    /**
     * 业务库连接（迭代 18）。<b>默认 5433</b>，和 5432 的向量库物理隔离。
     *
     * <p>为什么隔离成两个库而不是一个库两张 schema：做到 迭代 21（SQL 安全边界）时，
     * Agent 生成的 SQL 要能碰到业务数据、又完全够不到向量库。物理隔离比配权限省心。
     *
     * <p><b>注意这里注册的是 {@link BusinessDb}，不是一个 {@code DataSource} bean。</b>
     * 原因见 {@link BusinessDb} 的类注释——注册 DataSource 会让 Spring Boot 的
     * 数据源自动配置整体让位，连带把 {@code PgVectorStore} 和 迭代 16 的混合检索一起弄坏。
     * 这是本轮最容易看不出来的一个坑。
     */
    @Bean
    public BusinessDb businessDb(
            @Value("${sdaq.business-db.url:jdbc:postgresql://localhost:5433/business_db}") String url,
            @Value("${sdaq.business-db.username:sdaq}") String username,
            @Value("${sdaq.business-db.password:sdaq_dev_pwd}") String password,
            @Value("${sdaq.business-db.query-timeout-seconds:15}") int queryTimeoutSeconds) {
        log.info("业务库配置：{}（查询超时 {}s）", url, queryTimeoutSeconds);
        return new BusinessDb(url, username, password, queryTimeoutSeconds);
    }

    /**
     * 数据库支路。<b>迭代 18 起默认是真的了</b>（迭代 8—17 一直是 mock）。
     *
     * <pre>
     *   sdaq.database.mode  text2sql | mock
     *     text2sql = 表结构注入 → 模型生成 SQL → 执行（默认）
     *     mock     = 迭代 8 的硬编码实现，可一键复现旧基线
     * </pre>
     *
     * <p><b>为什么还要留 mock 这一档：</b>和 迭代 14 保留 {@code mockDocumentTool}、
     * 迭代 16/17 保留 {@code retrieval.mode} 是同一个理由——这是「靠对比数字说话」的项目，
     * 旧基线一旦跑不回来，以后任何一次改进都失去了参照。
     *
     * <p><b>为什么标 {@code @Primary}：</b>容器里有两个 {@code DatabaseTool}
     * （真的 + 历史的 mock），不指定主候选的话，{@code SmartQaAgent} 和
     * {@code SmartDataQaMcpTools} 的按类型注入会变成「候选不唯一」而启动失败。
     * 主链路用真的，历史 demo 显式指名 mock —— 和 迭代 14 处理两个 DocumentTool 的方式一致。
     *
     * <p>顺带一提：{@code ChatClient} 在这里是<b>为了生成 SQL 而单独 build 的一个</b>，
     * 和路由、综合回答用的不是一个实例——它们各自的 system prompt 完全不同。
     */
    /**
     * 迭代 20 · few-shot 样例（从 classpath 的 {@code text2sql/few-shot.md} 读）。
     *
     * <p><b>做成一启动就加载</b>，有两个理由：
     * <ol>
     *   <li>样例文件写坏了，希望在<b>启动时</b>炸，而不是等第一次提问才发现；</li>
     *   <li>加载器对「文件在、却一条都没解析出来」是<b>抛异常</b>的——
     *       那正是最难发现的静默失败：程序照跑，只是 few-shot 悄悄没生效，
     *       你会以为是「样例没用」，实际是格式写错了。</li>
     * </ol>
     * 注意它<b>总是</b>加载（哪怕 {@code few-shot-size=0}），这样文件一直是校验过的。
     */
    @Bean
    public FewShotExamples fewShotExamples() {
        return FewShotExamples.load();
    }

    @Bean
    @Primary
    public DatabaseTool databaseTool(BusinessDb businessDb,
                                     ChatClient.Builder chatClientBuilder,
                                     EmbeddingModel embeddingModel,
                                     FewShotExamples fewShotExamples,
                                     @Value("${sdaq.database.mode:text2sql}") String mode,
                                     @Value("${sdaq.text2sql.max-context-rows:50}") int maxContextRows,
                                     @Value("${sdaq.text2sql.max-rows:1000}") int maxRows,
                                     @Value("${sdaq.text2sql.few-shot-size:0}") int fewShotSize,
                                     @Value("${sdaq.text2sql.repair-attempts:2}") int repairAttempts,
                                     @Value("${sdaq.text2sql.masked-columns:}") String maskedColumns,
                                     @Value("${sdaq.table-select.mode:hybrid}") String tableSelectMode,
                                     @Value("${sdaq.table-select.top-k:8}") int tableTopK,
                                     @Value("${sdaq.table-select.candidates:10}") int tableCandidates,
                                     @Value("${sdaq.table-select.rrf-k:30}") int tableRrfK) {

        if ("mock".equals(mode)) {
            log.info("数据库支路装配完成：mock（迭代 8—17 的形态，可复现）");
            return new MockDatabaseTool();
        }
        if (!"text2sql".equals(mode)) {
            throw new IllegalStateException(
                    "未知的 sdaq.database.mode：" + mode + "（可选 text2sql | mock）");
        }

        JdbcTemplate businessJdbc = businessDb.jdbc();
        SchemaRenderer schemaRenderer = new SchemaRenderer(businessJdbc);
        TableSelector tableSelector = buildTableSelector(
                schemaRenderer, embeddingModel, tableSelectMode, tableTopK, tableCandidates, tableRrfK);

        // 迭代 21 · 安全边界的两半：准入（这条 SQL 能不能跑）+ 出参（跑出来的能不能给）。
        // 两个都给默认值、都不允许为 null —— 具体理由见 Text2SqlDatabaseTool.Options 的紧凑构造器。
        SqlGuard guard = new SqlGuard();
        ColumnMasker masker = ColumnMasker.fromCsv(maskedColumns);
        if (masker.size() == 0) {
            log.warn("脱敏：未配置任何列（sdaq.text2sql.masked-columns 为空）—— 结果里的敏感列会原样输出");
        }

        Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(
                businessJdbc,
                chatClientBuilder.build(),
                schemaRenderer,
                new Text2SqlDatabaseTool.Options(
                        maxContextRows,
                        maxRows,
                        tableSelector,
                        tableTopK,
                        fewShotExamples.render(fewShotSize),
                        repairAttempts,
                        null,
                        guard,
                        masker));
        log.info("数据库支路装配完成：Text2SQL（{} → 生成 SQL → 执行{}），放进上下文的最大行数 {}",
                tableSelector == null ? "注入全部表结构" : "先筛表再注入表结构",
                repairAttempts > 0 ? "，失败自修复最多 " + repairAttempts + " 次" : "",
                maxContextRows);
        if (fewShotSize > 0) {
            log.info("few-shot：注入前 {} 条（共 {} 条）",
                    Math.min(fewShotSize, fewShotExamples.size()), fewShotExamples.size());
        }
        else {
            log.info("few-shot：关闭（要开就把 sdaq.text2sql.few-shot-size 设为条数）");
        }
        return tool;
    }

    /**
     * 迭代 19 · 表筛选器：表多了不能全量注入，先按问题召回最相关的几张。
     *
     * <pre>
     *   sdaq.table-select.mode  none | bm25 | vector | hybrid
     *     none   = 不筛，注入全部表（= 迭代 18 的形态，可复现）
     *     bm25   = 只关键词
     *     vector = 只语义
     *     hybrid = 两路 + RRF 融合（默认）
     * </pre>
     *
     * <p><b>为什么是这四档、而不是一个「当前最佳」：</b>同 迭代 16/17 的理由——
     * 这是「靠对比数字说话」的项目，没有单路对照就说不出「融合好多少」，
     * 也分不清「方法不行」和「参数不行」。
     *
     * <p><b>为什么返回 null 表示关闭，而不是搞个空实现：</b>照抄 迭代 17 精排器那招
     * （{@code DocumentPostProcessor processor = null}）。关掉之后
     * {@link Text2SqlDatabaseTool} 走的代码路径和 迭代 18 完全一致，旧基线才真的跑得回来。
     *
     * <p>注意这里传的是 {@code schemaRenderer::catalog}（供应商）而不是现成的目录 ——
     * 表目录要读业务库元数据，<b>在 bean 构造时读会让「业务库没起」变成启动失败</b>，
     * 而 迭代 18 特意设计成「业务库不在也能启动」。
     */
    private static TableSelector buildTableSelector(SchemaRenderer schemaRenderer,
                                                    EmbeddingModel embeddingModel,
                                                    String mode,
                                                    int topK,
                                                    int candidates,
                                                    int rrfK) {
        if ("none".equals(mode)) {
            log.info("表筛选：关闭（注入全部表结构 = 迭代 18 的形态，可复现）");
            return null;
        }
        if (!"bm25".equals(mode) && !"vector".equals(mode) && !"hybrid".equals(mode)) {
            throw new IllegalStateException("未知的 sdaq.table-select.mode：" + mode
                    + "（可选 none | bm25 | vector | hybrid）");
        }
        HybridTableSelector hybrid = new HybridTableSelector(
                schemaRenderer::catalog, embeddingModel, candidates, rrfK);
        log.info("表筛选：{}，最多注入 {} 张表", mode, topK);
        return switch (mode) {
            case "bm25" -> hybrid::selectBm25;
            case "vector" -> hybrid::selectVector;
            default -> hybrid;
        };
    }

    /**
     * 历史 demo 专用的 mock 数据库支路（迭代 8 / 9 的基线）。
     *
     * <p>和 {@code mockDocumentTool} 同一个理由：迭代 8 那个 demo 演示的正是
     * 「关键词路由 + mock 数据」的形态，它的输出是一份<b>历史记录</b>。
     * 要是让它跟着换成真实 Text2SQL，那份记录就和当时实际跑出来的东西对不上了。
     *
     * <p>注意 {@code SkeletonDemo} / {@code RouterCompareDemo} 是直接 {@code new MockDatabaseTool()} 的
     * （它们本来就没有依赖），这个 bean 是给「想从容器里指名拿 mock」的场景留的。
     */
    @Bean
    public DatabaseTool mockDatabaseTool() {
        return new MockDatabaseTool();
    }

    /**
     * 路由器。主用 LLM 意图分类，失败降级到关键词规则。
     *
     * <p>迭代 23 起多给了一样东西：<b>「我手上有什么」</b>——语料清单 + 表清单，
     * 由 {@link #describeDataSources} 从真实来源生成（不再手写，手写的会过期）。
     *
     * @param chatClientBuilder Spring AI 自动配置提供的构建器
     */
    @Bean
    public QueryRouter queryRouter(ChatClient.Builder chatClientBuilder, BusinessDb businessDb) {
        KeywordQueryRouter fallback = new KeywordQueryRouter();
        QueryRouter router = new LlmQueryRouter(chatClientBuilder.build(), fallback,
                () -> DataSourceManifest.describe(businessDb));
        log.info("路由策略装配完成：主 {}，降级 {}（迭代 23 起已接入「我手上有什么」）",
                router.strategyName(), fallback.strategyName());
        return router;
    }

    // 迭代 24：清单的生成搬到了 router/DataSourceManifest —— 因为 迭代 24 的策略对比里，
    // 「A · LLM + 清单」那一行必须和产品路径【一字不差】。两份各拼一份迟早写岔，
    // 而写岔了不会报错，只会给出一个没法解释的准确率。

    /** Agent 主干。MCP Server 和 迭代 11 的对外接口都用这一个实例。 */
    @Bean
    public SmartQaAgent smartQaAgent(QueryRouter queryRouter,
                                     DocumentTool documentTool,
                                     DatabaseTool databaseTool,
                                     ChatClient.Builder chatClientBuilder,
                                     RagPipeline ragPipeline,
                                     @Value("${sdaq.rag.pipeline.enabled:false}") boolean pipelineEnabled) {
        SmartQaAgent agent = new SmartQaAgent(queryRouter, documentTool, databaseTool,
                chatClientBuilder.build(), pipelineEnabled ? ragPipeline : null);
        log.info("Agent 主干装配完成：文档支路 {}",
                pipelineEnabled
                        ? "走显式五层 pipeline"
                        : "走原有装配（pipeline 已构建但未接入，见 sdaq.rag.pipeline.enabled）");
        return agent;
    }

    // ==================================================================
    // 迭代 29：服务层（答案缓存 + 会话表）—— 两样都摆在 Agent 外面
    //
    // 为什么「摆在 Agent 外面」是这一天最重要的一个决定：
    //   放进 SmartQaAgent 的话，迭代 15 / 23 / 28 的评测（它们直接调 Agent）
    //   会连缓存一起命中——耗时和结果都会变，而且不报任何错，历史基线当场失效。
    //   摆在服务层之后，评测走不到它，只有 REST / MCP 这两条对外路径用得到。
    // ==================================================================

    /**
     * 答案缓存。<b>默认开</b>——它敢默认开的前提正是「评测路径走不到这里」。
     */
    @Bean
    @ConditionalOnProperty(name = "sdaq.cache.enabled", havingValue = "true", matchIfMissing = true)
    public AnswerCache answerCache(@Value("${sdaq.cache.max-entries:200}") int maxEntries,
                                   @Value("${sdaq.cache.ttl-seconds:600}") long ttlSeconds) {
        log.info("答案缓存已开启：最多 {} 条 / TTL {} 秒（只作用于对外服务，评测路径不经过）",
                maxEntries, ttlSeconds);
        return new AnswerCache(maxEntries, ttlSeconds);
    }

    /** 服务层。缓存被关掉时这里收到 null，照样能跑（只是每次都要调模型）。 */
    @Bean
    public QaService qaService(SmartQaAgent agent, ObjectProvider<AnswerCache> cache) {
        QaService service = new QaService(agent, cache.getIfAvailable());
        log.info("服务层装配完成：答案缓存 {}", service.cacheEnabled() ? "开" : "关");
        return service;
    }

    /**
     * 会话表（多轮 HTTP 接入）。压缩复用 迭代 4 的 {@code ContextCompactor} + 迭代 28 的 {@code ChatSummarizer}。
     *
     * <p>【注意】{@code sdaq.memory.*} 的默认值（5 条 / 保留 2 条）是 <b>迭代 28 那份受控实验的值</b>
     * ——调小是为了在 5 轮对话里强制触发压缩。真实使用下应当调大，见 application.yml 的注释。
     */
    @Bean
    public SessionStore sessionStore(SmartQaAgent agent,
                                     ChatClient.Builder chatClientBuilder,
                                     @Value("${sdaq.memory.enabled:true}") boolean memoryEnabled,
                                     @Value("${sdaq.memory.max-messages:5}") int maxMessages,
                                     @Value("${sdaq.memory.keep-recent:2}") int keepRecent,
                                     @Value("${sdaq.memory.max-sessions:100}") int maxSessions) {
        ChatClient chat = chatClientBuilder.build();
        ContextCompactor compactor = memoryEnabled
                ? new ContextCompactor(new ChatSummarizer(chat), maxMessages, keepRecent)
                : null;
        log.info("会话表装配完成：最多 {} 个会话，历史{}", maxSessions,
                memoryEnabled
                        ? "超 " + maxMessages + " 条压缩、保留最近 " + keepRecent + " 条"
                        : "不压缩（每轮带全量）");
        return new SessionStore(agent, chat, compactor, maxSessions);
    }
}
