package com.example.smartdataqa.tools;

/**
 * 数据库查询支路。给一个自然语言问题，返回查询结果。
 *
 * <p>迭代 8 是 mock，迭代 18 会换成真实的 Text2SQL：
 * 表结构注入 → 生成 SQL → 执行 → 失败自修复。
 *
 * <p><b>注意这个接口的签名设计：入参是「自然语言问题」而不是「SQL」。</b>
 * 因为「把问题翻译成 SQL」这件事属于这条支路的内部实现，
 * 不该暴露给调用方。Agent 主干只需要说「这个问题帮我查一下数据」，
 * 至于中间生成几条 SQL、试错几次，是 DatabaseTool 自己的事。
 *
 * <p>这也是 迭代 21 安全边界的落点——权限检查、只读白名单、行数上限，
 * 全都封装在这个接口的实现里。调用方想绕过也绕不过去。
 */
public interface DatabaseTool {

    /**
     * 用自然语言问题查询业务数据。
     *
     * @param question 用户的原始问题
     * @return 查询结果，<b>永远带上实际执行的 SQL</b>（见 {@link QueryResult#sql()}）
     */
    QueryResult query(String question);
}
