package com.example.smartdataqa.agent.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 5 · 计划。让 Agent 先列步骤，再动手。
 *
 * <p>来自 learn-claude-code 的 s05：<b>没有计划的 agent 走哪算哪。</b>
 * 先让它把步骤写出来，完成率会明显提升——因为模型在动手前被迫想了一遍全局，
 * 而且中途回头看得到「还剩什么没做」。
 *
 * <h3>计划格式：带上工具名，让它可校验</h3>
 * 每行写成：
 * <pre>
 *   1. [query_orders] 查询 2026-08 已退款订单
 *   2. [-] 核对数量与订单号是否符合预期
 *   3. [delete_orders] 永久删除这些订单
 * </pre>
 * 方括号里是这一步预期用的工具，不需要工具就写 {@code -}。
 *
 * <p><b>为什么非要写工具名？</b>这是本类最重要的一处设计。看下面的「状态」一节。
 *
 * <h3>三种完成状态（而不是「完成 / 未完成」两种）</h3>
 * <ul>
 *   <li>{@link Status#DONE} —— 模型说完成了，<b>并且</b>这一步声明的工具确实成功执行过。
 *       这是唯一可信的「完成」。</li>
 *   <li>{@link Status#DONE_UNVERIFIED} —— 模型说完成了，但这一步没声明工具（比如「核对」），
 *       没法校验，只能采信。诚实标注，不假装它被验证过。</li>
 *   <li>{@link Status#CLAIMED_BUT_NOT_EXECUTED} —— <b>模型说完成了，可它声明的工具没跑成。</b>
 *       这就是「进度条骗人」那个坑。</li>
 * </ul>
 *
 * <h3>为什么必须区分第三种</h3>
 * 实测踩过：任务要求「删除订单」，删除工具被权限门拦掉了、实际执行的是归档，
 * 但模型照样报告 {@code STEP_DONE: 3}，于是计划显示 3/3 完成。
 * <b>删除一次都没发生，进度却是满的。</b>
 *
 * <p>根因是进度只采信模型的自述。所以现在改成：<b>声明了工具名的步骤，
 * 一律拿「该工具是否真的执行过」来核对；对不上就不算完成。</b>
 *
 * <p>这条原则可以直接搬到你的正式项目上：模型说「我已查询数据库」不算数，
 * 要拿真实的执行痕迹（SQL 是否真的跑了）来对。
 */
public class Plan {

    /** 一步的完成状态。 */
    public enum Status {
        PENDING("待执行", " "),
        DONE("已完成", "x"),
        DONE_UNVERIFIED("已完成(未经校验)", "?"),
        CLAIMED_BUT_NOT_EXECUTED("自述完成但工具未执行", "!");

        private final String label;
        private final String marker;

        Status(String label, String marker) {
            this.label = label;
            this.marker = marker;
        }

        public String label() {
            return label;
        }

        public String marker() {
            return marker;
        }
    }

    /**
     * 一步。
     *
     * @param expectedTool 这一步预期使用的工具名；不需要工具时为 null
     */
    public record Step(int index, String description, String expectedTool) {
    }

    /** 匹配「1. [tool] 描述」或「1. 描述」。工具名可选。 */
    private static final Pattern STEP_LINE =
            Pattern.compile("^\\s*(\\d{1,2})\\s*[.、)]\\s*(?:\\[([^\\]]*)\\])?\\s*(.+?)\\s*$");

    private final List<Step> steps = new ArrayList<>();
    private final Map<Integer, Status> statuses = new LinkedHashMap<>();

    /** 从模型输出里解析计划。解析不出来就返回空计划（模型不守格式是常态）。 */
    public static Plan parse(String modelOutput) {
        Plan plan = new Plan();
        if (modelOutput == null) {
            return plan;
        }
        for (String line : modelOutput.split("\\R")) {
            Matcher m = STEP_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int idx = Integer.parseInt(m.group(1));
            // 只接受连续编号，防止把正文里的数字误当成步骤
            if (idx != plan.steps.size() + 1) {
                continue;
            }
            String toolRaw = m.group(2);
            // 剥掉 markdown 标记。模型常写成「**查询**订单」，中间那对星号也得去掉
            String desc = m.group(3).replace("*", "").replace("#", "").trim();
            if (desc.isEmpty()) {
                continue;
            }
            String tool = (toolRaw == null || toolRaw.isBlank() || "-".equals(toolRaw.trim()))
                    ? null
                    : toolRaw.trim().replace("*", "");
            plan.steps.add(new Step(idx, desc, tool));
            plan.statuses.put(idx, Status.PENDING);
        }
        return plan;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int total() {
        return steps.size();
    }

    /** 只有校验通过的才算真完成。自述完成但工具没跑的，不计入。 */
    public int verifiedDoneCount() {
        return (int) statuses.values().stream().filter(s -> s == Status.DONE).count();
    }

    public Status statusOf(int index) {
        return statuses.getOrDefault(index, Status.PENDING);
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /**
     * 模型报告某一步完成。这里做校验，而不是直接标成完成。
     *
     * @param index         步骤编号
     * @param executedTools 到目前<b>确实成功执行过</b>的工具名集合
     * @return 本次判定出的状态，方便调用方打日志
     */
    public Status claimDone(int index, Set<String> executedTools) {
        if (index < 1 || index > steps.size()) {
            return Status.PENDING;
        }
        Step step = steps.get(index - 1);
        Status result;

        if (step.expectedTool() == null) {
            // 没声明工具，无法校验，只能采信——但要如实标注，不假装验证过
            result = Status.DONE_UNVERIFIED;
        }
        else if (executedTools.contains(step.expectedTool())) {
            result = Status.DONE;
        }
        else {
            // ★ 关键分支：模型说完成了，但它声明的工具根本没跑成
            result = Status.CLAIMED_BUT_NOT_EXECUTED;
        }

        statuses.put(index, result);
        return result;
    }

    /**
     * 渲染成一段文本，每轮注入 system prompt。
     *
     * <p>注意它渲染的是<b>当前状态</b>——所以模型每轮看到的都是最新进度，
     * 不会以为已经做过的事还没做。
     *
     * <p>也注意这个对象活在消息列表外面：迭代 4 学过，消息可能被压缩、
     * 摘要可能走样，但计划每轮重新渲染，压不到它。
     */
    public String render() {
        if (steps.isEmpty()) {
            return "（暂无计划）";
        }
        StringBuilder sb = new StringBuilder("当前计划：\n");
        for (Step s : steps) {
            Status st = statusOf(s.index());
            sb.append("  [").append(st.marker()).append("] ")
              .append(s.index()).append(". ");
            if (s.expectedTool() != null) {
                sb.append("[").append(s.expectedTool()).append("] ");
            }
            sb.append(s.description())
              .append("  → ").append(st.label()).append('\n');
        }
        sb.append("已完成 ").append(verifiedDoneCount()).append("/").append(total())
          .append(" 步（按实际执行校验）。");
        return sb.toString();
    }
}
