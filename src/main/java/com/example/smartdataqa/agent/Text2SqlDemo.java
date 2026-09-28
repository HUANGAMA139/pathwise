package com.example.smartdataqa.agent;

import com.example.smartdataqa.tools.DatabaseTool;
import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import com.example.smartdataqa.tools.text2sql.Text2SqlDatabaseTool;
import com.example.smartdataqa.tools.text2sql.Text2SqlDiagnostic;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 18 · <b>Text2SQL v1 的诊断跑</b>：把「模型会怎么写错」这件事变成看得见的东西。
 *
 * <h3>为什么叫「诊断」而不叫「评测」</h3>
 * 因为它<b>不是</b>一个指标。它是「跑 9 道题，把生成的 SQL、查出来的结果、
 * 正确答案、判定摆在一起」——目的是让人亲眼看到失败模式，不是产出 0.xx 这样的数字。
 * <b>正式的「执行准确率」是 迭代 22 的事</b>（那时用 eval/ 那套脚手架，一条命令出报告）。
 * 这个区别很重要：诊断可以有「需人工判断」这一档，指标不行。
 *
 * <h3>9 道题是照着「四类失败模式」设计的</h3>
 * <table border="1">
 *   <tr><th>失败模式</th><th>哪几道</th></tr>
 *   <tr><td rowspan="2">不知道表结构 → 编表名/字段名</td><td>D3（要 JOIN 才知道 line_code 在哪）</td></tr>
 *   <tr><td>D6（要 JOIN region_provinces）</td></tr>
 *   <tr><td rowspan="3">不知道业务含义 → SQL 合法但答案错</td>
 *       <td><b>D5（★归属期间：created_at 还是 completed_at）</b></td></tr>
 *   <tr><td>D4（状态过滤 COMPLETED/REFUNDED）</td></tr>
 *   <tr><td>D9（客单价的分母口径，在文档里不在库里）</td></tr>
 *   <tr><td>业务规则不在库里 → 该说「答不了」却硬答</td>
 *       <td><b>D7（★福建省：库里根本没有省份维度）</b></td></tr>
 *   <tr><td>表结构暗示不了「哪张表能用」</td>
 *       <td><b>D8（★orders_archive 是归档表）</b></td></tr>
 * </table>
 *
 * <p><b>期望答案全部由 {@code scripts/gen-business-seed.py} 从同一份种子数据算出</b>，
 * 不是手写的。理由很实际：7 单净销售额手算错了的话，这个 demo 会把错的当成对的判——
 * 那就成了「量具坏了但看起来正常」，迭代 15 已经栽过三次。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp18.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp18.enabled", havingValue = "true")
public class Text2SqlDemo {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlDemo.class);

    // 迭代 20：题目和判定逻辑搬到了 Text2SqlDiagnostic ——
    // 因为 迭代 20 要拿【同一批题、同一套判据】做前后对比。
    // 两边各留一份的话，改一道题的措辞就能让两个数字不可比，而且不报错。
    // 本类现在只负责「跑 + 打印」。

    @Bean
    ApplicationRunner exp18Text2SqlDemo(BusinessDb businessDb,
                                        ChatClient.Builder chatClientBuilder,
                                        DatabaseTool wiredDatabaseTool,
                                        @Value("${sdaq.database.mode:text2sql}") String databaseMode,
                                        @Value("${sdaq.text2sql.max-context-rows:50}") int maxContextRows) {

        return args -> {
            JdbcTemplate businessJdbc = businessDb.jdbc();
            SchemaRenderer schemaRenderer = new SchemaRenderer(businessJdbc);
            Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(
                    businessJdbc, chatClientBuilder.build(), schemaRenderer, maxContextRows);

            log.info("========== 迭代 18 · Text2SQL v1 诊断 ==========");
            log.info("链路：问题 → 注入表结构 → 模型生成 SQL → 执行 → QueryResult（带真实 SQL）");
            log.info("主链路的 DatabaseTool 实际是：{}（sdaq.database.mode={}）",
                    wiredDatabaseTool.getClass().getSimpleName(), databaseMode);
            log.info("诊断题数：{}", Text2SqlDiagnostic.CASES.size());
            log.info("");

            // ---------- 本轮交付物的正面：注入 prompt 的东西长什么样 ----------
            log.info("================= 本轮注入给模型的表结构 =================");
            String schema;
            try {
                schema = schemaRenderer.render();
                for (String line : schema.split("\n")) {
                    log.info("| {}", line);
                }
            }
            catch (RuntimeException e) {
                log.error("读不到表结构 —— 业务库大概率没起。先执行：");
                log.error("  docker compose up -d business-db");
                log.error("  首次初始化才会执行 docker/init-business-db.sql；若数据卷已存在，");
                log.error("  见 docs/18-Text2SQL原理与Schema注入.md 第二节的重建步骤。");
                log.error("原始错误：{}", e.getMessage());
                return;
            }

            // ---------- 逐题诊断 ----------
            List<Outcome> outcomes = new ArrayList<>();
            for (int i = 0; i < Text2SqlDiagnostic.CASES.size(); i++) {
                Text2SqlDiagnostic.Case c = Text2SqlDiagnostic.CASES.get(i);
                log.info("");
                log.info("################ {} / {}  诊断 {} ################", i + 1, Text2SqlDiagnostic.CASES.size(), c.id());
                log.info("问：{}", c.question());
                log.info("探针：{}", c.probes());

                QueryResult result;
                try {
                    result = tool.query(c.question());
                }
                catch (RuntimeException e) {
                    log.error("  [!] 工具本身抛了异常（不属于设计内的路径）：{}", e.toString());
                    outcomes.add(new Outcome(c, null, "[X] 工具异常"));
                    continue;
                }

                String verdict = Text2SqlDiagnostic.judge(c, result);
                outcomes.add(new Outcome(c, result, verdict));

                log.info("");
                log.info("  ---------- 生成的 SQL / 结果 ----------");
                for (String line : result.render().split("\n")) {
                    log.info("  {}", line);
                }
                log.info("  ---------- 期望 ----------");
                log.info("  {}", c.expectedText());
                log.info("  ---------- 判定 ----------");
                log.info("  {}", verdict);
            }

            printSummary(outcomes);
            printConclusion();
        };
    }

    private record Outcome(Text2SqlDiagnostic.Case c, QueryResult result, String verdict) {
    }

    private void printSummary(List<Outcome> outcomes) {
        log.info("");
        log.info("================= 诊断汇总 =================");
        log.info("{} {} {} {}",
                ConsoleText.padRight("题", 5),
                ConsoleText.padRight("问题", 36),
                ConsoleText.padRight("行数", 6),
                "判定");

        int ok = 0;
        int bad = 0;
        int manual = 0;
        for (Outcome o : outcomes) {
            String verdict = o.verdict();
            if (Text2SqlDiagnostic.passed(verdict)) {
                ok++;
            }
            else if (Text2SqlDiagnostic.failed(verdict)) {
                bad++;
            }
            else {
                manual++;
            }
            log.info("{} {} {} {}",
                    ConsoleText.padRight(o.c().id(), 5),
                    ConsoleText.padRight(ConsoleText.shorten(o.c().question(), 34), 36),
                    ConsoleText.padRight(o.result() == null ? "-" : String.valueOf(o.result().rowCount()), 6),
                    verdict);
        }
        log.info("");
        log.info("自动判定通过 {} ／ 明确失败 {} ／ 需人工判断 {}", ok, bad, manual);
        log.info("（这不是准确率。诊断可以有人工档，指标不能 —— 正式的执行准确率见 迭代 22）");
    }

    private void printConclusion() {
        log.info("");
        log.info("================= 本轮得到了什么 =================");
        log.info("1. DatabaseTool 从 mock 换成了真的：表结构注入 → 生成 SQL → 执行 →");
        log.info("   返回带【真实 SQL】的 QueryResult。接口一行没改 ——");
        log.info("   这是 迭代 8 定下的契约第二次兑现（第一次是 迭代 14 换向量检索）。");
        log.info("");
        log.info("2. 逐题看下来，失败要按【响不响】分，才看得出危险在哪：");
        log.info("   · 会响的错：表名/字段名/语法写错 -> 数据库直接报错，一眼看见。");
        log.info("   · 不响的错：SQL 合法、有结果、但答案是错的。第一次实跑见到的四种形态：");
        log.info("     (1) 用错列：该 completed_at 却用了 created_at");
        log.info("     (2) 取值猜错：库里是 '华东'，模型写成 '华东区' -> 返回 0 行");
        log.info("     (3) 用错表：该查 orders_archive 却只查 sales_orders -> 返回 0 行");
        log.info("     (4) 函数语义：AVG 作用在 0 行上返回的是【1 行 NULL】，不是 0 行");
        log.info("   ★ 特别当心「返回 0 行」这一档：它既可能是真的没数据，");
        log.info("     也可能是取值或表选错了。这一档必须人工看，不能当成「查不到」。");
        log.info("");
        log.info("3. 所以表结构注入只覆盖了第一层。完整的四层是：");
        log.info("   (1) 表结构：有哪些表/列/外键       -> 本轮注入了，解决了");
        log.info("   (2) 值域  ：列里到底存了哪些取值   -> 本轮【没】注入，是本次最大的失分点");
        log.info("   (3) 业务语义：该用哪一列、哪张表能用于统计 -> 在文档里，不在库里");
        log.info("   (4) 方言与执行 -> 数据库会自己报错，最好办");
        log.info("   ★ (2)(3) 都不在库里。这就是「双支路 + 路由」存在的理由，");
        log.info("     「值域」请去数据字典/样例行拿，「业务语义」请去文档拿。");
        log.info("");
        log.info("4. 本轮是故意【不】修的：");
        log.info("   · 表多了 prompt 会爆 -> 表筛选（迭代 19，复用 迭代 16 的混合检索）");
        log.info("   · few-shot 样例（正好用来补上面第 (2) 条的值域）+ 报错回灌自修复（迭代 20）");
        log.info("   · 完整的 SQL 安全边界（迭代 21；本轮只有「只跑 SELECT/WITH」一条最小护栏）");
        log.info("   · 执行准确率评测（迭代 22，用 eval/ 那套脚手架，一条命令出报告）");
        log.info("   先看清楚错在哪，再决定怎么修 —— 顺序反了就是在猜。");
        log.info("");
        log.info("5. 顺手做完的一件事：业务支路的标准答案重新标注了。");
        log.info("   迭代 15 那份 testset 里 B01~B04 / X01~X04 的期望答案是 mock 时期的编造值");
        log.info("   （128,450,000 那批），接真实库后已按种子数据重算 —— 见 eval/testset.csv 的 note 列。");
        log.info("");
        log.info("6. 本次是哪几条错、错在哪，逐题记录在");
        log.info("   docs/18-Text2SQL原理与Schema注入.md 第六节 —— 那一节才是本轮的产出。");
    }
}
