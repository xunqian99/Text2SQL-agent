package com.text2sql.agent.api;

import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.retrieval.SchemaProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
 * <p>工程设计要点：「服务健康」的定义不仅是「进程活着（Liveness）」，
 * 更关键的是「核心依赖与业务链路就绪（Readiness）」。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final LlmSqlGenerator generator;
    private final SchemaProvider schemaProvider;

    public HealthController(LlmSqlGenerator generator, SchemaProvider schemaProvider) {
        this.generator = generator;
        this.schemaProvider = schemaProvider;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean llmConfigured = generator.isConfigured();
        body.put("status", "UP");
        // ready 的含义是「能不能真正回答业务问题」，不是「进程活没活着」。
        body.put("ready", llmConfigured);
        body.put("llmConfigured", llmConfigured);
        // 读一次 schema 就能顺带验证数据库连通性：连不上会在这里抛异常。
        body.put("schemaTableCount", schemaProvider.provide("").tables().size());
        return body;
    }
}
