package com.example.smartdataqa.agent;

import com.example.smartdataqa.eval.Csv;
import com.example.smartdataqa.tools.QueryResult;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.HybridTableSelector;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import com.example.smartdataqa.tools.text2sql.TableSelector;
import com.example.smartdataqa.tools.text2sql.Text2SqlDatabaseTool;
import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 迭代 19 · <b>表筛选的对比实验</b>：20 张表的库，4 种模式，量「选得准不准」和「省了多少 prompt」。
 *
 * <h3>这是个「有明确对错」的实验，所以能自动判</h3>
 * 每道题都标注了<b>必需表</b>（不查它就算不出正确答案）。于是可以算：
 * <pre>
 *   hit      必需表是否全被选中（全中才算过）—— 这是本轮唯一不能退让的指标
 *   recall   必需表被选中的比例
 *   precision 选中的表里有多少是必需的 —— 注意它天生长，见下
 *   prompt   注入的表结构字符数（和 none 比就是省下的）
 * </pre>
 *
 * <p><b>precision 不能当优化目标</b>：top-K 是刻意开宽的（选 8 张只为了答 1 张的题），
 * 所以精确率天然很低。它在这儿只用来发现「选了一大堆明显不相关的表」这种情况。
 * 真正该看的是 <b>hit</b>（别漏）和 <b>prompt</b>（省了多少）。
 *
 * <h3>为什么必须做单路对照</h3>
 * 和 迭代 16/17 一个道理：只报一个「hybrid 多少分」，就分不清是<b>方法</b>好还是
 * <b>BM25 本来就够用</b>。所以 modes 里 bm25 / vector 两路要单独跑。
 *
 * <h3>还要做一件事：K 扫描</h3>
 * 「选 8 张够不够」和「选 3 张够不够」是不同的问题。扫一遍 K，
 * 才能说出「从第几张开始召回已经饱和」——那是挑 top-K 默认值的依据。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp19.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp19.enabled", havingValue = "true")
public class TableSelectionDemo {

    private static final Logger log = LoggerFactory.getLogger(TableSelectionDemo.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 一道题 + 它的必需表。
     *
     * <p><b>「必需」的定义</b>：不查这张表就算不出正确答案。可选的表（比如只为了显示名字的
     * 维度表）不算必需——否则会把标准放宽，指标就没有意义了。
     */
    private record Case(String id, String question, List<String> required) {
    }

    /** 20 道题，覆盖全部 20 张表（demo 末了会自检覆盖率）。 */
    private static final List<Case> CASES = List.of(
            new Case("T01", "2026年第二季度华东区的销售额是多少？", List.of("sales_orders")),        // 基准题
            new Case("T02", "2026年第二季度 XR 产品线的销售额是多少？", List.of("sales_orders", "products")),  // 要 JOIN
            new Case("T03", "华东区都包含哪些省份？", List.of("region_provinces")),                 // 维度表
            new Case("T04", "公司一共划分了哪几个销售区域？", List.of("regions")),                   // 维度表
            new Case("T05", "2024 年华东区的销售额是多少？", List.of("orders_archive")),            // 历史期 -> 归档表
            new Case("T06", "2026年第二季度发货了多少单？", List.of("shipments")),                  // 另一条业务线
            new Case("T07", "哪个客户的发货单最多？", List.of("shipments", "customers")),           // 两表
            new Case("T08", "2026年第二季度订单明细的成交金额合计是多少？", List.of("order_items")),   // 强干扰：明细里也有金额
            new Case("T09", "公司今年向供应商采购了多少金额？", List.of("purchase_orders")),          // 强干扰：采购也叫订单
            new Case("T10", "2026年第二季度一共开了多少张发票？", List.of("invoices")),              // 另一条业务线
            new Case("T11", "退货申请都审核通过了吗？", List.of("returns")),                        // 语料 03 对应
            new Case("T12", "客户的回款情况怎么样？", List.of("payments")),                         // 销售额不等于收到的钱
            new Case("T13", "XR-2000B 的标准价格改过几次？", List.of("product_price_history")),     // 精确型号
            new Case("T14", "各个销售区域有多少员工？", List.of("employees")),                       // 组织维度
            new Case("T15", "现在各个仓库还有多少库存？", List.of("inventory")),                     // 快照表
            new Case("T16", "各供应商的采购金额排名是怎样的？", List.of("purchase_orders", "suppliers")),  // 两表
            new Case("T17", "产品一共分了哪些类？", List.of("product_categories")),                  // 维度表
            new Case("T18", "公司有哪些销售渠道？", List.of("channels")),                            // 维度表
            new Case("T19", "各个仓库的库存合计是多少？", List.of("inventory", "warehouses")),        // 两表
            new Case("T20", "采购明细里每种产品一共采购了多少数量？", List.of("purchase_order_items"))  // 明细表
    );

    /** K 扫描用的取值。 */
    private static final int[] K_SWEEP = {1, 2, 3, 5, 8, 12};

    @Bean
    ApplicationRunner exp19TableSelection(BusinessDb businessDb,
                                          ChatClient.Builder chatClientBuilder,
                                          EmbeddingModel embeddingModel,
                                          @Value("${sdaq.table-select.top-k:8}") int topK,
                                          @Value("${sdaq.table-select.candidates:10}") int candidates,
                                          @Value("${sdaq.table-select.rrf-k:30}") int rrfK,
                                          @Value("${sdaq.eval.table-select-metrics}") String metricsPath) {

        return args -> {
            JdbcTemplate jdbc = businessDb.jdbc();
            SchemaRenderer renderer = new SchemaRenderer(jdbc);
            HybridTableSelector selector = new HybridTableSelector(
                    renderer::catalog, embeddingModel, candidates, rrfK);

            log.info("========== 迭代 19 · 表筛选（复用 迭代 16 的混合检索） ==========");
            log.info("库里的表：{} 张；问题数：{}；主 top-K：{}；K 扫描：{}",
                    renderer.tableNames().size(), CASES.size(), topK, java.util.Arrays.toString(K_SWEEP));
            log.info("检索单位 = 表目录条目（一表一条）；融合用 RRF；Bm25Index / RrfDocumentJoiner 一行没改");

            // ---------- 把「检索单位」摆出来看 ----------
            log.info("");
            log.info("================= 检索用的表目录（这就是被检索的东西） =================");
            List<Document> catalog = renderer.catalog();
            for (Document d : catalog) {
                log.info("| {}", d.getText().replace("\n", "  "));
            }

            // ---------- 选表：四种模式 ----------
            List<Arm> arms = List.of(
                    new Arm("none", (q, k) -> renderer.tableNames()),
                    new Arm("bm25", selector::selectBm25),
                    new Arm("vector", selector::selectVector),
                    new Arm("hybrid", selector));

            String runStamp = LocalDateTime.now().format(STAMP);
            List<Row> rows = new ArrayList<>();

            for (Arm arm : arms) {
                for (Case c : CASES) {
                    List<String> selected = arm.selector().select(c.question(), topK);
                    // 【注入字符数必须按「实际会注入什么」算，不能直接 render(selected)】。
                    // 因为选空集时 Text2SqlDatabaseTool 会**回退成全量注入**（安全阀，见 schemaFor）。
                    // 如果这里直接 render(空集)，算出来是「（筛选后没有可用的表）」那 11 个字符，
                    // 于是「省了多少 prompt」被系统性地高估 —— 量具和真实行为不一致。
                    // ★ 这正是 迭代 21 那条教训的同一个形状：指标测的东西必须和系统的真实行为一致。
                    boolean fellBack = selected.isEmpty();
                    Set<String> effective = fellBack
                            ? new LinkedHashSet<>(renderer.tableNames())
                            : new LinkedHashSet<>(selected);
                    rows.add(new Row(runStamp, c, arm.name(), topK, selected,
                            renderer.render(effective).length(), fellBack));
                }
            }

            // ---------- 汇总 ----------
            log.info("");
            log.info("================= 四种模式对比（top-K = {}） =================", topK);
            log.info("{} {} {} {} {} {}",
                    ConsoleText.padRight("模式", 8),
                    ConsoleText.padRight("全中(hit)", 11),
                    ConsoleText.padRight("平均召回", 10),
                    ConsoleText.padRight("平均精确", 10),
                    ConsoleText.padRight("平均注入字符", 14),
                    "说明");

            int allTablesChars = renderer.render().length();
            for (Arm arm : arms) {
                List<Row> armRows = rows.stream().filter(r -> r.mode().equals(arm.name())).toList();
                int hits = (int) armRows.stream().filter(Row::hit).count();
                double recall = armRows.stream().mapToDouble(Row::recall).average().orElse(0);
                double precision = armRows.stream().mapToDouble(Row::precision).average().orElse(0);
                double chars = armRows.stream().mapToInt(Row::schemaChars).average().orElse(0);
                long fellBack = armRows.stream().filter(Row::fellBack).count();
                String note;
                if (arm.name().equals("none")) {
                    note = String.format("全量注入 %d 字符（迭代 18 的形态）", Math.round(chars));
                }
                else if (fellBack > 0) {
                    // 选空集时工具会回退全量 —— 所以这一档的「省」要打折看
                    note = String.format("省了 %.0f%%；其中 %d 道选空 → 回退全量",
                            100.0 * (1 - chars / allTablesChars), fellBack);
                }
                else {
                    note = String.format("省了 %.0f%% 的 prompt", 100.0 * (1 - chars / allTablesChars));
                }
                log.info("{} {} {} {} {} {}",
                        ConsoleText.padRight(arm.name(), 8),
                        ConsoleText.padRight(hits + "/" + armRows.size(), 11),
                        ConsoleText.padRight(String.format("%.3f", recall), 10),
                        ConsoleText.padRight(String.format("%.3f", precision), 10),
                        ConsoleText.padRight(String.format("%.0f", chars), 14),
                        note);
            }

            // ---------- 逐题看 hybrid，错在哪 ----------
            log.info("");
            log.info("================= 逐题看 hybrid（指出漏选） =================");
            for (Row r : rows) {
                if (!r.mode().equals("hybrid")) {
                    continue;
                }
                List<String> missed = r.case_().required().stream()
                        .filter(t -> !r.selected().contains(t)).toList();
                log.info("{} {} {} {}",
                        ConsoleText.padRight(r.case_().id(), 5),
                        r.hit() ? "[OK]    " : "[漏选]  ",
                        ConsoleText.padRight(ConsoleText.shorten(r.case_().question(), 34), 36),
                        missed.isEmpty() ? "" : "漏了 " + missed + "（选中的是 " + r.selected() + "）");
            }

            // ---------- K 扫描 ----------
            log.info("");
            log.info("================= K 扫描：选几张才够 =================");
            log.info("{} {} {} {}",
                    ConsoleText.padRight("K", 5),
                    ConsoleText.padRight("bm25 全中", 11),
                    ConsoleText.padRight("vector 全中", 11),
                    "hybrid 全中");
            for (int k : K_SWEEP) {
                int[] hits = new int[3];
                TableSelector[] three = {selector::selectBm25, selector::selectVector, selector};
                for (int i = 0; i < three.length; i++) {
                    for (Case c : CASES) {
                        if (three[i].select(c.question(), k).containsAll(c.required())) {
                            hits[i]++;
                        }
                    }
                }
                log.info("{} {} {} {}",
                        ConsoleText.padRight(String.valueOf(k), 5),
                        ConsoleText.padRight(hits[0] + "/" + CASES.size(), 11),
                        ConsoleText.padRight(hits[1] + "/" + CASES.size(), 11),
                        hits[2] + "/" + CASES.size());
            }

            // ---------- 覆盖率自检（两个方向都要查） ----------
            log.info("");
            Set<String> requiredUnion = new LinkedHashSet<>();
            for (Case c : CASES) {
                requiredUnion.addAll(c.required());
            }
            // 方向 1：题目要的表，库里到底有没有？
            //   【这个方向才是关键的】—— 库没扩表时，题目要求 20 张、库里只有 5 张，
            //   于是所有 hit 都会失败，但那个 0 分【不能解释成「筛选不行」】，
            //   只能解释成「库还是老的」。不加这条检查，跑出来的就是一个误导性的数字。
            Set<String> missingInDb = new LinkedHashSet<>(requiredUnion);
            missingInDb.removeAll(renderer.tableNames());
            // 方向 2：库里的表，有没有一道题都没要求过的？（测试集覆盖度）
            Set<String> neverRequired = new LinkedHashSet<>(renderer.tableNames());
            neverRequired.removeAll(requiredUnion);

            log.info("覆盖率自检（两个方向）：库里 {} 张表，{} 道题一共要求了 {} 张",
                    renderer.tableNames().size(), CASES.size(), requiredUnion.size());
            if (missingInDb.isEmpty()) {
                log.info("  [OK] 题目要求的表全都在库里");
            }
            else {
                log.warn("  [X] 有 {} 张【题目要求、但库里不存在】的表：{}", missingInDb.size(), missingInDb);
                log.warn("      -> 这个库还没扩表。**下面的 hit 数字不可解释**（不是「筛选不行」，是「库是旧的」）。");
                log.warn("         先灌扩表脚本（PowerShell 注意：不能用 < 重定向）：");
                log.warn("           docker compose cp docker/init-business-db-extra.sql business-db:/tmp/extra.sql");
                log.warn("           docker compose exec business-db psql -U sdaq -d business_db -f /tmp/extra.sql");
            }
            log.info("  {}", neverRequired.isEmpty()
                    ? "[OK] 库里每张表都至少被一道题要求过"
                    : "[提示] 从未被任何题要求过的表：" + neverRequired);

            // ---------- 端到端：筛选真的接进链路了吗 ----------
            log.info("");
            log.info("================= 端到端：筛选接进 DatabaseTool 之后跑一题 =================");
            Text2SqlDatabaseTool tool = new Text2SqlDatabaseTool(
                    jdbc, chatClientBuilder.build(), renderer, 50, selector, topK);
            Case demo = CASES.get(1);   // T02：要 sales_orders + products
            log.info("问：{}", demo.question());
            log.info("选中的表：{}", selector.select(demo.question(), topK));
            try {
                QueryResult result = tool.query(demo.question());
                for (String line : result.render().split("\n")) {
                    log.info("  {}", line);
                }
            }
            catch (RuntimeException e) {
                log.error("  [!] 端到端这一跑挂了：{}", e.toString());
            }

            writeCsv(Path.of(metricsPath), rows);
            printConclusion(allTablesChars);
        };
    }

    /** 一种模式 + 它的选表方式。 */
    private record Arm(String name, TableSelector selector) {
    }

    /** 一行结果（也就是 CSV 的一行）。 */
    private record Row(String run, Case case_, String mode, int topK, List<String> selected,
                       int schemaChars, boolean fellBack) {

        boolean hit() {
            return selected.containsAll(case_.required());
        }

        double recall() {
            if (case_.required().isEmpty()) {
                return 1.0;
            }
            long found = case_.required().stream().filter(selected::contains).count();
            return (double) found / case_.required().size();
        }

        double precision() {
            if (selected.isEmpty()) {
                return 0.0;
            }
            long good = selected.stream().filter(case_.required()::contains).count();
            return (double) good / selected.size();
        }
    }

    private static final List<String> CSV_HEADER = List.of(
            "run", "case_id", "question", "mode", "top_k",
            "required", "selected", "hit", "recall", "precision",
            "schema_chars", "fell_back_to_full");

    private void writeCsv(Path path, List<Row> rows) {
        try {
            for (Row r : rows) {
                Csv.appendRow(path, CSV_HEADER, List.of(
                        r.run(),
                        r.case_().id(),
                        r.case_().question(),
                        r.mode(),
                        String.valueOf(r.topK()),
                        String.join("|", r.case_().required()),
                        String.join("|", r.selected()),
                        r.hit() ? "1" : "0",
                        String.format("%.3f", r.recall()),
                        String.format("%.3f", r.precision()),
                        String.valueOf(r.schemaChars()),
                        r.fellBack() ? "1" : "0"));
            }
            log.info("结果已追加到 {}", path.toAbsolutePath());
        }
        catch (Exception e) {
            log.warn("写 {} 失败：{}", path, e.toString());
        }
    }

    /**
     * 结语。
     *
     * <p><b>刻意不写预测</b>：迭代 18 的结语里我事先断言了失败模式，结果被实跑直接推翻。
     * 所以这里只说<b>方法和判据</b>，具体数字留给日志上方的表和 docs 里那一节。
     */
    private void printConclusion(int allTablesChars) {
        log.info("");
        log.info("================= 本轮得到了什么 =================");
        log.info("1. 表筛选接进了 DatabaseTool：先按问题召回几张表，再只把这几张的结构注入 prompt。");
        log.info("   开关 sdaq.table-select.mode = none | bm25 | vector | hybrid；");
        log.info("   none 就是 迭代 18 的形态（全量注入 {} 字符），随时可复现。", allTablesChars);
        log.info("");
        log.info("2. 最要紧的一句：这活儿 迭代 16 已经干过一遍了。");
        log.info("   Bm25Index 和 RrfDocumentJoiner 两个类，本轮【一行都没改】。");
        log.info("   变的只有「被检索的东西」：文档片段 -> 表目录条目。");
        log.info("   能复用不是因为巧合，是因为当初它们只要求输入是「有 id、有正文、能排序的东西」。");
        log.info("");
        log.info("3. 判据只有两条，别被别的数字带跑：");
        log.info("   · hit（必需表全中）—— 这是唯一不能退让的；漏一张表就等于那道题必错。");
        log.info("   · prompt 字符数 —— 省了多少。但注意：省 token 在本轮这个规模【还不是瓶颈】，");
        log.info("     20 张表全量注入也就 {} 字符。表筛选的真正价值要到几百张表才兑现。", allTablesChars);
        log.info("   precision 天生长不高（top-K 是刻意开宽的），别拿它当优化目标。");
        log.info("");
        log.info("4. 本轮【不】解决的事：");
        log.info("   · 召回不到的表，注入不进去 —— 筛选只会把「本来就没选对」的错误固化下来，");
        log.info("     不会把它变对。所以 T05 那道题值得单独看（历史期该走归档表，而目录里没有任何词能提示这件事）。");
        log.info("   · few-shot 补值域、报错回灌自修复，都还是 迭代 20 的事。");
        log.info("");
        log.info("5. 结果写进了 {}（每行一道题 × 一种模式）。", "eval/table-select-compare.csv");
        log.info("   逐题明细和结论写在 docs/19-表筛选复用混合检索.md。");
    }
}
