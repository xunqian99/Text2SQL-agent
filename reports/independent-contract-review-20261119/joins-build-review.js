const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const root = process.cwd();
const dir = path.join(root, 'reports/independent-contract-review-20261119');
function read(file) {
  const b = fs.readFileSync(path.join(dir, file));
  return (b[0] === 255 && b[1] === 254 ? b.subarray(2).toString('utf16le') : b.toString('utf8')).replace(/^\uFEFF/, '');
}
const input = JSON.parse(read('joins-input.json'));
const sqlByCheck = {};
const resultByCheck = {};
for (const stem of ['joins-checks', 'joins-boundaries']) {
  for (const statement of read(stem + '.sql').split(';')) {
    const sql = statement.trim();
    const match = sql.match(/jsonb_build_object\('check','([^']+)'/);
    if (match) sqlByCheck[match[1]] = sql + ';';
  }
  for (const line of read(stem + '-results.jsonl').split(/\r?\n/).filter(Boolean)) {
    const record = JSON.parse(line);
    resultByCheck[record.check] = record.result;
  }
}
const source = {
  prompt: 'src/main/java/com/text2sql/agent/generation/PromptTemplate.java',
  reader: 'src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java',
  metrics: 'src/main/resources/schema/metrics.yml',
  ambiguities: 'src/main/resources/schema/ambiguities.yml',
  glossary: 'src/main/resources/schema/glossary.yml',
  detector: 'src/main/java/com/text2sql/agent/clarification/AmbiguityDetector.java',
  schema: 'reports/independent-contract-review-20261119/joins-schema-results.jsonl'
};
const emptyGoldError = JSON.parse(read('joins-empty-search-result.json'));
sqlByCheck[emptyGoldError.check] = read('joins-empty-search.sql').split(';').map(x=>x.trim()).find(x=>x.startsWith('SELECT'))+';';
resultByCheck[emptyGoldError.check] = emptyGoldError;
const notes = [
  {
    id:'T4-002', decision:'AMBIGUOUS',
    reason:'“商品销售额”已命中销售额澄清规则的明确口径提示，SUM(order_items.price)不含运费有依据；问题却没有说按买家州还是卖家州。Gold固定使用订单所关联客户档案的customer_state，连接orders主键和customers主键不会放大明细，金额粒度本身正确，但该地域选择不是题干唯一结论。112650条明细中71894条买卖双方州不同；SP按客户州为5202955.05，按卖家州为8753396.21。不能把这当作等价连接路径。题目未给时间和状态范围，不能自行加已送达/有效订单过滤。',
    contractSource:[source.ambiguities+' / 销售额 hints含“商品销售额”；大区澄清只覆盖大区词，不覆盖裸“州”',source.glossary+' / customer_state与seller_state均为地域来源，词典是结构映射而非地域业务默认',source.prompt+' / 规则7、10、16',source.schema+' / order_items组合主键(order_id,order_item_id)，orders与customers主键及FK'],
    checks:['T4-002-geography','T4-002-state-results'],
    proposedResolution:'先明确地域主体，再保留所选事实路径；不以Gold的既有选择倒推用户意图，不据此修改原正式得分。',
    proposedQuestion:'每个客户所在州的商品销售额是多少（不含运费）？或者明确改为每个卖家所在州。',
    proposedGoldSql:null,
    secondaryIssues:[{type:'CLARIFICATION_COVERAGE_GAP',detail:'现有大区规则不覆盖州；这里的歧义判断来自题干及实际双地域事实，不声称当前检测器会主动反问。'}]
  },
  {
    id:'T4-004', decision:'CONTRACT_CONFLICT',
    reason:'题干只有“销售额”，没有任何现行销售额澄清hint，按AmbiguityDetector的确定性判定应先询问含不含运费；Gold直接选择商品金额。另有独立参考缺陷：INNER JOIN翻译表把2个非NULL原始类目、24条明细、5514.48商品金额丢掉。类目默认只排原始SQL NULL，不授权排未翻译类目；英文名是展示属性，不能靠翻译存在性缩小事实全集。当前第一名health_beauty为1258681.34，保留未翻译类目后第一名恰好不变，现库只有一个第一名；这不能证明内连接方案泛化等价。',
    contractSource:[source.ambiguities+' / 销售额',source.detector+' / 无hint则needsClarification',source.metrics+' / sales_without_freight、gmv_with_freight、dimensionPolicies.product_category_nulls_v1',source.prompt+' / 规则7、8（名称关联保留未匹配事实）、9、12',source.schema+' / translation仅原始类目名为PK，英文名不唯一约束'],
    checks:['T4-004-coverage','T4-004-top'],
    proposedResolution:'保留澄清；先确认金额口径。新版参考应在明确排原始NULL类目的前提下LEFT JOIN翻译，并保留原始类目身份。下列两条只是诊断候选，不能静默改Gold或回填分数。',
    proposedQuestion:'用英文类目名统计最高销售额时，销售额按不含运费商品金额，还是含运费GMV？',
    proposedGoldSql:null,
    diagnosticSqlCandidates:[
      {assumption:'用户确认不含运费商品金额；未额外要求有效订单',sql:'SELECT t.product_category_name_english, ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN products p ON p.product_id=oi.product_id LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name WHERE p.product_category_name IS NOT NULL GROUP BY p.product_category_name,t.product_category_name_english ORDER BY sales DESC,p.product_category_name LIMIT 1;'},
      {assumption:'用户确认采用当前注册的含运费GMV（含其有效订单过滤）',sql:"SELECT t.product_category_name_english, ROUND(SUM(oi.price+oi.freight_value),2) AS sales FROM order_items oi JOIN orders o ON o.order_id=oi.order_id JOIN products p ON p.product_id=oi.product_id LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name WHERE p.product_category_name IS NOT NULL AND o.order_status NOT IN ('canceled','unavailable') GROUP BY p.product_category_name,t.product_category_name_english ORDER BY sales DESC,p.product_category_name LIMIT 1;"}
    ],
    secondaryIssues:[{type:'REFERENCE_DEFECT',detail:'翻译覆盖损失已实际发生；不只是未来NULL风险。'},{type:'COINCIDENTAL_TOP1',detail:'原SQL与保留翻译缺失类目的候选当前Top1相同，不能用Top1相同否认事实损失。'},{type:'EXECUTION_SCOPE_LIMIT',detail:'本复核依据澄清规则静态判读，未读取允许运行范围之外的入口配置，也未声称实际API已启用该模块。'}]
  },
  {
    id:'T4-006', decision:'CONTRACT_CONFLICT',
    reason:'卖家所在州已明确，order_items.seller_id连sellers主键是多对一，按seller_state汇总商品明细无连接放大；“每个卖家所在的州和该州卖家的总销售额”是州级结果，不要求再输出每个seller_id。问题中的裸“销售额”仍未说明含不含运费，现行澄清规则会提出选择，Gold无依据地固定SUM(price)。现库23个卖家州，SP商品金额8753396.21；若选择GMV，金额组成及有效订单过滤都要同步按该指标定义，不能只加运费不管事实范围。',
    contractSource:[source.ambiguities+' / 销售额缺hint',source.detector+' / legacy-hint确定性规则',source.metrics+' / gmv_with_freight与sales_without_freight',source.prompt+' / 规则7、9、16',source.schema+' / sellers PK及order_items.seller_id FK'],
    checks:['T4-006-seller-states','T4-002-geography'],
    proposedResolution:'维持澄清。确认不含运费后原聚合主体可保留；确认GMV则同时采用对应过滤。原正式集保持原样，新契约候选单列。',
    proposedQuestion:'每个卖家所在州的销售额，按不含运费商品金额还是含运费GMV统计？',
    proposedGoldSql:null,
    diagnosticSqlCandidates:[{assumption:'确认不含运费商品金额',sql:'SELECT s.seller_state,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN sellers s ON s.seller_id=oi.seller_id GROUP BY s.seller_state ORDER BY sales DESC LIMIT 100;'}, {assumption:'确认当前注册GMV',sql:"SELECT s.seller_state,ROUND(SUM(oi.price+oi.freight_value),2) AS sales FROM order_items oi JOIN orders o ON o.order_id=oi.order_id JOIN sellers s ON s.seller_id=oi.seller_id WHERE o.order_status NOT IN ('canceled','unavailable') GROUP BY s.seller_state ORDER BY sales DESC LIMIT 100;"}]
  },
  {
    id:'T4-008', decision:'KEEP',
    reason:'计数对象是members表的会员实体member_id。LEFT JOIN到任意标签映射后仅保留t.member_id IS NULL，匹配者即使有多条标签也全部被WHERE排除，未匹配会员只留一行，所以COUNT(*)不需再DISTINCT。实际与NOT EXISTS都为115/96096个会员；现有映射无孤儿会员。未要求正常状态或有效订单，不应加会员状态、标签定义关联或订单过滤。此处“从来”只能指当前留存映射中没有记录，不能宣称恢复了被物理删除的历史。',
    contractSource:[source.glossary+' / members、user_tag_map关系与粒度',source.prompt+' / 规则7、10',source.schema+' / members PK、user_tag_map PK(member_id,tag_id)，member_id非NULL'],
    checks:['T4-008-antijoin'],
    proposedResolution:'保留Gold。若产品将来允许删除映射且要证明历史从未打标，需新增历史契约或审计事实，不能用当前表伪称完整历史。'
  },
  {
    id:'T4-011', decision:'KEEP',
    reason:'平均对象是评价分，每条评价一份权重，按其订单状态分组。order_reviews按order_id关联唯一orders不会放大评价；同一订单多条评价本来就应进入评价均值，不应为“消重”改成每单均值或AVG(DISTINCT review_score)。现库99224条评价覆盖98673笔订单，各8种现存状态都有评价，例如delivered的96361条评价均值4.16。无评价的订单没有可参与平均的评分，不能补0。未授权只留有效/已送达订单，Gold保留canceled及unavailable正确。',
    contractSource:[source.glossary+' / order_reviews一行一条评价',source.prompt+' / 规则7、9、10、12',source.schema+' / reviews组合PK(review_id,order_id)，orders PK及review FK'],
    checks:['T4-011-review-status','T4-013-review-cardinality'],
    proposedResolution:'保留Gold。若新增一个完全无评价的订单状态并明确要求其也展示，应补NULL均值行；这与当前评价均值算法是否去重是两个问题。'
  },
  {
    id:'T4-013', decision:'REFERENCE_DEFECT',
    reason:'题目数“订单”“笔”，Gold却在评价事实粒度COUNT(*)。reviews只有(review_id,order_id)组合主键，order_id FK不保证一单一评。实际547笔订单有多条评价；14575条1/2分评价只对应14533笔订单，Gold多计42笔，10个州受影响。SP应5251而非5265，RJ应2634而非2647。分值实查都在1到5内，因此<=2与IN(1,2)当前相同；问题核心是订单身份去重，不应删除重复评价记录或用最新评价任意覆盖“有过差评”的集合。',
    contractSource:[source.prompt+' / 规则10：满足条件不同订单须按订单主键去重或EXISTS',source.glossary+' / order_reviews一行一评价',source.schema+' / order_reviews PK(review_id,order_id)，order_id无UNIQUE'],
    checks:['T4-013-bad-order-counts','T4-013-review-cardinality'],
    proposedResolution:'在单独版本中采用COUNT(DISTINCT o.order_id)或EXISTS，保留有至少一条1/2分评价的订单集合；原正式数据和分数不在此修改。',
    proposedGoldSql:'SELECT c.customer_state,COUNT(DISTINCT o.order_id) AS cnt FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE r.review_score IN (1,2) GROUP BY c.customer_state ORDER BY cnt DESC LIMIT 100;',
    secondaryIssues:[{type:'REGIONAL_SUBJECT_UNSPECIFIED',detail:'候选延续原Gold的客户州假设以隔离已证实的计数缺陷；若要求题干彻底无歧义，应明确写客户所在州。'}]
  },
  {
    id:'T4-015', decision:'CONTRACT_CONFLICT',
    reason:'“大区”和“订单”满足当前regional-subject澄清规则的启动条件，却缺客户/卖家/仓库地域证据，应先澄清。Gold擅自使用客户州映射大区。该路径连接customers与regions唯一主键，当前99441笔订单都可映射且无NULL大区，COUNT(*)在所选路径是正确的；但改为卖家大区会得到另一集合，Sudeste客户订单68266，卖家订单82583（按区域内订单去重），且一单多卖家可跨区，不能跨组加总当全站单量。',
    contractSource:[source.ambiguities+' / 大区 triggerPatterns与regional-subject必需证据',source.detector+' / trigger不是已解决证据，组间AND',source.glossary+' / customers、sellers、warehouses三类地域关联',source.schema+' / customers、regions PK与覆盖检查'],
    checks:['T4-015-regional-subject','T4-015-region-coverage'],
    proposedResolution:'维持澄清，让用户选择地域主体；不关闭反问迎合Gold。确认客户所在大区后可采用原路径；其他主体必须重新检查单订单多地域及未知归属。',
    proposedQuestion:'每个客户所在大区的订单数量是多少？如需卖家或仓库地域，明确指定该主体。',
    proposedGoldSql:null,
    diagnosticSqlCandidates:[{assumption:'用户确认按客户所在大区，当前映射全覆盖',sql:'SELECT r.macro_region,COUNT(o.order_id) AS cnt FROM orders o LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN regions r ON r.region_code=c.customer_state GROUP BY r.macro_region ORDER BY cnt DESC LIMIT 100;'}]
  },
  {
    id:'T4-017', decision:'KEEP',
    reason:'订单事实沿orders.customer_id定位客户档案，再按自然人customer_unique_id找到当前会员，这是跨客户关联键识别会员的正确路径。members.customer_unique_id有真实UNIQUE约束，故该连接不会复制订单；四级COUNT(*)分别54605、38080、6571、185，逐级与COUNT(DISTINCT order_id)完全一致。现行维度默认使用事实已有level_id，题干未要求等级名称，Gold不补member_levels名称符合契约。题干未要求历史下单时等级、正常会员或有效订单，不能偷偷改为快照/状态过滤。',
    contractSource:[source.prompt+' / 规则7、8、9、10',source.glossary+' / members.customer_unique_id与customers关系、level_id',source.schema+' / members UNIQUE(customer_unique_id)与level_id FK'],
    checks:['T4-017-member-level-orders'],
    proposedResolution:'保留Gold；在讲解中明确这是当前会员等级归属而不是下单时历史等级，不能把现有UNIQUE结论推广成所有会员表天然一人一行。'
  },
  {
    id:'T4-019', decision:'CONTRACT_CONFLICT',
    reason:'题目没有明确要求标签名称，当前维度默认是事实已有tag_id，Gold却输出并仅按tag_name分组。user_tags.tag_name没有UNIQUE约束，名称不是标签身份；当前6个名称恰好各自唯一，所以现库计数没有暴露同名合并错误，输出投影契约仍不一致。map的(member_id,tag_id)组合主键及COUNT DISTINCT保证每标签会员不重复；6类映射会员数与现存会员连接后完全一致，且当前无零覆盖标签。Gold从map出发还不能展示将来的零会员标签。',
    contractSource:[source.prompt+' / 规则8默认标识输出、规则9标签按完整主键、各指标事实全集',source.glossary+' / 标签实体与映射',source.schema+' / user_tags PK(tag_id)、tag_name无唯一约束、user_tag_map组合PK'],
    checks:['T4-019-tag-populations','T4-019-tag-name-duplicates'],
    proposedResolution:'先冻结新契约：沿用当前默认则输出tag_id；如果确实要名字，题干明确写名称，内部仍按tag_id区分。候选从标签全集保留零会员标签，并只数真实会员。不得静默回写原Gold。',
    proposedGoldSql:'SELECT t.tag_id,COUNT(DISTINCT m.member_id) AS cnt FROM user_tags t LEFT JOIN user_tag_map tm ON tm.tag_id=t.tag_id LEFT JOIN members m ON m.member_id=tm.member_id GROUP BY t.tag_id ORDER BY cnt DESC,t.tag_id LIMIT 100;',
    proposedQuestion:'每个用户标签（输出标签编号）各有多少会员，包含零会员标签？如要名称则明确要求展示名称。',
    secondaryIssues:[{type:'COINCIDENTAL_NAME_UNIQUENESS',detail:'当前没有同名标签、没有零会员标签，并不意味着schema保证它们不可能出现。'}]
  },
  {
    id:'T4-020', decision:'KEEP',
    reason:'“高价值用户”在当前glossary明确映射tag_code=HIGH_VALUE，实查仅tag_id=1且tag_name对应。筛选会员后按自然人连接其所有customer_id，再连订单，符合一个会员跨客户关联键多次下单的行为口径；COUNT(DISTINCT o.order_id)保护订单身份，不能用会员数或客户档案数代替。Gold与订单侧EXISTS均为20001笔，关联行也为20001。题目未指定有效订单、注册时间或下单当时已有该标签，故统计当前被打此标签会员的全量历史订单，不推断历史标签生效时点。',
    contractSource:[source.glossary+' / HIGH_VALUE枚举与会员自然人关联',source.prompt+' / 规则7、10、15',source.schema+' / orders、members、user_tags主键与mapping组合主键'],
    checks:['T4-020-high-value-orders'],
    proposedResolution:'保留Gold；若业务要“打标之后的订单”需另写明确时间边界，不应把tagged_at过滤偷偷加到当前题。'
  },
  {
    id:'T4-022', decision:'KEEP',
    reason:'目标是优惠券使用事实中的优惠金额合计，直接SUM(coupon_usages.discount_amount)即可，usage_id才是事实身份；不需要关联券定义、商品明细或订单支付，从而避开多条券记录与多条商品互乘。实查12054条用券记录、金额422462.87、金额NULL为0条；当前虽没有一单多券，正确性不依赖这一巧合，未来多券也应逐条加总而不是按金额DISTINCT。题目不是折扣率，不触发discount_rate的GMV分母或有效订单过滤。',
    contractSource:[source.glossary+' / coupon_usages.discount_amount',source.metrics+' / discount_rate仅适用于其别名及折扣率问题',source.prompt+' / 规则7、9、12',source.schema+' / coupon_usages PK(usage_id)'],
    checks:['T4-022-coupon-amount'],
    proposedResolution:'保留Gold。空事实集合SUM返回NULL符合现行一般聚合语义，不擅自加已完成订单过滤或用券定义的discount_value替代实际优惠。'
  },
  {
    id:'T4-024', decision:'COINCIDENTAL_EQUIVALENCE',
    reason:'题干明确按仓库名称汇总，因此按warehouse_name合并同名是允许的，不能机械要求额外输出ID。warehouse_id连仓库PK不放大发货单，但Gold从shipments出发的INNER JOIN仅产生有匹配发货单的仓库名称，无法回答零发货仓库；也会丢失nullable warehouse_id的未分配记录，与名称关联保留未知事实的当前规则不一致。当前5仓都有发货，98202张发货单全部匹配且无NULL，所以现库与保留全集的方案恰好一致。只读构造反例中一个零发货仓库会被Gold漏掉。',
    contractSource:[source.prompt+' / 规则8明确名称时可使用名称，但不得因名称关联改变事实覆盖；规则9事实全集与LEFT JOIN',source.glossary+' / shipments与warehouses',source.schema+' / shipments PK(shipment_id)、warehouse_id nullable且FK，warehouse_name非唯一'],
    checks:['T4-024-warehouse-coverage','T4-024-zero-warehouse-counterexample'],
    proposedResolution:'保持原正式样本；新版明确“包含零发货仓库”和“未分配仓库”展示策略。候选用仓库与发货FULL JOIN，在名称组内COUNT发货主键，既不把空连接计作一单，也保留未知仓库事实。',
    proposedGoldSql:'SELECT w.warehouse_name,COUNT(s.shipment_id) AS cnt FROM warehouses w FULL JOIN shipments s ON s.warehouse_id=w.warehouse_id GROUP BY w.warehouse_name ORDER BY cnt DESC LIMIT 100;',
    proposedQuestion:'每个仓库名称负责多少发货单？包含零发货仓库，并把未分配仓库的发货单单列NULL名称。',
    secondaryIssues:[{type:'CURRENT_OUTPUT_SAME',detail:'实际数据无零发货仓库、无未知归属；这不是当前结果错误，也不是已证明一般等价。'}]
  },
  {
    id:'T4-026', decision:'KEEP',
    reason:'问题点名承运商，匹配shipment_delivery_days而非orders下单到签收的delivery_days；事实一行一shipment，直接以carrier分组、两时间均非空后取EPOCH/86400平均并ROUND两位，单位是小数天，符合当前契约，无需轨迹JOIN。四家均有可计时记录，均值9.32/9.34/9.36/9.27天，分别16088/32133/32185/16063条参与平均。实查23条负时长，是源数据质量问题，现契约未授权排除或截为0；这样做会改变范围和均值，不能作为“修Gold”的借口。',
    contractSource:[source.metrics+' / shipment_delivery_days过滤、起止字段、粒度与单位',source.prompt+' / 规则7、12、16',source.schema+' / shipments PK与timestamp字段'],
    checks:['T4-026-carrier-days'],
    proposedResolution:'保留Gold。另行提交负时长数据质量口径供业务确认；若确认清洗，必须版本化并同时披露剔除数量，不能悄悄过滤。',
    secondaryIssues:[{type:'SOURCE_DATA_QUALITY',detail:'23条delivered_at早于shipped_at；本复核未修改源数据。'}]
  },
  {
    id:'T4-028', decision:'CONTRACT_CONFLICT',
    reason:'实际处理时长应是support_tickets.resolved_at-created_at，小数小时按3600换算，Gold没有误用sla_hours，这一机制正确；但题干没要求名称，当前维度默认使用category_id，Gold输出category_name且仅按名称分组。分类名没有唯一约束；当前6类名称恰好不同、所有工单都有分类且每类都有已解决工单，掩盖了同名合并和空类遗漏边界。当前7934张工单中可计时已解决6292张，发现2556条resolved_at<created_at，不能未经契约授权排除。题目未要求SLA达成率，不应使用SLA分母或阈值。',
    contractSource:[source.prompt+' / 规则8默认标识、规则9实体主键与覆盖、规则12、16工单实际时长',source.glossary+' / support_tickets、ticket_categories',source.schema+' / category_id PK/FK、category_name无UNIQUE'],
    checks:['T4-028-category-populations','T4-028-coverage'],
    proposedResolution:'沿用当前默认则输出category_id；若希望名称，先修改题干契约而非静默换投影。候选保留分类全集及未知分类事实，以AVG天然忽略没有完整起止时间的记录，不把未知耗时补0。负时长另按业务确认处理。',
    proposedGoldSql:'SELECT COALESCE(st.category_id,tc.category_id) AS category_id,ROUND((AVG(EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600.0))::numeric,2) AS avg_hours FROM ticket_categories tc FULL JOIN support_tickets st ON st.category_id=tc.category_id GROUP BY COALESCE(st.category_id,tc.category_id) ORDER BY avg_hours DESC NULLS LAST LIMIT 100;',
    proposedQuestion:'每个工单分类（输出分类编号）的平均实际处理时长是多少小时？无可计时时长的分类显示NULL。',
    secondaryIssues:[{type:'SOURCE_DATA_QUALITY',detail:'2556条负处理时长真实存在；本报告不自行定义清洗规则。'},{type:'COINCIDENTAL_NAME_UNIQUENESS_AND_COVERAGE',detail:'当前分类名唯一、无缺失分类且每类有已解决工单，不是schema对所有未来数据的保证。'}]
  },
  {
    id:'T4-031', decision:'KEEP',
    reason:'题干“卖家结算净额”已锁定settlements.net_amount，不能被商品销售金额或settlement_items毛额替换；地域主体明确为卖家州。按settlement_id事实逐笔SUM再按卖家PK取得seller_state，多对一不会放大结算。实查5658条结算全部匹配卖家，关联前后净额都是4821914.63、无NULL金额，结果20州。结算状态1/2/3是枚举而不是本题过滤请求，不能擅自仅数已打款；period_start等时间也未被题干限定。',
    contractSource:[source.glossary+' / settlements.net_amount、seller_state与约定关系',source.prompt+' / 规则7、9、16',source.schema+' / settlements PK、sellers PK；该关系未声明FK但已实查覆盖'],
    checks:['T4-031-settlement-coverage','T4-031-state-totals'],
    proposedResolution:'保留Gold。当前无数据库FK保证settlements.seller_id未来总有效；若接入增量库应监控孤儿结算并明确未知州策略，不能把本次覆盖检查冒充数据库完整性约束。'
  },
  {
    id:'T4-033', decision:'KEEP',
    reason:'按退款原因代码reason_code汇总refunds.refund_amount，事实主键refund_id，直查主退款表不会因退款明细或订单商品连接而放大金额。题干未要求原因名称，code输出符合现行默认；未要求已退款，不得根据status枚举擅自排待审核或已拒绝记录。现库四原因分别LATE=221424.77、QUAL=210843.80、OTHER=109628.71、CUST=107818.34，均无NULL金额。ROUND两位金额单位未变，无时间边界或TopK并列问题。',
    contractSource:[source.glossary+' / refunds.reason_code与refund_amount',source.prompt+' / 规则7、8、9、12',source.schema+' / refunds PK(refund_id)'],
    checks:['T4-033-refund-reasons'],
    proposedResolution:'保留Gold；如果业务想统计已实际退回款项，应另明确status/到账口径，不在本题里补一个看似合理的状态条件。'
  },
  {
    id:'T4-035', decision:'COINCIDENTAL_EQUIVALENCE',
    reason:'自然人customer_unique_id分组、含运费金额及有效订单过滤均与当前aov定义相容，题干按订单平均不是按人数。Gold INNER JOIN商品明细却将分母缩成“有明细的有效订单”，而不是全部有效订单。现库98207笔有效订单有8笔无明细，涉及8人；其中1人有一笔43.29有明细订单和另一笔无明细有效订单，Gold均值43.29，完整有效订单分母应为21.65。另7人完全无可知金额，不应擅自补0成真实消费。当前Top10不变且第10名4445.50无并列，只说明本题投影的榜单恰好隐藏分母缺陷，不能称一般公式正确。',
    contractSource:[source.metrics+' / aov=GMV/有效订单数',source.prompt+' / 规则7用户明确平均方式优先、规则9每指标事实全集、规则10去重不得改分母',source.metrics+' / entityPolicies.customer_identity_v2',source.schema+' / orders与order_items一对多且无必须有明细约束'],
    checks:['T4-035-order-population','T4-035-top10-comparison','T4-035-changed-person'],
    proposedResolution:'保留原正式榜单与得分。新版应先按订单合计商品金额，再从完整有效订单出发LEFT JOIN，分母数全部有效订单；纯缺失金额客户不可比较，明确排在榜单之后/不参与数值排名，而非把NULL补0。',
    proposedGoldSql:"WITH order_totals AS (SELECT order_id,SUM(price+freight_value) AS amount FROM order_items GROUP BY order_id),per_person AS (SELECT c.customer_unique_id,ROUND(SUM(t.amount)/NULLIF(COUNT(o.order_id),0),2) AS avg_order_value FROM orders o JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN order_totals t ON t.order_id=o.order_id WHERE o.order_status NOT IN ('canceled','unavailable') GROUP BY c.customer_unique_id) SELECT customer_unique_id,avg_order_value FROM per_person WHERE avg_order_value IS NOT NULL ORDER BY avg_order_value DESC,customer_unique_id LIMIT 10;",
    secondaryIssues:[{type:'CURRENT_TOP10_SAME',detail:'Gold与完整分母候选的10个ID及数值逐项相同；底层1名有金额客户均值不同，7名客户金额完全缺失。'},{type:'MISSING_AMOUNT_POLICY',detail:'SUM忽略缺失订单金额并保留其分母，是GMV/有效订单数的当前定义；若业务认为缺金额应让整个人的AOV为NULL，须明确另立契约，不能混称为已知零金额。'}]
  },
  {
    id:'T4-037', decision:'KEEP',
    reason:'事实是订单商品行，glossary明确一行一个商品，销量以行数COUNT(*)计而非订单去重数；产品照片数量是products属性，只投影不累加。products主键确保连接每条商品行只匹配一次，按product_id及照片数分组不会把不同商品合并。现库32951个商品都出现在明细中，前10销量依次527、488、484、392、388、373、343、323、281、274，第10名仅1件商品，无截止并列。题干不要求有效订单，不能为“销量”臆加订单状态过滤。若出现少于10个有销量商品，“每个商品”是否含零销量应另明确；本次Top10不受影响。',
    contractSource:[source.glossary+' / order_items一行一个商品及products.product_photos_qty',source.prompt+' / 规则7、9、10',source.schema+' / products PK与order_items.product_id非NULL FK'],
    checks:['T4-037-sales-top10'],
    proposedResolution:'保留Gold。对于榜单截止处将来出现并列，用户未要求全部并列时可加product_id稳定10条选择；不得把当前没有并列当成永远不会并列。'
  },
  {
    id:'T4-039', decision:'COINCIDENTAL_EQUIVALENCE',
    reason:'问题是搜索记录层有点击记录/全部搜索记录，COUNT(*)按日志主键粒度正确，不需按用户去重或连products。当前题干不包含ctr注册别名“搜索转化率/点击率/搜索点击率”，按metrics文件声明的子串匹配机制不会注入ctr；因此适用未注册普通比例默认小数比率四位，而不能仅因语义像CTR就强行乘100。14392条记录中8651条有点击，Gold=0.6011。Gold缺NULLIF保护，当前非空才避免除零；只读空过滤实查分子分母均0，当前规则要求返回NULL，Gold将触发division by zero。这是一般空集安全缺陷，不是当前数值算错，也不是已证实百分数契约冲突。',
    contractSource:[source.metrics+' / ctr aliases与文件说明的子串注入口径',source.prompt+' / 规则10日志记录粒度、规则12普通比率四位及零分母NULLIF',source.schema+' / search_logs PK(log_id)'],
    checks:['T4-039-search-ratio','T4-039-empty-population','T4-039-empty-gold-error'],
    proposedResolution:'新版参考补NULLIF(COUNT(*),0)，保留当前0到1四位小数。若希望这类释义也一律采用注册CTR百分数，应先显式扩充业务契约并版本化，不能倒推本题已命中过别名。',
    proposedGoldSql:'SELECT ROUND(COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)::numeric/NULLIF(COUNT(*),0),4) AS ctr FROM search_logs;',
    secondaryIssues:[{type:'UNIT_ROUTING_LIMIT',detail:'未执行实际检索与模型；别名是否命中依据允许读取的metrics文件自身说明静态判定。百分数60.11仅为诊断对照，未将其当作应有Gold。'},{type:'GENERAL_EMPTY_SET_SAFETY',detail:'空过滤查询为人为构造边界，实际search_logs非空，未伪称生产已发生除零。'}]
  },
  {
    id:'T4-040', decision:'KEEP',
    reason:'计数对象是products.product_id，要求从未出现在任意仓库库存记录，而非当前库存量大于0。inventory主键(warehouse_id,product_id)允许一个商品多仓，但只要有任一匹配库存行就被i.product_id IS NULL排除，无匹配商品只保留一行；因此Gold反连接COUNT(*)无需DISTINCT，且不依赖inventory.product_id唯一。现库32951商品中14678无库存记录，NOT EXISTS一致；5336个商品多仓，24行库存量<=0仍属于“进过库存表”，不得过滤后误数未入库。所谓历史仅限留存库存表，不能证明物理删除前的历史。',
    contractSource:[source.glossary+' / inventory仓库×商品粒度',source.prompt+' / 规则7、10',source.schema+' / inventory组合PK、product_id非NULL与products PK'],
    checks:['T4-040-inventory-antijoin'],
    proposedResolution:'保留Gold；如产品要真实历史从未入库，需明确使用入库流水/保留历史的事实定义，不能把库存量为0等同于从未入库。'
  },
  {
    id:'T4-042', decision:'AMBIGUOUS',
    reason:'Gold含义是“历史上至少有一次new_price>old_price的不同商品”，COUNT DISTINCT product_id正确保护历史多事件下商品身份；题干“发生过价格调整的商品里，价格是上调的”也可能指最近一次调整方向，未写至少一次还是最近一次，当前没有相关方向口径来替用户选择。现库9813条历史恰好对应9813个商品，4903个上调，最近一次与曾经上调均为4903；这是当前每商品一条记录的巧合，product_id并无UNIQUE约束。只读反例同商品先100→120再120→90，曾上调=1、最近上调=0，不能以当前相同消灭语义分歧。',
    contractSource:[source.glossary+' / product_price_history历史事实与old_price/new_price/changed_at',source.prompt+' / 规则7按请求确定范围、规则10按商品身份去重',source.ambiguities+' / 平均涨跌幅规则不适用于本题；无“曾经/最近方向”默认',source.schema+' / history_id PK，product_id与(product_id,changed_at)无UNIQUE'],
    checks:['T4-042-rise-interpretations','T4-042-history-counterexample'],
    proposedResolution:'保留原正式样本，先把题干固定为“历史上至少上调过一次”或“最近一次调整为上调”；不要强定新默认。选前者可保留原Gold；选最近一次则还要定义changed_at并列/缺失处理，不能随意取一条。',
    proposedQuestion:'发生过价格调整的商品里，有多少个商品在历史上至少有一次新价高于旧价？如指最近一次调整，请明确该时间口径。',
    proposedGoldSql:null,
    secondaryIssues:[{type:'COINCIDENTAL_SINGLE_HISTORY',detail:'当前一商品一条历史不是数据库约束，ever与latest不能宣称泛化等价。'}]
  },
  {
    id:'T4-044', decision:'CONTRACT_CONFLICT',
    reason:'当前customer_identity_v2明确区分两个指标：裸“客户有多少个”默认客户档案行/rowKey customer_id；“平均每人下多少单”才按自然人customer_unique_id。Gold把第一列也写成自然人数，不符合当前默认，不能因为同一个结果有“每人”就把所有计数单位扁平化。实查99441客户档案、96096自然人；Sudeste应输出68266档案而Gold65900人，但第二列每人订单数1.04有依据。订单沿customer_id取得该次客户档案的地区，regions PK不放大；LEFT orders保留零订单者的结构正确。22人跨大区记录，组内人数之和不是全站去重人数。当前无零订单档案、无未知大区，不代表这些边界由schema禁止。',
    contractSource:[source.metrics+' / entityPolicies.customer_identity_v2 populationCountUnit=ROW_KEY、每客户行为用自然人、同人多地区',source.prompt+' / 规则9每指标分别确定全集、规则10不同计数各定身份',source.glossary+' / 客户区域来源',source.schema+' / customers PK(customer_id)、customer_unique_id非NULL但非唯一、regions PK'],
    checks:['T4-044-region-populations','T4-044-coverage'],
    proposedResolution:'不得修改旧分数来迁就新默认。若采用当前默认，新版第一指标计客户档案，第二指标仍按自然人分母；若业务希望两列均是自然人，应明确题干“去重自然人数”，再冻结契约，不称原中文只有一种解释。',
    proposedGoldSql:'SELECT r.macro_region,COUNT(DISTINCT c.customer_id) AS customers,ROUND(COUNT(o.order_id)::numeric/NULLIF(COUNT(DISTINCT c.customer_unique_id),0),2) AS orders_per_customer FROM customers c LEFT JOIN regions r ON r.region_code=c.customer_state LEFT JOIN orders o ON o.customer_id=c.customer_id GROUP BY r.macro_region ORDER BY customers DESC LIMIT 100;',
    proposedQuestion:'每个客户所在大区有多少条客户档案记录？按大区内去重自然人计算，平均每人下多少单？',
    secondaryIssues:[{type:'LEGITIMATE_ALTERNATE_CONTRACT',detail:'若题干明确第一列为去重自然人数，原Gold两指标的计数身份可成立；这是契约选择而非自然人去重算法错误。'}]
  }
];
for (const n of notes) {
  n.checks = n.checks.map(check => {
    if (!(check in sqlByCheck) || !(check in resultByCheck)) throw new Error('Missing actual SQL/result: ' + check);
    return {check,sql:sqlByCheck[check],result:resultByCheck[check]};
  });
}
if (input.items.length !== 22 || notes.length !== 22) throw new Error('Expected 22 items');
if (input.items.map(x=>x.id).join('|') !== notes.map(x=>x.id).join('|')) throw new Error('Scope/order mismatch');
const counts = Object.fromEntries(['KEEP','CONTRACT_CONFLICT','REFERENCE_DEFECT','AMBIGUOUS','COINCIDENTAL_EQUIVALENCE'].map(k=>[k,notes.filter(x=>x.decision===k).length]));
const candidateQueries = [];
for (const item of notes) {
  if (item.proposedGoldSql) candidateQueries.push({check:'candidate-'+item.id,sql:item.proposedGoldSql});
  for (const [i, candidate] of (item.diagnosticSqlCandidates || []).entries()) candidateQueries.push({check:'candidate-'+item.id+'-option-'+(i+1),sql:candidate.sql});
}
const candidateSql = "BEGIN READ ONLY;\nSET LOCAL statement_timeout = '8s';\n" + candidateQueries.map(({check,sql})=>"SELECT jsonb_build_object('check','"+check+"','result',COALESCE(jsonb_agg(candidate_row),'[]'::jsonb)) FROM ("+sql.replace(/;\s*$/,'')+") candidate_row;").join('\n') + '\nCOMMIT;\n';
fs.writeFileSync(path.join(dir,'joins-candidate-checks.sql'),candidateSql,'utf8');
const candidateResultsPath = path.join(dir,'joins-candidate-checks-results.jsonl');
const candidateResults = fs.existsSync(candidateResultsPath) ? read('joins-candidate-checks-results.jsonl').split(/\r?\n/).filter(Boolean).map(JSON.parse) : [];
const report = {
  reviewer:'/root/review_joins',
  provenance:{freshContext:true,participatedInAuthoringOrTuning:false,readPriorAudit:false,externalModelCalls:0},
  scope:{input:'reports/independent-contract-review-20261119/joins-input.json',items:22,originalFormalOnly:true,productionOrEvalModified:false,gitCommitPerformed:false},
  methodology:{
    allowedSources:Object.values(source),
    excluded:'未读取历史模型输出、其他审核结论、docs/review、errata、ROADMAP/IMPROVEMENT_PLAN历史状态或其他审计报告；未调用外部模型API。',
    executionContext:resultByCheck['execution-context'],
    executionNote:'各SQL文件以BEGIN READ ONLY开始、SET LOCAL statement_timeout=8s，以COMMIT结束。checks中的sql为实际事务内执行的原SELECT语句；构造反例均明确标记，不是原库测量。',
    limitations:'这是题干/参考/现行规则的契约复核，不是模型复测、人工签字或评分器复测。澄清与别名命中依据允许来源静态判读，未读取调用入口运行开关。候选Gold SQL仅供后续独立复核，未改原始data、评分器、门槛或关闭澄清。',
    decisionDefinitions:{
      KEEP:'本次核查未发现具体的当前题意/规则/参考问题；不承诺未来任意脏数据自动正确。',
      CONTRACT_CONFLICT:'当前明确产品默认或澄清规则与Gold约定不同；不是据此宣布旧业务默认错误。',
      REFERENCE_DEFECT:'在已明确计数/事实语义下，参考SQL有确定错误，且现库结果已受影响。',
      AMBIGUOUS:'题意存在会改变答案且当前契约没有唯一解决的选择；本次不擅定默认。',
      COINCIDENTAL_EQUIVALENCE:'当前所问输出恰好一致或当前非空才可运行，但事实全集/分母/边界不保证一般等价。'
    }
  },
  sourceSnapshots:Object.entries(source).map(([key,file])=>({key,path:file,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')})),
  candidateValidation:{note:'仅运行已声明前提下的诊断候选SQL验证可执行性与结果；不是用户口径确认、Gold替换或模型评测。',plannedQueries:candidateQueries.length,completedQueries:candidateResults.length,results:candidateResults},
  summary:{total:notes.length,counts,actualAnswerDifference:['T4-013: Gold差评订单多计42笔（10州受影响）','T4-019: tag_name而非默认tag_id投影','T4-028: category_name而非默认category_id投影','T4-044: 客户档案数与自然人数不同'],topKCurrentCoincidence:['T4-004: 实际漏24条未翻译非NULL类目明细/5514.48，Top1恰好不变','T4-035: 漏8笔有效订单的分母，1人均值改变，当前Top10恰好不变'],generalSafetyOnly:['T4-024: 当前所有仓库有发货且已分配，零仓库/未知归属边界需保留','T4-039: 当前14392条日志不除零，空集0/0需NULLIF'],unresolvedSemantics:['T4-002: 客户州或卖家州','T4-042: 曾经上调或最近一次上调'],clarificationContractConflicts:['T4-004','T4-006','T4-015'],sourceDataQuality:['T4-026: 23条负配送时长','T4-028: 2556条负工单处理时长；未自行清洗']},
  items:notes
};
fs.writeFileSync(path.join(dir,'joins-review.json'),JSON.stringify(report,null,2)+'\n','utf8');
console.log(JSON.stringify({total:notes.length,counts,executedCheckQueries:Object.keys(sqlByCheck).length,nonemptyChecks:notes.filter(x=>x.checks.length>0).length,candidateQueries:candidateQueries.length,completedCandidateQueries:candidateResults.length}));
