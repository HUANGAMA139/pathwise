package com.example.smartdataqa.rag.pipeline;

/**
 * 迭代 26 · <b>改写器的「关」档</b>：原样返回。
 *
 * <p><b>为什么用一个类而不是 {@code null}</b>：把它做成空对象（null object），
 * 管道里就<b>不需要到处判空</b>——「关」和「开」在代码形状上完全一样，只有行为不同。
 * 这正是「每层可插拔」要的效果：换层不用改调用方。
 *
 * <p>它同时是消融实验的<b>基线档</b>：{@code none} 就是 迭代 14—17 的形态。
 */
public final class NoOpQueryRewriter implements QueryRewriter {

    @Override
    public String rewrite(String question) {
        return question;
    }

    @Override
    public String name() {
        return "none";
    }
}
