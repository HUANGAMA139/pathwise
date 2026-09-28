package com.example.smartdataqa.agent;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 迭代 14 · 同一批问题，两种检索方式并排跑。
 *
 * <p><b>本轮要验证的不是「向量是不是更好」，而是 迭代 12 留下的两个具体预言。</b>
 * 迭代 12 当时用字符匹配画出了能力的边界，并写下两句可证伪的话：
 * <ol>
 *   <li>「同义但字面不同 → 命不中。这是向量检索要解决的」</li>
 *   <li>「近似编码 → 嵌入模型会把它们映射到很近的位置，反而会混淆」</li>
 * </ol>
 * 第 1 条如果成立，说明换向量换对了；第 2 条如果成立，说明 <b>迭代 16 的混合检索
 * （保留关键词通道）是必需的，不是锦上添花</b>。两条方向相反，合起来才是完整的判断。
 *
 * <h3>怎么读输出</h3>
 * 每题下面两段：先向量检索的 top{@value #TOP_K}，再字符匹配的同一批。
 * 看三件事：<b>期望出处有没有被召回</b>、<b>召回的前几段是不是都来自同一份文档</b>、
 * <b>得分的量级</b>。
 *
 * <p><b>得分别跨实现比。</b>字符匹配的分数是「查询的 2-gram 有多少出现在片段里」，
 * 向量检索是 1 - 余弦距离。两者值域都是 0~1，但分布完全不同——
 * 向量的分数会挤在一起（一堆 0.6~0.8），字符匹配会拉得很开。
 * 所以「0.78 比 0.5 好」这种话在两个实现之间是不成立的。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp14.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp14.enabled", havingValue = "true")
public class VectorSearchDemo {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchDemo.class);

    /** 取前几段。本轮按需求取 5。 */
    private static final int TOP_K = 5;

    /**
     * 一条用例：问题 + 人工标注的正确答案出处 + 这条在测什么。
     *
     * <p>标注期望出处是必要的——没有标注就只能「感觉」哪个结果好。
     * 这也是 迭代 15 评测集的结构。
     */
    private record Case(String question, String expectedDocId, String note) {
    }

    private static final List<Case> CASES = List.of(
            new Case("销售额是怎么算的？", "01-销售数据统计口径说明",
                    "字面几乎完全重合——两种方式都该命中，用来做基线"),
            new Case("这个东西是怎么算出来的", "01-销售数据统计口径说明",
                    "同义不同字——迭代 12 就是拿这句当反例的"),
            new Case("华东区都包括哪儿", "02-销售区域划分规则",
                    "同义（问句说「包括哪儿」，文档写的是「包含哪些省」）"),
            new Case("退款的钱什么时候扣", "03-售后与退货管理流程",
                    "同义（文档原话是「退货金额在审核通过时即扣减」）"),
            new Case("XR-2000B 和 XR-2000C 有什么区别", "05-产品线与编码规则",
                    "精确编号——看向量会不会把这两个型号混起来"));

    /** 一对只差一个字母、但含义完全不同的编码（语料 05 里特意埋的）。 */
    private static final String CODE_A = "XR-2000B";
    private static final String CODE_B = "XR-2000C";

    @Bean
    ApplicationRunner exp14VectorSearch(
            @Qualifier("documentTool") DocumentTool vectorTool,
            @Qualifier("mockDocumentTool") DocumentTool charTool) {

        return args -> {
            log.info("========== 迭代 14 · 向量检索 vs 字符匹配 ==========");
            log.info("同一份语料、同一批问题、同一个 top{}，唯一变量是【检索方式】", TOP_K);
            log.info("语料：迭代 13 切好的分块，已灌进 pgvector（入库日志在上面）");
            log.info("");
            log.info("两个工具的接口都是 DocumentTool——调用方看不出区别。");
            log.info("");

            int vectorHits = 0;
            int charHits = 0;

            for (Case c : CASES) {
                log.info("----------------------------------------------------------");
                log.info("【问】{}", c.question());
                log.info("【注】{}", c.note());
                log.info("【期望出处】{}", c.expectedDocId());

                List<Chunk> v = vectorTool.search(c.question(), TOP_K);
                List<Chunk> m = charTool.search(c.question(), TOP_K);

                boolean vHit = hit(v, c.expectedDocId());
                boolean mHit = hit(m, c.expectedDocId());
                vectorHits += vHit ? 1 : 0;
                charHits += mHit ? 1 : 0;

                log.info("");
                log.info("  [向量检索] top{}  {}", TOP_K, vHit ? "命中期望出处 [OK]" : "没命中期望出处 [X]");
                printHits(v);
                log.info("");
                log.info("  [字符匹配] top{}  {}", TOP_K, mHit ? "命中期望出处 [OK]" : "没命中期望出处 [X]");
                printHits(m);
                log.info("");
            }

            printSummary(vectorHits, charHits);
            printCodeProbe(vectorTool, charTool);
            printHowToRead();
        };
    }

    /** 把一段结果打成几行。 */
    private void printHits(List<Chunk> hits) {
        if (hits.isEmpty()) {
            log.info("      （一个都没命中——相关度为 0 的片段会被丢掉）");
            return;
        }
        for (Chunk ch : hits) {
            log.info("      {} {} {}",
                    ConsoleText.padRight(ch.id(), 34),
                    ConsoleText.padRight(String.format("%.4f", ch.score()), 9),
                    ConsoleText.shorten(ch.source(), 26));
        }
    }

    /** 期望出处有没有出现在召回的片段里。 */
    private boolean hit(List<Chunk> hits, String expectedDocId) {
        return hits.stream().anyMatch(ch -> docIdOf(ch.id()).equals(expectedDocId));
    }

    private void printSummary(int vectorHits, int charHits) {
        log.info("==========================================================");
        log.info("===================== 汇总（5 个问题） =====================");
        log.info("{} {} {}", ConsoleText.padRight("检索方式", 14),
                ConsoleText.padRight("命中期望出处", 14), "说明");
        log.info("{} {} {}", ConsoleText.padRight("向量", 14),
                ConsoleText.padRight(vectorHits + "/" + CASES.size(), 14), "语义相近就能召回");
        log.info("{} {} {}", ConsoleText.padRight("字符匹配", 14),
                ConsoleText.padRight(charHits + "/" + CASES.size(), 14), "字面重合才能召回");
        log.info("");
    }

    /**
     * 精确编号专项：迭代 12 的第二个预言。
     *
     * <p>迭代 12 当时因为还没分块，两个编码在同一份文档里、根本区分不开，
     * 所以那句预言没法验证。迭代 13 切了块之后，字符匹配已经能分开它们了；
     * <b>本轮的看点变成：换成向量检索之后，这个能力还在不在。</b>
     */
    private void printCodeProbe(DocumentTool vectorTool, DocumentTool charTool) {
        log.info("==========================================================");
        log.info("============== 专项：近似编码会不会被混淆 ==============");
        log.info("{} 和 {} 只差最后一个字母，但价格和定位都不同（语料 05 原文）。", CODE_A, CODE_B);
        log.info("");

        probe(vectorTool, "向量检索", CODE_A, CODE_B);
        probe(charTool, "字符匹配", CODE_A, CODE_B);

        log.info("");
        log.info("如果向量这条分不开，就印证了 迭代 12 的第 2 句预言——");
        log.info("那 迭代 16 的混合检索（保留关键词通道）就不是优化，而是必需的。");
        log.info("");
    }

    private void probe(DocumentTool tool, String label, String codeA, String codeB) {
        List<Chunk> a = tool.search(codeA, 1);
        List<Chunk> b = tool.search(codeB, 1);
        String idA = a.isEmpty() ? "（无）" : a.get(0).id();
        String idB = b.isEmpty() ? "（无）" : b.get(0).id();
        double scoreA = a.isEmpty() ? 0.0 : a.get(0).score();
        double scoreB = b.isEmpty() ? 0.0 : b.get(0).score();
        boolean separated = !idA.equals(idB);

        log.info("  [{}]", label);
        log.info("      查 {} → {} （得分 {}）", CODE_A, idA, String.format("%.6f", scoreA));
        log.info("      查 {} → {} （得分 {}）", CODE_B, idB, String.format("%.6f", scoreB));
        log.info("      首个片段是否不同：{}", separated ? "不同 [OK] 能区分" : "相同 [X] 区分不开");
        log.info("      两个得分的差距：{}", String.format("%.6f", Math.abs(scoreA - scoreB)));
    }

    private void printHowToRead() {
        log.info("==========================================================");
        log.info("======================== 怎么读 ========================");
        log.info("1. 先看两个【同义问法】的用例（第 2、3、4 题）。");
        log.info("   命中数如果拉开了，就验证了 迭代 12 的第 1 句预言：");
        log.info("   字符匹配比的是「字有没有出现」，语义相同但用词不同它就看不见。");
        log.info("");
        log.info("2. 再看最后的近似编码专项。这是方向相反的一条：");
        log.info("   向量把语义压成距离，而 {} / {} 语义确实很近，", CODE_A, CODE_B);
        log.info("   所以有可能反而分不开——那是数据事故，不是体验问题。");
        log.info("");
        log.info("3. 最后看得分量级。两边的分数【不要互相比较】：");
        log.info("   字符匹配的分数会拉得很开（0.0 ~ 1.0 都有），");
        log.info("   向量的分数会挤在一起（大多在 0.6 ~ 0.8）。");
        log.info("   所以 迭代 25 那个相似度阈值，必须在【当前这个实现】上重新标定。");
        log.info("");
        log.info("【本轮不急着下结论】：5 个问题太少，只够看出方向。");
        log.info("真正的量化留给 迭代 15 的评测集，那才是一批能反复跑的数据。");
    }

    /** 从片段 id（形如 {@code 文档id#序号}）里取回文档 id。 */
    private String docIdOf(String chunkId) {
        int i = chunkId.indexOf('#');
        return i > 0 ? chunkId.substring(0, i) : chunkId;
    }
}
