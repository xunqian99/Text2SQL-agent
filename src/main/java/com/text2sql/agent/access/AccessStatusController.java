package com.text2sql.agent.access;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AccessStatusController {
    private final boolean enabled;
    private final ApiAdmissionGate gate;

    public AccessStatusController(AccessProperties properties, ApiAdmissionGate gate) {
        enabled = properties.isEnabled();
        this.gate = gate;
    }

    @GetMapping("/api/access/status")
    public Map<String, Object> status() {
        return Map.of("enabled", enabled, "authentication", enabled ? "single-user-http-basic" : "disabled",
                "limitsScope", "single-process-shared-user", "burst", gate.capacity(),
                "requestsPerMinute", gate.requestsPerMinute(), "maxConcurrent", gate.maxConcurrent(),
                "activeRequestsIncludingThis", gate.activeRequests(), "costQuotaEnforced", false);
    }
}
