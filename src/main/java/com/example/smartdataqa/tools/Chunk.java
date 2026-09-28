package com.example.smartdataqa.tools;

/**
 * 文档检索回来的一段文本。
 *
 * <p>迭代 8 先定形状，迭代 14 才会灌进真实的向量检索结果。
 *
 * @param id      片段标识。用于去重、以及 迭代 23 算检索指标时标注「哪些片段被召回了」
 * @param content 片段正文。会原样拼进给模型的 prompt
 * @param source  来源。<b>这个字段不是装饰</b>——答案里要能告诉用户
 *                「这句话出自哪份文档」，否则用户无法核实，也没法判断该不该信
 * @param score   相似度得分。迭代 14 接真实检索后才有意义；
 *                迭代 16/17 做混合检索和重排时，就是靠比较不同策略下的得分布局来调参
 */
public record Chunk(String id, String content, String source, double score) {

    /** 拼进 prompt 时的展示形式。带上出处，方便模型在答案里引用。 */
    public String render() {
        return "【片段 " + id + "｜出处：" + source + "】\n" + content;
    }
}
