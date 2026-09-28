package com.example.smartdataqa.tools.text2sql;

/**
 * 迭代 18 · 把模型输出变成「一条能执行的 SQL」的那点文本活。
 *
 * <p>单独一个类、方法全是静态、不依赖任何框架——因为它处理的全是<b>字符串</b>，
 * 和数据库、模型都无关。抽出来的直接原因是<b>诊断 demo 也要用它</b>：
 * demo 要判断「模型这次是给出了 SQL，还是用注释说了『答不了』」，
 * 而这个判断必须和工具内部用的是同一套逻辑（两处各写一份，迟早写岔）。
 *
 * <h3>为什么这几行值得单独放</h3>
 * 它们是「模型输出」和「数据库执行」之间唯一的缓冲。
 * 少了它，模型的「好的，以下是查询：」和那对反引号会被原样发给数据库，
 * 报出来的语法错误指向的却是反引号——<b>一个把错误指向别处的 bug</b>，
 * 排查起来会先怀疑模型、再怀疑提示词，最后才发现是自己没剥壳。
 */
public final class SqlText {

    private SqlText() {
    }

    /**
     * 从模型输出里取出 SQL 正文。
     *
     * <p>处理的三种现实情况：整段包在 ```sql 代码块里、结尾带分号、前面带一句中文。
     * <b>刻意不删注释行</b>——模型答「答不了」时输出的就是注释，那是有效信息。
     */
    public static String extractSql(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();

        int fence = text.indexOf("```");
        if (fence >= 0) {
            String body = text.substring(fence + 3);
            int firstNewline = body.indexOf('\n');
            if (firstNewline >= 0) {
                String firstLine = body.substring(0, firstNewline).trim();
                // "sql" / "SQL" / "postgresql" 这类语言标记丢掉；如果是内容本身（如 "-- xxx"）就保留
                if (firstLine.isEmpty() || firstLine.matches("[A-Za-z]+")) {
                    body = body.substring(firstNewline + 1);
                }
            }
            int close = body.indexOf("```");
            if (close >= 0) {
                body = body.substring(0, close);
            }
            text = body.trim();
        }

        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    /** 整个输出是不是只有注释（= 模型在使用「答不了」那个出口）。 */
    public static boolean isOnlyComments(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            return false;
        }
        return true;
    }
}
