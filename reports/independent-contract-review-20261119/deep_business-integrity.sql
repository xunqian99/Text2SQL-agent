BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
SELECT jsonb_build_object('check','undeclared foreign-key coverage','result',jsonb_build_object(
 'refund_items_missing_refund',(SELECT COUNT(*) FROM refund_items ri WHERE NOT EXISTS(SELECT 1 FROM refunds r WHERE r.refund_id=ri.refund_id)),
 'refund_items_missing_order_item',(SELECT COUNT(*) FROM refund_items ri WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id)),
 'settlement_items_missing_settlement',(SELECT COUNT(*) FROM settlement_items si WHERE NOT EXISTS(SELECT 1 FROM settlements st WHERE st.settlement_id=si.settlement_id)),
 'tag_maps_missing_member',(SELECT COUNT(*) FROM user_tag_map utm WHERE NOT EXISTS(SELECT 1 FROM members m WHERE m.member_id=utm.member_id)),
 'tag_maps_missing_tag',(SELECT COUNT(*) FROM user_tag_map utm WHERE NOT EXISTS(SELECT 1 FROM user_tags ut WHERE ut.tag_id=utm.tag_id)),
 'refunds_missing_order',(SELECT COUNT(*) FROM refunds r WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.order_id=r.order_id)),
 'snapshots_missing_member',(SELECT COUNT(*) FROM member_snapshot ms WHERE NOT EXISTS(SELECT 1 FROM members m WHERE m.member_id=ms.member_id)),
 'coupon_usage_missing_order',(SELECT COUNT(*) FROM coupon_usages cu WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.order_id=cu.order_id))
));
ROLLBACK;
