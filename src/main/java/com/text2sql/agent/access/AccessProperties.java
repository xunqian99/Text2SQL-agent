package com.text2sql.agent.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Single shared principal and process-wide limits; no user directory or tenant identity. */
@ConfigurationProperties(prefix = "agent.access")
public class AccessProperties {
    private boolean enabled;
    private String token = "";
    private int burst = 10;
    private int requestsPerMinute = 30;
    private int maxConcurrent = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getBurst() { return burst; }
    public void setBurst(int burst) { this.burst = burst; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
}
