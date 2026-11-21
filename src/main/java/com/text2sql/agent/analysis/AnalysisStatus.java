package com.text2sql.agent.analysis;

/** 分析任务的持久化状态，状态只允许沿单向状态机前进。 */
public enum AnalysisStatus {
    CREATED,
    RUNNING,
    SUCCESS,
    FAILED
}
