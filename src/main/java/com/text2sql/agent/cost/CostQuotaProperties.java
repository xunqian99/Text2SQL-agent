package com.text2sql.agent.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "agent.cost-quota")
public class CostQuotaProperties {
    private boolean enabled;
    private String stateFile = ".private/runtime/cost-quota.json";
    private BigDecimal totalYuan = new BigDecimal("1.00");
    private BigDecimal reservationYuan = new BigDecimal("0.10");
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStateFile() { return stateFile; }
    public void setStateFile(String stateFile) { this.stateFile = stateFile; }
    public BigDecimal getTotalYuan() { return totalYuan; }
    public void setTotalYuan(BigDecimal totalYuan) { this.totalYuan = totalYuan; }
    public BigDecimal getReservationYuan() { return reservationYuan; }
    public void setReservationYuan(BigDecimal reservationYuan) { this.reservationYuan = reservationYuan; }
}
