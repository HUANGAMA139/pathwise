package com.example.smartdataqa.tools.text2sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 迭代 20 · <b>few-shot 样例</b>：把几个「问题 → SQL」摆给模型看，让它照样式写。
 *
 * <h3>它要补的是 迭代 18 实测出来的那个最大的坑：值域</h3>
 * 迭代 18 跑完 9 道题，**最大的失分点不是表结构、是「取值」**——
 * 4 道题因为把 {@code '华东'} 写成 {@code '华东区'} 而返回 0 行，而且**数据库一声不响**。
 * 表结构注入给了表、列、类型、外键、注释，**唯独没给「列里到底存了哪些字面量」**。
 *
 * <p>few-shot 正是补这个的常规手段：给一条「问『华东区』、SQL 里写 {@code '华东'}」的样例，
 * 模型立刻就能学到这个对应关系——比在提示词里写一句「区域名不带区字」更自然，
 * 因为它是**照样式学**，不是**读规则**。
 *
 * <h3>样例为什么放在资源文件里，而不是写在代码里</h3>
 * 三条理由，都很实际：
 * <ol>
 *   <li><b>它是数据，不是逻辑。</b>加一条样例不该需要改 Java 文件。</li>
 *   <li><b>要能对错样例。</b>看到某道题错了，第一反应就是「该不该加个样例」——
 *       这时候改一个 .md 比改代码快得多，而且 diff 一眼看得清。</li>
 *   <li><b>要能消融。</b>{@code sdaq.text2sql.few-shot-size} 从 0 加到满，
 *       才知道「样例到底值多少分」。写在代码里就得改代码才能做这个实验。</li>
 * </ol>
 *
 * <h3>一条纪律：解析失败必须响</h3>
 * 「文件在、但一条样例都没解析出来」是典型的<b>不报错的错误</b>——
 * 程序照跑，只是 few-shot 悄悄没生效，你会以为是「样例没用」，实际是**格式写错了**。
 * 所以这里：格式不对就抛异常（启动时立刻炸），文件存在但零条也抛。
 */
public final class FewShotExamples {

    private static final Logger log = LoggerFactory.getLogger(FewShotExamples.class);

    /** 样例文件的位置（相对 classpath）。 */
    public static final String RESOURCE = "text2sql/few-shot.md";

    /** 一条样例。 */
    public record Example(String question, String sql, String note) {
    }

    private final List<Example> examples;

    private FewShotExamples(List<Example> examples) {
        this.examples = examples;
    }

    /** 空样例集（关掉 few-shot 时用）。 */
    public static FewShotExamples none() {
        return new FewShotExamples(List.of());
    }

    /** 从 classpath 加载 {@value #RESOURCE}。 */
    public static FewShotExamples load() {
        String text;
        try {
            text = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("读不到 few-shot 样例文件：" + RESOURCE, e);
        }
        List<Example> parsed = parse(text);
        if (parsed.isEmpty()) {
            // 文件在、内容不为空、却一条都没解析出来 —— 一定是格式写错了。不能装作没事。
            throw new IllegalStateException("few-shot 样例文件里一条都没解析出来，检查格式（每块以 "
                    + "### 开头，含「问：」「SQL：」两行）：" + RESOURCE);
        }
        log.info("few-shot 样例加载完成：{} 条（资源 {}）", parsed.size(), RESOURCE);
        return new FewShotExamples(parsed);
    }

    public int size() {
        return this.examples.size();
    }

    public List<Example> examples() {
        return List.copyOf(this.examples);
    }

    /** 前 {@code count} 条。<b>count 超过总数就给全部，不报错</b>——消融时容易传大。 */
    public List<Example> first(int count) {
        if (count <= 0) {
            return List.of();
        }
        return List.copyOf(this.examples.subList(0, Math.min(count, this.examples.size())));
    }

    /**
     * 拼成塞进 prompt 的文本。{@code count <= 0} 时返回空串（= 完全不注入）。
     *
     * <p>把条数做成参数而不是在构造时定死，是为了让<b>消融实验能用同一个实例跑不同的条数</b>——
     * 换个条数就重建一个加载器的话，两次跑之间就多了个变量。
     */
    public String render(int count) {
        List<Example> picked = first(count);
        if (picked.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【参考样例】\n下面几个例子展示期望的写法，注意其中取值和字段的用法：\n\n");
        for (int i = 0; i < picked.size(); i++) {
            Example e = picked.get(i);
            sb.append("例 ").append(i + 1).append('\n');
            sb.append("问：").append(e.question()).append('\n');
            sb.append("SQL：\n").append(e.sql()).append('\n');
            if (e.note() != null && !e.note().isBlank()) {
                sb.append("（要点：").append(e.note()).append("）\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析样例文件。格式：
     * <pre>
     * ### 序号
     * 问：……
     * SQL：
     * ……（可多行）
     * 要点：……（可选）
     * </pre>
     */
    static List<Example> parse(String text) {
        List<Example> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : lines) {
            if (line.startsWith("### ")) {
                current = new ArrayList<>();
                blocks.add(current);
            }
            else if (current != null) {
                current.add(line);
            }
        }

        for (int b = 0; b < blocks.size(); b++) {
            String question = null;
            String note = null;
            List<String> sqlLines = new ArrayList<>();
            boolean inSql = false;

            for (String raw : blocks.get(b)) {
                String line = raw;
                String trimmed = line.trim();
                if (question == null && trimmed.startsWith("问：")) {
                    question = trimmed.substring(2).trim();
                    continue;
                }
                if (!inSql && trimmed.equalsIgnoreCase("SQL：")) {
                    inSql = true;
                    continue;
                }
                if (trimmed.startsWith("要点：")) {
                    note = trimmed.substring(3).trim();
                    inSql = false;
                    continue;
                }
                if (inSql) {
                    sqlLines.add(line);
                }
            }

            String sql = String.join("\n", sqlLines).trim();
            if (question == null || question.isBlank() || sql.isBlank()) {
                throw new IllegalStateException(String.format(
                        Locale.ROOT,
                        "few-shot 第 %d 块不完整（问=%s，SQL 长度=%d）。每块必须有「问：」和单独一行的「SQL：」",
                        b + 1, question == null ? "缺失" : "有", sql.length()));
            }
            out.add(new Example(question, sql, note));
        }
        return out;
    }
}
