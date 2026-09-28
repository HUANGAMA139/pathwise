package com.example.smartdataqa.tools.text2sql;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 21 · <b>SQL 准入护栏</b>：这条 SQL 允不允许执行。
 *
 * <h3>为什么需要它——而且为什么它值得单独一个类</h3>
 * 这个项目里<b>唯一真正危险的操作</b>，就是把模型生成的字符串直接丢给数据库执行。
 * 前面 20 天一直在让它「更准」，本轮要让它「更安全」。
 *
 * <p>面试里这一天的分量比功能本身大：**能主动想到「让模型生成 SQL 必须有安全边界」，
 * 体现的是生产意识，而不是「我照着教程跑通了」。** 很多人的项目只做到「能跑」，安全是零。
 *
 * <h3>五条规则，每条都对着一种真实的绕过手法</h3>
 * <ol>
 *   <li><b>白名单：只放行 {@code SELECT} / {@code WITH}。</b>其余一律拒。
 *       白名单比黑名单可靠——黑名单永远漏。</li>
 *   <li><b>但白名单【不能只看第一个词】。</b>这是最容易被忽略的一个洞：
 *       <pre>
 *   WITH d AS (DELETE FROM sales_orders RETURNING *) SELECT * FROM d
 *       </pre>
 *       首词是 {@code WITH}，看起来像只读，实际上删表。<b>所以必须在【整条语句】上扫黑名单。</b></li>
 *   <li><b>禁止多语句。</b>{@code SELECT ...; DROP TABLE ...} 是经典组合，
 *       而 JDBC 的 {@code executeQuery} 对多语句的行为并不统一，不能指望它替你拦。</li>
 *   <li><b>拒 {@code SELECT ... INTO}</b>（它会建表）和 <b>{@code FOR UPDATE / FOR SHARE}</b>（它会加锁）。</li>
 *   <li><b>危险函数黑名单</b>：读服务器文件、睡死数据库、杀连接的函数。</li>
 * </ol>
 *
 * <h3>一个把「误报」和「漏报」同时解决掉的关键动作</h3>
 * 判定之前<b>先把字符串字面量和注释挖掉</b>，只留下「会真正执行的关键字」。顺序不能反：
 * <pre>
 *   先剥字符串，再剥注释   [对]
 *   先剥注释，再剥字符串   [错] 字符串里出现的 '--' 会被当成注释起点，把半条语句吃掉
 * </pre>
 * 这个顺序坑和 迭代 13 写 Java 静态检查脚本时踩的是同一个。
 *
 * <p>挖掉之后两头都对了：
 * <ul>
 *   <li><b>不误报</b>：{@code WHERE status = 'DELETED'} 里的 {@code DELETED} 在字符串里 → 看不见；
 *       {@code -- DROP 是危险的} 是注释 → 看不见；{@code created_at} 里的 {@code create}
 *       因为 {@code \b} 把下划线当词字符 → 匹配不上。</li>
 *   <li><b>不漏报</b>：{@code /* x *&#47; DROP TABLE t} 里 DROP 在注释外 → 照样看得见。</li>
 * </ul>
 *
 * <h3>先说清楚：这一层【不是】最后一道防线</h3>
 * 它拦得住「模型写错」和「手滑」，<b>拦不住铁了心要绕的人</b>。已知它挡不住的：
 * <ul>
 *   <li>PostgreSQL 的 <b>dollar-quoting</b>（{@code $$ ... $$}）和 <b>E'' 转义字符串</b>——
 *       本类的扫描器只认 {@code '...'} 和 {@code "..."}，遇到 {@code $$} 会把里面的内容当成真实代码。
 *       后果是<b>误报</b>（把合法查询拦下），不会漏放。</li>
 *   <li>任何本类没想到的新写法。</li>
 * </ul>
 * <b>真正硬的那一层在数据库侧：给应用一个只有 SELECT 权限的账号。</b>
 * 那一层即使应用层被绕过也拦得住——脚本见 {@code docker/init-business-db-ro.sql}。
 * <b>纵深防御的意思是「假设每一层都会被绕过」，而不是「我这一层写得够好」。</b>
 */
public final class SqlGuard {

    /**
     * 一次准入判定的结果。
     *
     * @param allowed 能不能执行
     * @param rule    命中了哪条规则（拒绝时才有意义）
     * @param reason  人读的理由
     */
    public record Decision(boolean allowed, String rule, String reason) {

        public static Decision allow() {
            return new Decision(true, "-", "只读查询");
        }

        public static Decision deny(String rule, String reason) {
            return new Decision(false, rule, reason);
        }

        public String brief() {
            return this.allowed ? "[放行] " + this.reason : "[拦截/" + this.rule + "] " + this.reason;
        }
    }

    /** 白名单：只允许以这两个词开头的语句。 */
    private static final List<String> ALLOWED_STARTS = List.of("SELECT", "WITH");

    /**
     * 黑名单：<b>在整条语句上扫</b>，不只看开头（见类注释第 2 条）。
     *
     * <p>这些词全部是 SQL 的「动作」，正常 SELECT 里一个都不该出现。
     * 注意用 {@code \b} 匹配时下划线算词字符，所以 {@code created_at} / {@code updated_at}
     * 这类<b>列名不会误报</b>{@code CREATE} / {@code UPDATE}。
     */
    private static final List<String> FORBIDDEN_WORDS = List.of(
            // DML
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT",
            // DDL
            "DROP", "CREATE", "ALTER", "TRUNCATE", "COMMENT", "REINDEX", "CLUSTER",
            // 权限
            "GRANT", "REVOKE",
            // 服务器端动作
            "COPY", "CALL", "DO", "EXECUTE", "VACUUM", "ANALYZE",
            // 会话/事务/锁
            // 【注意没有 FETCH】—— `FETCH FIRST 10 ROWS ONLY` 是合法的只读分页写法，
            // 列进来就会误报。而游标版的 FETCH 需要先 DECLARE（已被拦），所以不放它进来是安全的。
            "SET", "RESET", "LOCK", "BEGIN", "START", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "RELEASE", "PREPARE", "DEALLOCATE", "DECLARE", "DISCARD");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(" + String.join("|", FORBIDDEN_WORDS) + ")\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 危险函数：读服务器文件、睡死数据库、掐连接。
     *
     * <p>要求后面跟一个 {@code (}，否则函数名出现在别处（比如字符串之外的地方）会误报。
     */
    private static final Pattern DANGEROUS_FUNCTION = Pattern.compile(
            "\\b(pg_sleep|pg_sleep_for|pg_sleep_until|pg_read_file|pg_read_binary_file|"
                    + "pg_ls_dir|pg_ls_logdir|pg_ls_waldir|pg_stat_file|lo_import|lo_export|"
                    + "dblink|dblink_exec|pg_terminate_backend|pg_cancel_backend|pg_reload_conf|"
                    + "pg_rotate_logfile|pg_promote|pg_switch_wal|set_config)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** {@code SELECT ... INTO new_table} 会建表，必须拦。 */
    private static final Pattern SELECT_INTO = Pattern.compile(
            "\\bINTO\\b", Pattern.CASE_INSENSITIVE);

    /** 加锁读：{@code FOR UPDATE} / {@code FOR SHARE} / {@code FOR NO KEY UPDATE}。 */
    private static final Pattern FOR_LOCK = Pattern.compile(
            "\\bFOR\\s+(NO\\s+KEY\\s+)?(UPDATE|SHARE)\\b", Pattern.CASE_INSENSITIVE);

    /** 语句长度上限。防的是「超长语句把库拖死」，不是防注入。 */
    private static final int MAX_LENGTH = 20_000;

    public SqlGuard() {
    }

    /**
     * 判定这条 SQL 准不准跑。
     *
     * <p><b>注意它不修改 SQL</b>——只回答「行不行」。执行用的仍是模型写的那条原文，
     * 这样日志里看到的和被判定的是同一个东西（否则「我拦的是哪条」就说不清了）。
     */
    public Decision check(String sql) {
        if (sql == null || sql.isBlank()) {
            return Decision.deny("空语句", "没有可执行的内容");
        }
        if (sql.length() > MAX_LENGTH) {
            return Decision.deny("超长", "语句长度 " + sql.length() + " 超过上限 " + MAX_LENGTH);
        }

        String code = stripLiteralsAndComments(sql);

        // 挖掉字符串和注释之后什么都没剩 = 只有注释 / 空白
        if (code.chars().noneMatch(Character::isLetter)) {
            return Decision.deny("空语句", "去掉注释和字符串后没有可执行的语句");
        }

        // ---- 多语句 ----
        String trimmed = code.strip();
        int lastSemicolon = trimmed.lastIndexOf(';');
        if (lastSemicolon >= 0 && trimmed.substring(lastSemicolon + 1).strip().length() > 0) {
            return Decision.deny("多语句", "一条语句之外还有别的语句（分号后面还有内容）");
        }
        if (trimmed.substring(0, Math.max(0, lastSemicolon)).contains(";")) {
            return Decision.deny("多语句", "出现了多个分号，可能是拼起来的多条语句");
        }

        // ---- 白名单：开头必须是 SELECT / WITH ----
        String first = firstWord(trimmed);
        if (!ALLOWED_STARTS.contains(first)) {
            return Decision.deny("非只读", "第一条语句以 " + first + " 开头，只允许 "
                    + String.join(" / ", ALLOWED_STARTS));
        }

        // ---- 加锁读：放在黑名单扫描【之前】，为了报出更准确的原因 ----
        // `FOR UPDATE` 里的 UPDATE 也会被黑名单命中，但那样报出来的是「DDL/DML:UPDATE」，
        // 而真正的问题不是「它写了数据」而是「它要给行加锁」。诊断信息要指向真正的原因。
        Matcher lock = FOR_LOCK.matcher(code);
        if (lock.find()) {
            return Decision.deny("加锁读", "出现了 " + lock.group().toUpperCase(Locale.ROOT)
                    + "，只读查询不允许加锁");
        }

        // ---- 黑名单：整条语句扫（白名单只看开头，这里补上 WITH 里藏 DML 的洞）----
        Matcher forbidden = FORBIDDEN.matcher(code);
        if (forbidden.find()) {
            String word = forbidden.group().toUpperCase(Locale.ROOT);
            return Decision.deny("DDL/DML", "语句里出现了 " + word
                    + "（即使开头是 " + first + " 也不放行——WITH 里可以藏 DML）");
        }

        // ---- SELECT ... INTO：会建表 ----
        if (SELECT_INTO.matcher(code).find()) {
            return Decision.deny("SELECT INTO", "INTO 会创建新表，属于写操作");
        }

        // ---- 危险函数 ----
        Matcher func = DANGEROUS_FUNCTION.matcher(code);
        if (func.find()) {
            return Decision.deny("危险函数", "调用了 " + func.group(1).toLowerCase(Locale.ROOT)
                    + "()，属于读文件/拒绝服务/进程控制类函数");
        }

        return Decision.allow();
    }

    /** 取第一个词（第一个非空白、非标点的标识符序列）。 */
    private static String firstWord(String code) {
        int i = 0;
        while (i < code.length() && !Character.isLetter(code.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < code.length() && (Character.isLetterOrDigit(code.charAt(i)) || code.charAt(i) == '_')) {
            i++;
        }
        return code.substring(start, i).toUpperCase(Locale.ROOT);
    }

    /**
     * 把字符串字面量、双引号标识符和注释挖成空格，只留下会真正执行的部分。
     *
     * <p><b>顺序不能反</b>：先剥字符串，再剥注释（见类注释）。
     * 挖成空格而不是删掉，是为了不把前后两个词粘成一个（{@code a'x'b} → {@code a b} 而不是 {@code ab}）。
     *
     * <p>已知不处理：PostgreSQL 的 dollar-quoting（{@code $$…$$}）和 {@code E'…'} 里的反斜杠转义。
     * 后果是误报（把合法查询拦下），不是漏放。见类注释最后一节。
     */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'', out);
            }
            else if (c == '"') {
                i = skipQuoted(sql, i, '"', out);
            }
            else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end;
                out.append(' ');
            }
            else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            }
            else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 跳过一段引号包围的内容（SQL 里重复引号 = 转义），在 out 里只留一个空格。 */
    private static int skipQuoted(String sql, int from, char quote, StringBuilder out) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;        // '' 或 "" = 转义出来的一个引号，继续
                    continue;
                }
                out.append(' ');
                return i + 1;
            }
            i++;
        }
        out.append(' ');
        return n;                  // 引号没闭合：当成一直到结尾
    }
}
