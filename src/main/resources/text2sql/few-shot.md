# Text2SQL few-shot 样例

> 迭代 20。用法：`sdaq.text2sql.few-shot-size` 控制用前几条（0 = 关）。
> **顺序即优先级**——消融实验从 0 条一路加上去，所以最有价值的放最前面。

---

### 1
问：2026年第二季度华东区的销售额是多少？
SQL：
SELECT SUM(order_amount - COALESCE(refund_amount, 0)) AS net_sales
FROM sales_orders
WHERE region = '华东'
  AND status IN ('COMPLETED', 'REFUNDED')
  AND completed_at >= '2026-04-01' AND completed_at < '2026-07-01';
要点：区域名存的就是「华东」，**不带「区」字**；归期看 `completed_at`（不是 `created_at`）；只计 `COMPLETED` / `REFUNDED`；`refund_amount` 可能为 NULL，要 COALESCE。

### 2
问：2026年第二季度 XR 产品线的销售额是多少？
SQL：
SELECT SUM(o.order_amount - COALESCE(o.refund_amount, 0)) AS net_sales
FROM sales_orders o
JOIN products p ON p.product_code = o.product_code
WHERE p.line_code = 'XR'
  AND o.status IN ('COMPLETED', 'REFUNDED')
  AND o.completed_at >= '2026-04-01' AND o.completed_at < '2026-07-01';
要点：产品线在 `products.line_code`（取值 XR/MT/SX/CUS），订单表里没有，必须 JOIN。

### 3
问：2024 年华东区的销售额是多少？
SQL：
SELECT SUM(order_amount - COALESCE(refund_amount, 0)) AS net_sales
FROM orders_archive
WHERE region = '华东'
  AND status IN ('COMPLETED', 'REFUNDED')
  AND completed_at >= '2024-01-01' AND completed_at < '2025-01-01';
要点：**2024 年及以前的订单只在 `orders_archive`**；当期（2025 年起）在 `sales_orders`。两者不要混、也不要 UNION。

### 4
问：2026年第二季度各区域的销售额排名
SQL：
SELECT region, SUM(order_amount - COALESCE(refund_amount, 0)) AS net_sales
FROM sales_orders
WHERE status IN ('COMPLETED', 'REFUNDED')
  AND completed_at >= '2026-04-01' AND completed_at < '2026-07-01'
GROUP BY region
ORDER BY net_sales DESC;
要点：排名按净额排；不要因为 `regions` 表存在就硬 JOIN 它（订单表本身就存了区域名）。

### 5
问：华东区都包含哪些省份？
SQL：
SELECT province_name
FROM region_provinces
WHERE region_name = '华东';
要点：区域到省份的映射在 `region_provinces`；**订单表只有区域名，没有省份字段**，所以「某个省的销售额」在库里算不出来。

### 6
问：2026年第二季度华东区的客单价是多少？
SQL：
SELECT SUM(order_amount - COALESCE(refund_amount, 0)) / COUNT(*) AS avg_order_value
FROM sales_orders
WHERE region = '华东'
  AND status = 'COMPLETED'
  AND completed_at >= '2026-04-01' AND completed_at < '2026-07-01';
要点：客单价 = 销售额 ÷ 订单数，**分母只计 `COMPLETED`**（口径出自《销售指标定义手册》）；不要用 `AVG()`——它算的是「每单平均净额」，分母不一样。

### 7
问：订单明细里每种产品一共卖了多少数量？
SQL：
SELECT p.product_name, SUM(i.quantity) AS qty
FROM order_items i
JOIN products p ON p.product_code = i.product_code
GROUP BY p.product_name
ORDER BY qty DESC;
要点：`order_items` 是明细行（数量 × 单价）。**口径上的「销售额」不用它**，要用 `sales_orders` 表头的 `order_amount` − `refund_amount`。
