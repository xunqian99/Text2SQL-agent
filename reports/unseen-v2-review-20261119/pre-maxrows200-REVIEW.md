# 未见题v2独立语义审查

最终逐题统计：{'PASS': 100, 'DEFECT': 0, 'AMBIGUOUS': 0}。初审99 PASS、1 DEFECT、0 AMBIGUOUS。

本审查没有读取生产Prompt/实现、旧题内容、历史模型输出或旧审计。作者与审查者分工独立，但共享文件系统只有流程隔离。
只读实库执行、语义人工对照、合成CTE边界见证分别记录；执行成功不单独构成PASS。
最终版本属于“反馈后修订接受”，不宣称完全盲审。候选已在出题前冻结，反馈没有进入生产调参。

## 已知数据与边界

实库存在2997个多档案自然人、39个跨州多档案自然人、789个跨订单复用评价ID、2961个多支付订单、9803个多商品订单和97583个多轨迹运输记录。需要完整身份和预聚合/EXISTS。
实库有2556条解决时间早于创建时间的工单，其中548条位于第二季度。相关题按已明确的原始差值/<=SLA公式计算，未擅自改写成非负时长；不能把这些数包装成无质量问题的真实SLA运营结论。
退款头/行订单不一致和当前品牌多归属在实库均为0，但多品牌题面显式说明非独占，退款明细原订单匹配使用完整二键。
≤500行约束指本次冻结数据库快照的完整输出；部分维度全集没有人为LIMIT，未来数据库增长时必须重验。
旧题只允许程序精确重合检查；0精确重合不证明0语义重合。没有做旧题人工语义对照。

## B2-T1-001 — PASS

订单主键order_id；审核非空与交承运商为空是独立时间事实，包含全部状态；审核时间/order_id稳定截断30。

证据：只读执行 30 行；结果 SHA-256 `27613eb038bc13f2bc8b2bee038bc2e739c408b1541b700bca3bc182902fad63`。
Gold SHA-256 `e6f682ba9284006c6e8609d2d6c9778cf26c86577e95919399c6d16343d5783b`。

## B2-T1-002 — PASS

商品主键product_id；三边>=40的SQL三值逻辑自然排除NULL，另排除未知重量；重量倒序与商品ID保序25。

证据：只读执行 25 行；结果 SHA-256 `7236e10eb15da7361ef5165a1f0674f02bc78a8d2d1e0eceafc81161e68575c0`。
Gold SHA-256 `3473c6563cb37901ef9ef216144ed14e45e43ad520c3fb66420ac154f8670acd`。

## B2-T1-003 — PASS

member_id保留会员身份，status=2与积分>=1500匹配题意；积分NULL被排除，同积分按ID40。

证据：只读执行 40 行；结果 SHA-256 `56b50bc7eea83a15de9c70568e1e6aeca264b1c8cfd84f34f62fdbcdc4dad924`。
Gold SHA-256 `3e8104cf54763f4f51cd456ff926fad14d71311d0e0634c8d3d8a78080c0683c`。

## B2-T1-004 — PASS

fixed券与闭区间[100,300]准确；最低金额NULL不匹配；门槛/券ID截断35，不施加未要求的有效期。

证据：只读执行 35 行；结果 SHA-256 `90c2807f6a6dc769ac31c251bf924ca24502e5f865a518f55f4cae8e05d59215`。
Gold SHA-256 `b00655bfbb12b1451ff2d5219e09e488086b06a25ec2605fa3306b160ab5b4b4`。

## B2-T1-005 — PASS

shipment_id运输记录；status=2、shipped_at非空、delivered_at为空三项独立条件；发货时间/ID稳定40，不借订单时间。

证据：只读执行 40 行；结果 SHA-256 `bac2c4e7c887f133cab2744510ebda061e66b13b89a67a7394bffa8a620b5a88`。
Gold SHA-256 `0ff40be2ec06e66b7d5961519118561e1c53f88d84447fe2a6746088471241eb`。

## B2-T1-006 — PASS

refund_id退款申请粒度；status=2且refund_amount>150，直接读取金额不加运费；金额/ID稳定30。

证据：只读执行 30 行；结果 SHA-256 `02e71ed372dcd28c31fb9086f74a8040a1e0b4cd0207771f8817aba827d5448b`。
Gold SHA-256 `759c1d5ae9da618a6d8f377aae63fc0c22688d0358cf723f3afb78230cccc3a7`。

## B2-T1-007 — PASS

工单priority=4与resolved_at为空，不以status替代；created_at非空，时间/工单ID稳定30，允许未知订单号。

证据：只读执行 30 行；结果 SHA-256 `8adc185372bd6b6884785517eab5c439fed281e1f59709942bedd136a3a59ce8`。
Gold SHA-256 `6b81f69c76daa910a2e7c47496bffef105987209bf201f88f859991df3dfdbe1`。

## B2-T1-008 — PASS

log_id为事件身份；零结果与非NULL点击同时成立也保留，不纠正原始矛盾；搜索时间/ID双倒序30。

证据：只读执行 22 行；结果 SHA-256 `75dae8b1b88714b8bdf7965e01efa9824aa9dad7bf44cef9d6396449490e9996`。
Gold SHA-256 `9fc3c41780fa64af827ef93f976864c9b15d96bd7847eaa94497c7a9900fbfc1`。

## B2-T1-009 — PASS

库存复合键(warehouse_id,product_id)；两数量已知且quantity<safety_stock；差额与完整键稳定40。

证据：只读执行 40 行；结果 SHA-256 `d6fac681dab671f7f2ca11a599304e143fa345e4e4e256cafb2ba528bdbb26ac`。
Gold SHA-256 `7aa5ec2428a25b070c98d5f8abe61edd03b65b1c6d6bb62d19ddde2a9f4bf2a1`。

## B2-T1-010 — PASS

history_id调价事件；旧价减新价>=50，先排除任一价NULL；金额差/ID稳定30，不用相对涨幅。

证据：只读执行 30 行；结果 SHA-256 `7cb2c7c707e58f56a283cbf0120d3d2be1335e86a866be857fa5ebb8f207949f`。
Gold SHA-256 `f30685da78d9991aa9a2f17acc5f3d9920e08adfebc526c5a30432d5f4d40a08`。

## B2-T1-011 — PASS

评价完整键(review_id,order_id)保留；SQL NULL正文不等于空字符串；创建时间/评价ID/订单号稳定20。

证据：只读执行 20 行；结果 SHA-256 `6f86e19cb4b3545d217678edae446513a700bb9e4ea379440fecdcbeb52a86b3`。
Gold SHA-256 `ce152ac78c0e8c99bbb54486e4c8b7f78077f8692f1a145da18dd9cb52d6bbea`。

边界见证：review-complete-key-and-null-body=PASS

## B2-T1-012 — PASS

支付完整键(order_id,payment_sequential)；credit_card、期数>=8、金额非空；金额及完整键稳定25，全部订单状态。

证据：只读执行 25 行；结果 SHA-256 `7406e9d2ea1224aa0aa2a8ae04230b7fce725c1332019bc6703193354895a3df`。
Gold SHA-256 `df9843214508dfe2179fd2c01fa2ba1f0ccd6952b7dde13a5d84869643e55708`。

## B2-T1-013 — PASS

settlement_id结算单；status=2且net_amount>1000；不重复扣佣金；净额/ID稳定30。

证据：只读执行 30 行；结果 SHA-256 `899f3a1ab1f021ccfb708630ef69aa587dceffa88935d423ef44e0c8a316bd64`。
Gold SHA-256 `53e8831fb8a89751acde8d9cd63236a9b50afb9e6f1b65443e4027e35d934024`。

## B2-T1-014 — PASS

address_id地址记录；默认布尔、地址州RJ、城市非NULL；不改用客户城市或自然人身份，城市/ID稳定40。

证据：只读执行 40 行；结果 SHA-256 `5e793ccc32d2cdd5c39c4cf1963cbbf1356e9b625fbe0402b690dccc0fc4a7e4`。
Gold SHA-256 `88684856d3171c71afa9d2c21fb7aadd68a46f509c0deb9160b0c262d6fc4d2b`。

## B2-T1-015 — PASS

referral_id推荐事件；两端身份非NULL、未转化、原始奖励>0、创建时间已知；不把未转化奖励变0，时间/ID稳定20。

证据：只读执行 20 行；结果 SHA-256 `e4786e731a64cc1212dd134f36dd41afd5f3c3b9504b5807065f3c1c4c1b82d2`。
Gold SHA-256 `7e7420634e252d5502073381556898694d36897e7cef15af9cb3c082132b371f`。

## B2-T1-016 — PASS

campaign_id活动身份而非名称；status=3、budget>0保证已知正候选；预算/ID稳定12。

证据：只读执行 12 行；结果 SHA-256 `d05f7b5629e8a295f410b3484671d41757b270d208dab79bc987c7736e18b3de`。
Gold SHA-256 `6a430ebfc84e2053fa907602ee3c67b7f0a79ae8d74f76542ad14116567e54e0`。

## B2-T1-017 — PASS

brand_id身份；country原值BR精确匹配，名称NULLS LAST及ID稳定25，不翻译国家。

证据：只读执行 12 行；结果 SHA-256 `1a446a49914cadb40ad02740b5f015b18038ad9cee95b5dd9765e465644944dc`。
Gold SHA-256 `7209c488c341fbab6901052ed0280e733b4fd556a75b42eba67c61da2a983d0a`。

## B2-T1-018 — PASS

地理多坐标只投影DISTINCT已知邮编；SC与两坐标非空，不选择随意坐标或连交易事实；邮编升序40。

证据：只读执行 40 行；结果 SHA-256 `50bc133b6c1d2dcd55ff51567f4b28a5441d3f736fe22b4732320efc18af6b78`。
Gold SHA-256 `a96fa04b74961b01aea1d0ff78e506e6b362571530326ae10401a3a10ff2c25d`。

## B2-T1-019 — PASS

message_id事件；customer精确值、正文非SQL NULL（空串保留）、时间已知；时间/ID双倒序35。

证据：只读执行 35 行；结果 SHA-256 `af91234a366aa8456a6f827a512ac893fced81b1ba85822198c859514b1d6e5f`。
Gold SHA-256 `d4a6d79fa8ffb0ec82d6fcef3ecdc9452014fea51e04684e0c446143388c7c83`。

## B2-T1-020 — PASS

warehouse_id仓库身份；capacity>0排除未知非正容量；region_code即仓库州来源，容量/ID稳定8（实库5仓，不伪造8行）。

证据：只读执行 5 行；结果 SHA-256 `95c496d118e529d2994c97aeb7d1b009dd7153032d562023339c8a55660da120`。
Gold SHA-256 `c6a0e9d037d5782ea30a8022e83ecb7c8236a50a5f17b0dada03eab289ec3ebd`。

## B2-T2-001 — PASS

orders一行一单；发货非空且签收NULL，再按原状态分组COUNT(*)；不以状态代替时间、不补空组。

证据：只读执行 3 行；结果 SHA-256 `a70afd569bb60a84c66e21d85c246a4f4a95534598a9ef34c36bd01408c769db`。
Gold SHA-256 `1cd975eaafb11fea2d282b080fad346730afc3e0b65e6fe8c4f4496cebda97ff`。

## B2-T2-002 — PASS

客户档案COUNT(*)与自然人COUNT(DISTINCT customer_unique_id)分离；同一人跨州各计，州NULL成组；规模倒序/州保序。

证据：只读执行 27 行；结果 SHA-256 `63804a584a2242b8cb5e40bcedf3f55479f3fbf597527e55febec798d0b3a649`。
Gold SHA-256 `e111a22807b8cc100c051824bc281d9ddff40fbaf457dc8acfa7ddc5f70e57d6`。

边界见证：customer-record-versus-person=PASS

## B2-T2-003 — PASS

原类目含NULL成组；商品数含未知重量但AVG仅已知重量，HAVING>=80后类目保序，无补0。

证据：只读执行 44 行；结果 SHA-256 `6f0da3f78acc216ec3b2cafc97633ebe74cb5237083fa13b0183a86fcb3345d9`。
Gold SHA-256 `a60cc98b3daca6e7fe5ef888b389fc050d738ded173561c011dbbc67c0b89b2a`。

## B2-T2-004 — PASS

支付事实一行一笔，期数=1；支付方式NULL组、金额SUM/AVG忽略NULL并保全未知为NULL；不额外过滤订单。

证据：只读执行 5 行；结果 SHA-256 `280e202b030522920e39743609286f1d2099d5523962a4f89eec690d47e18579`。
Gold SHA-256 `218dfd442956d75dab85a73b443c22529f4ab0f52838138ec4d0f3e6f50dce5f`。

## B2-T2-005 — PASS

卖家维度COUNT商品行而非订单；SUM(price)保留等额明细、不含运费；未知卖家排除，行数/卖家ID稳定18。

证据：只读执行 18 行；结果 SHA-256 `aa79204eab4f58042300f27815428e9a0bc775a47daa9d60511e1bc529794438`。
Gold SHA-256 `5f6ed4276454b1d725504c2994fd9341a52e6de1352e7e7a48fb80f8e74cdfc1`。

## B2-T2-006 — PASS

member_id计全记录，COUNT(points)只计已知积分，AVG不补0；status含NULL分组（DDL当前非空不影响定义）。

证据：只读执行 3 行；结果 SHA-256 `f62297fc08315204b14719d6deb3e2bb4f7c0aba567567b1817cc2c35dfe6a3b`。
Gold SHA-256 `4f45ed332b2ae87bb573657d6c03ec310b394396687bceb48edc2e0534fc6e95`。

## B2-T2-007 — PASS

keyword非NULL保留空串，result_count=0不包含NULL；每log_id一次，条数/词排序稳定22。

证据：只读执行 9 行；结果 SHA-256 `c7f3a6b37af5b1bce8992867231b28d8d59194ec84cf847fd6b6e4771cd18fde`。
Gold SHA-256 `5805e6d8ae04086846581521996ebe598938c8fc001c260c3d7b122645c14e1b`。

## B2-T2-008 — PASS

shipment_id运输事实COUNT(*)，完整发货/签收双非空FILTER；承运商NULL组保留，不过滤状态或时间先后。

证据：只读执行 4 行；结果 SHA-256 `8dbf96d1d14079b9b955d2a4b6a8a426ceac7b55efbab8ceb8c809e9d7a01a34`。
Gold SHA-256 `2fc488fd29f0d93435c336812d9a483c78ca6f04b1abcf8ef17e10a3da3531ba`。

## B2-T2-009 — PASS

status=4退款单COUNT(*)与SUM(refund_amount)；原因NULL成组，金额不加运费、不变订单数。

证据：只读执行 4 行；结果 SHA-256 `35b0f697ebda529e9363588dc998c454db4aedc8d975e5e0a198598976340a26`。
Gold SHA-256 `05068d9a18232c29584d3ab86a9d667ae866c81c3e357903e9ef417cf47e23cc`。

## B2-T2-010 — PASS

工单按priority含NULL分组；全部工单与resolved_at为空数量分开，不把status作为解决证据。

证据：只读执行 4 行；结果 SHA-256 `4b39d9c21b64e014b286c2ff334017c689d1c3564ac01e45003d7131565eb2e3`。
Gold SHA-256 `8c10e30a1f1ca7c6d4316d9c863999fab3c4f0996553e867658c99ed2eb390c2`。

## B2-T2-011 — PASS

库存主键一仓商品一项；quantity SUM及quantity<safety_stock FILTER，任一NULL不认定低库存；只列库存出现仓。

证据：只读执行 5 行；结果 SHA-256 `649f1f009a8e214c2dc67a95c554e2d84ffb62bb60544863351d3ab42efd1452`。
Gold SHA-256 `68d11369b2c2ac59c6ce3f92ebaa73db5087c8a40267dca97a0a21174854a950`。

## B2-T2-012 — PASS

movement_id流水COUNT及原quantity SUM，movement_type含NULL组；不自动把出库变负，也不做时间限制。

证据：只读执行 4 行；结果 SHA-256 `636600531eed8931e273c82d21dda654d5361b6b9f6b874e927a1e15db05fa79`。
Gold SHA-256 `b1f047ff6b71483e83e3ba513b6d0d1a8f1276ee27d1df954990e4e8e1604b6c`。

## B2-T2-013 — PASS

用券member_id非NULL；COUNT核销与原优惠SUM，HAVING金额>0后排序20；不去重订单或借买家会员身份。

证据：只读执行 20 行；结果 SHA-256 `5d22fe4e6734ca933710a6ef970f50344f7d909a222373d26c236ede9ebfe65b`。
Gold SHA-256 `7b9678aafcfc794db28afc75d68655bb9aff594bc2295d19e8dc2061530194c1`。

## B2-T2-014 — PASS

settlement_id结算单一单一次；status组COUNT、SUM(net_amount)、AVG(net_amount)；全未知自然NULL，不重扣佣金。

证据：只读执行 2 行；结果 SHA-256 `5711e9df4168e85cf35f589890311b91cc6a597794e73007119e7cb014539e7b`。
Gold SHA-256 `418279fa2078e114b31a6c70544e35de1c497d2cb3ec8fe10362121cbb402d54`。

## B2-T2-015 — PASS

先排除任一价NULL再按>,<,=三路CASE；每history_id等权，不按商品去重，无未出现方向补行。

证据：只读执行 3 行；结果 SHA-256 `19331705b285230712ad34c0764fff387144beb8a717599cd20cb7dfbff62773`。
Gold SHA-256 `94a0698059753d512d49f5fb74763facca9a38d31f84924725357947140a2a52`。

## B2-T2-016 — PASS

奖励CASE按NULL、<=0、<=10、<=20、else无缝分桶，边界不重叠；converted_at COUNT为转化标记，未知奖励SUM仍NULL。

证据：只读执行 4 行；结果 SHA-256 `040732d8df8aebb6676a7ead465e11840f80f825ca61e862b4b1fbbdda99b4ce`。
Gold SHA-256 `e2c32938f30623c0c172199133552fb8b35d9d5691822b68c83cb669723e154c`。

## B2-T2-017 — PASS

评价每物理行按评分统计，复合身份不被review_id单键折叠；正文NULL FILTER保空字符串，评分NULL组保留。

证据：只读执行 5 行；结果 SHA-256 `f648f0137f2fc4ea185ac6b2161d5522e0347ead60fba0b40ca679562d2bc9cc`。
Gold SHA-256 `921a1a3fdbb1ecff26e7fa462ed977ead88375c571ab9b63b6a1a51184a75ce0`。

## B2-T2-018 — PASS

budget>0先定义已知正预算集合，再按campaign_type分组COUNT/AVG；不按活动名称去重，无正预算类型不补0。

证据：只读执行 4 行；结果 SHA-256 `2f4a9647b14cce3b3a9ae206dbd33219bf40b250955985386c9b12aa2bbb2790`。
Gold SHA-256 `c1af6c6fb568349cf96db8dcf72f96f7143108651afda7a800649a581cba6a31`。

## B2-T2-019 — PASS

shipping_addresses.state作为自身州；地址主键COUNT、默认TRUE FILTER与不同customer_id分开，不将关联键数称人数。

证据：只读执行 27 行；结果 SHA-256 `0487752c5dfb17e09b440118aada35a4a00ae59467fda8a382d9331f654cc316`。
Gold SHA-256 `ac6ee8ae307c5ff7e51139c8a2d2a900e13ba84a27b9ebd79c17528d688bbbaf`。

## B2-T2-020 — PASS

品牌物理行COUNT按原country含NULL分组；同名不同ID分别计，品牌数倒序/国家NULL末稳定。

证据：只读执行 5 行；结果 SHA-256 `313eb683d31f0bdfb5e5f3f5d18b3c8be3a4b4f9ab7510540ed87ebdb26f5a0f`。
Gold SHA-256 `d938ffc0c75fc7473589bb93e2cfbf4e1a2dfaa47b871a140c2188b84425d5c9`。

## B2-T3-001 — PASS

下单窗口[2017-09-01,2018-03-01)，自然月COUNT订单与COUNT审核时间；全部状态，仅实际月份且升序。

证据：只读执行 6 行；结果 SHA-256 `cafc4f8e7b96a9413bdf3568a1dd1e2d1a39d6cc182bc04759c876b9e3426b22`。
Gold SHA-256 `1a5e6640f4b8eda1e9a91df7da8c247a7ddbdedf0ba5a92aea35cabcb8263bfc`。

## B2-T3-002 — PASS

2017/2018各1-6月有效订单先按年/月计数，同月内连接取交集，2018减2017；01-06字符串稳定，不补缺月。

证据：只读执行 6 行；结果 SHA-256 `11c9e36cb2a764ec89fe4a608b7a4a4d39cbce44650a543f67844a2afb49348a`。
Gold SHA-256 `29eb1ba63189f32ee470701d696f84e2206f77d0a23090344b15a9f829d57e3d`。

## B2-T3-003 — PASS

searched_at左闭右开一周；EXTRACT HOUR跨日期按一天小时汇总，COUNT日志/COUNT点击非NULL，不按会员。

证据：只读执行 24 行；结果 SHA-256 `f02281d351338bd75bbfe54de832f1df8965b3280d7b99192dcb7c4509133296`。
Gold SHA-256 `eee8272ac39a2542599bf15cbcb9583f08b4f99a5ce99cbaa5323f29e42b91e7`。

## B2-T3-004 — PASS

completed_at定义Q2归属，status=4；非申请时间、金额退款头字段，不额外连订单；月份升序。

证据：只读执行 3 行；结果 SHA-256 `32e70fa765aa1bc918a5c71f2211813b520449359e58b643007a12b83cf68d44`。
Gold SHA-256 `4af9d63338d4497f691649a73e3376912b296c94120735664c48971548e30feb`。

## B2-T3-005 — PASS

截止前按product_id以changed_at DESC/history_id DESC取最后，再筛已知上涨；不倒置过滤，午夜上界排除。

证据：只读执行 30 行；结果 SHA-256 `7812f7fae526232514a92f17529a63542f6c62fb1ccce396d0734051256dd407`。
Gold SHA-256 `c6323464d2397fa295d96d7a4d8dd4cc6d71a39b118b23376eb5afb27e0ba564`。

边界见证：latest-before-price-filter=PASS

## B2-T3-006 — PASS

snapshot_date<=6月30含当天，member_id非NULL，日期/快照ID取最新后才筛积分闭区间；不回退旧快照。

证据：只读执行 40 行；结果 SHA-256 `8b398418f27cf3b02afd5fe698fb64917ca596eb0d75373d5dcd50dd6c3737d1`。
Gold SHA-256 `24b756ac74ab13f07496d3961d74ebdaba9ae708c7e05f594852a161410dda4b`。

边界见证：snapshot-latest-before-points=PASS

## B2-T3-007 — PASS

moved_at[3月1,6月1)且类型2，自然月原数量SUM/流水COUNT；不翻转正负，不补缺月。

证据：只读执行 3 行；结果 SHA-256 `f88a49dc3eef50ada62e436613724e6ff29d884d40343eb6d6a663370f32a52e`。
Gold SHA-256 `c7fdf1b7d9aedc1678a2a20ace9a85b921883ce40ecee018b4027f02f2fabe3e`。

## B2-T3-008 — PASS

工单创建窗口定义月份；COUNT保留未解决，EPOCH/3600平均仅两时刻已知；未按状态过滤。实库存在逆序时刻，按题面原始差值可为负，不隐式纠正。

证据：只读执行 3 行；结果 SHA-256 `63e4576f4f4360d20c93682d725ab97c0d089a2a6b0f83d8e37a05ada99eae74`。
Gold SHA-256 `f8ee6c02a19aea3274bee88c64b3e74d4d27aa336ccfe6ebd329f7e429e104b2`。

## B2-T3-009 — PASS

六月重叠区间须合法start<=end；已知两日期，start<7月1且end>=6月1；包括6月30单日，排除7月1与逆序区间。初版缺合法区间条件；作者按独立反馈定向修订后，实库及逆序/单日/上界反例复验通过，反馈后修订接受。

证据：只读执行 4 行；结果 SHA-256 `b55b75520ab2f61a0fb4a441abba0c931ca0a8e54375052e2a8a3d0f20c9ef41`。
Gold SHA-256 `f45ee9890b6a747d6dac858c6082d8847e256f197d399512bb9e59a20ab93582`。

边界见证：invalid-activity-interval=PASS

## B2-T3-010 — PASS

paid_at为实际打款月，窗口[1月1,9月1)，不以status=3或账期起点替代；结算头净额一单一次。

证据：只读执行 8 行；结果 SHA-256 `ac382cb8772c5f5c7860cb5030261be44811620eae1fec4da866ff93959e30fa`。
Gold SHA-256 `0cc14aa0d148e11e857a307ad0a4b91bd103e520175e65a8728159558655a99d`。

## B2-T3-011 — PASS

注册Q1左闭右开且末登录>=7月1，NULL日期不匹配；注册/会员ID稳定35，状态不限。

证据：只读执行 35 行；结果 SHA-256 `27947e0f18298536d3b9be51ce19db69d0ed4aedc4174f2ef7381e8da8d68f8b`。
Gold SHA-256 `05d7fd8ea9d1db438e187f243b97301704b974aa3c808969f008d039222975c4`。

## B2-T3-012 — PASS

推荐创建窗口分母全记录；转化条件created<=converted<=created+7日双含且不限转化月份；NULL及提前转化不入分子。

证据：只读执行 3 行；结果 SHA-256 `76d525d1a85c93a0d49501eaab9c9b8e32da5011de8a868e2657a72a0a7aab8b`。
Gold SHA-256 `a9014c1cc21e22a7ec5eb33e87a092c02ea98eea58b8e0d95a72aadad15f4864`。

## B2-T3-013 — PASS

shipping_limit_date[8月20,8月27)作为截止日期来源，按date聚合COUNT商品行和SUM(price)；不误用购买时间或加运费。

证据：只读执行 6 行；结果 SHA-256 `cd85104875162109db18384f58ef455cbae6b4ad80fe7ce7a7c497cd2fa91bec`。
Gold SHA-256 `af41085ef233cc13c4a13b19c4dd12485d23a27e9b01df313b5e54a084effdcf`。

## B2-T3-014 — PASS

shipment发货窗口[6月1,8月1)且签收已知，按发货月COUNT/AVG完整EPOCH天；跨月送达保留，无status过滤。

证据：只读执行 2 行；结果 SHA-256 `719f45278514dcaafa6ee2e1885fb9873d4b2c07df8f7eefd812f2752f7ca8bd`。
Gold SHA-256 `f0d9682ee6a623529b60b2205ec7fd60586d54cbeef80343ed429aa559234e1c`。

## B2-T3-015 — PASS

先截取五月评价，再每order_id按创建时间/评价ID倒序取最后，最后才筛1分；完整键对并列有确定性，订单号30。

证据：只读执行 30 行；结果 SHA-256 `750f7da0075736ae0060cfcb3f1e52072d2c548a153858d0057492bd62de1bf2`。
Gold SHA-256 `699740a8e1cfde1ab79975860fe795b8d6b99502c21eb1e146dac04282aa537a`。

边界见证：latest-review-within-window=PASS

## B2-T3-016 — PASS

有效订单范围[2017-11-01,2018-05-01)先自然月COUNT，再按month-1 month真日历连接；无缺月跳跃，首月无上月不输出。

证据：只读执行 5 行；结果 SHA-256 `2c6ffaf6ac480fb745343b0b1297998fa95f8bc2e909b0f9b6414c2211ff5cd3`。
Gold SHA-256 `e1e5693bf35ad9e7ad28cdfc6a909f95aa676b9bf5133f94a4041d5c074f1d84`。

边界见证：calendar-adjacent-not-last-nonempty=PASS

## B2-T4-001 — PASS

订单事实沿customer_id精确定位客户州再地区唯一键；两LEFT JOIN保未知大区，有效Q2前段订单COUNT不被自然人多档案扩散。

证据：只读执行 5 行；结果 SHA-256 `e845b7f0793d369d517df2624b49f3fee3a8ddcae229e915399b9aa36f393239`。
Gold SHA-256 `996485d3b9cd8b62b83cb8aafaca255c7e8dfbdf4adef65d0c3f1160f0998159`。

## B2-T4-002 — PASS

商品行沿seller_id到卖家州/大区；LEFT维度保完整明细，COUNT/SUM(price)，不筛订单状态或取客户区。

证据：只读执行 5 行；结果 SHA-256 `1f53ea3f3fca8db9b3812099e7a39c7614522b734e1853614920eb75f7e11111`。
Gold SHA-256 `e494cffd8e12e18b654cbdb4fd55ce724fa6adb2aa4cc64a276155ae91ef0f70`。

## B2-T4-003 — PASS

inventory仓商品quantity SUM沿产品/翻译LEFT JOIN，英文NULL含原始NULL和缺翻译；HAVING库存>0排名15，数量/英文NULL末稳定。

证据：只读执行 15 行；结果 SHA-256 `3a64cd37bb9a835017c07d1b8e7f9cfa0fbe31855a4c7baec6e3945501d0d620`。
Gold SHA-256 `fead48dc29d413092473f416f558b290379690d0169b75f0e5c84541768021cf`。

## B2-T4-004 — PASS

member_levels为维度全集，冻结条件放LEFT JOIN ON；COUNT(m.member_id)让零会员等级=0，未知等级不额外成组。

证据：只读执行 4 行；结果 SHA-256 `2831b44a7fc3def65a24f9484ca308b3b16f4267f49f86abbf59b93e760927a5`。
Gold SHA-256 `e270a954440dc345533448d59084137ec95870fa0308231316a93fd3b0d08d82`。

## B2-T4-005 — PASS

已结束活动ID全集，fixed券条件放LEFT ON；COUNT券ID而非*保零券=0，同名活动各组。

证据：只读执行 40 行；结果 SHA-256 `48c6c9ba87d312745d6daadf8d18389a197c9b01ad945fcc3159915456398e68`。
Gold SHA-256 `e00d957783d6028bdc0c0ce89a69d61cdd65331f348850107b223b52e60e3f49`。

## B2-T4-006 — PASS

核销事件通过券/活动唯一键归属，used_at[3月1,6月1)，一核销一次SUM/COUNT；已知合计12排名，不连明细扩优惠。

证据：只读执行 12 行；结果 SHA-256 `e6e2cd590a6322d32d03468b9adbc562f0327c8dc8bd7cfcb8456eff3fd11f6b`。
Gold SHA-256 `64ad15aaf4e580ff45f0179870aa5896a065f7fb347e3bfd84d5d230ea07a14f`。

## B2-T4-007 — PASS

退款明细完整事件身份，原明细以(order_id,order_item_id)二键匹配；类目LEFT保NULL，SUM件数/金额，不按退款头或订单状态筛。

证据：只读执行 18 行；结果 SHA-256 `5b17b472227e02cc8f96ce3bd3e349568b6eeee1b0a9b6e30b91740c8d08e296`。
Gold SHA-256 `c0c82392dc946f5a784b98c12b819a28d8a000b8a4a741dca229d977b6b0bbe0`。

## B2-T4-008 — PASS

shipment事实WHERE发货非空，轨迹EXISTS地点非NULL只作存在标记；不复制多轨迹，仓库/地区LEFT保NULL，未签收仅delivered_at为空。

证据：只读执行 3 行；结果 SHA-256 `ff37261fdcf4c42ba21d6a0bda003a4279288cca2c1701ef69898ef7e136206e`。
Gold SHA-256 `54211dd44f423c47994ccc9c9e6aef6dfa0d6f5995dc01ccdf2312e9de5f2fbf`。

边界见证：tracking-exists-no-fanout=PASS

## B2-T4-009 — PASS

created_at Q1定义工单集，分类LEFT保缺失映射为NULL组；COUNT工单、AVG完整小时不受消息加权或status过滤。

证据：只读执行 6 行；结果 SHA-256 `e36f680ab35abaad4721618f3453b9dc2130fbd8f57faff6928eb7c262d9667b`。
Gold SHA-256 `dd4800ada1d68b1fe451548887eb091fa22dae450870d1323127cb9bffcb9006`。

## B2-T4-010 — PASS

紧急工单全集LEFT customer消息且条件放ON；COUNT message_id保零消息工单，消息数/工单ID排序25。

证据：只读执行 25 行；结果 SHA-256 `44f8018c19d96794a073514b6ceec2b1edf42b73525b277c6a0554332d67fb9d`。
Gold SHA-256 `b70c3299b559836e56a5ccb34b6769cf6bd7c09b902a61a838847201ba757cb0`。

## B2-T4-011 — PASS

品牌全集LEFT当前关联再LEFT存在产品；关联复合主键确保每品牌商品只计一次，无产品品牌=0，多当前品牌分别计。

证据：只读执行 60 行；结果 SHA-256 `a42f176d692ec0b078814ce126f0fa73db0aae36019e6d6b00d50f8828309f1d`。
Gold SHA-256 `32288cc02c5d1fafd1c7d405d3f58cc3e01463615b2db5b3fdfe62d679128236`。

## B2-T4-012 — PASS

调价history_id与真实商品匹配、原类目已知、变更Q2；COUNT含未知价记录而AVG只含两价已知，每条变动等权金额差。

证据：只读执行 62 行；结果 SHA-256 `9467ea1e7d0f9bc9396cfd6fd2d4bf2d618b7f120dcf9bc0d15009fe43b00c7b`。
Gold SHA-256 `6f7f412fa2a4d34b22b2329e5ff3fd425db65056a6b2e0ebd091cad53bdcbe53`。

## B2-T4-013 — PASS

五月全部订单沿事实customer_id取客户州；默认且同州地址用EXISTS避免多地址复制，SQL=不认NULL相同，不按自然人转移地址证据。

证据：只读执行 27 行；结果 SHA-256 `920841270b6a2ee7f61382c84f607bf7f1b393cc423fa56063b9b00952fb9bce`。
Gold SHA-256 `0ac1ec17a4e02e3c5e4b00f2fe27fe8e8cdfdc73f4c1350daab8a67b0ce1a4d4`。

边界见证：default-address-exists-fact-key=PASS

## B2-T4-014 — PASS

标签全集LEFT关联及正常会员；关联(member_id,tag_id)主键保每标签每会员一次；无正常会员COUNT主键=0。

证据：只读执行 6 行；结果 SHA-256 `d18d01838abe4c61ab1d92e74e29ef4435471427d3cdb0ae2807aa330e65103f`。
Gold SHA-256 `1f8e7b0818eb7b8ebeade7e393a954a49062d5de899d9871d085122b1739239b`。

## B2-T4-015 — PASS

referrer_member_id推荐人链到会员/等级，均真实匹配；COUNT推荐、COUNT(converted_at)转化，不取被推荐人等级。

证据：只读执行 4 行；结果 SHA-256 `b54a350213e04cbc343f3e5b24bc49b0fffdbc5d7a0e4d026b93c1cd0f71d94b`。
Gold SHA-256 `aa17e02b73b8072820a70c7acdb52c557468cf515cf6a1da1af501471aeda4ff`。

## B2-T4-016 — PASS

账期period_start[3月1,6月1)与status=3，结算头SUM(net_amount)/COUNT；卖家LEFT保未知州，不筛paid_at或连明细。

证据：只读执行 15 行；结果 SHA-256 `b5c3ef3038d71193098400cc485a36d3b6e980b74ed2a103a841bb1887e2457c`。
Gold SHA-256 `99eaeac6a19e222e90885b2e976552d9a9f11e4ab5c3f877eff01748c7356e5e`。

## B2-T4-017 — PASS

财务明细(settlement_id,order_id,order_item_id)通过订单二键匹配，商品类目LEFT；SUM已存gross/commission，不重算、无状态筛选，已知毛额16稳定。

证据：只读执行 16 行；结果 SHA-256 `889667d8b76cabe834a4b2ae170a511e78ba65eaf2965d2ba35f45d0e460216a`。
Gold SHA-256 `8b07dd3f0ec77dc36aac56b8e417739a750b1cf241cc363002f4e3d48f76817a`。

## B2-T4-018 — PASS

七月非NULL点击日志为完整事实，LEFT产品及翻译保缺失为NULL英文组；COUNT log不去重用户/产品，组数15稳定。

证据：只读执行 15 行；结果 SHA-256 `d2245f00cb87ef4993f5b357f792e12ef3c9e05694627badec3b567ae0f8b8d4`。
Gold SHA-256 `ed526cee8cf1a20404cde7697a8bbd2819348113826148c4519b90981e18ea4d`。

## B2-T4-019 — PASS

八月类型3流水JOIN仓库且仓州SP，LEFT商品保未知类目；原quantity SUM/流水COUNT，无订单关系替代。

证据：只读执行 37 行；结果 SHA-256 `19bad8c5aad5718ac5ac40cab43f94cb1b2a88702c1e1f6851db1ccaa45fb224`。
Gold SHA-256 `e79901166a34ed1da31f2f993ce04af85dfd843bec95b1ed005dc966c4c1a325`。

## B2-T4-020 — PASS

debit_card支付事件JOIN Q1全部订单，LEFT客户按order.customer_id取州；SUM payment_value/支付COUNT，不加运费、不人反连。

证据：只读执行 23 行；结果 SHA-256 `11cfc2cfed9ed7914ada9ade42665220e8ac5a68e5ff79e85a47a9e0195916e1`。
Gold SHA-256 `0ccaa4dfca07169430184fb6a3f585c38d3fb4fabf058cfb1944b24b73681e0b`。

## B2-T4-021 — PASS

评价身份(review_id,order_id)加类目DISTINCT消同类商品复制；跨类目分别计；低分<=2 FILTER、未知评分留分母，原NULL类目明确排除。

证据：只读执行 73 行；结果 SHA-256 `a4e8a4e8b24108a3021a9da168691f901667145ff233274ca27869eed02d74f9`。
Gold SHA-256 `4993437307643b086385d25d6d1f006688d3917d8a79b3c2ac11c82da449c3a0`。

边界见证：review-category-complete-key=PASS

## B2-T4-022 — PASS

活动ID与其商品复合主键到订单行，活动开始2018年，订单有效且时间>=start并<end+1日；每活动独立统计行与price，不伪称因果归因。

证据：只读执行 16 行；结果 SHA-256 `85d772f732663c3e13ed0a26fdc22cca8253b46b900676d2c920ac1eef9d2275`。
Gold SHA-256 `93d512efe7ea3386a56f5da31eb45c682d24ece0bacc296e8364b8cdc1645917`。

## B2-T5-001 — PASS

退款头status=4且申请上半年，退款明细同时对refund_id/order_id对齐，再原商品二键；当前品牌可多归属，卖家地区LEFT保NULL，SUM明细事实不改退款头金额。实库头/行订单不一致0。

证据：只读执行 135 行；结果 SHA-256 `2e4f4a723633149e3a31b9e8c78594638338a4660e9e614cf93d9f0d52ffb364`。
Gold SHA-256 `573972b94d9f4f561aa2fd8e3e745a4253a86b81ae244664085ac1e229ab5ca9`。

## B2-T5-002 — PASS

运输shipment_id为事实；订单customer_id→自然人→唯一会员作正常筛选，标签EXISTS防复；仓库维度LEFT与客户州分离，3-5月发货含全部订单状态。

证据：只读执行 3 行；结果 SHA-256 `1762fe96cf4714a96572433c6f72756e7014a07314c99791b1884ef62ef521cf`。
Gold SHA-256 `9c8665db65c5dd6e7b1521326b39f8f5f96662644c1505e06920b078bfc18568`。

## B2-T5-003 — PASS

核销链活动与核销member_id当前等级为维度，核销及订单都Q2且有效；每usage金额一次，组内COUNT DISTINCT order_id，不替换成买家会员。

证据：只读执行 158 行；结果 SHA-256 `ff46c023d5aef92c966c92cabc2cf176391350dff6f0385a0218f73aee462ce0`。
Gold SHA-256 `29316cd71dfd2572bfb320cff5c145fb117f30be7c14e0faba4f4ca0c04a9af0`。

## B2-T5-004 — PASS

结算明细到结算头、结算卖家、原明细完整键、产品均内连；大区/翻译LEFT保NULL，Q1账期金额直接财务事实；维度来源明确为结算卖家。

证据：只读执行 131 行；结果 SHA-256 `eb2b848144d6c5d2d95655c666180b0e5a3b88dfc0843e41dd1577fc699be49b`。
Gold SHA-256 `7b553274b371a197dff1bf098bfc74079e50d6cf0dee6bfd6e6c727d3ab1122f`。

## B2-T5-005 — PASS

工单六月创建，订单/客户真实匹配取订单客户区；消息预先按ticket_id聚合并以COALESCE对无消息补可证明0，工单COUNT不被消息条数加权。

证据：只读执行 5 行；结果 SHA-256 `17d0b6e9e17134456ba567d832336b2e988d669d2bc557616154cd23b6e8ff4b`。
Gold SHA-256 `f556b88e7392b445437ac6f57200ceb2c9a4e97b8b4f2fae88e099ada9caa187`。

## B2-T5-006 — PASS

八月点击log→会员/等级/真实产品内连，当前品牌LEFT；每日志×当前品牌关联计一次，无品牌NULL组，不按人去重或假设品牌唯一。

证据：只读执行 156 行；结果 SHA-256 `aa5ef019710becb2e4a33dafab710a5b460f1cbf275f71625886ce3807a80a08`。
Gold SHA-256 `483a1106d25047d84c59725a1e30ce3cd6f8d48222003dd5d0b0103ab505ce10`。

## B2-T5-007 — PASS

当前仓商品为事实全集，仓/商品真实匹配；九月类型2流水先按同复合键SUM再LEFT，匹配键标记区分无事实0和有事实全NULL，库存不补0。

证据：只读执行 207 行；结果 SHA-256 `975da30cc3d010b4ee75d241b27d73a0ab90b87e256f9a871294638d14f680e0`。
Gold SHA-256 `7d06f5a93afd78a26423524db0fbd0843012714f14db4251ad5ec55b687129cf`。

边界见证：inventory-no-fact-versus-unknown=PASS

## B2-T5-008 — PASS

四月全部订单作分母事实全集，商品/支付/退款三事实各先按订单SUM；通过事实键标记无事实0与存在未知NULL，地区LEFT保全，不被三重一对多放大。

证据：只读执行 5 行；结果 SHA-256 `270b2a2900fd2e0f92538bdcfabb366f796af45e7ef6e335557faf520967e135`。
Gold SHA-256 `91b18f3374e96f050c0065f1c8a571aa594ffbad79e43199df4f70ad8df41786`。

边界见证：multi-fact-preaggregate=PASS

## B2-T5-009 — PASS

推荐两端分别以src/dst身份匹配，等级取推荐人，HIGH_VALUE EXISTS取被推荐人；上半年推荐COUNT、转化时间标记、奖励原SUM含未转化。

证据：只读执行 4 行；结果 SHA-256 `dbfabd06f08bd640c6bfe07ef031f5503e30ec4107fa95b1fc11c94dc7895295`。
Gold SHA-256 `fd2eece6fad4bfd71f8cd90a7aff11168b3ee484c09f3ad63ce181906d211f6c`。

## B2-T5-010 — PASS

七月有效订单准确customer_id定位自然人/当前会员等级，商品明细JOIN真实商品，英文翻译LEFT；COUNT行/SUM(price+freight)，不冒充历史等级。

证据：只读执行 174 行；结果 SHA-256 `9241825de8679aa5d5124b50fcc4b02ed9b27f451e461d583dc1e1776b05d3a0`。
Gold SHA-256 `325c655d35c4b11edf34601de87a509e9aa5612bd6c6ba8fbecdffbddd376994`。

## B2-T6-001 — PASS

MG属于各订单客户记录州，先限定有效期间订单再按已知自然人COUNT，>=2为分子人群，全部下单人群为分母，100.0及NULLIF保证百分数/空集NULL。

证据：只读执行 1 行；结果 SHA-256 `4afcb19191eb3d6e9860bef0204174807268d38a5bb349752b456825699572db`。
Gold SHA-256 `ce28dab54df90439a5dbfee4036cc0c533554e17bd7f36c4893e665bde5aabd9`。

## B2-T6-002 — PASS

有商品明细有效订单集合由订单预聚合INNER定义；分子SUM所有price+freight，分母COUNT一单一行；非人数/行数，保两位、空集NULL。

证据：只读执行 1 行；结果 SHA-256 `a1c09db61443144b2b8a52fff3780f038c6666ca8672baafdfa2d54677d6492f`。
Gold SHA-256 `a83d1fbfd4d59951e96632bdaf9f1316fe0fcb99b3d25cd5658e33e85b5bcf70`。

## B2-T6-003 — PASS

有效Q1订单为全分母，LEFT客户保未知州，会员EXISTS身份标记不删非会员且不限status；计订单比例而非人数，100.0/NULLIF两位。

证据：只读执行 27 行；结果 SHA-256 `19b5d6dcf9f1149d9909e4f9bc6d9d673a0daa67ec6e8286311564be1da1d5e9`。
Gold SHA-256 `fb9a52c02d5adb703ead493230c113b1d62aff8e0c1eed9d606fad8abf68ffba`。

边界见证：member-denominator-complete=PASS

## B2-T6-004 — PASS

全部Q2订单，真实签收非空为分母；签收<=预计才分子，预计NULL留分母但不算准时；不依赖状态，不用1-迟到率。

证据：只读执行 1 行；结果 SHA-256 `dc8ecbc6ef120f484c19feb2a918058eea6bf434bf72159160c6f235cbfbe1ef`。
Gold SHA-256 `38250f15a6d87cc390645c0ca528ed2d7d7d4483cd21a36fc5d55f4363872b58`。

## B2-T6-005 — PASS

上半年有效商品行沿卖家州/大区LEFT；整体SUM freight / SUM(price+freight)，非单行比率平均；100.0两位，零/未知分母NULL，DDL两金额非空。

证据：只读执行 5 行；结果 SHA-256 `c768371f6240f9ae9d52ebb80506c2a1be972bdb622b402ecda4a0758139d102`。
Gold SHA-256 `9976dc76b69220c1b31a02859c64ead07cd938449b71596fbe4c88044a5da273`。

## B2-T6-006 — PASS

商品与核销先各按order_id聚合，再INNER到六月有效订单，限定二事实皆存在；总优惠/总GMV、不乘商品数量、不用SUM DISTINCT，零/未知分母NULL。

证据：只读执行 1 行；结果 SHA-256 `b9b2c51240b315306d75dae7249b743fb33e2aac3ccc0fed6b057effb46a5cf0`。
Gold SHA-256 `b91f8e231d32116ebc382c7f51e66eff59b9059c3b5dc6631dc54edd2f165d8b`。

## B2-T6-007 — PASS

全历史有效且身份/时间已知首单判定11月cohort，1月回访先自然人去重标记；LEFT保未回访分母，跨customer_id同一人不漏不重，空人群NULL。

证据：只读执行 1 行；结果 SHA-256 `d787ac60421822535d75642aa5d817f670cd19bf6a43ac0d82f556cdfce7313d`。
Gold SHA-256 `a3892a04811b25587aabc63f33f75bb265180ea8325814bd6c43c43667869087`。

边界见证：retention-global-first-and-person-dedupe=PASS

## B2-T6-008 — PASS

全历史首有效月后报告期3-5月分类，同首月全单NEW、后月RETURNING、缺身份/首月NULL；商品订单预聚合LEFT保无明细订单及已知0，不偷删金额/订单。

证据：只读执行 6 行；结果 SHA-256 `f38c210e3e3b37f7a2731eea39b5734b5014eea4ce645d130912b627921a7716`。
Gold SHA-256 `967163ae3e18c1d17ef6b73e3fed90dfa1e151c16c533a11d70564dcb227b91b`。

边界见证：dynamic-cohort-order-completeness=PASS

## B2-T6-009 — PASS

Q2创建工单JOIN分类定义，全部工单分母含未解决；完整EPOCH小时<=SLA且已解决为分子，未知SLA不成功。合同未要求非负时长，源数据逆序时间按原公式参与（不是自动纠正）。

证据：只读执行 6 行；结果 SHA-256 `f979a645244169b86463f837923bc2b4f0a07bc86ad22d040194563d784061cb`。
Gold SHA-256 `368f886657132f32e2ccbcad8fa46f8ef470961bc3324a815673d0ec9473884e`。

边界见证：sla-all-tickets-and-fractional-hours=PASS

## B2-T6-010 — PASS

结算明细按头账期5-7月，卖家州LEFT；SUM已存commission / SUM gross，加权整体比率而非AVG行率，0-1四位，不乘100，零未知分母NULL。

证据：只读执行 15 行；结果 SHA-256 `25fb4079695d760aa514188b86cf1f1bffe0a3e59c601b16e6c38607bb1cba97`。
Gold SHA-256 `f657c57089a2340c384cac8b055203fd2c2207476c5bcbcfbab938c712be9989`。

## B2-T6-011 — PASS

九月日志→真实会员/等级定义，COUNT事件全分母，非NULL clicked字段为分子；不JOIN产品改变点击分母，不按人去重，未出现等级不补0。

证据：只读执行 4 行；结果 SHA-256 `7318baaeeeb79f2e104dcad297673fb684232322507e7ca5209465d5e6d02f35`。
Gold SHA-256 `ab3f1110721cca1b7ae578f1b0500a444c946c72ef326ea66001949d71bb007e`。

## B2-T6-012 — PASS

全库MAX moved_at统一锚（不限仓或类型），近90日左开右闭类型2原数量；库存/流水各先按仓SUM，库存仓LEFT全集，90.0比例1位，无出库/零/未知NULL。

证据：只读执行 5 行；结果 SHA-256 `c50d0e0111717394a2210b01996319a333dc119b74870a49165df9cbdd15e831`。
Gold SHA-256 `0f57812444d3257e8c6ac157528ba61fdfcd2478d70f9176e2c8ddaa4d288419`。

边界见证：inventory-global-anchor-and-open-lower-bound=PASS

