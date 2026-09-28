package com.example.smartdataqa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 迭代 15 · 极简 CSV 读写。
 *
 * <p><b>为什么不引一个 CSV 库：</b>我们只需要「读一个手写的测试集」和「往一个结果文件追加行」，
 * 为这点事加依赖不划算。真正需要注意的是<b>转义</b>——测试集里的
 * {@code 128,450,000} 这种数字自带逗号，不按标准引号包起来，读出来就会多出一列。
 *
 * <p>实现的规则（RFC 4180 的常用子集）：
 * <ul>
 *   <li>字段含 {@code ,} {@code "} 或换行 → 整个字段用双引号包起来</li>
 *   <li>字段内的双引号写成两个（{@code ""}）</li>
 * </ul>
 */
public final class Csv {

    private Csv() {
    }

    /** 读整个文件。返回的每一行是一个字段列表，含表头行。 */
    public static List<List<String>> read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // 连续两个双引号 = 一个转义出来的双引号
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    }
                    else {
                        inQuotes = false;
                    }
                }
                else {
                    field.append(c);
                }
            }
            else if (c == '"') {
                inQuotes = true;
            }
            else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            }
            else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            }
            else if (c != '\r') {   // CRLF 的 \r 直接丢掉
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        rows.removeIf(r -> r.size() == 1 && r.get(0).isBlank());
        return rows;
    }

    /** 把一行字段拼成 CSV 的一行（不含换行符）。 */
    public static String line(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(cells.get(i)));
        }
        return sb.toString();
    }

    /**
     * 追加一行；文件不存在或为空时先写表头。
     *
     * <p><b>追加而不是覆盖，是刻意的。</b>metrics.csv 的设计就是「一次实验一行」——
     * 历史记录必须留着，否则「比上次好多少」这个问题永远答不了。
     *
     * <p><b>表头不一致时直接抛异常，不往下写。</b>因为这只会在一种情况下发生：
     * 代码里的列改了、而文件里还是旧表头。真让它写下去，
     * 结果是「表头 11 列、数据 13 列」——**文件坏了，但没有一行报错**
     * （迭代 15 一整天都在处理「损坏得不报错」的问题，这里不能再放一个）。
     */
    public static void appendRow(Path path, List<String> header, List<String> row) throws IOException {
        boolean fresh = !Files.exists(path) || Files.size(path) == 0;
        StringBuilder sb = new StringBuilder();
        if (fresh) {
            sb.append(line(header)).append('\n');
        }
        else {
            List<String> existing = read(path).get(0);
            if (!existing.equals(header)) {
                throw new IllegalStateException("CSV 表头与现有文件不一致，拒绝追加："
                        + "\n  文件里的表头：" + existing
                        + "\n  代码里的表头：" + header
                        + "\n  要么删掉旧文件，要么换个文件名（或换 --sdaq.eval.* 的路径）");
            }
        }
        sb.append(line(row)).append('\n');
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String quote(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
