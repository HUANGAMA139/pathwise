package com.example.smartdataqa.trace;

/**
 * 迭代 29 · 轨迹里的一步：哪个阶段、做了什么、花了多久。
 *
 * <p>{@code detail} 是这一步的**证据摘要**，不是「执行成功」这种废话——
 * 路由那步写的是「选了哪条 + 置信度 + 理由」，
 * 检索那步写的是「召回几段、来自哪几份文档」，
 * SQL 那步写的是「跑了几行 + SQL 前几十个字符」。
 *
 * <p>这样做的理由是 迭代 4 那条：<b>「A 的输出喂给 B」的链路里，中间产物是错误放大器。</b>
 * 最终答案错了，你得能一眼看出是哪一步先偏的。
 *
 * @param stage  阶段（枚举，见 {@link TraceStage}）
 * @param detail 这一步产出了什么（一句话摘要）
 * @param millis 这一步花了多少毫秒
 */
public record TraceStep(TraceStage stage, String detail, long millis) {
}
