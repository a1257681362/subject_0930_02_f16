package com.evops.aqua.supervision.controller;

import com.evops.aqua.supervision.dto.ManualGenerateRequest;
import com.evops.aqua.supervision.entity.SvGenerateTask;
import com.evops.aqua.supervision.service.SvGenerateTaskService;
import com.evops.aqua.supervision.service.SvReportGenerationService;
import com.evops.aqua.supervision.service.SvReportQueryService;
import com.evops.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 水产监管月报：人工触发生成、重试扫描、查询详情、CSV 导出（均按当前用户海区权限过滤）。
 */
@RestController
@RequestMapping("/api/aqua/supervision")
public class SvReportController {

    private final SvReportGenerationService generationService;
    private final SvReportQueryService queryService;
    private final SvGenerateTaskService taskService;

    public SvReportController(SvReportGenerationService generationService,
                              SvReportQueryService queryService,
                              SvGenerateTaskService taskService) {
        this.generationService = generationService;
        this.queryService = queryService;
        this.taskService = taskService;
    }

    /** 人工触发生成（服务层校验海区可见；requestId 幂等；立即执行一次，失败留任务异常链）。 */
    @PostMapping("/reports/generate")
    public ApiResponse<SvGenerateTask> generate(@Validated @RequestBody ManualGenerateRequest request) {
        return ApiResponse.ok(generationService.requestManualGenerate(
                request.getReportMonth(), request.getSeaArea(), request.getRequestId()));
    }

    /** 手工触发到期任务扫描（Quartz 同逻辑）：执行 PENDING/FAILED 未超上限任务。 */
    @PostMapping("/reports/retry-scan")
    public ApiResponse<Map<String, Object>> retryScan() {
        return ApiResponse.ok(generationService.runDueTasks());
    }

    /** 月报列表（当前版本，按授权海区过滤）。 */
    @GetMapping("/reports")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String reportMonth,
            @RequestParam(defaultValue = "true") boolean currentOnly) {
        return ApiResponse.ok(queryService.listView(reportMonth, currentOnly));
    }

    /** 单报详情（越权海区拒绝）。 */
    @GetMapping("/reports/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(queryService.detail(id));
    }

    /** CSV 导出：与列表同一行源、同一海区权限过滤。 */
    @GetMapping(value = "/reports/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String reportMonth,
            @RequestParam(defaultValue = "true") boolean currentOnly) {
        String csv = queryService.exportCsv(reportMonth, currentOnly);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"supervision-monthly-report.csv\"");
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成任务查询（异常链路/重试次数）。 */
    @GetMapping("/tasks")
    public ApiResponse<List<SvGenerateTask>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reportMonth,
            @RequestParam(required = false) String seaArea) {
        return ApiResponse.ok(taskService.list(status, reportMonth, seaArea));
    }

    /** 单任务详情：含每次执行（失败重试）异常链路明细。 */
    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> taskDetail(@PathVariable Long id) {
        SvGenerateTask task = taskService.get(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("task", task);
        view.put("attempts", taskService.listAttempts(id));
        return ApiResponse.ok(view);
    }
}
