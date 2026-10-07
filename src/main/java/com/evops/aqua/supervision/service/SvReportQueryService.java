package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.supervision.entity.SvReport;
import com.evops.aqua.supervision.entity.SvReportIndicator;
import com.evops.aqua.supervision.mapper.SvReportIndicatorMapper;
import com.evops.aqua.supervision.mapper.SvReportMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管月报查询与导出：页面（JSON）与 CSV 导出共用 loadRows 同一行源、同一海区权限口径。
 * 行源只返回当前用户授权海区的 CURRENT 版本；无海区授权的用户查不到任何行。
 */
@Service
public class SvReportQueryService {

    private final SvReportMapper reportMapper;
    private final SvReportIndicatorMapper indicatorMapper;
    private final SvAccessService accessService;

    public SvReportQueryService(SvReportMapper reportMapper,
                                SvReportIndicatorMapper indicatorMapper,
                                SvAccessService accessService) {
        this.reportMapper = reportMapper;
        this.indicatorMapper = indicatorMapper;
        this.accessService = accessService;
    }

    /**
     * 当前用户可见的当前版本月报行（后台行源）。
     * @param reportMonth 可选报告期过滤
     * @param currentOnly true 仅当前版本；false 含历史版本（仍受海区过滤）
     */
    public List<SvReport> loadReports(String reportMonth, boolean currentOnly) {
        List<String> areas = accessService.visibleSeaAreas();
        if (areas != null && areas.isEmpty()) {
            return new ArrayList<>();
        }
        QueryWrapper<SvReport> wrapper = new QueryWrapper<>();
        wrapper.eq(reportMonth != null && !reportMonth.trim().isEmpty(),
                "report_month", reportMonth);
        wrapper.eq(currentOnly, "current_flag", true);
        if (areas != null) {
            wrapper.in("sea_area", areas);
        }
        wrapper.orderByDesc("report_month").orderByAsc("sea_area").orderByDesc("version_no");
        return reportMapper.selectList(wrapper);
    }

    /** 月报列表页面视图：头 + 五指标汇总（待补显式标注，不渲染为 0）。 */
    public List<Map<String, Object>> listView(String reportMonth, boolean currentOnly) {
        List<SvReport> reports = loadReports(reportMonth, currentOnly);
        List<Map<String, Object>> view = new ArrayList<>();
        for (SvReport report : reports) {
            view.add(toView(report, indicators(report.getId())));
        }
        return view;
    }

    /** 单报详情：必须在用户授权海区内，越权拒绝。 */
    public Map<String, Object> detail(Long reportId) {
        SvReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("监管月报不存在，id=" + reportId);
        }
        accessService.requireSeaAreaVisible(report.getSeaArea());
        return toView(report, indicators(reportId));
    }

    public List<SvReportIndicator> indicators(Long reportId) {
        return indicatorMapper.selectList(
                new QueryWrapper<SvReportIndicator>().eq("report_id", reportId)
                        .orderByAsc("id"));
    }

    /** CSV 导出：与页面共用行源与海区过滤。 */
    public String exportCsv(String reportMonth, boolean currentOnly) {
        List<SvReport> reports = loadReports(reportMonth, currentOnly);
        StringBuilder sb = new StringBuilder();
        sb.append("报告编号,报告期,海区,版本号,口径版本,指标编码,指标名称,指标值,数据状态,采样记录数,口径说明\n");
        for (SvReport report : reports) {
            for (SvReportIndicator ind : indicators(report.getId())) {
                sb.append(csv(report.getReportNo())).append(',')
                        .append(csv(report.getReportMonth())).append(',')
                        .append(csv(report.getSeaArea())).append(',')
                        .append(report.getVersionNo()).append(',')
                        .append(csv(ind.getCaliberVersionNo())).append(',')
                        .append(csv(ind.getIndicatorCode())).append(',')
                        .append(csv(ind.getIndicatorName())).append(',')
                        // 待补导出为空值并在数据状态列标 PENDING_SUPPLEMENT，绝不导出 0
                        .append(ind.getIndicatorValue() == null ? "" : csv(ind.getIndicatorValue())).append(',')
                        .append(csv(ind.getDataStatus())).append(',')
                        .append(ind.getSourceRecordCount()).append(',')
                        .append(csv(ind.getPendingReason() == null ? "" : ind.getPendingReason()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private Map<String, Object> toView(SvReport report, List<SvReportIndicator> indicators) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", report.getId());
        view.put("reportNo", report.getReportNo());
        view.put("reportMonth", report.getReportMonth());
        view.put("seaArea", report.getSeaArea());
        view.put("versionNo", report.getVersionNo());
        view.put("caliberVersionId", report.getCaliberVersionId());
        view.put("status", report.getStatus());
        view.put("currentFlag", report.getCurrentFlag());
        view.put("generatedBy", report.getGeneratedBy());
        view.put("triggerSource", report.getTriggerSource());
        view.put("frozenTime", report.getFrozenTime());
        view.put("pendingCount", report.getPendingCount());
        List<Map<String, Object>> indViews = new ArrayList<>();
        for (SvReportIndicator ind : indicators) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("indicatorCode", ind.getIndicatorCode());
            m.put("indicatorName", ind.getIndicatorName());
            m.put("indicatorValue", ind.getIndicatorValue());
            m.put("dataStatus", ind.getDataStatus());
            m.put("sourceRecordCount", ind.getSourceRecordCount());
            m.put("caliberVersionId", ind.getCaliberVersionId());
            m.put("caliberVersionNo", ind.getCaliberVersionNo());
            m.put("caliberSnapshot", ind.getCaliberSnapshotJson());
            m.put("pendingReason", ind.getPendingReason());
            indViews.add(m);
        }
        view.put("indicators", indViews);
        return view;
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
