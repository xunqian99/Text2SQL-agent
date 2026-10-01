package com.text2sql.agent.api;

import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        AgentResponse response = orchestrator.ask(request.question());
        HttpStatus status = switch (response.status()) {
            case SUCCESS -> HttpStatus.OK;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case GENERATION_FAILED, EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(AskResponse.from(response));
    }

    /** 请求体。加长度上限是因为超长问题没有业务意义，却会直接推高 token 成本。 */
    public record AskRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 500, message = "question 长度不能超过 500")
            String question) {
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
            String sql,
            String message,
            java.util.List<String> violations,
            java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows,
            boolean truncated,
            boolean rewritten,
            int rowCount,
            int schemaTableCount,
            Llm llm,
            Timings timings) {

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
            return new AskResponse(r.status().name(), r.sql(), r.message(), r.violations(),
                    r.columns(), r.rows(), r.truncated(), r.rewritten(), r.rowCount(),
                    r.schemaTableCount(), llm, timings);
        }
    }
}
