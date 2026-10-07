package com.evops.aqua.supervision.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 发布监管月报统计口径新版本：五指标口径整体一版。
 * 新版本发布后旧版本自动停用；已生成月报冻结引用版本，不受影响。
 */
public class CaliberVersionPublishRequest {

    @NotBlank(message = "口径版本号不能为空")
    private String versionNo;

    private String remark;

    @NotEmpty(message = "至少配置一个指标口径")
    @Valid
    private List<IndicatorCaliberDto> indicators;

    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<IndicatorCaliberDto> getIndicators() { return indicators; }
    public void setIndicators(List<IndicatorCaliberDto> indicators) { this.indicators = indicators; }

    /** 单个指标统计口径：数据源表/日期列/汇总表达式/单位/缺数规则。 */
    public static class IndicatorCaliberDto {
        @NotBlank(message = "指标编码不能为空")
        private String indicatorCode;
        @NotBlank(message = "指标名称不能为空")
        private String indicatorName;
        @NotBlank(message = "数据源表不能为空")
        private String sourceTable;
        @NotBlank(message = "日期列不能为空")
        private String dateColumn;
        @NotBlank(message = "汇总表达式不能为空")
        private String aggExpression;
        private String unit;
        private String missingRule;
        private Integer sortNo;

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
}
