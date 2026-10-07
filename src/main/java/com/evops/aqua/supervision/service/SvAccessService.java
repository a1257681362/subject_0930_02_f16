package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.supervision.entity.SvAccessGrant;
import com.evops.aqua.supervision.entity.SvChangeEvidence;
import com.evops.aqua.supervision.entity.SvGenerateTask;
import com.evops.aqua.supervision.mapper.SvAccessGrantMapper;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 海水养殖管理用户对监管月报的授权闸门。
 * 关键安全要求：口径管理授权在「服务层」统一校验——不依赖任何单一 URL/控制器路径，
 * 因此无论从正式入口还是更换调用地址（别名路径）进来，缺权用户都在此被拒绝并留 DENY 证据。
 * 海区数据可见性：查询/导出/生成都按授权海区过滤，越权海区不可见。
 */
@Service
public class SvAccessService {

    private final SvAccessGrantMapper grantMapper;
    private final SvEvidenceService evidenceService;

    public SvAccessService(SvAccessGrantMapper grantMapper, SvEvidenceService evidenceService) {
        this.grantMapper = grantMapper;
        this.evidenceService = evidenceService;
    }

    /** 当前登录用户名（Shiro 主体）；未登录记 anonymous。 */
    public String currentUsername() {
        try {
            Subject subject = SecurityUtils.getSubject();
            Object principal = subject.getPrincipal();
            return principal == null ? "anonymous" : String.valueOf(principal);
        } catch (Exception ex) {
            // Quartz 等无 Web 安全上下文环境
            return SvGenerateTask.SCHEDULER_OPERATOR;
        }
    }

    public SvAccessGrant getGrant(String username) {
        return grantMapper.selectOne(
                new QueryWrapper<SvAccessGrant>().eq("username", username).last("LIMIT 1"));
    }

    public SvAccessGrant requireAnyGrant() {
        String username = currentUsername();
        SvAccessGrant grant = getGrant(username);
        if (grant == null) {
            deny(username, null, "当前用户未配置任何监管月报海区授权，禁止访问监管月报");
        }
        return grant;
    }

    /**
     * 「水产监管月报口径管理」唯一入口闸门：必须显式具备 caliber_manage_granted。
     * 在 Service 层拒绝——任意控制器路径（含别名/更换调用地址）最终都走这里，缺权一律拒绝。
     * @param entryPoint 调用入口描述（控制器路径/动作），用于留证说明无论从哪个地址进入都被拒
     */
    public SvAccessGrant requireCaliberManage(String entryPoint) {
        String username = currentUsername();
        SvAccessGrant grant = getGrant(username);
        boolean granted = grant != null && Boolean.TRUE.equals(grant.getCaliberManageGranted());
        if (!granted) {
            deny(username, entryPoint,
                    "海水养殖管理用户「" + username + "」缺少【水产监管月报口径管理】权限，入口["
                            + entryPoint + "]（请求地址=" + currentRequestUri() + "）已拒绝，更换调用地址同样不放行");
        }
        return grant;
    }

    /**
     * 当前用户可见海区集合；返回 null 表示全部海区（*）。
     * 无授权记录或未授权任何海区返回空集合（调用方按空过滤，查不到任何数据）。
     */
    public List<String> visibleSeaAreas() {
        SvAccessGrant grant = requireAnyGrant();
        return parseSeaAreas(grant.getSeaAreas());
    }

    /** null=全部海区；否则为受限海区清单。 */
    private List<String> parseSeaAreas(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String trimmed = raw.trim();
        if (SvAccessGrant.ALL_AREAS.equals(trimmed)) {
            return null;
        }
        Set<String> areas = new LinkedHashSet<>();
        for (String part : Arrays.asList(trimmed.split(","))) {
            String a = part.trim();
            if (!a.isEmpty()) {
                areas.add(a);
            }
        }
        return new ArrayList<>(areas);
    }

    /** 校验某海区对当前用户可见，越权抛拒绝。 */
    public void requireSeaAreaVisible(String seaArea) {
        List<String> visible = visibleSeaAreas();
        if (visible != null && !visible.contains(seaArea)) {
            deny(currentUsername(), "sea-area:" + seaArea,
                    "当前用户未被授权海区「" + seaArea + "」，越权访问已拒绝（请求地址="
                            + currentRequestUri() + "）");
        }
    }

    private void deny(String username, String entryPoint, String message) {
        // 独立事务留证：拒绝动作必须可见，不随任何业务回滚丢失。
        evidenceService.recordRequiresNew(SvChangeEvidence.ENTITY_ACCESS_GRANT, 0L,
                SvChangeEvidence.ACTION_DENY, null, username,
                null, entryPoint, message);
        throw new SvAccessDeniedException(message);
    }

    private String currentRequestUri() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "non-web";
            }
            HttpServletRequest request = attrs.getRequest();
            return request.getMethod() + " " + request.getRequestURI();
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
