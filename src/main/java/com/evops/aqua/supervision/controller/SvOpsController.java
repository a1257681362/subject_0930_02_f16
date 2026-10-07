package com.evops.aqua.supervision.controller;

import com.evops.aqua.supervision.dto.FailureInjectRequest;
import com.evops.aqua.supervision.entity.SvChangeEvidence;
import com.evops.aqua.supervision.service.SvEvidenceService;
import com.evops.aqua.supervision.service.SvFailureInjector;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 监管月报证据追溯与故障演练。
 * 证据（含入口拒绝 DENY、生成失败/重试）可按对象或 requestId 追溯；
 * 故障注入仅用于验收/演练失败任务异常链路与重试恢复。
 */
@RestController
@RequestMapping("/api/aqua/supervision")
public class SvOpsController {

    private final SvEvidenceService evidenceService;
    private final SvFailureInjector failureInjector;

    public SvOpsController(SvEvidenceService evidenceService, SvFailureInjector failureInjector) {
        this.evidenceService = evidenceService;
        this.failureInjector = failureInjector;
    }

    @GetMapping("/evidence")
    public ApiResponse<List<SvChangeEvidence>> evidence(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String requestId) {
        return ApiResponse.ok(evidenceService.list(entityType, entityId, requestId));
    }

    /** 演练：对指定报告期+海区注入/解除生成失败。 */
    @PostMapping("/failure-inject")
    public ApiResponse<String> failureInject(@Validated @RequestBody FailureInjectRequest request) {
        if (Boolean.FALSE.equals(request.getEnabled())) {
            failureInjector.disable(request.getReportMonth(), request.getSeaArea());
            return ApiResponse.ok("已解除故障注入：" + request.getReportMonth() + "@" + request.getSeaArea());
        }
        failureInjector.enable(request.getReportMonth(), request.getSeaArea());
        return ApiResponse.ok("已注入生成失败：" + request.getReportMonth() + "@" + request.getSeaArea());
    }
}
