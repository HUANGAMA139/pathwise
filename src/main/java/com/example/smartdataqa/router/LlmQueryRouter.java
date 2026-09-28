package com.example.smartdataqa.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.function.Supplier;

/**
 * 迭代 9 · 路由 v1：LLM 意图分类。迭代 23 起会<b>读「我手上有什么」</b>。
 *
 * <p>用 迭代 7 学的结构化输出，让模型直接返回 {@link RouteDecision} 对象——
 * 不解析文本、不写正则、字段对不上会直接报错而不是悄悄给个错值。
 *
 * <h3>它解决了关键词路由的什么问题</h3>
 * 迭代 8 实测出关键词路由的三个毛病：
 * <ol>
 *   <li><b>给不出真实置信度</b>——只能给固定的 0.6，说不出「我有多确定」。</li>
 *   <li><b>同一个词两边都占</b>——「销售额」既像数据类词又常出现在文档问题里。</li>
 *   <li><b>误判会污染答案</b>——迭代 8 那次多查的库，让答案里多了一个没人问过的数字。</li>
 * </ol>
 *
 * <h3>迭代 23 的修正：原来的提示词里写死了一份会过期的清单</h3>
 * 原来「两个数据源的能力」那一节是<b>手写</b>的：
 * <pre>
 *   文档库：销售统计口径说明、区域划分规则、售后与退货流程、数据仓库使用说明
 *   业务数据库：订单表，含区域、订单金额、退款金额、状态、完成时间等字段
 * </pre>
 * 到 迭代 18 它就已经和真相脱节了（先是 5 张表，后来 20 张；语料实际是 6 份不是 4 份）。
 * 而 迭代 15 定下的那一刀正是冲着这里：<b>路由的错几乎全是「模型断言手上没有，而其实有」</b>
 * （D10 说 XR-2000B 不在文档里、D14 说福建归属是「通用地理常识」）。
 *
 * <p>所以现在改成<b>从真实来源读</b>：语料清单来自 {@code CorpusLoader}、表清单来自
 * {@code SchemaRenderer}，由构造时传入的 {@link Supplier} 提供（懒加载——启动时业务库允许不在线）。
 *
 * <h3>降级设计（两层）</h3>
 * <ol>
 *   <li><b>读不到能力清单</b>（业务库没起等）→ 退回一句静态描述，<b>路由照常工作</b>，并打日志。</li>
 *   <li><b>LLM 调用失败</b>（网络、限流、输出没按 schema）→ 降级到关键词路由。</li>
 * </ol>
 * 两层都<b>必须打日志</b>：偷偷降级比直接失败更糟——你会在看评测时奇怪「准确率怎么掉了」，
 * 却不知道有多少请求走的是降级路径。
 */
public class LlmQueryRouter implements QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRouter.class);

    /**
     * 提示词的<b>前半段</b>：任务 + 四条可选路由。能力清单插在它后面。
     *
     * <p>注意它不是简单罗列四个选项——<b>重点是后面的判断要点 1</b>，
     * 它直接针对关键词路由踩过的坑：定义类问题里出现业务词，不代表需要查库。
     */
    private static final String PROMPT_HEAD = """
            你是一个查询路由器。你的唯一任务是判断用户的问题该由哪个数据源回答，并给出判断理由。

            可选的路由：

            - document：答案在【制度文档】里。特征是问口径、定义、规则、流程、依据。
              例如：「销售额是怎么算的」「区域怎么划分」「退货要几天」

            - database：答案在【业务数据】里。特征是问具体金额、数量、排名、同比环比。
              例如：「上季度华东销售额多少」「哪个区卖得最好」

            - both：两类信息都需要，缺一个答案就不完整。
              特征是既要具体数字、又要口径或定义。
              例如：「上季度销售额多少，口径怎么定的」

            - refuse：不属于以上任何一类。特征是闲聊、常识问答、超出业务范围。
              例如：「今天天气怎么样」「你叫什么名字」

            【你手上实际有什么】——下面这份清单是完整的、权威的，请以它为准，不要凭印象判断：
            """;

    /** 提示词的<b>后半段</b>：判断要点 + confidence/reason。 */
    private static final String PROMPT_TAIL = """
            判断要点（很重要）：

            1. 单独问「怎么算 / 是什么意思 / 怎么定的」这类定义问题时，
               答案只在文档里。即使问题里出现了「销售额」这样的业务词，
               也不该走数据库。这类问题的正确路由是 document，不是 both。
               —— 只有问题确实需要一个具体数字时，才走 database 或 both。

            2. 不要因为问题里出现了某个词就下结论，要看用户到底想要什么。

            3. 【判 refuse 之前，务必先逐条对照上面的清单】——清单里有的资料，
               就不该判 refuse。说「我手上没有这份资料」之前，先确认清单里真的没有。
               （迭代 15 与 迭代 23 两次实测：路由的错几乎全是「模型断言手上没有，而其实有」。）

            4. 拿不准时，宁可选 refuse 并说明理由，也不要硬选一个。

            confidence 是你的把握程度，取 0 到 1。请如实填写——
            它会被用来决定要不要加人工确认，虚高的置信度会掩盖问题。

            reason 用一句话说明你为什么这么判断，要具体，不要套话。
            """;

    /** 拿不到真实清单时的兜底（迭代 9 的原始文案，保证「跑得起来」）。 */
    private static final String STATIC_CAPABILITY = """
            - 文档库：销售统计口径说明、区域划分规则、售后与退货流程、数据仓库使用说明
            - 业务数据库：订单表，含区域、订单金额、退款金额、状态、完成时间等字段
            """;

    private final ChatClient chatClient;
    private final QueryRouter fallback;
    /** 提供「我手上有什么」；{@code null} = 只用静态兜底描述。 */
    private final Supplier<String> capabilitySupplier;
    /** 算一次就记住。<b>只缓存成功</b>——否则业务库晚点起来也恢复不了。 */
    private volatile String cachedCapability;

    /**
     * @param chatClient 用来做意图分类
     * @param fallback   LLM 调用失败时的兜底路由器（传关键词路由即可）
     */
    public LlmQueryRouter(ChatClient chatClient, QueryRouter fallback) {
        this(chatClient, fallback, null);
    }

    /**
     * @param capabilitySupplier 「我手上有什么」的提供者（懒加载，见 {@link #capability()}）。
     *                           传 {@code null} 时用静态兜底描述。
     */
    public LlmQueryRouter(ChatClient chatClient, QueryRouter fallback,
                          Supplier<String> capabilitySupplier) {
        this.chatClient = chatClient;
        this.fallback = fallback;
        this.capabilitySupplier = capabilitySupplier;
    }

    @Override
    public RouteDecision decide(String question) {
        try {
            long t0 = System.currentTimeMillis();
            String system = PROMPT_HEAD + capability() + "\n" + PROMPT_TAIL;
            RouteDecision decision = chatClient.prompt()
                    .system(system)
                    .user(question)
                    .call()
                    .entity(RouteDecision.class);
            long cost = System.currentTimeMillis() - t0;

            log.info("  [LLM路由] {} → {}  ({} ms)",
                    shorten(question), decision.brief(), cost);
            return decision;
        }
        catch (Exception e) {
            // 降级：请求照常返回，只是路由质量差一点。但必须让人看得见。
            log.warn("  [LLM路由] 调用失败，降级到 {}：{}",
                    fallback.strategyName(), e.getMessage());
            return fallback.decide(question);
        }
    }

    /**
     * 「我手上有什么」。<b>懒算一次并缓存</b>：
     * <ul>
     *   <li><b>懒</b>——表清单要连业务库，而启动时业务库允许不在线（见 {@code BusinessDb}）；</li>
     *   <li><b>只缓存成功</b>——读失败就退回静态描述且不缓存，于是业务库晚点起来还能自愈。</li>
     * </ul>
     */
    private String capability() {
        String cached = this.cachedCapability;
        if (cached != null) {
            return cached;
        }
        if (this.capabilitySupplier == null) {
            return STATIC_CAPABILITY;
        }
        try {
            String built = this.capabilitySupplier.get();
            if (built != null && !built.isBlank()) {
                this.cachedCapability = built;
                return built;
            }
            log.warn("  [LLM路由] 能力清单为空，本次退回静态描述");
        }
        catch (RuntimeException e) {
            // 读不到也要能路由 —— 但必须让人看见，否则「路由变差」会被误当成模型的问题
            log.warn("  [LLM路由] 读「我手上有什么」失败，本次退回静态描述：{}", e.getMessage());
        }
        return STATIC_CAPABILITY;
    }

    @Override
    public String strategyName() {
        return "LlmQueryRouter";
    }

    private String shorten(String s) {
        return s == null ? "" : (s.length() <= 22 ? s : s.substring(0, 22) + "…");
    }
}
