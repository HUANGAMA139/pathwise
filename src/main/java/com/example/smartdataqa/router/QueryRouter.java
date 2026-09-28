package com.example.smartdataqa.router;

/**
 * 路由器的契约。
 *
 * <p>只做一件事：给一个问题，判断它该走哪条路。
 *
 * <p><b>为什么把它抽成接口，而不是直接写一个类？</b>
 * 因为实现方式会换好几次，而调用方不该跟着改：
 * <ul>
 *   <li>迭代 8（本轮）：{@link KeywordQueryRouter} —— 关键词规则，先用它让骨架跑起来</li>
 *   <li>迭代 9：LLM 意图分类——让模型直接返回 {@link RouteDecision} 对象</li>
 *   <li>迭代 24：多策略对比——规则 / LLM / 混合，还要量它们的延迟和成本</li>
 * </ul>
 * 接口不变，换实现只改注入的那一行。这也是 迭代 4 把摘要能力抽成
 * {@code Summarizer} 接口的同一个手法：<b>隔离会变的部分</b>。
 */
public interface QueryRouter {

    /**
     * 判断该走哪条路。
     *
     * @param question 用户的原始问题（不要预先改写，路由判断需要看到原话）
     * @return 路由决策，永远不返回 null
     */
    RouteDecision decide(String question);

    /** 实现的名字，用于日志和 迭代 24 的策略对比。 */
    default String strategyName() {
        return getClass().getSimpleName();
    }
}
