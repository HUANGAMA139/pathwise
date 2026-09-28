# 迭代 10 · 把项目接入 MCP：外部客户端怎么连

> 这份文档讲的是「怎么让别人用上你的能力」，是 README 里那句
> 「别人可以直接接入你的数据问答能力」的操作说明。

---

## 暴露了什么

项目启动后，MCP Server 监听：

```
http://localhost:8080/mcp
```

暴露三个工具，分两个层次：

| 工具名 | 层次 | 用途 |
|---|---|---|
| `ask_data_question` | 高层 | 完整问答。给一句话，路由+检索+查库+综合全包，返回答案并附带路由决策 |
| `search_documents` | 低层 | 只做文档语义检索，返回片段原文及出处，不做总结 |
| `query_database` | 低层 | 只做数据库查询，返回结果**和实际执行的 SQL** |

两个层次都对外，是因为调用方的需求不同：
想直接用就用高层，想自己编排（比如拿你的检索结果去喂它自己的模型）就用低层。

---

## 第一步：确认 Server 起来了

```powershell
cd D:\smart-data-qa
mvn spring-boot:run
```

启动日志里应该能看到 MCP 相关的行。**如果没看到，先确认这两件事**：

1. `pom.xml` 里有没有 `spring-ai-starter-mcp-server-webmvc`
2. `application.yml` 里 `spring.ai.mcp.server` 那段在不在

再确认 HTTP 端点活着：

```powershell
curl http://localhost:8080/mcp
```

MCP 的 Streamable HTTP 端点不接受裸 GET，**返回 4xx 是正常的**——
这说明「端点存在但请求格式不对」。真正要警惕的是**连不上**（connection refused），
那说明 Server 没起来或者端口不对。

---

## 第二步：用 MCP Inspector 测一下（推荐先做这个）

MCP Inspector 是官方提供的调试工具，能用图形界面列出你的工具、手动调一次看返回。

```powershell
npx @modelcontextprotocol/inspector
```

打开它给的地址后：

- **Transport** 选 `Streamable HTTP`
- **URL** 填 `http://localhost:8080/mcp`
- 点 Connect
- 左侧能看到三个工具，选中一个填参数，点 Run

**这一步值得认真做**，因为它是第一次「有外部客户端真的用上了你的能力」。
而且 Inspector 会把你工具的 JSON Schema 展示出来——你能看到
`@McpTool(description=...)` 那段文字到底变成了什么样子给外面看。

> 如果 `npx` 慢或者连不上 npm，可以跳过这步直接做第三步。

---

## 第三步：接到 Claude Desktop（或其他 MCP 客户端）

MCP 的标准客户端配置是一个 JSON，核心是「起个名字 + 填 URL」。大致长这样：

```json
{
  "mcpServers": {
    "smart-data-qa": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

**放在哪个文件、字段名具体叫什么，随客户端版本变过。**
以你装的那个版本的官方文档为准——这里只说明「要填的就是 URL 这一件事」。

配置好之后重启客户端，它应该能在工具列表里看到
`ask_data_question` / `search_documents` / `query_database`。

然后就可以直接问它：

> 上季度华东区销售额是多少？顺便说下口径怎么定的

**注意会发生什么**：客户端的模型读到你的工具描述，判断该调 `ask_data_question`，
把问题传过来，你的系统做完路由和检索，把答案返回去。
**所以这一次有两次模型调用叠加**——客户端一次，你这边一次。

---

## 一个和 迭代 6/8 一脉相承的要点

**`@McpTool(description = ...)` 里的文字，会成为调用方那个模型的提示词。**

对方模型是靠读这段描述来决定「这个问题要不要调你的工具」。
写得含糊，外面就调不准——和 迭代 8 那两个工具的 description 是同一个道理，
**只是读它的人从你自己项目的模型，换成了别人的模型。**

这也是 MCP 的隐藏成本：工具描述变成了跨系统契约，改它要谨慎。

---

## 面试可以怎么讲

MCP 现在是高频问题。有实操的话可以这样答，比背定义实得多：

> 我把项目的两条检索支路和一个完整问答能力都暴露成了 MCP Server，
> 用 Spring AI 的 MCP 注解实现，传输用 Streamable HTTP。
> 三个工具分两个层次——高层一个直接给答案，低层两个给原始材料，
> 让调用方自己选是想一步到位还是想自己编排。
>
> 一个具体的体会是：**MCP 把工具描述从「项目内部的提示词」变成了「跨系统的契约」**。
> 写描述的时候不只要考虑自己项目里的模型能不能读懂，
> 还要考虑别人系统里的模型读到它会怎么判断。这个和写 API 文档不一样，
> 因为读它的是模型，不是人。

---

## 常见问题

**Q：Server 起来了，但外部客户端连不上？**
先确认 `http://localhost:8080/mcp` 这个端口没被别的程序占用，
再确认客户端填的是完整 URL（带 `/mcp`），不是只填 8080。

**Q：能连上但工具列表是空的？**
检查工具类是不是 Spring 管理的 bean（要 `@Service` 或 `@Component`），
以及方法上是不是 `@McpTool` 而不是 Spring AI 那个 `@Tool`。
**这是两个不同的注解**——`@Tool` 是给自己项目内的模型用的，
`@McpTool`（来自 `org.springaicommunity.mcp.annotation`）才是对外暴露的。

**Q：为什么用 webmvc 版而不是 webflux 版？**
本项目是 servlet 技术栈（迭代 1 加 `spring-boot-starter-web` 就是为了让
Spring Boot 判定成 SERVLET 而不是响应式应用）。用 webmvc 版和现有栈一致，
不需要为此引入第二套 Web 编程模型。
