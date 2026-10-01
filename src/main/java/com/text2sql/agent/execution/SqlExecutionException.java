package com.text2sql.agent.execution;

/**
 * 执行层失败。
 *
 * <p>单独定义而不复用 RuntimeException，是为了让编排层能明确区分
 * 「SQL 有问题」（执行层）和「模型有问题」（生成层）。这两类失败的
 * 处理方式完全不同：前者应该把数据库的错误信息回灌给模型重试（阶段 5），
 * 后者应该直接算作生成失败。
 */
public class SqlExecutionException extends RuntimeException {

    public SqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
