# -*- coding: utf-8 -*-
"""
面试指南 Part 5 & Part 6：高并发/缓存 与 评测消融/数据治理。
"""

from generate_full_guide import card, callout, table, code_block

def build_part5():
    q15_body = f"""
    {callout("direct", "一句话直答", "<strong>在实时数据问数系统中缓存结果集是致命的架构坏味道</strong>（会导致业务返回已过期的陈旧数据且毫无感知）；系统采用的是基于<strong>“问题语法归一化 + 配置指纹”的 SQL 二级缓存</strong>，命中后跳过耗时数秒的大模型生成阶段，但仍向数据库物理查数，在实现 <strong>Token 消耗降低 50%、P50 延迟降低 53%</strong> 的同时，保证了业务数据的 100% 实时新鲜。")}

    <h4>1. 为什么“缓存 SQL”远胜于“缓存查询结果”？</h4>
    {table(["对比维度", "方案 A：缓存查询结果集（Result Cache）", "方案 B：缓存 SQL 结构（SQL Cache，系统方案）"], [
        ["数据时效性（Freshness）", "❌ <strong>产生脏数据</strong>：昨天查出的销售额今天变成了旧值，业务无法察觉", "✅ <strong>永远最新</strong>：SQL 是查数逻辑，每次物理查最新库数据"],
        ["内存开销", "❌ <strong>开销不可控</strong>：大表格有几千行数据，占用数 MB 堆内存，极易引发 GC 停顿", "✅ <strong>极度轻量</strong>：一条 SQL 字符串仅占几百字节，内存占用恒定"],
        ["降本提效收益", "省去模型调用 + 数据库查数", "<strong>省去最昂贵的大模型推理（节省 10~12 秒与全部 Token）</strong>"]
    ])}

    <h4>2. 配置指纹的作用</h4>
    <p>同一个问题在不同配置下（例如调整了 Prompt 版本、切换了模型、修改了业务口径）生成的 SQL 是不同的。因此缓存的 Key 必须由<strong>“去除标点与多余空格的归一化问题 + Prompt 配置 Hash 指纹”</strong>联合计算 SHA-256，确保配置变更后旧缓存立即失效，防止逻辑污染。</p>
    """

    q16_body = f"""
    {callout("direct", "一句话直答", "请求头中的 <code>X-Api-Key</code> 是外部不受信任的动态输入；如果直接使用无界的 <code>ConcurrentHashMap</code> 缓存持有底层 HTTP 连接池的 <code>ChatModel</code> 客户端，恶意调用方可以通过随机伪造海量 Key 引发<strong>“输入决定内存分配”的 JVM 堆内存耗尽（OOM）致命漏洞</strong>；系统通过继承 <code>LinkedHashMap</code> 实现容量为 32 的 <strong>LRU 客户端池</strong>，配合双重检查锁（DCL）兼顾了高并发复用与内存安全。")}

    <h4>1. 生产级 LRU 客户端池实现代码解析</h4>
    {code_block("""// LlmSqlGenerator 中的防 OOM 客户端池核心实现
private static final int MAX_DYNAMIC_MODELS = 32;

// 1. 基于 LinkedHashMap(accessOrder=true) 构建访问顺序 LRU 容器
private final Map<String, ChatModel> dynamicChatModels = Collections.synchronizedMap(
    new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ChatModel> eldest) {
            // 数量超过 32 时，自动淘汰最久未活跃的客户端并被 GC 回收
            return size() > MAX_DYNAMIC_MODELS;
        }
    });

// 2. 双重检查锁 (DCL) 解析客户端
public ChatModel resolveChatModel(String apiKey) {
    if (dynamicChatModels.containsKey(apiKey)) {
        return dynamicChatModels.get(apiKey);
    }
    // 耗时的底层连接池与 RestClient 构建放在同步块外部，避免阻塞其他线程
    ChatModel built = buildChatModel(apiKey);
    synchronized (dynamicChatModels) {
        // 二次检查防止高并发下重复 put
        return dynamicChatModels.computeIfAbsent(apiKey, k -> built);
    }
}
""")}

    {callout("interview", "面试官高频追问", "<strong>面试官问：为什么不用现成的开源缓存库（如 Caffeine / Guava Cache），非要用原生 LinkedHashMap？</strong><br><strong>高分回答：</strong><br>做架构必须考量<strong>“引入依赖的边际收益与维护代价”</strong>。客户端池的容量上限仅为 32，没有复杂的滑动过期或多线程分段写需求。重写 <code>LinkedHashMap</code> 的 <code>removeEldestEntry</code> 只需十几行标准 JDK 代码，没有任何外部侵入与类加载负担；引入 Caffeine 会带来额外的依赖树、版本冲突风险与不可忽视的包体积增长。在足够轻量的场景下，优先采用原生标准库是最佳实践。")}
    """

    q17_body = f"""
    {callout("direct", "一句话直答", "<strong>改用 WebSocket 或 SSE 流式输出不能真正解决物理延迟，它只是欺骗感官的 UI 障眼法</strong>！物理总耗时依然受制于大模型生成，且会破坏后置 AST 安全防御的原子性；要在生产高并发场景下真正把 P95 压进 8 秒内，工业界的硬核解法是<strong>“分级语义缓存（Semantic Cache）”配合“大小模型级联路由（Model Cascading）”</strong>。")}

    <h4>1. 为什么“流式输出（SSE）”在 Text2SQL 场景下行不通？</h4>
    <ul>
      <li><strong>物理耗时一毫秒没少</strong>：用户要的是最终的数据表格，不是看模型打字敲 SQL。在完整的 SQL 语法树出来前，数据库根本无法物理执行，从点击到出表的端到端延迟依然是 12.8 秒；</li>
      <li><strong>破坏安全拦截的事务性</strong>：模型吐到最后在子查询里写了个非法表，AST 白名单此时执行硬拦截抛异常。前端此时已经流式展示了一大半，如何回滚界面？这会导致前端状态机彻底崩溃。</li>
    </ul>

    <h4>2. 真正的生产性能优化演进方向</h4>
    <div class="diagram-wrap">
      <div style="text-align:left; max-width:680px; margin:0 auto; font-size:0.88rem;">
        <p>🚀 <strong>方案一：分级语义缓存（Semantic Cache）</strong><br>
        当前基于精确匹配的二级缓存命中率约 20%。生产级应引入基于问题向量相似度的语义缓存（如 pgvector / Redis VL）。对常见重复或相似提问（如“昨日销量”、“各省订单统计”），相似度 &gt; 0.95 时直接 <strong>50ms 走缓存返回</strong>，从概率分布上直接将整体 P95 强行拉低；</p>
        
        <p>🚀 <strong>方案二：大小模型级联路由（Model Cascading）</strong><br>
        单表/双表的简单聚合查询（占总业务量 40%）不需要调用百亿参数大模型，直接路由至本地轻量 7B 参数模型（如 Qwen-7B-Coder，1.5 秒出字）；只有判定为 3 表以上复杂拓扑时才路由给大模型。分级削峰，既压低了延迟又节省了算力成本。</p>
      </div>
    </div>
    """

    return f"""
    <div class="section-group" id="sec-cat5">
      <div class="section-header">
        <h2>五、高并发、性能与缓存设计（工程落地）</h2>
        <span class="section-tag">并发与缓存</span>
      </div>
      {card("q15", "cat5", 3, ["二级缓存", "SQL缓存 vs 结果缓存", "数据时效"], "Q15 系统如何设计缓存来降低昂贵的大模型推理成本与延迟？为什么缓存 SQL 而不是缓存结果集？", q15_body)}
      {card("q16", "cat5", 3, ["BYOK透传", "LRU客户端池", "DCL双重检查锁", "防OOM"], "Q16 用户自定义密钥（BYOK）中，如何利用 LinkedHashMap 实现 LRU 客户端池并杜绝 OOM 内存泄漏？", q16_body)}
      {card("q17", "cat5", 3, ["P95延迟优化", "SSE盲区", "语义缓存", "模型级联路由"], "Q17 P95 延迟高达 12.8 秒，在真实生产高并发场景下该如何真正优化系统延迟？", q17_body)}
    </div>
    """

def build_part6():
    q18_body = f"""
    {callout("direct", "一句话直答", "字符串比对会把合法但别名不同、Join 顺序不同的正确 SQL 判错；大模型裁判成本高且自身存在幻觉风险；<strong>执行准确率（Execution Accuracy）是将标注 SQL 与生成 SQL 分别在物理数据库执行，通过数值四舍五入、空值规整、多重集排序比对真实结果集</strong>，是目前学术界与工业界最严谨、唯一可复现的客观金标准。")}

    <h4>1. 三种评测方式优劣全面对比</h4>
    {table(["评测方案", "评测机制", "致命缺陷", "结论"], [
        ["SQL 字符串完全匹配", "直接 <code>goldSql.equals(genSql)</code>", "无法识别语义等价：多一个空格、表别名不同、Join 换位都会被无情误杀", "❌ 工业界完全不可用"],
        ["大模型当裁判（LLM-as-a-Judge）", "让 GPT-4 判断两段 SQL 是否等价", "裁判模型自身存在幻觉；无法捕捉细微的字段口径差异；调用成本极高且评判不可复现", "❌ 适合辅助解释，不可用于计分"],
        ["<strong>执行准确率（Execution Accuracy）</strong>", "两条 SQL 真实入库执行，规整化比对 ResultSet", "对测试集环境有依赖，需要自建标准沙箱库", "✅ <strong>系统采用的唯一金标准</strong>"]
    ])}
    """

    q19_body = f"""
    {callout("direct", "一句话直答", "在严格控制模型底座（<code>deepseek-flash</code>）完全不变的前提下，<strong>34 个百分点的提升是由清晰的四步工程消融归因构成的</strong>：初始基线 62% → 业务口径注入 (+9pp) → 外键图与时序规范 (+13pp) → 斯坦纳树全局拓扑规划 (+4pp，达到 88% 未清洗基线) → 评测基准数据治理 (+8pp，达到 96.00% 最终实测)。")}

    <h4>1. 同模型全链路消融台账</h4>
    {table(["迭代阶段", "核心工程改动", "执行准确率", "增量贡献", "归因解释"], [
        ["1. 零样本检索基线", "纯 Top-8 Schema DDL 裸跑", "62.00%", "—", "模型缺乏业务口径与连表约束，多表题大面积死锁"],
        ["2. 业务口径注入", "引入集中式指标定义（GMV/运费等公式）", "71.00%", "+9.00pp", "彻底消除了常见商业指标的口径计算幻觉"],
        ["3. 外键拓扑与时序规范", "提示词补充外键连接及标准日期转换规范", "84.00%", "+13.00pp", "消除了 2 表基础连表断裂与日期截断失真"],
        ["4. <strong>斯坦纳树近似算法</strong>", "重构多表规划器，消除局部贪心伪支路", "<strong>88.00%</strong>", "<strong>+4.00pp</strong>", "4+ 表复杂关联准确率由 50% 暴增至 90.91%，3 表达 100%"],
        ["5. <strong>评测基准数据治理</strong>", "消除原标注隐式过滤与口径死锁（问题零改动）", "<strong>96.00%</strong>", "<strong>+8.00pp</strong>", "剥离早期 AI 自动生成评测集时的标注瑕疵，恢复公正尺子"]
    ])}
    """

    q20_body = f"""
    {callout("direct", "一句话直答", "<strong>绝对没有作弊！我们坚守了三条不可逾越的评测底线</strong>：1. 中文业务问题文本一个字没改；2. Dev/Eval 数据集的 Hash 划分算法一行没动；3. 标准标注 SQL 跑自检保持 100% 通过；提升是由算法升级（62%→88%）与消除标注私货（88%→96%）两部分严格组成的。")}

    <h4>1. 早期 AI 自动生成基准时的典型标注私货缺陷</h4>
    <p>深挖剩余 12 条失败日志，发现其中 8 条属于评测集标注本身的瑕疵：</p>
    <ul>
      <li><strong>偷加未说明的过滤条件</strong>：例如中文题目只问“统计下过单的会员分布”，标注 SQL 却私自在最后写了 <code>HAVING count(order_id) &gt;= 2</code>（偷偷加了“2 次以上”的未提及条件）；</li>
      <li><strong>同义字段平局误判</strong>：同一口径的两个题目，一个要求输出 <code>level_name</code>，另一个强制要求输出 <code>level_id</code>；大模型输出了名称，虽然业务完全正确却被无情判错；</li>
      <li><strong>内连接漏算空行</strong>：标准 SQL 在标签统计里使用了 <code>INNER JOIN</code>，直接把没有订单的几百个打标会员漏掉了，而大模型使用了正确的 <code>LEFT JOIN</code> 却被判负。</li>
    </ul>
    <p>进行<strong>评测集数据治理（Data Cleansing）</strong>，抹掉的是评测尺子上的铁锈，而不是讨巧把题目改简单。</p>
    """

    q21_body = f"""
    {callout("direct", "一句话直答", "<strong>不讲投入产出比（ROI）的自纠错就是自嗨</strong>！在斯坦纳树与口径强约束下，系统单次生成准确率已达 96.00%；实测剩余的 4 条错误属于复杂多层嵌套逻辑的深度误解；模型首轮未想通时，回灌错误只会引发同义反复；重试成功率为 0 却导致 Token 翻倍、延迟翻倍，因此主动将其默认关闭。")}

    {callout("interview", "面试官高频追问", "<strong>面试官问：很多人简历上都吹自己有“反思机制”，能自动纠错重试，你为什么反而把它关了？</strong><br><strong>高分回答：</strong><br>做架构必须看<strong>投入产出比（ROI）</strong>。我们针对空结果、比率越界、金额为负等设计了 8 条严密的启发式校验规则并支持回灌重试。但在 100 道真实题目的评估大盘上实测：它总共仅触发了 1 次，救回成功率为 0！<br>因为在高质量的前置工程约束下，语法错误已经被消灭了，剩下的属于深层逻辑理解瓶颈。把错误信息丢回去，大模型只会换一种语法再写一遍逻辑错误的 SQL。为了 0% 的挽救概率让所有用户的响应耗时从 12 秒飙升到 24 秒、Token 消耗翻倍，是极不负责任的做法。<strong>把力气花在“前置一次做对”，远比“寄希望于后置碰运气重试”更有价值。</strong>")}
    """

    return f"""
    <div class="section-group" id="sec-cat6">
      <div class="section-header">
        <h2>六、评测基准、消融实验与数据治理</h2>
        <span class="section-tag">评测与消融</span>
      </div>
      {card("q18", "cat6", 2, ["评测金标准", "执行比对", "ResultSet规范化"], "Q18 你们的评测集是如何搭建的？为什么用执行准确率而不是字符串匹配或大模型裁判？", q18_body)}
      {card("q19", "cat6", 3, ["消融实验", "62%到96%", "量化归因台账"], "Q19 在同模型对照下，端到端准确率从 62% 提升到 96%，这 34 个百分点的提升是怎么量化归因出来的？", q19_body)}
      {card("q20", "cat6", 3, ["基准清洗", "数据治理", "防作弊自证"], "Q20 面试官追问：“你的准确率达到 96%，是不是改测试集刷出来的？” 该如何从数据治理的角度严密自证？", q20_body)}
      {card("q21", "cat6", 3, ["自纠错ROI", "错误回灌", "反思机制真相"], "Q21 启发式自纠错（Self-Correction）在很多简历上吹得很神，为什么你们实测后选择主动默认关闭？", q21_body)}
    </div>
    """

print("Parts 5 & 6 ready")
