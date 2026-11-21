package com.text2sql.agent.analysis;

import java.util.List;
import java.util.Objects;

/** 已通过字段白名单和口径校验、可交给只读执行器的分析查询。 */
public record SalesAnalysisQuery(SalesAnalysisView view, String sql, List<String> columns) {

    public SalesAnalysisQuery {
        view = Objects.requireNonNull(view, "view must not be null");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        sql = sql.strip();
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
