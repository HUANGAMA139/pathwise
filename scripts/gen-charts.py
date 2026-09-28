#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
迭代 28 · 消融实验的图表生成。

读 eval/ 下的 CSV，出 PNG 到 docs/ 下（README 用 `![](docs/fig-xxx.png)` 直接引用）。

为什么要脚本化、而不是贴一张手画的图：
  **图会随着数据变，手画的图不会。** 跑完端到端消融、加一行 CSV，重跑这个脚本，
  图就跟着更新 —— README 里的图和数字永远不会对不上。

用法（在项目根目录）：
    python scripts/gen-charts.py

三张图：
  fig-retrieval-ablation.png   每层「关 / 开」对 MRR 的影响（数据来自 eval/rag-ablation.csv）
  fig-router-compare.png       5 个路由策略的准确率 × 延迟（数据来自 eval/router-compare.csv）
  fig-e2e-ablation.png         端到端六类指标在不同配置下的对比（数据来自 eval/report.csv，
                               需要先用 --sdaq.eval.experiment=exp28-xxx 跑过）

输出一律【不打印中文】——用户的 Windows 控制台是 GBK，这里没必要冒那个险。
"""

import csv
import os
import sys

import matplotlib
matplotlib.use("Agg")           # 无显示环境
import matplotlib.pyplot as plt

# 中文字体：图里的标签是中文，必须显式设字体，否则是豆腐块
matplotlib.rcParams["font.sans-serif"] = ["Noto Sans CJK JP", "Droid Sans Fallback", "DejaVu Sans"]
matplotlib.rcParams["axes.unicode_minus"] = False

DOCS = "docs"
EVAL = "eval"

BASE = "#2f5d8a"   # 「关」/ 基线
ON = "#c9873a"     # 「开」/ 对照


def read(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def fig_retrieval_ablation():
    """每层「关 / 开」对 MRR 的影响。

    数据直接写在代码里 —— 因为它来自 eval/rag-ablation.csv 的**成对比较**，
    写成显式的常量比在脚本里再算一遍更不容易错（哪一行配哪一行是人工挑的）。
    """
    pairs = [
        ("重排\n(vector 上)", 0.906, 0.922),      # rerank none -> llm
        ("混合检索\n(不精排时)", 0.906, 0.847),    # vector -> hybrid（这里是变差）
        ("改写\n(bm25 上)", 0.756, 0.922),        # rewrite none -> llm
        ("改写\n(vector 上)", 0.906, 0.902),      # rewrite none -> llm（几乎没用）
    ]
    labels = [p[0] for p in pairs]
    off = [p[1] for p in pairs]
    on = [p[2] for p in pairs]
    x = range(len(pairs))

    fig, ax = plt.subplots(figsize=(9, 4.6))
    w = 0.36
    b1 = ax.bar([i - w / 2 for i in x], off, w, label="未启用该层", color=BASE)
    b2 = ax.bar([i + w / 2 for i in x], on, w, label="启用该层", color=ON)
    for bars in (b1, b2):
        for r in bars:
            ax.annotate(f"{r.get_height():.3f}", (r.get_x() + r.get_width() / 2, r.get_height()),
                        ha="center", va="bottom", fontsize=9)
    ax.set_xticks(list(x))
    ax.set_xticklabels(labels, fontsize=9)
    ax.set_ylabel("MRR（15 道 document 题）")
    ax.set_ylim(0.70, 0.98)
    ax.set_title("检索层消融：每一层「关 / 开」的效果（迭代 26 实跑）")
    ax.legend()
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    out = os.path.join(DOCS, "fig-retrieval-ablation.png")
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print("wrote " + out)


def fig_router_compare():
    rows = read(os.path.join(EVAL, "router-compare.csv"))
    if rows is None:
        print("skip router chart: no eval/router-compare.csv")
        return None

    # 每个策略一行（重复跑时取最后一轮）
    last = {}
    for r in rows:
        last[r["strategy"]] = r
    order = ["A LLM+清单", "A2 LLM无清单", "C 混合", "B 关键词规则", "D embedding原型"]
    names = [s for s in order if s in last]
    acc, lat = [], []
    for s in names:
        g = [r for r in rows if r["strategy"] == s]
        ok = sum(1 for r in g if r["correct"] == "1")
        acc.append(100.0 * ok / len(g))
        lats = []
        for r in g:
            try:
                lats.append(float(r["latency_ms"]))
            except ValueError:
                pass
        lat.append(sum(lats) / len(lats) if lats else 0.0)

    x = range(len(names))
    fig, ax1 = plt.subplots(figsize=(9, 4.6))
    b = ax1.bar([i - 0.2 for i in x], acc, 0.4, label="准确率 (%)", color=BASE)
    for r in b:
        ax1.annotate(f"{r.get_height():.0f}", (r.get_x() + r.get_width() / 2, r.get_height()),
                     ha="center", va="bottom", fontsize=9)
    ax1.set_ylabel("准确率 (%)", color=BASE)
    ax1.set_ylim(0, 118)
    ax1.tick_params(axis="y", labelcolor=BASE)

    ax2 = ax1.twinx()
    b2 = ax2.bar([i + 0.2 for i in x], lat, 0.4, label="平均延迟 (ms)", color=ON)
    for r in b2:
        ax2.annotate(f"{r.get_height():.0f}", (r.get_x() + r.get_width() / 2, r.get_height()),
                     ha="center", va="bottom", fontsize=9)
    ax2.set_ylabel("平均延迟 (ms)", color=ON)
    ax2.set_ylim(0, 4200)
    ax2.tick_params(axis="y", labelcolor=ON)

    ax1.set_xticks(list(x))
    ax1.set_xticklabels([n.replace(" ", "\n", 1) for n in names], fontsize=8.5)
    ax1.set_title("路由策略：准确率 vs 延迟（28 题，迭代 24 实跑）")
    fig.tight_layout()
    out = os.path.join(DOCS, "fig-router-compare.png")
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print("wrote " + out)
    return names


def fig_e2e_ablation():
    """端到端消融：需要先用 --sdaq.eval.experiment=exp28-xxx 跑过几次。"""
    rows = read(os.path.join(EVAL, "report.csv"))
    if rows is None:
        print("skip e2e chart: no eval/report.csv")
        return
    # 只取 exp28-* 的实验（每种实验取最后一次）
    picks = {}
    for r in rows:
        if r["experiment"].startswith("exp28-"):
            picks[r["experiment"]] = r
    if len(picks) < 2:
        print("skip e2e chart: need >=2 experiments named 'exp28-*' in eval/report.csv")
        return

    exps = sorted(picks.keys())
    short = [e.replace("exp28-", "") for e in exps]

    def pct(v):
        try:
            return 100.0 * float(v.split()[0])
        except Exception:
            return 0.0

    metrics = [
        ("路由准确率", lambda r: pct(r["route_accuracy"])),
        ("端到端正确率", lambda r: pct(r["e2e_correct"])),
        ("执行准确率", lambda r: pct(r["exec_accuracy"])),
        ("检索精确(LLM)", lambda r: pct(r["ctx_precision_llm"])),
    ]
    x = range(len(exps))
    n = len(metrics)
    w = 0.8 / n
    fig, ax = plt.subplots(figsize=(9.5, 4.8))
    colors = ["#2f5d8a", "#c9873a", "#5b8c5a", "#8a5b8c"]
    for i, (label, fn) in enumerate(metrics):
        vals = [fn(picks[e]) for e in exps]
        bars = ax.bar([xx - 0.4 + w * (i + 0.5) for xx in x], vals, w, label=label, color=colors[i % 4])
        for r in bars:
            ax.annotate(f"{r.get_height():.0f}", (r.get_x() + r.get_width() / 2, r.get_height()),
                        ha="center", va="bottom", fontsize=7.5)
    ax.set_xticks(list(x))
    ax.set_xticklabels(short, fontsize=9)
    ax.set_ylabel("(%)")
    ax.set_ylim(0, 112)
    ax.set_title("端到端消融：去掉某一层后，六类指标怎么变（迭代 23 脚手架）")
    ax.legend(fontsize=8.5)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    out = os.path.join(DOCS, "fig-e2e-ablation.png")
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print("wrote " + out)


def main():
    if not os.path.isdir(DOCS):
        print("run me from the project root (no docs/ here)")
        return 1
    fig_retrieval_ablation()
    fig_router_compare()
    fig_e2e_ablation()
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
