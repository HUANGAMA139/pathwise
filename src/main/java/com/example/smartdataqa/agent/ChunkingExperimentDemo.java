package com.example.smartdataqa.agent;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.corpus.CorpusLoader;
import com.example.smartdataqa.tools.corpus.DocumentChunker;
import com.example.smartdataqa.tools.mock.MockDocumentTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;

/**
 * 迭代 13 · 分块对比实验。
 *
 * <p><b>本轮的目的不是「跑通」，是「对比」。</b>
 *
 * <h3>实验设计</h3>
 * 同一份语料、同一批问题、同一个检索方式，
 * <b>唯一变量是分块大小</b>。这样才能把分块的影响单独量出来。
 *
 * <p>为此本实验<b>不注入 Spring 的 DocumentTool</b>，而是自己按不同切法各建一个——
 * 注入进来的那个只有一种切法。<b>这也说明为什么 迭代 13 要把分块从检索里拆出去</b>：
 * 拆开了才能这样换着比。
 *
 * <h3>量什么</h3>
 * <ul>
 *   <li><b>片段数、平均字数</b>——切法的直接结果</li>
 *   <li><b>命中率</b>——正确答案所在文档有没有被召回</li>
 *   <li><b>精确率</b>——召回的片段里，有多少真的来自那份文档（其余是噪声）</li>
 *   <li><b>材料字数</b>——一次检索喂了多少字给模型（上下文的成本）</li>
 * </ul>
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp13.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp13.enabled", havingValue = "true")
public class ChunkingExperimentDemo {

    private static final Logger log = LoggerFactory.getLogger(ChunkingExperimentDemo.class);

    /** 三种切法。小、中、大各一个。 */
    private static final List<Integer> SIZES = List.of(200, 500, 1000);

    /** 检索取前几段。 */
    private static final int TOP_K = 3;

    /**
     * 5 个测试问题，各带一个「人工标注的正确答案所在文档」。
     *
     * <p>标注是必要的——<b>不标注就没法算精确率</b>。
     * 这也是 迭代 15 评测集的结构：问题 + 期望答案的来源。
     */
    private record Case(String question, String expectedDocId) {
    }

    private static final List<Case> CASES = List.of(
            new Case("销售额是怎么算的？", "01-销售数据统计口径说明"),
            new Case("华东区包含哪些省？", "02-销售区域划分规则"),
            new Case("退货审核要多久？", "03-售后与退货管理流程"),
            new Case("XR-2000B 是什么产品？", "05-产品线与编码规则"),
            new Case("什么是客单价？", "06-销售指标定义手册"));

    /** 一对只差一个字母的编码。用来验证分块能不能把它们区分开。 */
    private static final String CODE_A = "XR-2000B";
    private static final String CODE_B = "XR-2000C";

    private record SizeResult(int chunkSize,
                              int chunkCount,
                              int avgChars,
                              int maxChars,
                              int hitCount,
                              double avgPrecision,
                              int avgMaterialChars,
                              boolean canDistinguishCodes) {
    }

    @Bean
    ApplicationRunner exp13ChunkingExperiment() {
        return args -> {
            log.info("========== 迭代 13 · 分块对比实验 ==========");

            List<Document> docs = CorpusLoader.loadDocuments();
            log.info("语料：{} 份文档，共 {} 字",
                    docs.size(), docs.stream().mapToInt(d -> d.getText().length()).sum());
            log.info("问题：{} 个，各带人工标注的正确答案来源", CASES.size());
            log.info("检索：取前 {} 段（检索方式不变，唯一变量是分块大小）", TOP_K);
            log.info("");

            List<SizeResult> results = new ArrayList<>();

            for (int size : SIZES) {
                results.add(runForSize(docs, size));
            }

            printComparison(results);
        };
    }

    /** 对一种切法跑完整套实验。 */
    private SizeResult runForSize(List<Document> docs, int size) {
        DocumentChunker chunker = DocumentChunker.of(size);
        List<Chunk> chunks = chunker.split(docs);
        MockDocumentTool tool = new MockDocumentTool(chunks);

        IntSummaryStatistics stats = chunks.stream()
                .mapToInt(c -> c.content().length())
                .summaryStatistics();

        log.info("");
        log.info("################ 切法：chunkSize={} ################", size);
        log.info("产出 {} 个片段｜平均 {} 字｜最长 {} 字｜最短 {} 字",
                chunks.size(), (int) stats.getAverage(), stats.getMax(), stats.getMin());
        log.info("");
        log.info("{} {} {} {} {}",
                ConsoleText.padRight("问题", 24),
                ConsoleText.padRight("命中", 7),
                ConsoleText.padRight("精确率", 9),
                ConsoleText.padRight("材料字数", 10),
                "召回的片段");

        int hits = 0;
        double precisionSum = 0;
        int materialSum = 0;

        for (Case c : CASES) {
            List<Chunk> got = tool.search(c.question(), TOP_K);

            long fromExpected = got.stream()
                    .filter(ch -> docIdOf(ch).equals(c.expectedDocId()))
                    .count();
            boolean hit = fromExpected > 0;
            hits += hit ? 1 : 0;

            double precision = got.isEmpty() ? 0.0 : (double) fromExpected / got.size();
            precisionSum += precision;

            int material = got.stream().mapToInt(ch -> ch.content().length()).sum();
            materialSum += material;

            log.info("{} {} {} {} {}",
                    ConsoleText.padRight(ConsoleText.shorten(c.question(), 22), 24),
                    ConsoleText.padRight(hit ? "是" : "否", 7),
                    ConsoleText.padRight(String.format("%.2f", precision), 9),
                    ConsoleText.padRight(String.valueOf(material), 10),
                    ConsoleText.shorten(describe(got), 46));
        }

        int n = CASES.size();
        double avgPrecision = precisionSum / n;
        int avgMaterial = materialSum / n;

        // ---- 附带实验：能不能区分两个近似编码 ----
        boolean canDistinguish = canDistinguishCodes(tool);
        log.info("");
        log.info("附带实验：查「{}」和「{}」，返回的首个片段是否不同？",
                CODE_A, CODE_B);
        log.info("  → {}", canDistinguish
                ? "不同 [OK] 能区分开"
                : "相同 [X] 区分不开");

        return new SizeResult(size, chunks.size(), (int) stats.getAverage(),
                stats.getMax(), hits, avgPrecision, avgMaterial, canDistinguish);
    }

    /**
     * 两个近似编码能不能被区分开。
     *
     * <p><b>这是 迭代 12 留下的待办。</b>当时整篇文档当一个片段，两个编码在同一份文档里，
     * 查哪个都返回同一份，区分不开。分块之后应该能区分——前提是块小到能把它们分开。
     */
    private boolean canDistinguishCodes(MockDocumentTool tool) {
        List<Chunk> a = tool.search(CODE_A, 1);
        List<Chunk> b = tool.search(CODE_B, 1);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return !a.get(0).id().equals(b.get(0).id());
    }

    private void printComparison(List<SizeResult> results) {
        log.info("");
        log.info("================= 三种切法对比 =================");
        log.info("{} {} {} {} {} {}",
                ConsoleText.padRight("切法", 9),
                ConsoleText.padRight("片段数", 9),
                ConsoleText.padRight("平均字数", 11),
                ConsoleText.padRight("命中率", 9),
                ConsoleText.padRight("平均精确率", 13),
                "平均材料字数");

        for (SizeResult r : results) {
            log.info("{} {} {} {} {} {}",
                    ConsoleText.padRight("chunkSize=" + r.chunkSize(), 9),
                    ConsoleText.padRight(String.valueOf(r.chunkCount()), 9),
                    ConsoleText.padRight(String.valueOf(r.avgChars()), 11),
                    ConsoleText.padRight(r.hitCount() + "/" + CASES.size(), 9),
                    ConsoleText.padRight(String.format("%.2f", r.avgPrecision()), 13),
                    r.avgMaterialChars());
        }

        log.info("");
        log.info("================= 怎么读这张表 =================");
        log.info("命中率：三种切法可能都不差——因为答案确实在那份文档里，块大块小都躲不掉。");
        log.info("        【这个指标区分不出切法好坏】，这是它作为评测指标的局限。");
        log.info("");
        log.info("精确率：块越小越高。因为块小了，一个片段里混的无关内容就少，");
        log.info("        召回的前几段更可能都来自正确的那份文档。");
        log.info("");
        log.info("材料字数：块越大越多。这是上下文成本，直接对应 token 花费和 迭代 4 的压缩压力。");
        log.info("");
        log.info("近似编码能否区分：这是小块的独特收益。");
        log.info("        {} 和 {} 只差一个字母，块大到一定程度它们就落在同一个片段里，", CODE_A, CODE_B);
        log.info("        检索层面再也分不开——而这两个型号的价格和定位都不同。");
        log.info("");
        log.info("所以结论不是「越小越好」，是三条曲线的权衡：");
        log.info("  块小 → 精确率高、材料少，但可能丢上下文（表格行脱离表头、结论脱离前提）");
        log.info("  块大 → 上下文完整，但精确率低、材料多、近似项分不开");
        log.info("");
        log.info("【本实验的局限，必须说清楚】：");
        log.info("  1. 检索方式仍是字符匹配，不是向量检索。所以「精确率」衡量的是");
        log.info("     字符匹配的精确率，换成向量检索后数字会变（迭代 14）。");
        log.info("  2. 语料只有 6 份、5100 字。这个规模下三种切法的命中率都很好看，");
        log.info("     真实规模（几百上千份）下块大的劣势会更明显。");
        log.info("  3. 精确率按「是否来自正确文档」算，没有判断片段内容是否真的回答了问题——");
        log.info("     同一份文档里也有无关段落。更严格的评测要判断片段级相关性（迭代 15）。");
    }

    /** 从片段 id（形如 {@code 文档id#序号}）里取回文档 id。 */
    private String docIdOf(Chunk c) {
        int i = c.id().indexOf('#');
        return i > 0 ? c.id().substring(0, i) : c.id();
    }

    private String describe(List<Chunk> got) {
        if (got.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (Chunk c : got) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(docIdOf(c)).append("#").append(c.id().substring(c.id().indexOf('#') + 1));
        }
        return sb.toString();
    }
}
