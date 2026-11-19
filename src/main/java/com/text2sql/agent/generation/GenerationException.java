package com.text2sql.agent.generation;

import com.text2sql.agent.observability.LlmCallRecord;

/**
 * 生成层的失败信号。
 *
 * <p>为什么单独定义而不是抛 RuntimeException：编排层需要区分「模型没配好」
 * 和「模型返回了不能用的东西」。前者是环境问题，应该告诉用户去配 Key；
 * 后者是质量问题，应该计入准确率。混在一起会让失败归因失真——
 * 而「错在哪一层」正是这个项目要回答的核心问题之一。
 */
public class GenerationException extends RuntimeException {

    public enum Reason {
        /** 没配 API Key，压根没调用。属于环境问题，不计入模型错误。 */
        NOT_CONFIGURED,
        /** Local pricing-window policy refused the request before transport. */
        OFF_PEAK_REQUIRED,
        /** A tool could not acquire its authorized catalog; no retry/finalization call. */
        SCHEMA_UNAVAILABLE,
        /** 调用失败（网络、超时、限流、鉴权）。属于基础设施问题。 */
        CALL_FAILED,
        /** 调用成功但返回内容里提不出合法 SQL。属于模型质量问题。 */
        UNPARSEABLE,
        /** 服务端明确报告输出预算用尽；不能把半条 SQL 当完整回答执行。 */
        OUTPUT_TRUNCATED
    }

    private final Reason reason;
    private final LlmCallRecord callRecord;

    public GenerationException(Reason reason, String message) {
        this(reason, message, null, null);
    }

    public GenerationException(Reason reason, String message, Throwable cause) {
        this(reason, message, null, cause);
    }

    public GenerationException(Reason reason, String message, LlmCallRecord callRecord) {
        this(reason, message, callRecord, null);
    }

    public GenerationException(Reason reason, String message, LlmCallRecord callRecord, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.callRecord = callRecord;
    }

    public Reason getReason() {
        return reason;
    }

    public LlmCallRecord getCallRecord() {
        return callRecord;
    }
}
