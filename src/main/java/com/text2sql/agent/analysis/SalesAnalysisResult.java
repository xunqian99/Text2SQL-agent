package com.text2sql.agent.analysis;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** 业务工作台可直接消费的分析结果，不携带 SQL、Prompt 或 Token。 */
public record SalesAnalysisResult(
        String sessionId,
        String analysisId,
        SalesAnalysisView view,
        List<String> columns,
        List<List<String>> rows,
        boolean truncated,
        long elapsedMs) {

    public SalesAnalysisResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (rows == null) {
            rows = List.of();
        } else {
            List<List<String>> copiedRows = new ArrayList<>(rows.size());
            for (List<String> row : rows) {
                copiedRows.add(row == null
                        ? null
                        : Collections.unmodifiableList(new ArrayList<>(row)));
            }
            rows = Collections.unmodifiableList(copiedRows);
        }
    }

    public boolean empty() {
        return rows.isEmpty();
    }
}
