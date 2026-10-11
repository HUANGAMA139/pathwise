package com.example.smartdataqa.router;

import com.example.smartdataqa.tools.corpus.CorpusLoader;
import com.example.smartdataqa.tools.text2sql.BusinessDb;
import com.example.smartdataqa.tools.text2sql.SchemaRenderer;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * v23 起 · <b>路由要看的「我手上有什么」清单</b>：语料标题 + 业务库表名。
 *
 * <h3>为什么要抽成一个类</h3>
 * v24 的策略对比里，「A · LLM + 清单」这一行<b>必须和产品路径一字不差</b>——
 * 否则比出来的不是策略差异，是提示词差异。
 * 如果对比 demo 自己再拼一份清单，两份迟早写岔，而且<b>写岔了不会报错</b>，
 * 只会给出一个没法解释的准确率。（同 {@code util/Amounts}、{@code DocumentChunks} 的理由。）
 *
 * <h3>为什么必须从真实来源读、不能手写</h3>
 * 这段文字原来是硬编码在 {@code LlmQueryRouter} 提示词里的：
 * 「业务数据库：订单表，含区域、订单金额、退款金额、状态、完成时间等字段」。
 * 它在 v18 扩表之后就与真相脱节了（先是 5 张表、后来 20 张；语料也是 6 份不是 4 份）。
 * <b>手写的清单会悄悄过期，而它一过期，路由就开始凭印象判「我手上没有」</b>——
 * v15 与 v23 两次实测的三处路由错，全是这个病。
 *
 * <p><b>懒加载的原因</b>：表清单要连业务库，而启动时业务库允许不在线（见 {@code BusinessDb}）。
 * 所以调用方传的是 {@code () -> DataSourceManifest.describe(businessDb)}，只在第一次路由时才真的去读。
 */
public final class DataSourceManifest {

    private DataSourceManifest() {
    }

    /** 生成清单文本。读不到业务库时由调用方兜底（见 {@link LlmQueryRouter}）。 */
    public static String describe(BusinessDb businessDb) {
        return build(businessDb, false);
    }

    /**
     * <b>实验臂</b>：清单里给每份文档补一句「它讲了什么」（取文档自己的二级标题）。
     *
     * <p>动机来自 v24 的三次复跑：清单<b>只给标题</b>时，D14/D15 那种
     * 「内容在、标题不提示」的题<b>反而被弄坏</b>——模型带着标题逐条找、找不到「像」的，
     * 于是更有底气地拒答。这个方法用来量「补上内容提示能不能救回来」。
     *
     * <p><b>提示从哪来</b>：不用硬编码，直接取文档自己的二级标题
     * （「## 一、数据刷新频率」→「数据刷新频率」）。它本来就是文档的一部分，不会另外过期。
     */
    public static String describeWithHints(BusinessDb businessDb) {
        return build(businessDb, true);
    }

    private static String build(BusinessDb businessDb, boolean withHints) {
        StringBuilder sb = new StringBuilder();

        List<Document> docs = CorpusLoader.loadDocuments();
        sb.append("- 文档库，共 ").append(docs.size()).append(" 份：\n");
        for (Document d : docs) {
            Object title = d.getMetadata().get(CorpusLoader.META_SOURCE);
            sb.append("    · ").append(title == null ? d.getId() : title);
            if (withHints) {
                String hints = sectionHeadings(d.getText());
                if (!hints.isEmpty()) {
                    sb.append("（含：").append(hints).append("）");
                }
            }
            sb.append('\n');
        }

        List<String> tables = new SchemaRenderer(businessDb.jdbc()).tableNames();
        sb.append("- 业务数据库，共 ").append(tables.size()).append(" 张表：\n");
        for (String t : tables) {
            sb.append("    · ").append(t).append('\n');
        }
        sb.append("（以上两份清单都是完整的。清单里没有的资料，才算「我手上没有」。）");
        return sb.toString();
    }

    /** 取正文的二级标题，拼成一句「这份文档讲了什么」。顺手去掉「一、」这类序号前缀。 */
    private static String sectionHeadings(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("## ")) {
                continue;
            }
            String h = t.substring(3).trim().replaceFirst("^[一二三四五六七八九十]+、\\s*", "");
            if (h.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(h);
        }
        return sb.toString();
    }
}
