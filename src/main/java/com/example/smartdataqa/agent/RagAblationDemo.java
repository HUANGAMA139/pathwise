package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.eval.EvalCase;
import com.example.smartdataqa.rag.pipeline.DefaultContextAugmenter;
import com.example.smartdataqa.rag.pipeline.LlmQueryRewriter;
import com.example.smartdataqa.rag.pipeline.NoOpQueryRewriter;
import com.example.smartdataqa.rag.pipeline.QueryRewriter;
import com.example.smartdataqa.rag.pipeline.RagPipeline;
import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.hybrid.HybridDocumentTool;
import com.example.smartdataqa.tools.rerank.LlmReranker;
import com.example.smartdataqa.tools.rerank.MmrDocumentPostProcessor;
import com.example.smartdataqa.tools.rerank.RerankedDocumentTool;
import com.example.smartdataqa.tools.vector.VectorDocumentTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 26 · <b>五件套消融</b>：2 改写 × 3 检索 × 3 重排 = <b>18 组</b>，出「每层开关 → 评测分数」的表。
 *
 * <h3>它把 迭代 16/17 没补全的那部分补上</h3>
 * <pre>
 *   迭代 16  检索器那一维（vector / bm25 / hybrid）        ← 融合层
 *   迭代 17  检索 × 重排（2 × 3）                          ← 少了 bm25 那一行
 *   迭代 26  改写 × 检索 × 重排（2 × 3 × 3）                ← 补齐，并加上改写层
 * </pre>
 * <b>指标和 迭代 16/17 逐位同一套</b>（hit@1/3/5 + MRR + 文档级精确率 + 多样性），
 * 否则新表和旧表不能对着看。
 *
 * <h3>★★ 一段不能省的检查：这是不是【纯重构】</h3>
 * 五件套是把原来「套娃式」的装配（{@code RerankedDocumentTool} 包召回器）改成显式 pipeline。
 * 如果改完行为变了，<b>迭代 15/23 的所有基线都会跟着漂，而且不会报错。</b>
 * 所以下面有一段回归检查：<b>在 {@code rewrite=none} 时，逐题比对「新 pipeline 的片段 id」和「旧装配的片段 id」</b>。
 * 不一致就是重构出错，不是「效果」。
 *
 * <p><b>为什么回归检查只跑 {@code none} / {@code mmr} 两档重排</b>：{@code llm} 档是模型输出，
 * <b>两次跑本来就可能不一样</b>（迭代 17 已经写明这一点）。把它放进比对里，
 * 会把「模型抖动」误报成「重构出错」——<b>一个会误报的检查等于没有检查</b>。
 *
 * <h3>★ 第 ⑤ 层（注入器）本轮量不了，这是要说清楚的</h3>
 * 注入器影响的是<b>生成</b>，而本轮的指标全是<b>检索</b>指标——所以它在表里占一列档位，但分数它不参与。
 * 想量它得看 迭代 23 的端到端指标。**不声称它有效，只把它做成可替换的。**
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp26.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp26.enabled", havingValue = "true")
public class RagAblationDemo {

    private static final Logger log = LoggerFactory.getLogger(RagAblationDemo.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<String> REWRITES = List.of("none", "llm");
    private static final List<String> RETRIEVALS = List.of("vector", "bm25", "hybrid");
    private static final List<String> RERANKS = List.of("none", "llm", "mmr");

    /** 最终交给模型的条数。与 迭代 16/17 一致，否则不可比。 */
    private static final int FINAL_K = 5;
    /** 粗排候选数。与 迭代 16/17 一致。 */
    private static final int CANDIDATES = 10;

    private static final List<String> HEADER = List.of(
            "experiment", "ts", "config", "rewrite", "retrieval", "rerank", "questions",
            "hit@1", "hit@3", "hit@5", "mrr", "precision_doc", "diversity", "note");

    /** 一道题在一组配置上的结果。和 迭代 17 的 {@code Hit} 同一套定义。 */
    private record Hit(int rankOfFirstCorrect, int correctCount, int returned, double diversity) {

        boolean hitAt(int k) {
            return this.rankOfFirstCorrect > 0 && this.rankOfFirstCorrect <= k;
        }

        double reciprocalRank() {
            return this.rankOfFirstCorrect == 0 ? 0.0 : 1.0 / this.rankOfFirstCorrect;
        }

        double precision() {
            return this.returned == 0 ? 0.0 : (double) this.correctCount / this.returned;
        }
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner exp26RagAblation(
            VectorStore vectorStore,
            HybridDocumentTool hybrid,
            EmbeddingModel embeddingModel,
            ChatClient.Builder chatClientBuilder,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.rag-ablation-metrics:eval/rag-ablation.csv}") String outPath,
            @Value("${sdaq.rerank.mmr-lambda:0.7}") double mmrLambda,
            @Value("${sdaq.eval.experiment:exp26-rag-ablation}") String experiment) {

        return args -> run(vectorStore, hybrid, embeddingModel, chatClientBuilder,
                testsetPath, outPath, mmrLambda, experiment);
    }

    private void run(VectorStore vectorStore, HybridDocumentTool hybrid, EmbeddingModel embeddingModel,
                     ChatClient.Builder chatClientBuilder, String testsetPath, String outPath,
                     double mmrLambda, String experiment) throws Exception {

        List<EvalCase> cases = loadDocumentCases(testsetPath);
        log.info("========== 迭代 26 · RAG 五件套消融 ==========");
        log.info("用例：{} 道（expected_route=document）", cases.size());
        log.info("网格：改写 {} × 检索 {} × 重排 {} = {} 组",
                REWRITES, RETRIEVALS, RERANKS, REWRITES.size() * RETRIEVALS.size() * RERANKS.size());
        log.info("粗排候选 {} → 最终取 {}（与 迭代 16/17 一致，否则不可比）", CANDIDATES, FINAL_K);

        Map<String, float[]> vectors = embedAll(hybrid.indexedChunks(), embeddingModel);
        log.info("已向量化 {} 个片段用于多样性指标", vectors.size());

        QueryRewriter off = new NoOpQueryRewriter();
        QueryRewriter llm = new LlmQueryRewriter(chatClientBuilder.build());
        Map<String, QueryRewriter> rewriters = new LinkedHashMap<>();
        rewriters.put("none", off);
        rewriters.put("llm", llm);

        Map<String, DocumentPostProcessor> processors = new LinkedHashMap<>();
        processors.put("llm", new LlmReranker(chatClientBuilder.build()));
        processors.put("mmr", new MmrDocumentPostProcessor(embeddingModel, mmrLambda));

        // ---------- 先做回归检查：证明这是纯重构 ----------
        regressionCheck(cases, off, vectorStore, hybrid, processors);

        // ---------- 再跑网格 ----------
        Map<String, List<Hit>> results = new LinkedHashMap<>();
        Map<String, String[]> labels = new LinkedHashMap<>();

        for (String rw : REWRITES) {
            for (String retrieval : RETRIEVALS) {
                DocumentTool recaller = recaller(retrieval, vectorStore, hybrid);
                for (String rerank : RERANKS) {
                    DocumentPostProcessor pp = processors.get(rerank);
                    RagPipeline pipeline = new RagPipeline(rewriters.get(rw), recaller, pp,
                            CANDIDATES, new DefaultContextAugmenter());
                    if ("none".equals(rw) && "vector".equals(retrieval) && "none".equals(rerank)) {
                        log.info("装配示例：{}", pipeline.describe());
                    }
                    String label = rw + "+" + retrieval + "+" + rerank;
                    labels.put(label, new String[] {rw, retrieval, rerank});

                    List<Hit> hits = new ArrayList<>();
                    for (EvalCase c : cases) {
                        hits.add(evaluate(pipeline.retrieve(c.question(), FINAL_K).chunks(),
                                c.sources(), vectors));
                    }
                    results.put(label, hits);
                }
            }
        }

        printSummary(results);
        persist(experiment, results, labels, cases.size(), outPath);
        printConclusion();
    }

    // ==================================================================
    // 回归检查：新 pipeline 是不是纯重构
    // ==================================================================

    private void regressionCheck(List<EvalCase> cases, QueryRewriter off,
                                 VectorStore vectorStore, HybridDocumentTool hybrid,
                                 Map<String, DocumentPostProcessor> processors) {
        log.info("");
        log.info("================= 回归检查：新 pipeline 是不是【纯重构】 =================");
        log.info("做法：rewrite=none 时，逐题比对新旧两套装配返回的【片段 id 序列】。");
        log.info("只跑 none / mmr 两档 —— llm 档是模型输出，两次跑本来就可能不同，");
        log.info("  把它放进来会把「模型抖动」误报成「重构出错」（迭代 17 已经写明这一点）。");

        int checked = 0;
        int mismatch = 0;
        for (String retrieval : RETRIEVALS) {
            DocumentTool recaller = recaller(retrieval, vectorStore, hybrid);
            for (String rerank : RERANKS) {
                if ("llm".equals(rerank)) {
                    continue;
                }
                DocumentPostProcessor pp = processors.get(rerank);
                RagPipeline pipeline = new RagPipeline(off, recaller, pp,
                        CANDIDATES, new DefaultContextAugmenter());
                DocumentTool legacy = pp == null ? recaller : new RerankedDocumentTool(recaller, pp, CANDIDATES);

                for (EvalCase c : cases) {
                    List<String> viaPipeline = pipeline.retrieve(c.question(), FINAL_K)
                            .chunks().stream().map(Chunk::id).toList();
                    List<String> viaLegacy = legacy.search(c.question(), FINAL_K)
                            .stream().map(Chunk::id).toList();
                    checked++;
                    if (!viaPipeline.equals(viaLegacy)) {
                        mismatch++;
                        log.warn("  [X] {}+{} / {} 不一致：", retrieval, rerank, c.id());
                        log.warn("      pipeline={}", viaPipeline);
                        log.warn("      legacy  ={}", viaLegacy);
                    }
                }
            }
        }
        log.info("");
        log.info("  逐题比对 {} 次，不一致 {} 次 —— {}", checked, mismatch,
                mismatch == 0
                        ? "[OK] 纯重构，行为未变（迭代 15/23 的基线不受影响）"
                        : "[X] 行为变了！必须先查清楚，不能当效果");
    }

    // ==================================================================
    // 指标（与 迭代 16/17 同一套定义）
    // ==================================================================

    private static Hit evaluate(List<Chunk> hits, List<String> expectedDocIds, Map<String, float[]> vectors) {
        int rank = 0;
        int correct = 0;
        for (int i = 0; i < hits.size(); i++) {
            if (expectedDocIds.contains(docIdOf(hits.get(i).id()))) {
                correct++;
                if (rank == 0) {
                    rank = i + 1;
                }
            }
        }
        return new Hit(rank, correct, hits.size(), diversity(hits, vectors));
    }

    /** 返回片段之间的平均两两余弦。<b>越低说明越不重复</b>。 */
    private static double diversity(List<Chunk> hits, Map<String, float[]> vectors) {
        List<float[]> vs = new ArrayList<>(hits.size());
        for (Chunk c : hits) {
            float[] v = vectors.get(c.id());
            if (v != null) {
                vs.add(v);
            }
        }
        if (vs.size() < 2) {
            return 0.0;
        }
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < vs.size(); i++) {
            for (int j = i + 1; j < vs.size(); j++) {
                sum += MmrDocumentPostProcessor.cosine(vs.get(i), vs.get(j));
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : sum / pairs;
    }

    private static DocumentTool recaller(String retrieval, VectorStore vectorStore, HybridDocumentTool hybrid) {
        return switch (retrieval) {
            case "vector" -> new VectorDocumentTool(vectorStore);
            case "bm25" -> hybrid::searchBm25;
            case "hybrid" -> hybrid;
            default -> throw new IllegalArgumentException("未知的检索档：" + retrieval);
        };
    }

    /** 把全部片段向量化。**自己分批**：DashScope 单次只收 10 条（迭代 14 踩过的坑）。 */
    private static Map<String, float[]> embedAll(List<Chunk> chunks, EmbeddingModel embeddingModel) {
        Map<String, float[]> out = new HashMap<>();
        for (int i = 0; i < chunks.size(); i += 10) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + 10, chunks.size()));
            List<String> texts = new ArrayList<>(batch.size());
            for (Chunk c : batch) {
                texts.add(c.content());
            }
            List<float[]> vecs = embeddingModel.embed(texts);
            for (int j = 0; j < batch.size(); j++) {
                out.put(batch.get(j).id(), vecs.get(j));
            }
        }
        return out;
    }

    // ==================================================================
    // 输出
    // ==================================================================

    private void printSummary(Map<String, List<Hit>> results) {
        log.info("");
        log.info("================= 每层开关 → 评测分数 =================");
        log.info("{} {} {} {} {} {} {}",
                ConsoleText.padRight("配置", 18), ConsoleText.padRight("hit@1", 8),
                ConsoleText.padRight("hit@3", 8), ConsoleText.padRight("hit@5", 8),
                ConsoleText.padRight("MRR", 8), ConsoleText.padRight("精确率", 8), "多样性");
        for (Map.Entry<String, List<Hit>> e : results.entrySet()) {
            List<Hit> hs = e.getValue();
            log.info("{} {} {} {} {} {} {}",
                    ConsoleText.padRight(e.getKey(), 18),
                    ConsoleText.padRight(rate(hs, 1), 8), ConsoleText.padRight(rate(hs, 3), 8),
                    ConsoleText.padRight(rate(hs, 5), 8),
                    ConsoleText.padRight(String.format("%.3f", mean(hs, Hit::reciprocalRank)), 8),
                    ConsoleText.padRight(String.format("%.3f", mean(hs, Hit::precision)), 8),
                    String.format("%.3f", mean(hs, Hit::diversity)));
        }
    }

    private void persist(String experiment, Map<String, List<Hit>> results, Map<String, String[]> labels,
                         int questionCount, String outPath) throws Exception {
        String ts = LocalDateTime.now().format(TS);
        Path out = Path.of(outPath);
        for (Map.Entry<String, List<Hit>> e : results.entrySet()) {
            List<Hit> hs = e.getValue();
            String[] lab = labels.get(e.getKey());
            Csv.appendRow(out, HEADER, List.of(
                    experiment, ts, e.getKey(), lab[0], lab[1], lab[2], String.valueOf(questionCount),
                    rate(hs, 1), rate(hs, 3), rate(hs, 5),
                    String.format("%.3f", mean(hs, Hit::reciprocalRank)),
                    String.format("%.3f", mean(hs, Hit::precision)),
                    String.format("%.3f", mean(hs, Hit::diversity)),
                    "检索层确定性指标；第⑤层(注入器)不影响检索、本轮量不了；llm 档受模型波动影响"));
        }
        log.info("");
        log.info("已追加到 {}（{} 行）", out.toAbsolutePath(), results.size());
    }

    private void printConclusion() {
        log.info("");
        log.info("================= 怎么读这张表 =================");
        log.info("1. 【改写】那一维：对比 none / llm 两行同名配置 —— 改写把低重合的语义题捞回来了多少？");
        log.info("   它的代价是每次提问多一次模型调用（改写在 迭代 26 的记忆化下每题只算一次）。");
        log.info("2. 【检索/融合】那一维：vector / bm25 / hybrid —— 这就是 迭代 16 那个负结果的位置。");
        log.info("3. 【重排】那一维：none / llm / mmr —— 迭代 17 量过；mmr 的多样性上升通常伴随命中率下降。");
        log.info("4. 【第⑤层 注入器】本轮不参与打分：它影响生成、不影响检索。别在这张表里找它的效果。");
        log.info("5. 回归检查那段如果是 [OK]，说明这次改造没动行为 —— 迭代 15/23 的基线可以放心继续用。");
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String rate(List<Hit> hits, int k) {
        if (hits.isEmpty()) {
            return "-";
        }
        long ok = hits.stream().filter(h -> h.hitAt(k)).count();
        return String.format("%.2f", (double) ok / hits.size());
    }

    private static double mean(List<Hit> hits, java.util.function.ToDoubleFunction<Hit> f) {
        return hits.isEmpty() ? 0.0 : hits.stream().mapToDouble(f).average().orElse(0.0);
    }

    private static String docIdOf(String chunkId) {
        int hash = chunkId.indexOf('#');
        return hash > 0 ? chunkId.substring(0, hash) : chunkId;
    }

    private List<EvalCase> loadDocumentCases(String path) throws Exception {
        List<List<String>> rows = Csv.read(Path.of(path));
        List<EvalCase> cases = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() != EvalCase.HEADER.size()) {
                throw new IllegalStateException("第 " + (i + 1) + " 行字段数不对：" + r);
            }
            EvalCase c = EvalCase.fromRow(r);
            if ("document".equals(c.expectedRoute())) {
                cases.add(c);
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("测试集里没有 expected_route=document 的用例");
        }
        return cases;
    }
}
