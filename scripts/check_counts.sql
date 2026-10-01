\pset pager off
SELECT 'customers' t, COUNT(*) n FROM customers
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'sellers', COUNT(*) FROM sellers
UNION ALL SELECT 'geolocation', COUNT(*) FROM geolocation
UNION ALL SELECT 'order_payments', COUNT(*) FROM order_payments
UNION ALL SELECT 'order_reviews', COUNT(*) FROM order_reviews
UNION ALL SELECT 'product_category_translation', COUNT(*) FROM product_category_translation
-- 扩展表
UNION ALL SELECT 'member_levels', COUNT(*) FROM member_levels
UNION ALL SELECT 'members', COUNT(*) FROM members
UNION ALL SELECT 'member_snapshot', COUNT(*) FROM member_snapshot
UNION ALL SELECT 'user_tags', COUNT(*) FROM user_tags
UNION ALL SELECT 'user_tag_map', COUNT(*) FROM user_tag_map
UNION ALL SELECT 'regions', COUNT(*) FROM regions
UNION ALL SELECT 'shipping_addresses', COUNT(*) FROM shipping_addresses
UNION ALL SELECT 'brands', COUNT(*) FROM brands
UNION ALL SELECT 'product_brand_map', COUNT(*) FROM product_brand_map
UNION ALL SELECT 'product_price_history', COUNT(*) FROM product_price_history
UNION ALL SELECT 'campaigns', COUNT(*) FROM campaigns
UNION ALL SELECT 'coupons', COUNT(*) FROM coupons
UNION ALL SELECT 'campaign_products', COUNT(*) FROM campaign_products
UNION ALL SELECT 'warehouses', COUNT(*) FROM warehouses
UNION ALL SELECT 'shipments', COUNT(*) FROM shipments
UNION ALL SELECT 'shipment_tracking', COUNT(*) FROM shipment_tracking
UNION ALL SELECT 'inventory', COUNT(*) FROM inventory
UNION ALL SELECT 'inventory_movements', COUNT(*) FROM inventory_movements
UNION ALL SELECT 'ticket_categories', COUNT(*) FROM ticket_categories
UNION ALL SELECT 'support_tickets', COUNT(*) FROM support_tickets
UNION ALL SELECT 'ticket_messages', COUNT(*) FROM ticket_messages
UNION ALL SELECT 'refunds', COUNT(*) FROM refunds
UNION ALL SELECT 'refund_items', COUNT(*) FROM refund_items
UNION ALL SELECT 'settlements', COUNT(*) FROM settlements
UNION ALL SELECT 'settlement_items', COUNT(*) FROM settlement_items
UNION ALL SELECT 'coupon_usages', COUNT(*) FROM coupon_usages
UNION ALL SELECT 'referrals', COUNT(*) FROM referrals
UNION ALL SELECT 'search_logs', COUNT(*) FROM search_logs
ORDER BY 1;

\echo '### TABLE_TOTAL'
SELECT COUNT(*) AS table_count FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
