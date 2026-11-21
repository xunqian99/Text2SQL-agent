package com.text2sql.agent.analysis;

import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 销售额分析的只读执行服务。
 *
 * <p>服务只把查询规格交给既有 {@link SqlExecutor}，因此自动继承只读连接、查询超时、
 * 最大返回行数和执行失败归因。业务结果 DTO 不携带 SQL 和诊断字段。
 */
@Service
public class SalesAnalysisService {

    private final SqlExecutor executor;
    private final SalesAnalysisQueryBuilder queryBuilder;

    public SalesAnalysisService(SqlExecutor executor, SalesAnalysisQueryBuilder queryBuilder) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.queryBuilder = Objects.requireNonNull(queryBuilder, "queryBuilder must not be null");
    }

    public SalesAnalysisResult analyze(AnalysisContext context, SalesAnalysisView view) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(view, "view must not be null");
        AnalysisContext effective = view == SalesAnalysisView.TOTAL || view == SalesAnalysisView.REGION
                ? context
                : context.withDimension(view.dimension(), AnalysisContext.Source.INHERITED);
        SalesAnalysisQuery query = queryBuilder.build(effective, view);
        QueryResult result = executor.execute(query.sql());
        return new SalesAnalysisResult(context.sessionId(), context.analysisId(), view,
                result.columns(), result.rows(), result.truncated(), result.elapsedMs());
    }
}
