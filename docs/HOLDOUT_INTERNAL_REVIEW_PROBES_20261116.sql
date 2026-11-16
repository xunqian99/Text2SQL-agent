-- Holdout v1 内部审核：可复查的只读证据。
-- 对应 docs/HOLDOUT_INTERNAL_REVIEW_20261116.md。
-- 本文件整理已运行查询的口径，没有在编写附录时重新执行数据库核查。
-- 下面的“预期”是 2026-11-16 审核时本地数据库观察值，不保证未来数据库相同。
-- 除 VALUES 构造的纯内存反例，其余查询读取 public 下现有业务表。
-- 不含 DDL / DML，不修改题集、Gold 或数据库。
-- 执行客户端必须支持 PostgreSQL；事务内任一语句失败后先 ROLLBACK 再重试。

BEGIN READ ONLY;

-- P1 / T3-H009：状态2=运输中，即使送达时间已有值，也不是状态3=已签收。
-- DDL 没有禁止状态与时间不一致，因此该 VALUES 记录是合法的语义反例。
-- 窗口按 shipped_at；区间 [2018-02-01, 2018-03-01)，至少7个完整24小时。
-- 预期：gold_count=1，require_status3_count=0。
SELECT
    'T3-H009-values-counterexample' AS probe,
    COUNT(*) FILTER (
        WHERE delivered_at >= shipped_at + INTERVAL '7 days'
    ) AS gold_count,
    COUNT(*) FILTER (
        WHERE status = 3
          AND delivered_at >= shipped_at + INTERVAL '7 days'
    ) AS require_status3_count
FROM (
    VALUES (2, TIMESTAMP '2018-02-01', TIMESTAMP '2018-02-10')
) AS s(status, shipped_at, delivered_at)
WHERE shipped_at >= TIMESTAMP '2018-02-01'
  AND shipped_at < TIMESTAMP '2018-03-01';

-- T3-H009：当前数据恰好无法揭露漏状态条件。
-- 预期：gold_count=4510，require_status3_count=4510，non3_with_time_count=0。
SELECT
    'T3-H009-current-data' AS probe,
    COUNT(*) FILTER (
        WHERE delivered_at >= shipped_at + INTERVAL '7 days'
    ) AS gold_count,
    COUNT(*) FILTER (
        WHERE status = 3
          AND delivered_at >= shipped_at + INTERVAL '7 days'
    ) AS require_status3_count,
    COUNT(*) FILTER (
        WHERE status IS DISTINCT FROM 3
          AND delivered_at >= shipped_at + INTERVAL '7 days'
    ) AS non3_with_time_count
FROM shipments
WHERE shipped_at >= TIMESTAMP '2018-02-01'
  AND shipped_at < TIMESTAMP '2018-03-01';

-- P1 / T3-H011：窗口按解决时间；Gold 平均使用秒/3600，保留2位小数。
-- negative_duration_count 不是额外过滤条件，而是所选记录中的异常计数。
-- 非状态3可能表示关闭后仍保留解决时间，不能仅据此认定 Gold 错误。
-- 预期：negative_duration_count=176，current_status_not3_count=80，gold_avg_hours=54.69。
SELECT
    'T3-H011-duration-quality' AS probe,
    COUNT(*) FILTER (
        WHERE resolved_at < created_at
    ) AS negative_duration_count,
    COUNT(*) FILTER (
        WHERE status IS DISTINCT FROM 3
    ) AS current_status_not3_count,
    ROUND(
        AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600.0),
        2
    ) AS gold_avg_hours
FROM support_tickets
WHERE resolved_at >= TIMESTAMP '2018-01-01'
  AND resolved_at < TIMESTAMP '2018-02-01';

-- P1 / T3-H014：窗口按转化时间；Gold 平均使用秒/86400，保留2位小数。
-- 预期：negative_duration_count=16，missing_start_count=0，gold_avg_days=137.31。
SELECT
    'T3-H014-duration-quality' AS probe,
    COUNT(*) FILTER (
        WHERE converted_at < created_at
    ) AS negative_duration_count,
    COUNT(*) FILTER (
        WHERE created_at IS NULL
    ) AS missing_start_count,
    ROUND(
        AVG(EXTRACT(EPOCH FROM (converted_at - created_at)) / 86400.0),
        2
    ) AS gold_avg_days
FROM referrals
WHERE converted_at >= TIMESTAMP '2018-04-01'
  AND converted_at < TIMESTAMP '2018-05-01';

-- P1 / T6-H010：严格复用 v1 的分母范围。
-- 创建时间窗口 [2018-01-01,2018-07-01)，解决时间非空，能匹配分类且 SLA 非空。
-- Gold 对耗时只有 <=SLA 的上界，没有 >=0 的下界。
-- 对照口径将负耗时同时从分子和分母排除；这是诊断对照，不替换 v1 标准答案。
-- 百分数为乘100后的数值，分母0返回NULL，均保留2位小数。
-- 预期：gold_denominator=2615，gold_numerator=1190，negative_duration_count=1079，
--       gold_sla_pct=45.51；
--       nonnegative_denominator=1536，nonnegative_numerator=111，nonnegative_sla_pct=7.23。
WITH eligible AS (
    SELECT
        EXTRACT(EPOCH FROM (t.resolved_at - t.created_at)) / 3600.0 AS duration_hours,
        c.sla_hours
    FROM support_tickets t
    JOIN ticket_categories c ON c.category_id = t.category_id
    WHERE t.created_at >= TIMESTAMP '2018-01-01'
      AND t.created_at < TIMESTAMP '2018-07-01'
      AND t.resolved_at IS NOT NULL
      AND c.sla_hours IS NOT NULL
), counts AS (
    SELECT
        COUNT(*) AS gold_denominator,
        COUNT(*) FILTER (WHERE duration_hours <= sla_hours) AS gold_numerator,
        COUNT(*) FILTER (WHERE duration_hours < 0) AS negative_duration_count,
        COUNT(*) FILTER (WHERE duration_hours >= 0) AS nonnegative_denominator,
        COUNT(*) FILTER (
            WHERE duration_hours >= 0 AND duration_hours <= sla_hours
        ) AS nonnegative_numerator
    FROM eligible
)
SELECT
    'T6-H010-gold-versus-nonnegative' AS probe,
    gold_denominator,
    gold_numerator,
    negative_duration_count,
    ROUND(100.0 * gold_numerator / NULLIF(gold_denominator, 0), 2) AS gold_sla_pct,
    nonnegative_denominator,
    nonnegative_numerator,
    ROUND(
        100.0 * nonnegative_numerator / NULLIF(nonnegative_denominator, 0),
        2
    ) AS nonnegative_sla_pct
FROM counts;

-- P2 / T6-H003：商品金额+运费先按订单汇总，再与2018年有效订单关联。
-- LEFT 分母包含无明细订单；COUNT(i.order_id) 是错误 INNER JOIN 分母的等价计数。
-- 对于存在明细的订单，金额求和相同，因此可在同一次 LEFT JOIN 中同时输出对照。
-- 输出金额沿用库内金额单位，不做汇率换算；这不是百分数。
-- 预期：gold_left_denominator=53532，wrong_inner_denominator=53531，
--       gold_left_rounded_aov=160.53，wrong_inner_rounded_aov=160.53。
-- 两个分母不同但四舍五入后答案相同，证明当前结果比较会漏检该错误。
WITH item_totals AS (
    SELECT order_id, SUM(price + freight_value) AS amount
    FROM order_items
    GROUP BY order_id
)
SELECT
    'T6-H003-rounding-masks-denominator-error' AS probe,
    COUNT(*) AS gold_left_denominator,
    COUNT(i.order_id) AS wrong_inner_denominator,
    ROUND(
        SUM(COALESCE(i.amount, 0)) / NULLIF(COUNT(*), 0),
        2
    ) AS gold_left_rounded_aov,
    ROUND(
        SUM(i.amount) / NULLIF(COUNT(i.order_id), 0),
        2
    ) AS wrong_inner_rounded_aov
FROM orders o
LEFT JOIN item_totals i ON i.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
  AND o.order_purchase_timestamp >= TIMESTAMP '2018-01-01'
  AND o.order_purchase_timestamp < TIMESTAMP '2019-01-01';

-- P2 / T5-H002、T5-H005、T5-H006：检查当前数据是否含多当前品牌。
-- product_brand_map 主键(product_id,brand_id)只能保证组内映射不重复，
-- 并不保证每个 product_id 最多一个 is_current=true。
-- 下面“没有实例”不能证明该唯一性是数据库约束，也不能证明业务已同意重叠归属。
-- 预期：max_all_mapping_count_per_product=1，multi_current_brand_product_count=0。
SELECT
    'current-brand-cardinality' AS probe,
    (
        SELECT MAX(mapping_count)
        FROM (
            SELECT product_id, COUNT(*) AS mapping_count
            FROM product_brand_map
            GROUP BY product_id
        ) all_maps
    ) AS max_all_mapping_count_per_product,
    (
        SELECT COUNT(*)
        FROM (
            SELECT product_id
            FROM product_brand_map
            WHERE is_current = true
            GROUP BY product_id
            HAVING COUNT(*) > 1
        ) multi_current
    ) AS multi_current_brand_product_count;

ROLLBACK;
