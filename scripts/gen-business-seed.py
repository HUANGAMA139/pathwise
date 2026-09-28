#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
迭代 18 · 业务库种子数据生成器 + 基准答案计算器

它干两件事（故意的，因为它们是同一份数据的两种用途）：

  1. 生成 docker/init-business-db.sql
     —— 5 张表 + 外键 + 字段注释 + 种子数据，供 docker 初始化业务库。

  2. 算出 迭代 18 诊断 demo 里那几道题的【期望答案】
     —— 手算 19 单的净销售额很容易错，而且错了看不出来（demo 会拿它当判据）。
        所以期望答案必须由脚本算出来，不能手写。

用法：
  python3 scripts/gen-business-seed.py            # 写 SQL，并打印基准答案
  python3 scripts/gen-business-seed.py --check    # 只打印基准答案

数据设计（和 6 份语料咬合，这是本项目的硬要求，见 docs/corpus.md）：
  · region 字段存【区域名】不存省份 —— 语料 02
  · region_provinces 是语料 02 里说的那张「额外的映射关系」，且 山东→华北（不是华东）
  · orders_archive 是语料 04 点名的「历史归档表，不要用于当期统计」—— 它就是那个陷阱
  · completed_at 才是归属期间的依据，created_at 不是 —— 语料 01 / 04
  · REFUNDED 的订单仍计入统计（净额 0），CANCELLED/CREATED/PAID 不计入 —— 语料 01
  · refund_amount 可为 NULL —— 语料 04
  · 故意留一条「创建在 Q1、完成在 Q2」的单，用来暴露 created_at 写错会差多少
"""

import sys
import os

# ----------------------------------------------------------------------------
# 一、维度数据
# ----------------------------------------------------------------------------

REGIONS = ["华东", "华南", "华北", "华中", "西部", "东北"]

# 语料 02《销售区域划分规则》第一节的原表。
# 注意两个坑：福建在华东（2025 年起由华南划归），山东在华北（不是行政区划的华东）。
REGION_PROVINCES = [
    ("上海", "华东"), ("江苏", "华东"), ("浙江", "华东"), ("安徽", "华东"), ("福建", "华东"),
    ("广东", "华南"), ("广西", "华南"), ("海南", "华南"),
    ("北京", "华北"), ("天津", "华北"), ("河北", "华北"), ("山西", "华北"),
    ("内蒙古", "华北"), ("山东", "华北"),
    ("河南", "华中"), ("湖北", "华中"), ("湖南", "华中"), ("江西", "华中"),
    ("四川", "西部"), ("重庆", "西部"), ("云南", "西部"), ("贵州", "西部"), ("陕西", "西部"),
    ("甘肃", "西部"), ("青海", "西部"), ("宁夏", "西部"), ("新疆", "西部"), ("西藏", "西部"),
    ("辽宁", "东北"), ("吉林", "东北"), ("黑龙江", "东北"),
]

# 语料 05《产品线与编码规则》第三节的型号表。
# XR-2000B / XR-2000C 只差最后一个字母，是语料里刻意埋的「近似编码」素材。
# 注意 products 表里【没有】逗号分隔的 line_name —— 产品线就是 line_code 前缀，见语料 04。
PRODUCTS = [
    ("XR-2000A", "XR 标准版",     "XR", 3999,   "DISCONTINUED"),
    ("XR-2000B", "XR 标准版升级", "XR", 4299,   "ON_SALE"),
    ("XR-2000C", "XR 标准版增强", "XR", 5299,   "ON_SALE"),
    ("XR-2010B", "XR 长续航版",   "XR", 4699,   "ON_SALE"),
    ("XR-3000A", "XR 旗舰版",     "XR", 8999,   "ON_SALE"),
    ("MT-1000B", "工业模组 B 型", "MT", 1899,   "ON_SALE"),
    ("SX-0101",  "通用配件套装",  "SX", 199,    "ON_SALE"),
    ("CUS-0001", "定制机型",      "CUS", 29999, "ON_SALE"),
]

# ----------------------------------------------------------------------------
# 二、订单数据
# ----------------------------------------------------------------------------
# 字段：(order_id, region, product_code, order_amount, refund_amount, status,
#         created_at, completed_at)
#   order_amount / refund_amount 单位：元（整数，避免浮点误差）
#   refund_amount = None 表示数据库里是 NULL（语料 04 专门强调要 IFNULL）
#   completed_at = None 表示未完成（只有 CREATED/PAID/CANCELLED 才是 NULL）
#
# 期间分布：sales_orders 只放 2025Q1 起；2024 及以前在 orders_archive。
# 今天（2026-09-26）是 2026Q3 中途，所以 Q3 有「还未完成」的单——这是真实形态。
ORDERS = [
    # ---------------- 2026 Q1（1—3 月）----------------
    ("ORD-20260101", "华东", "XR-2000B", 4300000, None,       "COMPLETED", "2026-01-12", "2026-01-20"),
    ("ORD-20260102", "华东", "XR-3000A", 9000000, None,       "COMPLETED", "2026-02-05", "2026-02-13"),
    ("ORD-20260103", "华东", "XR-2000C", 5300000, 300000,     "COMPLETED", "2026-03-02", "2026-03-10"),
    ("ORD-20260104", "华南", "XR-2000B", 4300000, None,       "COMPLETED", "2026-01-22", "2026-01-30"),
    ("ORD-20260105", "华南", "XR-2010B", 4700000, None,       "COMPLETED", "2026-02-18", "2026-02-26"),
    ("ORD-20260106", "华北", "XR-2000B", 4300000, None,       "COMPLETED", "2026-02-09", "2026-02-17"),
    ("ORD-20260107", "华中", "SX-0101",  80000,   None,       "COMPLETED", "2026-03-15", "2026-03-23"),
    ("ORD-20260108", "西部", "MT-1000B", 900000,  None,       "CANCELLED", "2026-03-20", None),

    # ---------------- 2026 Q2（4—6 月）----------------
    # 【陷阱单】创建在 3 月（Q1）、完成在 4 月（Q2）—— 归属期间看 completed_at。
    #   按 completed_at 算：它在 Q2；按 created_at 算：它会跑到 Q1。
    #   一句话写错，Q1 多 900 万、Q2 少 900 万。
    ("ORD-20260201", "华东", "XR-3000A", 9000000, None,       "COMPLETED", "2026-03-28", "2026-04-05"),
    ("ORD-20260202", "华东", "XR-2000B", 4300000, None,       "COMPLETED", "2026-04-08", "2026-04-15"),
    ("ORD-20260203", "华东", "XR-2000C", 5300000, 300000,     "COMPLETED", "2026-04-20", "2026-04-28"),
    ("ORD-20260204", "华东", "XR-3000A", 9000000, None,       "COMPLETED", "2026-05-03", "2026-05-11"),
    # 全额退货：订单金额与退货金额相等，净额 0，但【仍计入统计】—— 语料 01 / 03
    ("ORD-20260205", "华东", "MT-1000B", 1900000, 1900000,    "REFUNDED",  "2026-05-12", "2026-05-20"),
    ("ORD-20260206", "华东", "XR-2000B", 4300000, None,       "COMPLETED", "2026-05-25", "2026-06-02"),
    ("ORD-20260207", "华东", "XR-2010B", 4700000, 200000,     "COMPLETED", "2026-06-05", "2026-06-14"),
    ("ORD-20260208", "华东", "SX-0101",  200000,  None,       "PAID",      "2026-06-20", None),
    ("ORD-20260209", "华东", "XR-2000C", 5300000, None,       "CANCELLED", "2026-06-22", None),

    ("ORD-20260210", "华南", "XR-2000B", 4300000, None,       "COMPLETED", "2026-04-11", "2026-04-19"),
    ("ORD-20260211", "华南", "XR-2000C", 5300000, 500000,     "COMPLETED", "2026-05-06", "2026-05-13"),
    ("ORD-20260212", "华南", "SX-0101",  150000,  None,       "COMPLETED", "2026-05-18", "2026-05-26"),
    ("ORD-20260213", "华南", "XR-3000A", 8000000, None,       "COMPLETED", "2026-06-01", "2026-06-09"),
    ("ORD-20260214", "华南", "MT-1000B", 1900000, None,       "CREATED",   "2026-06-25", None),

    ("ORD-20260215", "华北", "XR-2000B", 4300000, None,       "COMPLETED", "2026-04-14", "2026-04-22"),
    ("ORD-20260216", "华北", "XR-2010B", 4700000, 700000,     "COMPLETED", "2026-05-09", "2026-05-17"),
    ("ORD-20260217", "华北", "SX-0101",  120000,  None,       "COMPLETED", "2026-06-11", "2026-06-19"),

    ("ORD-20260218", "华中", "XR-2000B", 3200000, None,       "COMPLETED", "2026-04-25", "2026-05-03"),
    ("ORD-20260219", "华中", "SX-0101",  90000,   None,       "COMPLETED", "2026-06-15", "2026-06-23"),

    ("ORD-20260220", "西部", "XR-2000B", 2100000, 100000,     "COMPLETED", "2026-05-02", "2026-05-10"),
    ("ORD-20260221", "西部", "MT-1000B", 800000,  None,       "COMPLETED", "2026-06-18", "2026-06-26"),

    ("ORD-20260222", "东北", "XR-2000B", 1500000, None,       "COMPLETED", "2026-05-20", "2026-05-28"),

    # ---------------- 2026 Q3（7—9 月，进行中）----------------
    ("ORD-20260301", "华东", "XR-3000A", 9000000, None,       "COMPLETED", "2026-07-08", "2026-07-16"),
    ("ORD-20260302", "华东", "XR-2000B", 4300000, None,       "COMPLETED", "2026-08-11", "2026-08-19"),
    ("ORD-20260303", "华南", "XR-2000C", 5300000, None,       "COMPLETED", "2026-07-22", "2026-07-30"),
    ("ORD-20260304", "华北", "XR-2000B", 4300000, None,       "COMPLETED", "2026-08-25", "2026-09-02"),
    ("ORD-20260305", "华东", "XR-2000C", 5300000, None,       "PAID",      "2026-09-15", None),
    ("ORD-20260306", "华东", "XR-3000A", 9000000, None,       "CREATED",   "2026-09-22", None),

    # ---------------- 2025（同比用的历史）----------------
    ("ORD-20250101", "华东", "XR-2000B", 3900000, None,       "COMPLETED", "2025-01-15", "2025-01-23"),
    ("ORD-20250102", "华南", "XR-2000B", 3900000, None,       "COMPLETED", "2025-02-10", "2025-02-18"),
    ("ORD-20250103", "华北", "XR-2000A", 3500000, None,       "COMPLETED", "2025-03-05", "2025-03-13"),
    ("ORD-20250201", "华东", "XR-2000B", 4100000, None,       "COMPLETED", "2025-04-18", "2025-04-26"),
    ("ORD-20250202", "华东", "XR-2000C", 5100000, 200000,     "COMPLETED", "2025-05-22", "2025-05-30"),
    ("ORD-20250203", "华南", "XR-2000B", 4100000, None,       "COMPLETED", "2025-06-09", "2025-06-17"),
    ("ORD-20250301", "华东", "XR-2000B", 4200000, None,       "COMPLETED", "2025-07-14", "2025-07-22"),
    ("ORD-20250302", "华中", "XR-2000B", 3100000, None,       "COMPLETED", "2025-08-19", "2025-08-27"),
    ("ORD-20250303", "华东", "XR-3000A", 8800000, 300000,     "COMPLETED", "2025-09-10", "2025-09-18"),
    ("ORD-20250401", "华东", "XR-2000B", 4250000, None,       "COMPLETED", "2025-10-16", "2025-10-24"),
    ("ORD-20250402", "华南", "XR-2000B", 4250000, None,       "COMPLETED", "2025-11-20", "2025-11-28"),
    ("ORD-20250403", "华东", "XR-2000C", 5200000, None,       "COMPLETED", "2025-12-08", "2025-12-16"),
]

# 2024 及以前 → 归档表。
# 语料 02 说 2025 年起福建由华南划归华东 —— 但库里【没有省份字段】，
# 所以这件事在数据上只体现为「2024 华南偏大、2025 起华东变大」，逐单查不出来。
# 这正是语料 02 第三节那句「如果要按省份分析，需要额外的映射关系」的意思。
ARCHIVED_ORDERS = [
    ("ORD-20240101", "华东", "XR-2000A", 3500000, None,   "COMPLETED", "2024-01-18", "2024-01-26"),
    ("ORD-20240102", "华南", "XR-2000A", 3500000, None,   "COMPLETED", "2024-02-14", "2024-02-22"),
    ("ORD-20240103", "华北", "XR-2000A", 3400000, None,   "COMPLETED", "2024-03-11", "2024-03-19"),
    ("ORD-20240201", "华东", "XR-2000A", 3600000, 100000, "COMPLETED", "2024-04-20", "2024-04-28"),
    ("ORD-20240202", "华南", "XR-2000B", 4000000, None,   "COMPLETED", "2024-05-16", "2024-05-24"),
    ("ORD-20240203", "华南", "XR-2000A", 3600000, None,   "COMPLETED", "2024-06-12", "2024-06-20"),
    ("ORD-20240301", "华东", "XR-2000A", 3600000, None,   "COMPLETED", "2024-07-19", "2024-07-27"),
    ("ORD-20240302", "华南", "XR-2000B", 4000000, 200000, "COMPLETED", "2024-08-23", "2024-08-31"),
    ("ORD-20240303", "华北", "XR-2000A", 3500000, None,   "COMPLETED", "2024-09-17", "2024-09-25"),
    ("ORD-20240401", "华东", "XR-2000A", 3700000, None,   "COMPLETED", "2024-10-21", "2024-10-29"),
    ("ORD-20240402", "华南", "XR-2000B", 4000000, None,   "COMPLETED", "2024-11-15", "2024-11-23"),
    ("ORD-20240403", "华东", "XR-2000B", 4000000, None,   "COMPLETED", "2024-12-10", "2024-12-18"),
]

# 计入统计的状态：语料 01 第三节 —— COMPLETED 和 REFUNDED 计，其余不计。
COUNTED_STATUS = ("COMPLETED", "REFUNDED")

# ----------------------------------------------------------------------------
# 三、SQL 生成
# ----------------------------------------------------------------------------

HEADER = """\
-- ============================================================================
-- smart-data-qa · 业务库初始化脚本
-- ----------------------------------------------------------------------------
-- 由 scripts/gen-business-seed.py 生成，请勿手改（改数据请改脚本）。
--
-- 只在容器【首次初始化】（数据卷为空）时自动执行一次。
-- 如果 business-db 容器早就起过了，数据卷已存在，这个脚本不会重跑 ——
-- 要重建请见 docs/18-Text2SQL原理与Schema注入.md 第二节。
--
-- 5 张表、3 条外键，全部与 6 份语料咬合：
--   regions            区域（6 个），语料 02
--   region_provinces   区域↔省份映射，语料 02 说的「额外的映射关系」
--   products           产品与型号，语料 05
--   sales_orders       当期订单（2025 Q1 起），语料 01 / 04
--   orders_archive     历史归档（2024 及以前）—— 语料 04 点名的陷阱表
-- ============================================================================

-- 幂等：重复执行可重建（手工重灌时用）
DROP TABLE IF EXISTS orders_archive CASCADE;
DROP TABLE IF EXISTS sales_orders CASCADE;
DROP TABLE IF EXISTS region_provinces CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS regions CASCADE;

-- ---------------------------------------------------------------------------
-- regions · 销售区域（语料 02《销售区域划分规则》）
-- ---------------------------------------------------------------------------
CREATE TABLE regions (
    region_name   varchar(16) PRIMARY KEY,
    display_order int         NOT NULL
);

COMMENT ON TABLE  regions              IS '销售区域字典';
COMMENT ON COLUMN regions.region_name  IS '区域名，中文';
COMMENT ON COLUMN regions.display_order IS '报表展示顺序';

INSERT INTO regions (region_name, display_order) VALUES
"""


def q(s):
    """SQL 字符串字面量。数据里没有单引号，直接包住即可。"""
    return "'" + s + "'"


def nullable(v):
    return "NULL" if v is None else str(v)


def build_sql():
    out = [HEADER]
    out.append(",\n".join("    (%s, %d)" % (q(r), i + 1) for i, r in enumerate(REGIONS)))
    out.append(";\n\n")

    # ---- region_provinces ----
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("-- region_provinces · 区域↔省份映射（语料 02 第一节的原表）\n")
    out.append("-- 两个坑：福建→华东（2025 年起由华南划归）；山东→华北（不是行政区划的华东）。\n")
    out.append("-- 语料 02 第三节明说：订单表的 region 存的是【区域名】不是省份名。\n")
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("CREATE TABLE region_provinces (\n")
    out.append("    province_name varchar(16) PRIMARY KEY,\n")
    out.append("    region_name   varchar(16) NOT NULL REFERENCES regions(region_name)\n")
    out.append(");\n\n")
    out.append("COMMENT ON TABLE  region_provinces               IS '省份到销售区域的映射';\n")
    out.append("COMMENT ON COLUMN region_provinces.province_name IS '省份名';\n")
    out.append("COMMENT ON COLUMN region_provinces.region_name   IS '所属销售区域名';\n\n")
    out.append("INSERT INTO region_provinces (province_name, region_name) VALUES\n")
    out.append(",\n".join("    (%s, %s)" % (q(p), q(r)) for p, r in REGION_PROVINCES))
    out.append(";\n\n")

    # ---- products ----
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("-- products · 产品（语料 04 第二节的字段，语料 05 的型号表）\n")
    out.append("-- XR-2000B 与 XR-2000C 只差最后一个字母 —— 语料 05 的近似编码素材。\n")
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("CREATE TABLE products (\n")
    out.append("    product_code varchar(16)  PRIMARY KEY,\n")
    out.append("    product_name varchar(64)  NOT NULL,\n")
    out.append("    line_code    varchar(8)   NOT NULL,\n")
    out.append("    list_price   numeric(14,2) NOT NULL,\n")
    out.append("    sale_status  varchar(16)  NOT NULL\n")
    out.append(");\n\n")
    out.append("COMMENT ON TABLE  products              IS '产品字典';\n")
    out.append("COMMENT ON COLUMN products.product_code IS '产品编码，格式 <产品线前缀>-<代数><序号><变体>';\n")
    out.append("COMMENT ON COLUMN products.product_name IS '产品名称';\n")
    out.append("COMMENT ON COLUMN products.line_code    IS '产品线编码：XR/MT/SX/CUS';\n")
    out.append("COMMENT ON COLUMN products.list_price   IS '标准单价（不含税）';\n")
    out.append("COMMENT ON COLUMN products.sale_status  IS '销售状态：ON_SALE/DISCONTINUED';\n\n")
    out.append("INSERT INTO products (product_code, product_name, line_code, list_price, sale_status) VALUES\n")
    rows = []
    for code, name, line, price, st in PRODUCTS:
        rows.append("    (%s, %s, %s, %d.00, %s)" % (q(code), q(name), q(line), price, q(st)))
    out.append(",\n".join(rows))
    out.append(";\n\n")

    # ---- sales_orders ----
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("-- sales_orders · 当期订单（2025 Q1 起）—— 语料 01 / 04\n")
    out.append("--   · 归属期间看 completed_at，不看 created_at（语料 01 第二节，历史踩坑点）\n")
    out.append("--   · 计入统计的状态只有 COMPLETED / REFUNDED；REFUNDED 净额为 0 仍计入\n")
    out.append("--   · refund_amount 可为 NULL（语料 04 第三节）\n")
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("CREATE TABLE sales_orders (\n")
    out.append("    order_id      varchar(16)  PRIMARY KEY,\n")
    out.append("    region        varchar(16)  NOT NULL REFERENCES regions(region_name),\n")
    out.append("    product_code  varchar(16)  NOT NULL REFERENCES products(product_code),\n")
    out.append("    order_amount  numeric(14,2) NOT NULL,\n")
    out.append("    refund_amount numeric(14,2),\n")
    out.append("    status        varchar(16)  NOT NULL,\n")
    out.append("    created_at    timestamp    NOT NULL,\n")
    out.append("    completed_at  timestamp\n")
    out.append(");\n\n")
    out.append("COMMENT ON TABLE  sales_orders               IS '当期销售订单';\n")
    out.append("COMMENT ON COLUMN sales_orders.order_id      IS '订单号，ORD- + 8 位数字';\n")
    out.append("COMMENT ON COLUMN sales_orders.region        IS '销售区域名，取值见 regions';\n")
    out.append("COMMENT ON COLUMN sales_orders.product_code  IS '产品编码';\n")
    out.append("COMMENT ON COLUMN sales_orders.order_amount  IS '订单金额（不含税）';\n")
    out.append("COMMENT ON COLUMN sales_orders.refund_amount IS '退货金额（不含税），可为空';\n")
    out.append("COMMENT ON COLUMN sales_orders.status        IS '订单状态：CREATED/PAID/COMPLETED/REFUNDED/CANCELLED';\n")
    out.append("COMMENT ON COLUMN sales_orders.created_at    IS '下单时间';\n")
    out.append("COMMENT ON COLUMN sales_orders.completed_at  IS '订单完成时间，未完成时为 NULL';\n\n")
    out.append("INSERT INTO sales_orders (order_id, region, product_code, order_amount, refund_amount, status, created_at, completed_at) VALUES\n")
    rows = []
    for oid, region, code, amt, refund, st, created, completed in ORDERS:
        rows.append("    (%s, %s, %s, %d.00, %s, %s, %s, %s)" % (
            q(oid), q(region), q(code), amt, nullable(refund), q(st),
            q(created) if created else "NULL",
            q(completed) if completed else "NULL"))
    out.append(",\n".join(rows))
    out.append(";\n\n")

    # ---- orders_archive ----
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("-- orders_archive · 历史归档（2024 及以前）\n")
    out.append("-- 【陷阱】语料 04 第四节明说：「不要用它做当期统计」。\n")
    out.append("--   只靠表结构，模型看不出这一点 —— 这条知识在文档里，不在库里。\n")
    out.append("--   刻意不加外键：归档表通常脱开约束，也可能含已删产品。\n")
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("CREATE TABLE orders_archive (\n")
    out.append("    order_id      varchar(16)  PRIMARY KEY,\n")
    out.append("    region        varchar(16)  NOT NULL,\n")
    out.append("    product_code  varchar(16)  NOT NULL,\n")
    out.append("    order_amount  numeric(14,2) NOT NULL,\n")
    out.append("    refund_amount numeric(14,2),\n")
    out.append("    status        varchar(16)  NOT NULL,\n")
    out.append("    created_at    timestamp    NOT NULL,\n")
    out.append("    completed_at  timestamp,\n")
    out.append("    archived_at   timestamp    NOT NULL DEFAULT now()\n")
    out.append(");\n\n")
    out.append("COMMENT ON TABLE  orders_archive             IS '历史订单归档表';\n")
    out.append("COMMENT ON COLUMN orders_archive.order_id    IS '订单号';\n")
    out.append("COMMENT ON COLUMN orders_archive.region      IS '销售区域名';\n")
    out.append("COMMENT ON COLUMN orders_archive.product_code IS '产品编码';\n")
    out.append("COMMENT ON COLUMN orders_archive.order_amount IS '订单金额（不含税）';\n")
    out.append("COMMENT ON COLUMN orders_archive.refund_amount IS '退货金额（不含税），可为空';\n")
    out.append("COMMENT ON COLUMN orders_archive.status      IS '订单状态';\n")
    out.append("COMMENT ON COLUMN orders_archive.created_at  IS '下单时间';\n")
    out.append("COMMENT ON COLUMN orders_archive.completed_at IS '订单完成时间';\n")
    out.append("COMMENT ON COLUMN orders_archive.archived_at IS '归档时间';\n\n")
    out.append("INSERT INTO orders_archive (order_id, region, product_code, order_amount, refund_amount, status, created_at, completed_at) VALUES\n")
    rows = []
    for oid, region, code, amt, refund, st, created, completed in ARCHIVED_ORDERS:
        rows.append("    (%s, %s, %s, %d.00, %s, %s, %s, %s)" % (
            q(oid), q(region), q(code), amt, nullable(refund), q(st),
            q(created) if created else "NULL",
            q(completed) if completed else "NULL"))
    out.append(",\n".join(rows))
    out.append(";\n\n")

    # ---- 自检 ----
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("-- 自检：行数对不上说明脚本和数据不同步\n")
    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("SELECT 'regions' AS t, count(*) FROM regions\n")
    out.append("UNION ALL SELECT 'region_provinces', count(*) FROM region_provinces\n")
    out.append("UNION ALL SELECT 'products', count(*) FROM products\n")
    out.append("UNION ALL SELECT 'sales_orders', count(*) FROM sales_orders\n")
    out.append("UNION ALL SELECT 'orders_archive', count(*) FROM orders_archive\n")
    out.append("ORDER BY 1;\n")
    return "".join(out)


# ----------------------------------------------------------------------------
# 四、基准答案（用 Python 复刻「正确的 SQL 应该算出的东西」）
# ----------------------------------------------------------------------------

def quarter_of(datestr):
    """把 'YYYY-MM-DD' 变成 'YYYYQn'。基准答案只关心季度。

    未完成的订单 completed_at 是 NULL —— 返回 None，
    而 None 不会等于任何 'YYYYQn'，所以过滤条件天然把它们排除掉了。
    这正好复刻 SQL 里 `WHERE completed_at >= ... AND completed_at < ...` 的行为：
    NULL 参与比较的结果是 UNKNOWN，行被丢掉。
    """
    if datestr is None:
        return None
    y, m, _ = datestr.split("-")
    qn = (int(m) - 1) // 3 + 1
    return "%sQ%d" % (y, qn)


def in_year(datestr, year):
    """completed_at 落在某年？None 安全（NULL 不落任何年里）。"""
    qq = quarter_of(datestr)
    return qq is not None and qq.startswith(year)


def net(o):
    """净销售额 = order_amount - coalesce(refund_amount, 0)"""
    return o[3] - (o[4] or 0)


def counted(orders):
    return [o for o in orders if o[5] in COUNTED_STATUS]


def net_by(orders, key):
    """按 key(order) 分组求净销售额。"""
    acc = {}
    for o in counted(orders):
        acc[key(o)] = acc.get(key(o), 0) + net(o)
    return acc


def fmt(n):
    return "{:,}".format(int(round(n)))


def ground_truth():
    """诊断 demo 里那几道题的期望答案。全部由这里算出来，手写必错。"""
    g = {}
    cur = ORDERS
    arch = ARCHIVED_ORDERS

    # D1 2026 Q2 华东净销售额
    d1 = counted([o for o in cur if o[1] == "华东" and quarter_of(o[7]) == "2026Q2"])
    g["D1_华东_2026Q2"] = (sum(net(o) for o in d1), len(d1))

    # D2 2026 Q2 各区域排名
    d2 = net_by([o for o in cur if quarter_of(o[7]) == "2026Q2"], lambda o: o[1])
    g["D2_排名_2026Q2"] = sorted(d2.items(), key=lambda kv: -kv[1])

    # D3 2026 Q2 XR 产品线净销售额（需要 join products 拿 line_code）
    line_of = {p[0]: p[2] for p in PRODUCTS}
    d3 = net_by([o for o in cur if quarter_of(o[7]) == "2026Q2"], lambda o: line_of[o[2]])
    g["D3_XR线_2026Q2"] = (d3.get("XR", 0), {k: v for k, v in sorted(d3.items(), key=lambda kv: -kv[1])})

    # D4 2026 Q2 全国净销售额
    d4 = counted([o for o in cur if quarter_of(o[7]) == "2026Q2"])
    g["D4_全国_2026Q2"] = (sum(net(o) for o in d4), len(d4))

    # D5 2026 Q1 华东净销售额（陷阱：created 在 Q1 而 completed 在 Q2 的那单不算）
    d5 = counted([o for o in cur if o[1] == "华东" and quarter_of(o[7]) == "2026Q1"])
    g["D5_华东_2026Q1"] = (sum(net(o) for o in d5), len(d5))
    # 如果按 created_at 归期，Q1 会多算多少？
    wrong = counted([o for o in cur if o[1] == "华东"
                     and quarter_of(o[6]) == "2026Q1" and quarter_of(o[7]) != "2026Q1"])
    g["D5_按created_at_会多算"] = sum(net(o) for o in wrong)

    # D6 华东包含的省份（按 display_order 排）
    order = {r: i for i, r in enumerate(REGIONS)}
    g["D6_华东省份"] = [p for p, r in REGION_PROVINCES if r == "华东"]

    # D7 福建 2026Q2 —— 算不出来（库里没有省份维度）
    #   这是能力边界题：正确答案是「算不出来」，最坏答案是一个看起来合理的假数字。
    #   region_provinces 能告诉你「福建属于华东」，但订单表只有区域名，
    #   没有省份字段 → 没有任何一行能定位到「福建的订单」。
    g["D7_福建"] = "库里没有省份字段，算不出来"

    # D8 2024 华东净销售额（在归档表里）
    d8 = counted([o for o in arch if o[1] == "华东" and in_year(o[7], "2024")])
    g["D8_华东_2024"] = (sum(net(o) for o in d8), len(d8))
    # 若模型不知道归档表的存在（或以为它不该用），只查 sales_orders 会得到空
    d8_cur = counted([o for o in cur if o[1] == "华东" and in_year(o[7], "2024")])
    g["D8_只查sales_orders会得到"] = (sum(net(o) for o in d8_cur), len(d8_cur))

    # D9 2026 Q2 华东客单价（口径在语料 06：订单数只计 COMPLETED）
    total = sum(net(o) for o in d1)
    completed_only = len([o for o in d1 if o[5] == "COMPLETED"])
    refunded_cnt = len(d1) - completed_only
    g["D9_客单价_口径_06号"] = (total / completed_only, completed_only)
    g["D9_客单价_若把REFUNDED算进订单数"] = (total / len(d1), len(d1))
    return g


def print_ground_truth():
    g = ground_truth()
    print("=" * 74)
    print("迭代 18 诊断题的基准答案（由本脚本从同一份数据算出，勿手写）")
    print("=" * 74)

    v, n = g["D1_华东_2026Q2"]
    print("D1 2026Q2 华东净销售额      = %s 元（%d 单计入）" % (fmt(v), n))
    print("D2 2026Q2 各区域排名：")
    for r, val in g["D2_排名_2026Q2"]:
        print("     %-4s %s" % (r, fmt(val)))
    v, lines = g["D3_XR线_2026Q2"]
    print("D3 2026Q2 XR 产品线净销售额  = %s 元" % fmt(v))
    print("     （各产品线：%s）" % "，".join("%s=%s" % (k, fmt(x)) for k, x in lines.items()))
    v, n = g["D4_全国_2026Q2"]
    print("D4 2026Q2 全国净销售额      = %s 元（%d 单计入）" % (fmt(v), n))
    v, n = g["D5_华东_2026Q1"]
    print("D5 2026Q1 华东净销售额      = %s 元（%d 单计入）" % (fmt(v), n))
    print("     若误用 created_at 归期，会多算 %s 元 → 27,300,000 那种错" % fmt(g["D5_按created_at_会多算"]))
    print("D6 华东包含的省份            = %s" % "、".join(g["D6_华东省份"]))
    print("D7 福建 2026Q2 销售额        = %s（这是个能力边界题）" % g["D7_福建"])
    v, n = g["D8_华东_2024"]
    print("D8 2024 华东净销售额        = %s 元（%d 单，在 orders_archive 里）" % (fmt(v), n))
    v, n = g["D8_只查sales_orders会得到"]
    print("     若只查 sales_orders，会得到 %d 单 / %s 元 → 会答「0」或「没查到」" % (n, fmt(v)))
    v, n = g["D9_客单价_口径_06号"]
    print("D9 2026Q2 华东客单价        = %s 元（口径：订单数只计 COMPLETED，共 %d 单）" % (fmt(v), n))
    v, n = g["D9_客单价_若把REFUNDED算进订单数"]
    print("     若把 REFUNDED 也算进订单数（%d 单）→ %s 元" % (n, fmt(v)))
    print("=" * 74)


def main():
    if "--check" not in sys.argv:
        here = os.path.dirname(os.path.abspath(__file__))
        target = os.path.join(os.path.dirname(here), "docker", "init-business-db.sql")
        sql = build_sql()
        with open(target, "w", encoding="utf-8") as f:
            f.write(sql)
        print("已写入 %s（%d 字节）" % (target, len(sql.encode("utf-8"))))
        print("表：regions=%d 省, region_provinces=%d, products=%d, sales_orders=%d, orders_archive=%d"
              % (len(REGIONS), len(REGION_PROVINCES), len(PRODUCTS), len(ORDERS), len(ARCHIVED_ORDERS)))
        print()
    print_ground_truth()


if __name__ == "__main__":
    main()
