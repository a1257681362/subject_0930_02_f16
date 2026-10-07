package com.evops.aqua.supervision.dto;

import javax.validation.constraints.NotBlank;

/**
 * 人工触发生成监管月报：指定报告期（yyyy-MM）与海区，requestId 幂等。
 */
public class ManualGenerateRequest {

    @NotBlank(message = "报告期不能为空（yyyy-MM）")
    private String reportMonth;

    @NotBlank(message = "海区不能为空")
    private String seaArea;

    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getSeaArea() { return seaArea; }
    public void setSeaArea(String seaArea) { this.seaArea = seaArea; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
