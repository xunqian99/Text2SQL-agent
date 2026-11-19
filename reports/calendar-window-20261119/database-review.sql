BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','one-day-contains-whole-reference-day','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('before',TIMESTAMP '2024-02-29 23:59:59.999999'),('start',TIMESTAMP '2024-03-01 00:00:00'),('noon',TIMESTAMP '2024-03-01 12:00:00'),('last',TIMESTAMP '2024-03-01 23:59:59.999999'),('next',TIMESTAMP '2024-03-02 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t >= DATE '2024-03-01' AND t < DATE '2024-03-01'+1) AS probe(value);
SELECT json_build_object('id','two-days-cross-leap-day','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('before',TIMESTAMP '2024-02-29 23:59:59.999999'),('start',TIMESTAMP '2024-03-01 00:00:00'),('noon',TIMESTAMP '2024-03-01 12:00:00'),('last',TIMESTAMP '2024-03-01 23:59:59.999999'),('next',TIMESTAMP '2024-03-02 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t >= DATE '2024-03-01'-1 AND t < DATE '2024-03-01'+1) AS probe(value);
SELECT json_build_object('id','between-date-end-is-only-midnight','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('before',TIMESTAMP '2024-02-29 23:59:59.999999'),('start',TIMESTAMP '2024-03-01 00:00:00'),('noon',TIMESTAMP '2024-03-01 12:00:00'),('last',TIMESTAMP '2024-03-01 23:59:59.999999'),('next',TIMESTAMP '2024-03-02 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t BETWEEN DATE '2024-02-29' AND DATE '2024-03-01') AS probe(value);
SELECT json_build_object('id','explicit-exclusive-end-remains-exclusive','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('before',TIMESTAMP '2024-02-29 23:59:59.999999'),('start',TIMESTAMP '2024-03-01 00:00:00'),('noon',TIMESTAMP '2024-03-01 12:00:00'),('last',TIMESTAMP '2024-03-01 23:59:59.999999'),('next',TIMESTAMP '2024-03-02 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t >= TIMESTAMP '2024-03-01 00:00' AND t < TIMESTAMP '2024-03-01 12:00') AS probe(value);
SELECT json_build_object('id','explicit-inclusive-clock-retains-noon','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('before',TIMESTAMP '2024-02-29 23:59:59.999999'),('start',TIMESTAMP '2024-03-01 00:00:00'),('noon',TIMESTAMP '2024-03-01 12:00:00'),('last',TIMESTAMP '2024-03-01 23:59:59.999999'),('next',TIMESTAMP '2024-03-02 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t >= TIMESTAMP '2024-03-01 00:00' AND t <= TIMESTAMP '2024-03-01 12:00') AS probe(value);
SELECT json_build_object('id','rolling-24-hours-preserves-clock','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('lower',TIMESTAMP '2024-02-29 12:00:00'),('inside',TIMESTAMP '2024-02-29 12:00:00.000001'),('anchor',TIMESTAMP '2024-03-01 12:00:00'),('after',TIMESTAMP '2024-03-01 12:00:00.000001')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t > TIMESTAMP '2024-03-01 12:00'-INTERVAL '24 hours' AND t <= TIMESTAMP '2024-03-01 12:00') AS probe(value);
SELECT json_build_object('id','thirty-natural-dates','result',probe.value) FROM (WITH events AS(SELECT d::date AS t FROM generate_series(
      TIMESTAMP '2024-01-01',TIMESTAMP '2024-03-02',INTERVAL '1 day') d)
    SELECT to_json(COUNT(*)) FROM events WHERE t >= DATE '2024-03-01'-29 AND t < DATE '2024-03-01'+1) AS probe(value);
SELECT json_build_object('id','off-by-one-control-is-thirty-one','result',probe.value) FROM (WITH events AS(SELECT d::date AS t FROM generate_series(
      TIMESTAMP '2024-01-01',TIMESTAMP '2024-03-02',INTERVAL '1 day') d)
    SELECT to_json(COUNT(*)) FROM events WHERE t >= DATE '2024-03-01'-30 AND t < DATE '2024-03-01'+1) AS probe(value);
SELECT json_build_object('id','cross-year-two-natural-days','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('before',TIMESTAMP '2023-12-30 23:59:59.999999'),('start',TIMESTAMP '2023-12-31 00:00:00'),('anchor',TIMESTAMP '2024-01-01 23:59:59.999999'),('end',TIMESTAMP '2024-01-02 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t >= DATE '2024-01-01'-1 AND t < DATE '2024-01-01'+1) AS probe(value);
SELECT json_build_object('id','calendar-month-keeps-final-microsecond','result',probe.value) FROM (WITH events(id,t) AS(VALUES ('first',TIMESTAMP '2024-02-01 00:00:00'),('last',TIMESTAMP '2024-02-29 23:59:59.999999'),('next',TIMESTAMP '2024-03-01 00:00:00')) SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE t >= DATE '2024-02-01' AND t < DATE '2024-03-01') AS probe(value);
SELECT json_build_object('id','null-anchor-count-and-mean','result',probe.value) FROM (WITH events(t,value) AS(VALUES(NULL::date,10),(NULL::date,20)),
    anchor AS(SELECT MAX(t) AS d FROM events)
    SELECT json_build_array(COUNT(*),AVG(value)) FROM events CROSS JOIN anchor
    WHERE t >= d-29 AND t < d+1) AS probe(value);
SELECT json_build_object('id','global-anchor-before-business-filter','result',probe.value) FROM (WITH events(group_id,t) AS(VALUES('old',DATE '2024-01-01'),('new',DATE '2024-03-01')),
    anchor AS(SELECT MAX(t) AS d FROM events)
    SELECT to_json(COUNT(*)) FROM events CROSS JOIN anchor
    WHERE group_id='old' AND t >= d-1 AND t < d+1) AS probe(value);
SELECT json_build_object('id','login-anchor-is-not-order-anchor','result',probe.value) FROM (WITH orders(t) AS(VALUES(DATE '2018-10-17')), members(t) AS(VALUES(DATE '2018-10-23')),
    anchor AS(SELECT MAX(t) AS d FROM members)
    SELECT to_json(COUNT(*)) FROM members CROSS JOIN anchor WHERE t >= d AND t < d+1) AS probe(value);
SELECT json_build_object('id','max-ignores-null-without-changing-window','result',probe.value) FROM (WITH events(t) AS(VALUES(NULL::date),(DATE '2024-03-01'),(DATE '2024-02-01')),
    anchor AS(SELECT MAX(t) AS d FROM events)
    SELECT to_json(COUNT(*)) FROM events CROSS JOIN anchor WHERE t >= d-29 AND t < d+1) AS probe(value);
SELECT json_build_object('id','real-member-window','result',probe.value) FROM (WITH a AS(SELECT MAX(last_login_date) AS d FROM members)
    SELECT json_build_object('anchor',d,'start',d-29,'endExclusive',d+1,
      'natural30',(SELECT COUNT(*) FROM members WHERE last_login_date>=d-29 AND last_login_date<d+1),
      'gold',(SELECT COUNT(*) FROM members WHERE last_login_date>d-30),
      'wrongInclusive31',(SELECT COUNT(*) FROM members WHERE last_login_date>=d-30),
      'extraBoundaryMembers',(SELECT COUNT(*) FROM members WHERE last_login_date=d-30)) FROM a) AS probe(value);
SELECT json_build_object('id','real-order-window-reference-difference','result',probe.value) FROM (WITH a AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
    SELECT json_build_object('anchor',mx,'calendarStart',mx::date-89,'calendarEndExclusive',mx::date+1,
      'natural90',(SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp>=mx::date-89 AND order_purchase_timestamp<mx::date+1),
      'oldGoldRolling90',(SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp>mx-INTERVAL '90 days'),
      'removedPartialDate',(SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp>mx-INTERVAL '90 days' AND order_purchase_timestamp<mx::date-89)) FROM a) AS probe(value);
SELECT json_build_object('id','real-week-sales-reference-difference','result',probe.value) FROM (WITH a AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders),
    calendar AS(SELECT DATE_TRUNC('week',o.order_purchase_timestamp)::date AS week_start,SUM(oi.price) AS sales
      FROM orders o JOIN order_items oi USING(order_id) CROSS JOIN a
      WHERE o.order_purchase_timestamp>=mx::date-89 AND o.order_purchase_timestamp<mx::date+1 GROUP BY 1),
    old_gold AS(SELECT DATE_TRUNC('week',o.order_purchase_timestamp)::date AS week_start,SUM(oi.price) AS sales
      FROM orders o JOIN order_items oi USING(order_id) CROSS JOIN a
      WHERE o.order_purchase_timestamp>mx-INTERVAL '90 days' AND o.order_purchase_timestamp<=mx GROUP BY 1)
    SELECT COALESCE(json_agg(json_build_object('week',g.week_start,'calendar',ROUND(c.sales,2),
      'oldGold',ROUND(g.sales,2),'difference',ROUND(g.sales-c.sales,2)) ORDER BY g.week_start),'[]'::json)
      FROM old_gold g FULL JOIN calendar c USING(week_start) WHERE c.sales IS DISTINCT FROM g.sales) AS probe(value);
COMMIT;
