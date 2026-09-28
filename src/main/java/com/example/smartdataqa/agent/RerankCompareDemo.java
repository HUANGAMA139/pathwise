package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.eval.EvalCase;
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
 * 迭代 17 · <b>粗排召回 × 精排打分</b>的 A/B：2 种召回 × 3 种后处理 = 6 组。
 *
 * <pre>
 *   召回   vector（迭代 14 的基线）| hybrid（迭代 16，实测负收益）
 *   精排   none（不做）         | llm（读正文判相关性）| mmr（控多样性）
 * </pre>
 *
 * <h3>为什么用和 迭代 16 一样的指标</h3>
 * hit@1/3/5 + MRR + 文档级精确率——<b>和 迭代 16 逐位可比</b>。
 * 而精排恰恰是「改变名次」的东西，所以这套指标正好是量它的工具。
 * 另外加一列<b>多样性</b>（返回片段之间的平均两两余弦，越低越不重复），
 * 用来量 MMR 到底带来了什么。
 *
 * <h3>三条必须写在结果前面的纪律</h3>
 * <ol>
 *   <li><b>{@code llm} 档是模型输出，天然带波动</b>——即使把 temperature 设成 0。
 *       所以这一档的结论要看<b>两次跑是否一致</b>；不一致的地方不能说成「效果」。</li>
 *   <li><b>精排的天花板是召回池。</b>如果正确片段根本没被召回（候选 10 里没有），
 *       精排再对也不可能把它变出来。所以看「+精排」的提升时，先确认那几道题的候选池里有没有它。</li>
 *   <li><b>多样性不是越高越好。</b>MMR 的 λ 把相关性让给多样性时，命中率是会掉的——
 *       这两列要一起看，单看一列都会得出片面的结论。</li>
 * </ol>
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp17.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp17.enabled", havingValue = "true")
public class RerankCompareDemo {

    private static final Logger log = LoggerFactory.getLogger(RerankCompareDemo.class);

    private static final List<String> HEADER = List.of(
            "experiment", "ts", "config", "retrieval", "rerank", "questions",
            "hit@1", "hit@3", "hit@5", "mrr", "precision_doc", "diversity", "avg_returned", "note");

    /** 最终交给模型的条数。 */
    private static final int FINAL_K = 5;

    /** 粗排先取多少个候选——精排只能在这个池子里挑，所以它同时是精排的天花板。 */
    private static final int CANDIDATES = 10;

    private static final List<String> RETRIEVALS = List.of("vector", "hybrid");
    private static final List<String> RERANKS = List.of("none", "llm", "mmr");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个配置在一道题上的结果。 */
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
    ApplicationRunner exp17RerankCompare(
            VectorStore vectorStore,
            HybridDocumentTool hybrid,
            EmbeddingModel embeddingModel,
            ChatClient.Builder chatClientBuilder,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.rerank-metrics:eval/rerank-compare.csv}") String outPath,
            @Value("${sdaq.rerank.mmr-lambda:0.7}") double mmrLambda,
            @Value("${sdaq.eval.experiment:exp17-rerank}") String experiment) {

        return args -> run(vectorStore, hybrid, embeddingModel, chatClientBuilder,
                testsetPath, outPath, mmrLambda, experiment);
    }

    private void run(VectorStore vectorStore, HybridDocumentTool hybrid, EmbeddingModel embeddingModel,
                     ChatClient.Builder chatClientBuilder, String testsetPath, String outPath,
                     double mmrLambda, String experiment) throws Exception {

        List<EvalCase> cases = loadDocumentCases(testsetPath);
        log.info("========== 迭代 17 · 粗排召回 × 精排打分 ==========");
        log.info("用例：{} 道（expected_route=document）｜配置：{} 种召回 × {} 种精排 = {} 组",
                cases.size(), RETRIEVALS.size(), RERANKS.size(), RETRIEVALS.size() * RERANKS.size());
        log.info("粗排候选 {} 个 → 精排取 {} 个（精排只能在这个池子里挑）", CANDIDATES, FINAL_K);

        // 把全部片段一次性向量化，用于算「返回片段之间有多重复」。
        // 只算一次：19 个片段分 2 批（DashScope 单次 10 条上限），而不是每个结果集都重算。
        Map<String, float[]> vectors = embedAll(hybrid.indexedChunks(), embeddingModel);
        log.info("已向量化 {} 个片段用于多样性指标", vectors.size());

        Map<String, DocumentPostProcessor> processors = new LinkedHashMap<>();
        processors.put("llm", new LlmReranker(chatClientBuilder.build()));
        processors.put("mmr", new MmrDocumentPostProcessor(embeddingModel, mmrLambda));

        Map<String, List<Hit>> results = new LinkedHashMap<>();
        Map<String, String[]> labels = new LinkedHashMap<>();

        for (String retrieval : RETRIEVALS) {
            DocumentTool recaller = "vector".equals(retrieval)
                    ? new VectorDocumentTool(vectorStore)
                    : hybrid;

            for (String rerank : RERANKS) {
                String label = retrieval + "+" + rerank;
                labels.put(label, new String[] {retrieval, rerank});

                DocumentPostProcessor processor = processors.get(rerank);
                DocumentTool tool = processor == null
                        ? recaller
                        : new RerankedDocumentTool(recaller, processor, CANDIDATES);

                List<Hit> hits = new ArrayList<>();
                for (EvalCase c : cases) {
                    List<Chunk> got = tool.search(c.question(), FINAL_K);
                    hits.add(evaluate(got, c.sources(), vectors));
                }
                results.put(label, hits);
            }
        }

        printPerQuestion(cases, results);
        printSummary(results, labels);
        persist(experiment, results, labels, cases.size(), outPath);

        log.info("");
        log.info("【怎么读】");
        log.info("1. 先看 llm 档：它真的把正确片段往前挪了吗？如果命中率没动，说明它没起作用——");
        log.info("   而不是「精排没用」，可能是**候选池里根本没有正确答案**（精排救不回来）。");
        log.info("   上面每题的重排前后顺序都打了日志，可以逐题核。");
        log.info("2. 再看 mmr 档：多样性应该上升（数值下降），代价通常体现在命中率上。");
        log.info("   这就是 λ 的取舍，不是 bug。");
        log.info("3. llm 档的结论要看**两次跑是否一致**——模型输出有波动，temperature=0 只是减小它。");
        log.info("4. hybrid+none 那一行就是 迭代 16 的负结果，它本轮是作为对照留在表里的。");
    }

    // ==================== 指标 ====================

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

    /** 返回片段之间的平均两两余弦。<b>越低说明越不重复</b>（多样性越高）。 */
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

    // ==================== 输出 ====================

    private void printPerQuestion(List<EvalCase> cases, Map<String, List<Hit>> results) {
        List<String> show = List.of("vector+none", "vector+llm", "vector+mmr");
        log.info("");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("用例", 6), ConsoleText.padRight("期望出处", 22),
                ConsoleText.padRight("vector+none", 13), ConsoleText.padRight("vector+llm", 13), "vector+mmr");
        for (int i = 0; i < cases.size(); i++) {
            StringBuilder row = new StringBuilder();
            for (String label : show) {
                Hit h = results.get(label).get(i);
                row.append(ConsoleText.padRight(h.rankOfFirstCorrect() == 0 ? "—" : String.valueOf(h.rankOfFirstCorrect()), 13));
            }
            log.info("{} {} {}",
                    ConsoleText.padRight(cases.get(i).id(), 6),
                    ConsoleText.padRight(docIdOf(cases.get(i).sources().get(0)), 22),
                    row);
        }
    }

    private void printSummary(Map<String, List<Hit>> results, Map<String, String[]> labels) {
        log.info("");
        log.info("================= 汇总 =================");
        log.info("{} {} {} {} {} {} {}",
                ConsoleText.padRight("配置", 15), ConsoleText.padRight("hit@1", 8), ConsoleText.padRight("hit@3", 8),
                ConsoleText.padRight("hit@5", 8), ConsoleText.padRight("MRR", 8),
                ConsoleText.padRight("精确率", 8), "多样性");
        for (Map.Entry<String, List<Hit>> e : results.entrySet()) {
            List<Hit> hs = e.getValue();
            log.info("{} {} {} {} {} {} {}",
                    ConsoleText.padRight(e.getKey(), 15),
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
                    experiment, ts, e.getKey(), lab[0], lab[1], String.valueOf(questionCount),
                    rate(hs, 1), rate(hs, 3), rate(hs, 5),
                    String.format("%.3f", mean(hs, Hit::reciprocalRank)),
                    String.format("%.3f", mean(hs, Hit::precision)),
                    String.format("%.3f", mean(hs, Hit::diversity)),
                    String.format("%.1f", mean(hs, h -> (double) h.returned())),
                    "检索层确定性指标；llm 档受模型波动影响；多样性=片段间平均两两余弦（越低越不重复）"));
        }
        log.info("");
        log.info("已追加到 {}（{} 行）", out.toAbsolutePath(), results.size());
    }

    // ==================== 小工具 ====================

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
