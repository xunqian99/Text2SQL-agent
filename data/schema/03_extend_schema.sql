-- 扩展表：在 Olist 真实订单域之上，补充相邻业务域
-- 目的：新增 28 张扩展表，把表数从 9 张扩充到 37 张，让 schema 检索成为真正的问题
--
-- 设计原则（这些"坑"是故意留的，它们才是检索模块存在的理由）：
--   1. 枚举值用数字代码（status=1/2/3），模型必须靠映射才能理解
--   2. 同义/近义字段并存（user_status 与 member_status），制造选择歧义
--   3. 部分表故意不加注释，模拟历史遗留库
--   4. 存在冗余表（member_snapshot 与 members 信息重叠）
--   5. 多跳 join 路径（订单 -> 发货 -> 仓库 -> 区域）

DROP TABLE IF EXISTS search_logs CASCADE;
DROP TABLE IF EXISTS settlement_items CASCADE;
DROP TABLE IF EXISTS settlements CASCADE;
DROP TABLE IF EXISTS refund_items CASCADE;
DROP TABLE IF EXISTS refunds CASCADE;
DROP TABLE IF EXISTS ticket_messages CASCADE;
DROP TABLE IF EXISTS support_tickets CASCADE;
DROP TABLE IF EXISTS ticket_categories CASCADE;
DROP TABLE IF EXISTS inventory_movements CASCADE;
DROP TABLE IF EXISTS inventory CASCADE;
DROP TABLE IF EXISTS shipment_tracking CASCADE;
DROP TABLE IF EXISTS shipments CASCADE;
DROP TABLE IF EXISTS warehouses CASCADE;
DROP TABLE IF EXISTS referrals CASCADE;
DROP TABLE IF EXISTS campaign_products CASCADE;
DROP TABLE IF EXISTS coupon_usages CASCADE;
DROP TABLE IF EXISTS coupons CASCADE;
DROP TABLE IF EXISTS campaigns CASCADE;
DROP TABLE IF EXISTS regions CASCADE;
DROP TABLE IF EXISTS shipping_addresses CASCADE;
DROP TABLE IF EXISTS user_tag_map CASCADE;
DROP TABLE IF EXISTS user_tags CASCADE;
DROP TABLE IF EXISTS member_snapshot CASCADE;
DROP TABLE IF EXISTS members CASCADE;
DROP TABLE IF EXISTS member_levels CASCADE;
DROP TABLE IF EXISTS product_price_history CASCADE;
DROP TABLE IF EXISTS brands CASCADE;
DROP TABLE IF EXISTS product_brand_map CASCADE;

-- ============ 用户域 ============

CREATE TABLE member_levels (
    level_id        INTEGER PRIMARY KEY,
    level_code      VARCHAR(16) NOT NULL,
    level_name      VARCHAR(32) NOT NULL,
    discount_rate   NUMERIC(4,3)
);
COMMENT ON TABLE member_levels IS '会员等级定义';

-- 会员表：以自然人（customer_unique_id）为单位，与 customers 表粒度不同
CREATE TABLE members (
    member_id       BIGSERIAL PRIMARY KEY,
    customer_unique_id VARCHAR(32) NOT NULL UNIQUE,
    level_id        INTEGER REFERENCES member_levels(level_id),
    status          SMALLINT NOT NULL,
    points          INTEGER DEFAULT 0,
    register_date   DATE,
    last_login_date DATE
);
COMMENT ON COLUMN members.status IS '会员状态：1=正常 2=冻结 3=注销';

-- 冗余快照表：模拟真实系统中的历史遗留表，与 members 信息重叠
CREATE TABLE member_snapshot (
    snapshot_id     BIGSERIAL PRIMARY KEY,
    member_id       BIGINT,
    snapshot_date   DATE,
    level_id        INTEGER,
    points          INTEGER
);

CREATE TABLE user_tags (
    tag_id      INTEGER PRIMARY KEY,
    tag_code    VARCHAR(32) NOT NULL,
    tag_name    VARCHAR(64) NOT NULL
);
COMMENT ON TABLE user_tags IS '用户标签定义表';

CREATE TABLE user_tag_map (
    member_id   BIGINT NOT NULL,
    tag_id      INTEGER NOT NULL,
    tagged_at   DATE,
    PRIMARY KEY (member_id, tag_id)
);

-- ============ 地址与区域 ============

CREATE TABLE regions (
    region_code VARCHAR(8) PRIMARY KEY,
    region_name VARCHAR(32) NOT NULL,
    macro_region VARCHAR(32)
);
COMMENT ON TABLE regions IS '巴西州份到大区的映射，用于区域维度分析';

CREATE TABLE shipping_addresses (
    address_id      BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(32) NOT NULL,
    zip_code_prefix INTEGER,
    city            VARCHAR(64),
    state           VARCHAR(8),
    is_default      BOOLEAN DEFAULT false
);

-- ============ 营销域 ============

CREATE TABLE campaigns (
    campaign_id     INTEGER PRIMARY KEY,
    campaign_name   VARCHAR(128) NOT NULL,
    campaign_type   VARCHAR(32),
    start_date      DATE,
    end_date        DATE,
    budget          NUMERIC(14,2),
    status          SMALLINT
);
COMMENT ON COLUMN campaigns.status IS '活动状态：1=草稿 2=进行中 3=已结束 4=已取消';

CREATE TABLE coupons (
    coupon_id       INTEGER PRIMARY KEY,
    coupon_code     VARCHAR(32) NOT NULL UNIQUE,
    campaign_id     INTEGER REFERENCES campaigns(campaign_id),
    discount_type   VARCHAR(16),
    discount_value  NUMERIC(10,2),
    min_order_amount NUMERIC(10,2),
    valid_from      DATE,
    valid_to        DATE
);
COMMENT ON COLUMN coupons.discount_type IS '折扣类型：fixed=满减 percent=折扣';

CREATE TABLE coupon_usages (
    usage_id        BIGSERIAL PRIMARY KEY,
    coupon_id       INTEGER NOT NULL REFERENCES coupons(coupon_id),
    order_id        VARCHAR(32) NOT NULL,
    member_id       BIGINT,
    used_at         TIMESTAMP,
    discount_amount NUMERIC(10,2)
);

CREATE TABLE campaign_products (
    campaign_id INTEGER NOT NULL REFERENCES campaigns(campaign_id),
    product_id  VARCHAR(32) NOT NULL,
    PRIMARY KEY (campaign_id, product_id)
);

CREATE TABLE referrals (
    referral_id     BIGSERIAL PRIMARY KEY,
    referrer_member_id BIGINT NOT NULL,
    invitee_member_id  BIGINT,
    invite_code     VARCHAR(16),
    created_at      TIMESTAMP,
    converted_at    TIMESTAMP,
    reward_amount   NUMERIC(10,2)
);

-- ============ 履约域 ============

CREATE TABLE warehouses (
    warehouse_id    INTEGER PRIMARY KEY,
    warehouse_name  VARCHAR(64) NOT NULL,
    region_code     VARCHAR(8),
    capacity        INTEGER
);

CREATE TABLE shipments (
    shipment_id     BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(32) NOT NULL,
    warehouse_id    INTEGER REFERENCES warehouses(warehouse_id),
    carrier         VARCHAR(32),
    shipped_at      TIMESTAMP,
    delivered_at    TIMESTAMP,
    status          SMALLINT
);
COMMENT ON COLUMN shipments.status IS '发货状态：1=待发货 2=运输中 3=已签收 4=异常';

CREATE TABLE shipment_tracking (
    tracking_id     BIGSERIAL PRIMARY KEY,
    shipment_id     BIGINT NOT NULL,
    event_time      TIMESTAMP,
    event_code      VARCHAR(24),
    location        VARCHAR(64)
);

CREATE TABLE inventory (
    warehouse_id    INTEGER NOT NULL,
    product_id      VARCHAR(32) NOT NULL,
    quantity        INTEGER,
    safety_stock    INTEGER,
    updated_at      TIMESTAMP,
    PRIMARY KEY (warehouse_id, product_id)
);

CREATE TABLE inventory_movements (
    movement_id     BIGSERIAL PRIMARY KEY,
    warehouse_id    INTEGER NOT NULL,
    product_id      VARCHAR(32) NOT NULL,
    movement_type   SMALLINT,
    quantity        INTEGER,
    moved_at        TIMESTAMP
);
COMMENT ON COLUMN inventory_movements.movement_type IS '出入库类型：1=入库 2=出库 3=退货入库 4=盘亏';

-- ============ 客服域 ============

CREATE TABLE ticket_categories (
    category_id     INTEGER PRIMARY KEY,
    category_name   VARCHAR(64) NOT NULL,
    sla_hours       INTEGER
);

CREATE TABLE support_tickets (
    ticket_id       BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(32),
    member_id       BIGINT,
    category_id     INTEGER REFERENCES ticket_categories(category_id),
    priority        SMALLINT,
    status          SMALLINT,
    created_at      TIMESTAMP,
    resolved_at     TIMESTAMP
);
COMMENT ON COLUMN support_tickets.priority IS '优先级：1=低 2=中 3=高 4=紧急';
COMMENT ON COLUMN support_tickets.status IS '工单状态：1=待处理 2=处理中 3=已解决 4=已关闭';

CREATE TABLE ticket_messages (
    message_id      BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL,
    sender_type     VARCHAR(16),
    content         TEXT,
    created_at      TIMESTAMP
);

-- ============ 财务域 ============

CREATE TABLE refunds (
    refund_id       BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(32) NOT NULL,
    refund_amount   NUMERIC(12,2),
    reason_code     VARCHAR(24),
    status          SMALLINT,
    requested_at    TIMESTAMP,
    completed_at    TIMESTAMP
);
COMMENT ON COLUMN refunds.status IS '退款状态：1=待审核 2=已通过 3=已拒绝 4=已退款';
COMMENT ON COLUMN refunds.reason_code IS '退款原因代码：CUST=客户取消 QUAL=质量问题 LATE=配送超时 OTHER=其他';

CREATE TABLE refund_items (
    refund_id       BIGINT NOT NULL,
    order_id        VARCHAR(32) NOT NULL,
    order_item_id   INTEGER NOT NULL,
    refund_qty      INTEGER,
    refund_value    NUMERIC(12,2),
    PRIMARY KEY (refund_id, order_id, order_item_id)
);

CREATE TABLE settlements (
    settlement_id   BIGSERIAL PRIMARY KEY,
    seller_id       VARCHAR(32) NOT NULL,
    period_start    DATE,
    period_end      DATE,
    total_amount    NUMERIC(14,2),
    commission      NUMERIC(14,2),
    net_amount      NUMERIC(14,2),
    status          SMALLINT,
    paid_at         TIMESTAMP
);
COMMENT ON COLUMN settlements.status IS '结算状态：1=待结算 2=已结算 3=已打款';

CREATE TABLE settlement_items (
    settlement_id   BIGINT NOT NULL,
    order_id        VARCHAR(32) NOT NULL,
    order_item_id   INTEGER NOT NULL,
    gross_amount    NUMERIC(12,2),
    commission_rate NUMERIC(5,4),
    commission_amount NUMERIC(12,2),
    PRIMARY KEY (settlement_id, order_id, order_item_id)
);

-- ============ 商品域补充 ============

CREATE TABLE brands (
    brand_id        INTEGER PRIMARY KEY,
    brand_name      VARCHAR(64) NOT NULL,
    country         VARCHAR(32)
);

CREATE TABLE product_price_history (
    history_id      BIGSERIAL PRIMARY KEY,
    product_id      VARCHAR(32) NOT NULL,
    old_price       NUMERIC(12,2),
    new_price       NUMERIC(12,2),
    changed_at      TIMESTAMP
);

-- 商品-品牌关联表：真实系统里品牌往往不是商品表的直接字段，
-- 而是通过中间表维护（一个商品可能先后归属不同品牌）。
-- 这样 products 到 brands 就有了一条真实的两跳路径，而不是靠函数硬凑。
CREATE TABLE product_brand_map (
    product_id  VARCHAR(32) NOT NULL,
    brand_id    INTEGER NOT NULL REFERENCES brands(brand_id),
    is_current  BOOLEAN DEFAULT true,
    mapped_at   DATE,
    PRIMARY KEY (product_id, brand_id)
);

-- ============ 行为域 ============

CREATE TABLE search_logs (
    log_id          BIGSERIAL PRIMARY KEY,
    member_id       BIGINT,
    keyword         VARCHAR(128),
    result_count    INTEGER,
    clicked_product_id VARCHAR(32),
    searched_at     TIMESTAMP
);

CREATE INDEX idx_members_level      ON members(level_id);
CREATE INDEX idx_members_status     ON members(status);
CREATE INDEX idx_members_lastlogin  ON members(last_login_date);
CREATE INDEX idx_tagmap_tag         ON user_tag_map(tag_id);
CREATE INDEX idx_coupon_usages_cpn  ON coupon_usages(coupon_id);
CREATE INDEX idx_coupon_usages_ord  ON coupon_usages(order_id);
CREATE INDEX idx_shipments_order    ON shipments(order_id);
CREATE INDEX idx_shipments_status   ON shipments(status);
CREATE INDEX idx_tracking_shipment  ON shipment_tracking(shipment_id);
CREATE INDEX idx_inventory_product  ON inventory(product_id);
CREATE INDEX idx_tickets_order      ON support_tickets(order_id);
CREATE INDEX idx_tickets_status     ON support_tickets(status);
CREATE INDEX idx_refunds_order      ON refunds(order_id);
CREATE INDEX idx_refunds_status     ON refunds(status);
CREATE INDEX idx_settlements_seller ON settlements(seller_id);
CREATE INDEX idx_price_hist_product ON product_price_history(product_id);
CREATE INDEX idx_brand_map_brand    ON product_brand_map(brand_id);
CREATE INDEX idx_search_member      ON search_logs(member_id);
