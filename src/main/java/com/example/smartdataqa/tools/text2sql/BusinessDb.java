package com.example.smartdataqa.tools.text2sql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 迭代 18 · 业务库连接。
 *
 * <h3>为什么它是「一个包装类」，而不是直接注册一个 DataSource / JdbcTemplate bean</h3>
 *
 * <p>这是本轮最容易踩、又最不容易看出来的一个坑，值得写清楚：
 *
 * <p>项目里已经有一个自动配置出来的 {@code DataSource}（指向 5432 的 pgvector 库），
 * 它是 {@code PgVectorStore} 和那个自动配置的 {@code JdbcTemplate} 的唯一来源
 * （{@code HybridDocumentTool}、{@code CorpusIngestor} 都在用它）。
 *
 * <p><b>而 Spring Boot 的 {@code DataSourceAutoConfiguration} 上挂着
 * {@code @ConditionalOnMissingBean(DataSource.class)}</b>——只要容器里出现<b>任何一个</b>
 * DataSource bean，它整条自动配置就<b>让位</b>了。后果是：
 * <ul>
 *   <li>{@code PgVectorStore} 找不到数据源，起不来（迭代 14 之后它一启动就要连库建表）；</li>
 *   <li>自动配置的 {@code JdbcTemplate} 也没了，迭代 16 的混合检索跟着挂。</li>
 * </ul>
 * 同理，{@code JdbcTemplateAutoConfiguration} 挂着 {@code @ConditionalOnMissingBean(JdbcOperations.class)}，
 * 所以<b>也不能把业务库的 JdbcTemplate 注册成 bean</b>——那样两个 {@code JdbcTemplate} 都没了 @Primary，
 * 现有注入点会直接变成「候选不唯一」而启动失败；给业务库那个标 @Primary 更糟，
 * 等于让 {@code CorpusIngestor} 往业务库里写向量。
 *
 * <p>所以做法是：<b>把业务库的数据源和 JdbcTemplate 关在这个类里，只暴露「一个叫 BusinessDb 的东西」。</b>
 * 它不是一个 DataSource，自动配置该干嘛干嘛；需要业务库的地方注入 {@code BusinessDb} 即可。
 *
 * <p>顺带解决的另一件事：<b>懒连接</b>。{@code DriverManagerDataSource} 构造时<b>不建立连接</b>，
 * 所以即使业务库没起，应用照样能启动——只是业务支路真正查库时才失败。
 * 这一点很重要：否则 迭代 18 之后「只跑 RAG 的人」会被迫也把业务库开着。
 *
 * <h3>迭代 21 · 行数上限【不放】在这一层（这是实跑打脸改过来的）</h3>
 * 我一开始把 {@code setMaxRows} 设在这个 JdbcTemplate 上，想着「上限要防内存，就该在最底层」。
 * 实跑立刻暴露了后果：<b>它把【元数据查询也一起截断了】</b>——
 * 演示里把上限调成 5 之后，SchemaRenderer 读到的列数变成了「5 列」（本该 26 列），
 * 于是<b>注入给模型的表结构悄悄少了 21 列，而且不报错</b>。
 *
 * <p>根因：元数据查询（查 information_schema）和「模型生成的查询」走的是同一个 JdbcTemplate，
 * 但这两件事要的上限完全不同——前者是<b>我们自己的小查询</b>，一个都不能少；
 * 后者是<b>模型写的东西</b>，才需要防跑飞。
 *
 * <p>所以上限搬到了 {@code Text2SqlDatabaseTool.Options.maxRows}，
 * 由工具在执行【模型那条 SQL】时用 {@code PreparedStatement.setMaxRows} 单独设。
 * <b>结论：安全措施配错了作用域，自己就会变成一个「不响的错」。</b>
 *
 * <p>它和 {@code sdaq.text2sql.max-context-rows} 仍是两个不同的上限，别混：
 * <pre>
 *   text2sql.max-rows           执行上限（语句级截断，防内存、防跑飞）
 *   text2sql.max-context-rows   进 prompt 的上限（防上下文爆）
 * </pre>
 *
 * <p><b>它是个开发用的简化实现</b>：没有连接池（每次查询新建连接，查完就关）。
 * 生产要换成 HikariCP，但那会引入生命周期管理（要 close），
 * 对「本轮先让 Text2SQL 跑起来」这个目标不划算。
 */
public final class BusinessDb {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param url                 业务库 JDBC URL（默认 5433）
     * @param username            用户名
     * @param password            密码
     * @param queryTimeoutSeconds 单条查询的超时秒数。<b>超时是安全边界的一部分</b>（迭代 21）：
     *                            一条写坏的 SQL 不该把连接占住不放。
     *                            注意超时和行数上限不一样——<b>超时留在这一层是对的</b>，
     *                            因为「某条语句跑太久」对元数据查询也是有害的；
     *                            而行数上限不能留在这（见类注释）。
     */
    public BusinessDb(String url, String username, String password, int queryTimeoutSeconds) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        // 显式指定驱动，不依赖 SPI 自动注册 —— 少了这一行，某些环境下会报 "No suitable driver"
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(queryTimeoutSeconds);
        this.jdbcTemplate = template;
    }

    /** 业务库的 JdbcTemplate。约定：<b>只在这里读表结构和跑模型生成的查询</b>。 */
    public JdbcTemplate jdbc() {
        return this.jdbcTemplate;
    }
}
