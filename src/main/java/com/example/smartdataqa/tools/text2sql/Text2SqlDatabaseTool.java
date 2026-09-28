package com.example.smartdataqa.tools.text2sql;

import com.example.smartdataqa.tools.DatabaseTool;
import com.example.smartdataqa.tools.QueryResult;
import org.postgresql.jdbc.PgResultSetMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 18 · <b>Text2SQL v1</b>：把自然语言问题翻译成 SQL 并执行。
 *
 * <p>它替换掉 迭代 8 的 {@code MockDatabaseTool}——接口 {@link DatabaseTool} 一个字没改，
 * Agent 主干、路由、MCP 的代码都不知道底下换了。<b>这是 迭代 8 那个契约第二次兑现。</b>
 *
 * <h3>v1 的三步，就是本轮要体会的那三件事</h3>
 * <pre>
 *   1. 注入表结构   →  模型不知道你的表结构        （本轮解决）
 *   2. 生成 SQL     →  模型不知道你的业务含义      （本轮【不】解决，这是重点）
 *   3. 执行并返回   →  模型容易写错                （本轮先如实暴露，不自修复）
 * </pre>
 *
 * <h3>v1 故意【不】做的事（每一天的边界，写在代码里免得自己以后忘）</h3>
 * <table border="1">
 *   <tr><th>没做</th><th>为什么不做</th><th>什么时候做</th></tr>
 *   <tr><td>表筛选（表多了 prompt 会爆）</td>
 *       <td><b>迭代 19 已做</b>：见 {@link TableSelector}，开关 {@code sdaq.table-select.mode}</td>
 *       <td>已完成</td></tr>
 *   <tr><td>few-shot 样例</td><td>要先有 v1 的错误数据，才知道该给什么样例</td><td>迭代 20</td></tr>
 *   <tr><td>错误自修复（把报错回灌给模型让它改）</td>
 *       <td><b>本轮就是要看见错误</b>——一上来就自动修，就体会不到「模型有多容易写错」</td><td>迭代 20</td></tr>
 *   <tr><td>完整 SQL 安全边界</td>
 *       <td><b>迭代 21 已做</b>：见 {@link SqlGuard}（准入）与 {@link ColumnMasker}（出参）</td>
 *       <td>已完成</td></tr>
 *   <tr><td>执行准确率评测</td><td>诊断跑一遍和「一条命令出报告」是两件事</td><td>迭代 22</td></tr>
 * </table>
 *
 * <h3>执行前那道门（迭代 21 起）</h3>
 * 从 迭代 18 的「一句话护栏」升级成了 {@link SqlGuard}：白名单 + 禁 DDL/DML + 拒多语句 +
 * 拒 {@code SELECT INTO} / {@code FOR UPDATE} + 危险函数黑名单。
 * <b>而且它不允许为 null</b>（见 {@code Options} 的紧凑构造器）——
 * 安全项能被一个 null 关掉的话，它迟早会被某条新的构造路径悄悄关掉，而且不报错。
 *
 * <p>出参那侧另有一层 {@link ColumnMasker}：一条合法的只读查询照样可能泄露个人信息，
 * 准入层拦不住它，也不该拦。
 *
 * <p>行数与超时则在 {@code BusinessDb} 上配（JDBC 层截断才算真的防内存）——
 * 见 {@code sdaq.business-db.max-rows} 与 {@code query-timeout-seconds}。
 */
public class Text2SqlDatabaseTool implements DatabaseTool {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlDatabaseTool.class);

    /**
     * 给模型的指令。
     *
     * <p>三条要求都不是客套话，各自对应一个本轮的失败模式：
     * <ol>
     *   <li><b>只输出一条 SQL、不要 markdown</b>——模型很爱回一句「好的，以下是查询：」再包个代码块，
     *       那句中文和那对反引号会被当成 SQL 发出去，直接语法错误。</li>
     *   <li><b>只能用给定的表和字段</b>——这是「模型不知道你的表结构」的正面约束。
     *       注意：约束了它照样会编，这条只是把「编」变成可控的失败（编出来的列名会让数据库报错）。</li>
     *   <li><b>答不了就用注释说明原因</b>——给它一个说「我不会」的出口。
     *       这一条很重要：不给出口，模型必须给一个答案，它就会硬编一个看起来合理的数字出去
     *       （迭代 4 见过模型编造「我打过电话」，迭代 8 见过误判产生的假数字）。
     *       有了出口之后它用不用，正好是本轮要看的一件事。</li>
     * </ol>
     */
    private static final String SYSTEM_PROMPT = """
            你是一个 PostgreSQL 专家，负责把用户的业务问题翻译成一条 SQL 查询语句。

            输出要求：
            1. 只输出 SQL 本身。不要输出解释、不要输出 markdown 代码块、不要输出反问。
            2. 只能使用下面给出的表和字段。不要臆造表名、字段名或取值。
            3. 统计类问题注意聚合、分组、排序，以及 NULL 的处理。
            4. 如果这个问题用现有的表和字段【无法回答】，就只输出一行以 -- 开头的注释说明原因，
               不要勉强写一条查不出正确答案的 SQL，更不要编一个数字。
            """;

    /**
     * 迭代 20 · 修正用的系统提示词。
     *
     * <p>第 2 条是这整件事的关键，也是最容易被做歪的地方：
     * <b>模型很容易把「修错」做成「换一个问题来答」</b>——
     * 比如原 SQL 因为列名写错而报错，它干脆改成查另一张表，报错没了，答案也变了。
     * 那种「修好了」是最坏的：<b>报错消失，错误留下</b>。
     * 所以这里明确要求「只修导致报错的地方，不要改变查询意图」。
     *
     * <p>第 4 条同样重要：如果报错本身就说明「这个库答不了这个问题」，
     * 那正确的修法是<b>承认答不了</b>，而不是绕出一条能跑通的 SQL。
     * （迭代 18 的 D7 就是这么栽的：模型写了个口径错误的 JOIN，报错没了、答案是错的。）
     */
    private static final String REPAIR_SYSTEM_PROMPT = """
            你是一个 PostgreSQL 专家。你上一次写的 SQL 执行时报错了，现在需要你修正它。

            要求：
            1. 只输出修正后的 SQL。不要输出解释、不要输出 markdown 代码块。
            2. 【只修导致报错的地方】——表名、字段名、类型、函数用法。
               不要改变查询的意图、统计口径和筛选条件。
            3. 只能使用下面给出的表和字段，不要臆造。
            4. 如果这个报错说明该问题用现有表和字段【无法回答】，
               就只输出一行以 -- 开头的注释说明原因，不要绕出一条查不出正确答案的 SQL。
            """;

    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final SchemaRenderer schemaRenderer;

    /** 修复尝试里的一次失败（失败的 SQL + 数据库报错）。 */
    private record FailedAttempt(String sql, String error) {
    }

    /**
     * 迭代 20 · <b>调试用故障注入</b>：把第一次生成的 SQL 做一次字符串替换。
     *
     * <p>它存在的唯一理由是<b>验收</b>——「把报错回灌给模型，它能自己改对吗」这件事
     * 不能靠等模型偶然写错来验证，得能<b>稳定地制造一个错</b>。
     * 默认 {@code null}（关闭），只有 迭代 20 的 demo 会传它。
     *
     * <p>顺带一句：迭代 21 要验「故意让它生成 {@code DROP TABLE}，安全边界拦不拦得住」，
     * 用的是同一个口子——<b>故障注入是自修复测试和安全测试的共同工具</b>。
     */
    public record SqlFault(String from, String to) {

        /** 把 SQL 里第一处 {@code from} 换成 {@code to}（按字面量匹配，不当正则用）。 */
        public String apply(String sql) {
            if (sql == null || sql.isEmpty() || from == null || from.isEmpty()) {
                return sql;
            }
            return sql.replaceFirst(Pattern.quote(from),
                    Matcher.quoteReplacement(to == null ? "" : to));
        }

        public String describe() {
            return from + " -> " + to;
        }
    }

    /**
     * 迭代 20 · 这个工具的全部可调项。
     *
     * <p><b>为什么现在才抽出来：</b>参数到本轮就 10 个了（3 个依赖 + 7 个旋钮）。
     * 十个位置参数没人读得懂，而且<b>加错一个不会报错</b>（类型一样就编过了）——
     * 这正是「参数多到一定程度就该收进一个具名对象」那条规矩的临界点。
     *
     * @param maxContextRows 放进 {@link QueryResult#rows()} 的最大行数（防上下文爆）
     * @param maxRows        <b>执行上限</b>：模型这条 SQL 最多返回多少行，{@code <= 0} = 不限。
     *                       用 {@code PreparedStatement.setMaxRows} 在<b>语句</b>这一层截断，
     *                       <b>不能设在连接层</b>——实跑证明那样会把元数据查询一起截断，
     *                       让注入给模型的表结构悄悄缺列（见 {@code BusinessDb} 类注释）
     * @param tableSelector  表筛选器；null = 不筛（迭代 18 的形态）
     * @param tableTopK      最多注入几张表的结构
     * @param fewShotBlock   few-shot 文本（<b>已经渲染好的</b>）；空串 = 不注入。
     *                       注意这里收的是<b>文本</b>而不是样例对象——本类不需要知道
     *                       few-shot 从哪来、怎么拼，那是 {@code FewShotExamples} 的事
     * @param repairAttempts 执行失败后最多让模型改几次；0 = 关闭自修复（迭代 18/19 的形态）
     * @param fault          调试用故障注入；null = 关
     * @param guard          迭代 21 · 准入护栏（白名单 + 禁 DDL/DML + 多语句 + 危险函数）。
     *                       <b>不允许为 null</b>——见下面的紧凑构造器
     * @param masker         迭代 21 · 出参脱敏。没配列名时等价于「不打码」
     */
    public record Options(int maxContextRows,
                          int maxRows,
                          TableSelector tableSelector,
                          int tableTopK,
                          String fewShotBlock,
                          int repairAttempts,
                          SqlFault fault,
                          SqlGuard guard,
                          ColumnMasker masker) {

        /**
         * <b>安全项在这里兜底，不允许为 null。</b>
         *
         * <p>理由很直接：护栏和脱敏是「安全」而不是「特性」——
         * 如果一个 {@code null} 就能把它关掉，那它迟早会被某个新建的构造路径悄悄关掉，
         * 而且那种关闭<b>不会报错</b>（只是从此不再拦截）。
         * 让默认值在这里强制成立，比在调用方每次记得传要可靠。
         */
        public Options {
            guard = guard == null ? new SqlGuard() : guard;
            masker = masker == null ? ColumnMasker.none() : masker;
        }

        /**
         * 迭代 18 的形态：不筛表、无 few-shot、不自修复、<b>也不限行数</b>。
         * 护栏照开（护栏是硬边界，任何形态都不关），脱敏不配。
         *
         * <p>行数不限是故意的：迭代 18/19 那会儿本来就没有上限，
         * 要复现旧基线就得一模一样。新写的调用点请传真实上限。
         */
        public static Options simple(int maxContextRows) {
            return new Options(maxContextRows, 0, null, 0, "", 0, null, null, null);
        }

        /** 迭代 19 的形态：筛表，但没有 few-shot、没有自修复、不限行数。 */
        public static Options withTableFilter(int maxContextRows, TableSelector selector, int tableTopK) {
            return new Options(maxContextRows, 0, selector, tableTopK, "", 0, null, null, null);
        }
    }

    private final Options options;

    /** 拼好的系统提示词（含 few-shot，构造时算一次——不必每次提问都拼字符串）。 */
    private final String systemPrompt;

    /**
     * 单元格为 SQL NULL 时的显示形式。
     *
     * <p><b>不能显示成空串。</b>「值是 NULL」和「值是空字符串」是两件事，
     * 而 NULL 恰恰是语料 04 点名要小心的地方（{@code refund_amount} 可为空，要 COALESCE）。
     *
     * <p>这条是<b>第一次实跑当场加的</b>：D9 那道题模型用了 {@code AVG(...)}，
     * 而 AVG 在 0 行上返回的是<b>一行 NULL</b>（不是 0 行）——
     * 于是 {@code rowCount = 1} 看起来像查成功了，结果单元格却是一片空白。
     * 把 NULL 打成字面量，这种「看起来成功的失败」就一眼可见了。
     */
    private static final String NULL_MARKER = "NULL";

    /**
     * <b>迭代 18 的形态</b>：不做表筛选、没有 few-shot、不自修复。
     * 留着它是因为「旧基线必须能跑回来」。
     */
    public Text2SqlDatabaseTool(JdbcTemplate jdbc,
                                ChatClient chatClient,
                                SchemaRenderer schemaRenderer,
                                int maxContextRows) {
        this(jdbc, chatClient, schemaRenderer, Options.simple(maxContextRows));
    }

    /** <b>迭代 19 的形态</b>：带表筛选。 */
    public Text2SqlDatabaseTool(JdbcTemplate jdbc,
                                ChatClient chatClient,
                                SchemaRenderer schemaRenderer,
                                int maxContextRows,
                                TableSelector tableSelector,
                                int tableTopK) {
        this(jdbc, chatClient, schemaRenderer,
                Options.withTableFilter(maxContextRows, tableSelector, tableTopK));
    }

    /** 迭代 20 起的完整入口。 */
    public Text2SqlDatabaseTool(JdbcTemplate jdbc,
                                ChatClient chatClient,
                                SchemaRenderer schemaRenderer,
                                Options options) {
        this.jdbc = jdbc;
        this.chatClient = chatClient;
        this.schemaRenderer = schemaRenderer;
        this.options = options;
        this.systemPrompt = (options.fewShotBlock() == null || options.fewShotBlock().isBlank())
                ? SYSTEM_PROMPT
                : SYSTEM_PROMPT + "\n" + options.fewShotBlock();
        if (options.repairAttempts() > 0) {
            log.info("      [Text2SQL] 自修复已开启：执行失败最多让模型改 {} 次", options.repairAttempts());
        }
    }

    @Override
    public QueryResult query(String question) {
        log.info("      [Text2SQL] 收到问题：\"{}\"", shorten(question));

        // ---------- 第 1 步：注入表结构（迭代 19 起：先筛表，再注入） ----------
        String schema;
        try {
            schema = schemaFor(question);
        }
        catch (RuntimeException e) {
            log.error("      [Text2SQL] 读取表结构失败：{}", briefError(e));
            return QueryResult.empty("-- 读取表结构失败（业务库没起？见 docs/18-Text2SQL原理与Schema注入.md）："
                    + briefError(e));
        }

        // ---------- 第 2 步：让模型生成 SQL ----------
        long t0 = System.currentTimeMillis();
        String raw;
        try {
            raw = this.chatClient.prompt()
                    .system(this.systemPrompt)
                    .user("【数据库表结构】\n" + schema + "\n【用户问题】\n" + question)
                    // 温度 0：SQL 要的是稳定复现，不是发挥。和 迭代 17 精排同一个理由。
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
        }
        catch (RuntimeException e) {
            log.warn("      [Text2SQL] 生成 SQL 失败：{}", briefError(e));
            return QueryResult.empty("-- 生成 SQL 失败：" + briefError(e));
        }
        long generateMs = System.currentTimeMillis() - t0;

        // 原始输出一定打出来。迭代 15 的教训：判据/解析类代码，看不到中间产物就没法查错。
        log.info("      [Text2SQL] 模型原始输出（生成 {} ms）：{}",
                generateMs, raw == null ? "(空)" : oneLine(raw));

        String sql = SqlText.extractSql(raw);

        // ---------- 故障注入：必须在护栏【之前】（迭代 21 修正了顺序） ----------
        // 迭代 20 时它排在护栏之后，当时无害。迭代 21 不行了：
        // 验收要求「故意让它生成 DROP TABLE，验证被拦住」——
        // 如果注入发生在护栏之后，那条 DROP 就绕过了护栏，验收是假的。
        // 语义上也更对：故障注入模拟的是「模型写出了这条 SQL」，那就该受和模型输出一样的检查。
        if (this.options.fault() != null) {
            String injected = this.options.fault().apply(sql);
            log.warn("      [Text2SQL] 故障注入（{}）之后的 SQL：{}",
                    this.options.fault().describe(), oneLine(injected));
            if (injected.equals(sql)) {
                // 注入没生效说明 from 串没匹配上 —— 不能装作注入了，否则验收是假的
                log.warn("      [Text2SQL] 故障注入没匹配到目标（{}），本次实际仍是原 SQL",
                        this.options.fault().from());
            }
            sql = injected;
        }

        if (sql.isEmpty()) {
            log.warn("      [Text2SQL] 提取后 SQL 为空，不执行（不编造数字）");
            return QueryResult.empty("-- 模型没有给出可执行的 SQL");
        }

        // 模型按提示词要求答「答不了」时，输出的是纯注释 —— 这不是错误，是它的选择。
        if (SqlText.isOnlyComments(sql)) {
            log.info("      [Text2SQL] 模型判定该问题无法用现有表回答（输出为纯注释），不执行");
            return QueryResult.empty(sql);
        }

        // ---------- 执行前的门：准入护栏（迭代 21） ----------
        SqlGuard.Decision decision = this.options.guard().check(sql);
        if (!decision.allowed()) {
            log.warn("      [Text2SQL] 已拦截：{}", decision.brief());
            log.warn("      [Text2SQL] 被拦下的语句：{}", oneLine(sql));
            // 【"[已阻止]" 这个字面量不能改】：诊断集的判据（Text2SqlDiagnostic.judge）靠它
            // 认出「这次没跑成」。迭代 20 我已经在「标记和判据对不上」这个坑上栽过一次——
            // 那次会让失败被当成「跑成功但结果为空」，数字整体偏乐观且不报错。
            // 所以这里要把规则和理由【附在标记后面】，而不是替换掉标记。
            return QueryResult.empty(sql
                    + "\n-- [已阻止] " + decision.rule() + "：" + decision.reason());
        }
        // 打的是 reason 而不是 rule：放行时 rule 是 "-"，打出来像少了个东西
        log.info("      [Text2SQL] 护栏放行：{}", decision.reason());

        log.info("      [Text2SQL] 生成的 SQL：{}", oneLine(sql));

        // ---------- 第 3 步：执行（迭代 20 起：失败就把报错回灌给模型让它改） ----------
        return executeWithRepair(question, schema, sql);
    }

    // ------------------------------------------------------------------
    // 诊断入口：跳过生成、直接跑一条指定的 SQL
    // ------------------------------------------------------------------

    /**
     * <b>跳过模型，直接执行一条指定的 SQL</b>——走完整的「护栏 → 行数上限 → 脱敏 → 截断上报」路径。
     *
     * <p>存在的理由很具体：有些东西要测的是<b>执行与出参那一侧</b>，而不是生成那一侧，
     * 而<b>生成是不可控的</b>。迭代 21 的脱敏演示就被这件事咬过：同一个问题，
     * 模型第一次写的是简单 `SELECT`（脱敏生效），第二次写的是 `UNION ALL`
     * （结果列拿不到底层表 OID，脱敏失效）。**判定随模型的写法翻来覆去，那就不叫测试了。**
     *
     * <p>所以留这个入口，把 SQL 固定住，测的东西才固定住。
     * 注意它<b>照样过护栏</b> —— 否则它就成了绕过安全边界的一个后门。
     */
    public QueryResult runSql(String sql) {
        SqlGuard.Decision decision = this.options.guard().check(sql);
        if (!decision.allowed()) {
            log.warn("      [Text2SQL] runSql 被护栏拦下：{}", decision.brief());
            return QueryResult.empty(sql + "\n-- [已阻止] " + decision.rule() + "：" + decision.reason());
        }
        return execute(sql);
    }

    // ------------------------------------------------------------------
    // 第 1 步：表结构（迭代 19 起：先筛表，再注入）
    // ------------------------------------------------------------------

    /**
     * 决定这次注入哪些表的结构。
     *
     * <p>三条纪律，每一条都对应一种「静默变坏」的可能：
     * <ol>
     *   <li><b>筛出空集就回退全量。</b>空表结构是最坏的静默失败——模型会以为这个库什么都没有，
     *       然后开始编。宁可多花 token，也不能给它一张空目录。</li>
     *   <li><b>筛选本身失败就降级，不让整次问答挂掉。</b>表筛选是<b>优化</b>不是必需步骤：
     *       它挂了（比如 embedding 接口不通）就退回全量注入，行为等于 迭代 18，只是慢一点。
     *       这和 迭代 9 的「LLM 路由失败降级到关键词路由」是同一种处理。</li>
     *   <li><b>降级必须打日志。</b>偷偷降级比直接失败更糟——你会在看评测时奇怪
     *       「token 怎么没降下来」，却不知道有一半请求根本没走筛选。</li>
     * </ol>
     */
    private String schemaFor(String question) {
        TableSelector selector = this.options.tableSelector();
        if (selector == null) {
            return this.schemaRenderer.render();
        }

        int total = this.schemaRenderer.tableNames().size();
        try {
            List<String> selected = selector.select(question, this.options.tableTopK());
            if (selected.isEmpty()) {
                log.warn("      [Text2SQL] 表筛选返回空集 —— 回退到全量注入（{} 张表）。"
                        + "空表结构是最坏的静默失败，必须回退", total);
                return this.schemaRenderer.render();
            }
            log.info("      [Text2SQL] 表筛选：{} 张里选出 {} 张 → {}", total, selected.size(), selected);
            return this.schemaRenderer.render(new LinkedHashSet<>(selected));
        }
        catch (RuntimeException e) {
            log.warn("      [Text2SQL] 表筛选失败，降级为全量注入（{} 张表）：{}", total, briefError(e));
            return this.schemaRenderer.render();
        }
    }

    // ------------------------------------------------------------------
    // 第 3 步：执行（迭代 20 起：失败就自修复）
    // ------------------------------------------------------------------

    /**
     * 执行 SQL；失败就把「失败的 SQL + 数据库报错」回灌给模型让它改，最多改
     * {@code options.repairAttempts()} 次。
     *
     * <h3>这就是 12-Factor Agents 里「把错误压缩进上下文」那一条</h3>
     * 原文的意思是：<b>别把错误当成异常抛给上层，要把它当成一次新的输入</b>——
     * 把报错信息塞回上下文，让模型自己看着办。这里正是这么做的。
     *
     * <h3>它为什么是「Agent 比固定工作流强」最直观的那个例子</h3>
     * 固定流水线里「生成 SQL → 执行」是一条直线，执行一报错就整条挂掉，
     * 用户拿到的是一个异常。<b>而模型本来就有能力看懂
     * {@code column "xxx" does not exist} 并改对</b>——缺的只是有人把这句话递给它。
     * 于是「让模型看着错误重试」这一圈，就是 Agent 相对流水线最实在的增量。
     *
     * <h3>四条纪律（每条都对应一种「修歪了」的方式）</h3>
     * <ol>
     *   <li><b>只有「执行报错」才修，「0 行」不修。</b>0 行不是错误——
     *       迭代 18 已经证明它既可能是「真的没数据」，也可能是「取值写错了」。
     *       拿 0 行去触发重试，等于让模型<b>瞎猜</b>，还会把「值域」这种该由 few-shot 解决的问题
     *       变成随机试探。</li>
     *   <li><b>修好的语句仍要过只读护栏。</b>「第一次给了 SELECT、修一次变成 DROP」是必须拦的。</li>
     *   <li><b>修不出可用 SQL 就停，不硬撑。</b>模型改两次还是空/纯注释/非只读，就收手如实上报。</li>
     *   <li><b>修复痕迹要留在返回的 SQL 里。</b>见 {@link #withRepairNote}。</li>
     * </ol>
     */
    private QueryResult executeWithRepair(String question, String schema, String firstSql) {
        List<FailedAttempt> failed = new ArrayList<>();
        String current = firstSql;
        int maxRepair = Math.max(0, this.options.repairAttempts());

        while (true) {
            long t0 = System.currentTimeMillis();
            try {
                QueryResult result = execute(current);
                long ms = System.currentTimeMillis() - t0;
                if (failed.isEmpty()) {
                    log.info("      [Text2SQL] 执行成功：{} 行（执行 {} ms）", result.rowCount(), ms);
                    return result;
                }
                log.info("      [Text2SQL] 自修复 {} 次后执行成功：{} 行（执行 {} ms）",
                        failed.size(), result.rowCount(), ms);
                return withRepairNote(result, failed);
            }
            catch (RuntimeException e) {
                String error = briefError(e);
                failed.add(new FailedAttempt(current, error));
                log.warn("      [Text2SQL] 执行失败（第 {} 次尝试）：{}", failed.size(), error);

                if (failed.size() > maxRepair) {
                    break;   // 修正次数用完了
                }
                String repaired = repair(question, schema, current, error);
                if (repaired.isEmpty() || SqlText.isOnlyComments(repaired)) {
                    log.warn("      [Text2SQL] 模型没给出可用的修正 SQL（空或只有注释），停止重试");
                    break;
                }
                SqlGuard.Decision repairedDecision = this.options.guard().check(repaired);
                if (!repairedDecision.allowed()) {
                    // 修一次反而给出非只读语句 —— 这正是「自修复」最该防的一种翻车：
                    // 它为了让报错消失，可能选择去写库。所以修正后的语句也要过同一道门。
                    log.warn("      [Text2SQL] 模型修正后的语句也没过护栏，拦下并停止重试：{} —— {}",
                            repairedDecision.brief(), oneLine(repaired));
                    break;
                }
                log.info("      [Text2SQL] 第 {} 次修正后的 SQL：{}", failed.size(), oneLine(repaired));
                current = repaired;
            }
        }

        int repairs = Math.max(0, failed.size() - 1);
        // 【注意标记的统一】两种失败都要含有字面量 "[执行失败]"：
        // 诊断集的判据（Text2SqlDiagnostic.judge）就是靠它认出「这次没跑成」的。
        // 要是这里给自修复失败换了个写法，判据就会把它当成「跑成功了但结果是空的」——
        // 又一个「不报错的错误」，而且会让消融实验的数字整体偏乐观。
        String prefix = repairs == 0
                ? "-- [执行失败] "
                : "-- [执行失败] 自修复 " + repairs + " 次仍失败：";
        return QueryResult.empty(current + "\n" + prefix + failed.get(failed.size() - 1).error());
    }

    /**
     * 把修复痕迹写进返回的 SQL。
     *
     * <p>为什么不直接返回 {@code result}：{@link QueryResult#sql()} 是
     * 「这个答案是怎么来的」的<b>唯一证据</b>。修了两次才成功的语句，和一次就成的语句，
     * 在证据上不是一回事——只看最终那条，看不出中间发生过什么。
     * 而且这件事会影响到下游：迭代 25 分析失败模式时，「有多少答案是靠重试才拿到的」
     * 是个有用的数字。
     */
    private static QueryResult withRepairNote(QueryResult result, List<FailedAttempt> failed) {
        StringBuilder note = new StringBuilder();
        note.append("-- [自修复 ").append(failed.size()).append(" 次后成功] 首次失败：")
            .append(oneLine(failed.get(0).error())).append('\n');
        for (int i = 1; i < failed.size(); i++) {
            note.append("--   第 ").append(i + 1).append(" 次失败：")
                .append(oneLine(failed.get(i).error())).append('\n');
        }
        return new QueryResult(note + result.sql(), result.columns(), result.rows(), result.rowCount());
    }

    /** 把报错回灌给模型，要一条修正后的 SQL。失败返回空串。 */
    private String repair(String question, String schema, String failedSql, String error) {
        String user = "【数据库表结构】\n" + schema
                + "\n【用户问题】\n" + question
                + "\n【你上一次写的 SQL（执行失败了）】\n" + failedSql
                + "\n【PostgreSQL 的报错】\n" + error
                + "\n请修正这条 SQL。";
        try {
            String raw = this.chatClient.prompt()
                    .system(REPAIR_SYSTEM_PROMPT)
                    .user(user)
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();
            log.info("      [Text2SQL] 修复的模型原始输出：{}", raw == null ? "(空)" : oneLine(raw));
            return SqlText.extractSql(raw);
        }
        catch (RuntimeException e) {
            log.warn("      [Text2SQL] 请求模型修复失败：{}", briefError(e));
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 执行（原语）
    // ------------------------------------------------------------------

    /**
     * 执行查询，并把结果装进 {@link QueryResult}。
     *
     * <p>用 {@code ResultSetExtractor}（自己循环 {@code rs.next()}）而不是 {@code queryForList}，
     * 是为了几件 queryForList 做不到的事：
     * <ol>
     *   <li><b>行数为 0 时也能拿到列名。</b>{@code queryForList} 返回空 List，列名就丢了；
     *       而「查到了但一行都没有」和「列名都不对」是两种要分开的情况。</li>
     *   <li><b>行数可以超过放进上下文的行数。</b>真实行数如实记在 rowCount，
     *       只把前 {@code maxContextRows} 行拼进 prompt——这正是
     *       {@link QueryResult} 里 {@code rowCount} 和 {@code rows.size()} 故意分开的理由。</li>
     *   <li><b>能在【语句】这一层设行数上限。</b>{@code PreparedStatement.setMaxRows} 让驱动
     *       干脆不把多余的行拉回来。<b>这里刻意不用 {@code JdbcTemplate.setMaxRows}</b>——
     *       那是连接级的，会把元数据查询一起截断（实跑踩过，见 {@code BusinessDb} 类注释）。</li>
     * </ol>
     */
    private QueryResult execute(String sql) {
        List<String> columns = new ArrayList<>();
        List<String> baseNames = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        int[] total = {0};
        int cap = this.options.maxRows();

        this.jdbc.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(sql);
                    if (cap > 0) {
                        // 在语句这一层截断：多余的行驱动根本不会拉回来
                        ps.setMaxRows(cap);
                    }
                    return ps;
                },
                rs -> {
                    ResultSetMetaData meta = rs.getMetaData();
                    int n = meta.getColumnCount();
                    for (int i = 1; i <= n; i++) {
                        columns.add(meta.getColumnLabel(i));      // 给人看的（可能被 AS 改过）
                        baseNames.add(baseColumnName(meta, i));   // 判定脱敏用的（别名改不掉）
                    }
                    while (rs.next()) {
                        total[0]++;
                        if (rows.size() < this.options.maxContextRows()) {
                            List<String> row = new ArrayList<>(n);
                            for (int i = 1; i <= n; i++) {
                                Object value = rs.getObject(i);
                                if (value == null) {
                                    // 【先处理 NULL】：NULL 有自己的显示标记，不该被打成 "N***"
                                    row.add(NULL_MARKER);
                                }
                                else {
                                    String text = String.valueOf(value);
                                    row.add(this.options.masker().masks(baseNames.get(i - 1))
                                            ? this.options.masker().mask(text)
                                            : text);
                                }
                            }
                            rows.add(row);
                        }
                    }
                    return null;
                });

        int count = total[0];
        if (cap > 0 && count >= cap) {
            // 【被截断必须说出来】。行数上限是安全边界，但「悄悄只回了一半数据」
            // 恰恰是本项目一路在抓的那类错误：查询看起来成功了，答案却只基于前 N 行。
            // 而且注意这时候 rowCount 已经【不是真实行数】了——真实行数我们拿不到，
            // 所以只能如实说明「撞到上限了」。
            log.warn("      [Text2SQL] 返回行数撞到上限 {} 行，结果可能被截断（真实行数未知）", cap);
            return new QueryResult(sql
                    + "\n-- [已被截断] 只返回前 " + cap + " 行（上限 sdaq.text2sql.max-rows）",
                    columns, rows, count);
        }
        return new QueryResult(sql, columns, rows, count);
    }

    /**
     * 取一列的<b>底层列名</b>——也就是「别名遮不住」的那个名字。
     *
     * <p><b>这里原本是错的，被实跑抓出来了。</b>我一开始写的是
     * {@code meta.getColumnName(i)}，以为 JDBC 契约里 {@code getColumnName} 给的是底层列名
     * （只有 {@code getColumnLabel} 才受 {@code AS} 影响）。
     * 实测 {@code SELECT order_id AS 订单号}：<b>PG 驱动返回的 getColumnName 就是「订单号」</b>，
     * 于是脱敏被一个别名绕过去了，而且不报错。
     *
     * <p>正确答案是 PG 驱动自己的 {@code getBaseColumnName}——它用结果集元数据里的
     * <b>表 OID + 列号</b>去反查，别名改不掉它。这是 PostgreSQL 专有 API
     * （项目本来也只对 PG），所以直接依赖没问题。
     *
     * <p><b>已知缺口</b>：聚合/表达式列（{@code SELECT MAX(customer_name) AS x}）
     * 拿不到底层列名（OID 是 0），会退回显示名 {@code x}，于是<b>聚合敏感列能绕过按列名脱敏</b>。
     * 这也说明按列名脱敏本身就不是完备的办法——真要严格，得在数据库侧用视图或列级权限。
     */
    private static String baseColumnName(ResultSetMetaData meta, int index) {
        if (meta instanceof PgResultSetMetaData pg) {
            try {
                String base = pg.getBaseColumnName(index);
                if (base != null && !base.isEmpty()) {
                    return base;
                }
            }
            catch (SQLException e) {
                // 取不到就退回显示名：脱敏会因此弱一点，但不该让整条查询挂掉
                log.warn("      [Text2SQL] 取底层列名失败，退回显示名：{}", e.getMessage());
            }
        }
        try {
            return meta.getColumnName(index);
        }
        catch (SQLException e) {
            return String.valueOf(index);
        }
    }

    // ------------------------------------------------------------------
    // 文本处理
    // ------------------------------------------------------------------
    // 「提取 SQL / 判断是不是纯注释 / 判断是不是只读」三件事搬到了 SqlText ——
    // 它们处理的全是字符串，和库、模型都无关，而诊断 demo 也要用同一套判断
    // （demo 要区分「模型给出了 SQL」和「模型用注释说了答不了」）。
    // 两处各写一份迟早写岔，所以抽成一处。

    // ------------------------------------------------------------------
    // 日志辅助
    // ------------------------------------------------------------------

    /** 多行文本压成一行——日志里 SQL 换行会把「一条语句」读成「好几条」。 */
    private static String oneLine(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) {
            flat = flat.replace("  ", " ");
        }
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    /** 只留最根上的那句错，否则 DataAccessException 会把整条 SQL 和一堆细节都吐出来。 */
    private static String briefError(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.toString() : root.getMessage();
        message = oneLine(message);
        return message.length() <= 220 ? message : message.substring(0, 220) + "…";
    }

    private String shorten(String s) {
        return s == null ? "" : (s.length() <= 30 ? s : s.substring(0, 30) + "…");
    }
}
