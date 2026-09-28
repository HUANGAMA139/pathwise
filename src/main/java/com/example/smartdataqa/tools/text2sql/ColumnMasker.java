package com.example.smartdataqa.tools.text2sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 迭代 21 · <b>出参脱敏</b>：结果里有敏感列时，把值打上码再交出去。
 *
 * <h3>为什么这一层和「准入护栏」是两件事</h3>
 * <pre>
 *   {@link SqlGuard}      管【入】：这条 SQL 能不能跑
 *   ColumnMasker   管【出】：跑出来的东西能不能原样给出去
 * </pre>
 * 一条完全合法的 {@code SELECT customer_name FROM customers} 也会泄露个人信息——
 * 准入层拦不住它，也不该拦（它确实是个只读查询）。<b>所以必须有一层管出参。</b>
 *
 * <h3>一个不显眼、但很要紧的实现细节：按【底层列名】判定，不按显示名</h3>
 * 如果按结果集的<b>显示名</b>（{@code getColumnLabel}）判定，那么
 * <pre>
 *   SELECT customer_name AS 客户 FROM customers
 * </pre>
 * 的列名就变成了「客户」——**一个别名就能绕过脱敏**，而且不报错。
 *
 * <p><b>但这里我猜错过一次，被实跑抓出来了。</b>我原以为用 JDBC 的
 * {@code ResultSetMetaData.getColumnName()} 就能拿到底层列名
 * （契约上只有 {@code getColumnLabel} 才受 {@code AS} 影响）。
 * 实测：<b>PG 驱动返回的 getColumnName 就是别名本身</b>，
 * 所以「别名遮不住」这个说法当时是假的，脱敏被一个 {@code AS} 绕过去了。
 *
 * <p>真正管用的是 PG 驱动专有的 {@code PgResultSetMetaData.getBaseColumnName(int)}——
 * 它拿结果集元数据里的<b>表 OID + 列号</b>去反查，别名改不掉。
 * 判定逻辑在 {@code Text2SqlDatabaseTool.baseColumnName}（那里写了完整的来龙去脉和已知缺口）。
 *
 * <h3>打码规则故意做得很简单</h3>
 * 保留首字符，其余替换成 {@code ***}。真实系统会按类型分开处理
 * （手机号留前 3 后 4、身份证留前 6 后 4、邮箱留域名），
 * 但那些规则每一条都要单独论证「这样真的脱敏了吗」——
 * 本轮的目标是<b>把这一层建起来并证明它会被触发</b>，不是把规则做全。
 */
public final class ColumnMasker {

    private static final Logger log = LoggerFactory.getLogger(ColumnMasker.class);

    /** 打码后替换成的字符串。固定长度，<b>不暴露原文长度</b>（长度本身也是信息）。 */
    private static final String MASK = "***";

    private final Set<String> maskedColumns;

    private ColumnMasker(Set<String> maskedColumns) {
        this.maskedColumns = maskedColumns;
    }

    /** 不打码（用于复现旧基线：脱敏是输出层的事，和历史行为正交）。 */
    public static ColumnMasker none() {
        return new ColumnMasker(Set.of());
    }

    /** 从列表构造。列名统一转小写，匹配时大小写不敏感。 */
    public static ColumnMasker of(Collection<String> columns) {
        Set<String> set = new LinkedHashSet<>();
        if (columns != null) {
            for (String c : columns) {
                if (c != null && !c.isBlank()) {
                    set.add(c.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!set.isEmpty()) {
            log.info("脱敏已开启：{} 个列名 {}", set.size(), set);
        }
        return new ColumnMasker(set);
    }

    /** 从逗号分隔的配置串构造（{@code sdaq.text2sql.masked-columns}）。 */
    public static ColumnMasker fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return none();
        }
        return of(java.util.Arrays.asList(csv.split(",")));
    }

    /** 配了几个列名。给 demo 打印用。 */
    public int size() {
        return this.maskedColumns.size();
    }

    public Set<String> columns() {
        return Set.copyOf(this.maskedColumns);
    }

    /** 这个（真实）列名要不要打码。 */
    public boolean masks(String columnName) {
        return columnName != null && this.maskedColumns.contains(columnName.toLowerCase(Locale.ROOT));
    }

    /**
     * 打码。
     *
     * <p>空串原样返回——它不是「值」，打码反而制造了一个假值。
     * 注意调用方要<b>先处理 NULL</b>：NULL 有自己的显示标记，不该被打成 {@code N***}。
     */
    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() == 1) {
            return "*";
        }
        return value.charAt(0) + MASK;
    }
}
