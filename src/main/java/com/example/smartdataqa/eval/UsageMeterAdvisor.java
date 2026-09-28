package com.example.smartdataqa.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 迭代 24 · 把每次模型调用的 token 用量记进 {@link TokenMeter}。
 *
 * <p>做成 advisor 而不是改 {@code LlmQueryRouter}，是因为<b>计量不该长在业务类里</b>：
 * 挂上去就量，摘下来就不量，路由本身一行不用动。
 * （同 {@link JudgeLoggingAdvisor} 的思路——那也是从外面看进去，而不是让被观察者自己汇报。）
 *
 * <p><b>拿不到 usage 时不装作 0</b>：调 {@link TokenMeter#missingUsage()} 记一笔，
 * 报告里会把「几次没拿到用量」打出来。否则「成本 0」会和「没花钱」长得一模一样。
 */
public record UsageMeterAdvisor(TokenMeter meter) implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(UsageMeterAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        try {
            ChatResponse cr = response.chatResponse();
            Usage usage = (cr == null || cr.getMetadata() == null) ? null : cr.getMetadata().getUsage();
            if (usage == null) {
                this.meter.missingUsage();
                log.warn("      [计量] 这次调用没有 usage 信息，token 记 0（不是真的 0）");
                return response;
            }
            Integer p = usage.getPromptTokens();
            Integer c = usage.getCompletionTokens();
            this.meter.add(p == null ? 0L : p.longValue(), c == null ? 0L : c.longValue());
        }
        catch (RuntimeException e) {
            // 计量挂了不能把业务调用带下水 —— 但要看得见
            this.meter.missingUsage();
            log.warn("      [计量] 读 usage 失败，token 记 0：{}", e.toString());
        }
        return response;
    }

    @Override
    public String getName() {
        return "UsageMeterAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
