package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import com.text2sql.agent.execution.SqlExecutionException;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估运行器：把评估集跑一遍，产出准确率报告。
 *
 * <p>对应 ROADMAP 阶段 1 第 6 步，也是第 5 节评估体系的最小实现。
 * 它复用 {@link Text2SqlOrchestrator} 的同一条链路——评估跑的就是线上行为，
 * 不是另写一套「评估专用逻辑」。这一点决定了报告里的数字能不能代表系统。
 *
 * <p><b>为什么实现成 ApplicationRunner 而不是一个 REST 接口</b>
 *
 * <p>因为评估要跑 50–200 次 LLM 调用，耗时以分钟计。做成 HTTP 接口会立刻
 * 遇到超时、并发、进度查询三个问题，而这些和「项目要解决的问题」毫无关系。
 * 做成启动参数（{@code --agent.eval.enabled=true}）则是一条命令跑完、
 * 日志里看进度、退出码表示成败，最简单也最够用。
 *
 * <p><b>执行准确率是怎么算的</b>
 *
 * <p>不是比 SQL 字符串，而是把两条 SQL 都执行一遍，比较结果集。
 * 「同一个问题有无数种正确写法」是 Text2SQL 评估的第一原则，
 * 字符串匹配会把大量正确回答判为错误（列顺序、别名、join 写法都会变）。
 */
@Component
public class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AgentProperties properties;
    private final EvalItemLoader loader;
    private final Text2SqlOrchestrator orchestrator;
    private final SqlExecutor executor;
    private final ObjectMapper jsonMapper;
    private final ConfigurableApplicationContext applicationContext;

    public EvalRunner(AgentProperties properties, EvalItemLoader loader,
                      Text2SqlOrchestrator orchestrator, SqlExecutor executor,
                      ObjectMapper jsonMapper, ConfigurableApplicationContext applicationContext) {
        this.properties = properties;
        this.loader = loader;
        this.orchestrator = orchestrator;
        this.executor = executor;
        // 这里注入的是 Spring Boot 自动配置的 JSON ObjectMapper，
        // 与 EvalItemLoader 内部那个 YAML 实例是两个不同用途的对象。
        this.jsonMapper = jsonMapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getEval().isEnabled()) {
            return;
        }
        EvalReport report = runEvaluation();
        printSummary(report);
        Path written = writeReport(report);
        log.info("评估报告已写入：{}", written.toAbsolutePath());

        // 批处理任务跑完就退出，见 AgentProperties.Eval#exitAfterRun 的说明。
        // 放在最后而不是 try-finally：报告没写成功就不该安静退出，
        // 让它带着非零退出码失败，比「退出码 0 但没报告」更容易发现问题。
        if (properties.getEval().isExitAfterRun()) {
            log.info("评估完成，退出进程（agent.eval.exit-after-run=true）");
            int code = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(code);
        }
    }

    /**
     * 跑完整轮评估。
     *
     * <p>这里**不并行**。理由：并行会让 token 计费和延迟数字失真
     * （限流会触发重试、连接池竞争会拉长执行耗时），而阶段 1 的目标是
     * 得到一个可信的 baseline 数字，不是把评估跑快。加速留到阶段 6，
     * 届时会用「并发下的数字 vs 串行的数字」作为对比。
     */
    public EvalReport runEvaluation() {
        List<EvalItem> items = loader.load();
        boolean dryRun = properties.getEval().isDryRun();

        if (items.isEmpty()) {
            throw new IllegalStateException("评估集为空，检查 agent.eval.dir 与 agent.eval.layers");
        }
        log.info("开始评估：{} 条，dryRun={}，model={}", items.size(), dryRun, properties.getLlm().getModel());

        List<EvalReport.Failure> failures = new ArrayList<>();
        List<ItemOutcome> outcomes = new ArrayList<>();
        int schemaTableCount = 0;

        for (int i = 0; i < items.size(); i++) {
            EvalItem item = items.get(i);
            ItemOutcome outcome = evaluateOne(item, dryRun);
            outcomes.add(outcome);
            schemaTableCount = Math.max(schemaTableCount, outcome.schemaTableCount());
            if (!outcome.correct()) {
                failures.add(new EvalReport.Failure(item.id(), item.difficulty(), item.tags(),
                        item.question(), outcome.status(), outcome.generatedSql(),
                        outcome.message(), outcome.latencyMs(), outcome.firstSql(),
                        outcome.attempts(), outcome.corrected()));
            }
            // 每 10 条打一行进度。200 条评估要跑几分钟，没有进度输出
            // 会让人怀疑进程是不是卡死了——这种不确定性会直接导致中途 Ctrl+C。
            if ((i + 1) % 10 == 0 || i == items.size() - 1) {
                long done = outcomes.stream().filter(ItemOutcome::correct).count();
                log.info("进度 {}/{}，当前准确率 {}%", i + 1, items.size(), pct(done, outcomes.size()));
            }
        }

        Map<String, EvalReport.Overall> byLayer = new LinkedHashMap<>();
        Map<String, List<ItemOutcome>> grouped = outcomes.stream()
                .collect(Collectors.groupingBy(ItemOutcome::difficulty, LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((layer, list) -> byLayer.put(layer, aggregate(list)));

        Map<String, EvalReport.Overall> byTableCount = groupByExpectedTableCount(outcomes);

        EvalReport.Retrieval retrieval = aggregateRetrieval(items, outcomes);

        EvalReport.Meta meta = new EvalReport.Meta(
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                PromptTemplate.VERSION,
                properties.getLlm().getModel(),
                properties.getLlm().getBaseUrl(),
                dryRun,
                items.size(),
                properties.getEval().getLimit(),
                properties.getEval().getLayers(),
                properties.getEval().getSplit(),
                properties.getPrompt().isIncludeDataProfile(),
                properties.getPrompt().isIncludeForeignKeys(),
                properties.getGuard().getLimitMode().name(),
                properties.getDb().getMaxRows(),
                schemaTableCount,
                properties.getRetrieval().isEnabled(),
                properties.getRetrieval().getTopK(),
                properties.getRetrieval().isJoinHintsEnabled(),
                properties.getSelfCorrection().isEnabled(),
                properties.getSelfCorrection().getMaxAttempts(),
                properties.getEval().isSelfCorrectionEnabled(),
                properties.getEval().getSelfCorrectionMaxAttempts());

        return new EvalReport(meta, aggregate(outcomes), byLayer, byTableCount, failures, retrieval,
                aggregateCorrection(outcomes));
    }

    private EvalReport.Correction aggregateCorrection(List<ItemOutcome> outcomes) {
        int eligible = (int) outcomes.stream()
                .filter(o -> !o.item().goldSql().isBlank())
                .count();
        int firstAttemptCorrect = (int) outcomes.stream().filter(ItemOutcome::firstAttemptCorrect).count();
        int retried = (int) outcomes.stream().filter(o -> o.attempts() > 1).count();
        int recovered = (int) outcomes.stream().filter(ItemOutcome::corrected).count();
        int extraPrompt = outcomes.stream().mapToInt(ItemOutcome::extraPromptTokens).sum();
        int extraCompletion = outcomes.stream().mapToInt(ItemOutcome::extraCompletionTokens).sum();
        long extraLatency = outcomes.stream().mapToLong(ItemOutcome::extraLatencyMs).sum();
        return new EvalReport.Correction(
                properties.getEval().isSelfCorrectionEnabled(),
                properties.getEval().getSelfCorrectionMaxAttempts(),
                properties.getEval().getSelfCorrectionTrigger().name(),
                eligible, firstAttemptCorrect, retried, recovered,
                extraPrompt, extraCompletion, extraLatency);
    }

    /**
     * 按「gold SQL 实际用到几张表」分组统计。
     *
     * <p><b>为什么难度层（T4/T5）不够，还要再按表数分一组</b>
     *
     * <p>难度层是**人工打的标签**，它混了两种不同的东西：T4 里既有两表 join
     * 也有三表 join，T5 里既有四表也有六表。而阶段 3 的 join 路径规划
     * **只在表数多的时候才起作用**——两张表的 join 只有一种连法，
     * 规划与不规划没有区别。
     *
     * <p>所以如果只看 T4/T5 的整体数字，规划带来的提升会被两表题稀释，
     * 看不出真实效果。按 gold 表数分组，就能直接回答
     * 「表越多，规划的价值是不是越明显」——这正是阶段 3 要证明的事。
     *
     * <p>表数从 gold SQL 解析（复用 {@link TableRecall#expectedTables}），
     * 而不是从模型生成的 SQL 数——否则模型漏 join 时分组会跟着漂移，
     * 那就成了「用模型的错误来给自己分组」。
     *
     * <p>分档取 1 / 2 / 3 / 4+：四张以上已经进入「必须规划」的区间，
     * 再细分会让每档样本太少，数字失去统计意义。
     */
    private Map<String, EvalReport.Overall> groupByExpectedTableCount(List<ItemOutcome> outcomes) {
        Map<String, List<ItemOutcome>> grouped = new LinkedHashMap<>();
        for (ItemOutcome outcome : outcomes) {
            int tables = TableRecall.expectedTables(outcome.item().goldSql()).size();
            if (tables == 0) {
                continue; // gold 解析不出表，无法分组，不计入
            }
            grouped.computeIfAbsent(bucket(tables), k -> new ArrayList<>()).add(outcome);
        }
        Map<String, EvalReport.Overall> result = new LinkedHashMap<>();
        // 固定顺序输出，避免 Map 顺序让报告每次长得不一样。
        for (String key : List.of("1 表", "2 表", "3 表", "4+ 表")) {
            List<ItemOutcome> list = grouped.get(key);
            if (list != null && !list.isEmpty()) {
                result.put(key, aggregate(list));
            }
        }
        return result;
    }

    private static String bucket(int tables) {
        return tables >= 4 ? "4+ 表" : tables + " 表";
    }

    /**
     * 聚合检索层指标。
     *
     * <p><b>为什么期望表从 gold_sql 解析而不是从 AgentResponse 拿</b>
     *
     * <p>期望是「业务上该用哪些表」，它只由 gold_sql 决定，与检索有没有
     * 真的召回到无关。如果从响应里推期望，就变成了「检索召回了什么，
     * 什么就是期望」——召回率永远是 100%，指标失去意义。
     *
     * <p>解析失败的条目（期望表为空）会被排除在统计外，而不是计为失败。
     * 理由见 {@link TableRecall#expectedTables}。
     */
    private EvalReport.Retrieval aggregateRetrieval(List<EvalItem> items, List<ItemOutcome> outcomes) {
        int evaluated = 0;
        int fullRecall = 0;
        int missed = 0;
        double recallSum = 0;
        double precisionSum = 0;
        double retrievedSum = 0;
        double ddlSum = 0;
        List<EvalReport.MissedCase> incompleteCases = new ArrayList<>();

        for (int i = 0; i < outcomes.size(); i++) {
            ItemOutcome outcome = outcomes.get(i);
            EvalItem item = items.get(i);
            TableRecall.Outcome recall = TableRecall.evaluate(item.goldSql(), outcome.retrievedTables());

            ddlSum += outcome.ddlChars();
            if (recall.expected().isEmpty()) {
                continue;
            }
            evaluated++;
            recallSum += (double) recall.hit() / recall.expected().size();
            precisionSum += recall.precision();
            retrievedSum += recall.retrieved().size();
            if (recall.fullRecall()) {
                fullRecall++;
            }
            if (recall.missed()) {
                missed++;
            }
            // 记录所有不完整召回，而不只是完全漏召回。理由见 MissedCase 的说明。
            if (!recall.fullRecall()) {
                incompleteCases.add(new EvalReport.MissedCase(item.id(), item.question(),
                        List.copyOf(recall.missing()), List.copyOf(recall.retrieved())));
            }
        }

        return new EvalReport.Retrieval(
                evaluated,
                ratio(fullRecall, evaluated),
                evaluated == 0 ? 0 : recallSum / evaluated,
                evaluated == 0 ? 0 : precisionSum / evaluated,
                evaluated == 0 ? 0 : retrievedSum / evaluated,
                missed,
                outcomes.isEmpty() ? 0 : round2(ddlSum / outcomes.size()),
                List.copyOf(incompleteCases));
    }

    /**
     * 评估单条。
     *
     * <p>期望结果由**当场执行 gold_sql** 得到，而不是读 {@code gold_results.json}
     * 快照。被否掉的方案是读快照——它更快，但一旦数据库被重建、或 gold_sql 被修正，
     * 快照就和事实分叉了，而分叉会表现为「准确率莫名其妙地掉」，排查成本极高。
     * 当场执行多花几十毫秒，换来「期望值永远等于当前库的真实答案」。
     */
    private ItemOutcome evaluateOne(EvalItem item, boolean dryRun) {
        List<List<String>> expected;
        boolean ordered = ResultNormalizer.orderMatters(item.goldSql());
        try {
            QueryResult gold = executor.execute(item.goldSql());
            expected = ResultNormalizer.normalize(gold, ordered);
        } catch (SqlExecutionException e) {
            // gold_sql 跑不通 = 评估集本身有问题，不是模型的错。
            // 这类条目必须显式暴露，不能静默计为「模型答错」。
            log.error("gold_sql 执行失败，评估集有误：id={} err={}", item.id(), e.getMessage());
            return ItemOutcome.goldBroken(item, e.getMessage());
        }

        AgentResponse firstResponse = dryRun
                ? orchestrator.askWithFixedSql(item.question(), item.goldSql(), null)
                : orchestrator.ask(item.question());

        List<List<String>> actual = actual(firstResponse, ordered);
        boolean correct = firstResponse.success() && ResultNormalizer.equivalent(expected, actual);
        if (shouldCorrect(dryRun, correct, firstResponse)) {
            String feedback = correctionFeedback(item, firstResponse);
            AgentResponse correctedResponse = orchestrator.askWithCorrection(
                    item.question(), firstResponse.sql(), feedback);
            List<List<String>> correctedActual = actual(correctedResponse, ordered);
            boolean corrected = correctedResponse.success()
                    && ResultNormalizer.equivalent(expected, correctedActual);
            LlmCallRecord correctionCall = correctedResponse.llmCall();
            LlmCallRecord combined = LlmCallRecord.combine(firstResponse.llmCall(), correctionCall);
            AgentResponse merged = mergeAttempts(firstResponse, correctedResponse, combined);
            return new ItemOutcome(item, merged, corrected, expected, correctedActual,
                    firstResponse.sql(), 2, corrected, correctionCall, correct);
        }

        return new ItemOutcome(item, firstResponse, correct, expected, actual,
                firstResponse.sql(), 1, false, null, correct);
    }

    /**
     * 判断这一条要不要重试。
     *
     * <p>这里的关键是**触发信号**，不是反馈内容：
     *
     * <ul>
     *   <li>{@code ERRORS_ONLY}：只在 SQL 没有成功执行时重试。校验拒绝和执行报错
     *       都是推理时真实存在的信号，线上可用。</li>
     *   <li>{@code ERRORS_AND_MISMATCH}：执行成功但结果不匹配时也重试。这一档
     *       依赖评估器手里的 gold 结果来触发，线上并不存在这个信号，
     *       因此只能当作**上限实验**来读，不能当成线上收益。</li>
     * </ul>
     */
    private boolean shouldCorrect(boolean dryRun, boolean correct, AgentResponse firstResponse) {
        if (dryRun
                || !properties.getEval().isSelfCorrectionEnabled()
                || properties.getEval().getSelfCorrectionMaxAttempts() <= 1) {
            return false;
        }
        boolean executionSignal = !firstResponse.success();
        if (executionSignal) {
            return true;
        }
        return !correct
                && properties.getEval().getSelfCorrectionTrigger()
                == AgentProperties.Eval.SelfCorrectionTrigger.ERRORS_AND_MISMATCH;
    }

    private List<List<String>> actual(AgentResponse response, boolean ordered) {
        return response.success()
                ? ResultNormalizer.normalize(
                        new QueryResult(response.columns(), response.rows(), response.truncated(), 0L), ordered)
                : List.of();
    }

    private static AgentResponse mergeAttempts(AgentResponse first, AgentResponse second,
                                               LlmCallRecord combinedCall) {
        AgentResponse.Timings a = first.timings();
        AgentResponse.Timings b = second.timings();
        AgentResponse.Timings timings = new AgentResponse.Timings(
                safe(a == null ? 0 : a.retrievalMs()) + safe(b == null ? 0 : b.retrievalMs()),
                safe(a == null ? 0 : a.generationMs()) + safe(b == null ? 0 : b.generationMs()),
                safe(a == null ? 0 : a.validationMs()) + safe(b == null ? 0 : b.validationMs()),
                safe(a == null ? 0 : a.executionMs()) + safe(b == null ? 0 : b.executionMs()),
                safe(a == null ? 0 : a.totalMs()) + safe(b == null ? 0 : b.totalMs()));
        return new AgentResponse(second.question(), second.status(), second.sql(), second.message(),
                second.violations(), second.columns(), second.rows(), second.truncated(), second.rewritten(),
                combinedCall, second.schemaTableCount(), second.retrievedTables(), second.schemaDdlChars(), timings);
    }

    private static long safe(long value) {
        return Math.max(0, value);
    }

    /**
     * 组装自纠错反馈。
     *
     * <p><b>这里绝对不能出现标准答案</b>：第一版曾经把 gold 的样例行和行列数写进来，
     * 70 条就跑到 95.7%——那不是模型学会了自己纠错，而是它看到了答案。
     * 这种数字在面试里一问就穿，必须整条链路都不出现 gold 内容。
     *
     * <p>允许出现的信息只有三类：用户问题、模型自己上一条 SQL、以及它能自己观察到的东西
     * （数据库报错、自己那次执行的列名行数）。这些在线上都拿得到。
     */
    private String correctionFeedback(EvalItem item, AgentResponse response) {
        StringBuilder feedback = new StringBuilder();
        feedback.append("问题：").append(item.question()).append('\n');
        feedback.append("上一条 SQL 状态：").append(response.status()).append('\n');
        if (response.message() != null && !response.message().isBlank()) {
            feedback.append("数据库或校验错误：").append(truncate(response.message(), 800)).append('\n');
        }
        if (response.success()) {
            feedback.append("上一条 SQL 自己跑出来的结果：列=")
                    .append(response.columns()).append("，行数=").append(response.rowCount())
                    .append("，前 3 行=").append(sample(response.rows())).append('\n');
        }
        feedback.append("请重新审视要点：\n");
        feedback.append("1. 输出列：是否只输出问题真正要的列，没有多余的分子/分母/排序辅助列。\n");
        feedback.append("2. 聚合粒度：GROUP BY 的粒度是否与问题的「每个…」一致。\n");
        feedback.append("3. 去重与放大：JOIN 是否造成行数放大，该用 COUNT(DISTINCT) 的地方是否用了 COUNT。\n");
        feedback.append("4. 连接键：多表关联是否用了正确的键（尤其人级 customer_unique_id 与订单级 customer_id）。\n");
        feedback.append("5. 过滤与排序：过滤条件、排序字段、LIMIT 是否与问题一致。\n");
        return feedback.toString();
    }

    private static String sample(List<List<String>> rows) {
        if (rows == null) {
            return "[]";
        }
        int max = 240;
        String text = rows.stream().limit(3).toList().toString();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 把一组评估项聚合成指标。整体与分层共用这一个方法，保证口径一致。 */
    private EvalReport.Overall aggregate(List<ItemOutcome> outcomes) {
        int total = outcomes.size();
        int correct = (int) outcomes.stream().filter(ItemOutcome::correct).count();
        int sqlValid = (int) outcomes.stream().filter(ItemOutcome::sqlValid).count();
        int rewritten = (int) outcomes.stream().filter(ItemOutcome::rewritten).count();

        double avgPromptTokens = outcomes.stream()
                .mapToInt(ItemOutcome::promptTokens).average().orElse(0);
        double avgCost = outcomes.stream().mapToDouble(ItemOutcome::costYuan).average().orElse(0);
        double totalCost = outcomes.stream().mapToDouble(ItemOutcome::costYuan).sum();

        List<Long> latencies = outcomes.stream().map(ItemOutcome::latencyMs).sorted().toList();
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        return new EvalReport.Overall(total, correct, ratio(correct, total),
                sqlValid, ratio(sqlValid, total), rewritten,
                round2(avgPromptTokens), round2(avgLatency),
                percentile(latencies, 0.50), percentile(latencies, 0.95),
                round6(avgCost), round6(totalCost));
    }

    /**
     * 取分位数。
     *
     * <p>用最近秩法（nearest-rank）而不是插值：延迟是离散的实测值，
     * 「P95 = 4321.7ms」这种插值出来的数字在报告里看着精确，实际是编的。
     * 取一个真实存在过的延迟值，面试时也更经得起追问。
     */
    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private void printSummary(EvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================ 评估结果 ================\n");
        EvalReport.Meta meta = report.meta();
        sb.append(String.format("模式      : %s%n", meta.dryRun() ? "dry-run（用 gold_sql 自检评估器）" : "真实链路"));
        sb.append(String.format("模型      : %s%n", meta.model()));
        sb.append(String.format("prompt    : %s%n", meta.promptVersion()));
        sb.append(String.format("样本数    : %d%n", meta.itemCount()));
        sb.append(String.format("schema    : %d 张表%n", meta.schemaTableCount()));
        sb.append("\n");
        appendRow(sb, "分组", report.overall());
        report.byLayer().forEach((layer, o) -> appendRow(sb, layer, o));
        appendByTableCount(sb, report);
        appendRetrieval(sb, report);
        sb.append("\n失败案例数: ").append(report.failures().size()).append("\n");
        report.failures().stream().limit(10).forEach(f ->
                sb.append(String.format("  - %s [%s] %s | %s%n",
                        f.id(), f.status(), truncate(f.question(), 30), truncate(f.message(), 60))));
        sb.append("=========================================\n");
        log.info(sb.toString());
    }

    /**
     * 打印「按 gold 表数分组」的准确率。
     *
     * <p>单独一段打印，是因为它是**阶段 3 的主证据**：join 路径规划只在
     * 表多的时候起作用，所以要看的是「4+ 表那一档涨了多少」，
     * 而不是整体涨了多少——整体会被单表题稀释。
     */
    private void appendByTableCount(StringBuilder sb, EvalReport report) {
        Map<String, EvalReport.Overall> byTableCount = report.byTableCount();
        if (byTableCount == null || byTableCount.isEmpty()) {
            return;
        }
        sb.append("\n---- 按 gold 表数分组（阶段 3 主指标）----\n");
        byTableCount.forEach((bucket, o) ->
                sb.append(String.format("%-8s 准确率 %6.2f%% (%d/%d)%n",
                        bucket, o.executionAccuracy() * 100, o.correct(), o.total())));
    }

    /**
     * 打印检索层指标。
     *
     * <p>分开打印而不是并进上面那张表，理由是这两组数字回答不同的问题：
     * 上面是「答对了多少」，这里是「检索选对了多少」。混在一起看，
     * 当准确率掉的时候无法立刻判断是检索退化还是模型退化。
     */
    private void appendRetrieval(StringBuilder sb, EvalReport report) {
        EvalReport.Retrieval retrieval = report.retrieval();
        if (retrieval == null || retrieval.evaluatedCount() == 0) {
            return;
        }
        sb.append("\n---- 检索层指标 ----\n");
        sb.append(String.format("表全召回率 : %6.2f%% (%d 条有效样本)%n",
                retrieval.fullRecallRate() * 100, retrieval.evaluatedCount()));
        sb.append(String.format("表平均召回 : %6.2f%%%n", retrieval.tableRecallRate() * 100));
        sb.append(String.format("表精确率   : %6.2f%%%n", retrieval.avgPrecision() * 100));
        sb.append(String.format("平均召回表 : %.2f 张（baseline 为全部表）%n",
                retrieval.avgRetrievedTables()));
        sb.append(String.format("平均 DDL   : %.0f 字符%n", retrieval.avgDdlChars()));
        sb.append(String.format("完全漏召回 : %d 条%n", retrieval.missedCount()));
        sb.append(String.format("不完整召回 : %d 条%n", retrieval.incompleteCases().size()));
        retrieval.incompleteCases().stream().limit(15).forEach(c ->
                sb.append(String.format("  - %s 漏 %s | %s%n",
                        c.id(), c.missing(), truncate(c.question(), 40))));
    }

    private static void appendRow(StringBuilder sb, String label, EvalReport.Overall o) {
        sb.append(String.format("%-24s 准确率 %6.2f%% (%d/%d) | SQL有效 %6.2f%% | 补LIMIT %d | tokens %.0f | 延迟 avg %.0fms p50 %dms p95 %dms | 成本 avg %.6f元%n",
                label, o.executionAccuracy() * 100, o.correct(), o.total(),
                o.sqlValidRate() * 100, o.rewrittenCount(),
                o.avgPromptTokens(), o.avgLatencyMs(), o.p50LatencyMs(), o.p95LatencyMs(), o.avgCostYuan()));
    }

    private Path writeReport(EvalReport report) {
        Path dir = Path.of(properties.getEval().getOutDir());
        String name = "eval-%s%s.json".formatted(
                LocalDateTime.now().format(STAMP),
                report.meta().dryRun() ? "-dryrun" : "");
        Path file = dir.resolve(name);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException e) {
            throw new UncheckedIOException("评估报告写入失败：" + file.toAbsolutePath(), e);
        }
        return file;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double pct(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 100.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 单条评估的中间结果。它把「模型表现」和「评估器判定」分开，便于归因。 */
    private record ItemOutcome(
            EvalItem item,
            AgentResponse response,
            boolean correct,
            List<List<String>> expected,
            List<List<String>> actual,
            String firstSql,
            int attempts,
            boolean corrected,
            LlmCallRecord correctionCall,
            boolean firstAttemptCorrect) {

        static ItemOutcome goldBroken(EvalItem item, String message) {
            AgentResponse broken = AgentResponse.failed(item.question(),
                    AgentResponse.Status.EXECUTION_FAILED, item.goldSql(), "gold_sql 执行失败：" + message,
                    null, 0, List.of(), 0, null);
            return new ItemOutcome(item, broken, false, List.of(), List.of(),
                    item.goldSql(), 1, false, null, false);
        }

        String difficulty() {
            return item.difficulty();
        }

        String status() {
            return response.status().name();
        }

        String generatedSql() {
            return response.sql();
        }

        String message() {
            return response.message();
        }

        long latencyMs() {
            return response.timings() == null ? 0 : response.timings().totalMs();
        }

        int promptTokens() {
            return response.llmCall() == null ? 0 : response.llmCall().promptTokens();
        }

        double costYuan() {
            return response.llmCall() == null ? 0 : response.llmCall().costYuan();
        }

        int schemaTableCount() {
            return response.schemaTableCount();
        }

        List<String> retrievedTables() {
            return response.retrievedTables() == null ? List.of() : response.retrievedTables();
        }

        /** 本次请求实际交给模型的 DDL 字符数，用来证明检索降低了上下文。 */
        int ddlChars() {
            return response.schemaDdlChars();
        }

        /**
         * SQL 是否「有效」：通过校验并成功执行。
         *
         * <p>这个指标的作用是把两类失败分开：模型压根没生成可用的 SQL（生成层问题），
         * 和生成的 SQL 能跑但答案不对（语义问题）。只有准确率一个数字时，
         * 你无法判断该去改 prompt 还是改 schema 描述。
         */
        boolean sqlValid() {
            return response.status() == AgentResponse.Status.SUCCESS;
        }

        boolean rewritten() {
            return response.rewritten();
        }

        int extraPromptTokens() {
            return correctionCall == null ? 0 : correctionCall.promptTokens();
        }

        int extraCompletionTokens() {
            return correctionCall == null ? 0 : correctionCall.completionTokens();
        }

        long extraLatencyMs() {
            return correctionCall == null ? 0 : correctionCall.latencyMs();
        }
    }
}
