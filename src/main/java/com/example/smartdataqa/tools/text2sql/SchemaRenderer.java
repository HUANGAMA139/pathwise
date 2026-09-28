package com.example.smartdataqa.tools.text2sql;

import com.example.smartdataqa.util.ConsoleText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 迭代 18 起 · <b>表结构注入</b>：把业务库的表结构渲染成一段文本，拼进 Text2SQL 的 prompt。
 * <br>迭代 19 起 · 多了一个 {@link #catalog()}：<b>「一表一条」的检索语料</b>，给表筛选用。
 *
 * <h3>为什么这是 Text2SQL 的第一步</h3>
 * 模型不知道你的库里有什么表、哪些字段、谁和谁能 JOIN。
 * 不给它表结构，它只能凭空编表名。
 *
 * <p>但 迭代 18 的实跑还告诉我们一件更要紧的事：**表结构能告诉模型「有哪些列」，
 * 告诉不了它「该用哪一列」，更告诉不了它「列里存了哪些值」。** 前者在文档里，后者在数据字典里。
 * 所以这个类只解决「表结构」这一层，别指望它更多。
 *
 * <h3>迭代 19 的两处改动</h3>
 * <ol>
 *   <li><b>元数据只读一次，且缓存成结构化的 {@link Schema}。</b>原来每次 {@code render} 都要重查一遍
 *       （而且 {@code render(子集)} 那个重载完全不缓存）。本轮要「先筛表、再渲染子集」，
 *       如果还是每次重查，筛一次就要多打一轮元数据查询——白白浪费，而且两轮之间拿到的
 *       可能不是同一份快照。</li>
 *   <li><b>新增 {@link #catalog()}。</b>筛选要有一个「可检索的单位」，那就是「一张表一条」。
 *       它和渲染出来的表结构<b>共用同一份 {@link Schema}</b>——这很重要：
 *       目录里有的表，渲染时一定渲染得出来；两边不会各说各话。</li>
 * </ol>
 *
 * <h3>怎么拿表结构：问数据库，不写死</h3>
 * 全部走 {@code information_schema} / {@code pg_catalog}，不硬编码 DDL。
 * 理由很实际：<b>写死的表结构会和你真实的库悄悄脱节</b>——加了一列忘了改常量，
 * 模型就会一直看不到那一列，而这件事不报错。
 *
 * <h3>懒加载</h3>
 * 第一次用到时才连库，之后走缓存。好处：业务库没起时，应用启动不受影响
 * （只在真正要渲染/筛选时才报错）。
 */
public class SchemaRenderer {

    private static final Logger log = LoggerFactory.getLogger(SchemaRenderer.class);

    /** 目录条目的元数据键：这张表叫什么。RRF/BM25 都按 {@code Document.id} 对齐，这里再放一份便于取用。 */
    public static final String META_TABLE = "table";

    private final JdbcTemplate jdbc;

    /** 缓存。volatile + 双重检查，避免并发首次调用时重复读元数据。 */
    private volatile Schema cached;

    public SchemaRenderer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /** 渲染<b>全部</b>表（迭代 18 的形态）。 */
    public String render() {
        return render(new LinkedHashSet<>(tableNames()));
    }

    /**
     * 只渲染指定的几张表。<b>迭代 19「先筛表再注入」的落点。</b>
     *
     * <p>传进来的名字如果一张都对不上，返回一句说明而不是空串——
     * <b>空表结构是最坏的静默失败</b>：模型会以为这个库什么都没有，然后开始编。
     */
    public String render(Set<String> onlyTables) {
        Schema schema = schema();
        List<String> picked = schema.tables().stream().filter(onlyTables::contains).toList();
        if (picked.isEmpty()) {
            log.warn("      [Text2SQL] 筛出来的表名一张都对不上：{}", onlyTables);
            return "（筛选后没有可用的表）";
        }
        return render(schema, picked);
    }

    /** 库里全部表名（按名字排序，确定性的）。 */
    public List<String> tableNames() {
        return schema().tables();
    }

    /**
     * <b>表目录</b>：一张表一条，用来做表级检索。
     *
     * <p>条目正文 = 表名 + 表注释 + 「列名 列注释」的列表。
     * 为什么把列注释也放进去：中文问题里的「销售额」「退货」，靠英文列名
     * （{@code order_amount} / {@code refund_amount}）是<b>匹配不上</b>的，
     * 而列注释正好是中文。目录只用于<b>排序</b>，不会被拼进 prompt，
     * 所以写详细一点没有代价——真正进 prompt 的只有选出来的那几张表。
     *
     * <p>条目的 id 就是<b>表名</b>。这一点是硬要求：BM25 和向量两路的结果要靠
     * {@code Document::getId()} 对齐才能融合（迭代 16 那条「两路必须共享同一 id 空间」）。
     */
    public List<Document> catalog() {
        Schema schema = schema();
        List<Document> out = new ArrayList<>(schema.tables().size());
        for (String table : schema.tables()) {
            StringBuilder text = new StringBuilder();
            text.append("表 ").append(table);
            String tableComment = schema.tableComments().get(table);
            if (tableComment != null && !tableComment.isBlank()) {
                text.append('（').append(tableComment).append('）');
            }
            text.append('\n').append("列：");
            List<String> parts = new ArrayList<>();
            for (Column column : schema.columnsByTable().getOrDefault(table, List.of())) {
                String comment = schema.columnComments().get(table + "." + column.name());
                parts.add(comment == null || comment.isBlank()
                        ? column.name()
                        : column.name() + " " + comment);
            }
            text.append(String.join("，", parts));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(META_TABLE, table);
            out.add(Document.builder().id(table).text(text.toString()).metadata(metadata).build());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 元数据（只读一次，缓存）
    // ------------------------------------------------------------------

    /** 表里的一列。 */
    private record Column(String name, String type, boolean nullable, String defaultValue) {
    }

    /** 一条外键。 */
    private record ForeignKey(String table, String column, String refTable, String refColumn) {
    }

    /**
     * 库里表结构的<b>结构化</b>快照。
     *
     * <p>迭代 19 之前只缓存渲染出来的<b>字符串</b>；现在缓存结构本身，
     * 于是「渲染全部」「渲染子集」「生成目录」三个用途共用一次读取。
     */
    private record Schema(List<String> tables,
                          Map<String, List<Column>> columnsByTable,
                          Map<String, String> tableComments,
                          Map<String, String> columnComments,
                          Set<String> primaryKeys,
                          List<ForeignKey> foreignKeys) {
    }

    private Schema schema() {
        Schema value = this.cached;
        if (value == null) {
            synchronized (this) {
                value = this.cached;
                if (value == null) {
                    value = read();
                    this.cached = value;
                }
            }
        }
        return value;
    }

    private Schema read() {
        long t0 = System.currentTimeMillis();

        List<String> tables = this.jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name""", String.class);

        Map<String, List<Column>> columnsByTable = new LinkedHashMap<>();
        for (String table : tables) {
            columnsByTable.put(table, new ArrayList<>());
        }
        List<ColumnWithTable> flatColumns = this.jdbc.query("""
                SELECT table_name, column_name,
                       CASE
                         WHEN character_maximum_length IS NOT NULL
                           THEN data_type || '(' || character_maximum_length || ')'
                         WHEN data_type = 'numeric' AND numeric_precision IS NOT NULL
                           THEN 'numeric(' || numeric_precision || ',' || numeric_scale || ')'
                         ELSE data_type
                       END AS col_type,
                       is_nullable,
                       column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position""",
                (rs, rowNum) -> new ColumnWithTable(
                        rs.getString("table_name"),
                        new Column(
                                rs.getString("column_name"),
                                normalizeType(rs.getString("col_type")),
                                "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                                rs.getString("column_default"))));
        for (ColumnWithTable c : flatColumns) {
            List<Column> bucket = columnsByTable.get(c.table());
            if (bucket != null) {
                bucket.add(c.column());
            }
        }

        // 注释走 pg_catalog：information_schema.columns 里没有注释字段。
        // objsubid = 0 是【表】的注释，> 0 是【列】的注释（此时 LEFT JOIN 到 pg_attribute 能拿到列名）。
        Map<String, String> tableComments = new LinkedHashMap<>();
        Map<String, String> columnComments = new LinkedHashMap<>();
        this.jdbc.query("""
                SELECT c.relname AS table_name,
                       a.attname  AS column_name,
                       d.description AS description
                FROM pg_description d
                JOIN pg_class c ON c.oid = d.objoid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid
                WHERE n.nspname = 'public'""",
                rs -> {
                    String table = rs.getString("table_name");
                    String column = rs.getString("column_name");
                    String description = rs.getString("description");
                    if (column == null) {
                        tableComments.put(table, description);
                    }
                    else {
                        columnComments.put(table + "." + column, description);
                    }
                });

        // ---- 主键 / 外键：走 pg_catalog，【不能】走 information_schema ----
        // 实跑踩到的：换成只读账号之后，`information_schema.table_constraints` 返回了 **0 条外键**。
        // 原因是 PG 文档里那句话 —— 它只显示「当前用户拥有、或持有 SELECT【以外】权限的表」上的约束。
        // 只读账号只有 SELECT，于是约束一条都看不见，注入给模型的表结构**悄悄少了 JOIN 线索**，
        // 而且不报错（26 列还在，因为 information_schema.columns 没有这个限制）。
        // ★ 系统目录（pg_catalog）对所有用户可读，这才是拿结构信息该走的路。
        // ★ 这也是「同一件事踩第二次」：迭代 18 拿列注释时，也是因为 information_schema 没有那个字段而转去 pg_catalog。
        Set<String> primaryKeys = new LinkedHashSet<>(this.jdbc.query("""
                SELECT c.relname || '.' || a.attname AS pk
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY(con.conkey)
                WHERE con.contype = 'p' AND n.nspname = 'public'""",
                (rs, rowNum) -> rs.getString("pk")));

        // 注意 conkey / confkey 是数组，多列外键时这个 join 会出组合行 —— 本项目的表都是单列外键。
        List<ForeignKey> foreignKeys = this.jdbc.query("""
                SELECT c.relname  AS table_name,
                       a.attname  AS column_name,
                       rc.relname AS ref_table,
                       ra.attname AS ref_column
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_class rc ON rc.oid = con.confrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY(con.conkey)
                JOIN pg_attribute ra ON ra.attrelid = con.confrelid AND ra.attnum = ANY(con.confkey)
                WHERE con.contype = 'f' AND n.nspname = 'public'
                ORDER BY c.relname, a.attname""",
                (rs, rowNum) -> new ForeignKey(
                        rs.getString("table_name"), rs.getString("column_name"),
                        rs.getString("ref_table"), rs.getString("ref_column")));

        // 主键数也打出来：这次「外键变 0」是靠这行日志看见的，主键同理，都摆在明面上
        log.info("      [Text2SQL] 元数据读取完成：{} 张表 / {} 列 / {} 个主键 / {} 条外键，耗时 {} ms",
                tables.size(), flatColumns.size(), primaryKeys.size(), foreignKeys.size(),
                System.currentTimeMillis() - t0);

        return new Schema(tables, columnsByTable, tableComments, columnComments, primaryKeys, foreignKeys);
    }

    /** 读列的时候带一份表名（列本身不需要表名，分组之后就不用了）。 */
    private record ColumnWithTable(String table, Column column) {
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return "?";
        }
        return raw.replace("character varying", "varchar")
                  .replace("timestamp without time zone", "timestamp")
                  .replace("timestamp with time zone", "timestamptz");
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private static String render(Schema schema, List<String> tables) {
        Map<String, String> fkTarget = new LinkedHashMap<>();
        for (ForeignKey fk : schema.foreignKeys()) {
            fkTarget.put(fk.table() + "." + fk.column(), fk.refTable() + "." + fk.refColumn());
        }

        StringBuilder sb = new StringBuilder();
        for (String table : tables) {
            sb.append("表 ").append(table);
            String tableComment = schema.tableComments().get(table);
            if (tableComment != null && !tableComment.isBlank()) {
                sb.append("  —— ").append(tableComment);
            }
            sb.append('\n');

            for (Column column : schema.columnsByTable().getOrDefault(table, List.of())) {
                String full = table + "." + column.name();
                sb.append("  ").append(ConsoleText.padRight(column.name(), 16));
                sb.append(ConsoleText.padRight(column.type(), 24));
                if (schema.primaryKeys().contains(full)) {
                    sb.append("PK  ");
                }
                if (!column.nullable()) {
                    sb.append("NOT NULL  ");
                }
                if (column.defaultValue() != null) {
                    sb.append("DEFAULT ").append(column.defaultValue()).append("  ");
                }
                // 外键用「字段 -> 目标」的写法直接标在列上，模型一眼能看出 JOIN 路径
                String target = fkTarget.get(full);
                if (target != null) {
                    sb.append("-> ").append(target).append("  ");
                }
                String comment = schema.columnComments().get(full);
                if (comment != null && !comment.isBlank()) {
                    sb.append("-- ").append(comment);
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        if (!schema.foreignKeys().isEmpty()) {
            sb.append("外键关系（JOIN 用）：\n");
            for (ForeignKey fk : schema.foreignKeys()) {
                sb.append("  ").append(fk.table()).append('.').append(fk.column())
                  .append(" -> ").append(fk.refTable()).append('.').append(fk.refColumn())
                  .append('\n');
            }
        }
        return sb.toString();
    }
}
