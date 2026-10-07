package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.entity.Pond;
import com.evops.aqua.mapper.PondMapper;
import com.evops.aqua.supervision.entity.SvCaliberVersion;
import com.evops.aqua.supervision.entity.SvGenerateTask;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管月报生成编排：
 * - 五指标按报告期+海区聚合，逐指标冻结统计口径版本与快照（构建在 SvReportBuilder 单事务内，失败整版回滚）；
 * - 缺采样数据 → PENDING_SUPPLEMENT 待补（value 为空），绝不以 0 填充；
 * - 同一报告期+海区重新生成只产生新版本（旧版保留不删）；
 * - 任务成功/失败由 SvGenerateTaskService 独立事务记账，失败保留异常链与重试次数，Quartz 扫描重试。
 */
@Service
public class SvReportGenerationService {

    static final int DEFAULT_MAX_RETRIES = 3;

    private final PondMapper pondMapper;
    private final SvCaliberVersionService caliberService;
    private final SvGenerateTaskService taskService;
    private final SvAccessService accessService;
    private final SvReportBuilder reportBuilder;

    public SvReportGenerationService(PondMapper pondMapper,
                                     SvCaliberVersionService caliberService,
                                     SvGenerateTaskService taskService,
                                     SvAccessService accessService,
                                     SvReportBuilder reportBuilder) {
        this.pondMapper = pondMapper;
        this.caliberService = caliberService;
        this.taskService = taskService;
        this.accessService = accessService;
        this.reportBuilder = reportBuilder;
    }

    /**
     * 人工触发生成（MANUAL）：当前用户须对该海区可见；立即尝试执行一次。
     * requestId 幂等：重放返回首次任务，不重复生成。
     */
    public SvGenerateTask requestManualGenerate(String reportMonth, String seaArea, String requestId) {
        validateMonth(reportMonth);
        if (seaArea == null || seaArea.trim().isEmpty()) {
            throw new IllegalArgumentException("海区不能为空");
        }
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        accessService.requireSeaAreaVisible(seaArea);

        SvGenerateTask idempotent = taskService.findByRequestId(requestId);
        if (idempotent != null) {
            return idempotent;
        }
        SvCaliberVersion active = caliberService.currentActive();
        String taskNo = "SV-" + reportMonth.replace("-", "") + "-" + seaArea + "-M-" + requestId;
        String operator = accessService.currentUsername();
        SvGenerateTask task = taskService.createTaskIfAbsent(taskNo, reportMonth, seaArea,
                active.getId(), SvGenerateTask.TRIGGER_MANUAL, operator, requestId, DEFAULT_MAX_RETRIES);
        // 失败不假装成功：executeOnce 内部已用独立事务保留异常链与重试次数，
        // 这里返回 FAILED 任务（含 attempts/lastError），由重试扫描或再次扫描恢复。
        try {
            executeOnce(task, operator);
        } catch (RuntimeException ignored) {
            // 生成失败已落任务记录；响应回传 FAILED 任务而非抛出通用系统错误
        }
        return taskService.get(task.getId());
    }

    /**
     * Quartz 定时入口：为所有存在 ACTIVE 池塘的海区各生成当月月报任务（不存在才建），
     * 随后统一扫描执行到期任务（含历史失败重试）。
     */
    public void runScheduledGeneration() {
        String month = java.time.YearMonth.now().toString();
        SvCaliberVersion active;
        try {
            active = caliberService.currentActive();
        } catch (IllegalStateException ex) {
            // 无生效口径：跳过本次调度，不生成任何任务
            return;
        }
        List<Pond> ponds = pondMapper.selectList(
                new QueryWrapper<Pond>().eq("status", Pond.STATUS_ACTIVE));
        List<String> areas = new ArrayList<>();
        for (Pond pond : ponds) {
            if (pond.getSeaArea() != null && !areas.contains(pond.getSeaArea())) {
                areas.add(pond.getSeaArea());
            }
        }
        for (String area : areas) {
            String taskNo = "SV-" + month.replace("-", "") + "-" + area + "-S";
            taskService.createTaskIfAbsent(taskNo, month, area, active.getId(),
                    SvGenerateTask.TRIGGER_SCHEDULED, SvGenerateTask.SCHEDULER_OPERATOR,
                    null, DEFAULT_MAX_RETRIES);
        }
        runDueTasks();
    }

    /** Quartz/手工重试入口：执行所有到期（PENDING/FAILED 未超上限）任务，单条失败隔离。 */
    public Map<String, Object> runDueTasks() {
        List<SvGenerateTask> due = taskService.listDue();
        int success = 0;
        int failed = 0;
        List<Long> okIds = new ArrayList<>();
        List<Map<String, Object>> failItems = new ArrayList<>();
        for (SvGenerateTask task : due) {
            String operator = SvGenerateTask.TRIGGER_SCHEDULED.equals(task.getTriggerSource())
                    ? SvGenerateTask.SCHEDULER_OPERATOR : task.getRequestedBy();
            try {
                executeOnce(task, operator);
                success++;
                okIds.add(task.getId());
            } catch (Throwable t) {
                failed++;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", task.getId());
                item.put("taskNo", task.getTaskNo());
                item.put("error", t.getMessage());
                failItems.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dueCount", due.size());
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("successTaskIds", okIds);
        result.put("failedItems", failItems);
        return result;
    }

    /**
     * 执行一次任务：领取 → 事务内构建（独立 Bean 代理）→ 成功记账；
     * 异常时独立事务记账（保留异常链/重试次数）后向上抛，构建事务已整体回滚不留半版。
     */
    private void executeOnce(SvGenerateTask task, String operator) {
        SvGenerateTask claimed = taskService.claim(task.getId());
        if (claimed == null) {
            return; // 已成功/运行中/未到重试时间/超上限：跳过
        }
        Long reportId;
        try {
            reportId = reportBuilder.build(claimed, operator);
        } catch (Throwable error) {
            taskService.markFailure(claimed.getId(), error, operator, true);
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            throw new RuntimeException(error);
        }
        taskService.markSuccess(claimed.getId(), reportId, operator);
    }

    private void validateMonth(String reportMonth) {
        if (reportMonth == null || !reportMonth.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("报告期格式必须为 yyyy-MM：" + reportMonth);
        }
        int month = Integer.parseInt(reportMonth.substring(5));
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("报告期月份非法：" + reportMonth);
        }
    }
}
