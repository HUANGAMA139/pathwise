package com.example.smartdataqa.mcp;

import com.example.smartdataqa.agent.SmartQaAgent;
import com.example.smartdataqa.service.QaService;
import com.example.smartdataqa.tools.Chunk;
import com.example.smartdataqa.tools.DatabaseTool;
import com.example.smartdataqa.tools.DocumentTool;
import com.example.smartdataqa.tools.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 迭代 10 · 把项目的工具暴露成 MCP Server。
 *
 * <h3>MCP 解决什么问题</h3>
 * 一句话：<b>让工具可以插拔。</b>
 *
 * <p>在此之前，你的工具只能被自己项目里的代码调用。
 * 暴露成 MCP Server 之后，任何支持 MCP 的客户端（Claude Desktop、IDE 插件、
 * 别人的 Agent 系统）都能直接连上来用——<b>不需要看你的代码，不需要你的 SDK</b>。
 *
 * <p>它标准化的是三件事：工具怎么被发现、怎么被调用、结果怎么返回。
 * 类比一下：这是工具界的 USB 接口。以前每个外设都要专门的驱动，
 * 现在插上就能用。
 *
 * <h3>这里暴露了三个工具，分两个层次</h3>
 * <ul>
 *   <li><b>低层两个</b>（{@code search_documents} / {@code query_database}）——
 *       让别人把你的检索和查库能力当积木用。</li>
 *   <li><b>高层一个</b>（{@code ask_data_question}）——把整个问答能力
 *       （含路由决策）作为一个工具暴露出去。</li>
 * </ul>
 * 两种层次都合理，取决于调用方想自己编排还是想直接用。
 *
 * <h3>一个和 迭代 6/8 一脉相承的要点</h3>
 * <b>工具描述会成为调用方那个模型的提示词。</b>
 * 对方模型是靠读这里的 {@code description} 来决定要不要调你的工具。
 * 写含糊了，外面就没人调得准——和 迭代 8 路由那两个工具的 description 是同一个道理，
 * 只是这次读它的人换成了别的系统的模型。
 */
@Service
public class SmartDataQaMcpTools {

    private static final Logger log = LoggerFactory.getLogger(SmartDataQaMcpTools.class);

    /** 文档检索返回几段。 */
    private static final int TOP_K = 3;

    private final DocumentTool documentTool;
    private final DatabaseTool databaseTool;

    /**
     * <b>迭代 29 起改为依赖服务层，而不是直接依赖 Agent。</b>
     * 这样一来，MCP 这条对外路径也就自动带上了答案缓存和完整轨迹；
     * 而评测路径（直接调 {@code SmartQaAgent}）依旧绕开缓存——
     * 这正是把缓存摆在服务层而不是塞进 Agent 的收益。
     */
    private final QaService qa;

    public SmartDataQaMcpTools(DocumentTool documentTool,
                               DatabaseTool databaseTool,
                               QaService qa) {
        this.documentTool = documentTool;
        this.databaseTool = databaseTool;
        this.qa = qa;
    }

    // ==================================================================
    // 高层：整个问答能力
    // ==================================================================

    /**
     * 完整问答。
     *
     * <p>调用方只需要给一句话，路由、检索、查库、综合都由这边完成。
     * 返回值里带上路由决策，让调用方知道这次答案是怎么来的——
     * <b>外部系统同样需要「可追溯」</b>，否则它没法判断该不该信这个答案。
     *
     * <p><b>迭代 29：返回值补上了完整轨迹。</b>
     * 原来只回「路由决策 + 答案」——外部系统看到的是一个孤立结论，
     * SQL 是什么、检索到哪几段，全都留在服务端的日志里。
     * 现在整条链路随响应一起回去（{@code Trace#render()}），对方才有东西可核对。
     */
    @McpTool(name = "ask_data_question",
            description = "回答关于销售数据的业务问题。适用于两类问题："
                    + "一是需要具体数字的（如某区域某季度销售额、排名、同比环比），"
                    + "二是需要口径或规则的（如销售额怎么算、区域怎么划分、退货流程）。"
                    + "系统会自动判断该查业务数据库还是制度文档，必要时两边都查。"
                    + "返回内容包含路由决策和完整执行轨迹（检索/查询/各步耗时），可核对。"
                    + "如果只想知道某个数字或只想看原始文档片段，用更底层的两个工具更直接。")
    public String askDataQuestion(
            @McpToolParam(description = "用户的完整问题，用自然语言描述即可", required = true)
            String question) {

        log.info("[MCP] ask_data_question：{}", question);
        try {
            QaService.Served served = qa.ask(question);
            SmartQaAgent.Answer answer = served.answer();
            return "【路由决策】" + answer.decision().brief()
                    + "（理由：" + answer.decision().reason() + "）\n\n"
                    + answer.text() + "\n\n"
                    + "【本次轨迹】\n"
                    + (served.trace() == null ? "（无）" : served.trace().render());
        }
        catch (Exception e) {
            log.warn("[MCP] ask_data_question 失败：{}", e.getMessage());
            return "回答失败：" + e.getMessage();
        }
    }

    // ==================================================================
    // 低层：两条支路各自暴露
    // ==================================================================

    /** 只要文档片段，不要综合答案——适合调用方想自己组织材料的场景。 */
    @McpTool(name = "search_documents",
            description = "在销售制度文档里做语义检索，返回相关片段原文及出处。"
                    + "适用于查询统计口径、区域划分规则、售后流程、数据刷新频率这类"
                    + "写在制度里的内容。返回的是文档原文片段，不做总结归纳。")
    public String searchDocuments(
            @McpToolParam(description = "检索用的查询语句，尽量具体", required = true)
            String query) {

        log.info("[MCP] search_documents：{}", query);
        try {
            List<Chunk> chunks = documentTool.search(query, TOP_K);
            if (chunks.isEmpty()) {
                return "没有检索到相关文档片段。";
            }
            StringBuilder sb = new StringBuilder("检索到 " + chunks.size() + " 个片段：\n\n");
            for (Chunk c : chunks) {
                sb.append(c.render()).append("\n\n");
            }
            return sb.toString();
        }
        catch (Exception e) {
            log.warn("[MCP] search_documents 失败：{}", e.getMessage());
            return "检索失败：" + e.getMessage();
        }
    }

    /**
     * 只要数据库结果，不要综合答案。
     *
     * <p><b>返回值里一定带上实际执行的 SQL。</b>这不是为了好看——
     * 外部系统拿到一个数字时，需要能核实它是怎么来的。
     * 这条原则从 迭代 8 定下 {@code QueryResult} 契约时就立住了。
     */
    @McpTool(name = "query_database",
            description = "查询业务数据库中与销售相关的数据，返回查询结果和实际执行的 SQL。"
                    + "适用于需要具体数值的问题：某区域某季度的销售额、各区域排名、"
                    + "金额汇总等。不适用于询问定义或口径这类问题——那些应该用文档检索。")
    public String queryDatabase(
            @McpToolParam(description = "要查询的业务问题，用自然语言描述", required = true)
            String question) {

        log.info("[MCP] query_database：{}", question);
        try {
            QueryResult result = databaseTool.query(question);
            if (result.rowCount() == 0) {
                return "查询执行了，但没有返回数据。\n\n执行的 SQL：\n" + result.sql();
            }
            return result.render();
        }
        catch (Exception e) {
            log.warn("[MCP] query_database 失败：{}", e.getMessage());
            return "查询失败：" + e.getMessage();
        }
    }
}
