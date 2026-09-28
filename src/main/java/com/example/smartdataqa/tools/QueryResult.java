package com.example.smartdataqa.tools;

import java.util.List;

/**
 * 数据库查询的结果。
 *
 * <p>迭代 8 先定形状，迭代 18 才会接真实的 Text2SQL。
 *
 * <h3>{@code sql} 这个字段是关键，不是可选的</h3>
 * 想象两种设计：
 * <pre>
 *   A. record QueryResult(List&lt;String&gt; columns, List&lt;List&lt;String&gt;&gt; rows)
 *   B. record QueryResult(String sql, List&lt;String&gt; columns, List&lt;List&lt;String&gt;&gt; rows, int rowCount)
 * </pre>
 * A 更简洁，但丢掉了「这个数字是怎么来的」。而本项目最需要防的风险恰恰是那个：
 * <b>模型没有真的查库，却声称「我已查询，结果是 1.28 亿」</b>
 * （迭代 4 亲眼见过模型编造「我打过电话」）。
 *
 * <p>带上 {@code sql} 之后，答案里可以附上真实执行过的语句，
 * 让「有没有真查过」变成<b>可验证</b>的，而不是听模型自述。
 * 这就是 迭代 21 要落地的那条防线，本轮先把字段留出来。
 *
 * @param sql       实际执行过的 SQL。<b>真实执行痕迹</b>，模型无权编造这个字段
 * @param columns   列名
 * @param rows      数据行，全部按字符串返回，避免类型转换的麻烦
 * @param rowCount  返回行数。<b>这个和 {@code rows.size()} 不一定相等</b>——
 *                  为了控制上下文，可能只把前 N 行喂给模型，但真实行数要如实记着
 */
public record QueryResult(String sql, List<String> columns, List<List<String>> rows, int rowCount) {

    /** 空结果（查询执行了，但没有数据）。注意 sql 仍然保留——「查了但没查到」也是执行痕迹。 */
    public static QueryResult empty(String sql) {
        return new QueryResult(sql, List.of(), List.of(), 0);
    }

    /**
     * 拼进 prompt 时的展示形式。
     *
     * <p>SQL 一并带上——不仅给人看，也<b>让模型知道这个数字的出处</b>，
     * 它引用起来会更谨慎（而不是当成自己想起来的事实）。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("【执行的 SQL】\n").append(sql).append('\n');
        sb.append("【返回 ").append(rowCount).append(" 行】\n");

        if (columns.isEmpty()) {
            return sb.toString();
        }
        sb.append(String.join(" | ", columns)).append('\n');
        for (List<String> row : rows) {
            sb.append(String.join(" | ", row)).append('\n');
        }
        if (rowCount > rows.size()) {
            sb.append("（上下文里只放了前 ").append(rows.size())
              .append(" 行，实际返回 ").append(rowCount).append(" 行）\n");
        }
        return sb.toString();
    }
}
