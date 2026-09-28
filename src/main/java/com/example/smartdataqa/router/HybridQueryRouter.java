package com.example.smartdataqa.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 迭代 24 · <b>策略 C：规则先过一遍，拿不准的交给 LLM。</b>
 *
 * <h3>「拿不准」怎么定义 —— 只能按【命中形状】，不能按置信度</h3>
 * 规则路由（{@link KeywordQueryRouter}）的置信度是<b>固定值</b>（命中 0.6 / 没命中 0.5），
 * 它只会说「匹配到了没有」，说不出「我有多确定」。所以判据只能看它给出的形状：
 *
 * <pre>
 *   规则判定          含义                        处理
 *   document          凭具体词命中了文档类词        【采信】
 *   database          凭具体词命中了数据类词        【采信】
 *   both              两类词都命中 → 它自己也含糊   转 LLM
 *   refuse            一个词都没命中 → 它也不知道   转 LLM
 * </pre>
 *
 * <p><b>为什么选这两个口转 LLM</b>：`both` 和 `refuse` 恰好是规则「两类都沾」和
 * 「一类都不沾」的两种情形——<b>它在这两处的输出最不可信</b>
 * （迭代 8 实测：把「销售额是怎么算的」误判成 both，往答案里塞了个没人问过的数字）。
 * 而 document / database 是它凭具体词命中的，相对可信。
 *
 * <p>这个取舍的代价要说清楚：<b>规则命中却判错的题，混合策略会原样继承</b>。
 * 所以 C 的准确率上界 = 「规则判 document/database 时对的部分」+「转给 LLM 时对的部分」，
 * 它<b>不可能超过 A</b>——它的价值全在成本：省掉了那部分本可以交给 LLM 的调用。
 */
public class HybridQueryRouter implements QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(HybridQueryRouter.class);

    private final QueryRouter rules;
    private final QueryRouter llm;

    /** 转给 LLM 的次数。<b>可观测</b>——不然「省了多少」只是感觉。 */
    private final AtomicLong delegated = new AtomicLong();

    public HybridQueryRouter(QueryRouter rules, QueryRouter llm) {
        this.rules = rules;
        this.llm = llm;
    }

    @Override
    public RouteDecision decide(String question) {
        RouteDecision byRule = this.rules.decide(question);

        if (byRule.route() == RouteDecision.Route.DOCUMENT
                || byRule.route() == RouteDecision.Route.DATABASE) {
            log.debug("  [混合] 规则给了单一类 {}，采信，不叫 LLM", byRule.route().code());
            return byRule;
        }

        this.delegated.incrementAndGet();
        log.debug("  [混合] 规则判成 {}（拿不准），转 LLM", byRule.route().code());
        return this.llm.decide(question);
    }

    /** 本次统计里转给 LLM 的次数。 */
    public long delegatedCount() {
        return this.delegated.get();
    }

    /** 开始一次新的统计。 */
    public void resetStats() {
        this.delegated.set(0);
    }

    @Override
    public String strategyName() {
        return "HybridQueryRouter";
    }
}
