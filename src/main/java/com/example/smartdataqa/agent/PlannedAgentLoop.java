package com.example.smartdataqa.agent;

import com.example.smartdataqa.agent.plan.Plan;
import com.example.smartdataqa.agent.tool.AgentTool;
import com.example.smartdataqa.agent.tool.ToolRegistry;
import com.example.smartdataqa.context.ContextCompactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 5 · Harness 工程：计划 + 权限门。
 *
 * <p>阶段一的收口日。本轮给循环加上两层工程约束，
 * 它们回答的是同一个问题的两面：<b>怎么让一个会自主决策的东西变得可控。</b>
 *
 * <h3>第一层：计划（先想清楚再动手）</h3>
 * 动手之前先让模型列出步骤，之后每一轮把「当前计划 + 完成进度」注入 system prompt。
 * 好处有两个：动手前被迫想一遍全局；中途回头看得到还剩什么。
 *
 * <h3>第二层：权限门（按风险分级放行）</h3>
 * 每个工具自报风险级别，执行前统一过一道门：
 * <pre>
 *   只读   → 自动放行
 *   写入   → 询问用户（演示里模拟成同意）
 *   破坏性 → 直接阻断
 * </pre>
 *
 * <h3>为什么拒绝要走回循环里</h3>
 * 这是本轮最关键的设计。被拒绝时，<b>拒绝理由会作为工具结果回灌给模型</b>，
 * 所以模型知道这条路不通，会转向替代方案。
 *
 * <p>本演示的任务是「删掉 8 月已退款订单」，模型会先查、再试图删。
 * 删除工具是破坏性的、被阻断；模型拿到阻断理由后，<b>应该改用归档工具</b>。
 * 你能在日志里看到 Agent 的执行路径被权限门<b>真实地改变</b>了——
 * 不是打印一行日志装作拦住了，而是它的后续行为真的不一样了。
 *
 * <p>开启方式：
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--sdaq.exp5.enabled=true"
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sdaq.exp5.enabled", havingValue = "true")
public class PlannedAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(PlannedAgentLoop.class);

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen-plus";
    private static final int MAX_STEPS = 8;

    /**
     * 故意设得小，好让压缩在演示中真的触发。
     *
     * <p>注意这两个值的搭配有讲究：压缩要生效，必须「超过 maxMessages」且
     * 「中间那段至少有 4 条」。所以 maxMessages 至少要是 keepRecent + 5。
     * 一开始我把 maxMessages 设成 10，结果对话只到 6 条，压缩压根没触发。
     */
    private static final int MAX_MESSAGES = 8;
    private static final int KEEP_RECENT = 4;

    private static final String USER_TASK =
            "帮我清理一下 2026 年 8 月已经退款完成的订单——直接从系统里删掉。";

    /**
     * 脚本化的多轮用户输入。
     *
     * <p>第二条是模拟「模型请求确认之后，用户点了同意」。
     *
     * <p><b>为什么要加这一条：</b>模型被阻断后<b>只在文字里问「是否确认归档」，
     * 没有真的调工具</b>——这其实是更安全、更正确的行为（先问再做），
     * 但结果是写入类的「询问」路径没被跑到。加一条确认输入，才能把 WRITE 级别
     * 的审批也走一遍。真实产品里这条来自界面，演示里写死。
     */
    private static final List<String> USER_TURNS = List.of(
            USER_TASK,
            "确认归档，请执行。");

    private static final String BASE_SYSTEM_PROMPT = """
            你是一个数据运维助手，通过调用工具来完成任务。

            必须遵守的规则：
            1. 需要数据时一定要调用工具获取，绝不要凭记忆或猜测编造数据。
            2. 每完成计划中的一步，在回答里单独用一行写：STEP_DONE: 步骤编号
            3. 如果某个工具调用被拒绝，不要重试它。改为向用户说明原因，
               并从可用工具中选一个可行的替代方案。
            4. 回答简洁，不要长篇大论。
            """;

    /** 匹配 STEP_DONE: 2 这种标记。 */
    private static final Pattern STEP_DONE = Pattern.compile("STEP_DONE\\s*[:：]\\s*(\\d{1,2})");

    // ==================================================================
    // 三个工具，三个风险级别
    // ==================================================================

    /** 只读：查订单。 */
    static class QueryOrdersTool implements AgentTool {
        @Override
        public String name() {
            return "query_orders";
        }

        @Override
        public String description() {
            return "查询指定月份处于指定状态的订单，返回订单号列表。";
        }

        @Override
        public Risk risk() {
            return Risk.READ_ONLY;
        }

        @Override
        public String parametersJson() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "month": { "type": "string", "description": "月份，格式 2026-08" },
                        "status": { "type": "string", "description": "订单状态，例如 已退款" }
                      },
                      "required": ["month", "status"]
                    }
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            // 假数据。迭代 5 只关心权限和计划，不关心数据真不真。
            return "2026-08 状态为「已退款」的订单共 3 笔：A1001、A1002、A1003";
        }
    }

    /** 写入：归档订单。这是删除被拒后的替代方案。 */
    static class ArchiveOrdersTool implements AgentTool {
        @Override
        public String name() {
            return "archive_orders";
        }

        @Override
        public String description() {
            return "把订单标记为已归档。数据仍然保留，只是从常规列表中移出，可以恢复。";
        }

        @Override
        public Risk risk() {
            return Risk.WRITE;
        }

        @Override
        public String parametersJson() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "orderIds": { "type": "string", "description": "订单号，多个用逗号分隔" }
                      },
                      "required": ["orderIds"]
                    }
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            return "已将 3 笔订单标记为已归档（可恢复）：A1001、A1002、A1003";
        }
    }

    /** 破坏性：永久删除。 */
    static class DeleteOrdersTool implements AgentTool {
        @Override
        public String name() {
            return "delete_orders";
        }

        @Override
        public String description() {
            return "永久删除订单数据。不可恢复。";
        }

        @Override
        public Risk risk() {
            return Risk.DESTRUCTIVE;
        }

        @Override
        public String parametersJson() {
            return """
                    {
                      "type": "object",
                      "properties": {
                        "orderIds": { "type": "string", "description": "要删除的订单号" }
                      },
                      "required": ["orderIds"]
                    }
                    """;
        }

        @Override
        public String execute(String argumentsJson) {
            // 正常情况下走不到这里——权限门会先把它挡住
            return "订单已永久删除。（注意：如果你看到这行，说明权限门失效了）";
        }
    }

    // ==================================================================

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.create();

    private final List<String> permissionTrail = new ArrayList<>();

    @Bean
    ApplicationRunner exp5PlannedLoop() {
        return args -> {
            // ---------- 组装 ----------
            ToolRegistry registry = new ToolRegistry((name, toolArgs) -> {
                log.info("  [审批] 请求执行写入类工具 {}，参数 {}", name, toolArgs);
                log.info("  [审批] （真实产品会在这里弹确认框；本演示模拟用户点了「同意」）");
                return true;
            });
            registry.register(new QueryOrdersTool());
            registry.register(new ArchiveOrdersTool());
            registry.register(new DeleteOrdersTool());

            log.info("========== 迭代 5 计划 + 权限门 ==========");
            log.info("任务：{}", USER_TASK);
            log.info("");
            log.info("可用工具及其风险级别：");
            for (AgentTool t : registry.all()) {
                // 用 String.format 先对齐再当参数传——SLF4J 的 {} 不支持对齐语法
                log.info("  {} [{}] {}",
                        String.format("%-18s", t.name()), t.risk().label(), t.risk().note());
            }

            String toolsJson = registry.toolsJson();

            // ---------- 第一阶段：先出计划 ----------
            log.info("");
            log.info("=========== 第一阶段：让模型先列计划 ===========");
            Plan plan = generatePlan(registry);
            if (plan.isEmpty()) {
                log.warn("没能解析出计划（模型没按格式输出）。继续执行，但本轮就看不到计划的作用了。");
            }
            else {
                log.info("解析到 {} 步计划：", plan.total());
                for (Plan.Step s : plan.steps()) {
                    log.info("  {}. {}", s.index(), s.description());
                }
            }

            // ---------- 第二阶段：带着计划和权限门执行 ----------
            log.info("");
            log.info("=========== 第二阶段：执行（计划每轮注入 + 权限门把关）===========");
            log.info("本演示包含 {} 次用户输入（第二条是脚本化的确认）：", USER_TURNS.size());
            for (int i = 0; i < USER_TURNS.size(); i++) {
                log.info("  {}. {}", i + 1, USER_TURNS.get(i));
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            int turnIndex = 0;
            messages.add(msg("user", USER_TURNS.get(turnIndex)));

            int compactionCount = 0;
            String finalAnswer = "(未结束)";
            int totalRounds = MAX_STEPS * USER_TURNS.size();

            // 记录「确实成功执行过」的工具。计划进度校验依据的就是这个集合，
            // 而不是模型的自述——这是本次修掉的核心缺陷。
            Set<String> executedTools = new LinkedHashSet<>();

            for (int round = 1; round <= totalRounds; round++) {
                log.info("");
                log.info("--------------- 第 {} 轮 ---------------", round);

                // 压缩：注意计划不在消息里，压不到它
                if (messages.size() > MAX_MESSAGES) {
                    ContextCompactor compactor = new ContextCompactor(this::summarize, MAX_MESSAGES, KEEP_RECENT);
                    int before = messages.size();
                    List<Map<String, Object>> compacted = compactor.compact(messages);
                    if (compacted.size() < before) {
                        compactionCount++;
                        messages = compacted;
                        log.info("  >>> 第 {} 次压缩：{} 条 → {} 条。计划不在消息里，不受影响。",
                                compactionCount, before, messages.size());
                    }
                }

                // 关键：system prompt 每轮重新拼，把「最新计划 + 进度」注入进去
                String systemPrompt = BASE_SYSTEM_PROMPT + "\n\n" + plan.render();
                JsonNode message = callModel(systemPrompt, messages, toolsJson);

                String content = text(message, "content");
                messages.add(toAssistantMessage(message));

                // 看模型有没有报告某步完成。注意这里会拿「实际执行」校验，不是直接采信
                applyPlanClaims(plan, content, executedTools, round);

                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                    finalAnswer = content;
                    turnIndex++;
                    if (turnIndex >= USER_TURNS.size()) {
                        log.info("模型没有再要求调用工具，且没有更多用户输入 → 循环结束");
                        break;
                    }
                    // 模型停下来了，通常是在等用户回应。
                    // 真实产品里这时候就是把控制权交回给用户；演示里脚本化地给出下一条输入。
                    log.info("模型没要求调工具（在等用户回应）。脚本化注入下一条用户输入，继续。");
                    messages.add(msg("user", USER_TURNS.get(turnIndex)));
                    continue;
                }

                for (JsonNode call : toolCalls) {
                    String callId = call.path("id").asText();
                    String toolName = call.path("function").path("name").asText();
                    String callArgs = call.path("function").path("arguments").asText();

                    AgentTool tool = registry.get(toolName);
                    String result;

                    if (tool == null) {
                        result = "错误：没有名为 " + toolName + " 的工具。";
                        log.warn("  [工具] 模型点了一个不存在的工具：{}", toolName);
                    }
                    else {
                        // ★ 权限门：执行前先过这一关
                        ToolRegistry.Decision decision = registry.authorize(tool, callArgs);
                        permissionTrail.add(toolName + "[" + tool.risk().label() + "] → "
                                + (decision.allowed() ? "放行" : "阻断") + "：" + decision.reason());

                        if (decision.allowed()) {
                            log.info("  [工具] {} [{}] 放行 → 执行", toolName, tool.risk().label());
                            result = tool.execute(callArgs);
                            // 只有真的执行成功才记进来。计划进度校验依据的就是这个集合
                            executedTools.add(toolName);
                        }
                        else {
                            log.warn("  [工具] {} [{}] 阻断 → 理由回灌给模型", toolName, tool.risk().label());
                            // ★ 关键：拒绝理由回给模型，它才知道要换路
                            result = "工具调用被拒绝。" + decision.reason()
                                    + " 请不要重试这个工具；改为向用户说明原因，"
                                    + "并在可用工具中选择一个可行的替代方案。";
                        }
                    }

                    log.info("  [结果] {}", result);
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", result));
                }
            }

            // ---------- 收尾 ----------
            log.info("");
            log.info("=========== 结果 ===========");
            log.info("模型最终回答：");
            log.info("{}", finalAnswer);

            log.info("");
            log.info("--- 权限门决策记录 ---");
            if (permissionTrail.isEmpty()) {
                log.info("  （本轮没有工具调用经过权限门）");
            }
            else {
                for (String s : permissionTrail) {
                    log.info("  {}", s);
                }
            }

            log.info("");
            log.info("--- 计划执行情况（按实际执行校验）---");
            log.info("{}", plan.render());
            log.info("");
            if (plan.total() > 0 && plan.verifiedDoneCount() < plan.total()) {
                log.info("!!! 计划未全部完成：{}/{} 步通过校验。", plan.verifiedDoneCount(), plan.total());
                log.info("    这正是「拿实际执行对账」的效果——计划里说要删除，但删除被拦掉了，");
                log.info("    所以那一步不算数。只信模型自述的话，这里会显示全部完成。");
            }
            log.info("");
            log.info("消息被压缩了 {} 次。计划始终完整——因为它存在消息列表外面，"
                    + "每轮重新注入，压缩碰不到它。", compactionCount);
            log.info("这一点正是 迭代 4 学到的：必须准确的状态别放在消息列表里。");
            log.info("");
            log.info("重点看三件事：");
            log.info("  1. 删除被阻断后，模型是不是转向了归档（权限门真的改变了它的行为）");
            log.info("  2. 用户确认后，写入类工具的审批路径（WRITE -> 询问 -> 放行）有没有跑通");
            log.info("  3. 压缩发生后，计划里的步骤是不是一条没少");
        };
    }

    // ==================================================================

    /** 第一阶段：单独一次调用，只要计划。 */
    private Plan generatePlan(ToolRegistry registry) {
        StringBuilder toolList = new StringBuilder();
        for (AgentTool t : registry.all()) {
            toolList.append("- ").append(t.name())
                    .append(" [").append(t.risk().label()).append("] ")
                    .append(t.description()).append('\n');
        }

        String prompt = "你要为下面的任务先列一个执行计划。\n\n"
                + "可用工具：\n" + toolList + "\n"
                + "任务：" + USER_TASK + "\n\n"
                + "要求：只输出编号步骤，每行一步，格式为：\n"
                + "  编号. [工具名] 步骤描述\n"
                + "方括号里写这一步预期调用的工具名；如果某一步不需要工具"
                + "（比如核对、汇总、说明），就写 [-]。\n"
                + "步骤要具体可执行，控制在 2 到 5 步。不要输出任何其它内容。";

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("user", prompt));

        try {
            JsonNode message = callModel("你是一个任务规划助手。", messages, null);
            String out = text(message, "content");
            log.info("模型原始输出：");
            log.info("{}", out);
            return Plan.parse(out);
        }
        catch (Exception e) {
            log.error("生成计划失败：{}", e.getMessage());
            return new Plan();
        }
    }

    /**
     * 从模型回答里找 STEP_DONE 标记，并<b>校验之后</b>更新计划进度。
     *
     * <p>关键改动：不再直接采信模型的自述，而是拿「这一步声明的工具是否真的成功执行过」来核对。
     * 对不上就标成 {@code CLAIMED_BUT_NOT_EXECUTED}，并且不计入完成数。
     *
     * <p>这条规则来自实测踩坑：任务要求「删除订单」，删除被权限门拦掉、实际执行的是归档，
     * 模型却照样报告 {@code STEP_DONE: 3}——进度条显示 3/3，而删除一次都没发生。
     */
    private void applyPlanClaims(Plan plan, String content, Set<String> executedTools, int round) {
        Matcher m = STEP_DONE.matcher(content);
        boolean any = false;
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            Plan.Status st = plan.claimDone(idx, executedTools);
            any = true;
            if (st == Plan.Status.CLAIMED_BUT_NOT_EXECUTED) {
                log.warn("  [计划] 第 {} 步：模型自称完成，但它声明的工具没有成功执行过 → 不计入完成", idx);
            }
            else {
                log.info("  [计划] 第 {} 步 → {}", idx, st.label());
            }
        }
        if (!any && !plan.isEmpty()) {
            log.debug("  第 {} 轮没有 STEP_DONE 标记", round);
        }
    }

    /** 压缩用的摘要实现。迭代 5 不是重点，复用 迭代 4 的做法。 */
    private String summarize(List<Map<String, Object>> messagesToSummarize) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messagesToSummarize) {
            sb.append('[').append(m.get("role")).append("] ")
              .append(m.get("content")).append('\n');
        }
        List<Map<String, Object>> prompt = new ArrayList<>();
        prompt.add(msg("system", """
                把下面这段对话压缩成简洁摘要。只记录用户明确要求过的事、
                以及工具实际返回的结果。不要写你自己的建议。
                只输出摘要正文，不要前缀或解释。
                """));
        prompt.add(msg("user", sb.toString()));
        try {
            JsonNode message = callModel("你是摘要助手。", prompt, null);
            return text(message, "content");
        }
        catch (Exception e) {
            return null;
        }
    }

    private JsonNode callModel(String systemPrompt, List<Map<String, Object>> messages, String toolsJson)
            throws Exception {
        List<Map<String, Object>> full = new ArrayList<>();
        full.add(msg("system", systemPrompt));
        full.addAll(messages);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", full);
        if (toolsJson != null) {
            body.put("tools", mapper.readTree(toolsJson));
        }

        String raw = http.post()
                .uri(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        return mapper.readTree(raw).path("choices").path(0).path("message");
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * 模型这一轮的消息 → 可以塞回消息列表的 Map。
     *
     * <p>显式只带上该带的字段，而不是把服务端返回的对象原样转过去——
     * 原样回传可能把只该出现在响应里的字段也发回去，部分服务端会报 400。
     */
    private Map<String, Object> toAssistantMessage(JsonNode message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "assistant");
        out.put("content", text(message, "content"));

        // tool_calls 必须原样带上，否则后面的 role=tool 消息会「找不到对应的请求」
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray() && !calls.isEmpty()) {
            out.put("tool_calls", mapper.convertValue(calls, List.class));
        }
        return out;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? "" : v.asText();
    }
}
