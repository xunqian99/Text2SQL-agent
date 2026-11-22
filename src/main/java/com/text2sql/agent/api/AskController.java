package com.text2sql.agent.api;

import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.session.SessionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 接入层：唯一对外暴露业务能力的 HTTP 入口。
 *
 * <p>对应 ROADMAP 3.2 分层表里的「接入层」，职责是「请求校验 + 协议转换」，
 * 不含任何业务逻辑。链路本身在 {@link Text2SqlOrchestrator} 里，
 * 这里只做三件事：把 JSON 变成字符串、把 {@link AgentResponse} 变成 HTTP 响应、
 * 把内部状态码映射成合适的状态码。
 *
 * <p><b>为什么这里没有 try-catch 兜异常</b>
 *
 * <p>因为编排层已经保证不抛异常了——所有失败都以 {@code status} 的形式
 * 返回。这是刻意的设计：如果这里再包一层 try-catch，就会出现「同一类错误
 * 有两个处理路径」，一个走异常、一个走状态码，日志里会看到两种形态，
 * 排查时先要判断这次走的是哪条路。异常处理集中在一处（见
 * {@code ApiExceptionHandler}），状态映射集中在这里。
 *
 * <p><b>HTTP 状态码的映射规则</b>
 *
 * <ul>
 *   <li>200 —— 链路跑通并拿到结果。</li>
 *   <li>422 —— SQL 被安全校验拦下。语义上「请求本身合法，但生成的内容不可用」，
 *       用 422 比 400 更准确，也方便前端区分「你问得不对」和「系统生成的 SQL 被拦了」。</li>
 *   <li>503 —— 未配置 LLM。这是环境问题，用 503（服务不可用）能让运维一眼看出
 *       该去配 Key，而不是去查业务逻辑。返回 500 会误导排查方向。</li>
 *   <li>502 —— LLM 调用失败或数据库执行失败。上游依赖出了问题。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final Text2SqlOrchestrator orchestrator;

    public AskController(Text2SqlOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 把中文问题翻译成 SQL 并执行。
     *
     * <p>请求体形如 {@code {"question": "2018 年有多少笔订单？"}}。
     * 用 record 而不是 Map 接参数，是为了让校验注解生效——{@code @NotBlank}
     * 只对 Bean 属性有效，Map 拿不到这个能力，空问题会一路传到 LLM 才失败。
     */
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String headerApiKey) {
        AgentResponse response = execute(request, headerApiKey);
        return ResponseEntity.status(httpStatus(response)).body(AskResponse.from(response));
    }

    /**
     * 业务工作台专用入口。它复用同一条只读问数链路，但只投影业务字段，
     * 避免页面通过 CSS 隐藏 SQL、Token 或模型诊断来假装完成隔离。
     */
    @PostMapping("/business/ask")
    public ResponseEntity<BusinessAskResponse> businessAsk(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String headerApiKey) {
        AgentResponse response = execute(request, headerApiKey);
        return ResponseEntity.status(httpStatus(response)).body(BusinessAskResponse.from(response));
    }

    private AgentResponse execute(AskRequest request, String headerApiKey) {
        String apiKey = (request.apiKey() != null && !request.apiKey().isBlank())
                ? request.apiKey()
                : headerApiKey;
        return Boolean.TRUE.equals(request.endSession())
                ? orchestrator.ask(request.question(), request.sessionId(), apiKey, true)
                : orchestrator.ask(request.question(), request.sessionId(), apiKey);
    }

    private static HttpStatus httpStatus(AgentResponse response) {
        return switch (response.status()) {
            case SUCCESS -> HttpStatus.OK;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            // 需要澄清是「请求本身信息不足」，不是服务端故障，所以用 400 而不是 5xx。
            case NEEDS_CLARIFICATION -> HttpStatus.BAD_REQUEST;
            case GENERATION_FAILED, EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
            case REQUEST_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case RESOURCE_LIMITED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /**
     * 查询历史与当前会话列表。
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionStore.SessionSummary>> listSessions() {
        return ResponseEntity.ok(orchestrator.listSessions());
    }

    /**
     * 结束会话并持久化入库。
     */
    @PostMapping("/session/{sessionId}/close")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable("sessionId") String sessionId) {
        var closed = orchestrator.closeSession(sessionId);
        if (closed.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var s = closed.get();
        return ResponseEntity.ok(new CloseSessionResponse(s.sessionId(), s.turns().size(), orchestrator.getSessionStore().persisted()));
    }

    /**
     * 删除指定会话（内存与数据库同步删除）。
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
        orchestrator.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    public record CloseSessionResponse(String sessionId, int turnCount, boolean persisted) {
    }

    /** 请求体。加长度上限是因为超长问题没有业务意义，却会直接推高 token 成本。 */
    public record AskRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 500, message = "question 长度不能超过 500")
            String question,
            // sessionId 必须限长：持久化表的 session_id 是 VARCHAR(64)，
            // 超长会在落库时被 PostgreSQL 拒绝，而那条失败只 log.warn，
            // 表现为「会话历史莫名丢失」。在入口就拦住，比事后查日志便宜得多。
            @Size(max = 64, message = "sessionId 长度不能超过 64")
            @Pattern(regexp = "[A-Za-z0-9_.:-]*",
                    message = "sessionId 只能包含字母、数字、下划线、点、冒号和短横线")
            String sessionId,
            Boolean endSession,
            String apiKey) {

        public AskRequest(String question) {
            this(question, null, false, null);
        }

        public AskRequest(String question, String sessionId, Boolean endSession) {
            this(question, sessionId, endSession, null);
        }
    }

    /**
     * 响应体。
     *
     * <p>刻意不复用 {@link AgentResponse} 直接序列化：内部类型里有
     * {@code llmCall}、{@code timings} 这类结构，接口契约一旦跟着内部类型走，
     * 以后重构内部字段就会破坏 API。多一层 DTO 换来的是「内部可以随便改」。
     * 这是典型的样板代码，看的时候可以跳过，只需知道它负责字段映射。
     */
    public record AskResponse(
            String status,
            String question,
            String sql,
            String message,
            java.util.List<String> violations,
            java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows,
            boolean truncated,
            boolean rewritten,
            int rowCount,
            int schemaTableCount,
  /**
   * 本次实际检索到的表。
   *
   * <p>补这个字段是因为阶段 7 要求「可解释性信息：用了哪些表」，
   * 而它此前只存在于 {@code AgentResponse}，没暴露到 HTTP——
   * 前端拿不到，就没法回答「是不是表选错了、为什么这么 join」。
   * 评估报告里有、接口却看不到，属于信息断层。
   */
  java.util.List<String> retrievedTables,
  int schemaDdlChars,
            Llm llm,
            Timings timings,
            String sessionId,
            String rewrittenQuestion,
            boolean schemaReused,
            com.text2sql.agent.budget.RequestBudget.Snapshot budget,
            Provenance provenance) {

        /** 四类来源凭据单独保留，供页面解释“表为什么进入上下文”。 */
        public record Provenance(java.util.Set<String> directHits,
                                 java.util.Set<String> expandedTables,
                                 java.util.Set<String> metricDependencies,
                                 java.util.Set<String> safetyBridges,
                                 boolean fallback,
                                 boolean traceable) {
        }

        public record Llm(String model, int promptTokens, int completionTokens, int totalTokens,
                          long latencyMs, double costYuan) {
        }

        public record Timings(long retrievalMs, long generationMs, long validationMs,
                              long executionMs, long totalMs) {
        }

        public static AskResponse from(AgentResponse r) {
            Llm llm = r.llmCall() == null ? null : new Llm(
                    r.llmCall().model(), r.llmCall().promptTokens(), r.llmCall().completionTokens(),
                    r.llmCall().totalTokens(), r.llmCall().latencyMs(), r.llmCall().costYuan());
            Timings timings = r.timings() == null ? null : new Timings(
                    r.timings().retrievalMs(), r.timings().generationMs(), r.timings().validationMs(),
                    r.timings().executionMs(), r.timings().totalMs());
            SchemaContext.SourceProvenance source = r.sourceProvenance() == null
                    ? SchemaContext.SourceProvenance.unknown() : r.sourceProvenance();
            Provenance provenance = new Provenance(source.directHits(), source.expandedTables(),
                    source.metricDependencies(), source.safetyBridges(), source.fallback(), source.traceable());
            return new AskResponse(r.status().name(), r.question(), r.sql(), r.message(), r.violations(),
                    r.columns(), r.rows(), r.truncated(), r.rewritten(), r.rowCount(),
                    r.schemaTableCount(), r.retrievedTables(), r.schemaDdlChars(),
                    llm, timings, r.sessionId(), r.rewrittenQuestion(), r.schemaReused(), r.budget(), provenance);
        }
    }

    /**
     * 业务工作台响应白名单。内部执行证据只通过 {@link AskResponse} 提供给诊断入口，
     * 不能把完整 AgentResponse 直接序列化到业务页面。
     */
    public record BusinessAskResponse(
            String status,
            String question,
            String message,
            String title,
            String unit,
            String summary,
            List<String> columns,
            List<List<String>> rows,
            boolean truncated,
            int rowCount,
            String sessionId,
            List<FollowUpSuggestion> followUpSuggestions,
            String dataAsOf) {

        private static final String DATA_AS_OF = "Olist 本地分析快照";

        public static BusinessAskResponse from(AgentResponse source) {
            List<String> sourceColumns = source.columns() == null ? List.of() : source.columns();
            List<String> columns = sourceColumns.stream().map(BusinessAskResponse::friendlyColumn).toList();
            List<List<String>> rows = source.rows() == null
                    ? List.of()
                    : source.rows().stream().map(BusinessAskResponse::copyRowPreservingNulls).toList();
            String status = publicStatus(source.status());
            String message = businessMessage(source);
            String metricColumn = metricColumn(sourceColumns);
            String unit = inferUnit(metricColumn);
            String title = source.status() == AgentResponse.Status.SUCCESS
                    ? friendlyColumn(metricColumn == null ? "result" : metricColumn)
                    : null;
            String summary = source.status() == AgentResponse.Status.SUCCESS && rows.size() == 1
                    && rows.getFirst().size() == 1
                    ? formatSummary(rows.getFirst().getFirst(), unit)
                    : null;
            String contextQuestion = source.rewrittenQuestion() == null
                    ? source.question() : source.rewrittenQuestion();
            return new BusinessAskResponse(status, source.question(), message, title, unit, summary,
                    columns, rows, source.truncated(), rows.size(), source.sessionId(),
                    followUpSuggestions(source.status(), contextQuestion, sourceColumns, rows), DATA_AS_OF);
        }

        /**
         * 页面上的快捷追问只是当前结果的上下文提示，真正的追问仍然走输入框和同一个 sessionId。
         *
         * <p>这里不再根据“销售额”这个业务词硬编码下钻类型，而是根据结果列判断哪些维度还没有展示，
         * 再生成普通自然语言问题。这样订单数、客单价、复购率等其他指标也会获得同一套能力；
         * 已经返回的维度不会再次生成重复按钮，多行结果则补充排序和明细两个通用动作。</p>
         */
        public record FollowUpSuggestion(String label, String question) { }

        private static String publicStatus(AgentResponse.Status status) {
            return switch (status) {
                case SUCCESS -> "SUCCESS";
                case NEEDS_CLARIFICATION -> "NEEDS_CLARIFICATION";
                default -> "FAILED";
            };
        }

        private static String businessMessage(AgentResponse source) {
            if (source.status() == AgentResponse.Status.NEEDS_CLARIFICATION) return source.message();
            if (source.status() == AgentResponse.Status.SUCCESS) return null;
            return switch (source.status()) {
                case NOT_CONFIGURED -> "分析服务暂不可用，请联系系统管理员。";
                case REJECTED -> "这个问题无法安全执行，请缩小范围或换一种问法。";
                default -> "暂时无法完成分析，请稍后重试。";
            };
        }

        private static List<FollowUpSuggestion> followUpSuggestions(AgentResponse.Status status,
                                                                      String question,
                                                                      List<String> columns,
                                                                      List<List<String>> rows) {
            if (status != AgentResponse.Status.SUCCESS || question == null || question.isBlank()) {
                return List.of();
            }
            String normalized = (columns == null ? List.<String>of() : columns).stream()
                    .filter(java.util.Objects::nonNull)
                    .map(column -> column.toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.joining("|"));
            var result = new java.util.ArrayList<FollowUpSuggestion>();
            if (!hasTimeDimension(normalized)) {
                result.add(new FollowUpSuggestion("按时间拆分", "按时间维度拆分这个结果"));
            }
            if (!hasCategoryDimension(normalized)) {
                result.add(new FollowUpSuggestion("按品类拆分", "按品类维度拆分这个结果"));
            }
            if (!hasRegionDimension(normalized)) {
                result.add(new FollowUpSuggestion("按地区拆分", "按地区维度拆分这个结果"));
            }
            if (rows != null && rows.size() > 1) {
                result.add(new FollowUpSuggestion("查看排名", "按结果数值从高到低排序"));
                result.add(new FollowUpSuggestion("查看明细", "查看这些结果对应的明细"));
            }
            return List.copyOf(result);
        }

        private static boolean hasTimeDimension(String columns) {
            return containsAny(columns, "month", "date", "year", "week", "quarter", "day", "月份", "日期", "年份", "季度", "周");
        }

        private static boolean hasCategoryDimension(String columns) {
            return containsAny(columns, "categor", "product", "sku", "type", "status", "payment", "channel",
                    "品类", "类别", "商品", "产品", "类型", "状态", "支付", "渠道");
        }

        private static boolean hasRegionDimension(String columns) {
            return containsAny(columns, "state", "city", "region", "country", "area", "地区", "区域", "省", "州", "城市", "国家");
        }

        private static boolean containsAny(String value, String... needles) {
            for (String needle : needles) {
                if (value.contains(needle)) return true;
            }
            return false;
        }

        private static String metricColumn(List<String> columns) {
            return columns.stream().filter(c -> c != null && isMetric(c)).findFirst()
                    .orElse(columns.isEmpty() ? null : columns.getLast());
        }

        private static boolean isMetric(String column) {
            String key = column.toLowerCase(java.util.Locale.ROOT);
            return key.contains("gmv") || key.contains("sales") || key.contains("amount")
                    || key.contains("price") || key.contains("freight") || key.contains("count")
                    || key.contains("rate") || key.contains("ratio") || key.contains("pct");
        }

        private static String inferUnit(String column) {
            if (column == null) return "TEXT";
            String key = column.toLowerCase(java.util.Locale.ROOT);
            if (key.contains("rate") || key.contains("ratio") || key.contains("pct") || key.contains("percent")) return "PERCENT";
            if (key.contains("count") || key.contains("orders") || key.contains("items")) return "COUNT";
            if (key.contains("gmv") || key.contains("sales") || key.contains("amount")
                    || key.contains("price") || key.contains("freight") || key.contains("payment")) return "CNY";
            return "TEXT";
        }

        private static String formatSummary(String value, String unit) {
            if (value == null || value.isBlank()) return value;
            if (!"CNY".equals(unit)) return value;
            try {
                return "¥" + String.format(java.util.Locale.ROOT, "%,.2f", new java.math.BigDecimal(value));
            } catch (NumberFormatException ignored) {
                return value;
            }
        }

        private static List<String> copyRowPreservingNulls(List<String> row) {
            if (row == null) return List.of();
            // SQL 聚合/左连接结果允许出现 NULL；List.copyOf 会拒绝 NULL，导致整个业务接口 500。
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(row));
        }

        private static String friendlyColumn(String column) {
            if (column == null || column.isBlank() || "result".equals(column)) return "分析结果";
            return switch (column.toLowerCase(java.util.Locale.ROOT)) {
                case "gmv", "gmv_with_freight", "total_gmv" -> "成交总额 GMV";
                case "sales_without_freight", "sales_amount_excl_freight" -> "不含运费商品金额";
                case "sales_amount", "total_sales" -> "销售额";
                case "month", "order_month" -> "月份";
                case "product_category_name", "category_name" -> "商品品类";
                case "customer_state" -> "客户地区";
                case "seller_state" -> "卖家地区";
                case "state" -> "地区";
                case "city" -> "城市";
                case "order_count", "orders_count", "total_orders", "cnt" -> "订单数";
                default -> column.replace('_', ' ');
            };
        }
    }
}
