package com.example.smartdataqa.rag.pipeline;

import com.example.smartdataqa.tools.Chunk;

import java.util.List;

/**
 * 迭代 26 · <b>第五层：注入器</b>。对应 Spring AI 的 {@code QueryAugmenter} 槽位。
 *
 * <h3>为什么这一层之前是隐形的</h3>
 * 它原来就是 {@code SmartQaAgent.buildEvidence} 里的几行字符串拼接——<b>散在主干里，
 * 看不出来它是一层</b>。抽出来的好处是它开始<strong>可替换、可单独解释</strong>：
 * 「拼材料」这件事本身值得独立决策（带不带出处？片段之间怎么分隔？要不要加一句「只根据材料回答」？）。
 *
 * <p>它对应的是 迭代 8 定 {@code Chunk.source} 那句话的落点：
 * <b>材料里带出处，答案里才能标出处，用户才有东西可核实。</b>
 *
 * <p><b>诚实边界</b>：这一层影响的是<b>生成</b>，不影响检索排序，
 * 所以本轮的确定性检索指标（hit@k / MRR）<b>量不出它</b>——本轮只把它做成可插拔，
 * 不声称它有效。要量它得看 迭代 23 那套端到端指标或答案忠实性。
 */
public interface ContextAugmenter {

    /**
     * 把召回片段拼成给模型的文档上下文。
     *
     * @param chunks 召回片段。<b>{@code null} 表示这条支路没走</b>，返回空串；
     *               <b>空列表</b>表示走了但没召回，返回「没检索到」的说明——
     *               这两种情况必须分开（「没走」和「没找到」对模型是两件事）。
     */
    String augment(List<Chunk> chunks);

    /** 档位名。 */
    String name();
}
