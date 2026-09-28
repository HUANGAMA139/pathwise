# 为什么 Agent 不等于 if-else 编排

> 迭代 1 笔记 · 读完 Anthropic《Building Effective Agents》与 Hello-Agents 第一章之后
> 来源：[Building Effective Agents 中文全译](https://www.fancyoung.com/posts/agentic-coding-classics/anthropic-building-effective-agents-fulltext/) · [Hello-Agents 第一章](https://github.com/datawhalechina/hello-agents/blob/main/docs/chapter1/%E7%AC%AC%E4%B8%80%E7%AB%A0%20%E5%88%9D%E8%AF%86%E6%99%BA%E8%83%BD%E4%BD%93.md)

## 我原来以为的

读这两篇之前，我以为 Agent 无非就是流程更复杂的 if-else——分支多一点，判断条件换成大模型给的而已。读完发现，恰恰是这个理解漏掉了最关键的地方。

## 真正的区别：决策权在谁手里

if-else 的分支是**我写代码的时候就定死的**。哪种情况走哪条路，代码写完，路径也就固定了。

Agent 不是。它的循环骨架确实只是固定的一小段：

```
循环 {
    模型看着当前掌握的信息和可用的工具，决定下一步调哪个
    我执行它选的那个工具
    把结果塞回去
}
```

骨架是我写死的，但**走哪条分支不是**——所有分支的集合根本不在代码里，而是模型看着当前上下文现场生成的。

一句话：**if-else 把决策写进代码，Agent 把决策交给模型。**

## 用风控下单举例

传统写法是一条固定链路：查黑名单 → 查历史订单 → 算风险分 → 超阈值转人工。每一步走不走、走哪条，都是我写代码的时候定死的。

换成 Agent，就是把这些查询都做成工具，只留一个循环，让模型根据这笔订单的具体情况，自己决定查哪几项、发现可疑后要不要继续往下挖。

**为什么 if-else 做不到**：风控真正难的地方不是规则多，而是**你没法穷举所有可疑特征的组合**。手法每周都在变，写规则永远滞后于攻击者。Agent 把「该看哪些信号」这个判断交了出去，所以能处理我压根没预想到的组合。

## 但也不是所有事都该用 Agent

反过来说，支付扣款这类流程，我绝对不会交给 Agent——它必须确定性、可审计、不能有幻觉。这种地方，if-else 才是对的答案。

所以边界是：**流程固定、要求高可靠的地方用工作流；路径没法穷举的地方，才值得上 Agent。**

Agent 不是「更高级的 if-else」，它是用来处理「分支写不完」这类问题的另一类工具。
