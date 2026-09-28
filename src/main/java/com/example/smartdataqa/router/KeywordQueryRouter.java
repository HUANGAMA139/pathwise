package com.example.smartdataqa.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 迭代 8 · 关键词路由器。先用规则让骨架跑起来。
 *
 * <p><b>它不是最终方案</b>——迭代 9 会换成 LLM 意图分类。
 * 但本轮需要它，有两个原因：
 * <ol>
 *   <li>骨架要能端到端跑通，而路由是链路的第一环</li>
 *   <li>它同时是 迭代 24「多策略对比」的<b>基线</b>。
 *       到时候要比的就是「规则 vs LLM vs 混合」，看谁的准确率/延迟/成本更划算——
 *       而「规则」这一方就是本轮这个类。</li>
 * </ol>
 *
 * <p>规则本身很朴素：命中「多少/金额/排名」这类词往数据库走，
 * 命中「怎么算/口径/定义」这类词往文档走；两边都命中就是 both；都不命中就 refuse。
 */
public class KeywordQueryRouter implements QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(KeywordQueryRouter.class);

    /** 指向「数据在库里」的信号词。 */
    private static final List<String> DATABASE_HINTS = List.of(
            "多少", "几笔", "金额", "销售额", "营收", "数量", "总计", "合计", "平均",
            "排名", "top", "环比", "同比", "增长率", "占比", "分布", "统计");

    /** 指向「答案在文档里」的信号词。 */
    private static final List<String> DOCUMENT_HINTS = List.of(
            "怎么算", "怎么定义", "口径", "定义", "规则", "流程", "依据", "标准",
            "为什么", "什么条件", "制度", "规定", "说明", "含义");

    @Override
    public RouteDecision decide(String question) {
        if (question == null || question.isBlank()) {
            return new RouteDecision(RouteDecision.Route.REFUSE, 1.0, "问题为空，没什么可查的");
        }

        String q = question.toLowerCase();

        String hitDb = firstHit(q, DATABASE_HINTS);
        String hitDoc = firstHit(q, DOCUMENT_HINTS);

        RouteDecision decision;
        if (hitDb != null && hitDoc != null) {
            decision = new RouteDecision(RouteDecision.Route.BOTH, 0.6,
                    "同时命中数据类词「" + hitDb + "」和文档类词「" + hitDoc + "」，两类信息可能都需要");
        }
        else if (hitDb != null) {
            decision = new RouteDecision(RouteDecision.Route.DATABASE, 0.6,
                    "命中数据类词「" + hitDb + "」，答案应在业务数据里");
        }
        else if (hitDoc != null) {
            decision = new RouteDecision(RouteDecision.Route.DOCUMENT, 0.6,
                    "命中文档类词「" + hitDoc + "」，答案应在制度文档里");
        }
        else {
            decision = new RouteDecision(RouteDecision.Route.REFUSE, 0.5,
                    "没有命中任何已知词，无法判断该查什么");
        }

        // 注意置信度这里一律给固定值——这是规则的固有局限：
        // 它只能给出「匹配到了/没匹配到」，给不出「我有多确定」。
        // 迭代 9 换成 LLM 之后，置信度才真正有信息量（迭代 25 的兜底策略要靠它）。
        log.debug("关键词路由：{} -> {}", question, decision.brief());
        return decision;
    }

    private String firstHit(String question, List<String> hints) {
        for (String h : hints) {
            if (question.contains(h.toLowerCase())) {
                return h;
            }
        }
        return null;
    }
}
