package com.example.smartdataqa.util;

import org.springframework.ai.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 19 · 向量计算的两个小工具。
 *
 * <h3>为什么抽出来：同一个东西已经要写第三遍了</h3>
 * <pre>
 *   第 1 次  迭代 17 的 {@code MmrDocumentPostProcessor}：算片段间的重复度
 *   第 2 次  迭代 17 的 A/B 实验：算返回片段的平均两两相似度（多样性指标）
 *   第 3 次  迭代 19 的表筛选：算「问题」和「表目录条目」的相似度
 * </pre>
 * 按项目里那条「第一次写、第二次忍、第三次抽」的规矩，该抽了。
 * 但更硬的理由是 迭代 17 类注释里已经写过的那一条：
 * <b>如果实验里另写一份余弦，两处的数值就不可比了</b>——而「可比」正是整个项目的立身之本。
 *
 * <h3>{@link #embedInBatches} 为什么也在这儿</h3>
 * 因为它是个<b>会咬人的坑，不该被第二次学会</b>：DashScope 单次只收 10 条文本，
 * 而 {@code EmbeddingModel.embed(List)} <b>不做分批</b>（1.1.0 里只有带
 * {@code batchingStrategy} 的那个重载才分批）。迭代 14 已经因为这件事撞过一次
 * InvalidParameter，迭代 17 的 MMR 里又写了一遍规避。本轮是第三处——
 * 与其再抄一遍，不如放到这里，让「必须自己分批」这件事只有一个出处。
 */
public final class Vectors {

    private static final Logger log = LoggerFactory.getLogger(Vectors.class);

    /**
     * 每次调 embedding 接口的批大小。
     *
     * <p>DashScope 的上限是 10，这里就取 10——调大不会更快，只会 400。
     */
    public static final int EMBED_BATCH = 10;

    private Vectors() {
    }

    /**
     * 余弦相似度。
     *
     * <p>长度不等的向量按短的算（不抛异常）——真实调用里不该出现，
     * 但真出现了，返回一个不爆的值比抛出去打断整条链路要好。
     */
    public static double cosine(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 把一个文本列表按 ≤{@link #EMBED_BATCH} 分批向量化。
     *
     * <p><b>不要用 {@code embeddingModel.embed(list)} 直接处理长列表</b>——它不分批（见类注释）。
     */
    public static List<float[]> embedInBatches(EmbeddingModel embeddingModel, List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()));
            out.addAll(embeddingModel.embed(batch));
        }
        if (texts.size() > EMBED_BATCH) {
            log.debug("分批向量化：{} 条文本，分 {} 批", texts.size(),
                    (texts.size() + EMBED_BATCH - 1) / EMBED_BATCH);
        }
        return out;
    }
}
