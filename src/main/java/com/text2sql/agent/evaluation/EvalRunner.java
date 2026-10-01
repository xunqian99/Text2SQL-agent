package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import com.text2sql.agent.execution.SqlExecutionException;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.PromptTemplate;
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
                        outcome.message(), outcome.latencyMs()));
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

        EvalReport.Meta meta = new EvalReport.Meta(
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                PromptTemplate.VERSION,
                properties.getLlm().getModel(),
                properties.getLlm().getBaseUrl(),
                dryRun,
                items.size(),
                properties.getEval().getLimit(),
                properties.getEval().getLayers(),
                properties.getPrompt().isIncludeDataProfile(),
                properties.getPrompt().isIncludeForeignKeys(),
                properties.getGuard().getLimitMode().name(),
                properties.getDb().getMaxRows(),
                schemaTableCount);

        return new EvalReport(meta, aggregate(outcomes), byLayer, failures);
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

        AgentResponse response = dryRun
                ? orchestrator.askWithFixedSql(item.question(), item.goldSql(), null)
                : orchestrator.ask(item.question());

        List<List<String>> actual = response.success()
                ? ResultNormalizer.normalize(
                        new QueryResult(response.columns(), response.rows(), response.truncated(), 0L), ordered)
                : List.of();

        boolean correct = response.success() && ResultNormalizer.equivalent(expected, actual);
        return new ItemOutcome(item, response, correct, expected, actual);
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
        sb.append("\n失败案例数: ").append(report.failures().size()).append("\n");
        report.failures().stream().limit(10).forEach(f ->
                sb.append(String.format("  - %s [%s] %s | %s%n",
                        f.id(), f.status(), truncate(f.question(), 30), truncate(f.message(), 60))));
        sb.append("=========================================\n");
        log.info(sb.toString());
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
            List<List<String>> actual) {

        static ItemOutcome goldBroken(EvalItem item, String message) {
            AgentResponse broken = AgentResponse.failed(item.question(),
                    AgentResponse.Status.EXECUTION_FAILED, "gold_sql 执行失败：" + message, null, 0, null);
            return new ItemOutcome(item, broken, false, List.of(), List.of());
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
    }
}
