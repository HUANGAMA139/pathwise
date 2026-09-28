package com.example.smartdataqa.rag.pipeline;

import com.example.smartdataqa.tools.Chunk;

import java.util.List;

/**
 * 迭代 26 · <b>注入器的默认档</b>：和 {@code SmartQaAgent.buildEvidence} 原来那几行<b>逐字节一致</b>。
 *
 * <p><b>为什么强调「逐字节一致」</b>：抽层是<b>纯重构</b>——如果抽完拼出来的材料多一个换行、
 * 少一个空格，模型的输出就可能变，而 迭代 15/23 的所有基线数字都建立在这段材料上。
 * <b>那种偏差不会报错，只会让旧数字对不上。</b>
 * 所以这个类的唯一要求是：输出和原来那几行一模一样，并且有回归检查盯着它（见 迭代 26 的 demo）。
 */
public final class DefaultContextAugmenter implements ContextAugmenter {

    @Override
    public String augment(List<Chunk> chunks) {
        if (chunks == null) {
            // 这条支路没走 —— 原文在这种情况下什么都不加
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("========== 【文档片段】 ==========\n");
        if (chunks.isEmpty()) {
            sb.append("（没有检索到相关文档）\n");
            return sb.toString();
        }
        for (Chunk c : chunks) {
            sb.append(c.render()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String name() {
        return "default";
    }
}
