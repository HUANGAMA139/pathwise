#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
迭代 19 · 业务库「扩表」脚本 —— 5 张表扩到 20 张

它只做一件事：生成 docker/init-business-db-extra.sql（15 张新表 + 种子数据）。

为什么单独一个文件、不去改 迭代 18 那个 init-business-db.sql：
  1. 迭代 18 的 5 张表是 迭代 20 做「前后对比」的基线 —— schema 一变，那 9 道题的结论就不可比了。
     这条纪律和「retrieval.mode 要能跑回旧基线」是同一条。
  2. 它同时是一份「加表」的迁移脚本：对已经在跑的库直接 psql -f 就能加，不用清数据卷。
     （脚本只 DROP 自己这 15 张，不碰那 5 张。）

为什么是这 15 张：迭代 19 要测「表多了要能筛出相关的表」。要测出这件事，需要的是
  · 规模：20 张表，全量注入明显变长
  · 干扰项：必须有几张「和问题沾边但答不了」的表，否则筛选就成了送分题
两个特意放的强干扰：
  · `purchase_orders`（采购单）—— 和 `sales_orders` 都叫「订单」，但一个进货一个出货
  · `order_items`（订单明细）—— 它也有金额（quantity × unit_price），一个不懂口径的模型
    完全可能拿它去算「销售额」，而语料 01 说的销售额是【订单表头上的 order_amount − refund_amount】

铁律：不与 6 份语料冲突。最要紧的一条 —— 语料 06 明说「达成率的分母（目标销售额）不在数据库里」，
所以【不能】造目标表，否则那句话就成了假的。

用法：
  python3 scripts/gen-extra-tables.py            # 写 SQL 并打印行数自检
  python3 scripts/gen-extra-tables.py --check    # 只打印
"""

import sys
import os

# ----------------------------------------------------------------------------
# 一、15 张新表
# ----------------------------------------------------------------------------
# 每张表：(表名, 表注释, 列定义, 主键列, 外键[(列, 引用表, 引用列)], 种子行)
#   列定义：(列名, 类型, 是否可空, 默认值, 注释)

TABLES = []


def table(name, comment, columns, pk, fks, rows):
    TABLES.append({
        "name": name, "comment": comment, "columns": columns,
        "pk": pk, "fks": fks, "rows": rows,
    })


# ---- 1. customers 客户 -------------------------------------------------------
table("customers", "客户档案", [
    ("customer_id",   "varchar(16)", False, None, "客户编号，CUS- + 6 位数字"),
    ("customer_name", "varchar(64)", False, None, "客户名称"),
    ("customer_type", "varchar(16)", False, None, "客户类型：ENTERPRISE/DISTRIBUTOR/RETAIL"),
    ("city",          "varchar(32)", True,  None, "客户所在城市"),
    ("registered_at", "timestamp",   False, None, "注册时间"),
], "customer_id", [], [
    ("CUS-100001", "启明科技",     "ENTERPRISE",  "上海", "2024-03-11"),
    ("CUS-100002", "宏远电子",     "ENTERPRISE",  "杭州", "2024-07-02"),
    ("CUS-100003", "北方数码城",   "DISTRIBUTOR", "北京", "2025-01-19"),
    ("CUS-100004", "南岭商贸",     "DISTRIBUTOR", "广州", "2025-04-08"),
    ("CUS-100005", "线上散客旗舰店", "RETAIL",      "厦门", "2025-09-23"),
    ("CUS-100006", "西部智联",     "ENTERPRISE",  "成都", "2026-02-14"),
])

# ---- 2. suppliers 供应商 -----------------------------------------------------
table("suppliers", "供应商档案", [
    ("supplier_id",   "varchar(16)", False, None, "供应商编号，SUP- + 5 位数字"),
    ("supplier_name", "varchar(64)", False, None, "供应商名称"),
    ("supplier_type", "varchar(16)", False, None, "供应商类型：COMPONENT/ODM/LOGISTICS"),
    ("cooperation_start", "timestamp", False, None, "合作起始时间"),
], "supplier_id", [], [
    ("SUP-20001", "瑞芯微电子",   "COMPONENT", "2023-05-06"),
    ("SUP-20002", "广达精密",     "ODM",       "2023-11-20"),
    ("SUP-20003", "顺捷物流",     "LOGISTICS", "2024-02-01"),
    ("SUP-20004", "京东方光电",   "COMPONENT", "2024-08-15"),
    ("SUP-20005", "立讯智造",     "ODM",       "2025-03-27"),
])

# ---- 3. warehouses 仓库 ------------------------------------------------------
table("warehouses", "仓库档案", [
    ("warehouse_code", "varchar(16)", False, None, "仓库编码"),
    ("warehouse_name", "varchar(64)", False, None, "仓库名称"),
    ("region",         "varchar(16)", False, None, "所属销售区域名，取值见 regions"),
], "warehouse_code", [("region", "regions", "region_name")], [
    ("WH-EAST", "华东中心仓", "华东"),
    ("WH-SOUTH", "华南中心仓", "华南"),
    ("WH-NORTH", "华北中心仓", "华北"),
    ("WH-WEST", "西部中心仓", "西部"),
])

# ---- 4. channels 销售渠道 ----------------------------------------------------
table("channels", "销售渠道档案", [
    ("channel_code", "varchar(16)", False, None, "渠道编码"),
    ("channel_name", "varchar(64)", False, None, "渠道名称"),
    ("channel_type", "varchar(16)", False, None, "渠道类型：ONLINE/OFFLINE/PARTNER"),
], "channel_code", [], [
    ("CH-ONLINE", "官方商城", "ONLINE"),
    ("CH-DIRECT", "直营门店", "OFFLINE"),
    ("CH-PARTNER", "渠道代理", "PARTNER"),
    ("CH-RETAIL", "第三方零售平台", "ONLINE"),
])

# ---- 5. product_categories 产品分类 -----------------------------------------
table("product_categories", "产品分类字典", [
    ("category_code", "varchar(16)", False, None, "分类编码"),
    ("category_name", "varchar(64)", False, None, "分类名称"),
    ("parent_code",   "varchar(16)", True,  None, "上级分类编码，顶层分类为 NULL"),
], "category_code", [], [
    ("CAT-TERM",  "智能终端",       None),
    ("CAT-TERM-XR", "XR 系列终端",  "CAT-TERM"),
    ("CAT-MOD",   "工业模组",       None),
    ("CAT-ACC",   "配件",           None),
    ("CAT-CUS",   "定制机型",       None),
    ("CAT-TERM-MT", "MT 系列终端",  "CAT-MOD"),
])

# ---- 6. employees 员工 -------------------------------------------------------
table("employees", "员工档案", [
    ("employee_id",   "varchar(16)", False, None, "员工编号，EMP- + 4 位数字"),
    ("employee_name", "varchar(32)", False, None, "员工姓名"),
    ("region",        "varchar(16)", False, None, "所属销售区域名，取值见 regions"),
    ("title",         "varchar(32)", False, None, "岗位：SALES/SUPPORT/MANAGER"),
], "employee_id", [("region", "regions", "region_name")], [
    ("EMP-1001", "王建国", "华东", "MANAGER"),
    ("EMP-1002", "李思远", "华东", "SALES"),
    ("EMP-1003", "陈晓芸", "华东", "SALES"),
    ("EMP-1004", "赵鹏",   "华南", "SALES"),
    ("EMP-1005", "孙敏",   "华北", "SUPPORT"),
    ("EMP-1006", "周涛",   "华中", "SALES"),
    ("EMP-1007", "吴倩",   "西部", "SUPPORT"),
    ("EMP-1008", "郑昊",   "东北", "SALES"),
])

# ---- 7. order_items 订单明细（强干扰：也有金额）-------------------------------
# 【注释写法的教训，迭代 19 实测逼出来的】
# 表注释有两个用途，而这两个用途的要求【相反】：
#   · 给模型看（注入 prompt）：最好把业务含义写清楚
#   · 给表检索当语料：要短、且只说自己 —— 一旦夹带别的指标的解释，
#     这张表就会变成「磁铁」：任何含那个词的查询都会把它排到前面，把真正该选的表挤出去。
# 实测：`payments` 原来写「回款记录（销售额是净额口径，不等于实际收到的钱）」，
# 于是所有问「销售额」的题里 payments 都排第一，orders_archive 被挤出 top-8，T05 直接漏表。
# 把这几处夹带的解释删掉之后，top-8 命中从 19/20 回到 20/20。
# 所以：**表注释只说自己是什么；业务含义放文档（本来也该在文档里）。**
table("order_items", "订单明细行", [
    ("item_id",      "varchar(20)",  False, None, "明细行编号"),
    ("order_id",     "varchar(16)",  False, None, "所属订单号"),
    ("product_code", "varchar(16)",  False, None, "产品编码"),
    ("quantity",     "integer",      False, None, "数量"),
    ("unit_price",   "numeric(14,2)", False, None, "成交单价（不含税）"),
], "item_id", [("order_id", "sales_orders", "order_id"),
               ("product_code", "products", "product_code")], [
    ("IT-0001", "ORD-20260202", "XR-2000B", 1000, 4300.00),
    ("IT-0002", "ORD-20260203", "XR-2000C", 1000, 5300.00),
    ("IT-0003", "ORD-20260204", "XR-3000A", 1000, 9000.00),
    ("IT-0004", "ORD-20260205", "MT-1000B", 1000, 1900.00),
    ("IT-0005", "ORD-20260206", "XR-2000B",  800, 4300.00),
    ("IT-0006", "ORD-20260206", "SX-0101",   860,  232.56),
    ("IT-0007", "ORD-20260207", "XR-2010B", 1000, 4700.00),
    ("IT-0008", "ORD-20260210", "XR-2000B", 1000, 4300.00),
    ("IT-0009", "ORD-20260211", "XR-2000C", 1000, 5300.00),
    ("IT-0010", "ORD-20260213", "XR-3000A",  889, 9000.00),
    ("IT-0011", "ORD-20260218", "XR-2000B",  744, 4300.00),
    ("IT-0012", "ORD-20260222", "XR-2000B",  349, 4300.00),
])

# ---- 8. returns 退货申请单（与语料 03 对应）-----------------------------------
# 一致性：审核通过的那些，金额必须和对应订单的 refund_amount 对得上
# （语料 03 说「审核通过时即从当期销售额中扣减」，所以两边不能各说各话）
table("returns", "退货申请单", [
    ("return_id",     "varchar(20)",  False, None, "退货申请编号"),
    ("order_id",      "varchar(16)",  False, None, "关联订单号"),
    ("apply_at",      "timestamp",    False, None, "申请提交时间"),
    ("approved_at",   "timestamp",    True,  None, "审核通过时间，未通过为 NULL"),
    ("refund_amount", "numeric(14,2)", False, None, "退货金额（不含税）"),
    ("status",        "varchar(16)",  False, None, "申请状态：SUBMITTED/APPROVED/REJECTED"),
], "return_id", [("order_id", "sales_orders", "order_id")], [
    ("RT-0001", "ORD-20260203", "2026-04-25", "2026-04-29", 300000.00, "APPROVED"),
    ("RT-0002", "ORD-20260205", "2026-05-15", "2026-05-18", 1900000.00, "APPROVED"),
    ("RT-0003", "ORD-20260207", "2026-06-09", "2026-06-12", 200000.00, "APPROVED"),
    ("RT-0004", "ORD-20260211", "2026-05-10", "2026-05-14", 500000.00, "APPROVED"),
    ("RT-0005", "ORD-20260216", "2026-05-12", "2026-05-16", 700000.00, "APPROVED"),
    ("RT-0006", "ORD-20260220", "2026-05-06", "2026-05-09", 100000.00, "APPROVED"),
    ("RT-0007", "ORD-20260212", "2026-05-22", None, 150000.00, "REJECTED"),
    ("RT-0008", "ORD-20260219", "2026-06-19", None, 90000.00,  "SUBMITTED"),
])

# ---- 9. payments 回款 ---------------------------------------------------------
# 刻意做成「部分回款」——语料 01 说销售额不等于收到的钱（还有账期），这里让这句话在数据上成立
table("payments", "回款记录", [
    ("payment_id", "varchar(20)",  False, None, "回款流水号"),
    ("order_id",   "varchar(16)",  False, None, "关联订单号"),
    ("paid_at",    "timestamp",    False, None, "到账时间"),
    ("amount",     "numeric(14,2)", False, None, "回款金额"),
    ("channel",    "varchar(16)",  False, None, "回款渠道：WIRE/CARD/ALIPAY"),
], "payment_id", [("order_id", "sales_orders", "order_id")], [
    ("PM-0001", "ORD-20260202", "2026-05-06", 4300000.00, "WIRE"),
    ("PM-0002", "ORD-20260203", "2026-05-20", 5000000.00, "WIRE"),
    ("PM-0003", "ORD-20260204", "2026-06-03", 9000000.00, "WIRE"),
    ("PM-0004", "ORD-20260206", "2026-07-02", 4300000.00, "CARD"),
    ("PM-0005", "ORD-20260207", "2026-07-14", 4500000.00, "WIRE"),
    ("PM-0006", "ORD-20260210", "2026-05-19", 4300000.00, "ALIPAY"),
    ("PM-0007", "ORD-20260213", "2026-07-09", 8000000.00, "WIRE"),
    ("PM-0008", "ORD-20260215", "2026-05-22", 4300000.00, "CARD"),
])

# ---- 10. invoices 发票 -------------------------------------------------------
table("invoices", "发票开具记录", [
    ("invoice_id",   "varchar(20)",  False, None, "发票记录编号"),
    ("order_id",     "varchar(16)",  False, None, "关联订单号"),
    ("invoice_no",   "varchar(32)",  False, None, "发票号码"),
    ("issued_at",    "timestamp",    False, None, "开票时间"),
    ("amount",       "numeric(14,2)", False, None, "开票金额"),
    ("invoice_type", "varchar(16)",  False, None, "发票类型：NORMAL/SPECIAL"),
], "invoice_id", [("order_id", "sales_orders", "order_id")], [
    ("INV-0001", "ORD-20260202", "00A240000001", "2026-04-20", 4300000.00, "SPECIAL"),
    ("INV-0002", "ORD-20260203", "00A240000002", "2026-05-02", 5300000.00, "SPECIAL"),
    ("INV-0003", "ORD-20260204", "00A240000003", "2026-05-14", 9000000.00, "SPECIAL"),
    ("INV-0004", "ORD-20260210", "00A240000004", "2026-04-23", 4300000.00, "NORMAL"),
    ("INV-0005", "ORD-20260215", "00A240000005", "2026-04-26", 4300000.00, "NORMAL"),
    ("INV-0006", "ORD-20260218", "00A240000006", "2026-05-07", 3200000.00, "NORMAL"),
])

# ---- 11. shipments 发货单 ----------------------------------------------------
table("shipments", "发货单（记录收货客户与承运信息）", [
    ("shipment_id", "varchar(20)", False, None, "发货单号"),
    ("order_id",    "varchar(16)", False, None, "关联订单号"),
    ("customer_id", "varchar(16)", False, None, "收货客户编号"),
    ("shipped_at",  "timestamp",   False, None, "发货时间"),
    ("carrier",     "varchar(16)", False, None, "承运商"),
    ("status",      "varchar(16)", False, None, "发货状态：PENDING/SHIPPED/DELIVERED"),
], "shipment_id", [("order_id", "sales_orders", "order_id"),
                   ("customer_id", "customers", "customer_id")], [
    ("SH-0001", "ORD-20260202", "CUS-100001", "2026-04-16", "顺丰", "DELIVERED"),
    ("SH-0002", "ORD-20260203", "CUS-100002", "2026-04-29", "顺丰", "DELIVERED"),
    ("SH-0003", "ORD-20260204", "CUS-100001", "2026-05-12", "京东物流", "DELIVERED"),
    ("SH-0004", "ORD-20260206", "CUS-100005", "2026-06-03", "中通", "DELIVERED"),
    ("SH-0005", "ORD-20260207", "CUS-100003", "2026-06-15", "顺丰", "DELIVERED"),
    ("SH-0006", "ORD-20260210", "CUS-100004", "2026-04-20", "京东物流", "DELIVERED"),
    ("SH-0007", "ORD-20260211", "CUS-100004", "2026-05-14", "中通", "DELIVERED"),
    ("SH-0008", "ORD-20260213", "CUS-100001", "2026-06-10", "顺丰", "DELIVERED"),
    ("SH-0009", "ORD-20260215", "CUS-100003", "2026-04-23", "顺丰", "DELIVERED"),
    ("SH-0010", "ORD-20260218", "CUS-100006", "2026-05-04", "中通", "SHIPPED"),
    ("SH-0011", "ORD-20260302", "CUS-100001", "2026-08-20", "顺丰", "SHIPPED"),
])

# ---- 12. inventory 库存快照 --------------------------------------------------
# 快照表：同一天同一仓库同一产品只应有一行（真实系统靠唯一索引保证，这里从数据上保证）
table("inventory", "库存快照（按仓库 × 产品 × 快照日）", [
    ("snapshot_id",    "varchar(24)", False, None, "快照编号"),
    ("warehouse_code", "varchar(16)", False, None, "仓库编码"),
    ("product_code",   "varchar(16)", False, None, "产品编码"),
    ("snapshot_at",    "timestamp",   False, None, "快照时间"),
    ("on_hand",        "integer",     False, None, "在库数量"),
], "snapshot_id", [("warehouse_code", "warehouses", "warehouse_code"),
                   ("product_code", "products", "product_code")], [
    ("SN-20260901-01", "WH-EAST",  "XR-2000B", "2026-09-01", 3120),
    ("SN-20260901-02", "WH-EAST",  "XR-2000C", "2026-09-01", 1480),
    ("SN-20260901-03", "WH-EAST",  "XR-3000A", "2026-09-01", 620),
    ("SN-20260901-04", "WH-SOUTH", "XR-2000B", "2026-09-01", 2050),
    ("SN-20260901-05", "WH-SOUTH", "XR-2010B", "2026-09-01", 990),
    ("SN-20260901-06", "WH-NORTH", "XR-2000B", "2026-09-01", 1730),
    ("SN-20260901-07", "WH-WEST",  "MT-1000B", "2026-09-01", 4400),
    ("SN-20260901-08", "WH-WEST",  "SX-0101",  "2026-09-01", 26000),
])

# ---- 13. purchase_orders 采购单（强干扰：也叫「订单」）------------------------
table("purchase_orders", "采购单（公司向供应商进货）", [
    ("po_id",       "varchar(20)",  False, None, "采购单号"),
    ("supplier_id", "varchar(16)",  False, None, "供应商编号"),
    ("ordered_at",  "timestamp",    False, None, "下单时间"),
    ("amount",      "numeric(14,2)", False, None, "采购金额"),
    ("status",      "varchar(16)",  False, None, "状态：CREATED/RECEIVED/CANCELLED"),
], "po_id", [("supplier_id", "suppliers", "supplier_id")], [
    ("PO-30001", "SUP-20001", "2026-01-08", 6200000.00, "RECEIVED"),
    ("PO-30002", "SUP-20002", "2026-02-14", 9800000.00, "RECEIVED"),
    ("PO-30003", "SUP-20004", "2026-03-22", 4100000.00, "RECEIVED"),
    ("PO-30004", "SUP-20005", "2026-04-30", 7300000.00, "RECEIVED"),
    ("PO-30005", "SUP-20001", "2026-05-19", 5400000.00, "RECEIVED"),
    ("PO-30006", "SUP-20003", "2026-06-11", 260000.00,  "RECEIVED"),
    ("PO-30007", "SUP-20002", "2026-08-05", 8700000.00, "CREATED"),
    ("PO-30008", "SUP-20004", "2026-08-27", 3900000.00, "CANCELLED"),
])

# ---- 14. purchase_order_items 采购明细 ---------------------------------------
table("purchase_order_items", "采购单明细行", [
    ("item_id",      "varchar(20)",  False, None, "明细行编号"),
    ("po_id",        "varchar(20)",  False, None, "所属采购单号"),
    ("product_code", "varchar(16)",  False, None, "产品编码"),
    ("quantity",     "integer",      False, None, "数量"),
    ("unit_cost",    "numeric(14,2)", False, None, "采购单价（成本）"),
], "item_id", [("po_id", "purchase_orders", "po_id"),
               ("product_code", "products", "product_code")], [
    ("PIT-0001", "PO-30001", "XR-2000B", 1500, 2600.00),
    ("PIT-0002", "PO-30002", "XR-2000B", 2400, 2550.00),
    ("PIT-0003", "PO-30002", "XR-2000C", 1300, 3200.00),
    ("PIT-0004", "PO-30003", "XR-3000A",  700, 5400.00),
    ("PIT-0005", "PO-30004", "XR-2000B", 2000, 2500.00),
    ("PIT-0006", "PO-30005", "XR-2010B", 1400, 2800.00),
    ("PIT-0007", "PO-30005", "SX-0101",  20000,  108.00),
    ("PIT-0008", "PO-30007", "MT-1000B", 3600, 1150.00),
])

# ---- 15. product_price_history 价格变更历史 ----------------------------------
table("product_price_history", "产品标准单价变更历史", [
    ("change_id",    "varchar(20)",  False, None, "变更记录编号"),
    ("product_code", "varchar(16)",  False, None, "产品编码"),
    ("changed_at",   "timestamp",    False, None, "变更生效时间"),
    ("old_price",    "numeric(14,2)", True,  None, "变更前标准单价，首次定价为 NULL"),
    ("new_price",    "numeric(14,2)", False, None, "变更后标准单价"),
    ("reason",       "varchar(64)",  True,  None, "变更原因"),
], "change_id", [("product_code", "products", "product_code")], [
    ("PC-0001", "XR-2000B", "2025-01-05", None,     4099.00, "新品定价"),
    ("PC-0002", "XR-2000B", "2025-11-01", 4099.00,  4299.00, "成本上涨"),
    ("PC-0003", "XR-2000C", "2025-01-05", None,     5199.00, "新品定价"),
    ("PC-0004", "XR-2000C", "2026-01-10", 5199.00,  5299.00, "元器件涨价"),
    ("PC-0005", "XR-3000A", "2025-03-18", None,     9299.00, "新品定价"),
    ("PC-0006", "XR-3000A", "2026-02-01", 9299.00,  8999.00, "促销调价"),
    ("PC-0007", "XR-2000A", "2024-06-01", 4299.00,  3999.00, "停产清库存"),
    ("PC-0008", "MT-1000B", "2025-05-20", 1999.00,  1899.00, "B 端议价"),
])

HEADER = """\
-- ============================================================================
-- smart-data-qa · 业务库「扩表」脚本（迭代 19）
-- ----------------------------------------------------------------------------
-- 由 scripts/gen-extra-tables.py 生成，请勿手改（改数据请改脚本）。
--
-- 5 张表 → 20 张表。迭代 19 要测「表多了能不能先筛后注」。
--
-- 【为什么不合并进 init-business-db.sql】
--   迭代 18 那 5 张表是 迭代 20 做前后对比的基线，schema 一变基线就不可比了。
--   所以这个文件只新增自己的 15 张，一行都不碰原来那 5 张 ——
--   对已经在跑的库直接 psql -f 就能加，不用清数据卷。
--
-- 【两个强干扰项，是刻意放的】
--   purchase_orders  和 sales_orders 都叫「订单」，但一个进货一个出货
--   order_items      它也有金额（quantity × unit_price），但语料 01 定义销售额时
--                    用的是【订单表头上的 order_amount − refund_amount】——不懂口径就会用错
--
-- 【和语料的铁律】语料 06 明说「达成率的分母（目标销售额）不在数据库里」，
--   所以这里【不能】造目标表。加表前先回头读 docs/corpus.md。
--
-- 挂载为 /docker-entrypoint-initdb.d/20-extra.sql（按文件名顺序在 10-init.sql 之后执行）。
-- ============================================================================

-- 幂等：只 DROP 自己这 15 张，不碰原 5 张
"""

DROP_ORDER = [
    "product_price_history", "purchase_order_items", "purchase_orders",
    "inventory", "shipments", "invoices", "payments", "returns",
    "order_items", "employees", "product_categories", "channels",
    "warehouses", "suppliers", "customers",
]

# 建表顺序要满足外键依赖：被引用的先建
CREATE_ORDER = [
    "customers", "suppliers", "warehouses", "channels", "product_categories",
    "employees", "order_items", "returns", "payments", "invoices", "shipments",
    "inventory", "purchase_orders", "purchase_order_items", "product_price_history",
]


def q(s):
    return "'" + s + "'"


def lit(v):
    """把 Python 值写成 SQL 字面量。"""
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return "%.2f" % v
    return q(str(v))


def build_sql():
    by_name = {t["name"]: t for t in TABLES}
    out = [HEADER]
    for name in DROP_ORDER:
        out.append("DROP TABLE IF EXISTS %s CASCADE;\n" % name)
    out.append("\n")

    for name in CREATE_ORDER:
        t = by_name[name]
        out.append("-- ---------------------------------------------------------------------------\n")
        out.append("-- %s · %s\n" % (name, t["comment"]))
        out.append("-- ---------------------------------------------------------------------------\n")
        out.append("CREATE TABLE %s (\n" % name)
        lines = []
        for col, typ, nullable, default, comment in t["columns"]:
            piece = "    %-16s %-14s" % (col, typ)
            if not nullable:
                piece += " NOT NULL"
            ref = next((f for f in t["fks"] if f[0] == col), None)
            if ref:
                piece += " REFERENCES %s(%s)" % (ref[1], ref[2])
            lines.append(piece)
        lines.append("    PRIMARY KEY (%s)" % t["pk"])
        out.append(",\n".join(lines))
        out.append("\n);\n\n")
        out.append("COMMENT ON TABLE  %s IS %s;\n" % (name, q(t["comment"])))
        for col, typ, nullable, default, comment in t["columns"]:
            out.append("COMMENT ON COLUMN %s.%s IS %s;\n" % (name, col, q(comment)))
        out.append("\n")

        cols = [c[0] for c in t["columns"]]
        out.append("INSERT INTO %s (%s) VALUES\n" % (name, ", ".join(cols)))
        rows = []
        for row in t["rows"]:
            rows.append("    (" + ", ".join(lit(v) for v in row) + ")")
        out.append(",\n".join(rows))
        out.append(";\n\n")

    out.append("-- ---------------------------------------------------------------------------\n")
    out.append("-- 自检：每张表都应有行（0 行说明种子数据漏了）\n")
    out.append("-- ---------------------------------------------------------------------------\n")
    parts = []
    for name in CREATE_ORDER:
        parts.append("SELECT '%s' AS t, count(*) AS n FROM %s" % (name, name))
    out.append(" UNION ALL ".join(parts))
    out.append(" ORDER BY 1;\n")
    return "".join(out)


def main():
    if "--check" not in sys.argv:
        here = os.path.dirname(os.path.abspath(__file__))
        target = os.path.join(os.path.dirname(here), "docker", "init-business-db-extra.sql")
        sql = build_sql()
        with open(target, "w", encoding="utf-8") as f:
            f.write(sql)
        print("已写入 %s（%d 字节）" % (target, len(sql.encode("utf-8"))))

    print("新增 %d 张表 → 业务库共 %d 张" % (len(TABLES), 5 + len(TABLES)))
    total = 0
    for name in CREATE_ORDER:
        t = next(x for x in TABLES if x["name"] == name)
        fk = len(t["fks"])
        total += len(t["rows"])
        print("  %-24s %2d 列  %d 条外键  %3d 行" % (name, len(t["columns"]), fk, len(t["rows"])))
    print("共 %d 行种子数据，%d 条外键" % (total, sum(len(t["fks"]) for t in TABLES)))


if __name__ == "__main__":
    main()
