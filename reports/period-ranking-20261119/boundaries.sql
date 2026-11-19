BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','sparse-row-is-not-previous-calendar-month','result',q.value) FROM (WITH m(t,n) AS(VALUES(DATE '2016-10-01',324),(DATE '2016-12-01',1)),
bad AS(SELECT t,n-LAG(n) OVER(ORDER BY t) AS delta FROM m)
SELECT json_build_array(b.delta,c.n-COALESCE(p.n,0))
FROM m c LEFT JOIN m p ON p.t=c.t-INTERVAL '1 month'
JOIN bad b ON b.t=c.t WHERE c.t=DATE '2016-12-01') q(value);
SELECT json_build_object('id','report-first-period-can-have-history','result',q.value) FROM (WITH m(t,n) AS(VALUES(DATE '2023-12-01',10),(DATE '2024-01-01',17)),
compared AS(SELECT t,n-LAG(n) OVER(ORDER BY t) AS delta FROM m)
SELECT to_json(delta) FROM compared WHERE t=DATE '2024-01-01') q(value);
SELECT json_build_object('id','partition-by-full-nontime-group','result',q.value) FROM (WITH m(g,t,n) AS(VALUES('a',DATE '2024-01-01',10),('a',DATE '2024-02-01',12),
 ('b',DATE '2024-01-01',100),('b',DATE '2024-02-01',107)),
q AS(SELECT g,t,n-LAG(n) OVER(PARTITION BY g ORDER BY t) AS d FROM m)
SELECT json_agg(json_build_array(g,d) ORDER BY g) FROM q WHERE t=DATE '2024-02-01') q(value);
SELECT json_build_object('id','requested-periods-are-not-inner-joined-away','result',q.value) FROM (WITH months AS(SELECT generate_series(1,12) AS m),
facts(m,v) AS(VALUES(1,10),(9,90))
SELECT json_build_array(COUNT(*),COUNT(v),MAX(v) FILTER(WHERE months.m=12))
FROM months LEFT JOIN facts USING(m)) q(value);
SELECT json_build_object('id','coverage-is-separate-from-event-minmax','result',q.value) FROM (WITH requested(period,complete) AS(VALUES('a',true),('b',false)),
facts(period,n) AS(SELECT 'none',1 WHERE false)
SELECT json_agg(json_build_array(r.period,CASE WHEN r.complete THEN COALESCE(f.n,0) ELSE f.n END)
 ORDER BY r.period) FROM requested r LEFT JOIN facts f USING(period)) q(value);
SELECT json_build_object('id','calendar-empty-input-no-invented-period','result',q.value) FROM (WITH m(t,n) AS(SELECT DATE '2024-01-01',1 WHERE false),
calendar AS(SELECT generate_series(MIN(t),MAX(t),INTERVAL '1 month') AS t FROM m)
SELECT to_json(COUNT(*)) FROM calendar) q(value);
SELECT json_build_object('id','identity-does-not-outrank-metric','result',q.value) FROM (WITH x(id,v) AS(VALUES('z',100),('a',90),('b',90),('c',NULL::integer)),
q AS(SELECT * FROM x ORDER BY v DESC NULLS LAST,id LIMIT 2)
SELECT json_agg(id ORDER BY v DESC,id) FROM q) q(value);
SELECT json_build_object('id','input-order-does-not-decide-cutoff-tie','result',q.value) FROM (WITH x(id,v) AS(VALUES('b',90),('z',100),('a',90)),
q AS(SELECT * FROM x ORDER BY v DESC,id LIMIT 2)
SELECT json_agg(id ORDER BY v DESC,id) FROM q) q(value);
SELECT json_build_object('id','composite-key-breaks-within-parent-ties','result',q.value) FROM (WITH x(order_id,payment_sequential,v) AS(VALUES('b',1,24),('a',2,24),('a',1,24)),
q AS(SELECT * FROM x ORDER BY v DESC,order_id,payment_sequential LIMIT 1)
SELECT json_build_array(order_id,payment_sequential,v) FROM q) q(value);
SELECT json_build_object('id','unknown-is-not-numeric-maximum','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',NULL::integer),('b',24)),
q AS(SELECT * FROM x WHERE v IS NOT NULL ORDER BY v DESC,id LIMIT 1)
SELECT to_json(id) FROM q) q(value);
SELECT json_build_object('id','all-unknown-has-no-confirmed-maximum','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',NULL::integer),('b',NULL::integer)),
q AS(SELECT * FROM x WHERE v IS NOT NULL ORDER BY v DESC,id LIMIT 1)
SELECT to_json(COUNT(*)) FROM q) q(value);
SELECT json_build_object('id','ranking-keys-must-exclude-display-identity','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',100),('b',90),('c',90)),
ranked AS(SELECT *,RANK() OVER(ORDER BY v DESC) AS good_rank,
 RANK() OVER(ORDER BY v DESC,id) AS broken_rank FROM x)
SELECT json_build_array(COUNT(*) FILTER(WHERE good_rank<=2),COUNT(*) FILTER(WHERE broken_rank<=2))
FROM ranked) q(value);
SELECT json_build_object('id','insufficient-candidates-are-not-padded','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',1)),q AS(SELECT * FROM x ORDER BY v DESC,id LIMIT 20)
SELECT to_json(COUNT(*)) FROM q) q(value);
SELECT json_build_object('id','absent-values-do-not-become-zero-means','result',q.value) FROM (WITH x(g,v) AS(VALUES('unknown',NULL::numeric),('known',10::numeric)),
groups(g) AS(VALUES('absent'),('unknown'),('known'))
SELECT json_agg(json_build_array(g,n,mean) ORDER BY g) FROM(
 SELECT gs.g,COUNT(x.g) AS n,AVG(x.v) AS mean FROM groups gs LEFT JOIN x USING(g) GROUP BY gs.g) q) q(value);
SELECT json_build_object('id','minimum-direction-is-ascending','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',5),('b',1)),
q AS(SELECT * FROM x WHERE v IS NOT NULL ORDER BY v ASC,id LIMIT 1)
SELECT to_json(id) FROM q) q(value);
SELECT json_build_object('id','display-precision-is-the-default-tie-precision','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',10.003::numeric),('b',10.004::numeric)),
q AS(SELECT id,ROUND(v,2) AS shown FROM x ORDER BY ROUND(v,2) DESC,id LIMIT 1)
SELECT json_build_array(id,shown) FROM q) q(value);
SELECT json_build_object('id','rank-and-dense-rank-have-different-contracts','result',q.value) FROM (WITH x(id,v) AS(VALUES('a',100),('b',100),('c',90)),
q AS(SELECT RANK() OVER(ORDER BY v DESC) AS r,DENSE_RANK() OVER(ORDER BY v DESC) AS d FROM x)
SELECT json_build_array(COUNT(*) FILTER(WHERE r<=2),COUNT(*) FILTER(WHERE d<=2)) FROM q) q(value);
COMMIT;
