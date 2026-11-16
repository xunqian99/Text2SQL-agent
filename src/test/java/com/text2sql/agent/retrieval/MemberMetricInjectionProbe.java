package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptFingerprint;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Real schema/injection and aggregate-only database witness. No model client and no writes. */
public final class MemberMetricInjectionProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || Files.exists(Path.of(args[0])))
            throw new IllegalArgumentException("Expected a new output file; never overwrite evidence");
        var props = new AgentProperties();
        props.getSemantic().setEnabled(true);
        props.getRetrieval().setEnabled(true);
        props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setJoinHintsEnabled(true);
        props.getRetrieval().setJoinPlanEnabled(true);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"), env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        jdbc.setQueryTimeout(10);
        var catalog = new SchemaCatalog(new DatabaseSchemaReader(jdbc), props);
        var glossary = new GlossaryLoader(catalog);
        var metrics = new MetricRegistry();
        ReflectionTestUtils.invokeMethod(metrics, "load");
        var metric = metrics.findApplicable("会员渗透率", Set.of("orders", "customers", "members"))
                .stream().filter(m -> m.name().equals("member_penetration")).findFirst().orElseThrow();
        var providers = List.<SchemaProvider>of(new FullSchemaProvider(catalog, metrics, props),
                new HybridSchemaProvider(catalog, new LexicalSchemaRetriever(glossary, catalog, props), glossary,
                        props, metrics, null, new ValueRetriever(glossary), new ColumnPruner(glossary)));
        var template = new PromptTemplate(props);
        var captures = new ArrayList<Object>();
        for (var provider : providers) {
            for (String question : List.of("会员渗透率是多少？", "会员订单占比是多少？", "会员占比是多少？",
                    "会员用户贡献的订单占比是多少？（会员渗透率）")) {
                var context = provider.provide(question);
                String user = template.userPrompt(context, question);
                require(context.metricsText().contains(metric.render()), "Corrected rendered metric missing");
                require(user.contains(metric.render()), "Corrected metric missing from final user message");
                require(context.tableNames().containsAll(metric.tables()), "Missing required tables");
                var capture = new LinkedHashMap<String, Object>();
                capture.put("provider", provider.getClass().getSimpleName());
                capture.put("question", question);
                capture.put("tables", context.tableNames());
                capture.put("metricsSha256", PromptFingerprint.textSha256(context.metricsText()));
                capture.put("userMessageSha256", PromptFingerprint.textSha256(user));
                captures.add(capture);
            }
            props.getSemantic().setEnabled(false);
            require(provider.provide("会员渗透率是多少？").metricsText().isBlank(), "Disabled injection still present");
            props.getSemantic().setEnabled(true);
        }
        // Independent denominator and membership-existence oracle: no joining/fanout in the outer count.
        long denominator = jdbc.queryForObject("SELECT COUNT(*) FROM orders o WHERE o.order_status NOT IN ('canceled','unavailable')", Long.class);
        long numerator = jdbc.queryForObject("SELECT COUNT(*) FROM orders o WHERE o.order_status NOT IN ('canceled','unavailable') "
                + "AND EXISTS (SELECT 1 FROM customers c WHERE c.customer_id=o.customer_id "
                + "AND EXISTS (SELECT 1 FROM members m WHERE m.customer_unique_id=c.customer_unique_id))", Long.class);
        BigDecimal expected = denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        BigDecimal pattern = jdbc.queryForObject(metric.queryPattern(), BigDecimal.class);
        BigDecimal expression = jdbc.queryForObject("SELECT " + metric.expression() + " FROM orders o "
                + "LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN members m ON m.customer_unique_id=c.customer_unique_id WHERE "
                + metric.filter(), BigDecimal.class);
        require(equal(expected, pattern) && equal(expected, expression), "Production formula differs from independent counts");
        var report = new LinkedHashMap<String, Object>();
        report.put("format", "member-metric-injection-v1");
        report.put("metricDefinitionsSha256", MetricRegistry.definitionSha256());
        report.put("loadedMetrics", metrics.all().size());
        report.put("renderedMemberMetric", metric.render());
        report.put("captures", captures);
        report.put("disabledProviderChecks", 2);
        report.put("eligibleOrders", denominator);
        report.put("memberOrders", numerator);
        report.put("oraclePercent", expected);
        report.put("expressionPercent", expression);
        report.put("patternPercent", pattern);
        report.put("modelCalls", 0);
        report.put("tokens", 0);
        report.put("modelCostYuan", 0);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
        System.out.println("MEMBER_METRIC captures=8 disabledChecks=2 databaseOracle=PASS modelCalls=0");
    }

    private static boolean equal(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
