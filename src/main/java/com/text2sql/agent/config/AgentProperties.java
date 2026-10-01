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
     * LLM 接入参数。默认走 OpenAI 兼容协议，因此 DeepSeek / 通义千问 / 智谱 / 本地 vLLM
     * 只需要改 base-url 和 model，不用改代码。
     */
    public static class Llm {

        private String provider = "openai-compatible";
        private String baseUrl = "https://api.deepseek.com";

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
         * 自检模式：用 gold_sql 冒充模型输出跑一遍评估。
         *
         * <p>作用是把「评估器本身有 bug」和「模型不准」这两件事分开。
         * 如果 dry-run 都跑不到 100%，说明问题在评估器，此时任何准确率数字都不可信。
         * 这是评估体系建设里最容易被跳过、也最值得讲的一步。
         */
        private boolean dryRun = false;

        /** 是否用 Java 侧重新执行 gold_sql（而不是直接读 gold_results.json 快照）。 */
        private boolean recomputeGold = true;

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

        public boolean isExitAfterRun() {
            return exitAfterRun;
        }

        public void setExitAfterRun(boolean exitAfterRun) {
            this.exitAfterRun = exitAfterRun;
        }
    }
}
