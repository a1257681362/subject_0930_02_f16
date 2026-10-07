package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.supervision.dto.CaliberVersionPublishRequest;
import com.evops.aqua.supervision.entity.SvCaliberIndicator;
import com.evops.aqua.supervision.entity.SvCaliberVersion;
import com.evops.aqua.supervision.entity.SvChangeEvidence;
import com.evops.aqua.supervision.mapper.SvCaliberIndicatorMapper;
import com.evops.aqua.supervision.mapper.SvCaliberVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 监管月报统计口径版本管理：五指标口径整体版本化。
 * 发布新版本 → 旧 ACTIVE 版本置 SUPERSEDED（只允许一个 ACTIVE）；
 * 已生成月报冻结引用版本与指标快照，历史月报永不回改。
 *
 * 所有写/读入口都先经 SvAccessService.requireCaliberManage 服务层闸门——
 * 缺权的海水养殖管理用户无论从正式入口还是别名地址调用都被拒绝。
 */
@Service
public class SvCaliberVersionService {

    /** 月报固定五指标编码，发布版本必须逐指标配置口径。 */
    public static final Set<String> REQUIRED_INDICATORS = new HashSet<>(Arrays.asList(
            SvCaliberIndicator.CODE_SEEDLING_QTY,
            SvCaliberIndicator.CODE_MEDICATION_QTY,
            SvCaliberIndicator.CODE_MORTALITY_RATE,
            SvCaliberIndicator.CODE_HARVEST_QTY,
            SvCaliberIndicator.CODE_INSPECTION_CONCLUSION));

    private final SvCaliberVersionMapper versionMapper;
    private final SvCaliberIndicatorMapper indicatorMapper;
    private final SvAccessService accessService;
    private final SvEvidenceService evidenceService;

    public SvCaliberVersionService(SvCaliberVersionMapper versionMapper,
                                   SvCaliberIndicatorMapper indicatorMapper,
                                   SvAccessService accessService,
                                   SvEvidenceService evidenceService) {
        this.versionMapper = versionMapper;
        this.indicatorMapper = indicatorMapper;
        this.accessService = accessService;
        this.evidenceService = evidenceService;
    }

    /**
     * 发布口径新版本（唯一写入口，服务层鉴权）。
     * @param entryPoint 调用入口（控制器路径），缺权时由闸门拒绝并留证，换地址同样拒绝
     */
    @Transactional(rollbackFor = Exception.class)
    public SvCaliberVersion publish(CaliberVersionPublishRequest request, String entryPoint) {
        accessService.requireCaliberManage(entryPoint);

        Long duplicated = versionMapper.selectCount(
                new QueryWrapper<SvCaliberVersion>().eq("version_no", request.getVersionNo()));
        if (duplicated != null && duplicated > 0) {
            throw new IllegalArgumentException("口径版本号已存在：" + request.getVersionNo());
        }
        validateIndicators(request);

        String operator = accessService.currentUsername();
        SvCaliberVersion version = new SvCaliberVersion();
        version.setVersionNo(request.getVersionNo());
        version.setStatus(SvCaliberVersion.STATUS_ACTIVE);
        version.setRemark(request.getRemark());
        version.setPublishedBy(operator);
        versionMapper.insert(version);

        int sort = 0;
        for (CaliberVersionPublishRequest.IndicatorCaliberDto dto : request.getIndicators()) {
            SvCaliberIndicator indicator = new SvCaliberIndicator();
            indicator.setVersionId(version.getId());
            indicator.setIndicatorCode(dto.getIndicatorCode());
            indicator.setIndicatorName(dto.getIndicatorName());
            indicator.setSourceTable(dto.getSourceTable());
            indicator.setDateColumn(dto.getDateColumn());
            indicator.setAggExpression(dto.getAggExpression());
            indicator.setUnit(dto.getUnit());
            indicator.setMissingRule(dto.getMissingRule());
            indicator.setSortNo(dto.getSortNo() == null ? sort : dto.getSortNo());
            indicatorMapper.insert(indicator);
            sort++;
        }

        // 旧生效版本停用：只允许一个 ACTIVE
        List<SvCaliberVersion> actives = versionMapper.selectList(
                new QueryWrapper<SvCaliberVersion>()
                        .eq("status", SvCaliberVersion.STATUS_ACTIVE)
                        .ne("id", version.getId()));
        for (SvCaliberVersion old : actives) {
            SvCaliberVersion before = copyOf(old);
            old.setStatus(SvCaliberVersion.STATUS_SUPERSEDED);
            versionMapper.updateById(old);
            evidenceService.record(SvChangeEvidence.ENTITY_CALIBER_VERSION, old.getId(),
                    SvChangeEvidence.ACTION_UPDATE, null, operator, before, old,
                    "发布口径新版本" + version.getVersionNo() + "，旧版本" + old.getVersionNo() + "停用");
        }

        evidenceService.record(SvChangeEvidence.ENTITY_CALIBER_VERSION, version.getId(),
                SvChangeEvidence.ACTION_CREATE, null, operator, null, version,
                "发布监管月报统计口径版本" + version.getVersionNo() + "，指标数="
                        + request.getIndicators().size());
        return version;
    }

    /** 口径管理读入口同样鉴权：缺权用户既不能改也不能查看口径配置。 */
    public List<SvCaliberVersion> listVersions(String entryPoint) {
        accessService.requireCaliberManage(entryPoint);
        return versionMapper.selectList(
                new QueryWrapper<SvCaliberVersion>().orderByDesc("id"));
    }

    public List<SvCaliberIndicator> listIndicators(Long versionId, String entryPoint) {
        accessService.requireCaliberManage(entryPoint);
        return indicatorMapper.selectList(
                new QueryWrapper<SvCaliberIndicator>().eq("version_id", versionId)
                        .orderByAsc("sort_no", "id"));
    }

    /** 当前生效口径版本（供月报生成使用）。 */
    public SvCaliberVersion currentActive() {
        List<SvCaliberVersion> actives = versionMapper.selectList(
                new QueryWrapper<SvCaliberVersion>().eq("status", SvCaliberVersion.STATUS_ACTIVE)
                        .orderByDesc("id").last("LIMIT 1"));
        if (actives.isEmpty()) {
            throw new IllegalStateException("尚无生效的水产监管月报统计口径版本，请先发布");
        }
        return actives.get(0);
    }

    public SvCaliberVersion getOrThrow(Long id) {
        SvCaliberVersion version = versionMapper.selectById(id);
        if (version == null) {
            throw new IllegalArgumentException("口径版本不存在，id=" + id);
        }
        return version;
    }

    public List<SvCaliberIndicator> indicatorsOf(Long versionId) {
        return indicatorMapper.selectList(
                new QueryWrapper<SvCaliberIndicator>().eq("version_id", versionId)
                        .orderByAsc("sort_no", "id"));
    }

    private void validateIndicators(CaliberVersionPublishRequest request) {
        Set<String> seen = new HashSet<>();
        for (CaliberVersionPublishRequest.IndicatorCaliberDto dto : request.getIndicators()) {
            if (!seen.add(dto.getIndicatorCode())) {
                throw new IllegalArgumentException("口径版本内指标编码重复：" + dto.getIndicatorCode());
            }
        }
        for (String required : REQUIRED_INDICATORS) {
            if (!seen.contains(required)) {
                throw new IllegalArgumentException("口径版本缺少必填指标：" + required);
            }
        }
    }

    private SvCaliberVersion copyOf(SvCaliberVersion source) {
        SvCaliberVersion copy = new SvCaliberVersion();
        copy.setId(source.getId());
        copy.setVersionNo(source.getVersionNo());
        copy.setStatus(source.getStatus());
        copy.setRemark(source.getRemark());
        copy.setPublishedBy(source.getPublishedBy());
        return copy;
    }
}
