package com.text2sql.agent.api;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.retrieval.SchemaProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;

/**
 * 健康检查接口。看起来可有可无，实际承担一个具体的排障职责：
 * **让「应用能起来」和「能回答业务问题」这两件事可区分**。
 *
 * <p>这个项目里这两件事经常不一致。最典型的情况是没配 LLM Key：
 * 数据库、校验器、评估器全都正常，只有 {@code /api/ask} 不可用。
 * 如果只有一个健康检查返回 200，排查的人会以为一切正常，然后对着
 * 「为什么问问题报错」发懵。把 LLM 配置状态、schema 表数直接暴露出来，
 * 一次请求就能定位问题在哪一层。
 *
 * <p>这里只检查全局客户端配置和 Schema 快照可用性，不发送付费探针。
 * SchemaCatalog 可能使用缓存，因此也不把它冒充数据库实时连通性检查。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final LlmSqlGenerator generator;
    private final SchemaProvider schemaProvider;
    private final AgentProperties properties;
    private final Environment environment;

    public HealthController(LlmSqlGenerator generator, SchemaProvider schemaProvider,
                            AgentProperties properties, Environment environment) {
        this.generator = generator;
        this.schemaProvider = schemaProvider;
        this.properties = properties;
        this.environment = environment;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean llmConfigured = generator.isGloballyConfigured();
        int tableCount;
        try {
            tableCount = schemaProvider.fullSchema().tables().size();
        } catch (RuntimeException unavailable) {
            // Never echo a JDBC URL, credential or upstream exception into this public response.
            tableCount = 0;
        }
        boolean schemaAvailable = tableCount > 0;
        body.put("status", schemaAvailable ? "UP" : "DEGRADED");
        body.put("ready", llmConfigured && schemaAvailable);
        body.put("llmConfigured", llmConfigured);
        body.put("schemaAvailable", schemaAvailable);
        body.put("schemaTableCount", tableCount);
        body.put("readinessScope", "global-client-configuration-and-schema-snapshot");
        body.put("providerVerified", false);
        body.put("databaseLiveVerified", false);
        body.put("mode", mode());
        body.put("activeProfiles", environment.getActiveProfiles());
        body.put("model", properties.getLlm().getModel());
        body.put("capabilities", capabilities());
        body.put("limits", Map.of(
                "requestBudgetEnabled", properties.getRequestBudget().isEnabled(),
                "requestTimeoutMs", properties.getRequestBudget().getTimeoutMs(),
                "maxModelCalls", properties.getRequestBudget().getMaxModelCalls(),
                "queryTimeoutSeconds", properties.getDb().getQueryTimeoutSeconds(),
                "maxRows", properties.getDb().getMaxRows()));
        if (!schemaAvailable) {
            body.put("reason", "SCHEMA_UNAVAILABLE");
        }
        return ResponseEntity.status(schemaAvailable ? 200 : 503).body(body);
    }

    private String mode() {
        // Actual evaluation flags take precedence over a profile label.
        if (properties.getEval().isEnabled()) {
            return properties.getEval().isDryRun() ? "gold-self-check" : "evaluation";
        }
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("demo") && profiles.contains("baseline")) {
            return "custom";
        }
        return profiles.contains("demo") ? "demo" : profiles.contains("baseline") ? "baseline" : "custom";
    }

    private Map<String, Object> capabilities() {
        // Explicit whitelist: never serialize AgentProperties, Environment or a datasource.
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("retrieval", properties.getRetrieval().isEnabled());
        flags.put("columnPruning", properties.getRetrieval().isColumnPruningEnabled());
        flags.put("joinPlan", properties.getRetrieval().isJoinPlanEnabled());
        flags.put("semantic", properties.getSemantic().isEnabled());
        flags.put("fewshot", properties.getFewshot().isEnabled());
        flags.put("clarification", properties.getClarification().isEnabled());
        flags.put("selfCorrection", properties.getSelfCorrection().isEnabled());
        flags.put("resultChecker", properties.getResultChecker().isEnabled());
        flags.put("conversation", properties.getConversation().isEnabled());
        flags.put("cache", properties.getCache().isEnabled());
        flags.put("persistence", properties.getPersistence().isEnabled());
        flags.put("toolUse", properties.getToolUse().isEnabled());
        flags.put("routing", properties.getRouting().isEnabled());
        flags.put("explainGuard", properties.getGuard().isExplainCostGuardEnabled());
        return flags;
    }
}
