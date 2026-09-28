#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态自检 —— 没有 JDK 时能做的六类检查。

    python3 scripts/static-check.py

这份脚本的来历：迭代 21 我把 Text2SqlDatabaseTool.Options 从 6 个字段加到 8 个，
三个老调用点（SqlRepairDemo）没跟着改，**一路漏到本机 mvn 才炸**。
而当时的自检只查括号/占位符/未定义方法/import/GBK —— **不查构造参数个数**。
项目技术坑表里那条「改签名必须把所有调用点过一遍」是对的，但人总会漏，脚本不该漏。
所以第 6 类检查是那天加上的。

六类检查：
  1. 括号/花括号/方括号配平
  2. 日志占位符与参数【按语句配对】（`{}` 写在上一行、参数传在下一行会静默吞掉参数）
  3. 本类内被无前缀调用、但本文件没有定义的方法名（迭代 13 的 shortenId 就是这么暴露的）
  4. import 是否有来源 / 有没有用到
  5. 非 GBK 字符（用户的 Windows 控制台是 cp936，`✓`/`✗` 会变成 `?`）
  6. ★ 构造函数/record 的参数个数是否和调用点一致（迭代 21 加）
  7. ★ <scope>runtime</scope> 的依赖，源码里不许 import 它的包（迭代 21 加）

**查不出来的**（别指望它）：
  · 类型是否兼容（传了个 String 给 int）—— 那要真编译器
  · 泛型是否对得上、方法重载解析是否有歧义
  · 一个 import 的包到底在不在编译期 classpath 上（第 7 条只覆盖「pom 里明确写了 runtime」这一种）
  · 任何运行期行为
所以它是「把 mvn 之前的粗筛」，不是替代 mvn。
"""

import os
import re
import sys

ROOT = 'src/main/java'


# ---------------------------------------------------------------------------
# 公共：把字符串和注释换成等长空格（保留换行）
# ---------------------------------------------------------------------------
def strip_code(src):
    """等长替换：注释/字符串变空格，换行保留 —— 这样行号不漂，深度也不会被内容带偏。"""
    out = list(src)
    i, n = 0, len(src)

    def blank(a, b):
        for k in range(a, min(b, n)):
            if out[k] != '\n':
                out[k] = ' '

    while i < n:
        c = src[i]
        if c in '"\'':
            q, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                j += 1
            blank(i, j)
            i = j
        elif src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
        else:
            i += 1
    return ''.join(out)


def slice_parens(s, open_idx):
    """从 open_idx 处的 '(' 起，返回到匹配 ')' 之间的内容。"""
    depth, i = 1, open_idx + 1
    while i < len(s) and depth:
        if s[i] == '(':
            depth += 1
        elif s[i] == ')':
            depth -= 1
        i += 1
    return s[open_idx + 1:i - 1]


def count_args(params, angle=True):
    """按顶层逗号数参数个数。

    `angle` 决定要不要把 `<` `>` 当成泛型括号——这里有个**必须区分**的地方：

      · 解析【声明】时（`public Foo(Map<String, List<Document>> m)`）要认泛型，
        否则泛型里的逗号会被当成参数分隔符 → 多算。
      · 统计【调用点实参】时**不能认**，因为 `repairAttempts > 0` 里的 `>` 是大于号，
        会把它当闭合泛型、把深度减成负数 → 后面的逗号漏算 → 少算。

    两个方向我都实测踩过一次（迭代 21 加这条检查的当天），所以留了开关而不是选一个。
    `->` 要整对跳过：箭头里的 `>` 同样会污染深度。
    """
    p = params.strip()
    if not p:
        return 0
    depth, count, i = 0, 1, 0
    while i < len(p):
        if p.startswith('->', i):
            i += 2
            continue
        c = p[i]
        if c in '([{' or (angle and c == '<'):
            depth += 1
        elif c in ')]}' or (angle and c == '>'):
            depth -= 1
        elif c == ',' and depth == 0:
            count += 1
        i += 1
    return count


def split_top(s, angle=False):
    """按顶层逗号切分实参。默认不认泛型尖括号 —— 见 count_args 里对 angle 的说明。"""
    parts, depth, cur, i, instr = [], 0, '', 0, False
    while i < len(s):
        c = s[i]
        if instr:
            cur += c
            if c == '\\':
                cur += s[i + 1:i + 2]
                i += 2
                continue
            if c == '"':
                instr = False
            i += 1
            continue
        if c == '"':
            instr = True
            cur += c
            i += 1
            continue
        if s.startswith('->', i):
            cur += '->'
            i += 2
            continue
        if c in '([{' or (angle and c == '<'):
            depth += 1
        elif c in ')]}' or (angle and c == '>'):
            depth -= 1
        if c == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += c
        i += 1
    parts.append(cur)
    return parts


# ---------------------------------------------------------------------------
def java_files():
    return sorted(os.path.join(r, n) for r, _, ns in os.walk(ROOT)
                  for n in ns if n.endswith('.java'))


JAVA_LANG = set("""String Integer Long Double Float Boolean Character Byte Short Object Math System
Exception RuntimeException IllegalArgumentException IllegalStateException NullPointerException
NumberFormatException UnsupportedOperationException IndexOutOfBoundsException StringBuilder Thread
Override Deprecated FunctionalInterface SuppressWarnings Number CharSequence Iterable Comparable
AutoCloseable Throwable Error Enum Class Void List Map Set ArrayList LinkedHashMap LinkedHashSet
Objects Optional Stream Collectors Collections Arrays Pattern Matcher""".split())

KEYWORDS = set("""if while for switch catch return new super this assert case throw synchronized do else
try yield record instanceof""".split())

KNOWN_METHODS = set("""format valueOf parseInt parseDouble abs max min round join of copyOf asList
isEmpty get put add toList stream map filter sorted forEach split trim replace startsWith endsWith
contains indexOf substring length charAt equals equalsIgnoreCase toLowerCase toUpperCase matches
append toString repeat strip""".split())


def main():
    files = java_files()
    problems = []
    for f in files:
        src = open(f, encoding='utf-8').read()
        code = strip_code(src)
        short = os.path.basename(f)

        def bad(msg):
            problems.append('%s: %s' % (short, msg))

        # 1) 配平
        for op, cl, label in (('{', '}', '花括号'), ('(', ')', '圆括号'), ('[', ']', '方括号')):
            if code.count(op) != code.count(cl):
                bad('%s不配平：%s=%d %s=%d' % (label, op, code.count(op), cl, code.count(cl)))

        # 2) 日志占位符与参数按语句配对（在【原始】文本上数，字符串要保留）
        for m in re.finditer(r'\blog\.(info|warn|error|debug|trace)\s*\(', src):
            depth, i, buf = 1, m.end(), []
            while i < len(src) and depth > 0:
                c = src[i]
                if c in '"\'':
                    q, j = c, i + 1
                    while j < len(src):
                        if src[j] == '\\':
                            j += 2
                            continue
                        if src[j] == q:
                            break
                        j += 1
                    buf.append(src[i:j + 1])
                    i = j + 1
                    continue
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
                buf.append(c)
                i += 1
            body = ''.join(buf)
            ph = body.count('{}')
            args = max(0, len(split_top(body)) - 1)
            if ph != args:
                bad('第 %d 行 log.%s：%d 个 {} vs %d 个参数'
                    % (src[:m.start()].count('\n') + 1, m.group(1), ph, args))

        # 3) 无前缀调用但本文件没定义的方法
        defined = set(re.findall(r'\b(\w+)\s*\(', code))
        defined |= set(re.findall(
            r'(?:public|private|protected|static|final|abstract)\s+[\w<>\[\],.\s]+\s+(\w+)\s*\([^;{]*\)', code))
        cls = re.search(r'\b(?:class|record|interface|enum)\s+(\w+)', code)
        if cls:
            defined.add(cls.group(1))
        called = set()
        for m in re.finditer(r'(?<![.\w])([a-z]\w*)\s*\(', code):
            if 'new ' in code[max(0, m.start() - 5):m.start()]:
                continue
            tok = m.group(1)
            if tok in KEYWORDS:
                continue
            called.add(tok)
        sus = sorted(called - defined - KNOWN_METHODS)
        if sus:
            bad('疑似未定义的方法调用：%s' % ', '.join(sus))

        # 4) import 用没用
        for imp in re.findall(r'^import\s+(?:static\s+)?([\w.]+);', src, re.M):
            simple = imp.split('.')[-1]
            if simple != '*' and not re.search(r'\b%s\b' % re.escape(simple), code):
                bad('import 了却没用到：%s' % imp)

        # 5) 非 GBK 字符
        for idx, line in enumerate(src.split('\n'), 1):
            try:
                line.encode('gbk')
            except UnicodeEncodeError as e:
                bad('第 %d 行有非 GBK 字符 %r' % (idx, line[e.start:e.end]))

    # 6) 构造函数/record 参数个数
    declared = {}
    codes = {}
    for f in files:
        code = strip_code(open(f, encoding='utf-8').read())
        codes[f] = code
        for m in re.finditer(r'\brecord\s+(\w+)\s*\(', code):
            declared.setdefault(m.group(1), set()).add(count_args(slice_parens(code, m.end() - 1)))
        for m in re.finditer(r'(?:public|private|protected)\s+(?!static)(\w+)\s*\(', code):
            declared.setdefault(m.group(1), set()).add(count_args(slice_parens(code, m.end() - 1)))

    checked = 0
    for f, code in codes.items():
        # 【限定名也要认】：`new Text2SqlDatabaseTool.Options(...)` 里被 new 的是最后一个类名。
        # 这里连踩两次坑，都写在注释里免得再犯：
        #   第一版 `new\s+([A-Z]\w*)\s*\(`：碰到「带前缀的构造」直接跳过 ——
        #     于是「检查通过」是假的，它压根没查那些调用点。
        #   第二版前缀写成 `[a-z]\w*`：只认小写开头的包名（java.util.），
        #     而 `Text2SqlDatabaseTool.` 是【大写开头的外层类名】，照样漏。
        # 所以前缀必须允许任意标识符。
        for m in re.finditer(r'\bnew\s+(?:[A-Za-z_]\w*\s*\.\s*)*([A-Z]\w*)\s*(?:<[^>()]*>)?\s*\(', code):
            name = m.group(1)
            if name not in declared:
                continue
            checked += 1
            # angle=False：调用点的实参里可能有 `>` 比较符（`repairAttempts > 0`），
            # 认成泛型就会把深度算错、少算参数个数 —— 这个误报我自己踩过一次。
            n = count_args(slice_parens(code, m.end() - 1), angle=False)
            if n not in declared[name]:
                problems.append('%s: 第 %d 行 new %s(...) 传了 %d 个参数，声明的是 %s 个'
                                % (os.path.basename(f), code[:m.start()].count('\n') + 1,
                                   name, n, sorted(declared[name])))

    # 7) pom 里 <scope>runtime</scope> 的依赖，源码里不许 import 它的包。
    #    runtime 作用域的依赖【不在编译期 classpath 上】—— import 了就是
    #    「程序包 xxx 不存在」。迭代 21 我想用 PG 驱动的 PgResultSetMetaData，
    #    而 pom 里 postgresql 写的是 runtime，mvn 当场报了那个错。
    #    （groupId 通常就是包根：org.postgresql / org.springframework.ai / com.alibaba.cloud.ai）
    if os.path.exists('pom.xml'):
        pom = open('pom.xml', encoding='utf-8').read()
        for dep in re.finditer(r'<dependency>(.*?)</dependency>', pom, re.S):
            block = dep.group(1)
            if '<scope>runtime</scope>' not in block:
                continue
            g = re.search(r'<groupId>([\w.]+)</groupId>', block)
            if not g:
                continue
            group = g.group(1)
            for f, code in codes.items():
                m = re.search(r'^import\s+' + re.escape(group) + r'\.', code, re.M)
                if m:
                    problems.append('%s: 第 %d 行 import 了 %s.*，但 pom 里那条依赖是 '
                                    '<scope>runtime</scope>（不在编译期 classpath 上）'
                                    % (os.path.basename(f), code[:m.start()].count('\n') + 1, group))

    # 8) 用了却没 import 的类型名（迭代 23 加）
    #    迭代 23 我给 EvalReport 写了 LocalDateTime / DateTimeFormatter 却忘了 import，
    #    前七类检查【都不看「这个名字是从哪来的」】，于是一路漏到 mvn 才炸。
    #    这已经是第 3 次「交付后才编译失败」（迭代 13 跨文件签名 / 迭代 21 构造参数个数 / 迭代 23 少 import），
    #    三次都不是「不会写」，是「没验证到」——所以补成检查。
    #
    #    做法：把【项目里任何地方声明过的类型名】+ 本文件的 import + java.lang 当成已知；
    #    出现在【类型位置】的大写开头标识符（后面跟 . 或 <，或跟在 new 后面）不在这三者里就报警。
    #    刻意保守：已知集合取「项目全局」——宁可漏报，也不误报（误报会让人把工具关掉）。
    JAVA_LANG = {
        'String', 'Object', 'Integer', 'Long', 'Double', 'Float', 'Boolean', 'Byte', 'Short',
        'Character', 'Number', 'Math', 'System', 'StringBuilder', 'CharSequence', 'Iterable',
        'Comparable', 'Runnable', 'Class', 'Enum', 'Record', 'Thread', 'Void', 'Throwable',
        'Exception', 'RuntimeException', 'IllegalArgumentException', 'IllegalStateException',
        'UnsupportedOperationException', 'NumberFormatException', 'NoSuchElementException',
        'IndexOutOfBoundsException', 'NullPointerException', 'Error',
        'Override', 'Deprecated', 'SuppressWarnings', 'FunctionalInterface', 'SafeVarargs',
    }
    declared_types = set()
    imports_by_file = {}
    for f in files:
        c = codes[f]
        for m in re.finditer(r'\b(?:class|record|interface|enum)\s+(\w+)', c):
            declared_types.add(m.group(1))
        imps = set()
        # import static a.b.c; 的最后一段是小写方法名，无妨——它不会出现在「类型位置」
        for imp in re.findall(r'^import\s+(?:static\s+)?([\w.]+);', c, re.M):
            imps.add(imp.split('.')[-1])
        imports_by_file[f] = imps

    for f in files:
        c = codes[f]
        known = declared_types | imports_by_file[f] | JAVA_LANG
        # 类型位置 1：X. 或 X<   （前一个字符不是 . / $ / 词字符 —— 于是 `RouteDecision.Route.REFUSE`
        #   里的 `Route` 不会被当成本文件独立使用；`java.time.LocalDateTime` 这种全限定名也不会）
        used = set(re.findall(r'(?<![\w.$])([A-Z]\w*)\s*[.<]', c))
        # 类型位置 2：new X / new a.b.X
        used |= set(re.findall(r'\bnew\s+(?:[A-Za-z_]\w*\s*\.\s*)*([A-Z]\w*)', c))
        # 【全大写的标识符是常量，不是类型】—— 第一版没排除，一次报出 20 处误报
        # （`CASES.` / `BIG_NUMBER.` / `ROUTE_ORDER.` … 全是静态常量）。
        # Java 惯例把这两者分得很开：常量 ALL_CAPS，类型 UpperCamelCase。
        # 误报会让人把工具关掉，所以这一条必须排掉。
        miss = sorted(u for u in used
                      if u not in known
                      and len(u) > 1
                      and not re.fullmatch(r'[A-Z][A-Z0-9_]*', u))
        if miss:
            problems.append('%s: 疑似用了却没 import 的类型：%s'
                            % (os.path.basename(f), ', '.join(miss)))

    # 9) application.yml 里被 YAML 悄悄当成布尔的「裸词」值（迭代 26 加）
    #    迭代 26 我把改写档位写成 `mode: off`，结果 off 被 YAML 1.1 解析成布尔 false，
    #    于是 @Value 读到字符串 "false"，启动直接炸：
    #      「未知的 sdaq.rag.rewrite.mode：false（可选 none | llm）」
    #    项目里别处用的是 none —— 它不在 YAML 的布尔表里，所以一直没事。
    #    规则刻意很窄：只报 on/off/yes/no/y/n 这些【几乎总是想写成字符串】的裸值。
    #    （true/false 是有意为之的布尔，不报。）
    YML_PATH = 'src/main/resources/application.yml'
    if os.path.exists(YML_PATH):
        TRAP = {'on', 'off', 'yes', 'no', 'y', 'n'}
        for i, line in enumerate(open(YML_PATH, encoding='utf-8').read().split('\n'), 1):
            body = line.split('#', 1)[0]
            m = re.match(r'^\s*[A-Za-z0-9_.-]+\s*:\s*([A-Za-z]+)\s*$', body)
            if m and m.group(1).lower() in TRAP:
                problems.append('%s: 第 %d 行 值是裸的 `%s` —— 会被 YAML 当成布尔，'
                                '想写字符串就加引号或改用 none' % (YML_PATH, i, m.group(1)))

    print('检查 %d 个 Java 文件（含 %d 处 new 的构造参数个数）' % (len(files), checked))
    if problems:
        print()
        print('发现 %d 处可疑：' % len(problems))
        for p in problems:
            print('  -', p)
        return 1
    print('九类静态检查全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
