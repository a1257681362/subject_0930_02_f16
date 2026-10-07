package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.evops.aqua.supervision.entity.SvChangeEvidence;
import com.evops.aqua.supervision.entity.SvGenerateTask;
import com.evops.aqua.supervision.entity.SvTaskAttempt;
import com.evops.aqua.supervision.mapper.SvGenerateTaskMapper;
import com.evops.aqua.supervision.mapper.SvTaskAttemptMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 月报生成任务记账：领取、成功、失败均用独立事务条件更新。
 * 失败保留异常链路（完整 cause 链堆栈写入 error_stack 与 t_sv_task_attempt），
 * attempts 为执行次数、retry_count 为重试次数；超过 max_retries 不再安排重试（next_retry_time 置空）。
 */
@Service
public class SvGenerateTaskService {

    /** 失败后下一次重试退避秒数（可配置，默认 60 秒；验收可置 0 立即重试）。 */
    private final long retryBackoffSeconds;

    private final SvGenerateTaskMapper taskMapper;
    private final SvTaskAttemptMapper attemptMapper;
    private final SvEvidenceService evidenceService;

    public SvGenerateTaskService(SvGenerateTaskMapper taskMapper,
                                 SvTaskAttemptMapper attemptMapper,
                                 SvEvidenceService evidenceService,
                                 @Value("${aqua.supervision.retry-backoff-seconds:60}") long retryBackoffSeconds) {
        this.taskMapper = taskMapper;
        this.attemptMapper = attemptMapper;
        this.evidenceService = evidenceService;
        this.retryBackoffSeconds = retryBackoffSeconds;
    }

    @Transactional(rollbackFor = Exception.class)
    public SvGenerateTask createTaskIfAbsent(String taskNo, String reportMonth, String seaArea,
                                             Long caliberVersionId, String triggerSource,
                                             String requestedBy, String requestId, int maxRetries) {
        SvGenerateTask existing = taskMapper.selectOne(
                new QueryWrapper<SvGenerateTask>().eq("task_no", taskNo).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        SvGenerateTask task = new SvGenerateTask();
        task.setTaskNo(taskNo);
        task.setReportMonth(reportMonth);
        task.setSeaArea(seaArea);
        task.setCaliberVersionId(caliberVersionId);
        task.setStatus(SvGenerateTask.STATUS_PENDING);
        task.setTriggerSource(triggerSource);
        task.setRequestedBy(requestedBy);
        task.setRequestId(requestId);
        task.setAttempts(0);
        task.setRetryCount(0);
        task.setMaxRetries(maxRetries);
        task.setNextRetryTime(LocalDateTime.now());
        taskMapper.insert(task);
        evidenceService.record(SvChangeEvidence.ENTITY_GENERATE_TASK, task.getId(),
                SvChangeEvidence.ACTION_CREATE, requestId, requestedBy, null, task,
                "创建月报生成任务：" + taskNo + "，报告期=" + reportMonth + "，海区=" + seaArea);
        return task;
    }

    public SvGenerateTask get(Long id) {
        SvGenerateTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new IllegalArgumentException("月报生成任务不存在，id=" + id);
        }
        return task;
    }

    public SvGenerateTask findByRequestId(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return null;
        }
        return taskMapper.selectOne(
                new QueryWrapper<SvGenerateTask>().eq("request_id", requestId).last("LIMIT 1"));
    }

    /**
     * 领取任务：仅 PENDING 或 FAILED（且未超重试上限、到达重试时间）可转为 RUNNING。
     * 条件更新保证并发/重扫只有一方领取；返回 null 表示未领到。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public SvGenerateTask claim(Long taskId) {
        SvGenerateTask task = taskMapper.selectById(taskId);
        if (task == null || SvGenerateTask.STATUS_RUNNING.equals(task.getStatus())
                || SvGenerateTask.STATUS_SUCCESS.equals(task.getStatus())) {
            return null;
        }
        if (SvGenerateTask.STATUS_FAILED.equals(task.getStatus())) {
            if (task.getRetryCount() >= task.getMaxRetries()) {
                return null;
            }
            if (task.getNextRetryTime() != null && task.getNextRetryTime().isAfter(LocalDateTime.now())) {
                return null;
            }
        }
        LambdaUpdateWrapper<SvGenerateTask> uw = Wrappers.lambdaUpdate(SvGenerateTask.class)
                .eq(SvGenerateTask::getId, taskId)
                .in(SvGenerateTask::getStatus,
                        SvGenerateTask.STATUS_PENDING, SvGenerateTask.STATUS_FAILED)
                .set(SvGenerateTask::getStatus, SvGenerateTask.STATUS_RUNNING)
                .set(SvGenerateTask::getLastRunTime, LocalDateTime.now())
                .setSql("attempts = attempts + 1");
        int rows = taskMapper.update(null, uw);
        return rows == 1 ? taskMapper.selectById(taskId) : null;
    }

    /** 成功记账（独立事务）：SUCCESS + 关联月报。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markSuccess(Long taskId, Long reportId, String operator) {
        SvGenerateTask before = taskMapper.selectById(taskId);
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, Wrappers.lambdaUpdate(SvGenerateTask.class)
                .eq(SvGenerateTask::getId, taskId)
                .set(SvGenerateTask::getStatus, SvGenerateTask.STATUS_SUCCESS)
                .set(SvGenerateTask::getReportId, reportId)
                .set(SvGenerateTask::getFinishedTime, now)
                .set(SvGenerateTask::getLastError, null)
                .set(SvGenerateTask::getNextRetryTime, null));
        recordAttempt(taskId, before.getAttempts(), SvTaskAttempt.RESULT_SUCCESS, operator, null, now);
        SvGenerateTask after = taskMapper.selectById(taskId);
        evidenceService.record(SvChangeEvidence.ENTITY_GENERATE_TASK, taskId,
                SvChangeEvidence.ACTION_GENERATE, before.getRequestId(), operator, before, after,
                "月报生成成功：" + before.getTaskNo() + "，第" + before.getAttempts() + "次执行，reportId=" + reportId);
    }

    /**
     * 失败记账（独立事务）：业务回滚后任务、异常链路与重试次数仍保留。
     * 未超上限置 FAILED + next_retry_time 等待 Quartz 重试；达到上限置 FAILED 且不再安排重试。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailure(Long taskId, Throwable error, String operator, boolean willRetry) {
        SvGenerateTask before = taskMapper.selectById(taskId);
        int attemptNo = before.getAttempts();
        // attempts 已含本次执行：第 1 次失败重试次数为 0，第 2 次失败为 1，即 retryCount = attempts - 1。
        int newRetryCount = Math.max(0, attemptNo - 1);
        LocalDateTime now = LocalDateTime.now();
        boolean exhausted = newRetryCount >= before.getMaxRetries();
        String message = rootMessage(error);
        String stack = fullStackTrace(error);

        LambdaUpdateWrapper<SvGenerateTask> uw = Wrappers.lambdaUpdate(SvGenerateTask.class)
                .eq(SvGenerateTask::getId, taskId)
                .set(SvGenerateTask::getStatus, SvGenerateTask.STATUS_FAILED)
                .set(SvGenerateTask::getRetryCount, newRetryCount)
                .set(SvGenerateTask::getLastError, truncate(message, 900))
                .set(SvGenerateTask::getErrorStack, truncate(stack, 32000));
        if (willRetry && !exhausted) {
            uw.set(SvGenerateTask::getNextRetryTime, now.plusSeconds(retryBackoffSeconds));
        } else {
            uw.set(SvGenerateTask::getNextRetryTime, null);
        }
        taskMapper.update(null, uw);

        recordAttempt(taskId, attemptNo, SvTaskAttempt.RESULT_FAIL, operator, error, now);

        SvGenerateTask after = taskMapper.selectById(taskId);
        String action = attemptNo > 1 ? SvChangeEvidence.ACTION_RETRY
                : SvChangeEvidence.ACTION_GENERATE_FAIL;
        evidenceService.record(SvChangeEvidence.ENTITY_GENERATE_TASK, taskId,
                action, before.getRequestId(), operator, before, after,
                "月报生成失败：" + before.getTaskNo() + "，第" + attemptNo + "次执行，重试次数="
                        + newRetryCount + "/" + before.getMaxRetries()
                        + (exhausted ? "，已达重试上限不再自动重试" : "，已安排重试")
                        + "，异常=" + truncate(message, 400));
    }

    private void recordAttempt(Long taskId, int attemptNo, String result, String operator,
                               Throwable error, LocalDateTime runTime) {
        SvTaskAttempt attempt = new SvTaskAttempt();
        attempt.setTaskId(taskId);
        attempt.setAttemptNo(attemptNo);
        attempt.setResult(result);
        attempt.setOperator(operator);
        attempt.setRunTime(runTime);
        if (error != null) {
            attempt.setErrorType(error.getClass().getName());
            attempt.setErrorMessage(truncate(rootMessage(error), 900));
            attempt.setErrorStack(truncate(fullStackTrace(error), 32000));
        }
        attemptMapper.insert(attempt);
    }

    /** 待处理任务：PENDING 或 FAILED 未超上限、next_retry_time 已到；SUCCESS/RUNNING 不取。 */
    public List<SvGenerateTask> listDue() {
        List<SvGenerateTask> all = taskMapper.selectList(new QueryWrapper<SvGenerateTask>()
                .in("status", SvGenerateTask.STATUS_PENDING, SvGenerateTask.STATUS_FAILED)
                .orderByAsc("id"));
        LocalDateTime now = LocalDateTime.now();
        java.util.List<SvGenerateTask> due = new java.util.ArrayList<>();
        for (SvGenerateTask task : all) {
            if (task.getRetryCount() >= task.getMaxRetries()) {
                continue;
            }
            if (task.getNextRetryTime() == null || !task.getNextRetryTime().isAfter(now)) {
                due.add(task);
            }
        }
        return due;
    }

    public List<SvGenerateTask> list(String status, String reportMonth, String seaArea) {
        QueryWrapper<SvGenerateTask> wrapper = new QueryWrapper<>();
        wrapper.eq(status != null && !status.trim().isEmpty(), "status", status);
        wrapper.eq(reportMonth != null && !reportMonth.trim().isEmpty(), "report_month", reportMonth);
        wrapper.eq(seaArea != null && !seaArea.trim().isEmpty(), "sea_area", seaArea);
        wrapper.orderByDesc("id");
        return taskMapper.selectList(wrapper);
    }

    public List<SvTaskAttempt> listAttempts(Long taskId) {
        return attemptMapper.selectList(new QueryWrapper<SvTaskAttempt>()
                .eq("task_id", taskId).orderByAsc("attempt_no"));
    }

    private String rootMessage(Throwable error) {
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return error.getClass().getName() + ": " + safe(error.getMessage())
                + " | 根因: " + cur.getClass().getName() + ": " + safe(cur.getMessage());
    }

    private String safe(String s) {
        return s == null ? "(无异常消息)" : s;
    }

    private String fullStackTrace(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
