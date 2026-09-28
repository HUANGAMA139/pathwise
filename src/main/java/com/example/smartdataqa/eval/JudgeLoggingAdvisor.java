package com.example.smartdataqa.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 把<b>框架判分器到底说了什么</b>打出来的小 advisor（迭代 15 起，迭代 23 抽成独立类给报告复用）。
 *
 * <p><b>为什么非要有它：</b>{@code RelevancyEvaluator} 和 {@code FactCheckingEvaluator}
 * 内部都是拿模型回答跟 {@code "yes"} 做<b>精确比较</b>，然后把原始输出<b>丢掉</b>
 * （只留下一个 boolean）。于是它们坏了不报错，只会给出一个错误的分数。
 *
 * <p>迭代 15 实测撞过：basic-rag 在 D03 上「答案与标准答案完全一致」（自写判分 1.00），
 * 框架的忠实性却判 0.00——一个完全正确的答案不可能不被上下文支持。到底是模型回了
 * 「Yes.」「是」这种带修饰的形式，还是它真的判了不支持，<b>不把原始输出打出来就永远分不清</b>。
 *
 * <p>挂在框架判分器专用的 ChatClient 上（不影响业务链路的调用）。
 */
public record JudgeLoggingAdvisor() implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(JudgeLoggingAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        ChatResponse cr = response.chatResponse();
        String text = (cr == null || cr.getResult() == null)
                ? "(无输出)" : cr.getResult().getOutput().getText();
        // 换行替成可见标记，保证一条日志一行
        log.info("      [框架判分] 原始输出：{}",
                text == null ? "(null)" : text.replace("\n", "\\n"));
        return response;
    }

    @Override
    public String getName() {
        return "JudgeLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
