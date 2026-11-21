package com.text2sql.agent.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 销售额分析闭环的跨轮次业务上下文。
 *
 * <p>该对象是不可变值对象。下钻和追问通过返回新对象完成，旧上下文因此可以安全地
 * 作为已完成分析的审计快照和幂等恢复依据。
 */
public record AnalysisContext(
        String sessionId,
        String analysisId,
        MetricRef metric,
        Period period,
        List<DimensionRef> dimensions,
        List<AnalysisFilter> filters,
        SortSpec sort,
        String metricDefinitionSha256) {

    public AnalysisContext {
        sessionId = requireText(sessionId, "sessionId");
        analysisId = requireText(analysisId, "analysisId");
        metric = Objects.requireNonNull(metric, "metric must not be null");
        period = Objects.requireNonNull(period, "period must not be null");
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        filters = filters == null ? List.of() : List.copyOf(filters);
        metricDefinitionSha256 = requireText(metricDefinitionSha256, "metricDefinitionSha256");
    }

    /** 添加一个维度；同名维度保持第一次出现时的来源并保证幂等。 */
    public AnalysisContext withDimension(String name, Source source) {
        String normalizedName = requireText(name, "dimension name");
        Objects.requireNonNull(source, "dimension source must not be null");
        if (dimensions.stream().anyMatch(dimension -> dimension.name().equals(normalizedName))) {
            return this;
        }
        List<DimensionRef> next = new ArrayList<>(dimensions);
        next.add(new DimensionRef(normalizedName, source));
        return new AnalysisContext(sessionId, analysisId, metric, period, next, filters, sort,
                metricDefinitionSha256);
    }

    /**
     * 添加过滤条件。同一字段的新条件替换旧条件，保证下钻上下文不会同时携带互相冲突的值。
     */
    public AnalysisContext withFilter(AnalysisFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        List<AnalysisFilter> next = new ArrayList<>(filters);
        int existing = -1;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).field().equals(filter.field())) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            next.set(existing, filter);
        } else {
            next.add(filter);
        }
        return new AnalysisContext(sessionId, analysisId, metric, period, dimensions, next, sort,
                metricDefinitionSha256);
    }

    public enum Source {
        USER_DIRECT,
        METRIC_DEFINITION,
        INHERITED,
        JOIN_BRIDGE
    }

    public record MetricRef(String name, Source source) {
        public MetricRef {
            name = requireText(name, "metric name");
            source = Objects.requireNonNull(source, "metric source must not be null");
        }
    }

    public record Period(String type, String value) {
        public Period {
            type = requireText(type, "period type");
            value = requireText(value, "period value");
        }
    }

    public record DimensionRef(String name, Source source) {
        public DimensionRef {
            name = requireText(name, "dimension name");
            source = Objects.requireNonNull(source, "dimension source must not be null");
        }
    }

    public record SortSpec(String field, Direction direction) {
        public SortSpec {
            field = requireText(field, "sort field");
            direction = Objects.requireNonNull(direction, "sort direction must not be null");
        }
    }

    public enum Direction {
        ASC, DESC
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
