-- ============================================================================
-- smart-data-qa · 业务库初始化脚本
-- ----------------------------------------------------------------------------
-- 由 scripts/exp18-gen-business-seed.py 生成，请勿手改（改数据请改脚本）。
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
    ('华东', 1),
    ('华南', 2),
    ('华北', 3),
    ('华中', 4),
    ('西部', 5),
    ('东北', 6);

-- ---------------------------------------------------------------------------
-- region_provinces · 区域↔省份映射（语料 02 第一节的原表）
-- 两个坑：福建→华东（2025 年起由华南划归）；山东→华北（不是行政区划的华东）。
-- 语料 02 第三节明说：订单表的 region 存的是【区域名】不是省份名。
-- ---------------------------------------------------------------------------
CREATE TABLE region_provinces (
    province_name varchar(16) PRIMARY KEY,
    region_name   varchar(16) NOT NULL REFERENCES regions(region_name)
);

COMMENT ON TABLE  region_provinces               IS '省份到销售区域的映射';
COMMENT ON COLUMN region_provinces.province_name IS '省份名';
COMMENT ON COLUMN region_provinces.region_name   IS '所属销售区域名';

INSERT INTO region_provinces (province_name, region_name) VALUES
    ('上海', '华东'),
    ('江苏', '华东'),
    ('浙江', '华东'),
    ('安徽', '华东'),
    ('福建', '华东'),
    ('广东', '华南'),
    ('广西', '华南'),
    ('海南', '华南'),
    ('北京', '华北'),
    ('天津', '华北'),
    ('河北', '华北'),
    ('山西', '华北'),
    ('内蒙古', '华北'),
    ('山东', '华北'),
    ('河南', '华中'),
    ('湖北', '华中'),
    ('湖南', '华中'),
    ('江西', '华中'),
    ('四川', '西部'),
    ('重庆', '西部'),
    ('云南', '西部'),
    ('贵州', '西部'),
    ('陕西', '西部'),
    ('甘肃', '西部'),
    ('青海', '西部'),
    ('宁夏', '西部'),
    ('新疆', '西部'),
    ('西藏', '西部'),
    ('辽宁', '东北'),
    ('吉林', '东北'),
    ('黑龙江', '东北');

-- ---------------------------------------------------------------------------
-- products · 产品（语料 04 第二节的字段，语料 05 的型号表）
-- XR-2000B 与 XR-2000C 只差最后一个字母 —— 语料 05 的近似编码素材。
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    product_code varchar(16)  PRIMARY KEY,
    product_name varchar(64)  NOT NULL,
    line_code    varchar(8)   NOT NULL,
    list_price   numeric(14,2) NOT NULL,
    sale_status  varchar(16)  NOT NULL
);

COMMENT ON TABLE  products              IS '产品字典';
COMMENT ON COLUMN products.product_code IS '产品编码，格式 <产品线前缀>-<代数><序号><变体>';
COMMENT ON COLUMN products.product_name IS '产品名称';
COMMENT ON COLUMN products.line_code    IS '产品线编码：XR/MT/SX/CUS';
COMMENT ON COLUMN products.list_price   IS '标准单价（不含税）';
COMMENT ON COLUMN products.sale_status  IS '销售状态：ON_SALE/DISCONTINUED';

INSERT INTO products (product_code, product_name, line_code, list_price, sale_status) VALUES
    ('XR-2000A', 'XR 标准版', 'XR', 3999.00, 'DISCONTINUED'),
    ('XR-2000B', 'XR 标准版升级', 'XR', 4299.00, 'ON_SALE'),
    ('XR-2000C', 'XR 标准版增强', 'XR', 5299.00, 'ON_SALE'),
    ('XR-2010B', 'XR 长续航版', 'XR', 4699.00, 'ON_SALE'),
    ('XR-3000A', 'XR 旗舰版', 'XR', 8999.00, 'ON_SALE'),
    ('MT-1000B', '工业模组 B 型', 'MT', 1899.00, 'ON_SALE'),
    ('SX-0101', '通用配件套装', 'SX', 199.00, 'ON_SALE'),
    ('CUS-0001', '定制机型', 'CUS', 29999.00, 'ON_SALE');

-- ---------------------------------------------------------------------------
-- sales_orders · 当期订单（2025 Q1 起）—— 语料 01 / 04
--   · 归属期间看 completed_at，不看 created_at（语料 01 第二节，历史踩坑点）
--   · 计入统计的状态只有 COMPLETED / REFUNDED；REFUNDED 净额为 0 仍计入
--   · refund_amount 可为 NULL（语料 04 第三节）
-- ---------------------------------------------------------------------------
CREATE TABLE sales_orders (
    order_id      varchar(16)  PRIMARY KEY,
    region        varchar(16)  NOT NULL REFERENCES regions(region_name),
    product_code  varchar(16)  NOT NULL REFERENCES products(product_code),
    order_amount  numeric(14,2) NOT NULL,
    refund_amount numeric(14,2),
    status        varchar(16)  NOT NULL,
    created_at    timestamp    NOT NULL,
    completed_at  timestamp
);

COMMENT ON TABLE  sales_orders               IS '当期销售订单';
COMMENT ON COLUMN sales_orders.order_id      IS '订单号，ORD- + 8 位数字';
COMMENT ON COLUMN sales_orders.region        IS '销售区域名，取值见 regions';
COMMENT ON COLUMN sales_orders.product_code  IS '产品编码';
COMMENT ON COLUMN sales_orders.order_amount  IS '订单金额（不含税）';
COMMENT ON COLUMN sales_orders.refund_amount IS '退货金额（不含税），可为空';
COMMENT ON COLUMN sales_orders.status        IS '订单状态：CREATED/PAID/COMPLETED/REFUNDED/CANCELLED';
COMMENT ON COLUMN sales_orders.created_at    IS '下单时间';
COMMENT ON COLUMN sales_orders.completed_at  IS '订单完成时间，未完成时为 NULL';

INSERT INTO sales_orders (order_id, region, product_code, order_amount, refund_amount, status, created_at, completed_at) VALUES
    ('ORD-20260101', '华东', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-01-12', '2026-01-20'),
    ('ORD-20260102', '华东', 'XR-3000A', 9000000.00, NULL, 'COMPLETED', '2026-02-05', '2026-02-13'),
    ('ORD-20260103', '华东', 'XR-2000C', 5300000.00, 300000, 'COMPLETED', '2026-03-02', '2026-03-10'),
    ('ORD-20260104', '华南', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-01-22', '2026-01-30'),
    ('ORD-20260105', '华南', 'XR-2010B', 4700000.00, NULL, 'COMPLETED', '2026-02-18', '2026-02-26'),
    ('ORD-20260106', '华北', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-02-09', '2026-02-17'),
    ('ORD-20260107', '华中', 'SX-0101', 80000.00, NULL, 'COMPLETED', '2026-03-15', '2026-03-23'),
    ('ORD-20260108', '西部', 'MT-1000B', 900000.00, NULL, 'CANCELLED', '2026-03-20', NULL),
    ('ORD-20260201', '华东', 'XR-3000A', 9000000.00, NULL, 'COMPLETED', '2026-03-28', '2026-04-05'),
    ('ORD-20260202', '华东', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-04-08', '2026-04-15'),
    ('ORD-20260203', '华东', 'XR-2000C', 5300000.00, 300000, 'COMPLETED', '2026-04-20', '2026-04-28'),
    ('ORD-20260204', '华东', 'XR-3000A', 9000000.00, NULL, 'COMPLETED', '2026-05-03', '2026-05-11'),
    ('ORD-20260205', '华东', 'MT-1000B', 1900000.00, 1900000, 'REFUNDED', '2026-05-12', '2026-05-20'),
    ('ORD-20260206', '华东', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-05-25', '2026-06-02'),
    ('ORD-20260207', '华东', 'XR-2010B', 4700000.00, 200000, 'COMPLETED', '2026-06-05', '2026-06-14'),
    ('ORD-20260208', '华东', 'SX-0101', 200000.00, NULL, 'PAID', '2026-06-20', NULL),
    ('ORD-20260209', '华东', 'XR-2000C', 5300000.00, NULL, 'CANCELLED', '2026-06-22', NULL),
    ('ORD-20260210', '华南', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-04-11', '2026-04-19'),
    ('ORD-20260211', '华南', 'XR-2000C', 5300000.00, 500000, 'COMPLETED', '2026-05-06', '2026-05-13'),
    ('ORD-20260212', '华南', 'SX-0101', 150000.00, NULL, 'COMPLETED', '2026-05-18', '2026-05-26'),
    ('ORD-20260213', '华南', 'XR-3000A', 8000000.00, NULL, 'COMPLETED', '2026-06-01', '2026-06-09'),
    ('ORD-20260214', '华南', 'MT-1000B', 1900000.00, NULL, 'CREATED', '2026-06-25', NULL),
    ('ORD-20260215', '华北', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-04-14', '2026-04-22'),
    ('ORD-20260216', '华北', 'XR-2010B', 4700000.00, 700000, 'COMPLETED', '2026-05-09', '2026-05-17'),
    ('ORD-20260217', '华北', 'SX-0101', 120000.00, NULL, 'COMPLETED', '2026-06-11', '2026-06-19'),
    ('ORD-20260218', '华中', 'XR-2000B', 3200000.00, NULL, 'COMPLETED', '2026-04-25', '2026-05-03'),
    ('ORD-20260219', '华中', 'SX-0101', 90000.00, NULL, 'COMPLETED', '2026-06-15', '2026-06-23'),
    ('ORD-20260220', '西部', 'XR-2000B', 2100000.00, 100000, 'COMPLETED', '2026-05-02', '2026-05-10'),
    ('ORD-20260221', '西部', 'MT-1000B', 800000.00, NULL, 'COMPLETED', '2026-06-18', '2026-06-26'),
    ('ORD-20260222', '东北', 'XR-2000B', 1500000.00, NULL, 'COMPLETED', '2026-05-20', '2026-05-28'),
    ('ORD-20260301', '华东', 'XR-3000A', 9000000.00, NULL, 'COMPLETED', '2026-07-08', '2026-07-16'),
    ('ORD-20260302', '华东', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-08-11', '2026-08-19'),
    ('ORD-20260303', '华南', 'XR-2000C', 5300000.00, NULL, 'COMPLETED', '2026-07-22', '2026-07-30'),
    ('ORD-20260304', '华北', 'XR-2000B', 4300000.00, NULL, 'COMPLETED', '2026-08-25', '2026-09-02'),
    ('ORD-20260305', '华东', 'XR-2000C', 5300000.00, NULL, 'PAID', '2026-09-15', NULL),
    ('ORD-20260306', '华东', 'XR-3000A', 9000000.00, NULL, 'CREATED', '2026-09-22', NULL),
    ('ORD-20250101', '华东', 'XR-2000B', 3900000.00, NULL, 'COMPLETED', '2025-01-15', '2025-01-23'),
    ('ORD-20250102', '华南', 'XR-2000B', 3900000.00, NULL, 'COMPLETED', '2025-02-10', '2025-02-18'),
    ('ORD-20250103', '华北', 'XR-2000A', 3500000.00, NULL, 'COMPLETED', '2025-03-05', '2025-03-13'),
    ('ORD-20250201', '华东', 'XR-2000B', 4100000.00, NULL, 'COMPLETED', '2025-04-18', '2025-04-26'),
    ('ORD-20250202', '华东', 'XR-2000C', 5100000.00, 200000, 'COMPLETED', '2025-05-22', '2025-05-30'),
    ('ORD-20250203', '华南', 'XR-2000B', 4100000.00, NULL, 'COMPLETED', '2025-06-09', '2025-06-17'),
    ('ORD-20250301', '华东', 'XR-2000B', 4200000.00, NULL, 'COMPLETED', '2025-07-14', '2025-07-22'),
    ('ORD-20250302', '华中', 'XR-2000B', 3100000.00, NULL, 'COMPLETED', '2025-08-19', '2025-08-27'),
    ('ORD-20250303', '华东', 'XR-3000A', 8800000.00, 300000, 'COMPLETED', '2025-09-10', '2025-09-18'),
    ('ORD-20250401', '华东', 'XR-2000B', 4250000.00, NULL, 'COMPLETED', '2025-10-16', '2025-10-24'),
    ('ORD-20250402', '华南', 'XR-2000B', 4250000.00, NULL, 'COMPLETED', '2025-11-20', '2025-11-28'),
    ('ORD-20250403', '华东', 'XR-2000C', 5200000.00, NULL, 'COMPLETED', '2025-12-08', '2025-12-16');

-- ---------------------------------------------------------------------------
-- orders_archive · 历史归档（2024 及以前）
-- 【陷阱】语料 04 第四节明说：「不要用它做当期统计」。
--   只靠表结构，模型看不出这一点 —— 这条知识在文档里，不在库里。
--   刻意不加外键：归档表通常脱开约束，也可能含已删产品。
-- ---------------------------------------------------------------------------
CREATE TABLE orders_archive (
    order_id      varchar(16)  PRIMARY KEY,
    region        varchar(16)  NOT NULL,
    product_code  varchar(16)  NOT NULL,
    order_amount  numeric(14,2) NOT NULL,
    refund_amount numeric(14,2),
    status        varchar(16)  NOT NULL,
    created_at    timestamp    NOT NULL,
    completed_at  timestamp,
    archived_at   timestamp    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  orders_archive             IS '历史订单归档表';
COMMENT ON COLUMN orders_archive.order_id    IS '订单号';
COMMENT ON COLUMN orders_archive.region      IS '销售区域名';
COMMENT ON COLUMN orders_archive.product_code IS '产品编码';
COMMENT ON COLUMN orders_archive.order_amount IS '订单金额（不含税）';
COMMENT ON COLUMN orders_archive.refund_amount IS '退货金额（不含税），可为空';
COMMENT ON COLUMN orders_archive.status      IS '订单状态';
COMMENT ON COLUMN orders_archive.created_at  IS '下单时间';
COMMENT ON COLUMN orders_archive.completed_at IS '订单完成时间';
COMMENT ON COLUMN orders_archive.archived_at IS '归档时间';

INSERT INTO orders_archive (order_id, region, product_code, order_amount, refund_amount, status, created_at, completed_at) VALUES
    ('ORD-20240101', '华东', 'XR-2000A', 3500000.00, NULL, 'COMPLETED', '2024-01-18', '2024-01-26'),
    ('ORD-20240102', '华南', 'XR-2000A', 3500000.00, NULL, 'COMPLETED', '2024-02-14', '2024-02-22'),
    ('ORD-20240103', '华北', 'XR-2000A', 3400000.00, NULL, 'COMPLETED', '2024-03-11', '2024-03-19'),
    ('ORD-20240201', '华东', 'XR-2000A', 3600000.00, 100000, 'COMPLETED', '2024-04-20', '2024-04-28'),
    ('ORD-20240202', '华南', 'XR-2000B', 4000000.00, NULL, 'COMPLETED', '2024-05-16', '2024-05-24'),
    ('ORD-20240203', '华南', 'XR-2000A', 3600000.00, NULL, 'COMPLETED', '2024-06-12', '2024-06-20'),
    ('ORD-20240301', '华东', 'XR-2000A', 3600000.00, NULL, 'COMPLETED', '2024-07-19', '2024-07-27'),
    ('ORD-20240302', '华南', 'XR-2000B', 4000000.00, 200000, 'COMPLETED', '2024-08-23', '2024-08-31'),
    ('ORD-20240303', '华北', 'XR-2000A', 3500000.00, NULL, 'COMPLETED', '2024-09-17', '2024-09-25'),
    ('ORD-20240401', '华东', 'XR-2000A', 3700000.00, NULL, 'COMPLETED', '2024-10-21', '2024-10-29'),
    ('ORD-20240402', '华南', 'XR-2000B', 4000000.00, NULL, 'COMPLETED', '2024-11-15', '2024-11-23'),
    ('ORD-20240403', '华东', 'XR-2000B', 4000000.00, NULL, 'COMPLETED', '2024-12-10', '2024-12-18');

-- ---------------------------------------------------------------------------
-- 自检：行数对不上说明脚本和数据不同步
-- ---------------------------------------------------------------------------
SELECT 'regions' AS t, count(*) FROM regions
UNION ALL SELECT 'region_provinces', count(*) FROM region_provinces
UNION ALL SELECT 'products', count(*) FROM products
UNION ALL SELECT 'sales_orders', count(*) FROM sales_orders
UNION ALL SELECT 'orders_archive', count(*) FROM orders_archive
ORDER BY 1;
