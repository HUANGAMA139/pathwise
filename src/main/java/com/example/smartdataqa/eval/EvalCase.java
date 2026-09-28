package com.example.smartdataqa.eval;

import com.example.smartdataqa.router.RouteDecision;

import java.util.Arrays;
import java.util.List;

/**
 * 迭代 15 · 测试集里的一行。
 *
 * <p>8 列的设计不是随便定的，每一列都对应评测里的一个动作：
 * <pre>
 *   id              定位一道题（metrics 里要能对回来）
 *   question        喂给链路
 *   expected_route  → 算「路由准确率」
 *   expected_source → 算「文档级 context 精确率/召回率」（零 LLM 成本）
 *   expected_answer → 算「答案准确率」和「LLM 版 context 召回率」（需要标准答案）
 *   category        分组看指标（比如只统计「语义」那一族）
 *   difficulty      同上
 *   note            给读的人解释「这题在考什么」
 * </pre>
 *
 * <p><b>{@code expected_route} 贯穿全表</b>，这是这个测试集最要紧的地方：
 * 只有一个「准确率」数字的话，你分不清是<b>路由选错了</b>还是<b>路由对了但检索差</b>——
 * 而这两件事的修法完全不同。
 */
public record EvalCase(String id,
                       String question,
                       String expectedRoute,
                       String expectedSource,
                       String expectedAnswer,
                       String category,
                       String difficulty,
                       String note) {

    /** 表头。读和写都用它，避免两边写岔。 */
    public static final List<String> HEADER = List.of(
            "id", "question", "expected_route", "expected_source",
            "expected_answer", "category", "difficulty", "note");

    public static EvalCase fromRow(List<String> r) {
        return new EvalCase(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6), r.get(7));
    }

    /**
     * 期望路由 → 代码里的枚举。
     *
     * <p><b>这是「测试集里写 none、代码里叫 REFUSE」的唯一映射处。</b>
     * 两个词各用各的场合：测试集是给人和报表看的（none 更直白），
     * 代码里是既有命名（迭代 8 定的，改它要动一片）。把映射收在一处，
     * 以后要统一命名也只改这里。
     */
    public RouteDecision.Route route() {
        return switch (this.expectedRoute) {
            case "document" -> RouteDecision.Route.DOCUMENT;
            case "database" -> RouteDecision.Route.DATABASE;
            case "both" -> RouteDecision.Route.BOTH;
            case "none" -> RouteDecision.Route.REFUSE;
            default -> throw new IllegalArgumentException("未知的 expected_route：" + this.expectedRoute);
        };
    }

    /** 这题会不会走文档支路——决定它有没有「检索到的上下文」可评。 */
    public boolean usesDocument() {
        return "document".equals(this.expectedRoute) || "both".equals(this.expectedRoute);
    }

    /** 期望出处。可能不止一个（both 那种一行里既有文档又有表），用 {@code |} 分隔。 */
    public List<String> sources() {
        if (this.expectedSource == null || this.expectedSource.isBlank()) {
            return List.of();
        }
        return Arrays.stream(this.expectedSource.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean hasExpectedAnswer() {
        return this.expectedAnswer != null && !this.expectedAnswer.isBlank();
    }
}
