package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.eval.EvalCase;
import com.example.smartdataqa.eval.TokenMeter;
import com.example.smartdataqa.eval.UsageMeterAdvisor;
import com.example.smartdataqa.router.DataSourceManifest;
import com.example.smartdataqa.router.EmbeddingPrototypeRouter;
import com.example.smartdataqa.router.HybridQueryRouter;
import com.example.smartdataqa.router.KeywordQueryRouter;
import com.example.smartdataqa.router.LlmQueryRouter;
import com.example.smartdataqa.router.QueryRouter;
import com.example.smartdataqa.router.RouteDecision;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * v24 · <b>路由 v2：多策略对比</b>。28 条用例 × 6 个策略，比<b>准确率 × 延迟 × 成本</b>。
 *
 * <h3>为什么不能只看准确率</h3>
 * 计划里那句话就是本轮的题眼：
 * <blockquote>一个 95% 准确但每次要 2 秒 + 花 token 的方案，未必比 88% 准确但 20ms 免费的好。</blockquote>
 * 只报准确率的对比表，会系统性地偏向「调大模型」那一边——因为<b>准确率免费，延迟和钱不免费</b>。
 * 所以本轮必须把三个维度都摆出来，而且<b>成本要用 token 数说话，不能只是「会花钱」</b>
 * （同 v9 那句「LLM 比关键词好」——当时也没数据）。
 *
 * <h3>策略</h3>
 * <pre>
 *   A   LLM + 清单        产品路径（v23 起）。清单从 CorpusLoader / SchemaRenderer 实时读
 *   A2  LLM 无清单         = v9 的形态（提示词里那份硬编码清单）。用来量 v23 那个清单值多少
 *   A3  LLM + 清单带提示    实验臂：清单里给每份文档补一句「它讲了什么」（取它自己的二级标题）
 *   B   关键词规则         零 token、零延迟，但零泛化
 *   C   混合               规则给单一类就采信，both / refuse 转 LLM
 *   D   embedding 原型      把问题投到 4 个「路由原型」上取最近。零生成 token
 * </pre>
 *
 * <p><b>★ A 与 A2 必须和产品路径同源</b>：A 用的清单来自 {@code DataSourceManifest}
 * （产品路径用的也是它），不是这里另拼一份——否则比出来的不是策略差异，是提示词差异。
 *
 * <p><b>★ D 有个必须说明的偏差</b>：计划里写的是「本地小分类器（本地跑）」，
 * 但本项目不引入本地模型权重，所以 D 实际是「用一次 embedding 代替一次生成」——
 * 便宜得多也快得多，<b>但不是真本地</b>（仍要一次网络往返）。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp24.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp24.enabled", havingValue = "true")
public class RouterBenchmarkDemo {

    private static final Logger log = LoggerFactory.getLogger(RouterBenchmarkDemo.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个策略。{@code note} 会进对比表的最后一列。 */
    private record Strategy(String name, String note, QueryRouter router) {
    }

    /** 一次判定。 */
    private record CaseResult(String strategy, String caseId, String expected, String actual,
                              boolean correct, double latencyMs,
                              long promptTokens, long completionTokens, boolean delegated) {
    }

    @Bean
    ApplicationRunner exp24RouterBenchmark(
            ChatClient.Builder chatClientBuilder,
            BusinessDb businessDb,
            EmbeddingModel embeddingModel,
            @Value("${sdaq.eval.testset:eval/testset.csv}") String testsetPath,
            @Value("${sdaq.eval.testset-ext:eval/testset-ext.csv}") String testsetExtPath,
            @Value("${sdaq.eval.router-metrics:eval/router-compare.csv}") String metricsPath,
            @Value("${sdaq.eval.router-metrics-ext:eval/router-compare-ext.csv}") String metricsExtPath,
            @Value("${sdaq.router-bench.price-per-1k-prompt:0}") double pricePrompt,
            @Value("${sdaq.router-bench.price-per-1k-completion:0}") double priceCompletion) {

        return args -> {
            List<EvalCase> cases = loadCases(testsetPath);

            // 计量的 ChatClient：给路由用的都从它建，于是 token 能落到 meter 里
            TokenMeter meter = new TokenMeter();
            ChatClient metered = chatClientBuilder.clone()
                    .defaultAdvisors(new UsageMeterAdvisor(meter))
                    .build();

            QueryRouter rules = new KeywordQueryRouter();
            QueryRouter llmWithManifest = new LlmQueryRouter(metered, rules,
                    () -> DataSourceManifest.describe(businessDb));
            QueryRouter llmNoManifest = new LlmQueryRouter(metered, rules);
            QueryRouter llmManifestHints = new LlmQueryRouter(metered, rules,
                    () -> DataSourceManifest.describeWithHints(businessDb));
            HybridQueryRouter hybrid = new HybridQueryRouter(rules, llmWithManifest);
            QueryRouter prototype = new EmbeddingPrototypeRouter(embeddingModel);

            List<Strategy> strategies = List.of(
                    new Strategy("A LLM+清单", "产品路径（v23 起）", llmWithManifest),
                    new Strategy("A2 LLM无清单", "v9 的形态；量清单值多少", llmNoManifest),
                    new Strategy("A3 清单带提示", "实验臂：清单里补内容提示", llmManifestHints),
                    new Strategy("B 关键词规则", "零 token、零延迟、零泛化", rules),
                    new Strategy("C 混合", "单一类采信，其余转 LLM", hybrid),
                    new Strategy("D embedding原型", "零生成 token（一次 embedding）", prototype));

            log.info("========== v24 · 路由 v2：多策略对比 ==========");
            log.info("用例：{} 条（testset 全量）｜策略：{} 种", cases.size(), strategies.size());
            log.info("维度：准确率 × 延迟 × 成本（token；折成钱要先填单价）");
            if (pricePrompt <= 0 && priceCompletion <= 0) {
                log.warn("单价未配置（sdaq.router-bench.price-per-1k-*），对比表的「元/次」列会留空——");
                log.warn("  token 列照样有效，只是不折成钱。想折就把两个单价填上再跑一次。");
            }

            String run = LocalDateTime.now().format(TS);
            List<CaseResult> results = new ArrayList<>();

            for (Strategy s : strategies) {
                log.info("");
                log.info("########## {} —— {} ##########", s.name(), s.note());
                for (EvalCase c : cases) {
                    meter.reset();
                    if (s.router() instanceof HybridQueryRouter h) {
                        h.resetStats();
                    }
                    long t0 = System.nanoTime();
                    RouteDecision d = null;
                    try {
                        d = s.router().decide(c.question());
                    }
                    catch (RuntimeException e) {
                        log.error("  {} [X] 路由抛异常：{}", c.id(), e.toString());
                    }
                    double ms = (System.nanoTime() - t0) / 1_000_000.0;

                    boolean ok = d != null && d.route() == c.route();
                    boolean delegated = s.router() instanceof HybridQueryRouter h && h.delegatedCount() > 0;
                    results.add(new CaseResult(s.name(), c.id(), c.expectedRoute(),
                            d == null ? "-" : d.route().code(), ok, ms,
                            meter.promptTokens(), meter.completionTokens(), delegated));

                    // 只打错的（对的在最后的逐题矩阵里看）—— 28×6 条全打会把日志淹掉
                    if (!ok) {
                        log.info("  {} [X] 期望={} 实际={}  {} ms  —— {}", c.id(), c.expectedRoute(),
                                d == null ? "-" : d.route().code(), String.format("%.1f", ms),
                                d == null ? "(异常)" : d.reason());
                    }
                }
                log.info("  → 准确率 {}，平均 {} ms，平均 token {}/{}，转 LLM {} 次",
                        rate(subset(results, s.name())), String.format("%.0f", avgMs(results, s.name())),
                        String.format("%.0f", avgTokens(results, s.name(), true)),
                        String.format("%.0f", avgTokens(results, s.name(), false)),
                        subset(results, s.name()).stream().filter(CaseResult::delegated).count());
            }

            printSummary(strategies, results, pricePrompt, priceCompletion);
            printCaseMatrix(strategies, cases, results);
            writeCsv(Path.of(metricsPath), run, results);

            // 扩展集：跨方向用例（database→document / refuse 硬答）。
            // ★ 单独跑、单独报、**单独落一个 CSV** —— 核心 28 条是历史基线，一行都不能混进去
            //   （混了就没人再能按「28 题」读 router-compare.csv，而且不会报错）。
            List<EvalCase> extCases = loadCasesIfPresent(testsetExtPath);
            if (!extCases.isEmpty()) {
                List<CaseResult> extResults = runAll(strategies, extCases, meter);
                printExtensionSet(strategies, extCases, extResults);
                writeCsv(Path.of(metricsExtPath), run, extResults);
            }

            printConclusion(strategies, results);
        };
    }

    // ==================================================================
    // 汇总与打印
    // ==================================================================

    private void printSummary(List<Strategy> strategies, List<CaseResult> results,
                              double pricePrompt, double priceCompletion) {
        log.info("");
        log.info("================= 路由对比表（策略 × 准确率 × 延迟 × 成本） =================");
        log.info("{} {} {} {} {} {} {}",
                ConsoleText.padRight("策略", 17),
                ConsoleText.padRight("准确率", 13),
                ConsoleText.padRight("平均延迟", 11),
                ConsoleText.padRight("prompt tok", 11),
                ConsoleText.padRight("compl tok", 10),
                ConsoleText.padRight("元/次", 12),
                "说明");

        boolean moneyConfigured = pricePrompt > 0 || priceCompletion > 0;
        for (Strategy s : strategies) {
            List<CaseResult> g = subset(results, s.name());
            long ok = g.stream().filter(CaseResult::correct).count();
            double pt = avgTokens(results, s.name(), true);
            double ct = avgTokens(results, s.name(), false);
            String money = !moneyConfigured ? "—"
                    : String.format("%.6f", pricePrompt * pt / 1000.0 + priceCompletion * ct / 1000.0);

            log.info("{} {} {} {} {} {} {}",
                    ConsoleText.padRight(s.name(), 17),
                    ConsoleText.padRight(String.format("%.2f (%d/%d)", (double) ok / g.size(), ok, g.size()), 13),
                    ConsoleText.padRight(String.format("%.0f ms", avgMs(results, s.name())), 11),
                    ConsoleText.padRight(String.format("%.0f", pt), 11),
                    ConsoleText.padRight(String.format("%.0f", ct), 10),
                    ConsoleText.padRight(money, 12),
                    s.note());
        }
        log.info("");
        if (moneyConfigured) {
            log.info("元/次 = prompt_tok/1000 × {} + compl_tok/1000 × {}（单价写在 application.yml，**会过期**）",
                    pricePrompt, priceCompletion);
        } else {
            log.info("「元/次」列留空是因为**单价没配**——不是「不要钱」。填了单价再跑就有。");
        }
        log.info("D 的 token 是 0：它走 embedding，不走生成（另有一条 embedding 计费线，比生成小一两个数量级）。");
    }

    /** 逐题矩阵：横向看同一个策略在哪些题上错。 */
    private void printCaseMatrix(List<Strategy> strategies, List<EvalCase> cases, List<CaseResult> results) {
        log.info("");
        log.info("================= 逐题矩阵（[OK] / [X实际路由]） =================");
        StringBuilder header = new StringBuilder(ConsoleText.padRight("题", 6));
        for (Strategy s : strategies) {
            header.append(ConsoleText.padRight(s.name(), 18));
        }
        log.info(header.toString());

        for (EvalCase c : cases) {
            StringBuilder line = new StringBuilder(ConsoleText.padRight(c.id(), 6));
            for (Strategy s : strategies) {
                CaseResult r = find(results, s.name(), c.id());
                String mark = r == null ? "-" : (r.correct() ? "[OK]" : "[X " + r.actual() + "]");
                line.append(ConsoleText.padRight(mark, 18));
            }
            log.info(line.toString());
        }
    }

    private static final List<String> CSV_HEADER = List.of(
            "run", "strategy", "case_id", "expected", "actual", "correct",
            "latency_ms", "prompt_tokens", "completion_tokens", "delegated_to_llm");

    private void writeCsv(Path path, String run, List<CaseResult> results) {
        try {
            for (CaseResult r : results) {
                Csv.appendRow(path, CSV_HEADER, List.of(
                        run, r.strategy(), r.caseId(), r.expected(), r.actual(),
                        r.correct() ? "1" : "0",
                        String.format("%.1f", r.latencyMs()),
                        String.valueOf(r.promptTokens()),
                        String.valueOf(r.completionTokens()),
                        r.delegated() ? "1" : "0"));
            }
            log.info("");
            log.info("逐题结果已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    /**
     * 结语。<b>只写方法与判据，不写预测</b>——预判被实跑推翻是这个项目的常态
     * （v18 断言失败模式、v23 断言路由会涨到 1.00，两次都被推翻）。
     */
    private void printConclusion(List<Strategy> strategies, List<CaseResult> results) {
        log.info("");
        log.info("================= 本轮得到了什么 =================");
        log.info("1. 三个维度一起看，才谈得上「值不值」：");
        log.info("   准确率免费，延迟和钱不免费——只报准确率的对比表会系统性偏向「调大模型」那一边。");
        log.info("");
        log.info("2. A 与 A2 的差 = v23 那份清单值多少（同一批题、同一套判定，只差提示词里那一段）。");
        log.info("   注意看两侧的 prompt token：清单不是免费的，它每次都跟着 prompt 走一遍。");
        log.info("   ★ A3 用来回答「清单只给标题够不够」：它给每份文档补了一句内容提示（取它自己的二级标题）。");
        log.info("     动机是 v24 三次复跑发现——清单只给标题时，D14/D15 那种「内容在、标题不提示」的题反而被弄坏。");
        log.info("");
        log.info("3. C 的价值全在成本：它把一部分本该交给 LLM 的调用省掉了。");
        log.info("   但代价是【规则命中却判错的题，它原样继承】——所以 C 的准确率上界是 A，不可能超过。");
        log.info("");
        log.info("4. D 代表「不调大模型也能做语义分类」：零生成 token，一次 embedding。");
        log.info("   ★ 它不是真本地（仍要一次网络往返），但比 qwen-plus 便宜、快得多——");
        log.info("     这一行就是用来回答「95% 但 2 秒 + 花 token，值不值」的。");
        log.info("");
        log.info("5. 逐题矩阵比汇总行值钱（v23 刚吃过这个亏：两条汇总数字一样，其实是换了一条错题）。");
        log.info("   结果写进 eval/router-compare.csv（策略 × 题）。");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private List<EvalCase> loadCases(String path) throws Exception {
        List<List<String>> rows = Csv.read(Path.of(path));
        if (rows.isEmpty()) {
            throw new IllegalStateException("测试集是空的：" + path);
        }
        List<String> header = rows.get(0);
        if (!header.equals(EvalCase.HEADER)) {
            throw new IllegalStateException("测试集表头不对。期望 " + EvalCase.HEADER + "，实际 " + header);
        }
        List<EvalCase> cases = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() != EvalCase.HEADER.size()) {
                throw new IllegalStateException("第 " + (i + 1) + " 行字段数是 " + r.size()
                        + "，应为 " + EvalCase.HEADER.size() + "：" + r);
            }
            cases.add(EvalCase.fromRow(r));
        }
        return cases;
    }

    /**
     * 扩展集：文件不在就返回空——<b>它是可选的</b>。
     * 没有这个文件时，本 demo 的行为和以前一模一样（核心 28 条照跑）。
     */
    private List<EvalCase> loadCasesIfPresent(String path) throws Exception {
        if (!Path.of(path).toFile().isFile()) {
            return List.of();
        }
        return loadCases(path);
    }

    /** 把一组用例跑过全部策略，收成 {@link CaseResult}。只给扩展集用——核心集那一段保持原样不动。 */
    private List<CaseResult> runAll(List<Strategy> strategies, List<EvalCase> cases, TokenMeter meter) {
        List<CaseResult> out = new ArrayList<>();
        for (Strategy s : strategies) {
            for (EvalCase c : cases) {
                meter.reset();
                if (s.router() instanceof HybridQueryRouter h) {
                    h.resetStats();
                }
                long t0 = System.nanoTime();
                RouteDecision d = null;
                try {
                    d = s.router().decide(c.question());
                }
                catch (RuntimeException e) {
                    log.error("  {} [X] 路由抛异常：{}", c.id(), e.toString());
                }
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                boolean ok = d != null && d.route() == c.route();
                boolean delegated = s.router() instanceof HybridQueryRouter h && h.delegatedCount() > 0;
                out.add(new CaseResult(s.name(), c.id(), c.expectedRoute(),
                        d == null ? "-" : d.route().code(), ok, ms,
                        meter.promptTokens(), meter.completionTokens(), delegated));
            }
        }
        return out;
    }

    /**
     * 扩展集的逐题矩阵。
     *
     * <p><b>为什么单独报</b>：核心 28 条是历史基线（v15 以来的所有 {@code /28} 都建在它上面）。
     * 把跨方向用例混进去，会一次性让那批数字全部失真——而<b>那正是本项目一直守的「旧基线可复现」</b>。
     * 所以扩展集**只加不混**：单独跑、单独报，给那两个方向定价。
     */
    private void printExtensionSet(List<Strategy> strategies, List<EvalCase> cases, List<CaseResult> results) {
        log.info("");
        log.info("================= 扩展集：跨方向用例（{} 条，【不在】核心 28 条里）=================", cases.size());
        log.info("用途：给「database→document」和「refuse 硬答」两个方向定价——核心集里这两类一条都没有。");
        log.info("");
        for (Strategy s : strategies) {
            StringBuilder row = new StringBuilder();
            long ok = 0;
            for (EvalCase c : cases) {
                CaseResult r = find(results, s.name(), c.id());
                boolean good = r != null && r.correct();
                if (good) {
                    ok++;
                }
                row.append(ConsoleText.padRight(c.id() + (good ? " 对" : " 错"), 8));
            }
            log.info("  {} {}  → {}/{}", ConsoleText.padRight(s.name(), 17), row, ok, cases.size());
        }
        log.info("");
        log.info("  逐题（期望 → 各策略实际判定）：");
        for (EvalCase c : cases) {
            StringBuilder row = new StringBuilder();
            for (Strategy s : strategies) {
                CaseResult r = find(results, s.name(), c.id());
                row.append(s.name().split(" ")[0]).append("=")
                        .append(r == null ? "-" : r.actual()).append("  ");
            }
            log.info("    {} 期望={}  {}", ConsoleText.padRight(c.id(), 6),
                    ConsoleText.padRight(c.expectedRoute(), 10), row);
        }
    }

    private static List<CaseResult> subset(List<CaseResult> results, String strategy) {
        List<CaseResult> out = new ArrayList<>();
        for (CaseResult r : results) {
            if (r.strategy().equals(strategy)) {
                out.add(r);
            }
        }
        return out;
    }

    private static CaseResult find(List<CaseResult> results, String strategy, String caseId) {
        for (CaseResult r : results) {
            if (r.strategy().equals(strategy) && r.caseId().equals(caseId)) {
                return r;
            }
        }
        return null;
    }

    private static String rate(List<CaseResult> g) {
        if (g.isEmpty()) {
            return "-";
        }
        long ok = g.stream().filter(CaseResult::correct).count();
        return String.format("%.2f (%d/%d)", (double) ok / g.size(), ok, g.size());
    }

    private static double avgMs(List<CaseResult> results, String strategy) {
        return subset(results, strategy).stream().mapToDouble(CaseResult::latencyMs).average().orElse(0);
    }

    private static double avgTokens(List<CaseResult> results, String strategy, boolean prompt) {
        List<CaseResult> g = subset(results, strategy);
        return g.stream()
                .mapToLong(prompt ? CaseResult::promptTokens : CaseResult::completionTokens)
                .average().orElse(0);
    }
}
