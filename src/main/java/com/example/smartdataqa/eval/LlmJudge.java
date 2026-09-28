package com.example.smartdataqa.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迭代 15 · 用 LLM 当裁判的三个判分器。
 *
 * <p>Spring AI 自带两个（{@code RelevancyEvaluator} / {@code FactCheckingEvaluator}），
 * 但它俩覆盖不了这四个 RAGAS 指标里的全部——尤其是 context 那两个，框架没有内置，
 * 因为它们都需要 <b>ground truth</b>（标准答案），框架无法凭空生成。所以这里自己写。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>原始输出一定打进日志。</b>判分器是整条评测里最容易悄悄坏掉的一环——
 *       它坏了不会报错，只会给出好看或难看的分数。看不见判分过程，就永远查不出来
 *       （迭代 4/6 的教训：中间产物是错误放大器）。</li>
 *   <li><b>解析失败返回 {@link #UNPARSED}，不返回 0。</b>
 *       返回 0 会把「判分器崩了」伪装成「答案很差」，而这两种情况的修法完全相反。</li>
 *   <li><b>拼提示词不用 {@code String.format}。</b>拼接内容里只要出现一个 {@code %}
 *       （比如文档里的「重合度 71%」），格式化就会抛异常或错位。这里全部用字符串拼接。</li>
 * </ol>
 */
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    /**
     * 判分输出无法解析时的返回值。
     *
     * <p><b>刻意不用 0</b>：0 是「判定为最差」，而 UNPARSED 是「没判出来」。
     * 两者在汇总时必须区别对待——前者计入平均，后者要单独计数并报警。
     */
    public static final double UNPARSED = -1.0;

    /** 覆盖度判分要求的行格式：{@code 1: 是}。用编号锚定，避免把正文里的「是否」误当判定结果。 */
    private static final Pattern RECALL_LINE = Pattern.compile("(\\d+)\\s*[:：.]\\s*(是|否)");

    /** 取第一个整数（判「相关片段个数」用）。 */
    private static final Pattern FIRST_INT = Pattern.compile("\\d+");

    /** 取首个等级字母（判 A/B/C 用），只看第一行，避免把回显的内容误当判定。 */
    private static final Pattern FIRST_LEVEL = Pattern.compile("[ABC]");

    private final ChatClient chatClient;

    public LlmJudge(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 答案准确率：实际答案与标准答案的一致性。
     *
     * @return 1.0（A 完全一致）/ 0.5（B 部分）/ 0.0（C 不一致），或 {@link #UNPARSED}
     */
    public double gradeAnswer(String question, String expectedAnswer, String actualAnswer) {
        String prompt = "你在做答案质量判定。下面是【问题】【标准答案】【待判定答案】。\n\n"
                + "【问题】\n" + question + "\n\n"
                + "【标准答案】\n" + expectedAnswer + "\n\n"
                + "【待判定答案】\n" + actualAnswer + "\n\n"
                + "判断待判定答案与标准答案的一致性，只能选一个等级：\n"
                + "A = 覆盖了标准答案的全部要点，且没有与标准答案矛盾的内容\n"
                + "B = 覆盖了部分要点，或有次要偏差\n"
                + "C = 没有覆盖要点，或与标准答案矛盾（包括编造了标准答案里没有的数字）\n\n"
                + "只输出一个字母（A 或 B 或 C），不要输出任何其他内容。";

        String s = ask(prompt);
        String firstLine = s.lines().findFirst().orElse("").trim().toUpperCase();
        Matcher m = FIRST_LEVEL.matcher(firstLine);
        if (!m.find()) {
            log.warn("      [判分] 答案等级解析失败，原始输出：{}", s);
            return UNPARSED;
        }
        return switch (m.group()) {
            case "A" -> 1.0;
            case "B" -> 0.5;
            default -> 0.0;
        };
    }

    /**
     * 迭代 22 · <b>SQL 意图正确性</b>：这条 SQL 是否正确表达了用户的查询意图。
     *
     * <h3>它和「执行准确率」是两个指标，不是一个</h3>
     * 计划里点名要区分的那一对，就是这两个：
     * <pre>
     *   SQL 正确率（本方法）  这条 SQL 的【形状】对不对——表、列、聚合、筛选条件
     *   执行准确率（Text2SqlDiagnostic / 数值比对）  这条 SQL 查出来的【数据】对不对
     * </pre>
     * 两者会分叉，而且是<b>各自往不同方向分叉</b>：
     * <ul>
     *   <li><b>SQL 对、执行错</b>：形状没毛病，但取值域写错了（{@code '华东区'} 而非 {@code '华东'}）
     *       → 返回 0 行。这时 SQL 意图判 A，执行判 0。</li>
     *   <li><b>SQL 错、执行「对」</b>：口径写错（归期用了 {@code created_at}），
     *       但它撞上一个恰好对得上的数——迭代 18 的 D4 就是两处错互相抵消、只差 2.3%。</li>
     * </ul>
     * 所以只报其中一个都会漏掉一半的失败模式。<b>迭代 18 那句「失败要按响不响分」，
     * 到了指标这一层就变成「要按两个指标分」。</b>
     *
     * <p><b>刻意不给它表结构和文档</b>：它判的是「这条 SQL 有没有把问题问出来」，
     * 不是「它懂不懂业务口径」——后者由执行准确率负责。给了 schema 反而会让它
     * 滑向判业务对错，两个指标就重叠了。
     *
     * @return 1.0（A 形状完全对）/ 0.5（B 大体对）/ 0.0（C 明显错），或 {@link #UNPARSED}
     */
    public double gradeSqlIntent(String question, String sql) {
        if (sql == null || sql.isBlank()) {
            return UNPARSED;
        }
        String prompt = "你在判定一条 SQL 是否【正确表达了用户的查询意图】。\n\n"
                + "【问题】\n" + question + "\n\n"
                + "【生成的 SQL】\n" + sql + "\n\n"
                + "只判【意图与结构】，不要真的执行它，也不要因为大小写、换行、别名、"
                + "空格这类格式问题扣分：\n"
                + "A = 表、列、聚合与筛选条件的形状都与问题匹配，若数据无误则会得到正确答案\n"
                + "B = 大体对，但有次要问题（如缺排序、多了无关列、非关键的口径偏差）\n"
                + "C = 有明显错误：用错表/列、时间列选错、口径错、或本就不该用 SQL 回答\n\n"
                + "只输出一个字母（A 或 B 或 C），不要输出任何其他内容。";

        String s = ask(prompt);
        String firstLine = s.lines().findFirst().orElse("").trim().toUpperCase();
        Matcher m = FIRST_LEVEL.matcher(firstLine);
        if (!m.find()) {
            log.warn("      [判分] SQL 意图等级解析失败，原始输出：{}", s);
            return UNPARSED;
        }
        return switch (m.group()) {
            case "A" -> 1.0;
            case "B" -> 0.5;
            default -> 0.0;
        };
    }

    /**
     * 片段级 context 精确率：检索回来的片段里，有多少真的「有助于回答这个问题」。
     *
     * <p>这是 迭代 13/14 一直缺的那个指标——那时候只能按「是否来自期望文档」算，
     * 粗到解释不了结果（同一份文档里也有无关段落）。
     *
     * @return 相关片段数 / 总片段数，或 {@link #UNPARSED}
     */
    public double gradeContextPrecision(String question, List<String> chunkTexts) {
        if (chunkTexts.isEmpty()) {
            return 0.0;
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < chunkTexts.size(); i++) {
            list.append("片段").append(i + 1).append("：\n").append(chunkTexts.get(i)).append("\n\n");
        }
        int n = chunkTexts.size();
        String prompt = "下面是 " + n + " 个资料片段，以及一个问题。\n"
                + "请逐个判断每个片段是否【有助于回答这个问题】——只判断相关性，\n"
                + "不要求它单独就能完整回答。\n\n"
                + "【问题】\n" + question + "\n\n"
                + list
                + "只输出一个数字：有助于回答问题的片段个数（0 到 " + n + " 之间）。\n"
                + "不要输出任何其他内容。";

        String s = ask(prompt);
        Matcher m = FIRST_INT.matcher(s);
        if (!m.find()) {
            log.warn("      [判分] 片段相关数解析失败，原始输出：{}", s);
            return UNPARSED;
        }
        int relevant = Integer.parseInt(m.group());
        return Math.min(Math.max(relevant, 0), n) / (double) n;
    }

    /**
     * RAGAS 的 context recall：标准答案里的要点，有多少能从检索到的上下文里得到支持。
     *
     * <p>做法和 RAGAS 一致：先把标准答案拆成要点，再逐条判「上下文支不支持」，
     * 支持数 ÷ 要点数就是召回率。
     *
     * @return 支持率，或 {@link #UNPARSED}
     */
    public double gradeContextRecall(String expectedAnswer, String joinedContext) {
        String prompt = "下面是【标准答案】和【检索到的上下文】。\n\n"
                + "先把标准答案拆成若干条关键要点并逐条编号，然后判断每条要点\n"
                + "能否从上下文中得到支持。\n\n"
                + "【标准答案】\n" + expectedAnswer + "\n\n"
                + "【检索到的上下文】\n" + joinedContext + "\n\n"
                + "输出格式（每行一条，严格照这个格式，不要输出别的任何内容）：\n"
                + "1: 是\n"
                + "2: 否\n"
                + "「是」表示该要点能被上下文支持，「否」表示不能。";

        String s = ask(prompt);
        int yes = 0;
        int total = 0;
        Matcher m = RECALL_LINE.matcher(s);
        while (m.find()) {
            total++;
            if ("是".equals(m.group(2))) {
                yes++;
            }
        }
        if (total == 0) {
            log.warn("      [判分] 覆盖度解析失败（没找到任何「编号: 是/否」行），原始输出：{}", s);
            return UNPARSED;
        }
        log.info("      [判分] 要点 {} 条，被上下文支持 {} 条", total, yes);
        return (double) yes / total;
    }

    /** 问一次模型，返回 trim 后的原始文本，并把它记进日志。 */
    private String ask(String prompt) {
        String raw = this.chatClient.prompt().user(prompt).call().content();
        String s = raw == null ? "" : raw.trim();
        log.info("      [判分] 原始输出：{}", s.length() <= 80 ? s : s.substring(0, 80) + "…");
        return s;
    }
}
