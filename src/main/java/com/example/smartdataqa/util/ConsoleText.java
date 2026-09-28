package com.example.smartdataqa.util;

/**
 * 控制台输出的小工具。
 *
 * <p>抽出来的原因很实际：<b>「按显示宽度对齐中文」这件事已经写了三遍了</b>
 * （SkeletonDemo 一次，迭代 9 的对比表又要一次）。第三次出现就该抽出来——
 * 这个「三次法则」在工程上很常用：第一次写、第二次忍、第三次抽。
 *
 * <p>核心问题：{@link String#length()} 数的是<b>字符个数</b>，
 * 不是屏幕占几列。「问题」是 2 个字符但占 4 列。
 * 拿 length 去补空格，含中文的表格就会歪。
 */
public final class ConsoleText {

    private ConsoleText() {
    }

    /**
     * 估算显示宽度：中日韩字符占两列，其余占一列。
     *
     * <p>这是个粗略估算（没处理全角标点、emoji、组合字符），
     * 但对「日志里对齐一张表」这个用途足够了。
     */
    public static int displayWidth(String s) {
        if (s == null) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += (s.charAt(i) >= 0x2E80) ? 2 : 1;
        }
        return w;
    }

    /** 右补空格到指定显示宽度。 */
    public static String padRight(String s, int width) {
        if (s == null) {
            s = "";
        }
        int w = displayWidth(s);
        return w >= width ? s : s + " ".repeat(width - w);
    }

    /** 按<b>显示宽度</b>截断，不是按字符个数。超长时末尾加省略号。 */
    public static String shorten(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (displayWidth(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = (s.charAt(i) >= 0x2E80) ? 2 : 1;
            if (w + cw > maxWidth - 2) {   // 给末尾的省略号留 2 列
                break;
            }
            sb.append(s.charAt(i));
            w += cw;
        }
        return sb + "…";
    }
}
