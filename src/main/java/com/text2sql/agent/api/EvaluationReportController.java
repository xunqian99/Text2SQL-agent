package com.text2sql.agent.api;

import com.text2sql.agent.evaluation.EvaluationReportReader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1 离线评测面板接口。
 *
 * <p>这是文件读取接口，不是评估运行入口：只读配置白名单中的 JSON/JSONL，
 * 不启动模型、不执行 SQL、不接受任意路径参数。页面拿到的是经过聚合的摘要，
 * 同时保留每个源文件的 SHA-256，避免把不同题集或代码版本的数字拼接起来。
 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationReportController {

    private final EvaluationReportReader reader;

    public EvaluationReportController(EvaluationReportReader reader) {
        this.reader = reader;
    }

    @GetMapping("/reports")
    public ResponseEntity<EvaluationReportReader.DashboardSnapshot> reports() {
        return ResponseEntity.ok(reader.read());
    }
}
