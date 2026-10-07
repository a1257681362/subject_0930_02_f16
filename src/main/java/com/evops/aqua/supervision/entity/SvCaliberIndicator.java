package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 口径版本下逐指标统计口径：数据源表、日期列、汇总表达式、单位与缺数判定规则。
 * 月报生成时把整行序列化为 caliber_snapshot_json 固化到指标行。
 */
@TableName("t_sv_caliber_indicator")
public class SvCaliberIndicator extends BaseEntity {

    public static final String CODE_SEEDLING_QTY = "SEEDLING_QTY";
    public static final String CODE_MEDICATION_QTY = "MEDICATION_QTY";
    public static final String CODE_MORTALITY_RATE = "MORTALITY_RATE";
    public static final String CODE_HARVEST_QTY = "HARVEST_QTY";
    public static final String CODE_INSPECTION_CONCLUSION = "INSPECTION_CONCLUSION";

    private Long versionId;
    private String indicatorCode;
    private String indicatorName;
    private String sourceTable;
    private String dateColumn;
    private String aggExpression;
    private String unit;
    private String missingRule;
    private Integer sortNo;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getIndicatorCode() { return indicatorCode; }
    public void setIndicatorCode(String indicatorCode) { this.indicatorCode = indicatorCode; }
    public String getIndicatorName() { return indicatorName; }
    public void setIndicatorName(String indicatorName) { this.indicatorName = indicatorName; }
    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }
    public String getDateColumn() { return dateColumn; }
    public void setDateColumn(String dateColumn) { this.dateColumn = dateColumn; }
    public String getAggExpression() { return aggExpression; }
    public void setAggExpression(String aggExpression) { this.aggExpression = aggExpression; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getMissingRule() { return missingRule; }
    public void setMissingRule(String missingRule) { this.missingRule = missingRule; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
