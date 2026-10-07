package com.evops.aqua.supervision.controller;

import com.evops.aqua.supervision.dto.CaliberVersionPublishRequest;
import com.evops.aqua.supervision.entity.SvCaliberIndicator;
import com.evops.aqua.supervision.entity.SvCaliberVersion;
import com.evops.aqua.supervision.service.SvCaliberVersionService;
import com.evops.common.ApiResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 水产监管月报口径管理。
 * 鉴权在服务层（SvAccessService.requireCaliberManage）统一执行：
 * 下面同时暴露正式入口与一个别名地址——缺权的海水养殖管理用户无论打哪个地址都会被拒绝，
 * 不存在「换调用地址绕过入口」的路径。
 */
@RestController
@RequestMapping("/api/aqua/supervision")
public class SvCaliberController {

    static final String ENTRY_CALIBER = "POST /api/aqua/supervision/calibers（口径管理正式入口）";
    static final String ENTRY_CALIBER_ALIAS =
            "POST /api/aqua/supervision/caliber-manage/versions（口径管理别名入口，换地址）";

    private final SvCaliberVersionService caliberService;

    public SvCaliberController(SvCaliberVersionService caliberService) {
        this.caliberService = caliberService;
    }

    /** 正式入口：发布统计口径新版本。 */
    @PostMapping("/calibers")
    public ApiResponse<SvCaliberVersion> publish(@Validated @RequestBody CaliberVersionPublishRequest request) {
        return ApiResponse.ok(caliberService.publish(request, ENTRY_CALIBER));
    }

    /** 别名入口（同一服务方法）：换调用地址也必须在服务层被拒绝，不能放行。 */
    @PostMapping("/caliber-manage/versions")
    public ApiResponse<SvCaliberVersion> publishByAlias(@Validated @RequestBody CaliberVersionPublishRequest request) {
        return ApiResponse.ok(caliberService.publish(request, ENTRY_CALIBER_ALIAS));
    }

    @GetMapping("/calibers")
    public ApiResponse<List<SvCaliberVersion>> list() {
        return ApiResponse.ok(caliberService.listVersions(ENTRY_CALIBER));
    }

    @GetMapping("/calibers/{id}/indicators")
    public ApiResponse<List<SvCaliberIndicator>> indicators(@PathVariable Long id) {
        return ApiResponse.ok(caliberService.listIndicators(id, ENTRY_CALIBER));
    }

    @GetMapping("/caliber-manage/current")
    public ApiResponse<Map<String, Object>> current() {
        // 别名读入口同样过闸门
        SvCaliberVersion version = caliberService.listVersions(ENTRY_CALIBER_ALIAS).stream()
                .filter(v -> SvCaliberVersion.STATUS_ACTIVE.equals(v.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("尚无生效口径版本"));
        List<SvCaliberIndicator> indicators =
                caliberService.listIndicators(version.getId(), ENTRY_CALIBER_ALIAS);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", version);
        view.put("indicators", indicators);
        return ApiResponse.ok(view);
    }
}
