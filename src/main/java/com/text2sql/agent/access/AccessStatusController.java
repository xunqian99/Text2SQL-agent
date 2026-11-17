package com.text2sql.agent.access;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AccessStatusController {
    private final boolean enabled;
    private final ApiAdmissionGate gate;
    private final com.text2sql.agent.cost.CostQuota costQuota;

    public AccessStatusController(AccessProperties properties, ApiAdmissionGate gate) {
        this(properties, gate, com.text2sql.agent.cost.CostQuota.disabled());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccessStatusController(AccessProperties properties, ApiAdmissionGate gate, com.text2sql.agent.cost.CostQuota costQuota) {
        enabled = properties.isEnabled();
        this.gate = gate;
        this.costQuota = costQuota;
    }

    @GetMapping("/api/access/status")
    public Map<String, Object> status() {
        return Map.of("enabled", enabled, "authentication", enabled ? "single-user-http-basic" : "disabled",
                "limitsScope", "single-process-shared-user", "burst", gate.capacity(),
                "requestsPerMinute", gate.requestsPerMinute(), "maxConcurrent", gate.maxConcurrent(),
                "activeRequestsIncludingThis", gate.activeRequests(), "costQuotaEnforced", costQuota.enabled());
    }
}
