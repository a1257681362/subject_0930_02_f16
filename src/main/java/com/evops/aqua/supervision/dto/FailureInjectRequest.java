package com.evops.aqua.supervision.dto;

import javax.validation.constraints.NotBlank;

/** 故障演练开关：对指定 报告期+海区 注入/解除一次生成失败。 */
public class FailureInjectRequest {

    @NotBlank(message = "报告期不能为空（yyyy-MM）")
    private String reportMonth;

    @NotBlank(message = "海区不能为空")
    private String seaArea;

    /** true 注入失败，false 解除。 */
    private Boolean enabled = Boolean.TRUE;

    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getSeaArea() { return seaArea; }
    public void setSeaArea(String seaArea) { this.seaArea = seaArea; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
