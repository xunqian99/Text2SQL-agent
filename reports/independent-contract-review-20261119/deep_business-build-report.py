"""Assemble only this independent reviewer's evidence and report; no model calls."""
import hashlib
import json
import re
import subprocess
from collections import Counter
from pathlib import Path

ROOT = Path(r"C:\Users\xunqian\Desktop\agent")
OUT = ROOT / "reports/independent-contract-review-20261119"
input_doc = json.loads((OUT / "deep_business-input.json").read_text(encoding="utf-8-sig"))
evidence = {}
for stem in ("checks", "topk", "followup"):
    sql_text = (OUT / f"deep_business-{stem}.sql").read_text(encoding="utf-8-sig")
    statements = [s.strip() + ";" for s in sql_text.split(";") if "jsonb_build_object('id'," in s]
    rows = [json.loads(line) for line in (OUT / f"deep_business-{stem}-results.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    assert len(statements) == len(rows), (stem, len(statements), len(rows))
    for statement, row in zip(statements, rows):
        assert re.search(r"'id','" + re.escape(row["id"]) + "'", statement)
        evidence.setdefault(row["id"], []).append({
            "check": row["check"],
            "evidenceType": "synthetic_counterexample" if "synthetic" in row["check"] else "production_snapshot",
            "sql": statement,
            "result": row["result"],
        })

P = "src/main/java/com/text2sql/agent/generation/PromptTemplate.java"
M = "src/main/resources/schema/metrics.yml"
A = "src/main/resources/schema/ambiguities.yml"
G = "src/main/resources/schema/glossary.yml"
D = "src/main/java/com/text2sql/agent/clarification/AmbiguityDetector.java"
S = "src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java"

reviews = {}
def add(id, decision, reason, sources, resolution, *, secondary=None, sql=None, question=None, boundary=None):
    reviews[id] = {
        "id": id, "decision": decision, "reason": reason,
        "contractSource": sources,
        "checks": evidence[id], "proposedResolution": resolution,
        "secondaryIssues": secondary or [],
        "boundaryAssessment": boundary or "本题核查以当前只读快照为证据；没有把观测到的唯一性冒充数据库约束。",
    }
    if sql:
        reviews[id]["proposedGoldSql"] = sql.strip()
        reviews[id]["proposedGoldSqlStatus"] = "diagnostic_candidate_only_not_applied"
    if question:
        reviews[id]["proposedQuestion"] = question

add("T5-001", "CONTRACT_CONFLICT",
    "商品销售额已有明确不含运费提示，Gold按商品明细price汇总且会员通过customer_unique_id关联正确；会员customer_unique_id有UNIQUE，未发生明细放大。问题没有明确宏观区域归属哪一方，而当前大区规则要求先确认客户/卖家/仓库来源，Gold直接指定customers.customer_state。会员等级按名称是题干显式要求，不能把此处名称输出判错。当前112650条会员商品明细的区域/等级均匹配；549条取消/不可用商品明细被计入，题干没有有效订单要求，不能擅自再过滤。",
    [f"{A}:67 regional-subject及triggerPatterns", f"{D}:decisionsFor（证据组全部满足才免澄清）", f"{P}:78-85（事实过滤、名称维度）", "实际schema: members.customer_unique_id UNIQUE、members.level_id FK、regions.region_code PK"],
    "先确认地域主体。若基准本来意图客户登记州，应把该意图写进问题；若希望由产品自动选客户州，需另行明确修改产品默认，不能把Gold选择伪装成唯一题意。",
    question="按客户登记州所属宏观区域，各会员等级（按等级名称）的商品销售额分别是多少？按销售额从高到低排序。",
    secondary=[{"type":"AMBIGUOUS", "detail":"原题交易地域来源未确定；候选题干采用客户州只是待业务确认的一个选项，卖家州或仓库归属也需各自定义。"}],
    boundary="等级名称明确可合并名称组；会员唯一约束阻止按自然人连接产生会员重复。区域/等级缺失当前为0，不代表所有外部地域映射均有强制FK。没有增加有效订单过滤。")

add("T5-003", "COINCIDENTAL_EQUIVALENCE",
    "退款金额使用refund_items.refund_value，且订单明细按(order_id,order_item_id)完整键关联，粒度正确，不存在退款头金额重复累加。当前5356条退款明细中84条原始类目为SQL NULL，金额9667.78；按当前product_category_nulls_v1默认排除，这部分不是翻译丢失错误。其余原始类目均能匹配翻译，故Gold的INNER JOIN当前等价于保留未翻译名称的LEFT JOIN；但products类目到翻译表没有FK保证，不能宣称以后也等价。前15名边界金额13359.56，只有1组，无截断并列。",
    [f"{M}:477 product_category_nulls_v1", f"{P}:85（名称关联保留未匹配事实）", "实际schema: refund_items复合PK、order_items复合PK、translation原类目PK；products类目无翻译FK"],
    "保留当前商品原始类目NULL默认；把翻译连接改为LEFT JOIN并单独显式排除原始类目SQL NULL。未翻译的非NULL原类目保留为NULL英文名组。不要追加退款状态或订单状态过滤。",
    secondary=[{"type":"NULL_POLICY", "detail":"原始类目NULL与非NULL类目缺英文翻译必须分开；前者受当前排除口径管理，后者不能被翻译INNER JOIN删掉。"}],
    boundary="有多个退款明细时金额逐明细求和；同金额不同退款不得SUM(DISTINCT金额)。现库无非NULL类目缺翻译只是事实，不是完整性保证。")

add("T5-005", "CONTRACT_CONFLICT",
    "当前产品默认每个活动以campaign_id区分并输出标识，原题没有要求活动名称，而Gold仅按campaign_name分组和输出。campaign_name没有UNIQUE；当前同名活动为0只能证明本快照暂未合并。另Gold把coupon_usages直接连商品明细，会在同一活动同一订单有多条用券记录时重复销售额，COUNT DISTINCT订单只能修计数不能修金额。本快照11926个有效活动-订单恰好各1条用券，Gold与活动-订单去重销售额均1660953.90；这条金额等价不能推广。前10边界无并列。",
    [f"{P}:84（未明确名称默认标识）", f"{P}:92-98（实体完整主键、金额事实粒度）", "实际schema: coupon_usages仅usage_id PK，无(campaign,order)唯一；campaigns仅campaign_id PK"],
    "先把结果维度契约写清：按现有产品默认输出campaign_id；若业务要名称则显式改题，并仍按活动主键隔离同名活动。销售额必须先去重活动-订单，再关联一行一订单商品金额；各指标保留有效订单全集。候选SQL包含全部已定义活动，空活动计数0、金额保持NULL。",
    sql="""WITH campaign_orders AS (
 SELECT DISTINCT cp.campaign_id, cu.order_id
 FROM coupon_usages cu JOIN coupons cp ON cp.coupon_id=cu.coupon_id
 JOIN orders o ON o.order_id=cu.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
), item_totals AS (
 SELECT order_id,SUM(price) AS sales FROM order_items GROUP BY order_id
)
SELECT ca.campaign_id,COUNT(co.order_id) AS used_orders,ROUND(SUM(it.sales),2) AS gmv
FROM campaigns ca LEFT JOIN campaign_orders co ON co.campaign_id=ca.campaign_id
LEFT JOIN item_totals it ON it.order_id=co.order_id
GROUP BY ca.campaign_id ORDER BY gmv DESC NULLS LAST LIMIT 10;""",
    question="按活动编号统计每个优惠券活动带来的有效订单数和商品销售额，按商品销售额降序取前10；没有有效用券订单的活动也保留。",
    secondary=[{"type":"COINCIDENTAL_EQUIVALENCE", "detail":"现库无同活动多次用券，也无同名活动；Gold的活动粒度与金额未被当前数据揭穿。"}])

add("T5-007", "CONTRACT_CONFLICT",
    "统计对象是有点击的搜索记录，会员及会员等级不是本题指标或维度，Gold多加两个INNER JOIN会额外要求点击者是可匹配等级的会员。当前8651条点击记录都匹配会员/等级，故这部分过滤暂未造成差异；但member_id可空且search_logs没有这条完整性保证。类目原始NULL有162条，当前默认可排除；另有5条非NULL原类目没有英文翻译，被Gold删掉，违反名称查找不能缩小事实的当前契约。Gold纳入8484条；保留缺翻译后为8489条。因缺翻译仅5条、Top20边界77次，当前所选Top20成员不变，不能据此说全链路口径正确。",
    [f"{P}:78-85（不得无据加过滤；缺名称保留）", f"{M}:477（仅排除原始SQL NULL）", "实际schema: search_logs.log_id PK、member_id nullable；翻译原类目唯一但不保证覆盖所有products类目"],
    "删掉无题意依据的会员/等级连接；保留原始类目NULL默认过滤；翻译用LEFT JOIN保留非NULL原类目缺译的点击。不能为了凑深度增加会员连接。",
    sql="""SELECT sl.keyword,t.product_category_name_english,COUNT(*) AS clicks
FROM search_logs sl JOIN products p ON p.product_id=sl.clicked_product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE sl.clicked_product_id IS NOT NULL AND p.product_category_name IS NOT NULL
GROUP BY sl.keyword,t.product_category_name_english ORDER BY clicks DESC LIMIT 20;""",
    secondary=[{"type":"COINCIDENTAL_EQUIVALENCE", "detail":"当前会员连接恰好不丢记录；缺翻译组又低于Top20阈值，因此最终Top20相同不能证明参考SQL范围正确。"}],
    boundary="Top20边界有4组同为77次，但恰好16组更高，4组全被包含，并未从并列组中任取一部分。未知产品的原类目归属仍需独立业务定义，本快照缺商品0条。")

add("T5-009", "AMBIGUOUS",
    "题干没有规定Top20遇到并列如何处理。当前Gold共282组，第20名计数5，19组更高，另19组同为5；LIMIT20必须任取这19组中的1组，无唯一可审核结果。另1177个低库存仓库×商品行有359个没有当前品牌，Gold的INNER JOIN全部删掉；若保留未知品牌，其5个仓库组分别有66/70/77/67/79个，全部进入Top20。这是实际覆盖差异，但题干尚未说明未知品牌是否保留，也未明确历史品牌或当前品牌。Gold默认名称输出还与当前标识列默认不一致。不能为了贴Gold暗定这些选项。",
    ["原题：库存低于安全库存、按仓库/区域/品牌、前20；没有并列、未知品牌或历史品牌口径", f"{P}:84-85（标识默认、未知名称覆盖）", f"{G}:346（历史商品品牌关系及is_current）", "实际schema: inventory PK(warehouse_id,product_id)；product_brand_map PK(product_id,brand_id)，无单一当前品牌唯一约束"],
    "先确认三项：按仓库×商品行数还是库存件数；当前品牌且缺失是否归入未知组；并列全保留还是按仓库/州/品牌标识确定顺序严格20行。确认后再冻结SQL与门槛。当前数据无多当前品牌，不等于DDL禁止；若未来一商品多当前品牌，还需定义多标签归属还是报数据质量问题。",
    question="库存低于安全库存时，按仓库编号、仓库州代码及当前品牌编号统计仓库×商品行数；缺当前品牌是否归入未知组？第20名并列时，是全部保留，还是按这三个编号升序取严格20行？",
    secondary=[{"type":"CONTRACT_CONFLICT", "detail":"原题未明确名称，Gold使用warehouse_name/region_name/brand_name；名称均无业务实体唯一性保证。"}, {"type":"FACT_COVERAGE_UNRESOLVED", "detail":"359行缺当前品牌已被Gold删除；未知组会实际改变Top20，需业务选择，不能当作无影响的NULL细节。"}],
    boundary="quantity或safety_stock为NULL时比较不成立，是否算低库存不能推断。当前没有多当前品牌记录，但schema允许；COUNT(*)只在所选品牌归属明确且每品牌每商品唯一时才表示组内商品行数。")

add("T5-010", "REFERENCE_DEFECT",
    "佣金合计的事实全集是卖家结算明细，不应被英文类目展示的可用性缩小。Gold将商品/翻译全做INNER JOIN，38312条结算明细总佣金535788.39被截成527124.40，少8663.99：699条原始类目NULL少8092.23，20条非NULL缺翻译少571.76。类目计数可以忽略没有英文名称的项目，不能连带删佣金。该问题已进入交付结果：Gold前20中1个卖家佣金少261.92，纠正后另1个卖家进入Top20。订单明细复合键连接本身正确，无需SUM(DISTINCT金额)。",
    ["原题：每个卖家结算明细覆盖的英文类目数及佣金合计，是不同指标全集", f"{P}:85,94（名称不能缩小事实、每指标独立全集）", f"{M}:477（原始类目NULL默认不等于删独立佣金事实）", "实际schema: settlement_items PK(settlement_id,order_id,order_item_id)，其佣金是原事实金额"],
    "在结算明细原粒度累计佣金，仅将分类查找改为LEFT JOIN；COUNT DISTINCT英文类目自动忽略NULL，不让其过滤佣金。候选SQL保持卖家标识、类目数、佣金三列及佣金Top20。",
    sql="""SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id ORDER BY commission DESC LIMIT 20;""",
    boundary="类目数忽略NULL英文名；佣金仍涵盖无类目/无翻译项目。各右表查找键在当前DDL中唯一，LEFT JOIN不会放大结算明细；如果未来维表键不唯一，应修维表而非SUM(DISTINCT佣金)。")

add("T5-012", "CONTRACT_CONFLICT",
    "Gold的会员分母与价格均值链路基本正确：从标签会员出发LEFT JOIN订单/商品，保留没有商品价格的会员，AVG只统计存在的商品明细且不把缺失补0。六类标签各有8至659名无商品明细会员，改INNER JOIN会真实少计。问题没要求标签名称，当前默认应输出tag_id并按完整标签主键分组；Gold只输出和分组tag_name，tag_name没有UNIQUE。当前六个名称各唯一只能解释现库未出现标签合并，不能盖过输出契约冲突。",
    [f"{P}:84,92,94（标识、实体主键、会员独立全集）", "实际schema: user_tag_map PK(member_id,tag_id)，user_tags仅tag_id PK；members.customer_unique_id UNIQUE"],
    "明确维度契约：若遵循当前默认，输出tag_id并从user_tags保留全部标签；若要标签名称，显式改题并仍按tag_id区分实体。保留无订单会员及NULL均价，商品均价按明细等权，不能先算每会员均价后等权平均。",
    sql="""SELECT ut.tag_id,COUNT(DISTINCT m.member_id) AS members,ROUND(AVG(oi.price),2) AS avg_item_price
FROM user_tags ut LEFT JOIN user_tag_map utm ON utm.tag_id=ut.tag_id
LEFT JOIN members m ON m.member_id=utm.member_id
LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id
LEFT JOIN orders o ON o.customer_id=c.customer_id
LEFT JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY ut.tag_id ORDER BY members DESC;""",
    secondary=[{"type":"COINCIDENTAL_EQUIVALENCE", "detail":"当前无同名标签，Gold的名称分组恰好未合并实体；这不是名称唯一约束。"}])

add("T5-014", "COINCIDENTAL_EQUIVALENCE",
    "题干明确最近一次会员快照，Gold却读取所有member_snapshot，未取MAX(snapshot_date)或按会员排序取最新。当前38471条快照恰好一会员一条，多个快照会员数0，所以Gold与正确latest集合的三组结果同为2110/415/7。这个等价由数据巧合支撑，snapshot_id主键并不保证一会员一条。只读VALUES反例中，同一会员旧快照2、最新快照3、当前等级3，Gold误报1人，latest为0人。不能将本题算成已验证了最新快照能力。",
    ["原题：最近一次会员快照", f"{G}:261（会员每日历史快照）", "实际schema: member_snapshot只有snapshot_id PK；member_id/snapshot_date/level_id均可NULL，无(member_id,date)唯一约束"],
    "先按member_id选最新snapshot_date，再比较等级。必须定义同日多快照的裁决依据：snapshot_id仅是行标识，不能未经确认就当业务版本。日期NULL、等级NULL与无快照会员是否算不一致也需明确；当前快照日期无NULL且无同日并列。",
    question="按每个会员最大snapshot_date对应快照与当前等级比较；同日多条快照如何裁决？等级NULL或没有快照是否单列未知，而不当作已确认不一致？",
    boundary="原Gold的<>会跳过任一NULL等级；若业务把未知与已知视作差异须显式采用IS DISTINCT FROM，不能自行改。COUNT DISTINCT只能去重同组会员，不能实现最新记录筛选。")

add("T5-016", "COINCIDENTAL_EQUIVALENCE",
    "按类目与原因统计时，COUNT DISTINCT refund_id正确防止一张退款单同类多明细重复计笔数，SUM refund_value仍逐退款明细累加；跨类目的一张退款可进入多个组，不能再把各类目退款数相加当平台去重退款数。实际5356条明细对应4785笔退款，明细order_id与退款头不一致为0。84条原始类目NULL受当前默认排除；其余类目当前全部可翻译，INNER翻译连接才碰巧等于保留缺译组的LEFT连接。前20边界9490.91仅1组。",
    [f"{M}:477（类目NULL及跨类目订单/事实口径）", f"{P}:85,95-100（名称覆盖、完整事实身份）", "实际schema: refunds.refund_id PK，refund_items完整复合PK，翻译表不保证覆盖products的非NULL类目"],
    "保持退款ID去重计数与明细金额，翻译LEFT JOIN并显式仅排除原始类目SQL NULL。缺译非NULL类目保留NULL英文名组；不追加已退款状态、有效订单或其他隐含过滤。",
    boundary="多个原因由退款头reason_code决定，不改用明细order_id上的其他退款。当前头明细订单一致为0异常但DDL无关联约束，未来需数据校验；不能靠额外无题意过滤掩盖。")

add("T5-018", "KEEP",
    "Gold沿shipping_addresses.customer_id直接关联该客户档案及订单，符合客户属性必须沿事实关联键读取的当前身份政策，没有绕customer_unique_id去匹配另一州的档案。is_default由题干明确，COUNT DISTINCT order_id保证同组重复默认地址不加权订单。当前99441条默认地址，所有订单都有默认地址，同客户多默认及冲突默认州均0；因此两个州维度与订单量的链路成立。Top20边界413单仅1组，没有并列截断。",
    ["原题：默认收货地址所在州与客户登记州组合", f"{M}:490 customer_identity_v2（沿事实关联客户记录取属性）", f"{G}:529 shipping_addresses", "实际schema: orders.customer_id FK、customers.customer_id PK"],
    "保持题目和Gold当前统计口径。若未来允许同客户多个不同默认州，必须先修默认地址业务约束或定义快照选择，COUNT DISTINCT只能防同组重复，不能决定唯一地域归属。",
    boundary="无默认地址不属于可构成默认地址州组合的集合，本题未要求未知组；有默认但state NULL时Gold保留NULL州组。没有题意依据添加有效订单过滤。")

add("T6-002", "REFERENCE_DEFECT",
    "Gold把有效订单数、下单人数与客单价统一绑在INNER JOIN order_items后的集合，悄悄排除了无商品明细的有效订单。实际有效订单98207单、94990人，Gold只数98199单、94983人，少8单7人；同一商品GMV分子除以错分母后客单价160.24，完整有效订单分母应为160.23。问题明确数有效订单，而不是有商品明细的有效订单；注册客单价分母也写有效订单数。身份按customer_unique_id去重、GMV含运费这两点正确，不能用它们掩盖全集错位。",
    ["原题明确排除canceled/unavailable，无有明细限制", f"{M}:39 valid_order、60 unique_buyers、129 aov", f"{P}:94-100（每指标独立全集、计数身份）"],
    "先保留全部有效订单与自然人身份；商品金额按order_id预聚合后LEFT JOIN。订单数与人数从完整订单集算，缺明细不造0价格，金额聚合保留SQL空值语义。",
    sql="""WITH valid_orders AS (
 SELECT o.order_id,c.customer_unique_id FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
), item_totals AS (
 SELECT order_id,SUM(price+freight_value) AS gmv FROM order_items GROUP BY order_id
)
SELECT COUNT(*) AS valid_orders,COUNT(DISTINCT vo.customer_unique_id) AS buyers,
 ROUND(SUM(it.gmv)/NULLIF(COUNT(*),0),2) AS aov
FROM valid_orders vo LEFT JOIN item_totals it ON it.order_id=vo.order_id;""",
    boundary="customer_unique_id非NULL且客户FK完整，故COUNT DISTINCT人数不额外丢未知身份；若业务未来允许未知身份，订单总数仍保留。空订单集客单价NULL，计数0。")

add("T6-004", "KEEP",
    "Gold先在完整有效订单历史按customer_unique_id取首单，再选2017上半年cohort，没有先截报告期而误认新客；下半年回访先DISTINCT自然人，使多笔回访不放大分子。分母LEFT保留未回访用户。按当前retention_rate明确的默认输出规则，题干给出下半年回访窗口且未要求活动月交叉明细，按首单月输出留存率符合当前契约。实际六个cohort用户数为752/1690/2571/2325/3541/3102，回访人数9/15/35/38/64/63。百分比100倍两位与注册单位一致。",
    [f"{M}:388 retention_rate（同期群、请求回访窗口、去重、输出cohort维度）", "实际schema: customers.customer_unique_id非NULL"],
    "保持当前cohort口径和Gold。若业务真正要2017下半年每个活动月分别留存，应另改题明确activity_month维度，不能把它当原Gold唯一等价表达。",
    boundary="每个输出cohort至少一人，Gold组内分母非0；无cohort月份不会输出行，要求空月份也展示时需另定日历范围。客户身份非NULL；没有将NULL身份当一个人。")

add("T6-006", "KEEP",
    "Gold用实际签收时间非NULL作为已送达全集，再以实际不晚于预计计准时，与注册on_time_rate一致。当前96476条实际签收订单中88649条准时，91.89%；其中6条订单状态不是delivered，证明若替换成状态筛选会真实改分母。预计时间缺失当前0；未来缺失仍应留分母不计准时，Gold现有FILTER具有该语义。没有商品明细加权订单，也没有擅自排除取消/不可用。",
    [f"{M}:156 on_time_rate（签收事实、未知预计分母、订单粒度、PERCENT两位）"],
    "保留当前定义。参考SQL可以加NULLIF(COUNT(*),0)完成空集防除零，但本固定快照的分子分母与结果没有具体逻辑偏差。",
    secondary=[{"type":"EMPTY_SET_BOUNDARY", "detail":"Gold缺NULLIF，在已签收集合为空时会除零，当前产品契约要求NULL；此处KEEP限于题目固定快照，不能宣称Gold可直接用于任意空库。"}],
    boundary="NULL预计时间比较为UNKNOWN，不计分子但在COUNT(*)分母；这与过滤掉未知预计订单不同。无符号/单位冲突。")

add("T6-008", "KEEP",
    "Gold将有效订单中出现的不同product_id与独立全量products分母分别聚合，正确保留未动销商品作为分母，没有用库存或销售连接后的商品数。当前全部商品32951、有效动销32729，222个未动销商品未丢，结果99.33%。有效订单过滤来自sell_through_rate，百分比两位与注册单位一致；不存在多明细把商品计多的问题。",
    [f"{M}:206 sell_through_rate（有效销量/全部商品、独立分母、PERCENT两位）", "实际schema: order_items.product_id NOT NULL且FK引用products"],
    "保持Gold与题意。可把分母写成NULLIF(allp.n,0)与注册空集策略对齐；不要为追求100%把分母缩成有销量商品。",
    secondary=[{"type":"EMPTY_SET_BOUNDARY", "detail":"Gold在products为空时除零，产品契约期望NULL；当前固定快照32951条商品使该边界不触发。"}],
    boundary="商品主键及商品外键保证分子是真实商品子集；空分子COUNT为0，非空商品分母下结果0%，不是NULL。")

add("T6-011", "CONTRACT_CONFLICT",
    "含运费GMV、有效订单、运费分子和百分比两位均与freight_ratio一致，但原题宏观区域未指定地域主体。当前ambiguities的交易大区规则要求确认客户/卖家/仓库，Gold径取客户登记州；freight_ratio推荐示例同时写客户州，与澄清规则形成当前配置内的优先行为冲突，不能因为示例存在就说题意已明确。选择来源会实际改结果：42190条有效商品明细、6842445.94的GMV在客户大区与卖家大区归属不同。",
    [f"{A}:67-96（大区主体证据与交易触发）", f"{D}:decisionsFor", f"{M}:280 freight_ratio（含运费有效订单指标及客户州示例）", f"{G}:542 regions（客户/卖家地域路径）"],
    "由业务确认按哪一方地域后再冻结题目及Gold；保留freight_ratio分子分母及百分比单位。若确认原Gold意图客户登记州，可采用候选题干；卖家州也可合法定义，不能为了符合当前示例强制客户来源。",
    question="按客户登记州所属宏观区域，运费占含运费GMV的百分比是多少？仅统计有效订单。",
    secondary=[{"type":"CURRENT_CONFIG_INCONSISTENCY", "detail":"指标推荐SQL隐含客户州，而澄清规则要求题干提供地域主体；运行时若澄清启用，题目会先被反问。未执行产品请求或模型调用，不声称实测了运行时结果。"}],
    boundary="当前客户/卖家区域映射缺失均0，只代表快照覆盖。GMV分母0必须NULLIF，Gold未保护空/零集合；不把缺地域事实自动当客户区域。")

add("T6-013", "REFERENCE_DEFECT",
    "Gold直接连接用券记录与商品明细，把每笔订单的discount_amount重复到每个商品上。11926个有效用券订单中1175个有多商品；正确优惠417833.13被Gold算成473711.60，多55878.47，折扣率21.64%被算成24.54%。当前每订单仅1次用券，所以GMV仍碰巧是1930583.35；coupon_usages没有order_id唯一约束，未来多券还会反向重复GMV。COUNT DISTINCT或SUM DISTINCT金额都不能修复两个事实表的交叉放大。",
    [f"{M}:325 discount_rate（先按订单分别汇总cu和oi、同一订单集合、PERCENT两位）", f"{P}:93（先原事实聚合，禁SUM DISTINCT金额）", "实际schema: coupon_usages usage_id PK；order_items复合PK"],
    "分别预聚合订单级优惠总额和订单级含运费商品金额，再在有用券且有商品明细的有效订单交集一对一连接。该共同全集是当前discount_rate明确契约，不能扩成无券订单或无明细订单。",
    sql="""WITH coupon_totals AS (
 SELECT order_id,SUM(discount_amount) AS discount_amount FROM coupon_usages GROUP BY order_id
), order_totals AS (
 SELECT order_id,SUM(price+freight_value) AS order_gmv FROM order_items GROUP BY order_id
)
SELECT ROUND(SUM(ot.order_gmv),2) AS gmv,ROUND(SUM(ct.discount_amount),2) AS discount,
 ROUND(100.0*SUM(ct.discount_amount)/NULLIF(SUM(ot.order_gmv),0),2) AS discount_pct
FROM coupon_totals ct JOIN orders o ON o.order_id=ct.order_id
JOIN order_totals ot ON ot.order_id=ct.order_id
WHERE o.order_status NOT IN ('canceled','unavailable');""",
    boundary="两端均先按order_id归并，完全消除cu行数×商品行数的乘法；等额不同优惠仍各自保留。总GMV0时率NULL；SUM折扣为NULL不补0制造奖励或优惠。")

add("T6-015", "CONTRACT_CONFLICT",
    "Gold全工单分母、未解决不计分子、小时EPOCH换算和百分比两位符合sla_rate；但原题没要求类目名称，当前维度默认应使用support_tickets.category_id，Gold输出category_name并仅按名称分组。名称没有UNIQUE；当前6个名称唯一、分类NULL工单0，只说明暂未发生合并/丢失。另实际2556条resolved_at早于created_at的工单全部被当前公式计为SLA成功，这不是Gold独有错误：产品同一表达式也如此，不能悄悄给Gold加非负时长过滤然后宣布纠正。",
    [f"{P}:84,92（名称显式、实体身份分组）", f"{M}:312 sla_rate（全部工单分母、已解决且时长<=SLA）", "实际schema: ticket_categories只有category_id PK，category_name非唯一；support_tickets.category_id nullable FK"],
    "先明确输出维度，按当前默认候选使用category_id并LEFT保留分类未知工单。负时长另列数据质量决策：修源数据、标记未知、计失败或排除各自改变指标，须由业务确认并版本化，不能在本次审计偷偷改。候选SQL忠实保持当前SLA数学定义。",
    sql="""SELECT st.category_id,ROUND(100.0*COUNT(*) FILTER(
 WHERE st.resolved_at IS NOT NULL
 AND EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600.0<=tc.sla_hours
)/NULLIF(COUNT(*),0),2) AS sla_pct
FROM support_tickets st LEFT JOIN ticket_categories tc ON tc.category_id=st.category_id
GROUP BY st.category_id ORDER BY sla_pct;""",
    secondary=[{"type":"DATA_QUALITY_UNRESOLVED", "detail":"2556个负时长全部被算达成；Gold和产品都受影响，正确业务处置尚未给定。"}],
    boundary="resolved_at NULL不计成功但留分母；created_at或sla_hours NULL也无法确认成功。未知类目保留NULL组，不能借查SLA删除工单。")

add("T6-017", "KEEP",
    "Gold按推荐记录计算已转化/全部推荐，奖励总额除以已转化推荐数，符合当前conversion_rate与reward_per_converted，而不是自然人去重或每全部推荐均摊。当前4793条推荐、1623条转化，33.86%；奖励总额32481.59，按约定得到20.01。必须揭露该约定的含义：1068条未转化推荐也有正奖励，合计21582.52；若只统计成功推荐上的奖励10899.07，人均是6.72，不能把两者说成等价。这里Gold没有偏离已注册默认，是否接受这个业务定义应另行确认。",
    [f"{M}:368 conversion_rate、377 reward_per_converted（奖励总额/转化推荐数）", "实际schema: referrals以referral_id为PK，未强制邀请人/被邀请人唯一"],
    "保留当前Gold；为产品解释清楚人均实际是每次成功推荐分摊全部奖励。若业务要每个转化会员仅分摊其成功事件奖励，必须另设清楚的新指标，不能据口语人均直接改已有基准。",
    secondary=[{"type":"BUSINESS_LABEL_CAVEAT", "detail":"指标名人均不意味着COUNT DISTINCT会员；当前合同明确按转化推荐事件。当前已转化invitee去重数恰好1623也不能当普遍唯一保证。"}, {"type":"EMPTY_SET_BOUNDARY", "detail":"Gold缺NULLIF；没有推荐或没有转化时除零，产品注册式要求NULL。当前集合两分母均正。"}],
    boundary="reward_amount NULL由SUM忽略；未转化奖励当前有实值且在总额内，不擅自假设它们恒0，也不自行排除。")

add("T6-019", "KEEP",
    "Gold严格使用下单到客户签收的时间差，EPOCH/86400.0保留小数天，AVG后两位，符合delivery_days而非承运商发货到送达口径。沿订单customer_id取登记州，没有跨自然人其他客户档案取州。实际96476条签收订单无下单时间缺失、无负时长、州NULL为0；最大209.6286天，证明应保留完整跨月时长。27州中Top10边界20.43仅1州。未擅自套有效订单过滤，符合本指标只要求实际送达的定义。",
    [f"{M}:427 delivery_days", f"{M}:490 customer_identity_v2（事实所属客户档案）", "原题明确下单到签收，排除shipment_delivery_days解释"],
    "保持Gold及已注册时间口径。正式长期查询可显式::numeric以免不同时间函数返回类型导致ROUND重载问题；本次只读SQL已在实际PostgreSQL执行同一EPOCH/ROUND计算成功。",
    boundary="签收NULL不参与；下单时间NULL时AVG忽略时长，未来若出现应单列质量指标而不是把缺时间记0天。州归属是订单对应客户记录的州，不猜最新州。")

add("T6-020", "COINCIDENTAL_EQUIVALENCE",
    "平台总佣金/总毛额与卖家分别聚合后MIN/AVG/MAX的结构正确，平均卖家率是各卖家等权、整体率是按毛额加权，DECIMAL_RATIO四位也符合注册单位。当前2176卖家全部毛额>0，平台/平均0.1000、最小0.0997、最大0.1004，故Gold CASE ELSE0当前没有触发。但Gold对零、负或NULL毛额统统给0，而当前契约是分母0返回NULL；负毛额也不应未经业务定义直接变0。只读零分母反例中Gold卖家均率0.0500，NULLIF约定0.1000，表明当前等价仅由毛额全正维持。",
    [f"{M}:357 commission_rate（总额率、卖家分布、DECIMAL_RATIO四位）", f"{P}:105（零分母NULL而非0）"],
    "改为SUM(commission_amount)/NULLIF(SUM(gross_amount),0)，零毛额卖家率保持NULL，不以假0压低分布。总平台分母同样NULLIF。负毛额的商业意义应明确，若作为退款净额也要保留符号而非自动置0。",
    boundary="分布只覆盖有结算明细的卖家；无结算卖家的率未定义，题干没有要求把他们用0塞入分布。现库2176卖家全正不是表约束。")

add("T6-022", "REFERENCE_DEFECT",
    "问题同时要求2017与2018各月GMV，再算2018同月同比；Gold却以2018为驱动INNER JOIN2017，只输出两年交集1至9月，删除2017年10/11/12月764756.03、1172191.68、861526.77，合计2798474.48。题目没有仅两年都有数据的月份限定，因此不能为了同比可算就缩小原本两年金额展示范围。2018缺这些月份是数据缺失，不能补0制造-100%同比。含运费、有效订单、百分比两位本身正确。",
    ["原题：2017年和2018年各月GMV分别是多少，并计算同比", f"{M}:456 yoy_growth（交集仅在用户明确要求；缺期NULL、不造0、保留请求覆盖）", f"{P}:systemPrompt第17号规则（输出契约不得改变时间覆盖）"],
    "以1至12月日历为驱动分别LEFT JOIN两年金额；缺失金额及同比返回NULL。候选保留mo、gmv_2017、gmv_2018、yoy_pct。若业务真的只要共同月份，应明确另改题，不能将内连接当自动默认。",
    sql="""WITH monthly AS (
 SELECT EXTRACT(YEAR FROM o.order_purchase_timestamp)::int AS yr,
 EXTRACT(MONTH FROM o.order_purchase_timestamp)::int AS mo,SUM(oi.price+oi.freight_value) AS gmv
 FROM orders o JOIN order_items oi ON oi.order_id=o.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
 AND o.order_purchase_timestamp>=DATE '2017-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01'
 GROUP BY 1,2
), months AS (SELECT generate_series(1,12) AS mo)
SELECT m.mo,ROUND(m17.gmv,2) AS gmv_2017,ROUND(m18.gmv,2) AS gmv_2018,
 ROUND(100.0*(m18.gmv-m17.gmv)/NULLIF(m17.gmv,0),2) AS yoy_pct
FROM months m LEFT JOIN monthly m17 ON m17.mo=m.mo AND m17.yr=2017
LEFT JOIN monthly m18 ON m18.mo=m.mo AND m18.yr=2018 ORDER BY m.mo;""",
    boundary="2017为0或NULL时同比NULL；2018缺失不补0，2017已知仍展示。日期半开边界保留2018最后一刻。候选结果对照另存，明确9行交集与12行完整月份差别。")

add("T6-024", "COINCIDENTAL_EQUIVALENCE",
    "当前低分率度量以评价记录为单位，原Gold将退款与评价直接按order_id连接；order_reviews完整身份是(review_id,order_id)，同订单多评价本应各保留。当前每订单只有一笔退款，按原因归属后平均及低分率各组正确：CUST4.04/15.97%、LATE4.07/14.96%、OTHER4.10/14.89%、QUAL4.08/14.60%。但refunds只有refund_id主键，没有order_id或(order_id,reason_code)唯一；同一订单同一原因有两笔退款时，Gold会把同一评价重复加权。只读反例把两条真实评价1分/5分算成均分2.33、低分66.67%，去重原因-订单后应3.00/50.00%。当前结果等价只由退款一单一条的偶然数据维持。",
    [f"{M}:255 low_score_rate（评价记录分母；退款集合不叠订单状态）", f"{P}:95-100（完整记录身份，连接不能改变计数对象）", f"{G}:182（一行一评价）", "实际schema: order_reviews PK(review_id,order_id)，refunds仅refund_id PK"],
    "先取DISTINCT(reason_code,order_id)归属集合，再连接所有评价记录；同评价在不同退款原因组可各出现一次，同原因组不得因多退款重复。保留多评价本身，不随意取最新评价，不增有效订单或已完成退款过滤。",
    boundary="AVG忽略NULL评分；低分率COUNT(*)分母包含NULL评分评价，符合当前registered metric，未知不计低分。实际NULL评分0。订单/客户连接受强制FK/PK保障当前不放大，无需额外加入它们完成本题。")

assert len(reviews) == 22
assert set(reviews) == {i["id"] for i in input_doc["items"]}
ordered = []
for raw in input_doc["items"]:
    review = reviews[raw["id"]]
    review["question"] = raw["question"]
    review["originalGoldSql"] = raw["gold_sql"]
    ordered.append(review)

# Execute all frozen diagnostic candidates in one read-only batch. These are not
# benchmark replacements and are never written to data/eval or a production file.
candidate_statements = []
for item in ordered:
    if "proposedGoldSql" in item:
        inner = item["proposedGoldSql"].strip().rstrip(";")
        candidate_statements.append(
            f"SELECT jsonb_build_object('id','{item['id']}','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM ({inner}) q;"
        )
candidate_sql = "BEGIN READ ONLY;\nSET LOCAL statement_timeout = '8s';\n" + "\n\n".join(candidate_statements) + "\nROLLBACK;\n"
candidate_path = OUT / "deep_business-candidate-results.sql"
candidate_result_path = OUT / "deep_business-candidate-results.jsonl"
if candidate_path.exists() and candidate_result_path.exists() and candidate_path.read_text(encoding="utf-8") == candidate_sql:
    candidate_output = candidate_result_path.read_text(encoding="utf-8-sig")
else:
    candidate_path.write_text(candidate_sql, encoding="utf-8")
    run = subprocess.run(["docker", "exec", "-i", "text2sql-postgres", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "text2sql_ro", "-d", "olist"], input=candidate_sql, text=True, encoding="utf-8", capture_output=True, check=True)
    candidate_output = run.stdout
    candidate_result_path.write_text(candidate_output, encoding="utf-8")
candidate_rows = [json.loads(line) for line in candidate_output.splitlines() if line.strip()]
assert len(candidate_rows) == len(candidate_statements)
for row in candidate_rows:
    reviews[row["id"]]["candidateResult"] = row["result"]
    reviews[row["id"]]["candidateResultSqlFile"] = "deep_business-candidate-results.sql"

sources = [P, M, A, G, D, S]
source_hashes = {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in sources}
counts = dict(Counter(item["decision"] for item in ordered))
report = {
    "reviewer": "/root/review_deep_business",
    "provenance": {
        "freshContext": True,
        "participatedInAuthoringOrTuning": False,
        "readPriorAudit": False,
        "externalModelCalls": 0,
        "scope": "Only original formal 22 T5/T6 questions in deep_business-input.json; product defaults read only from the six allowed source files and actual public schema.",
        "dbRole": "text2sql_ro",
        "database": "olist",
        "dbAccess": "docker exec psql -X -qAt -v ON_ERROR_STOP=1; every script BEGIN READ ONLY, statement_timeout 8s, ROLLBACK",
        "inputSha256": hashlib.sha256((OUT / "deep_business-input.json").read_bytes()).hexdigest(),
        "productSourceSha256": source_hashes,
        "excludedSources": ["historical model outputs", "other reviewers' conclusions", "docs/review", "docs/EVALUATION_CONTRACT_ERRATA*", "ROADMAP/IMPROVEMENT_PLAN historical states", "other project audit reports"],
        "runtimeLimitation": "No production request, model call, or paid evaluation was executed; clarification behavior is source analysis, not claimed runtime measurement.",
    },
    "decisionPriority": "REFERENCE_DEFECT: clear question/measure defect with actual result change; CONTRACT_CONFLICT: frozen current product rule disagrees with Gold; AMBIGUOUS: material unresolved business/tie choice; COINCIDENTAL_EQUIVALENCE: actual snapshot equality depends on unsupported shape; KEEP: no concrete fixed-snapshot answer defect under current registered business definition, with separately stated edge limits.",
    "summary": {"reviewed": len(ordered), "counts": counts, "candidateSqlCount": len(candidate_rows), "changedProductionOrBenchmark": False, "gitCommitPerformed": False},
    "sharedChecks": [{
        "sql": (OUT / "deep_business-integrity.sql").read_text(encoding="utf-8"),
        "result": json.loads((OUT / "deep_business-integrity-results.jsonl").read_text(encoding="utf-8-sig"))["result"],
        "interpretation": "未声明外键的关联当前孤儿数均为0；这只是快照核查，不替代DDL完整性保证。"
    }],
    "items": ordered,
}
(OUT / "deep_business-review.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

rows = "\n".join(f"| {item['id']} | {item['decision']} | {item['reason'].split('。')[0]}。 |" for item in ordered)
markdown = f"""# 独立契约复核：原正式T5/T6题

复核人：`/root/review_deep_business`。新上下文、未参与题目/Gold编写或Prompt/规则调参、未读既有审计与其他审阅者结论，外部模型调用 **0次**。只读产品六个指定源文件及实际schema；只读SQL全部使用`text2sql_ro`、`BEGIN READ ONLY`、8秒statement timeout。未改生产代码、原始评测/holdout、Gold、评分器或门槛，未提交Git。

完整逐题理由、契约来源、已执行SQL与实际结果在`deep_business-review.json`；全部22题均有独立数据库核查。当前统计：{json.dumps(counts, ensure_ascii=False)}。分类以主问题为准，其他缺陷列为secondaryIssues，不能把分类数直接当模型错误数。

## 已确定的参考答案缺陷（4题）

- **T5-010**：分类查找删佣金事实。总佣金535788.39被算成527124.40；缺8663.99，其中原始类目NULL 8092.23、非NULL缺英文翻译571.76。前20中一个佣金值少261.92，纠正后有一个新卖家进入前20。类目数与佣金的事实全集必须分开。
- **T6-002**：商品明细内连接删8个有效订单、7名买家；98207/94990被算成98199/94983，AOV应160.23而Gold160.24。
- **T6-013**：1175个多商品用券订单放大优惠。417833.13被算成473711.60，折扣率21.64%被算成24.54%；当前无一单多券，GMV1930583.35才暂未重复。
- **T6-022**：Gold只有1至9月。2017年10—12月764756.03、1172191.68、861526.77被删除，共2798474.48。完整月份候选输出12行，缺2018金额及同比保持NULL，不填0造-100%。具体逐月对照见JSON的T6-022 checks与candidateResult。

## 当前契约冲突与待定业务

T5-001、T6-011未明确宏观地域主体，当前规则要求澄清，Gold固定客户州。客户大区和卖家大区实际会改变42190条明细、6842445.94 GMV的归属；两题只给待确认候选题干，未强定地域答案。T5-005活动、T5-012标签、T6-015工单分类的名称输出与当前“未明说名称则输出标识”默认不同；名称均没有唯一约束。T5-007多加会员连接并删5条非NULL类目缺译点击，虽缺译组低于Top20阈值，仍与保留事实的当前契约冲突。

**T5-009不能冻结为唯一Top20**：19组高于5，另19组同为5，Gold只任取并列中的1组。另359个缺当前品牌库存行被删，保留未知品牌会有5组进入Top20。商品行数/库存件数、当前/历史品牌、未知品牌及并列裁决均需明确后再定SQL。没有凭当前数据无多当前品牌就宣称其受唯一约束。

## 现库巧合与边界

T5-014全部38471条快照恰好每会员1条，Gold未筛最近却与latest相同；只读合成反例已证明旧快照可造成误报。T5-003/016本快照非NULL退款类目均有翻译，不保证以后仍完整；84条原始类目NULL按当前默认排除，不能与缺翻译混为一谈。T6-024目前一订单一退款，直接关联评价才未重复加权，合成反例展示均分2.33/低分66.67%对正确3.00/50.00%。T6-020当前2176卖家毛额均正，零分母补0的错误尚未触发；合成例的平均率0.0500对0.1000。合成证据均明确标记，**不是生产实测记录**。

SLA另有**2556条负处理时长**，当前产品公式和Gold都会把它们计为成功。这是共用定义与数据质量的待决事项，没有偷加非负过滤。T6-017当前奖励约定会将未转化记录奖励21582.52也计入总奖励，得到20.01；若只算转化奖励则6.72。Gold符合现注册口径，业务名称是否应叫“人均”需要另审，不当作模型算错。

KEEP的6题为T5-018、T6-004/006/008/017/019；它们也逐项记录身份、独立分母、NULL、时间与TopK边界。几个Gold省略NULLIF，当前快照分母正不报错；这项结论不等于它们已满足任意空库的产品安全策略。

## 证据文件

- `deep_business-schema.sql` / `deep_business-schema-results.jsonl`：实际列、可空性、PK/FK/UNIQUE/索引。
- `deep_business-checks.sql` / `deep_business-checks-results.jsonl`：22题逐题生产快照核查。
- `deep_business-topk.sql` / `deep_business-topk-results.jsonl`：8道有LIMIT题的截断边界。
- `deep_business-followup.sql` / `deep_business-followup-results.jsonl`：实际影响及明确标记的只读合成反例。
- `deep_business-integrity.sql` / `deep_business-integrity-results.jsonl`：未声明外键的8类关联完整性，当前孤儿均0，仅代表当前快照。
- `deep_business-candidate-results.sql` / `deep_business-candidate-results.jsonl`：{len(candidate_rows)}道已冻结诊断候选SQL及实际执行结果，均未写回基准。
- `deep_business-build-report.py`：报告组装与候选只读查询脚本。只读取本审阅者证据及指定输入/源文件。

全部候选只是可复核材料。需要业务确认的地方保留问题；没有人工审核签字、没有模型成绩、没有付费评测，不能据此宣称新准确率。

| ID | 主判断 | 首要理由 |
|---|---|---|
{rows}
"""
(OUT / "deep_business-review.md").write_text(markdown, encoding="utf-8")
print(json.dumps({"reviewed": len(ordered), "counts": counts, "candidateSqlCount": len(candidate_rows), "candidateRowCounts": {row["id"]: len(row["result"]) for row in candidate_rows}}, ensure_ascii=False))
