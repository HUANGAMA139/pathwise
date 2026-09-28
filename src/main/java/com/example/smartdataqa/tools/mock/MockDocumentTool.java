package com.example.smartdataqa.tools.mock;

import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DocumentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 迭代 12 起 · 文档检索的实现（假的仍然是「检索方式」）。
 *
 * <h3>迭代 13 改了什么</h3>
 * 原来它自己在构造函数里加载语料、每个文件当一个片段。
 * 现在改成<b>由外部把切好的片段传进来</b>——因为它不该管分块。
 *
 * <p>这样拆之后，这个类只对一件事负责：<b>给一个查询，从一批片段里挑出最相关的几个</b>。
 * 分块策略怎么选、语料从哪来，都与它无关。
 * 迭代 13 的对比实验就是靠这一点——同一份语料、同一批问题，
 * 只换分块方式，就能看出分块的影响。
 *
 * <h3>仍然是假的</h3>
 * 没有 embedding、没有向量库，只是拿查询里的 2-gram 做字符匹配。
 * 换成向量检索是 迭代 14；迭代 16 再加 BM25 做混合检索。
 * 但接口一直是 {@link DocumentTool}。
 */
public class MockDocumentTool implements DocumentTool {

    private static final Logger log = LoggerFactory.getLogger(MockDocumentTool.class);

    private final List<Chunk> chunks;

    public MockDocumentTool(List<Chunk> chunks) {
        this.chunks = List.copyOf(chunks);
    }

    /** 当前检索的片段总数。对比实验里要用。 */
    public int corpusSize() {
        return chunks.size();
    }

    @Override
    public List<Chunk> search(String query, int topK) {
        log.info("      [DocumentTool] 检索：query=\"{}\" topK={}", shorten(query), topK);

        List<Chunk> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            double score = crudeScore(query, c.content());
            if (score > 0) {
                scored.add(new Chunk(c.id(), c.content(), c.source(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Chunk::score).reversed());

        if (scored.isEmpty()) {
            log.info("      [DocumentTool] 没有相关片段（相关度全为 0）");
            return List.of();
        }

        List<Chunk> top = scored.subList(0, Math.min(topK, scored.size()));
        log.info("      [DocumentTool] 命中 {} 个片段，返回 {} 个，最高分 {}",
                scored.size(), top.size(), String.format("%.3f", top.get(0).score()));
        return top;
    }

    /**
     * 极简打分：查询里的 2-gram 有多少出现在片段里。
     *
     * <p><b>这个打分方式有明显缺陷，而且 迭代 12/13 的验收标准正好指着它：</b>
     * 它只看「字符有没有出现」，完全不懂语义。
     *
     * <p>反过来，它在精确编号上是好的——`XR-2000B` 这种串能精确匹配。
     * <b>迭代 16 的混合检索就是把这两种互补的能力合起来。</b>
     */
    private double crudeScore(String query, String content) {
        if (query == null || query.isBlank()) {
            return 0.0;
        }
        String q = query.replaceAll("[\\p{Punct}\\s，。？！、；：（）《》]", "");
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 2 <= q.length(); i++) {
            total++;
            if (content.contains(q.substring(i, i + 2))) {
                hit++;
            }
        }
        return total == 0 ? 0.0 : (double) hit / total;
    }

    private String shorten(String s) {
        return s == null ? "" : (s.length() <= 24 ? s : s.substring(0, 24) + "…");
    }
}
