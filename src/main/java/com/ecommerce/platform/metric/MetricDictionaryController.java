package com.ecommerce.platform.metric;

import com.ecommerce.platform.common.ApiResponse;
import com.text2sql.agent.semantic.Metric;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 业务指标字典 RESTful 接口。
 * 暴露当前数仓中注册的全部标准口径（GMV、客单价、复购率等），供前端中台管理展示。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricDictionaryController {

    private final MetricRegistry metricRegistry;

    public MetricDictionaryController(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    /**
     * 返回全部指标。用 {@code List} 而不是 {@code Collection}：接口契约要写死到
     * 具体类型，序列化时 Jackson 才能稳定推导出数组结构，前端拿到的始终是
     * {@code {code, message, data: [...], timestamp}} 信封。
     */
    @GetMapping
    public ApiResponse<List<Metric>> listMetrics() {
        return ApiResponse.ok(metricRegistry.all());
    }
}
