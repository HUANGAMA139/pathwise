package com.example.smartdataqa.util;

import com.example.smartdataqa.tools.QueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 23 · <b>数值量具</b>：从自然语言答案或查询结果里抽出「金额量级」的数字，做容差比对。
 *
 * <h3>为什么它必须只有一个副本</h3>
 * 「执行准确率 = 期望答案里的数字能不能在真实结果里找到」这条判据，
 * 迭代 22 的支路评测要用，迭代 23 的整份报告也要用。
 * <b>两份各写一份，迟早写岔——而它是个量具，漂移了不会报错，只会安静地给出一个错的准确率。</b>
 * （和 {@code DocumentChunks}（迭代 17）、{@code Vectors}（迭代 19）是同一条理由。）
 *
 * <h3>抽取规则：&ge;5 位的数字，或带千分位的数字</h3>
 * 期望答案是自然语言（{@code "36,100,000（2026Q2 …，7 单计入）"}），里面混着年份、题号、单数。
 * 只取金额量级的数，才不会把 2026 / Q2 / 19 / 7 也当成期望值。
 *
 * <p><b>★ 边界那两个 lookaround 是 迭代 22 第一次实跑抓出来的 bug。</b>
 * 原来的写法 {@code \d{1,3}(?:,\d{3})+|\d{5,}} 没有边界约束——一个小数单元格
 * {@code 5708333.333333333333} 会被抽成<b>两个</b>数（{@code 5,708,333} 和 {@code 333,333,333,333}，
 * 小数点后面那串 3 被当成了大数字）。{@code (?<![\d.])} 挡住「前面是小数点或数字」的位置，
 * {@code (?!\d)} 挡住「后面还接着数字」。★ 它当时没造成错误判定，但<b>一个会凭空造数字的量具必须修</b>。
 *
 * <p><b>抽取规则本身也可能错</b>——所以用它的人（迭代 22/23）都会把抽出来的数字打进日志，让人能核对。
 */
public final class Amounts {

    private Amounts() {
    }

    private static final Pattern BIG_NUMBER =
            Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{3})+|\\d{5,})(?!\\d)");

    /** 从任意文本里抽金额量级的数字（去千分位、转 double）。 */
    public static List<Double> inText(String text) {
        List<Double> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = BIG_NUMBER.matcher(text);
        while (m.find()) {
            try {
                out.add(Double.parseDouble(m.group().replace(",", "")));
            }
            catch (NumberFormatException ignored) {
                // 解析不了就跳过，不影响别的
            }
        }
        return out;
    }

    /**
     * 只从<b>数据行</b>里抽数字，不动 {@code render()} 里的 SQL 正文。
     *
     * <p>为什么强调这点：{@code render()} 带着那条 SQL，万一句子里出现数字常量，
     * 就会被误当成结果（迭代 21「量具要和真实行为一致」的同一个道理）。
     */
    public static List<Double> inResult(QueryResult r) {
        List<Double> out = new ArrayList<>();
        if (r == null) {
            return out;
        }
        for (List<String> row : r.rows()) {
            for (String cell : row) {
                out.addAll(inText(cell));
            }
        }
        return out;
    }

    /** {@code want} 里每个数都能在 {@code got} 里找到（±tol 相对容差）。 */
    public static boolean containsAll(List<Double> want, List<Double> got, double tol) {
        for (double w : want) {
            boolean hit = false;
            for (double g : got) {
                double denom = Math.abs(w) < 1e-9 ? 1.0 : Math.abs(w);
                if (Math.abs(g - w) / denom <= tol) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    /** 把数字列表打成一行，给人核对用。 */
    public static String format(List<Double> nums) {
        if (nums == null || nums.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (double v : nums) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%,.0f", v));
        }
        return sb.toString();
    }
}
