package com.text2sql.agent.analysis;

/** 销售额分析工作台支持的四种结果视图。 */
public enum SalesAnalysisView {
    TOTAL(null),
    MONTH("month"),
    CATEGORY("category"),
    REGION("region");

    private final String dimension;

    SalesAnalysisView(String dimension) {
        this.dimension = dimension;
    }

    public String dimension() {
        return dimension;
    }
}
