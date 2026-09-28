-- ============================================================================
-- smart-data-qa · 业务库「扩表」脚本（迭代 19）
-- ----------------------------------------------------------------------------
-- 由 scripts/exp19-gen-extra-tables.py 生成，请勿手改（改数据请改脚本）。
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
DROP TABLE IF EXISTS product_price_history CASCADE;
DROP TABLE IF EXISTS purchase_order_items CASCADE;
DROP TABLE IF EXISTS purchase_orders CASCADE;
DROP TABLE IF EXISTS inventory CASCADE;
DROP TABLE IF EXISTS shipments CASCADE;
DROP TABLE IF EXISTS invoices CASCADE;
DROP TABLE IF EXISTS payments CASCADE;
DROP TABLE IF EXISTS returns CASCADE;
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS employees CASCADE;
DROP TABLE IF EXISTS product_categories CASCADE;
DROP TABLE IF EXISTS channels CASCADE;
DROP TABLE IF EXISTS warehouses CASCADE;
DROP TABLE IF EXISTS suppliers CASCADE;
DROP TABLE IF EXISTS customers CASCADE;

-- ---------------------------------------------------------------------------
-- customers · 客户档案
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    customer_id      varchar(16)    NOT NULL,
    customer_name    varchar(64)    NOT NULL,
    customer_type    varchar(16)    NOT NULL,
    city             varchar(32)   ,
    registered_at    timestamp      NOT NULL,
    PRIMARY KEY (customer_id)
);

COMMENT ON TABLE  customers IS '客户档案';
COMMENT ON COLUMN customers.customer_id IS '客户编号，CUS- + 6 位数字';
COMMENT ON COLUMN customers.customer_name IS '客户名称';
COMMENT ON COLUMN customers.customer_type IS '客户类型：ENTERPRISE/DISTRIBUTOR/RETAIL';
COMMENT ON COLUMN customers.city IS '客户所在城市';
COMMENT ON COLUMN customers.registered_at IS '注册时间';

INSERT INTO customers (customer_id, customer_name, customer_type, city, registered_at) VALUES
    ('CUS-100001', '启明科技', 'ENTERPRISE', '上海', '2024-03-11'),
    ('CUS-100002', '宏远电子', 'ENTERPRISE', '杭州', '2024-07-02'),
    ('CUS-100003', '北方数码城', 'DISTRIBUTOR', '北京', '2025-01-19'),
    ('CUS-100004', '南岭商贸', 'DISTRIBUTOR', '广州', '2025-04-08'),
    ('CUS-100005', '线上散客旗舰店', 'RETAIL', '厦门', '2025-09-23'),
    ('CUS-100006', '西部智联', 'ENTERPRISE', '成都', '2026-02-14');

-- ---------------------------------------------------------------------------
-- suppliers · 供应商档案
-- ---------------------------------------------------------------------------
CREATE TABLE suppliers (
    supplier_id      varchar(16)    NOT NULL,
    supplier_name    varchar(64)    NOT NULL,
    supplier_type    varchar(16)    NOT NULL,
    cooperation_start timestamp      NOT NULL,
    PRIMARY KEY (supplier_id)
);

COMMENT ON TABLE  suppliers IS '供应商档案';
COMMENT ON COLUMN suppliers.supplier_id IS '供应商编号，SUP- + 5 位数字';
COMMENT ON COLUMN suppliers.supplier_name IS '供应商名称';
COMMENT ON COLUMN suppliers.supplier_type IS '供应商类型：COMPONENT/ODM/LOGISTICS';
COMMENT ON COLUMN suppliers.cooperation_start IS '合作起始时间';

INSERT INTO suppliers (supplier_id, supplier_name, supplier_type, cooperation_start) VALUES
    ('SUP-20001', '瑞芯微电子', 'COMPONENT', '2023-05-06'),
    ('SUP-20002', '广达精密', 'ODM', '2023-11-20'),
    ('SUP-20003', '顺捷物流', 'LOGISTICS', '2024-02-01'),
    ('SUP-20004', '京东方光电', 'COMPONENT', '2024-08-15'),
    ('SUP-20005', '立讯智造', 'ODM', '2025-03-27');

-- ---------------------------------------------------------------------------
-- warehouses · 仓库档案
-- ---------------------------------------------------------------------------
CREATE TABLE warehouses (
    warehouse_code   varchar(16)    NOT NULL,
    warehouse_name   varchar(64)    NOT NULL,
    region           varchar(16)    NOT NULL REFERENCES regions(region_name),
    PRIMARY KEY (warehouse_code)
);

COMMENT ON TABLE  warehouses IS '仓库档案';
COMMENT ON COLUMN warehouses.warehouse_code IS '仓库编码';
COMMENT ON COLUMN warehouses.warehouse_name IS '仓库名称';
COMMENT ON COLUMN warehouses.region IS '所属销售区域名，取值见 regions';

INSERT INTO warehouses (warehouse_code, warehouse_name, region) VALUES
    ('WH-EAST', '华东中心仓', '华东'),
    ('WH-SOUTH', '华南中心仓', '华南'),
    ('WH-NORTH', '华北中心仓', '华北'),
    ('WH-WEST', '西部中心仓', '西部');

-- ---------------------------------------------------------------------------
-- channels · 销售渠道档案
-- ---------------------------------------------------------------------------
CREATE TABLE channels (
    channel_code     varchar(16)    NOT NULL,
    channel_name     varchar(64)    NOT NULL,
    channel_type     varchar(16)    NOT NULL,
    PRIMARY KEY (channel_code)
);

COMMENT ON TABLE  channels IS '销售渠道档案';
COMMENT ON COLUMN channels.channel_code IS '渠道编码';
COMMENT ON COLUMN channels.channel_name IS '渠道名称';
COMMENT ON COLUMN channels.channel_type IS '渠道类型：ONLINE/OFFLINE/PARTNER';

INSERT INTO channels (channel_code, channel_name, channel_type) VALUES
    ('CH-ONLINE', '官方商城', 'ONLINE'),
    ('CH-DIRECT', '直营门店', 'OFFLINE'),
    ('CH-PARTNER', '渠道代理', 'PARTNER'),
    ('CH-RETAIL', '第三方零售平台', 'ONLINE');

-- ---------------------------------------------------------------------------
-- product_categories · 产品分类字典
-- ---------------------------------------------------------------------------
CREATE TABLE product_categories (
    category_code    varchar(16)    NOT NULL,
    category_name    varchar(64)    NOT NULL,
    parent_code      varchar(16)   ,
    PRIMARY KEY (category_code)
);

COMMENT ON TABLE  product_categories IS '产品分类字典';
COMMENT ON COLUMN product_categories.category_code IS '分类编码';
COMMENT ON COLUMN product_categories.category_name IS '分类名称';
COMMENT ON COLUMN product_categories.parent_code IS '上级分类编码，顶层分类为 NULL';

INSERT INTO product_categories (category_code, category_name, parent_code) VALUES
    ('CAT-TERM', '智能终端', NULL),
    ('CAT-TERM-XR', 'XR 系列终端', 'CAT-TERM'),
    ('CAT-MOD', '工业模组', NULL),
    ('CAT-ACC', '配件', NULL),
    ('CAT-CUS', '定制机型', NULL),
    ('CAT-TERM-MT', 'MT 系列终端', 'CAT-MOD');

-- ---------------------------------------------------------------------------
-- employees · 员工档案
-- ---------------------------------------------------------------------------
CREATE TABLE employees (
    employee_id      varchar(16)    NOT NULL,
    employee_name    varchar(32)    NOT NULL,
    region           varchar(16)    NOT NULL REFERENCES regions(region_name),
    title            varchar(32)    NOT NULL,
    PRIMARY KEY (employee_id)
);

COMMENT ON TABLE  employees IS '员工档案';
COMMENT ON COLUMN employees.employee_id IS '员工编号，EMP- + 4 位数字';
COMMENT ON COLUMN employees.employee_name IS '员工姓名';
COMMENT ON COLUMN employees.region IS '所属销售区域名，取值见 regions';
COMMENT ON COLUMN employees.title IS '岗位：SALES/SUPPORT/MANAGER';

INSERT INTO employees (employee_id, employee_name, region, title) VALUES
    ('EMP-1001', '王建国', '华东', 'MANAGER'),
    ('EMP-1002', '李思远', '华东', 'SALES'),
    ('EMP-1003', '陈晓芸', '华东', 'SALES'),
    ('EMP-1004', '赵鹏', '华南', 'SALES'),
    ('EMP-1005', '孙敏', '华北', 'SUPPORT'),
    ('EMP-1006', '周涛', '华中', 'SALES'),
    ('EMP-1007', '吴倩', '西部', 'SUPPORT'),
    ('EMP-1008', '郑昊', '东北', 'SALES');

-- ---------------------------------------------------------------------------
-- order_items · 订单明细行
-- ---------------------------------------------------------------------------
CREATE TABLE order_items (
    item_id          varchar(20)    NOT NULL,
    order_id         varchar(16)    NOT NULL REFERENCES sales_orders(order_id),
    product_code     varchar(16)    NOT NULL REFERENCES products(product_code),
    quantity         integer        NOT NULL,
    unit_price       numeric(14,2)  NOT NULL,
    PRIMARY KEY (item_id)
);

COMMENT ON TABLE  order_items IS '订单明细行';
COMMENT ON COLUMN order_items.item_id IS '明细行编号';
COMMENT ON COLUMN order_items.order_id IS '所属订单号';
COMMENT ON COLUMN order_items.product_code IS '产品编码';
COMMENT ON COLUMN order_items.quantity IS '数量';
COMMENT ON COLUMN order_items.unit_price IS '成交单价（不含税）';

INSERT INTO order_items (item_id, order_id, product_code, quantity, unit_price) VALUES
    ('IT-0001', 'ORD-20260202', 'XR-2000B', 1000, 4300.00),
    ('IT-0002', 'ORD-20260203', 'XR-2000C', 1000, 5300.00),
    ('IT-0003', 'ORD-20260204', 'XR-3000A', 1000, 9000.00),
    ('IT-0004', 'ORD-20260205', 'MT-1000B', 1000, 1900.00),
    ('IT-0005', 'ORD-20260206', 'XR-2000B', 800, 4300.00),
    ('IT-0006', 'ORD-20260206', 'SX-0101', 860, 232.56),
    ('IT-0007', 'ORD-20260207', 'XR-2010B', 1000, 4700.00),
    ('IT-0008', 'ORD-20260210', 'XR-2000B', 1000, 4300.00),
    ('IT-0009', 'ORD-20260211', 'XR-2000C', 1000, 5300.00),
    ('IT-0010', 'ORD-20260213', 'XR-3000A', 889, 9000.00),
    ('IT-0011', 'ORD-20260218', 'XR-2000B', 744, 4300.00),
    ('IT-0012', 'ORD-20260222', 'XR-2000B', 349, 4300.00);

-- ---------------------------------------------------------------------------
-- returns · 退货申请单
-- ---------------------------------------------------------------------------
CREATE TABLE returns (
    return_id        varchar(20)    NOT NULL,
    order_id         varchar(16)    NOT NULL REFERENCES sales_orders(order_id),
    apply_at         timestamp      NOT NULL,
    approved_at      timestamp     ,
    refund_amount    numeric(14,2)  NOT NULL,
    status           varchar(16)    NOT NULL,
    PRIMARY KEY (return_id)
);

COMMENT ON TABLE  returns IS '退货申请单';
COMMENT ON COLUMN returns.return_id IS '退货申请编号';
COMMENT ON COLUMN returns.order_id IS '关联订单号';
COMMENT ON COLUMN returns.apply_at IS '申请提交时间';
COMMENT ON COLUMN returns.approved_at IS '审核通过时间，未通过为 NULL';
COMMENT ON COLUMN returns.refund_amount IS '退货金额（不含税）';
COMMENT ON COLUMN returns.status IS '申请状态：SUBMITTED/APPROVED/REJECTED';

INSERT INTO returns (return_id, order_id, apply_at, approved_at, refund_amount, status) VALUES
    ('RT-0001', 'ORD-20260203', '2026-04-25', '2026-04-29', 300000.00, 'APPROVED'),
    ('RT-0002', 'ORD-20260205', '2026-05-15', '2026-05-18', 1900000.00, 'APPROVED'),
    ('RT-0003', 'ORD-20260207', '2026-06-09', '2026-06-12', 200000.00, 'APPROVED'),
    ('RT-0004', 'ORD-20260211', '2026-05-10', '2026-05-14', 500000.00, 'APPROVED'),
    ('RT-0005', 'ORD-20260216', '2026-05-12', '2026-05-16', 700000.00, 'APPROVED'),
    ('RT-0006', 'ORD-20260220', '2026-05-06', '2026-05-09', 100000.00, 'APPROVED'),
    ('RT-0007', 'ORD-20260212', '2026-05-22', NULL, 150000.00, 'REJECTED'),
    ('RT-0008', 'ORD-20260219', '2026-06-19', NULL, 90000.00, 'SUBMITTED');

-- ---------------------------------------------------------------------------
-- payments · 回款记录
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    payment_id       varchar(20)    NOT NULL,
    order_id         varchar(16)    NOT NULL REFERENCES sales_orders(order_id),
    paid_at          timestamp      NOT NULL,
    amount           numeric(14,2)  NOT NULL,
    channel          varchar(16)    NOT NULL,
    PRIMARY KEY (payment_id)
);

COMMENT ON TABLE  payments IS '回款记录';
COMMENT ON COLUMN payments.payment_id IS '回款流水号';
COMMENT ON COLUMN payments.order_id IS '关联订单号';
COMMENT ON COLUMN payments.paid_at IS '到账时间';
COMMENT ON COLUMN payments.amount IS '回款金额';
COMMENT ON COLUMN payments.channel IS '回款渠道：WIRE/CARD/ALIPAY';

INSERT INTO payments (payment_id, order_id, paid_at, amount, channel) VALUES
    ('PM-0001', 'ORD-20260202', '2026-05-06', 4300000.00, 'WIRE'),
    ('PM-0002', 'ORD-20260203', '2026-05-20', 5000000.00, 'WIRE'),
    ('PM-0003', 'ORD-20260204', '2026-06-03', 9000000.00, 'WIRE'),
    ('PM-0004', 'ORD-20260206', '2026-07-02', 4300000.00, 'CARD'),
    ('PM-0005', 'ORD-20260207', '2026-07-14', 4500000.00, 'WIRE'),
    ('PM-0006', 'ORD-20260210', '2026-05-19', 4300000.00, 'ALIPAY'),
    ('PM-0007', 'ORD-20260213', '2026-07-09', 8000000.00, 'WIRE'),
    ('PM-0008', 'ORD-20260215', '2026-05-22', 4300000.00, 'CARD');

-- ---------------------------------------------------------------------------
-- invoices · 发票开具记录
-- ---------------------------------------------------------------------------
CREATE TABLE invoices (
    invoice_id       varchar(20)    NOT NULL,
    order_id         varchar(16)    NOT NULL REFERENCES sales_orders(order_id),
    invoice_no       varchar(32)    NOT NULL,
    issued_at        timestamp      NOT NULL,
    amount           numeric(14,2)  NOT NULL,
    invoice_type     varchar(16)    NOT NULL,
    PRIMARY KEY (invoice_id)
);

COMMENT ON TABLE  invoices IS '发票开具记录';
COMMENT ON COLUMN invoices.invoice_id IS '发票记录编号';
COMMENT ON COLUMN invoices.order_id IS '关联订单号';
COMMENT ON COLUMN invoices.invoice_no IS '发票号码';
COMMENT ON COLUMN invoices.issued_at IS '开票时间';
COMMENT ON COLUMN invoices.amount IS '开票金额';
COMMENT ON COLUMN invoices.invoice_type IS '发票类型：NORMAL/SPECIAL';

INSERT INTO invoices (invoice_id, order_id, invoice_no, issued_at, amount, invoice_type) VALUES
    ('INV-0001', 'ORD-20260202', '00A240000001', '2026-04-20', 4300000.00, 'SPECIAL'),
    ('INV-0002', 'ORD-20260203', '00A240000002', '2026-05-02', 5300000.00, 'SPECIAL'),
    ('INV-0003', 'ORD-20260204', '00A240000003', '2026-05-14', 9000000.00, 'SPECIAL'),
    ('INV-0004', 'ORD-20260210', '00A240000004', '2026-04-23', 4300000.00, 'NORMAL'),
    ('INV-0005', 'ORD-20260215', '00A240000005', '2026-04-26', 4300000.00, 'NORMAL'),
    ('INV-0006', 'ORD-20260218', '00A240000006', '2026-05-07', 3200000.00, 'NORMAL');

-- ---------------------------------------------------------------------------
-- shipments · 发货单（记录收货客户与承运信息）
-- ---------------------------------------------------------------------------
CREATE TABLE shipments (
    shipment_id      varchar(20)    NOT NULL,
    order_id         varchar(16)    NOT NULL REFERENCES sales_orders(order_id),
    customer_id      varchar(16)    NOT NULL REFERENCES customers(customer_id),
    shipped_at       timestamp      NOT NULL,
    carrier          varchar(16)    NOT NULL,
    status           varchar(16)    NOT NULL,
    PRIMARY KEY (shipment_id)
);

COMMENT ON TABLE  shipments IS '发货单（记录收货客户与承运信息）';
COMMENT ON COLUMN shipments.shipment_id IS '发货单号';
COMMENT ON COLUMN shipments.order_id IS '关联订单号';
COMMENT ON COLUMN shipments.customer_id IS '收货客户编号';
COMMENT ON COLUMN shipments.shipped_at IS '发货时间';
COMMENT ON COLUMN shipments.carrier IS '承运商';
COMMENT ON COLUMN shipments.status IS '发货状态：PENDING/SHIPPED/DELIVERED';

INSERT INTO shipments (shipment_id, order_id, customer_id, shipped_at, carrier, status) VALUES
    ('SH-0001', 'ORD-20260202', 'CUS-100001', '2026-04-16', '顺丰', 'DELIVERED'),
    ('SH-0002', 'ORD-20260203', 'CUS-100002', '2026-04-29', '顺丰', 'DELIVERED'),
    ('SH-0003', 'ORD-20260204', 'CUS-100001', '2026-05-12', '京东物流', 'DELIVERED'),
    ('SH-0004', 'ORD-20260206', 'CUS-100005', '2026-06-03', '中通', 'DELIVERED'),
    ('SH-0005', 'ORD-20260207', 'CUS-100003', '2026-06-15', '顺丰', 'DELIVERED'),
    ('SH-0006', 'ORD-20260210', 'CUS-100004', '2026-04-20', '京东物流', 'DELIVERED'),
    ('SH-0007', 'ORD-20260211', 'CUS-100004', '2026-05-14', '中通', 'DELIVERED'),
    ('SH-0008', 'ORD-20260213', 'CUS-100001', '2026-06-10', '顺丰', 'DELIVERED'),
    ('SH-0009', 'ORD-20260215', 'CUS-100003', '2026-04-23', '顺丰', 'DELIVERED'),
    ('SH-0010', 'ORD-20260218', 'CUS-100006', '2026-05-04', '中通', 'SHIPPED'),
    ('SH-0011', 'ORD-20260302', 'CUS-100001', '2026-08-20', '顺丰', 'SHIPPED');

-- ---------------------------------------------------------------------------
-- inventory · 库存快照（按仓库 × 产品 × 快照日）
-- ---------------------------------------------------------------------------
CREATE TABLE inventory (
    snapshot_id      varchar(24)    NOT NULL,
    warehouse_code   varchar(16)    NOT NULL REFERENCES warehouses(warehouse_code),
    product_code     varchar(16)    NOT NULL REFERENCES products(product_code),
    snapshot_at      timestamp      NOT NULL,
    on_hand          integer        NOT NULL,
    PRIMARY KEY (snapshot_id)
);

COMMENT ON TABLE  inventory IS '库存快照（按仓库 × 产品 × 快照日）';
COMMENT ON COLUMN inventory.snapshot_id IS '快照编号';
COMMENT ON COLUMN inventory.warehouse_code IS '仓库编码';
COMMENT ON COLUMN inventory.product_code IS '产品编码';
COMMENT ON COLUMN inventory.snapshot_at IS '快照时间';
COMMENT ON COLUMN inventory.on_hand IS '在库数量';

INSERT INTO inventory (snapshot_id, warehouse_code, product_code, snapshot_at, on_hand) VALUES
    ('SN-20260901-01', 'WH-EAST', 'XR-2000B', '2026-09-01', 3120),
    ('SN-20260901-02', 'WH-EAST', 'XR-2000C', '2026-09-01', 1480),
    ('SN-20260901-03', 'WH-EAST', 'XR-3000A', '2026-09-01', 620),
    ('SN-20260901-04', 'WH-SOUTH', 'XR-2000B', '2026-09-01', 2050),
    ('SN-20260901-05', 'WH-SOUTH', 'XR-2010B', '2026-09-01', 990),
    ('SN-20260901-06', 'WH-NORTH', 'XR-2000B', '2026-09-01', 1730),
    ('SN-20260901-07', 'WH-WEST', 'MT-1000B', '2026-09-01', 4400),
    ('SN-20260901-08', 'WH-WEST', 'SX-0101', '2026-09-01', 26000);

-- ---------------------------------------------------------------------------
-- purchase_orders · 采购单（公司向供应商进货）
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_orders (
    po_id            varchar(20)    NOT NULL,
    supplier_id      varchar(16)    NOT NULL REFERENCES suppliers(supplier_id),
    ordered_at       timestamp      NOT NULL,
    amount           numeric(14,2)  NOT NULL,
    status           varchar(16)    NOT NULL,
    PRIMARY KEY (po_id)
);

COMMENT ON TABLE  purchase_orders IS '采购单（公司向供应商进货）';
COMMENT ON COLUMN purchase_orders.po_id IS '采购单号';
COMMENT ON COLUMN purchase_orders.supplier_id IS '供应商编号';
COMMENT ON COLUMN purchase_orders.ordered_at IS '下单时间';
COMMENT ON COLUMN purchase_orders.amount IS '采购金额';
COMMENT ON COLUMN purchase_orders.status IS '状态：CREATED/RECEIVED/CANCELLED';

INSERT INTO purchase_orders (po_id, supplier_id, ordered_at, amount, status) VALUES
    ('PO-30001', 'SUP-20001', '2026-01-08', 6200000.00, 'RECEIVED'),
    ('PO-30002', 'SUP-20002', '2026-02-14', 9800000.00, 'RECEIVED'),
    ('PO-30003', 'SUP-20004', '2026-03-22', 4100000.00, 'RECEIVED'),
    ('PO-30004', 'SUP-20005', '2026-04-30', 7300000.00, 'RECEIVED'),
    ('PO-30005', 'SUP-20001', '2026-05-19', 5400000.00, 'RECEIVED'),
    ('PO-30006', 'SUP-20003', '2026-06-11', 260000.00, 'RECEIVED'),
    ('PO-30007', 'SUP-20002', '2026-08-05', 8700000.00, 'CREATED'),
    ('PO-30008', 'SUP-20004', '2026-08-27', 3900000.00, 'CANCELLED');

-- ---------------------------------------------------------------------------
-- purchase_order_items · 采购单明细行
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_order_items (
    item_id          varchar(20)    NOT NULL,
    po_id            varchar(20)    NOT NULL REFERENCES purchase_orders(po_id),
    product_code     varchar(16)    NOT NULL REFERENCES products(product_code),
    quantity         integer        NOT NULL,
    unit_cost        numeric(14,2)  NOT NULL,
    PRIMARY KEY (item_id)
);

COMMENT ON TABLE  purchase_order_items IS '采购单明细行';
COMMENT ON COLUMN purchase_order_items.item_id IS '明细行编号';
COMMENT ON COLUMN purchase_order_items.po_id IS '所属采购单号';
COMMENT ON COLUMN purchase_order_items.product_code IS '产品编码';
COMMENT ON COLUMN purchase_order_items.quantity IS '数量';
COMMENT ON COLUMN purchase_order_items.unit_cost IS '采购单价（成本）';

INSERT INTO purchase_order_items (item_id, po_id, product_code, quantity, unit_cost) VALUES
    ('PIT-0001', 'PO-30001', 'XR-2000B', 1500, 2600.00),
    ('PIT-0002', 'PO-30002', 'XR-2000B', 2400, 2550.00),
    ('PIT-0003', 'PO-30002', 'XR-2000C', 1300, 3200.00),
    ('PIT-0004', 'PO-30003', 'XR-3000A', 700, 5400.00),
    ('PIT-0005', 'PO-30004', 'XR-2000B', 2000, 2500.00),
    ('PIT-0006', 'PO-30005', 'XR-2010B', 1400, 2800.00),
    ('PIT-0007', 'PO-30005', 'SX-0101', 20000, 108.00),
    ('PIT-0008', 'PO-30007', 'MT-1000B', 3600, 1150.00);

-- ---------------------------------------------------------------------------
-- product_price_history · 产品标准单价变更历史
-- ---------------------------------------------------------------------------
CREATE TABLE product_price_history (
    change_id        varchar(20)    NOT NULL,
    product_code     varchar(16)    NOT NULL REFERENCES products(product_code),
    changed_at       timestamp      NOT NULL,
    old_price        numeric(14,2) ,
    new_price        numeric(14,2)  NOT NULL,
    reason           varchar(64)   ,
    PRIMARY KEY (change_id)
);

COMMENT ON TABLE  product_price_history IS '产品标准单价变更历史';
COMMENT ON COLUMN product_price_history.change_id IS '变更记录编号';
COMMENT ON COLUMN product_price_history.product_code IS '产品编码';
COMMENT ON COLUMN product_price_history.changed_at IS '变更生效时间';
COMMENT ON COLUMN product_price_history.old_price IS '变更前标准单价，首次定价为 NULL';
COMMENT ON COLUMN product_price_history.new_price IS '变更后标准单价';
COMMENT ON COLUMN product_price_history.reason IS '变更原因';

INSERT INTO product_price_history (change_id, product_code, changed_at, old_price, new_price, reason) VALUES
    ('PC-0001', 'XR-2000B', '2025-01-05', NULL, 4099.00, '新品定价'),
    ('PC-0002', 'XR-2000B', '2025-11-01', 4099.00, 4299.00, '成本上涨'),
    ('PC-0003', 'XR-2000C', '2025-01-05', NULL, 5199.00, '新品定价'),
    ('PC-0004', 'XR-2000C', '2026-01-10', 5199.00, 5299.00, '元器件涨价'),
    ('PC-0005', 'XR-3000A', '2025-03-18', NULL, 9299.00, '新品定价'),
    ('PC-0006', 'XR-3000A', '2026-02-01', 9299.00, 8999.00, '促销调价'),
    ('PC-0007', 'XR-2000A', '2024-06-01', 4299.00, 3999.00, '停产清库存'),
    ('PC-0008', 'MT-1000B', '2025-05-20', 1999.00, 1899.00, 'B 端议价');

-- ---------------------------------------------------------------------------
-- 自检：每张表都应有行（0 行说明种子数据漏了）
-- ---------------------------------------------------------------------------
SELECT 'customers' AS t, count(*) AS n FROM customers UNION ALL SELECT 'suppliers' AS t, count(*) AS n FROM suppliers UNION ALL SELECT 'warehouses' AS t, count(*) AS n FROM warehouses UNION ALL SELECT 'channels' AS t, count(*) AS n FROM channels UNION ALL SELECT 'product_categories' AS t, count(*) AS n FROM product_categories UNION ALL SELECT 'employees' AS t, count(*) AS n FROM employees UNION ALL SELECT 'order_items' AS t, count(*) AS n FROM order_items UNION ALL SELECT 'returns' AS t, count(*) AS n FROM returns UNION ALL SELECT 'payments' AS t, count(*) AS n FROM payments UNION ALL SELECT 'invoices' AS t, count(*) AS n FROM invoices UNION ALL SELECT 'shipments' AS t, count(*) AS n FROM shipments UNION ALL SELECT 'inventory' AS t, count(*) AS n FROM inventory UNION ALL SELECT 'purchase_orders' AS t, count(*) AS n FROM purchase_orders UNION ALL SELECT 'purchase_order_items' AS t, count(*) AS n FROM purchase_order_items UNION ALL SELECT 'product_price_history' AS t, count(*) AS n FROM product_price_history ORDER BY 1;
