package com.example.smartdataqa.router;

import com.example.smartdataqa.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

/**
 * 迭代 24 · <b>策略 D：把问题投到 4 个「路由原型」上，取最像的那个。</b>
 *
 * <h3>它为什么值得占一行</h3>
 * 它代表「<b>不调大模型也能做语义分类</b>」这条路：<b>零生成 token</b>，
 * 每次只花一次 embedding——比 qwen-plus 便宜一两个数量级，也快得多。
 * 计划里那句「95% 准确但 2 秒 + 花 token，未必比 88% 且 20ms 免费的好」，
 * 要有一行这样的东西才谈得上比较。
 *
 * <p><b>★ 一个必须说明的偏差</b>：计划里原本写的是「本地小分类器（本地跑）」，
 * 但本项目<b>不引入本地模型权重</b>（那要带一个几百 MB 的模型，且与「主 Java」的定位不搭）。
 * 所以这一行实际是<b>「用一次 embedding 代替一次生成」</b>：
 * 便宜得多、也快得多，但<b>不是真本地</b>——它仍要一次网络往返。
 * 把这条写清楚，比含糊地叫它「本地分类器」诚实。
 *
 * <h3>原型文本从哪来（以及为什么不能拿测试集当训练集）</h3>
 * 直接用提示词里那四条路由的定义 + 例子。**不能**把 28 条测试用例当训练样本做最近邻——
 * 那是拿测试集训练（D13/D15 本就是「同一事实两种问法」），1-NN 会刷出接近满分，
 * 而那分毫无意义。用「路由定义」当原型是零样本的，没有这个问题。
 *
 * <p>4 个原型向量在<b>构造时算一次</b>（1 批，不到 10 条），之后每次判定只算问题那一条。
 */
public class EmbeddingPrototypeRouter implements QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingPrototypeRouter.class);

    private record Prototype(RouteDecision.Route route, String text) {
    }

    /** 四条路由的「原型」= 提示词里那份定义 + 例子。改这里要同步改提示词，否则两者会对不上。 */
    private static final List<Prototype> PROTOTYPES = List.of(
            new Prototype(RouteDecision.Route.DOCUMENT,
                    "答案在制度文档里：问口径、定义、规则、流程、依据。"
                            + "例如：销售额是怎么算的、区域怎么划分、退货要几天"),
            new Prototype(RouteDecision.Route.DATABASE,
                    "答案在业务数据里：问具体金额、数量、排名、同比环比。"
                            + "例如：上季度华东销售额多少、哪个区卖得最好"),
            new Prototype(RouteDecision.Route.BOTH,
                    "既要一个具体数字、又要口径或定义，缺一个答案就不完整。"
                            + "例如：上季度销售额多少，口径怎么定的"),
            new Prototype(RouteDecision.Route.REFUSE,
                    "闲聊、常识问答、与业务数据无关。例如：今天天气怎么样、你叫什么名字、帮我写一首诗"));

    private final EmbeddingModel embeddingModel;
    private final List<float[]> prototypeVectors;

    public EmbeddingPrototypeRouter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.prototypeVectors = Vectors.embedInBatches(embeddingModel,
                PROTOTYPES.stream().map(Prototype::text).toList());
        log.info("  原型路由器就绪：{} 个原型向量", this.prototypeVectors.size());
    }

    @Override
    public RouteDecision decide(String question) {
        float[] q = this.embeddingModel.embed(question);

        int bestIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < PROTOTYPES.size(); i++) {
            // 同分时先出现的胜出，所以结果是确定性的（列表顺序固定）
            double s = Vectors.cosine(q, this.prototypeVectors.get(i));
            if (s > bestScore) {
                bestScore = s;
                bestIdx = i;
            }
        }

        Prototype best = PROTOTYPES.get(bestIdx);
        return new RouteDecision(best.route(), Math.max(0.0, bestScore),
                "与「" + best.route().code() + "」原型最像（余弦 " + String.format("%.3f", bestScore) + "）");
    }

    @Override
    public String strategyName() {
        return "EmbeddingPrototypeRouter";
    }
}
