package com.example.smartdataqa.tools.mock;

import com.example.smartdataqa.tools.DatabaseTool;
import com.example.smartdataqa.tools.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 迭代 8 · 数据库查询的 mock 实现。
 *
 * <p>不连库、不生成 SQL，就是按问题里的关键词返回一段硬编码结果。
 *
 * <h3>为什么 mock 也要返回「看起来真实的 SQL」</h3>
 * 因为 {@link QueryResult#sql()} 这个字段是 迭代 21 那条防线的落点，
 * 现在就把形状立起来，到时候接真实执行只要换掉这一行。
 *
 * <p>而且更实际的原因：<b>mock 的数据形态和真实形态一致，接真实现的时候才不会牵连一片。</b>
 * 如果 mock 图省事只返回一个数字，迭代 18 接真实 Text2SQL 时，
 * 所有下游代码都要跟着改。
 */
public class MockDatabaseTool implements DatabaseTool {

    private static final Logger log = LoggerFactory.getLogger(MockDatabaseTool.class);

    @Override
    public QueryResult query(String question) {
        log.info("      [DatabaseTool] 收到问题：\"{}\"", shorten(question));

        // 按关键词粗略判断该查什么。真实版是：表结构注入 → 生成 SQL → 执行 → 失败自修复
        if (containsAny(question, "华东", "销售额", "营收")) {
            String sql = """
                    SELECT region, SUM(order_amount - refund_amount) AS net_sales
                    FROM sales_orders
                    WHERE quarter = '2026Q2'
                      AND region = '华东'
                      AND status = 'COMPLETED'
                    GROUP BY region""";

            QueryResult r = new QueryResult(
                    sql,
                    List.of("region", "net_sales"),
                    List.of(List.of("华东", "128,450,000")),
                    1);
            log.info("      [DatabaseTool] 返回 1 行（华东 2026Q2 净销售额）");
            return r;
        }

        if (containsAny(question, "排名", "top", "最高")) {
            String sql = """
                    SELECT region, SUM(order_amount - refund_amount) AS net_sales
                    FROM sales_orders
                    WHERE quarter = '2026Q2' AND status = 'COMPLETED'
                    GROUP BY region
                    ORDER BY net_sales DESC""";

            QueryResult r = new QueryResult(
                    sql,
                    List.of("region", "net_sales"),
                    List.of(
                            List.of("华东", "128,450,000"),
                            List.of("华南", "97,320,000"),
                            List.of("华北", "85,110,000")),
                    3);
            log.info("      [DatabaseTool] 返回 3 行（各区域净销售额排名）");
            return r;
        }

        // 认不出来的问题：照样返回一个 QueryResult，但 sql 字段如实说明「没查到」
        // 注意这里【不能】编一个数字出来 —— 那正是 迭代 4 见过的「编造过程」
        String sql = "-- 未匹配到可执行的查询模板（mock 实现只认识几种问法）";
        log.warn("      [DatabaseTool] 没匹配到查询模板，返回空结果（不编造数字）");
        return QueryResult.empty(sql);
    }

    private boolean containsAny(String text, String... keys) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase();
        for (String k : keys) {
            if (t.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String shorten(String s) {
        return s == null ? "" : (s.length() <= 30 ? s : s.substring(0, 30) + "…");
    }
}
