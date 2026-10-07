package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.supervision.entity.SvCaliberIndicator;
import com.evops.aqua.supervision.entity.SvCaliberVersion;
import com.evops.aqua.supervision.entity.SvChangeEvidence;
import com.evops.aqua.supervision.entity.SvGenerateTask;
import com.evops.aqua.supervision.entity.SvReport;
import com.evops.aqua.supervision.entity.SvReportIndicator;
import com.evops.aqua.supervision.mapper.SvReportIndicatorMapper;
import com.evops.aqua.supervision.mapper.SvReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月报整版构建器：单独成 Bean，使 {@link Transactional} 经 Spring 代理生效
 * （避免同 Bean 自调用导致事务不生效）。
 * 五指标逐行固化口径版本与快照，整版 snapshot_json 固化；缺采样数据标待补不填零；
 * 重新生成版本号递增、旧版取消当前标记但原样保留。任何异常整体回滚，不留半版。
 */
@Service
public class SvReportBuilder {

    private final SvReportMapper reportMapper;
    private final SvReportIndicatorMapper indicatorMapper;
    private final SvCaliberVersionService caliberService;
    private final SvReportAggregator aggregator;
    private final SvFailureInjector failureInjector;
    private final SvEvidenceService evidenceService;
    private final ObjectMapper objectMapper;

    public SvReportBuilder(SvReportMapper reportMapper,
                           SvReportIndicatorMapper indicatorMapper,
                           SvCaliberVersionService caliberService,
                           SvReportAggregator aggregator,
                           SvFailureInjector failureInjector,
                           SvEvidenceService evidenceService,
                           ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.indicatorMapper = indicatorMapper;
        this.caliberService = caliberService;
        this.aggregator = aggregator;
        this.failureInjector = failureInjector;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long build(SvGenerateTask task, String operator) {
        // 演练故障注入：验证失败回滚不留半版、异常链路与重试次数（带根因 cause 链）
        if (failureInjector.isInjected(task.getReportMonth(), task.getSeaArea())) {
            throw new IllegalStateException("演练注入：月报" + task.getReportMonth() + "@"
                    + task.getSeaArea() + "聚合失败，整版回滚",
                    new java.io.IOException("监管采样数据外部依赖超时（模拟根因）"));
        }

        SvCaliberVersion caliber = task.getCaliberVersionId() == null
                ? caliberService.currentActive()
                : caliberService.getOrThrow(task.getCaliberVersionId());
        List<SvCaliberIndicator> calibers = caliberService.indicatorsOf(caliber.getId());

        Map<String, SvReportAggregator.Metric> metrics =
                aggregator.aggregate(task.getReportMonth(), task.getSeaArea());

        // 重新生成只产生新版本：同报告期+海区版本号递增，旧版 current_flag=0 原样保留
        Long existing = reportMapper.selectCount(new QueryWrapper<SvReport>()
                .eq("report_month", task.getReportMonth()).eq("sea_area", task.getSeaArea()));
        int versionNo = (existing == null ? 0 : existing.intValue()) + 1;

        List<SvReport> oldCurrent = reportMapper.selectList(new QueryWrapper<SvReport>()
                .eq("report_month", task.getReportMonth())
                .eq("sea_area", task.getSeaArea())
                .eq("current_flag", true));
        for (SvReport old : oldCurrent) {
            old.setCurrentFlag(false);
            reportMapper.updateById(old);
        }

        SvReport report = new SvReport();
        report.setReportNo("MR" + task.getReportMonth().replace("-", "")
                + "-V" + versionNo + "-" + task.getSeaArea());
        report.setReportMonth(task.getReportMonth());
        report.setSeaArea(task.getSeaArea());
        report.setVersionNo(versionNo);
        report.setCaliberVersionId(caliber.getId());
        report.setStatus(SvReport.STATUS_GENERATED);
        report.setCurrentFlag(true);
        report.setGeneratedBy(operator);
        report.setTriggerSource(task.getTriggerSource());
        report.setRequestId(task.getRequestId());
        report.setFrozenTime(LocalDateTime.now());
        report.setPendingCount(0);
        reportMapper.insert(report);

        int pending = 0;
        List<Map<String, Object>> snapshotIndicators = new ArrayList<>();
        for (SvCaliberIndicator caliberIndicator : calibers) {
            SvReportAggregator.Metric metric = metrics.get(caliberIndicator.getIndicatorCode());
            SvReportIndicator row = new SvReportIndicator();
            row.setReportId(report.getId());
            row.setIndicatorCode(caliberIndicator.getIndicatorCode());
            row.setIndicatorName(caliberIndicator.getIndicatorName());
            row.setCaliberVersionId(caliber.getId());
            row.setCaliberVersionNo(caliber.getVersionNo());
            row.setCaliberSnapshotJson(toJson(caliberIndicator));
            if (metric != null && !metric.missing) {
                row.setIndicatorValue(metric.value);
                row.setDataStatus(SvReportIndicator.STATUS_OK);
                row.setSourceRecordCount(metric.recordCount);
            } else {
                // 缺采样数据：值留空、标待补——绝不填零
                row.setIndicatorValue(null);
                row.setDataStatus(SvReportIndicator.STATUS_PENDING_SUPPLEMENT);
                row.setSourceRecordCount(metric == null ? 0 : metric.recordCount);
                row.setPendingReason(metric == null
                        ? "口径指标无聚合结果，标待补" : metric.pendingReason);
                pending++;
            }
            indicatorMapper.insert(row);

            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("indicatorCode", caliberIndicator.getIndicatorCode());
            snap.put("indicatorName", caliberIndicator.getIndicatorName());
            snap.put("caliberVersionNo", caliber.getVersionNo());
            snap.put("caliber", caliberIndicator);
            snap.put("value", row.getIndicatorValue());
            snap.put("dataStatus", row.getDataStatus());
            snap.put("sourceRecordCount", row.getSourceRecordCount());
            snap.put("pendingReason", row.getPendingReason());
            snapshotIndicators.add(snap);
        }
        report.setPendingCount(pending);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("reportNo", report.getReportNo());
        snapshot.put("reportMonth", report.getReportMonth());
        snapshot.put("seaArea", report.getSeaArea());
        snapshot.put("versionNo", versionNo);
        snapshot.put("caliberVersionNo", caliber.getVersionNo());
        snapshot.put("caliberVersionId", caliber.getId());
        snapshot.put("generatedBy", operator);
        snapshot.put("frozenTime", report.getFrozenTime());
        snapshot.put("pendingCount", pending);
        snapshot.put("indicators", snapshotIndicators);
        report.setSnapshotJson(toJson(snapshot));
        reportMapper.updateById(report);

        evidenceService.record(SvChangeEvidence.ENTITY_REPORT, report.getId(),
                SvChangeEvidence.ACTION_CREATE, task.getRequestId(), operator,
                null, snapshot, "固化监管月报快照：" + report.getReportNo()
                        + "，待补指标数=" + pending + "，口径版本=" + caliber.getVersionNo());
        return report.getId();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
