# -*- coding: utf-8 -*-
"""
面试指南 Part 1 & Part 2：宏观架构设计与图算法/检索。
"""

from generate_full_guide import card, callout, table, code_block

def build_part1():
    q1_body = f"""
    {callout("direct", "一句话直答", "这是一个面向企业电商数仓的<strong>高可信 Text2SQL 智能问数系统</strong>，核心解决大模型在数十张表复杂拓扑下的“选错表、连错表、算错口径”三大痛点。基于 <strong>Java 21 + Spring Boot 3.5 + PostgreSQL 16</strong> 技术栈，构筑了图论斯坦纳树连表规划、AST 语法树白名单、EXPLAIN 静态代价防御及严谨的评测归因体系，在同模型对照下将执行准确率从初始基线的 <strong>62% 稳步提升至 96.00%</strong>。")}

    <h4>1. 痛点背景与为什么做这个项目</h4>
    <p>很多市面上的 Text2SQL Demo 只有 2–3 张平铺单表，直接把建表 DDL 全塞给大模型就能跑通。但在企业真实生产数仓中：</p>
    <ul>
      <li><strong>拓扑极其复杂</strong>：数仓有数十张业务表、数百个字段，全量 DDL 严重超出大模型上下文有效注意力，极易发生“长上下文注意力稀释”；</li>
      <li><strong>外键错连与笛卡尔积</strong>：面对多对多关联、桥接维度表，大模型纯靠概率连接极易走弯路或漏连桥接表；</li>
      <li><strong>静默语义错误危害极大</strong>：大模型写出的 SQL 语法完全合法，甚至能查出看似合理的数据，但由于业务口径偏差（如统计 GMV 算错运费），业务方根本无法察觉，导致决策失误。</li>
    </ul>

    <h4>2. 系统定位与明确的产品边界</h4>
    {table(["系统支持的能力（Scope）", "明确不支持的能力（Non-Goals）"], [
        ["只读数据分析（SELECT 查询）", "写操作、DML 增删改、DDL 建表改表"],
        ["单租户企业电商数仓拓扑", "通用多租户、行级权限系统（RLS）"],
        ["工程化提示词工程、图算法与语法防御护栏", "大模型底层参数微调（Fine-Tuning）"],
        ["PostgreSQL 16 深度特性与执行计划防御", "跨 MySQL / Oracle 等异构方言适配"]
    ])}

    {callout("interview", "面试官高频追问", "<strong>面试官问：为什么你的系统一再强调“只读（Read-Only）”？</strong><br><strong>高分回答：</strong>因为生成式大模型在本质上是概率模型，具有不可消除的幻觉风险，绝不能让不可信的模型输出直接拥有数据修改权。只读是严肃企业数据问数系统的<strong>安全底线与产品边界</strong>，而不是功能阉割。系统通过 AST 语法树白名单、数据库内核级只读角色（Role）和物理语句超时，确保最坏情况也只是读取数据，绝不会发生数据被篡改或删库的生产事故。")}
    """

    q2_body = f"""
    {callout("direct", "一句话直答", "整个请求遵循严格的<strong>九阶段单向流水线（Pipeline）</strong>：用户输入 → 实体识别与词典检索 → 斯坦纳树拓扑规划 → 指标口径与上下文注入 → LLM 确定性生成 → AST 语法树白名单审计 → EXPLAIN 执行计划代价探测 → 只读账号物理执行 → 结果集规整化输出。")}

    <h4>1. 端到端数据流与耗时拆解图解</h4>
    <div class="diagram-wrap">
      <div class="flow-diagram">
        <div class="flow-node">1. 自然语言输入<br><small style="color:#64748b">提问/改写</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">2. 词典检索<br><small style="color:#0284c7">Top-8 表筛选 (~12ms)</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">3. 斯坦纳树规划<br><small style="color:#0f766e">极小外键子图 (~2ms)</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">4. 提示词装配<br><small style="color:#8b5cf6">MDL 口径/枚举注入</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node danger">5. LLM 生成推理<br><small style="color:#b91c1c">主要耗时 (~12.0s)</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">6. AST 语法遍历<br><small style="color:#0284c7">只读白名单 (~4ms)</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">7. EXPLAIN 探测<br><small style="color:#0f766e">拦截笛卡尔积 (~6ms)</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node success">8. 只读库执行<br><small style="color:#047857">返回数据表格 (~20ms)</small></div>
      </div>
    </div>

    <h4>2. 各阶段数据形态转换详解</h4>
    <ul>
      <li><strong>输入阶段</strong>：接收中文文本（如“2018年销售额最高的5个州是哪些”）；</li>
      <li><strong>表检索阶段</strong>：通过应用层领域词典最大匹配，精准命中 <code>orders</code>, <code>order_items</code>, <code>customers</code> 3 张核心表，从 37 张表里完成有效降维；</li>
      <li><strong>拓扑规划阶段</strong>：图算法基于数仓外键图，计算连接这 3 张表的极小连通树，输出约束：<code>orders.customer_id = customers.customer_id AND orders.order_id = order_items.order_id</code>；</li>
      <li><strong>提示词注入阶段</strong>：将选出的表结构、销售额计算口径（依赖 <code>order_items.price</code> 求和）、枚举对齐规则（州名在库中为 <code>SP</code>, <code>RJ</code> 代码）组装入 Prompt；</li>
      <li><strong>代码审计与拦截阶段</strong>：LLM 生成 SQL 后，先经过 JSqlParser 进行抽象语法树遍历，再向 PostgreSQL 发送 <code>EXPLAIN (FORMAT JSON)</code> 检测计划树代价，确认无全表笛卡尔积；</li>
      <li><strong>物理执行阶段</strong>：使用只读连接池执行 SQL，限制 10s 超时和 200 行上限，最终返回数据 JSON。</li>
    </ul>

    {callout("interview", "面试官高频追问", "<strong>面试官问：这条链路里哪一步最容易错？哪一步最消耗系统资源？</strong><br><strong>高分回答：</strong><br>1. <strong>最容易出错的是“语义对齐阶段”</strong>：SQL 语法报错是低级错误，最危险的是 SQL 能跑但口径算错（比如销售额是否包含运费、状态是否过滤了已取消订单）。这必须靠统一的指标定义层与提示词规范来对齐；<br>2. <strong>最消耗资源的是“大模型生成阶段”</strong>：检索、图算法、语法校验和数据库执行全在 20ms 以内，而大模型推理生成耗时高达 10–12 秒，消耗数千 Token。因此后端架构的优化核心永远是<strong>“前置做足过滤与缓存，尽量避免无效的模型调用”</strong>。")}
    """

    q3_body = f"""
    {callout("direct", "一句话直答", "在严肃的企业数据问数场景下，<strong>“纯自主 ReAct Agent”在商业落地中是一个高成本、高延迟、低准确率的伪需求</strong>。工业界落地的真实解法是 <strong>Workflow as Agent（确定性流水线编排）</strong>——用代码、图算法和规则收敛一切确定性边界，仅把大模型约束在核心的代码泛化生成上。")}

    <h4>1. 为什么 ReAct Agent 在 Text2SQL 场景下会崩溃？</h4>
    <p>很多开源框架（如 LangChain / AutoGen）提倡让大模型像人一样“思考-行动-观察”自主循环：大模型自己决定调 <code>list_tables</code>，看完结果再调 <code>describe_table</code>，再调 <code>sample_data</code>，最后写 SQL。</p>
    <p>这种纯自主 Agent 存在三大致命缺陷：</p>
    <ul>
      <li><strong>累积幻觉导致准确率暴跌</strong>：大模型每一次工具调用的输出都会作为下一次的输入。多轮循环下只要某一步选错表，后面的思考链路就会彻底跑偏，实测端到端准确率比确定性流水线低近 20 个百分点；</li>
      <li><strong>延迟完全失控</strong>：一次模型推理耗时 2~3 秒，ReAct 循环 5 轮，用户端到端等待时间直接超过 30–40 秒，在线业务根本无法接受；</li>
      <li><strong>Token 成本翻倍暴增</strong>：每一轮都要重复携带不断膨胀的上下文历史，一次问数的 Token 消耗是流水线的 3–5 倍。</li>
    </ul>

    <h4>2. Workflow as Agent 的工程取舍</h4>
    <div class="diagram-wrap">
      <p style="font-weight:700; margin-bottom:8px;">对比：ReAct 循环 vs 确定性流水线</p>
      <div style="text-align:left; max-width:700px; margin:0 auto; font-size:0.88rem;">
        <p>❌ <strong>ReAct 模式</strong>：LLM (思考) ➔ 查表工具 ➔ LLM (再思考) ➔ 查字段工具 ➔ LLM (试错) ➔ 产生幻觉 ➔ 耗时 40s (成本 ¥0.02)</p>
        <p>✅ <strong>Pipeline 模式</strong>：词典检索 (15ms) ➔ 斯坦纳树拓扑 (2ms) ➔ 指标注入 ➔ LLM (单次生成) ➔ AST护栏 ➔ 耗时 2~12s (成本 ¥0.002)</p>
      </div>
    </div>

    {callout("interview", "面试官高频追问", "<strong>面试官问：如果每一步都用代码写死，那大模型的核心价值在哪里？你们系统还能叫 Agent 吗？</strong><br><strong>高分回答：</strong><br>Agent 的本质是用智能体解决复杂非结构化环境的决策问题。在自然语言问数中，大模型最大的不可替代性在于<strong>“跨语意泛化理解”</strong>——用户会输入千奇百怪的中文口语、行业俚语和复杂条件组合，传统 SQL 模板匹配对此完全无能为力。大模型负责把非结构化自然语言翻译为抽象逻辑，而后端工程负责用图算法和语法护栏为其构筑物理安全网与拓扑约束。<strong>“把确定性的交给工程，把发散性的交给模型”</strong>，这才是工业级高可靠 Agent 的唯一出路。")}
    """

    return f"""
    <div class="section-group" id="sec-cat1">
      <div class="section-header">
        <h2>一、系统架构与宏观设计</h2>
        <span class="section-tag">架构与理念</span>
      </div>
      {card("q01", "cat1", 3, ["项目介绍", "架构选型", "系统边界"], "Q01 如何用一句话和两分钟向面试官介绍你的 Text2SQL 项目？", q1_body)}
      {card("q02", "cat1", 3, ["数据流", "耗时分解", "数据形态转换"], "Q02 从用户输入到最终返回表格，系统底层的数据流是怎样的？", q2_body)}
      {card("q03", "cat1", 3, ["Workflow vs ReAct", "架构权衡", "Agent认知"], "Q03 为什么选择确定性流水线（Pipeline），而不是完全自主的动态 ReAct Agent？", q3_body)}
    </div>
    """

def build_part2():
    q4_body = f"""
    {callout("direct", "一句话直答", "全量 37 张业务表的 DDL 超过 7,400 字符，直接全量塞入 Prompt 会引发严重的<strong>长上下文注意力稀释（Lost in the Middle）</strong>与选表幻觉；系统设计了<strong>领域词典检索与列剪枝</strong>，将 DDL 字符量大幅压缩 <strong>82.7%</strong> 至 Top-8 表，表全召回率达 <strong>99.00%</strong>，平均召回率达 <strong>99.80%</strong>。")}

    <h4>1. 为什么不能一股脑把所有表都给大模型？</h4>
    <ul>
      <li><strong>长上下文注意力的 U 型曲线缺陷</strong>：大语言模型（包括 GPT-4、DeepSeek）对 Prompt 开头和结尾的信息敏感度极高，而中间冗长的表定义极易被“视而不见”；</li>
      <li><strong>同名与同义字段混淆</strong>：真实数仓有大量相似命名的表与字段（如 <code>customers</code>、<code>customer_segments</code>、<code>customer_level</code>），表越多，模型猜错主表的概率呈指数级上升；</li>
      <li><strong>Token 与推理成本浪费</strong>：输入 Token 增加数倍，直接拉高每一次请求的成本与 P95 响应延迟。</li>
    </ul>

    <h4>2. 检索剪枝三步走策略</h4>
    <ol>
      <li><strong>领域词典最大匹配</strong>：将中文问题分词后与业务术语词典做最长匹配，提取命中的核心候选表集合；</li>
      <li><strong>检索评分与 Top-8 截断</strong>：结合词权重与实体提及度对候选表打分，取前 8 张相关度最高的表；</li>
      <li><strong>连通性修复与外键桥接</strong>：如果选出的核心表在拓扑图上属于孤立点，自动基于外键关系寻找距离最短的中间桥接表加入集合，防止连表断裂。</li>
    </ol>
    """

    q5_body = f"""
    {callout("direct", "一句话直答", "<strong>结构化数据库的表检索与非结构化文档问答有本质区别</strong>：向量检索存在致命的<strong>“语义漂移（Semantic Drift）”</strong>，且引入外部向量模型与数据库依赖；而应用层领域语义词典匹配具备<strong>“零外部依赖、确定性可控、15ms 极低耗时”</strong>的压倒性工程优势。")}

    <h4>1. 向量检索（Embedding RAG）在表检索上的三大硬伤</h4>
    {table(["对比维度", "向量检索（Embedding / pgvector）", "领域词典匹配（系统当前方案）"], [
        ["语义精确度", "<strong>存在语义漂移</strong>：例如问“复购率”，向量可能会相似到“退款率”或“用户活跃”，但在数仓中差一张表就是错", "<strong>绝对精准</strong>：业务概念直接绑定目标物理表与字段，无语义模糊"],
        ["系统依赖与稳定性", "依赖外部 Embedding API 或模型、需要维护 pgvector 向量数据库，网络波动会导致雪崩", "<strong>依赖为零</strong>：纯 Java 内存数据结构匹配，无网络开销与外部挂掉风险"],
        ["检索耗时与开销", "向量化 + 相似度计算通常耗时 50~150ms，产生额外的向量推理成本", "纯内存字符串匹配，<strong>耗时 &lt; 15ms</strong>，成本为零"],
        ["维护与可解释性", "向量距离是黑盒，搜不出某张表时无法解释原因，难以做有针对性的微调", "<strong>完全白盒化</strong>：词典 YAML 配置维护，支持热更新与精准人工纠偏"]
    ])}

    {callout("interview", "面试官高频追问", "<strong>面试官问：如果业务新上线了 20 张数仓表，词典怎么跟进维护？人工维护成本不重吗？</strong><br><strong>高分回答：</strong>在成熟的大数据数仓体系中，每个业务域都有统一的<strong>元数据管理中心（Data Catalog）</strong>。词典不需要数据开发逐行手写，我们设计了元数据定时同步任务：每天从 PostgreSQL 的 <code>information_schema</code>、系统表注释（<code>pg_description</code>）以及指标系统提取表名、字段名及注释，自动编译更新领域词典。冷门生僻词支持业务方在界面动态打标热载入。")}
    """

    q6_body = f"""
    {callout("direct", "一句话直答", "大模型在多表关联时最核心的痛点是<strong>“缺乏全局拓扑感知”</strong>：面对 37 张表组成的网状外键图，模型依靠概率生成极易出现<strong>“绕远路连接、误选同名弱关联、漏连桥接表引发笛卡尔积”</strong>三大致命问题。")}

    <h4>1. 真实多表关联常见翻车场景</h4>
    <ul>
      <li><strong>同名外键歧义</strong>：例如用户问“某地区的卖家销售额”，大模型看到 <code>customers</code> 有 <code>customer_city</code>，<code>sellers</code> 也有 <code>seller_city</code>，极易把卖家所属城市与买家收货城市关联错；</li>
      <li><strong>桥接表缺失（笛卡尔积隐患）</strong>：例如统计“每个会员等级的卖家数量”，大模型不知道 <code>customer_level</code> 必须先连 <code>customers</code>，再连 <code>orders</code>，再连 <code>sellers</code>，直接写出 <code>FROM customer_level, sellers</code> 造成全表笛卡尔积；</li>
      <li><strong>冗余连表死锁</strong>：当多个路径均能连通时，大模型可能把两组路径的表全部写进 JOIN，生成一个包含 5~6 张表的无意义庞大语句。</li>
    </ul>
    """

    q7_body = f"""
    {callout("direct", "一句话直答", "单源加权最短路（Dijkstra）只能计算“以某张根表为起点的单点最短路”，在多目标表分布在拓扑两端时会产生<strong>局部贪心死锁</strong>，强制并入无关业务表形成伪支路；而<strong>斯坦纳最小生成树（Steiner Tree）</strong>是图论中连接指定终端节点的全局极小连通子图，彻底消除了伪支路，直接将 3 表关联推到 <strong>100%</strong>，4+ 表复杂关联推到 <strong>90.91%</strong>。")}

    <h4>1. 图论底层原理：为什么 Dijkstra 会惨败？</h4>
    <p>假设用户提问涉及两张核心表：<strong>会员等级表（A）</strong> 与 <strong>卖家表（B）</strong>。在数仓中，它们仅需通过 <strong>用户表（C）</strong> 即可打通：<code>A ↔ C ↔ B</code>（仅需 2 条连线）。</p>
    
    <div class="diagram-wrap">
      <p style="font-weight:700;">Dijkstra 局部贪心死锁 vs 斯坦纳树全局最优对比</p>
      <div style="font-size:0.88rem; text-align:left; max-width:680px; margin:0 auto;">
        <p class="callout warning">❌ <strong>Dijkstra 局部贪心做法</strong>：<br>
        1. 规划器以订单事实表（O）为根；<br>
        2. 计算 O 到 A 的最短路：<code>O ↔ C ↔ A</code>；<br>
        3. 计算 O 到 B 的最短路：<code>O ↔ 明细表(I) ↔ B</code>；<br>
        4. 路径求并集：最终生成 <code>A ↔ C ↔ O ↔ I ↔ B</code>（共 5 张表！）。<br>
        <strong>结果</strong>：把毫无关系的订单表和明细表当成中继强制塞入，大模型看到这 5 张表直接写出复杂的错误 JOIN，甚至返回空结果！</p>
        
        <p class="callout success">✅ <strong>斯坦纳树（Steiner Tree）全局极小规划</strong>：<br>
        1. 将目标表 A 和 B 设为终端节点（Terminals）；<br>
        2. 全局搜寻连接所有终端节点的极小权重连通子图；<br>
        3. 发现仅需通过中间点 C 即可闭环，剪除所有与目标无关的叶子节点；<br>
        4. 最终输出极小子图：<code>A ↔ C ↔ B</code>（仅 3 张表）。<br>
        <strong>结果</strong>：多余的伪支路被彻底斩断，大模型 100% 连对外键！</p>
      </div>
    </div>

    <h4>2. 工程实现：NP-hard 难题如何在 1.5ms 内秒级求解？</h4>
    <p>图论中严格的斯坦纳树是 NP-hard 问题，若使用穷举法，在大图上计算复杂度极高。我们在工程中采用了经典的 <strong>Takahashi-Matsuyama 启发式 2-近似算法</strong>：</p>
    <ul>
      <li><strong>步骤一（终端度量闭包）</strong>：仅针对检索召回的 2~4 张目标终端表计算成对最短外键距离；</li>
      <li><strong>步骤二（贪心树生长）</strong>：从起始点出发，每轮在剩余目标表中寻找距离当前树最近的节点，合并最短路并入树；</li>
      <li><strong>步骤三（非终端叶子剪枝）</strong>：递归修剪树中所有度数为 1 的非目标中间节点，确保子图无多余支路。</li>
    </ul>
    <p>在数仓拓扑规模下，该算法运行耗时稳定在 <strong>1~2 毫秒</strong>，在复杂度与最优解之间达成了完美的工程折中。</p>
    """

    return f"""
    <div class="section-group" id="sec-cat2">
      <div class="section-header">
        <h2>二、Schema 检索与多表拓扑（图算法深度）</h2>
        <span class="section-tag">图论与检索</span>
      </div>
      {card("q04", "cat2", 2, ["Schema检索", "列剪枝", "注意力稀释"], "Q04 面对数十张业务表，为什么不能直接把全量 DDL 塞给大模型？你们是如何做 Schema 检索的？", q4_body)}
      {card("q05", "cat2", 3, ["词典检索", "向量RAG对比", "语义漂移"], "Q05 为什么在表检索中采用领域词典最大匹配，而不是流行的向量语义检索（Embedding RAG）？", q5_body)}
      {card("q06", "cat2", 2, ["多表关联", "笛卡尔积", "外键歧义"], "Q06 大模型在多表关联（Multi-table Join）时最容易出现什么问题？根本原因是什么？", q6_body)}
      {card("q07", "cat2", 3, ["斯坦纳树", "Dijkstra局限", "图论2-近似"], "Q07 为什么单源加权最短路（Dijkstra）不能解决多表 Join，而必须引入斯坦纳树（Steiner Tree）近似算法？", q7_body)}
    </div>
    """

print("Parts 1 & 2 ready")
