package com.example.smartdataqa.agent.tool;

/**
 * 迭代 5 · 一个工具的自描述。
 *
 * <p>注意它比 迭代 2/3 里的工具多了一样东西：{@link #risk()}。
 * 这是「权限门」的基础——工具自己声明它能造成多大破坏，
 * 由上层策略决定放不放行。
 *
 * <p><b>为什么要有这个设计？</b>因为一旦 Agent 能执行真实操作，
 * 最大的风险不是它答错，而是它<b>做错事</b>：删了数据、发了消息、
 * 改了状态，而这些往往不可逆。所以每个工具都要事先说清自己的危险级别，
 * 而不是等出事了再补。
 *
 * <p>迭代 21 做 SQL 安全边界时，你只要把 SQL 工具注册成 {@code DESTRUCTIVE}，
 * 本轮这套机制直接就能用。
 */
public interface AgentTool {

    /**
     * 工具的风险级别。三级，对应三种处置方式。
     */
    enum Risk {

        /** 只读：不改变任何数据，可以自动执行。 */
        READ_ONLY("只读", "不改变数据，自动放行"),

        /** 写入：会修改数据，执行前需要确认。 */
        WRITE("写入", "会修改数据，需要人工确认"),

        /** 破坏性：可能造成不可逆损失，直接拒绝。 */
        DESTRUCTIVE("破坏性", "不可逆操作，策略直接阻断");

        private final String label;
        private final String note;

        Risk(String label, String note) {
            this.label = label;
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String note() {
            return note;
        }
    }

    /** 工具名。模型靠这个名字来「点单」。 */
    String name();

    /**
     * 工具说明。<b>这不是注释，是提示词</b>——
     * 模型靠它判断「这个问题该不该调我」。写得含糊，模型就选不准。
     * 迭代 8 设计路由时这条会变得很关键。
     */
    String description();

    /** 危险级别。 */
    Risk risk();

    /** 参数的 JSON Schema，会原样放进给模型的工具定义里。 */
    String parametersJson();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型给的参数（JSON 字符串）
     * @return 给模型看的结果文本
     */
    String execute(String argumentsJson);
}
