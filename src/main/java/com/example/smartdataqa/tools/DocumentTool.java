package com.example.smartdataqa.tools;

import java.util.List;

/**
 * 文档检索支路。给一个问题，返回相关的文档片段。
 *
 * <p>迭代 8 是 mock，后面会被逐层替换：
 * <ul>
 *   <li>迭代 14：接上 pgvector，真实向量检索</li>
 *   <li>迭代 16：加 BM25 关键词通道，变成混合检索</li>
 *   <li>迭代 17：加 rerank / MMR 重排</li>
 * </ul>
 * <b>但接口不变。</b>所以 迭代 14 换实现时，Agent 主干一行都不用改——
 * 和 {@code QueryRouter} 是同一个手法。
 */
public interface DocumentTool {

    /**
     * 检索相关文档片段。
     *
     * @param query 检索用的查询语句（通常是用户的原始问题，迭代 22 的查询重写会改这个环节）
     * @param topK  最多返回几段。控制上下文占用，也和 迭代 20 的 rerank 配合
     * @return 相关片段，按相关度从高到低
     */
    List<Chunk> search(String query, int topK);
}
