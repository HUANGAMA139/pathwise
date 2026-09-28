package com.example.smartdataqa.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 5 · 工具注册表 + 权限门。
 *
 * <p>两个职责合在一个类里：
 * <ol>
 *   <li><b>注册表</b>：保存所有可用工具，并生成给模型看的 tools 定义。</li>
 *   <li><b>权限门</b>：工具执行前，按它的风险级别决定放行、询问还是拒绝。</li>
 * </ol>
 *
 * <h3>权限门是干什么的</h3>
 * Agent 和普通程序最关键的差别：<b>它的动作是运行时由模型决定的，你写代码时
 * 不知道它下一步要干什么。</b> 这意味着你不能靠「代码里没写就不会发生」来保证安全。
 *
 * <p>所以安全边界必须建在<b>执行入口</b>上——所有工具调用都得先过这道门，
 * 按风险级别分别处置：只读放行、写入询问、破坏性拒绝。
 *
 * <h3>为什么拒绝理由要回灌给模型</h3>
 * {@link Decision} 里那个 reason 不只是给人看的日志。
 * 被拒绝时要把理由塞回消息列表，让模型<b>知道这条路走不通</b>，
 * 它才有机会给你一个替代方案（「不能删，但我可以帮你标记归档」）。
 * 如果不回灌，模型会以为工具执行成功了，接着往下编——那又是 迭代 4 见过的「编造过程」。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 权限判定结果。
     *
     * @param allowed 是否放行
     * @param reason  理由。<b>不只是日志</b>——被拒时这段文字会回灌给模型。
     */
    public record Decision(boolean allowed, String reason) {
    }

    /**
     * 人工审批的抽象。写入类操作要经过它。
     *
     * <p>在真实产品里，这里会弹一个确认框、或者在 IM 里等人回复。
     * 在本演示里由代码模拟，方便无人值守地跑。
     */
    @FunctionalInterface
    public interface Approver {
        boolean approve(String toolName, String argumentsJson);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Approver approver;

    public ToolRegistry(Approver approver) {
        this.approver = approver;
    }

    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    /** 生成给模型的 tools 定义（OpenAI 格式）。 */
    public String toolsJson() {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (AgentTool t : tools.values()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            try {
                fn.put("parameters", mapper.readTree(t.parametersJson()));
            }
            catch (Exception e) {
                // 工具的 Schema 写错了要当场炸掉，不要留到运行时才发现
                throw new IllegalStateException("工具 " + t.name() + " 的 parametersJson 不是合法 JSON", e);
            }
            arr.add(Map.of("type", "function", "function", fn));
        }
        try {
            return mapper.writeValueAsString(arr);
        }
        catch (Exception e) {
            throw new IllegalStateException("生成 tools JSON 失败", e);
        }
    }

    /**
     * 权限门的核心：按风险级别处置。
     *
     * <p>三级对应三种策略，这是最简单可用的一套。真实产品里还会更细，
     * 比如「只读但涉及敏感字段也要确认」「同一次会话里同类写入只问一次」。
     */
    public Decision authorize(AgentTool tool, String argumentsJson) {
        return switch (tool.risk()) {
            case READ_ONLY -> new Decision(true, "只读操作");

            case WRITE -> {
                boolean ok = approver.approve(tool.name(), argumentsJson);
                yield ok
                        ? new Decision(true, "用户已确认")
                        : new Decision(false, "用户拒绝了这次写入操作");
            }

            case DESTRUCTIVE -> {
                log.warn("  [权限] 阻断破坏性操作：{}", tool.name());
                yield new Decision(false,
                        "该工具属于「" + tool.risk().label() + "」级别，"
                                + "策略不允许执行不可逆操作。");
            }
        };
    }
}
