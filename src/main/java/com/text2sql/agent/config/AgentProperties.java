package com.text2sql.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 全项目的外部可调参数集中在这里，对应 application.yml 的 agent.* 前缀。
 *
 * <p>为什么不用 @Value 散落各处：阶段 1 的验收标准之一是「每个阶段必须产出数字」，
 * 而数字只有在参数集中、可复现时才有意义。阶段 6 做消融实验时会反复改这些值，
 * 集中在一个类里才能一眼看出「这次实验到底改了哪几个旋钮」。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Db db = new Db();
    private Guard guard = new Guard();
    private Llm llm = new Llm();
    private Prompt prompt = new Prompt();
    private Retrieval retrieval = new Retrieval();
    private Semantic semantic = new Semantic();
    private Clarification clarification = new Clarification();
    private SelfCorrection selfCorrection = new SelfCorrection();
    private Eval eval = new Eval();

    public Db getDb() {
        return db;
    }

    public void setDb(Db db) {
        this.db = db;
    }

    public Guard getGuard() {
        return guard;
    }

    public void setGuard(Guard guard) {
        this.guard = guard;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Semantic getSemantic() {
        return semantic;
    }

    public void setSemantic(Semantic semantic) {
        this.semantic = semantic;
    }

    public Clarification getClarification() {
        return clarification;
    }

    public void setClarification(Clarification clarification) {
        this.clarification = clarification;
    }

    public SelfCorrection getSelfCorrection() {
        return selfCorrection;
    }

    public void setSelfCorrection(SelfCorrection selfCorrection) {
        this.selfCorrection = selfCorrection;
    }

    public Eval getEval() {
        return eval;
    }

    public void setEval(Eval eval) {
        this.eval = eval;
    }

    /** 数据库执行护栏：行数上限与超时。属于 ROADMAP 阶段 5 的第一层防线，阶段 1 先落地。 */
    public static class Db {

        /** 结果集最大行数。超过就截断，避免一条 SELECT * 把内存打满。 */
        private int maxRows = 200;

        /** 单条 SQL 的执行超时（秒）。 */
        private int queryTimeoutSeconds = 10;

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }
    }

    /**
     * LLM 接入参数。默认走 OpenAI 兼容协议，因此 DeepSeek / 通义千问 / 千帆 / 智谱 /
     * 本地 vLLM 只需要改 base-url、completions-path 和 model，不用改代码。
     *
     * <p>路径也要能配：各家「OpenAI 兼容」兼容的是请求体格式，路径并不统一
     * （通义 /v1/chat/completions，千帆 /v2/chat/completions）。
     */
    public static class Llm {

        private String provider = "openai-compatible";
        private String baseUrl = "https://api.deepseek.com";

        /**
         * 补全端点路径。默认值与 Spring AI 内置的 OpenAI 默认值一致。
         *
         * <p>为什么必须外置：各家「OpenAI 兼容」兼容的是请求体格式，不是路径。
         * 通义是 /v1/chat/completions，千帆是 /v2/chat/completions。
         * 路径也能配，换厂商才真正做到只改配置、不改代码。
         */
        private String completionsPath = "/v1/chat/completions";

        /** 留空则整个应用仍可启动，只是 /api/ask 会返回明确的 503，而不是启动失败。 */
        private String apiKey = "";

        private String model = "deepseek-chat";
        private Double temperature = 0.0;
        private Integer maxTokens = 1024;
        private int timeoutSeconds = 60;

        /** 成本估算单价（元 / 千 token）。阶段 6 会用真实单价替换，这里先留 0 表示不估算。 */
        private double inputPricePer1k = 0.0;
        private double outputPricePer1k = 0.0;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getCompletionsPath() {
            return completionsPath;
        }

        public void setCompletionsPath(String completionsPath) {
            this.completionsPath = completionsPath;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public double getInputPricePer1k() {
            return inputPricePer1k;
        }

        public void setInputPricePer1k(double inputPricePer1k) {
            this.inputPricePer1k = inputPricePer1k;
        }

        public double getOutputPricePer1k() {
            return outputPricePer1k;
        }

        public void setOutputPricePer1k(double outputPricePer1k) {
            this.outputPricePer1k = outputPricePer1k;
        }
    }

    /**
     * 校验层的护栏参数。
     *
     * <p>把这些做成配置而不是写死在代码里，是为了能在评估时做消融实验：
     * 「关掉强制 LIMIT 后准确率会变成多少」这个问题，面试时值得有一个具体数字。
     */
    public static class Guard {

        /**
         * 没有 LIMIT 时怎么办。
         *
         * <p>APPEND = 自动补上 LIMIT（默认）。REJECT = 直接判定非法。
         *
         * <p>默认选 APPEND 的理由：用户问「所有订单有多少」时模型可能忘了写 LIMIT，
         * 直接拒绝等于把一个能答对的问题判死，用户体验上是纯损失。
         * 补 LIMIT 既保证不拖库，又不牺牲答案。REJECT 模式保留，用于演示
         * 「拦截」这个动作本身——两种模式的行为差异会被写进测试。
         */
        private LimitMode limitMode = LimitMode.APPEND;

        /**
         * 是否启用「列级白名单」（阶段 5）。
         *
         * <p>只校验带表别名前缀的列，例如 {@code o.customer_id}——这类引用能精确
         * 归属到某张表，判定是确定的。裸列名不检查，因为 SELECT 别名、CTE 输出列、
         * 派生表列都可能是合法来源，误判会把正常查询拦下。
         *
         * <p>默认开启：它拦下的是 {@code sellers.region_name} 这种执行时必然报错的
         * 引用，收益确定、误伤面小。要复现阶段 1–4 的历史数字时把它关掉。
         */
        private boolean columnWhitelistEnabled = true;

        public boolean isColumnWhitelistEnabled() {
            return columnWhitelistEnabled;
        }

        public void setColumnWhitelistEnabled(boolean columnWhitelistEnabled) {
            this.columnWhitelistEnabled = columnWhitelistEnabled;
        }

        /**
         * 额外禁止的函数名（小写）。
         *
         * <p>列表里的函数都有一个共同点：**只读语句也能造成副作用**。
         * 典型如 pg_read_file 能读服务器任意文件、pg_sleep 能把连接挂死、
         * pg_terminate_backend 能踢掉别的会话。
         * 这类风险单靠「只允许 SELECT」是挡不住的——这是很多人做 Text2SQL
         * 安全时最容易漏掉的一类攻击面。
         */
        private List<String> forbiddenFunctions = new ArrayList<>(List.of(
                "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
                "pg_file_read", "pg_file_write", "pg_file_rename", "pg_file_unlink",
                "pg_ls_logdir", "pg_logdir_ls",
                "pg_sleep", "pg_sleep_for", "pg_sleep_until",
                "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "pg_rotate_logfile",
                "lo_import", "lo_export", "dblink", "dblink_exec",
                "set_config", "nextval", "setval",
                "pg_advisory_lock", "pg_advisory_xact_lock",
                "copy", "pg_stat_reset"));

        public enum LimitMode {
            APPEND,
            REJECT
        }

        public LimitMode getLimitMode() {
            return limitMode;
        }

        public void setLimitMode(LimitMode limitMode) {
            this.limitMode = limitMode;
        }

        public List<String> getForbiddenFunctions() {
            return forbiddenFunctions;
        }

        public void setForbiddenFunctions(List<String> forbiddenFunctions) {
            this.forbiddenFunctions = forbiddenFunctions;
        }
    }

    /**
     * 上下文组装参数。阶段 1 只做「整库塞入」，因此这里只有开关，没有 Top-K。
     * 阶段 2 引入检索后，Top-K / Top-N 会加在这里。
     */
    public static class Prompt {

        /**
         * 是否在上下文里附带数据画像（行数、时间范围）。
         *
         * <p>这是阶段 1 唯一一处「人为加料」，必须显式记录，否则 baseline 数字不可复现。
         * 加它的理由：Olist 数据截止 2018-10，而模型默认会用 current_date 理解「最近 30 天」，
         * 导致 T3 时间层 30 条全部落空。那不是「朴素 baseline」，那是「有缺陷的 baseline」，
         * 会让阶段 2 的增量虚高。开关保留，阶段 6 可以做消融实验量化它的贡献。
         */
        private boolean includeDataProfile = true;

        /** 是否在上下文里附带外键关系。同上，属于 schema 事实而非优化。 */
        private boolean includeForeignKeys = true;

        public boolean isIncludeDataProfile() {
            return includeDataProfile;
        }

        public void setIncludeDataProfile(boolean includeDataProfile) {
            this.includeDataProfile = includeDataProfile;
        }

        public boolean isIncludeForeignKeys() {
            return includeForeignKeys;
        }

        public void setIncludeForeignKeys(boolean includeForeignKeys) {
            this.includeForeignKeys = includeForeignKeys;
        }
    }

    /**
     * 检索层参数（阶段 2）。
     *
     * <p><b>为什么 enabled 默认 false</b>
     *
     * <p>两个理由。一是「优化必须显式开启」：baseline 是验收的参照系，
     * 它必须随时可复现，不能因为某个默认值变了就悄悄变成另一个东西。
     * 二是消融实验需要这个开关——同一份代码、同一个数据库、同一个模型，
     * 只改这一个布尔值，就能得到「+schema 检索」那一行的数字。
     * 如果检索是默认行为，跑 baseline 反而要额外配一堆参数，很容易配错。
     *
     * <p>这些阈值都放在配置里而不是代码常量，是为了能在开发集上做小范围
     * 网格搜索，并把这个过程写进 README——「我怎么调出这个数字的」
     * 本身就是面试材料。
     */
    public static class Retrieval {

        /** 是否启用检索版 provider。false = 走阶段 1 的全量 provider（baseline）。 */
        private boolean enabled = false;

        /** 最终交给模型的表数量上限。 */
        private int topK = 8;

        /** 关系扩展跳数。0 = 不扩展；1 = 只带一跳邻居；2 = 两跳。 */
        private int relationHops = 1;

        /** 每扩展一跳，分数乘以这个系数。衰减是为了让直接命中永远排在扩展项前面。 */
        private double relationDecay = 0.4;

        /**
         * 最多从几张表出发做关系扩展。
         *
         * <p>不做这个限制的话，一个命中五张表的问题会扩展出十几张表，
         * Top-K 形同虚设。取分数最高的几张出发，是把扩展预算花在
         * 最可信的命中上。
         */
        private int expansionSeedLimit = 3;

        /**
         * 单字别名的权重折扣。
         *
         * <p>「州」「券」「仓」这类单字词召回价值高但误命中率也高
         * （「广州」含「州」、「优惠」含「券」）。打折而不是禁用：
         * 禁用会丢掉「各州的订单量」这类问法，那是真实存在的说法。
         */
        private double singleCharWeightFactor = 0.5;

        /** 英文标识符（表名/列名）直接出现在问题里时的权重。 */
        private double identifierHitWeight = 1.5;

        /**
         * 是否开启「连通性修复」：Top-K 出来的表如果连不成一张图，
         * 自动补入关系图上的最短桥接表。
         *
         * <p>默认开启。它解决的是纯词法检索的结构性盲区——用户问
         * 「每个大区的商品销售额」时不会说「客户」，而 {@code customers}
         * 恰恰是连接 {@code regions} 和 {@code orders} 的唯一通路。
         * 这个缺口补词典补不掉，只能靠图算法。
         */
        private boolean bridgeRepairEnabled = true;

        /**
         * 连通性修复最多额外补几张表。
         *
         * <p>上限存在的意义：多组件子图可能需要引入多张桥接表，
         * 不封顶就会一路补到接近全量，把检索的意义抵消掉。
         * 宁可保留不连通（模型会生成跑不通的 SQL，错误可见），
         * 也不要悄悄把上下文撑爆。取 2 的实测依据：绝大多数
         * 不连通情形只差一张中枢表。
         */
        private int bridgeRepairMaxTables = 2;

        /**
         * 阶段 3：是否在 prompt 里附上「列级 join 关系」。
         *
         * <p><b>默认关闭——这是被实测数字推翻后的决定。</b>
         *
         * <p>阶段 3 的设计假设是「模型不知道该用哪一列 join」。这个假设
         * 本身被验证了：T4-015 问「每个大区的订单量」，gold 写
         * {@code r.region_code = c.customer_state}，模型写成了
         * {@code r.region_name}——两列在 regions 里都存在，SQL 能跑、结果错。
         * 补上这条边之后，T4-015 确实修好了，join 类错误从 4 条降到 1 条。
         *
         * <p>但**整体准确率没有变化**：52% → 52%（100 条 eval，同模型）。
         * 修好 5 条、新坏 5 条，净变化为零。核查新坏的 5 条：
         *
         * <ul>
         *   <li>3 条是单表题（T1-010 / T2-013 / T3-014），压根没有 join，
         *       属于模型自身的随机波动，与本次改动无关；</li>
         *   <li>2 条是**过度 join**（T4-006 / T4-031）：模型看到 JOINS 段里
         *       有 {@code sellers -> regions} 这条边，就把它连上了，
         *       而 gold 不需要它。</li>
         * </ul>
         *
         * <p>代价则是确定的：平均 DDL 从 1579 涨到 2193 字符（+39%），
         * 平均输入 token 从 709 涨到 917（+29%）。
         * **确定成本换不确定收益，工程上不成立，所以默认关闭。**
         *
         * <p>代码保留而不是删除，理由有两个：一是它修好的那类错误是真实的
         * （join 列猜错会静默出错），换一个 join 更密集的数据集时它可能就划算了；
         * 二是「做了优化、测出无效、如实关掉」这件事本身就是可信度的证据，
         * 比删掉痕迹更值得保留。想复现实验：{@code --agent.retrieval.join-hints-enabled=true}。
         */
        private boolean joinHintsEnabled = false;

        /**
         * 是否渲染 {@code JOINS} 段（全部候选边，含列名）。
         *
         * <p>单独留开关是为了定位「过度 join」的来源。实测怀疑是这一段
         * 让模型看到了太多可用边，于是把不该连的表也连上了。要验证的话：
         * 关掉这一段、只留 {@code JOIN PLAN}，看 T4-006 / T4-031 是否恢复。
         */
        private boolean joinListEnabled = true;

        /**
         * 是否渲染 {@code JOIN PLAN} 段（规划出的连接树）。
         *
         * <p>与 {@link #joinListEnabled} 配合做两段分离的消融：
         * 只给列表、只给树、两个都给，三种组合能定位到底哪一段在起作用。
         */
        private boolean joinPlanEnabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getRelationHops() {
            return relationHops;
        }

        public void setRelationHops(int relationHops) {
            this.relationHops = relationHops;
        }

        public double getRelationDecay() {
            return relationDecay;
        }

        public void setRelationDecay(double relationDecay) {
            this.relationDecay = relationDecay;
        }

        public int getExpansionSeedLimit() {
            return expansionSeedLimit;
        }

        public void setExpansionSeedLimit(int expansionSeedLimit) {
            this.expansionSeedLimit = expansionSeedLimit;
        }

        public double getSingleCharWeightFactor() {
            return singleCharWeightFactor;
        }

        public void setSingleCharWeightFactor(double singleCharWeightFactor) {
            this.singleCharWeightFactor = singleCharWeightFactor;
        }

        public double getIdentifierHitWeight() {
            return identifierHitWeight;
        }

        public void setIdentifierHitWeight(double identifierHitWeight) {
            this.identifierHitWeight = identifierHitWeight;
        }

        public boolean isBridgeRepairEnabled() {
            return bridgeRepairEnabled;
        }

        public void setBridgeRepairEnabled(boolean bridgeRepairEnabled) {
            this.bridgeRepairEnabled = bridgeRepairEnabled;
        }

        public int getBridgeRepairMaxTables() {
            return bridgeRepairMaxTables;
        }

        public void setBridgeRepairMaxTables(int bridgeRepairMaxTables) {
            this.bridgeRepairMaxTables = bridgeRepairMaxTables;
        }

        public boolean isJoinHintsEnabled() {
            return joinHintsEnabled;
        }

        public void setJoinHintsEnabled(boolean joinHintsEnabled) {
            this.joinHintsEnabled = joinHintsEnabled;
        }

        public boolean isJoinListEnabled() {
            return joinListEnabled;
        }

        public void setJoinListEnabled(boolean joinListEnabled) {
            this.joinListEnabled = joinListEnabled;
        }

        public boolean isJoinPlanEnabled() {
            return joinPlanEnabled;
        }

        public void setJoinPlanEnabled(boolean joinPlanEnabled) {
            this.joinPlanEnabled = joinPlanEnabled;
        }
    }

    /**
     * 业务口径注册表参数（阶段 4）。
     *
     * <p><b>它是什么（先把话说准）</b>
     *
     * <p>它把 {@code metrics.yml} 里人工写好的口径文本按关键词命中后注入 prompt。
     * 全程只有「子串匹配 + 依赖表过滤 + 拼接文本」三步，**不做语义理解、
     * 不生成 SQL、不改写 SQL**；最终 SQL 仍由模型写。
     *
     * <p><b>它解决什么问题</b>
     *
     * <p>阶段 3 的失败归因显示：T6 业务口径题 12 条全错，而且错法高度一致——
     * 不是 SQL 写不出来，是**口径猜错**。实测 25 条 T6 题里 17 条要求
     * 「有效订单」（排除 canceled/unavailable），模型经常忘；GMV 含不含运费、
     * 比例要不要乘 100、时长用整数天还是小数天，模型每次都在猜。
     * 这些信息不在数据库里：表、列、join 能从 schema 读到，但「哪些行算数、
     * 分子分母是谁、输出成什么样」是业务约定。
     *
     * <p><b>与检索层开关的关系</b>
     *
     * <p>两者独立：口径注入可以在全量 schema 下生效（baseline + 口径注入），
     * 也可以和检索一起生效。独立开关是为了能做出干净的四方对照：
     *
     * <pre>
     *   baseline            (retrieval=false, semantic=false)
     *   + 检索              (retrieval=true,  semantic=false)
     *   + 口径注入          (retrieval=false, semantic=true)
     *   + 检索 + 口径注入   (retrieval=true,  semantic=true)   ← 完整形态
     * </pre>
     *
     * <p>默认关闭，与检索开关遵循同一条纪律：baseline 必须随时可复现，
     * 优化要显式开启。
     *
     * <p><b>配置键为什么仍叫 semantic</b>：{@code agent.semantic.enabled} 被
     * application.yml、命令行参数和历史评估报告引用，改名会让历史数字无法复现，
     * 因此保留键名，只在文档里使用「口径注册表」这个准确说法。
     */
    public static class Semantic {

        /** 是否注入业务指标定义。 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 歧义反问参数（阶段 5）。
     *
     * <p><b>它解决什么问题</b>
     *
     * <p>有些问题没有唯一答案，取决于业务口径：「销售额」含不含运费、
     * 「活跃用户」按下单还是按登录。这两种算法都能跑出结果、语法都正确，
     * 因此**没有任何自动信号能发现猜错了**——阶段 5 的自纠错实验证明了这点：
     * 首答 SQL 有效率 100%，整体却只有 70% 上下，差额全是静默错误。
     *
     * <p>既然系统自己发现不了，就只能在不确定时不下结论：把选择权交还给提问的人。
     *
     * <p><b>默认关闭</b>，而且必须关闭：评估集里的题目都写明了口径，
     * 反问会把「本该作答」变成「不作答」，直接压低准确率。
     * 它是给真实用户链路用的能力，不是给离线评估用的。
     */
    public static class Clarification {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 线上自纠错参数（阶段 5）。
     *
     * <p><b>它和 {@code Eval.selfCorrection*} 的区别</b>
     *
     * <p>这一份管的是**真实请求链路**（{@code /api/ask}）。它只在 SQL 没有成功执行时
     * 重试——校验拒绝、执行报错、模型返回空内容，这些都是推理时真实存在的信号。
     *
     * <p>{@code Eval.selfCorrection*} 管的是离线评估，多了一档
     * {@code ERRORS_AND_MISMATCH}：执行成功但结果不对时也重试。那一档依赖评估器
     * 手里的 gold 结果来触发，线上不存在这个信号，只能当上限实验读。
     *
     * <p><b>为什么默认关闭</b>：实测首答 SQL 有效率已经是 96–100%，
     * 没有多少错误信号可回灌，收益接近 0；而一旦触发，延迟会明显上升。
     * 优化必须显式开启，baseline 才随时可复现——和检索、口径注入同一条纪律。
     */
    public static class SelfCorrection {

        private boolean enabled = false;

        /** 总尝试次数上限，含首次。1 表示不重试。 */
        private int maxAttempts = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /** 评估运行器参数。默认关闭，避免正常启动时误跑 200 条评估。 */
    public static class Eval {

        private boolean enabled = false;
        private String dir = "data/eval";
        private String outDir = "reports";

        /** 0 表示全部跑；阶段 1 验收用 50。 */
        private int limit = 0;

        /** 留空表示全部难度层；例如 [T1, T2] 只跑前两层。 */
        private List<String> layers = new ArrayList<>();

        /**
         * 开发集 / 评估集切分：{@code all} / {@code dev} / {@code eval}。
         *
         * <p><b>为什么需要它</b>
         *
         * <p>ROADMAP 5.5 的第一条硬原则是「评估集不能用来调参」。
         * 如果只有一份 200 条的数据集，那么「看失败案例 → 补词典 →
         * 重跑同一批 200 条」这个循环跑上几轮之后，报出来的召回率就是
         * 对这批样本过拟合的结果，不再代表真实能力。
         *
         * <p>默认 {@code all}：阶段 1 的历史数字是在全部 200 条上跑的，
         * 默认值改成 {@code dev} 会让「同一条命令的数字变了」，
         * 破坏历史可比性。要分集训练时必须显式写 {@code --agent.eval.split=dev}。
         */
        private String split = "all";

        /**
         * 自检模式：用 gold_sql 冒充模型输出跑一遍评估。
         *
         * <p>作用是把「评估器本身有 bug」和「模型不准」这两件事分开。
         * 如果 dry-run 都跑不到 100%，说明问题在评估器，此时任何准确率数字都不可信。
         * 这是评估体系建设里最容易被跳过、也最值得讲的一步。
         */
        private boolean dryRun = false;

        /** 是否用 Java 侧重新执行 gold_sql（而不是直接读 gold_results.json 快照）。 */
        private boolean recomputeGold = true;

        /** 阶段 5：评估中是否启用自纠错重试。默认关闭，保持已有基线可复现。 */
        private boolean selfCorrectionEnabled = false;

        private int selfCorrectionMaxAttempts = 1;

        /**
         * 自纠错的触发条件。
         *
         * <p><b>为什么必须把触发条件和重试内容分开</b>
         *
         * <p>{@link #ERRORS_ONLY} 是**线上真实可用**的：SQL 被校验层拒绝，或数据库
         * 执行报错，这两个信号在推理时确实存在，把它们回传给模型是合法的自纠错。
         *
         * <p>{@link #ERRORS_AND_MISMATCH} 是**评估专用**的：SQL 执行成功但答案不对时，
         * 线上并没有「答案错了」这个信号，只有评估器知道（它手里有 gold_sql）。
         * 所以它测的不是线上能力，而是**上限**——「如果有一个完美的结果校验器，
         * 自纠错还能再救回多少条」。报告里必须标明用的是哪一种，否则这个数字
         * 会被误读成线上收益。
         *
         * <p>注意两者的**反馈内容完全相同**：都只包含数据库真实报错、模型自己上一条
         * SQL 和它自己的结果，绝不包含 gold_sql 或标准答案的任何内容。
         */
        public enum SelfCorrectionTrigger {
            /** 只在执行/校验失败时重试。线上可复现。 */
            ERRORS_ONLY,
            /** 执行成功但结果不匹配时也重试。评估专用，测上限。 */
            ERRORS_AND_MISMATCH
        }

        private SelfCorrectionTrigger selfCorrectionTrigger = SelfCorrectionTrigger.ERRORS_ONLY;

        /**
         * 评估跑完后是否退出进程。默认 true。
         *
         * <p>这是踩过坑才加上的开关：评估器是 {@code ApplicationRunner}，
         * 跑完业务逻辑后 Spring Boot 会继续守着 Web 服务不退出。
         * 结果就是「第一次跑评估正常，第二次启动报 8080 端口被占用」——
         * 因为上一次的进程还在后台活着。批处理任务必须自己结束，
         * 否则每次跑评估前都要手工杀进程，这个坑迟早会浪费掉半小时。
         *
         * <p>留 false 的用途：跑完评估后想接着用 {@code /api/ask} 手工问几句，
         * 这时需要服务继续活着。
         */
        private boolean exitAfterRun = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getOutDir() {
            return outDir;
        }

        public void setOutDir(String outDir) {
            this.outDir = outDir;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public List<String> getLayers() {
            return layers;
        }

        public void setLayers(List<String> layers) {
            this.layers = layers;
        }

        public String getSplit() {
            return split;
        }

        public void setSplit(String split) {
            this.split = split;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public boolean isRecomputeGold() {
            return recomputeGold;
        }

        public void setRecomputeGold(boolean recomputeGold) {
            this.recomputeGold = recomputeGold;
        }

        public boolean isSelfCorrectionEnabled() {
            return selfCorrectionEnabled;
        }

        public void setSelfCorrectionEnabled(boolean selfCorrectionEnabled) {
            this.selfCorrectionEnabled = selfCorrectionEnabled;
        }

        public int getSelfCorrectionMaxAttempts() {
            return selfCorrectionMaxAttempts;
        }

        public void setSelfCorrectionMaxAttempts(int selfCorrectionMaxAttempts) {
            this.selfCorrectionMaxAttempts = selfCorrectionMaxAttempts;
        }

        public SelfCorrectionTrigger getSelfCorrectionTrigger() {
            return selfCorrectionTrigger;
        }

        public void setSelfCorrectionTrigger(SelfCorrectionTrigger selfCorrectionTrigger) {
            this.selfCorrectionTrigger = selfCorrectionTrigger;
        }

        public boolean isExitAfterRun() {
            return exitAfterRun;
        }

        public void setExitAfterRun(boolean exitAfterRun) {
            this.exitAfterRun = exitAfterRun;
        }
    }
}
