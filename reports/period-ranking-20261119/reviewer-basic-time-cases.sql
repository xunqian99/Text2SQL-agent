BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';

SELECT jsonb_build_object('case','sparse_month','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(m,n) AS(VALUES(TIMESTAMP '2018-01-01',10),(TIMESTAMP '2018-03-01',5)),
 sparse AS(SELECT m,n,n-LAG(n) OVER(ORDER BY m) AS delta FROM facts),
 calendar AS(SELECT generate_series(TIMESTAMP '2018-01-01',TIMESTAMP '2018-03-01',INTERVAL '1 month') AS m),
 filled AS(SELECT c.m,COALESCE(f.n,0) AS n FROM calendar c LEFT JOIN facts f USING(m)),
 aligned AS(SELECT m,n,n-LAG(n) OVER(ORDER BY m) AS delta FROM filled)
 SELECT a.m,a.n,s.delta AS sparse_row_delta,a.delta AS natural_month_delta
 FROM aligned a LEFT JOIN sparse s USING(m) ORDER BY a.m
) q;

SELECT jsonb_build_object('case','multiple_dimensions','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(m,state,n) AS(VALUES(TIMESTAMP '2018-01-01','A',10),(TIMESTAMP '2018-01-01','B',20),
 (TIMESTAMP '2018-02-01','A',11),(TIMESTAMP '2018-02-01','B',21))
 SELECT m,state,n,n-LAG(n) OVER(ORDER BY m,state) AS unpartitioned_delta,
 n-LAG(n) OVER(PARTITION BY state ORDER BY m) AS per_identity_delta FROM facts ORDER BY m,state
) q;

SELECT jsonb_build_object('case','history_outside_report','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(m,n) AS(VALUES(TIMESTAMP '2017-12-01',5),(TIMESTAMP '2018-01-01',8)),
 bad AS(SELECT m,n,n-LAG(n) OVER(ORDER BY m) AS delta FROM facts WHERE m>=TIMESTAMP '2018-01-01'),
 full_history AS(SELECT m,n,n-LAG(n) OVER(ORDER BY m) AS delta FROM facts)
 SELECT b.m,b.n,b.delta AS report_filtered_first_delta,h.delta AS history_retained_delta
 FROM bad b JOIN full_history h USING(m) WHERE h.m>=TIMESTAMP '2018-01-01'
) q;

SELECT jsonb_build_object('case','ties_and_composite_key','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(order_id,payment_sequential,score) AS(VALUES('a',2,100),('a',1,100),('b',1,90),('c',1,80))
 SELECT *,RANK() OVER(ORDER BY score DESC) AS rank_metric,
 RANK() OVER(ORDER BY score DESC,order_id,payment_sequential) AS rank_with_identity,
 DENSE_RANK() OVER(ORDER BY score DESC) AS dense_metric
 FROM facts ORDER BY score DESC,order_id ASC,payment_sequential ASC
) q;

SELECT jsonb_build_object('case','empty_and_all_null','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(id,score) AS(VALUES(1,NULL::numeric),(2,NULL::numeric)),
 selected_max AS(SELECT * FROM facts WHERE score IS NOT NULL ORDER BY score DESC NULLS LAST,id LIMIT 5),
 ordinary_list AS(SELECT * FROM facts ORDER BY score DESC NULLS LAST,id LIMIT 5)
 SELECT 5 AS requested_n,(SELECT COUNT(*) FROM facts WHERE FALSE) AS empty_input_rows,
 (SELECT COUNT(*) FROM selected_max) AS confirmed_max_candidates,
 (SELECT COUNT(*) FROM ordinary_list) AS ordinary_list_rows
) q;

SELECT jsonb_build_object('case','requested_calendar_vs_coverage','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH coverage AS(SELECT TIMESTAMP '2018-01-01' AS lo,TIMESTAMP '2018-11-01' AS hi),
 requested AS(SELECT generate_series(TIMESTAMP '2018-09-01',TIMESTAMP '2018-12-01',INTERVAL '1 month') AS m),
 facts(m,n) AS(VALUES(TIMESTAMP '2018-09-01',9),(TIMESTAMP '2018-10-01',4))
 SELECT r.m,(r.m>=c.lo AND r.m<c.hi) AS known_covered,
 CASE WHEN r.m>=c.lo AND r.m<c.hi THEN COALESCE(f.n,0) ELSE NULL END AS count_with_coverage,
 (r.m>=c.lo AND r.m<c.hi) AS retained_by_intersection_calendar
 FROM requested r CROSS JOIN coverage c LEFT JOIN facts f ON f.m=r.m ORDER BY r.m
) q;

SELECT jsonb_build_object('case','all_null_event_time','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(id,event_time) AS(VALUES(1,NULL::timestamp),(2,NULL::timestamp)),
 bounds AS(SELECT MIN(event_time) AS lo,MAX(event_time) AS hi FROM facts),
 inferred AS(SELECT generate_series(lo,hi,INTERVAL '1 month') AS m FROM bounds),
 requested AS(SELECT generate_series(TIMESTAMP '2018-01-01',TIMESTAMP '2018-03-01',INTERVAL '1 month') AS m)
 SELECT (SELECT COUNT(*) FROM facts) AS unknown_time_facts,
 (SELECT COUNT(*) FROM inferred) AS inferred_calendar_rows,
 (SELECT COUNT(*) FROM requested) AS explicit_calendar_rows
) q;

SELECT jsonb_build_object('case','rounded_vs_raw_ranking','rows',jsonb_agg(to_jsonb(q))) FROM (
 WITH facts(id,avg_value) AS(VALUES('a',10.003::numeric),('b',10.004::numeric)),
 raw_top AS(SELECT * FROM facts ORDER BY avg_value DESC,id ASC LIMIT 1),
 rounded_top AS(SELECT * FROM facts ORDER BY ROUND(avg_value,2) DESC,id ASC LIMIT 1)
 SELECT (SELECT id FROM raw_top) AS raw_winner,(SELECT id FROM rounded_top) AS rounded_winner,
 (SELECT ROUND(avg_value,2) FROM raw_top) AS raw_display,
 (SELECT ROUND(avg_value,2) FROM rounded_top) AS rounded_display
) q;

ROLLBACK;
