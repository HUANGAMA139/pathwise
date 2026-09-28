package com.example.smartdataqa.router;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 路由决策 —— 整个项目的核心契约。
 *
 * <p>模型（或规则）判断一个用户问题该走哪条路，结果就是这个对象。
 * <b>它是「原创点」的载体</b>：开源里的数据问答项目要么只查库、要么只查文档，
 * 没人把「该走哪条路」当成一个要输出的决策。
 *
 * <h3>为什么是四个值，不是两个</h3>
 * 直觉上答案只有「查文档」和「查数据库」两种。但实际跑下来，两种情况各占一头：
 *
 * <ul>
 *   <li><b>both</b>——两类信息都需要。典型例子：
 *       「上季度华东区销售额是多少？顺便说下口径是怎么定的」
 *       数字在库里，而「销售额怎么算」的定义在文档里。只走一边，用户拿到的答案就是残缺的。</li>
 *   <li><b>refuse</b>——两边都不该走。比如闲聊、或者超出系统知识范围的问题。
 *       没有这个值，模型会被迫在两条错的路里选一条，
 *       于是要么硬查库编出个数字、要么硬检索文档答非所问。</li>
 * </ul>
 *
 * <p><b>这两个额外值的意义不在于「多两个选项」，而在于逼模型先判断问题的类型。</b>
 * 二选一的题，模型永远能选出一个答案，哪怕答案是错的；
 * 四选一的题，它有了说「这题不该用我」的出口。
 *
 * <h3>这个契约后面会连着谁</h3>
 * <ul>
 *   <li>{@code reason} 字段——迭代 25 做「路由失败代价分析」时的唯一抓手。
 *       没有它，你只知道选错了，不知道它当时在想什么。</li>
 *   <li>{@link Route#code()}——迭代 15 建评测集时，它就是 {@code expected_route} 列的取值，
 *       也是评测里「路由准确率」和「混淆矩阵」的计算依据。</li>
 * </ul>
 *
 * @param route      走哪条路
 * @param confidence 模型的把握程度，0 到 1。低置信度是「要不要兜底」的触发条件（迭代 25）
 * @param reason     为什么这么选。给人和给评测看的，不是给模型看的
 */
public record RouteDecision(Route route, double confidence, String reason) {

    /**
     * 可选的四条路。
     *
     * <p>用枚举而不是 String，是为了让「拼错值」在编译期就暴露——
     * 而不是等到运行时模型返回了一个你没预料的值、代码走进 default 分支才发现。
     */
    public enum Route {

        /** 答案在制度文档里：口径、定义、规则、流程。 */
        DOCUMENT("document"),

        /** 答案在业务数据里：金额、数量、排名、同比环比。 */
        DATABASE("database"),

        /** 两边都需要，缺一个答案就不完整。 */
        BOTH("both"),

        /** 不属于以上任何一类，应该明确拒绝而不是硬答。 */
        REFUSE("refuse");

        private final String code;

        Route(String code) {
            this.code = code;
        }

        /**
         * 对外（评测集、日志、前端、以及<b>模型输出</b>）用的字符串标识。
         *
         * <p>{@code @JsonValue} 让 Jackson 序列化时用这个 code 而不是枚举名。
         * 于是模型看到的 schema 里是 {@code "document"} 而不是 {@code "DOCUMENT"}——
         * 和提示词里写的一致，它更不容易输出错。
         */
        @JsonValue
        public String code() {
            return code;
        }

        /**
         * 从字符串还原。
         *
         * <p>{@code @JsonCreator} 让 Jackson 反序列化时走这个方法，
         * 于是模型返回 {@code "document"} 能正确映射到 {@link #DOCUMENT}。
         * <b>不加这个注解，Jackson 默认按枚举名（大写）匹配，模型输出的小写值会解析失败。</b>
         *
         * <p><b>认不出来的值选择抛异常，而不是静默返回 REFUSE。</b>
         * 这是刻意的：如果模型拼错了（比如返回 "documet"），
         * 静默变成「拒答」会让用户看到一句莫名其妙的「这不在我能力范围」——
         * 错误被伪装成了正常行为。抛出去让调用方处理（降级到关键词路由），
         * 至少日志里能看见出了问题。
         *
         * @throws IllegalArgumentException 无法识别时
         */
        @JsonCreator
        public static Route fromCode(String code) {
            if (code != null) {
                String c = code.trim();
                for (Route r : values()) {
                    // 同时接受 code（小写）和枚举名（大写），容错模型输出的两种写法
                    if (r.code.equalsIgnoreCase(c) || r.name().equalsIgnoreCase(c)) {
                        return r;
                    }
                }
            }
            throw new IllegalArgumentException(
                    "无法识别的路由值：" + code + "（期望 document / database / both / refuse 之一）");
        }
    }

    /** 这条决策需不需要走文档支路。 */
    public boolean needsDocument() {
        return route == Route.DOCUMENT || route == Route.BOTH;
    }

    /** 这条决策需不需要走数据库支路。 */
    public boolean needsDatabase() {
        return route == Route.DATABASE || route == Route.BOTH;
    }

    /** 打日志用的一行摘要。 */
    public String brief() {
        return route.code() + " (置信度 " + String.format("%.2f", confidence) + ")";
    }
}
