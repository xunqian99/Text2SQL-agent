package com.text2sql.agent.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 接入层的异常出口。**所有**没被业务代码处理的异常都在这里落地。
 *
 * <p>集中处理而不是每个 Controller 各写一份，理由有两条：
 *
 * <ol>
 *   <li>响应格式统一。前端只需要认识一种错误结构，不用为每个接口写一套解析。</li>
 *   <li>兜底可控。最后一个 {@code handleUnexpected} 保证任何未预期的异常
 *       也会返回结构化 JSON，而不是 Spring 默认的 HTML 错误页——
 *       后者在评估脚本里解析会直接炸掉。</li>
 * </ol>
 *
 * <p>注意：这里**不吞异常**。兜底分支会把完整堆栈打进日志，只是不返回给客户端。
 * 对外隐藏内部细节、对内保留全部信息，这两件事必须同时做到。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 参数校验失败（空问题、超长问题）。属于调用方错误，用 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", detail));
    }

    /**
     * 兜底。
     *
     * <p>用 500 而不是 4xx：走到这里说明是我们没预料到的情况，
     * 把责任归给调用方会掩盖真实问题。日志里带完整堆栈，响应里只给一句
     * 通用提示——错误详情可能包含表名、SQL、连接串，不适合直接返回。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "服务内部错误，请查看服务端日志"));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", code);
        body.put("message", message);
        return body;
    }
}
