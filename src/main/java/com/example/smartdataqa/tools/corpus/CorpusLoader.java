package com.example.smartdataqa.tools.corpus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 迭代 12 起 · 语料加载器。把语料文件读成 Spring AI 的 {@link Document}。
 *
 * <h3>迭代 13 改了什么</h3>
 * 原来直接返回 {@code List<Chunk>}，一个文件 = 一个片段。
 * 现在改成返回 {@link Document}——因为<b>「一个文件」和「一个片段」不再是同一个东西了</b>：
 * 中间要插一步分块（{@link DocumentChunker}）。
 *
 * <p>这正是 Spring AI ETL 三段式的形状：
 * <pre>
 *   DocumentReader（读） → DocumentTransformer（切） → DocumentWriter（写）
 *   我们这里：CorpusLoader   → DocumentChunker          → 存进检索工具
 * </pre>
 * 不按这个形状拆也行，但拆开之后每一步都能单独替换——
 * 迭代 14 要换的是「检索」，读和切两段不受影响。
 *
 * <h3>放在 resources 而不是外部目录</h3>
 * 见 迭代 12 的说明：打包后也能用、不用配路径。
 */
public final class CorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);

    /** 语料在 classpath 下的位置。 */
    private static final String PATTERN = "classpath*:corpus/*.md";

    /** 元数据键：文档出处。分块后要原样带到每个片段上。 */
    public static final String META_SOURCE = "source";

    private CorpusLoader() {
    }

    /**
     * 加载全部语料为 {@link Document}。
     *
     * <p>按文件名排序，保证每次加载顺序一致——顺序不稳定的话，
     * 迭代 15 做评测时同样的代码两次跑出的结果可能不同，那就没法比较了。
     */
    public static List<Document> loadDocuments() {
        List<Document> docs = new ArrayList<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(PATTERN);
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));

            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null) {
                    continue;
                }
                String raw = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String id = stem(filename);
                String title = firstHeading(raw, filename);

                Map<String, Object> meta = new HashMap<>();
                meta.put(META_SOURCE, title);

                docs.add(new Document(id, raw.trim(), meta));
            }

            log.info("语料加载完成：{} 份，共 {} 字",
                    docs.size(), docs.stream().mapToInt(d -> d.getText().length()).sum());
        }
        catch (Exception e) {
            // 语料加载失败要让它在启动时就暴露，而不是等到第一次检索才发现
            throw new IllegalStateException("加载语料失败（模式：" + PATTERN + "）", e);
        }
        return docs;
    }

    /** 文件名去掉扩展名，作为文档 id。 */
    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 取正文第一个一级标题作为出处。
     *
     * <p>用「取标题」而不是「写死文件名」，是因为文件里那个标题才是给人看的出处，
     * 文件名带了排序用的数字前缀（01-、02-），不适合直接展示给用户。
     */
    private static String firstHeading(String content, String fallback) {
        for (String line : content.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        return fallback;
    }
}
