package com.example.smartdataqa.trace;

/**
 * 迭代 29 · 轨迹里的一步属于哪个阶段。
 *
 * <p><b>为什么用枚举而不是 String</b>——理由和 {@code RouteDecision.Route} 一模一样：
 * <b>拼错值要在编译期暴露。</b>
 * 轨迹不只是打给人看的，它还要被程序统计（「路由一共占了多少毫秒」）。
 * 一个拼错的字符串不会报任何错，只会让那一行统计<b>静默地少掉</b>——
 * 这正是本项目一直在抓的那类「不响的错」。
 *
 * <p>{@code label} 是给日志和响应体用的人话，长度都控制在 5 个字以内，
 * 这样 {@code render()} 里按显示宽度对齐不会太宽。
 */
public enum TraceStage {

    /** 第 1 步：判断该走文档、数据库、两边都要、还是拒答。 */
    ROUTE("路由"),

    /** 第 2 步之一：文档支路检索（召回 + 精排）。 */
    RETRIEVE("文档检索"),

    /** 第 2 步之二：数据库支路（筛表 → 生成 SQL → 执行）。 */
    SQL("数据库查询"),

    /** 第 3 步：把两路证据拼成给模型的材料。 */
    PREPARE("拼材料"),

    /** 第 4 步：模型综合（这一步最贵，通常占总耗时的一半以上）。 */
    SYNTHESIZE("模型综合"),

    /** 拒答的出口：没有调模型，但轨迹上要留一笔。 */
    REFUSE("拒答");

    private final String label;

    TraceStage(String label) {
        this.label = label;
    }

    /** 人话标签（中文，用于控制台和响应体）。 */
    public String label() {
        return label;
    }
}
