package com.example.smartdataqa.tools.text2sql;

import com.example.smartdataqa.tools.QueryResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 20 · <b>Text2SQL 诊断集</b>：迭代 18 那 9 道题 + 判定逻辑。
 *
 * <h3>为什么从 迭代 18 的 demo 里搬出来</h3>
 * 因为 <b>迭代 20 要拿同一批题做前后对比</b>：迭代 18 是 0 通过，
 * 加了 few-shot 之后应该好多少？——这个对比只有<b>题目和判据完全一样</b>时才成立。
 *
 * <p>两组各写一份的后果很具体：改了一道题的措辞、或者调了判定的容差，
 * 两个数字就不再可比，<b>而且不会报错</b>——你会拿两个不同尺子量出来的数字做结论。
 * 这就是 迭代 17 抽 {@code DocumentChunks} 时那条「两份映射迟早写岔」的同类问题。
 *
 * <h3>判定分三档，这是「诊断」不是「指标」</h3>
 * <pre>
 *   NUMBER   有确切数字期望值，自动比（结果里【有没有任何一个】数字落在期望 ±1% 内）
 *   REFUSAL  正确答案是「答不了」——要区分「如实说不会」和「硬编一个数字」
 *   MANUAL   口径/文本类，必须人看
 * </pre>
 * 诊断可以有人工档，指标不行。正式的执行准确率是 迭代 22，用 {@code eval/} 那套脚手架。
 *
 * <p>{@code NUMBER} 档故意用「有没有任何一个数字落进来」而不是「取某一列来比」：
 * 同一个小数可能出现在不同的列名/别名下，而诊断宁可放过、不要误报。
 * 代价也要认清：多行结果里只要有一个数字撞上就算过——<b>所以它宽松，不能当指标用。</b>
 */
public final class Text2SqlDiagnostic {

    private Text2SqlDiagnostic() {
    }

    /** 判定方式。 */
    public enum Kind {
        /** 有确切的数字期望值，可以自动比。 */
        NUMBER,
        /** 正确答案是「答不了」——要区分「如实说不会」和「硬编一个数字」。 */
        REFUSAL,
        /** 口径/文本类，必须人看。 */
        MANUAL
    }

    /**
     * @param id           诊断编号
     * @param question     提问
     * @param kind         判定方式
     * @param expected     期望数值（{@code kind=NUMBER} 时用），由生成脚本算出
     * @param expectedText 期望答案的人读形式（每一档都打出来，方便人核对）
     * @param probes       这道题在探什么
     */
    public record Case(String id, String question, Kind kind, Double expected,
                       String expectedText, String probes) {
    }

    /** 9 道题。顺序沿用 迭代 18，不要重排——前后对比按 id 对。 */
    public static final List<Case> CASES = List.of(
            new Case("D1", "2026年第二季度华东区的销售额是多少？", Kind.NUMBER, 36_100_000d,
                    "36,100,000（2026Q2 华东净销售额，7 单计入）",
                    "区域过滤 + 状态过滤 + 归属期间 + refund 为 NULL"),

            new Case("D2", "2026年第二季度各区域的销售额排名", Kind.MANUAL, null,
                    "华东 36,100,000 / 华南 17,250,000 / 华北 8,420,000 / 华中 3,290,000 / 西部 2,800,000 / 东北 1,500,000",
                    "GROUP BY + ORDER BY；还要知道排序依据是净额"),

            new Case("D3", "2026年第二季度 XR 产品线的销售额是多少？", Kind.NUMBER, 68_200_000d,
                    "68,200,000",
                    "要 JOIN products 才拿得到 line_code —— 不知道表结构就会编字段"),

            new Case("D4", "2026年第二季度全国销售额合计是多少？", Kind.NUMBER, 69_360_000d,
                    "69,360,000（19 单计入）",
                    "没有区域条件时，状态过滤会不会漏"),

            new Case("D5", "2026年第一季度华东区的销售额是多少？", Kind.NUMBER, 18_300_000d,
                    "18,300,000（3 单计入）",
                    "★归属期间陷阱：有一条创建在 3 月、完成在 4 月。用 created_at 会算成 27,300,000"),

            new Case("D6", "华东区都包含哪些省份？", Kind.MANUAL, null,
                    "上海、江苏、浙江、安徽、福建",
                    "要 JOIN region_provinces；注意文档也能答这题 —— 这是双支路的重叠区"),

            new Case("D7", "2026年第二季度福建省的销售额是多少？", Kind.REFUSAL, null,
                    "库里没有省份维度 → 正确回答是「算不出来」。最坏的答案是给一个看着合理的假数字",
                    "★能力边界：表里有 region 但没有 province，任何一行都定位不到「福建的订单」"),

            new Case("D8", "2024年华东区的销售额是多少？", Kind.NUMBER, 18_300_000d,
                    "18,300,000（5 单，在 orders_archive 里）",
                    "★归档表陷阱：只查 sales_orders 会得到 0 行。这条规则在语料 04 里，不在表结构里"),

            new Case("D9", "2026年第二季度华东区的客单价是多少？", Kind.NUMBER, 6_016_666.67d,
                    "≈6,016,667（口径出自语料 06：订单数只计 COMPLETED = 6 单；"
                            + "若把 REFUNDED 也计进订单数则是 ≈5,157,143）",
                    "★口径题：表结构给不出分母口径，同一个数有两种合法读法")
    );

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /**
     * 粗判定。
     *
     * <p><b>失败标记必须和工具那边对齐。</b>这里靠 {@code "[执行失败]"} 这个字面量认出「没跑成」，
     * 所以 {@link Text2SqlDatabaseTool} 里两种失败（自己失败、自修复若干次仍失败）
     * 都带着它。哪天那边改了写法，这边就会把失败当成「跑成功但结果为空」，
     * 数字会整体偏乐观——又是一个不报错的错误。
     */
    public static String judge(Case c, QueryResult result) {
        String sql = result.sql() == null ? "" : result.sql();

        if (sql.contains("[执行失败]")) {
            return "[X] 执行失败（SQL 没跑成）";
        }
        if (sql.contains("[已阻止]")) {
            return "[X] 被最小护栏拦下（生成了非 SELECT 语句）";
        }
        if (sql.startsWith("-- 读取表结构失败") || sql.startsWith("-- 生成 SQL 失败")) {
            return "[X] 链路失败（连不上库或模型没返回）";
        }

        if (c.kind() == Kind.REFUSAL) {
            if (SqlText.isOnlyComments(sql)) {
                return "[OK] 模型主动说明「现有表和字段答不了」——它用了那个出口，没硬编数字";
            }
            if (result.rowCount() == 0) {
                return "需人工判断（返回 0 行，但没说清是答不了还是查不到）";
            }
            return "[X] 硬答了：它没承认能力边界，而是给了一个结果";
        }

        if (c.kind() == Kind.MANUAL) {
            return "需人工判断";
        }

        // NUMBER
        if (result.rowCount() == 0) {
            return "[X] 返回 0 行（期望 " + String.format("%,.0f", c.expected()) + "）";
        }
        double got = maxNumber(result);
        if (Double.isNaN(got)) {
            return "[X] 结果里没有可比的数字";
        }
        double expected = c.expected();
        if (Math.abs(got - expected) / Math.abs(expected) <= 0.01d) {
            return String.format("[OK] 数值符合（%,.0f）", expected);
        }
        return String.format("[X] 数值不符：期望 %,.0f，结果里最大的是 %,.0f", expected, got);
    }

    /** 结果里出现过的最大数字（去掉千分位后）。用于诊断判定，见 {@link #judge} 的说明。 */
    static double maxNumber(QueryResult result) {
        double best = Double.NaN;
        for (List<String> row : result.rows()) {
            for (String cell : row) {
                if (cell == null) {
                    continue;
                }
                Matcher matcher = NUMBER.matcher(cell.replace(",", ""));
                while (matcher.find()) {
                    try {
                        double value = Math.abs(Double.parseDouble(matcher.group()));
                        if (Double.isNaN(best) || value > best) {
                            best = value;
                        }
                    }
                    catch (NumberFormatException ignored) {
                        // 解析不了就跳过，不影响别的单元格
                    }
                }
            }
        }
        return best;
    }

    /** {@code [OK]} 才算通过；用于统计「自动判定通过了几条」。 */
    public static boolean passed(String verdict) {
        return verdict.startsWith("[OK]");
    }

    /** {@code [X]} 才算明确失败。 */
    public static boolean failed(String verdict) {
        return verdict.startsWith("[X]");
    }
}
