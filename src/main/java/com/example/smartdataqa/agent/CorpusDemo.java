package com.example.smartdataqa.agent;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.corpus.CorpusLoader;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代 12 · 语料就位，以及检索能力的边界。
 *
 * <p>本轮的产出是语料，但光有语料看不出对错。这个 demo 用三组查询把
 * <b>当前检索方式的边界</b>画出来——边界清楚了，才知道 迭代 14 和 迭代 16 该补什么。
 *
 * <h3>三组查询，两个方向</h3>
 * <ol>
 *   <li><b>字面重合 → 命得中。</b>这是当前方式擅长的</li>
 *   <li><b>同义但不同字 → 命不中。</b>这是向量检索要解决的（迭代 14）</li>
 *   <li><b>精确编号 → 命得准。</b>这是纯向量检索会丢的（迭代 16 要保留的）</li>
 * </ol>
 *
 * <p>第 2 和第 3 条结论方向相反，而它们合起来正好说明了
 * <b>为什么最后要做混合检索</b>——不是「向量比关键词好」，是两者互补。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp12.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp12.enabled", havingValue = "true")
public class CorpusDemo {

    private static final Logger log = LoggerFactory.getLogger(CorpusDemo.class);

    /** 检索取前几份。 */
    private static final int TOP_K = 3;

    /** 第 2 组：同义但字面完全不同的一对查询。 */
    private static final String LITERAL_HIT = "销售额是怎么计算的";
    private static final String SYNONYM_MISS = "这个东西是怎么算出来的";

    /** 第 3 组：一对只差一个字母、但完全不同的产品编码。 */
    private static final String CODE_A = "XR-2000B";
    private static final String CODE_B = "XR-2000C";

    @Bean
    ApplicationRunner exp12Corpus(@Qualifier("mockDocumentTool") DocumentTool documentTool) {
        return args -> {
            log.info("========== 迭代 12 · 语料与检索的边界 ==========");

            // ---------------- 第 1 部分：语料概览 ----------------
            // 迭代 13 起 CorpusLoader 返回的是 Document 而不是 Chunk——
            // 因为「一个文件」和「一个片段」不再是一回事了，中间多了分块这一步。
            // 这一部分要看的是「有几份文件」，所以直接列 Document。
            List<Document> corpus = CorpusLoader.loadDocuments();
            log.info("");
            log.info("================= 语料清单 =================");
            log.info("{}  {}", ConsoleText.padRight("id", 12), "出处");
            int totalChars = 0;
            for (Document d : corpus) {
                int len = d.getText().length();
                totalChars += len;
                log.info("{}  {}  ({} 字)",
                        ConsoleText.padRight(d.getId(), 12),
                        ConsoleText.shorten(
                                String.valueOf(d.getMetadata().get(CorpusLoader.META_SOURCE)), 40),
                        len);
            }
            log.info("");
            log.info("共 {} 份，合计 {} 字", corpus.size(), totalChars);

            // ---------------- 第 2 部分：字面重合 ----------------
            log.info("");
            log.info("============== 第 2 组：字面重合能命中 ==============");
            log.info("这一组是当前检索方式擅长的：查询里的词在文档里出现过。");
            log.info("");
            showSearch(documentTool, LITERAL_HIT);

            // ---------------- 第 3 部分：同义不同字 ----------------
            log.info("");
            log.info("============== 第 3 组：同义但字面不同，会命错 ==============");
            log.info("【{}】和【{}】问的是同一件事——销售额的计算方式。", LITERAL_HIT, SYNONYM_MISS);
            log.info("看两次结果的区别：前者的最高分是口径说明，后者命中的是另一个文档，");
            log.info("而且分数明显低。");
            log.info("");
            showSearch(documentTool, SYNONYM_MISS);

            log.info("");
            log.info("★ 这就是 迭代 14 要解决的问题。");
            log.info("  当前方式比的是**字符有没有出现**，所以「怎么计算」和「怎么算出来」");
            log.info("  在它眼里是两串不同的字，尽管它们是一个意思。");
            log.info("  向量检索比的是**语义**：它把文本映射成向量，");
            log.info("  这两句会被映射到很近的位置，所以能命中。");

            // ---------------- 第 4 部分：精确编号 ----------------
            log.info("");
            log.info("============== 第 4 组：精确编号 ==============");
            log.info("这两个编码只差最后一个字母，但它们是两个完全不同的产品：");
            log.info("");
            log.info("  {}  vs  {}", CODE_A, CODE_B);
            log.info("  字符重合度：{}", String.format("%.0f%%", charOverlap(CODE_A, CODE_B) * 100));
            log.info("");
            log.info("  字符层面几乎一样，**嵌入模型也会把它们映射到很近的位置**——");
            log.info("  因为向量看的是整体语义，一两个字符的差别对向量影响很小。");
            log.info("  而报表里这两个型号价格和定位都不同，混了就是数据事故。");
            log.info("");
            log.info("  当前这种「精确字符匹配」的做法在这类场景下反而是对的：");

            List<Chunk> fromA = documentTool.search(CODE_A, 2);
            List<Chunk> fromB = documentTool.search(CODE_B, 2);
            log.info("");
            log.info("  查 {} 的首个片段：{}", CODE_A,
                    fromA.isEmpty() ? "（无）" : shortenId(fromA.get(0).id()));
            log.info("  查 {} 的首个片段：{}", CODE_B,
                    fromB.isEmpty() ? "（无）" : shortenId(fromB.get(0).id()));
            log.info("");
            if (!fromA.isEmpty() && !fromB.isEmpty()
                    && !fromA.get(0).id().equals(fromB.get(0).id())) {
                log.info("  → 两个编码落在了【不同的片段】上，能区分开 [OK]");
            }
            else {
                log.info("  → 两个编码落到了同一个片段上，区分不开 [X]");
                log.info("     （当前分块大小下它们挨在一起。迭代 13 的实验会量这个。）");
            }

            // ---------------- 总结 ----------------
            log.info("");
            log.info("================= 结论 =================");
            log.info("第 3 组说明：光看字面不够，会命中文不对题的文档 —— 需要向量检索（迭代 14）");
            log.info("第 4 组说明：光看语义不够，近似编码会被混淆 —— 需要保留关键词检索（迭代 16）");
            log.info("");
            log.info("这两条结论方向相反，合起来就是 迭代 16 混合检索的理由：");
            log.info("  向量擅语义（同义、改写、口语化），关键词擅精确（编码、型号、表名、编号）");
            log.info("  两者互补，不是替代关系。");
            log.info("");
            log.info("本轮还多出一个待办：分块（迭代 13）。");
            log.info("  现在整篇文档就是一个片段，粒度过粗——");
            log.info("  既是第 4 组区分不开的原因，也是材料动辄上千字塞满上下文的原因。");
            log.info("");
            log.info("回到本轮的验收标准：");
            log.info("  纯向量检索对产品型号、表名、编号这类精确关键词常常失效——");
            log.info("  因为嵌入模型会把语义相近但含义不同的串映射到相近位置，");
            log.info("  而这类串恰恰要求精确区分。第 4 组的 71% 字符重合度就是这个问题的微观版本。");
        };
    }

    private void showSearch(DocumentTool tool, String query) {
        log.info("  查询：「{}」", query);
        List<Chunk> hits = tool.search(query, TOP_K);
        if (hits.isEmpty()) {
            log.info("    → 命中 0 份");
        }
        else {
            for (Chunk c : hits) {
                log.info("    → {}（相关度 {}）", ConsoleText.shorten(c.source(), 36),
                        String.format("%.3f", c.score()));
            }
        }
        log.info("");
    }

    /**
     * 两串的字符集合重合度（Jaccard）。
     *
     * <p>用它来粗略展示「表面有多像」。
     * 真正的嵌入模型算的是语义距离，但**对这类编码来说，字符重合度高往往也意味着
     * 向量距离近**——这正是问题所在。
     */
    private double charOverlap(String a, String b) {
        Set<Character> sa = new HashSet<>();
        Set<Character> sb = new HashSet<>();
        for (char c : a.toCharArray()) {
            sa.add(c);
        }
        for (char c : b.toCharArray()) {
            sb.add(c);
        }
        Set<Character> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<Character> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    /**
     * 把片段 id 压成简短形式，便于在日志里对齐：{@code 05-产品线与编码规则#0} → {@code 05#0}。
     *
     * <p><b>这里不能用 {@link ConsoleText#shorten} 从右边截断。</b>第 4 组要比较的正是
     * 「文档编号 + 片段序号」这两段，而从右边截断会先把真正的差别（只差最后一个字符）
     * 截掉——两个不同片段显示成一模一样，反而制造误判。
     * 所以这个方法保留左侧的编号和序号，只去掉中间那段中文标题。
     */
    private static String shortenId(String id) {
        int hash = id.indexOf('#');
        if (hash <= 0) {
            return id;
        }
        String doc = id.substring(0, hash);
        int dash = doc.indexOf('-');
        return (dash > 0 ? doc.substring(0, dash) : doc) + id.substring(hash);
    }
}
