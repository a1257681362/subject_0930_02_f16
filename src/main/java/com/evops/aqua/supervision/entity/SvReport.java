package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监管月报头：报告期（yyyy-MM）+ 海区 + 版本序号。
 * 同一报告期+海区可有多版本（重新生成只产生新版本），同时仅一个 CURRENT；
 * status=GENERATED 即整版冻结（snapshot_json 固化全量），不提供修改/删除入口。
 */
@TableName("t_sv_report")
public class SvReport extends BaseEntity {

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String CURRENT_YES = "1";

    private String reportNo;
    private String reportMonth;
    private String seaArea;
    private Integer versionNo;
    private Long caliberVersionId;
    private String status;
    private Boolean currentFlag;
    private String generatedBy;
    private String triggerSource;
    private String requestId;
    private Integer pendingCount;
    private String snapshotJson;
    private LocalDateTime frozenTime;

    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getSeaArea() { return seaArea; }
    public void setSeaArea(String seaArea) { this.seaArea = seaArea; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Long getCaliberVersionId() { return caliberVersionId; }
    public void setCaliberVersionId(Long caliberVersionId) { this.caliberVersionId = caliberVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getCurrentFlag() { return currentFlag; }
    public void setCurrentFlag(Boolean currentFlag) { this.currentFlag = currentFlag; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getPendingCount() { return pendingCount; }
    public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public LocalDateTime getFrozenTime() { return frozenTime; }
    public void setFrozenTime(LocalDateTime frozenTime) { this.frozenTime = frozenTime; }
}
