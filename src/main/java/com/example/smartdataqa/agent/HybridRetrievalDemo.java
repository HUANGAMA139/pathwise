package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.eval.EvalCase;
import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.hybrid.HybridDocumentTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 迭代 16 · 三种检索方式的对比：向量 / BM25 / 混合 RRF。
 *
 * <h3>为什么这个实验长这样（而不是跑 迭代 15 那套）</h3>
 * 迭代 15 花了一整天才量出两个噪声底：
 * <pre>
 *   判分噪声 0.20 / 条（同一输入送两次判分差一个片段）
 *   路由噪声 ±1 条 ≈ 0.036（同一题两次跑出不同路由）
 * </pre>
 * 拿端到端总分去比 迭代 15，真实增益会被这两个噪声吃掉。而且更根本：
 * 语义族的 3 道题失败在**路由**层，**检索根本不会执行**——混合检索在这几道上影响为零。
 *
 * <p>所以这里<b>绕开路由和生成，只测检索</b>，用完全确定性的指标：
 * {@code hit@1/3/5}（正确文档的片段有没有出现在前 k 名）、{@code MRR}、文档级精确率。
 * <b>零 LLM 调用、零噪声</b>——差异是多少就是多少，两次跑必须一致。
 *
 * <h3>为什么要扫参数，而不是只跑一组</h3>
 * 第一次跑（候选 5、k=60）的结果是<b>混合比单向量还差</b>（hit@1 0.87 → 0.73）。
 * 逐题表把机制指向两处：RRF 的 k 太大把名次差异压平了，以及候选池太浅。
 * 但「机制假设」不等于结论——<b>把参数做成可扫的，是为了让「方法不行」和「参数不行」分得开</b>。
 * 候选与 k 只影响融合、不影响两次检索，所以这里<b>每道题只检索一次</b>，
 * 再拿同一批候选去套所有组合——对比才是干净的。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp16.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp16.enabled", havingValue = "true")
public class HybridRetrievalDemo {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalDemo.class);

    private static final List<String> HEADER = List.of(
            "experiment", "ts", "config", "candidates", "rrf_k", "questions",
            "hit@1", "hit@3", "hit@5", "mrr", "precision_doc", "avg_returned", "note");

    /** 最终要交给模型的条数。和 SmartQaAgent 的 TOP_K 对齐。 */
    private static final int FINAL_K = 5;

    /** 每路一次取这么多候选，供不同 candidates 组合切片复用（省掉重复检索）。 */
    private static final int MAX_CAND = 20;

    private static final List<Integer> CANDIDATE_GRID = List.of(5, 10, 20);
    private static final List<Integer> RRF_K_GRID = List.of(10, 30, 60);

    /** 逐题表里展示的默认混合配置（和 application.yml 的默认值一致）。 */
    private static final int DEFAULT_CAND = 10;
    private static final int DEFAULT_RRF_K = 30;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个配置。{@code candidates == 0} 表示单路（vector 或 bm25）。 */
    private record Cfg(String label, int candidates, int rrfK) {
    }

    /** 一个配置在一道题上的结果。 */
    private record Hit(int rankOfFirstCorrect, int correctCount, int returned) {

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
    ApplicationRunner exp16HybridRetrieval(
            HybridDocumentTool hybrid,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.retrieval-metrics:eval/retrieval-compare.csv}") String outPath,
            @Value("${sdaq.eval.experiment:exp16-hybrid}") String experiment) {

        return args -> run(hybrid, testsetPath, outPath, experiment);
    }

    private void run(HybridDocumentTool hybrid, String testsetPath, String outPath,
                     String experiment) throws Exception {

        List<EvalCase> cases = loadDocumentCases(testsetPath);
        List<Cfg> cfgs = buildConfigs();

        log.info("========== 迭代 16 · 向量 / BM25 / 混合 RRF 对比 ==========");
        log.info("用例：{} 道（只取 expected_route=document 的题——测的是检索，不是路由）", cases.size());
        log.info("配置：{} 组（2 个单路 + {} 组融合参数）", cfgs.size(), CANDIDATE_GRID.size() * RRF_K_GRID.size());
        log.info("指标：hit@1/3/5 + MRR + 文档级精确率（**零 LLM 调用；两次跑结果必须一致**）");
        log.info("");

        // ---------- 每道题只检索一次，候选缓存起来给所有组合复用 ----------
        Map<String, List<Chunk>> vectorCache = new LinkedHashMap<>();
        Map<String, List<Chunk>> bm25Cache = new LinkedHashMap<>();
        Map<String, List<String>> expectedById = new LinkedHashMap<>();
        for (EvalCase c : cases) {
            vectorCache.put(c.id(), hybrid.searchVector(c.question(), MAX_CAND));
            bm25Cache.put(c.id(), hybrid.searchBm25(c.question(), MAX_CAND));
            expectedById.put(c.id(), c.sources());
        }

        // ---------- 评估所有组合 ----------
        Map<String, List<Hit>> results = new LinkedHashMap<>();
        Map<String, Cfg> cfgByLabel = new LinkedHashMap<>();
        for (Cfg cfg : cfgs) {
            cfgByLabel.put(cfg.label(), cfg);
            List<Hit> hits = new ArrayList<>();
            for (EvalCase c : cases) {
                List<Chunk> got = retrieve(hybrid, cfg, c, vectorCache.get(c.id()), bm25Cache.get(c.id()));
                hits.add(evaluate(got, expectedById.get(c.id())));
            }
            results.put(cfg.label(), hits);
        }

        printPerQuestion(cases, results, expectedById);
        printSummary(results, cfgByLabel);
        persist(experiment, cfgs, results, cases.size(), outPath);

        log.info("");
        log.info("========== 对比结束 ==========");
    }

    private static List<Cfg> buildConfigs() {
        List<Cfg> cfgs = new ArrayList<>();
        cfgs.add(new Cfg("vector", 0, 0));
        cfgs.add(new Cfg("bm25", 0, 0));
        for (int cand : CANDIDATE_GRID) {
            for (int k : RRF_K_GRID) {
                cfgs.add(new Cfg("hybrid-c" + cand + "-k" + k, cand, k));
            }
        }
        return cfgs;
    }

    /**
     * 按配置取结果。
     *
     * <p>单路就是切前 {@value #FINAL_K} 个；混合是「切前 candidates 个 → 融合 → 取前 {@value #FINAL_K} 个」。
     * 全程不重新检索，所以不同组合之间的输入是可比的。
     */
    private List<Chunk> retrieve(HybridDocumentTool tool, Cfg cfg, EvalCase c,
                                 List<Chunk> vectorAll, List<Chunk> bm25All) {
        if ("vector".equals(cfg.label())) {
            return slice(vectorAll, FINAL_K);
        }
        if ("bm25".equals(cfg.label())) {
            return slice(bm25All, FINAL_K);
        }
        return tool.fuse(c.question(), slice(vectorAll, cfg.candidates()),
                slice(bm25All, cfg.candidates()), FINAL_K, cfg.rrfK());
    }

    private static List<Chunk> slice(List<Chunk> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    /** 算一道题在一个配置下的表现。 */
    private static Hit evaluate(List<Chunk> hits, List<String> expectedDocIds) {
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
        return new Hit(rank, correct, hits.size());
    }

    // ==================== 输出 ====================

    private void printPerQuestion(List<EvalCase> cases, Map<String, List<Hit>> results,
                                  Map<String, List<String>> expectedById) {
        String hybridLabel = "hybrid-c" + DEFAULT_CAND + "-k" + DEFAULT_RRF_K;
        List<Hit> v = results.get("vector");
        List<Hit> b = results.get("bm25");
        List<Hit> h = results.get(hybridLabel);

        log.info("{} {} {} {} {}", ConsoleText.padRight("用例", 6), ConsoleText.padRight("期望出处", 24),
                ConsoleText.padRight("vector", 10), ConsoleText.padRight("bm25", 10), "hybrid");
        for (int i = 0; i < cases.size(); i++) {
            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(cases.get(i).id(), 6),
                    ConsoleText.padRight(docIdOf(expectedById.get(cases.get(i).id()).get(0)), 24),
                    ConsoleText.padRight(rank(v.get(i)), 10),
                    ConsoleText.padRight(rank(b.get(i)), 10),
                    rank(h.get(i)));
        }
    }

    private void printSummary(Map<String, List<Hit>> results, Map<String, Cfg> cfgByLabel) {
        log.info("");
        log.info("================= 汇总 =================");
        log.info("{} {} {} {} {} {} {} {}",
                ConsoleText.padRight("配置", 18), ConsoleText.padRight("候选", 6), ConsoleText.padRight("k", 5),
                ConsoleText.padRight("hit@1", 8), ConsoleText.padRight("hit@3", 8),
                ConsoleText.padRight("hit@5", 8), ConsoleText.padRight("MRR", 8), "精确率");
        for (Map.Entry<String, List<Hit>> e : results.entrySet()) {
            List<Hit> hs = e.getValue();
            Cfg cfg = cfgByLabel.get(e.getKey());
            log.info("{} {} {} {} {} {} {} {}", ConsoleText.padRight(e.getKey(), 18),
                    ConsoleText.padRight(cfg.candidates() == 0 ? "-" : String.valueOf(cfg.candidates()), 6),
                    ConsoleText.padRight(cfg.rrfK() == 0 ? "-" : String.valueOf(cfg.rrfK()), 5),
                    ConsoleText.padRight(rate(hs, 1), 8), ConsoleText.padRight(rate(hs, 3), 8),
                    ConsoleText.padRight(rate(hs, 5), 8),
                    ConsoleText.padRight(String.format("%.3f", mean(hs, Hit::reciprocalRank)), 8),
                    String.format("%.3f", mean(hs, Hit::precision)));
        }
        log.info("");
        log.info("【怎么读】");
        log.info("1. 这些指标是确定性的——**两次跑必须一模一样**。不一样就说明代码里有非确定的排序，要先修。");
        log.info("2. **混合不比单路好是完全可能的，而且第一次跑就是这样**（hit@1 0.87 → 0.73）。");
        log.info("   机制有三个，逐题表里都能看到：");
        log.info("   a) RRF 给两路**同等权重**，而 BM25 在这套语料上明显更弱（hit@1 0.67 vs 0.87）——弱的那路会把强的拖下来；");
        log.info("   b) RRF 的秩粒度粗：(向量第1, BM25第2) 和 (向量第2, BM25第1) 算出来**同分**，谁在前由同分排序决定，不是证据决定的；");
        log.info("   c) 在零字面重合的题上两路**完全不相交**（RRF 日志会打「0 个被两路同时命中」），");
        log.info("      这时 RRF 只是在交错拼接两份名单，向量原本排第 3 的正确片段会被挤到第 5。");
        log.info("3. 所以这张参数网格的意义是：**把「方法不行」和「参数不行」分开**。");
        log.info("   如果调小 k、加大候选数能让混合追平甚至超过单向量，那是参数问题；");
        log.info("   如果怎么调都追不平，那就是「弱通道 + 等权融合」在这个语料规模上的结构性问题。");
        log.info("4. 精确率的陷阱：BM25 只返回 2~3 个片段时分母小、精确率会虚高。");
        log.info("   所以 CSV 里专门有一列 avg_returned，两个要一起看。");
    }

    private void persist(String experiment, List<Cfg> cfgs, Map<String, List<Hit>> results,
                         int questionCount, String outPath) throws Exception {
        String ts = LocalDateTime.now().format(TS);
        Path out = Path.of(outPath);
        for (Cfg cfg : cfgs) {
            List<Hit> hs = results.get(cfg.label());
            Csv.appendRow(out, HEADER, List.of(
                    experiment, ts, cfg.label(),
                    cfg.candidates() == 0 ? "-" : String.valueOf(cfg.candidates()),
                    cfg.rrfK() == 0 ? "-" : String.valueOf(cfg.rrfK()),
                    String.valueOf(questionCount),
                    rate(hs, 1), rate(hs, 3), rate(hs, 5),
                    String.format("%.3f", mean(hs, Hit::reciprocalRank)),
                    String.format("%.3f", mean(hs, Hit::precision)),
                    String.format("%.1f", mean(hs, h -> (double) h.returned())),
                    "检索层确定性指标；文档级，非片段级"));
        }
        log.info("");
        log.info("已追加到 {}（{} 行）", out.toAbsolutePath(), cfgs.size());
    }

    // ==================== 小工具 ====================

    private static String rank(Hit h) {
        return h.rankOfFirstCorrect() == 0 ? "—" : String.valueOf(h.rankOfFirstCorrect());
    }

    private static String rate(List<Hit> hits, int k) {
        if (hits.isEmpty()) {
            return "-";
        }
        long ok = hits.stream().filter(h -> h.hitAt(k)).count();
        return String.format("%.2f", (double) ok / hits.size());
    }

    private static double mean(List<Hit> hits, ToDoubleFunction<Hit> f) {
        return hits.isEmpty() ? 0.0 : hits.stream().mapToDouble(f).average().orElse(0.0);
    }

    /** 从片段 id（形如 {@code 01-...#2}）取回文档 id。 */
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
