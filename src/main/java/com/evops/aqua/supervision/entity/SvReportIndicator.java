package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 月报指标行：五指标各一行。
 * 缺采样数据时 indicator_value 为空、data_status=PENDING_SUPPLEMENT——绝不以零填充；
 * caliber_version_id + caliber_snapshot_json 固化该指标当时统计口径，新版本不回改历史行。
 */
@TableName("t_sv_report_indicator")
public class SvReportIndicator extends BaseEntity {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_PENDING_SUPPLEMENT = "PENDING_SUPPLEMENT";

    private Long reportId;
    private String indicatorCode;
    private String indicatorName;
    private String indicatorValue;
    private String dataStatus;
    private Integer sourceRecordCount;
    private Long caliberVersionId;
    private String caliberVersionNo;
    private String caliberSnapshotJson;
    private String pendingReason;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getIndicatorCode() { return indicatorCode; }
    public void setIndicatorCode(String indicatorCode) { this.indicatorCode = indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public void setIndicatorName(String indicatorName) { this.indicatorName = indicatorName; }
    public String getIndicatorValue() { return indicatorValue; }
    public void setIndicatorValue(String indicatorValue) { this.indicatorValue = indicatorValue; }
    public String getDataStatus() { return dataStatus; }
    public void setDataStatus(String dataStatus) { this.dataStatus = dataStatus; }
    public Integer getSourceRecordCount() { return sourceRecordCount; }
    public void setSourceRecordCount(Integer sourceRecordCount) { this.sourceRecordCount = sourceRecordCount; }
    public Long getCaliberVersionId() { return caliberVersionId; }
    public void setCaliberVersionId(Long caliberVersionId) { this.caliberVersionId = caliberVersionId; }
    public String getCaliberVersionNo() { return caliberVersionNo; }
    public void setCaliberVersionNo(String caliberVersionNo) { this.caliberVersionNo = caliberVersionNo; }
    public String getCaliberSnapshotJson() { return caliberSnapshotJson; }
    public void setCaliberSnapshotJson(String caliberSnapshotJson) { this.caliberSnapshotJson = caliberSnapshotJson; }
    public String getPendingReason() { return pendingReason; }
    public void setPendingReason(String pendingReason) { this.pendingReason = pendingReason; }
}
