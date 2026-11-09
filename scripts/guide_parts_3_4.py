# -*- coding: utf-8 -*-
"""
面试指南 Part 3 & Part 4：语义层/Prompt工程 与 安全防御/AST护栏。
"""

from generate_full_guide import card, callout, table, code_block

def build_part3():
    q8_body = f"""
    {callout("direct", "一句话直答", "<strong>绝不能让大模型在 Prompt 里“猜”业务口径！</strong>系统引入了工业级 <strong>MDL（Modeling Definition Language）语义层</strong>，将业务指标的计算公式、依赖表、过滤口径集中声明式管理，在检索阶段动态注入上下文，彻底解耦了复杂的商业逻辑与底层物理表结构。")}

    <h4>1. 为什么“口径不一致”是 Text2SQL 最大的杀手？</h4>
    <p>在企业中，一个简单的词汇在不同部门有着完全不同的含义：</p>
    <ul>
      <li><strong>GMV（成交额）</strong>：财务口径要求“扣除优惠券、扣除退款、扣除运费”；运营口径要求“包含运费且不论是否取消”；</li>
      <li><strong>复购率</strong>：分子是“二次购买人数”还是“二次购买订单数”？分母是“全量注册用户”还是“当期下单用户”？</li>
      <li><strong>有效订单</strong>：是否需要剔除 <code>order_status = 'canceled'</code> 或 <code>'unavailable'</code>？</li>
    </ul>
    <p>如果不做统一的语义层治理，大模型每次生成都会像掷骰子一样随机使用一种口径，导致数据团队与业务团队产生严重的信任危机。</p>

    <h4>2. MDL 语义层声明与动态注入机制</h4>
    <p>系统通过集中式指标模型管理通用口径规范，示例如下：</p>
    {code_block("""# 指标语义层定义 (MDL)
metrics:
  - name: gmv
    description: "平台总成交金额（包含商品金额与实际运费，排除已取消订单）"
    sql_formula: "ROUND(SUM(oi.price + oi.freight_value)::numeric, 2)"
    required_tables: [orders, order_items]
    filter: "orders.order_status NOT IN ('canceled', 'unavailable')"
""")}
    <p>当用户提问命中“GMV”或“销售额”时，检索层会自动将上述声明注入 Prompt，强制大模型严格照抄标准的聚合公式与过滤条件，从根源上消除了指标幻觉。</p>
    """

    q9_body = f"""
    {callout("direct", "一句话直答", "采用<strong>分层渐进式注入 + 末尾近因效应（Recency Effect）</strong>的提示词结构：系统角色与四步思考契约（置顶）→ 候选表 DDL 与枚举映射（居中）→ MDL 业务指标口径（居中）→ 输出格式契约与 LIMIT 约束（紧随其后）→ <strong>用户自然语言问题（置于最末尾）</strong>。")}

    <h4>1. 为什么必须将“用户问题”放在 Prompt 的最末尾？</h4>
    <p>根据大模型注意力机制研究（U 型注意力曲线），当 Prompt 长度达到数千 Token 时，模型对<strong>最开头（Primacy Effect）</strong>和<strong>最末尾（Recency Effect）</strong>的内容保留度最高，而对中间信息最容易淡化。</p>
    <div class="diagram-wrap">
      <p style="font-weight:700;">Prompt 结构布局与模型注意力分布</p>
      <div style="font-size:0.88rem; text-align:left; max-width:680px; margin:0 auto;">
        <p>🔝 <strong>[开头 · 高注意力区]</strong>：System Prompt（系统角色定义、只输出 SELECT、四步思考法）</p>
        <p>📦 <strong>[中间 · 知识参考区]</strong>：Top-8 表 Schema DDL、枚举映射字典（州代码对应关系）、MDL 指标口径</p>
        <p>🔒 <strong>[后置 · 强约束区]</strong>：格式契约（禁止输出 Markdown 解释、必须 LIMIT 200、除零防护）</p>
        <p>🎯 <strong>[末尾 · 激活触发区]</strong>：<strong>用户原始问题</strong>（最后被模型读取，直接指导输出第一行 Token）</p>
      </div>
    </div>
    <p>这种结构能让大模型在“脑中”装填完所有的业务资料与纪律规范后，以最强烈的注意力对焦在具体问题上，极大抑制了答非所问与格式失控。</p>
    """

    q10_body = f"""
    {callout("direct", "一句话直答", "Text2SQL 是严肃的<strong>确定性代码生成与数据检索任务</strong>，不需要文学创作的发散想象；<strong>将 Temperature 设为 0 可以最大程度压制采样随机性</strong>，使同一输入产生稳定、确定、可复现的 SQL 输出。")}

    {callout("interview", "面试官高频追问", "<strong>面试官问：Temperature 设为 0，大模型的输出就真的 100% 确定了吗？为什么复跑偶尔还会出现 1~2 题波动？</strong><br><strong>高分回答：</strong><br>温度设为 0 并不等同于绝对的数学确定性。在真实工程中，依然存在三种微观波动源：<br>1. <strong>服务端批处理（Continuous Batching）的不确定性</strong>：云端大模型服务在并发调度时，每次请求被打包进 GPU 的 Batch 大小不同，浮点数矩阵并行相加的顺序微弱差异会导致末位精度的漂移；<br>2. <strong>模型版本灰度微调</strong>：云厂商线上模型常年进行后台细微调优与对齐；<br>3. <strong>Top-K 与核采样的截断边界</strong>：多分支概率几乎完全相等时可能发生平局。")}
    """

    return f"""
    <div class="section-group" id="sec-cat3">
      <div class="section-header">
        <h2>三、语义工程与 Prompt 注入（模型对齐）</h2>
        <span class="section-tag">Prompt与对齐</span>
      </div>
      {card("q08", "cat3", 3, ["MDL语义层", "指标对齐", "解耦架构"], "Q08 业务指标（如 GMV、复购率、客单价）口径歧义在 Text2SQL 中如何解耦与对齐？", q8_body)}
      {card("q09", "cat3", 2, ["Prompt结构", "近因效应", "注意力曲线"], "Q09 你们的 Prompt 是如何设计的？如何抑制大模型的长上下文遗忘与格式幻觉？", q9_body)}
      {card("q10", "cat3", 2, ["温度参数", "确定性边界", "浮点数波动"], "Q10 为什么将模型生成采样温度（Temperature）设为 0？大模型真能实现确定性输出吗？", q10_body)}
    </div>
    """

def build_part4():
    q11_body = f"""
    {callout("direct", "一句话直答", "绝不能信任大模型输出的任何代码！系统构筑了<strong>“应用层 AST 语法白名单 → 执行前 EXPLAIN 代价探测 → 数据库内核级只读角色（Role）隔离”</strong>的三层纵深防御体系（Defense in Depth），并在自动化回归测试中注入 <strong>53 条对抗渗透用例全量拦截验证</strong>。")}

    <h4>1. 三层纵深防御架构图解</h4>
    <div class="diagram-wrap">
      <div class="flow-diagram">
        <div class="flow-node danger">LLM 生成 SQL<br><small style="color:#b91c1c">不可信外部输入</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">第 1 层：JSqlParser AST<br><small style="color:#0284c7">严格只读白名单 / 语法树遍历</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node">第 2 层：EXPLAIN 计划探测<br><small style="color:#0f766e">拦截全表笛卡尔积与超时代价</small></div>
        <div class="flow-arrow">➔</div>
        <div class="flow-node success">第 3 层：内核只读角色<br><small style="color:#047857">text2sql_ro + 10s物理超时</small></div>
      </div>
    </div>

    <h4>2. 三层防线的职责边界</h4>
    <ul>
      <li><strong>第一道防线（AST 语法树白名单）</strong>：在 JVM 内存中将 SQL 解析为 AST 语法树，拦截一切非 SELECT 语句、拦截多语句分号拼接、拦截系统表/表函数越权调用；</li>
      <li><strong>第二道防线（EXPLAIN 代价防御）</strong>：将 SQL 送往数据库仅做静态“彩排”，探测是否存在无关联条件的 Nested Loop（笛卡尔积），防止慢查询拖垮生产库；</li>
      <li><strong>第三道防线（数据库内核权限硬约束）</strong>：使用无任何写权限的 <code>text2sql_ro</code> 专属连接池执行，设置 <code>statement_timeout = '10s'</code>。即使应用层两道代码检查全部失守，物理连接依然强制报错拒绝。</li>
    </ul>
    """

    q12_body = f"""
    {callout("direct", "一句话直答", "<strong>用正则表达式过滤 SQL 危险关键字是典型的防君子不防小人</strong>！大语言模型具有无限的编码变种生成能力，正则极易被注释混淆、大小写变异、CTE 嵌套和系统命名空间完全击穿；必须使用 <strong>JSqlParser 将 SQL 解析为抽象语法树（AST）</strong>，通过 Visitor 模式逐个节点进行类型安全的白名单遍历。")}

    <h4>1. 真实渗透中，正则表达式被击穿的三个典型漏洞</h4>
    {table(["攻击场景", "渗透示例", "为什么正则表达式会失效", "AST 如何完美拦截"], [
        ["系统表命名空间逃逸", "<code>SELECT * FROM pg_catalog.pg_user</code>", "正则通常匹配单独的单词 <code>pg_user</code>，带限定名时正则匹配漏判", "AST 的 <code>Table.getFullyQualifiedName()</code> 解析出命名空间，白名单直接拦截"],
        ["CTE 隐藏数据删除", "<code>WITH deleted AS (DELETE FROM orders RETURNING *) SELECT * FROM deleted;</code>", "正则看到开头是 <code>SELECT</code> 或包含 <code>WITH</code> 判为安全读取", "AST 递归遍历 CTE 的 <code>WithItem</code>，检测到内部节点为 <code>Delete</code>，直接抛出安全异常"],
        ["表函数与代码执行", "<code>SELECT * FROM dblink('host=...', 'DROP TABLE users')</code>", "正则难以穷举数据库数十种内置的底层远程连接与文件读取表函数", "AST 的 <code>Function</code> 访问器校验函数名白名单，非聚合函数直接拦截"]
    ])}

    <h4>2. AST Visitor 核心遍历代码逻辑</h4>
    {code_block("""// JSqlParser 抽象语法树遍历核心逻辑
Statement stmt = CCJSqlParserUtil.parse(sql);
if (!(stmt instanceof PlainSelect select)) {
    throw new SecurityException("只允许单条 SELECT 语句，禁止多语句或复合操作");
}
select.accept(new SelectVisitorAdapter() {
    @Override
    public void visit(Table table) {
        String tableName = table.getName().toLowerCase();
        if (!ALLOWED_TABLES.contains(tableName)) {
            throw new SecurityException("越权访问未授权表: " + tableName);
        }
    }
});
""")}
    """

    q13_body = f"""
    {callout("direct", "一句话直答", "语法正确的 SQL 也可能拖垮数据库！在真正执行前，系统调用 PostgreSQL 原生 <code>EXPLAIN (FORMAT JSON)</code> 获取静态执行计划树，递归检测各层节点的代价（Cost）与连接算子，<strong>重点探测并硬拦截无索引连接条件的嵌套循环（Nested Loop Without Index Cond）</strong>，杜绝全表笛卡尔积。")}

    <h4>1. 为什么“笛卡尔积”必须在执行前硬拦截？</h4>
    <p>数仓中的核心事实表（如订单明细表）通常在数百万行级别。大模型如果在连表时遗漏了 <code>ON</code> 关联条件，两张大表就会发生笛卡尔乘积，输出行数膨胀为数万亿级别，直接导致数据库 CPU 100% 满载、磁盘 I/O 耗尽，拖垮整库业务。</p>

    <h4>2. 执行计划树如何识别“危险的笛卡尔积”？</h4>
    <p>并非所有的 <code>Nested Loop</code> 都是笛卡尔积。如果外表只有 1 行，内表走主键索引（Index Cond），这是最高效的查询方式之一。因此静态分析器必须做到<strong>精准识别，严禁误杀</strong>：</p>
    <ul>
      <li><strong>危险特征（硬拦截）</strong>：算子为 <code>Nested Loop</code>，且子节点中<strong>没有任何 <code>Index Cond</code> 或 <code>Join Filter</code> 条件</strong>，两表直接进行全表扫描配对；</li>
      <li><strong>安全特征（正常放行）</strong>：算子带有明确的 <code>Index Cond: (orders.id = items.order_id)</code>，属于标准的索引关联下推。</li>
    </ul>
    """

    q14_body = f"""
    {callout("direct", "一句话直答", "<strong>任何纯应用层的软件安全检查都无法排除存在未知的 0-day 漏洞或方言解析盲区</strong>；引入数据库内核级的 <code>text2sql_ro</code> 只读角色，是用物理隔离兜底的<strong>纵深防御原则</strong>，确保最坏情况下即便应用层发生漏洞穿透，数据库也无法发生数据损坏。")}

    <h4>1. 数据库只读账号的最小权限原则配置</h4>
    {code_block("""-- 生产级只读角色初始化脚本
CREATE ROLE text2sql_ro LOGIN PASSWORD 'strong_password';
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM text2sql_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO text2sql_ro;
-- 物理强制单语句 10 秒超时，防止慢查询占满连接池
ALTER ROLE text2sql_ro SET statement_timeout = '10000';
-- 物理限制内存工作区大小
ALTER ROLE text2sql_ro SET work_mem = '16MB';
""")}

    {callout("interview", "面试官高频追问", "<strong>面试官问：如果你们系统既有 AST 拦截、又有只读角色，为什么还要做 10 秒超时？</strong><br><strong>高分回答：</strong><br>只读角色只保证“不能修改数据”，但并不保证“不能耗尽服务器资源”。恶意的黑客可以构造一条极其复杂的合规只读 SQL（例如超多层子查询关联），在百万级大表上做全量排序与哈希计算，持续占用数据库 CPU 和连接资源，引发<strong>拒绝服务攻击（DoS）</strong>。10 秒强制物理超时（<code>statement_timeout</code>）是防止慢查询与死锁拖死数据库连接池的最终物理屏障。")}
    """

    return f"""
    <div class="section-group" id="sec-cat4">
      <div class="section-header">
        <h2>四、安全防御与执行护栏（纵深防御体系）</h2>
        <span class="section-tag">安全与AST</span>
      </div>
      {card("q11", "cat4", 3, ["三层护栏", "安全防御", "53条渗透用例"], "Q11 绝不能信任大模型输出！你们是如何防范越权删库与 SQL 注入渗透的？", q11_body)}
      {card("q12", "cat4", 3, ["JSqlParser", "AST语法树", "正则穿透缺陷"], "Q12 为什么要用 JSqlParser 做 AST 语法分析树遍历，而不用正则表达式做关键字过滤？", q12_body)}
      {card("q13", "cat4", 3, ["EXPLAIN计划", "笛卡尔积拦截", "Index Cond"], "Q13 如何利用 PostgreSQL 的 EXPLAIN JSON 执行计划在物理查数前拦截慢查询与笛卡尔积？", q13_body)}
      {card("q14", "cat4", 2, ["只读角色", "statement_timeout", "纵深防御"], "Q14 既然系统层有了 AST 校验与 EXPLAIN 检查，为什么还需要数据库内核级只读角色隔离？", q14_body)}
    </div>
    """

print("Parts 3 & 4 ready")
