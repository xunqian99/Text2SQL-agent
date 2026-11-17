package com.text2sql.agent.cost;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class CostStatusController {
    private final CostQuota quota;
    public CostStatusController(CostQuota quota) { this.quota = quota; }
    @GetMapping("/api/cost/status") public Map<String, Object> status() { return quota.snapshot(); }
}
