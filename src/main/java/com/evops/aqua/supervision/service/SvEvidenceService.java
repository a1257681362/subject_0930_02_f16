package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.supervision.entity.SvChangeEvidence;
import com.evops.aqua.supervision.mapper.SvChangeEvidenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 监管月报变更证据：所有写操作（口径发布/停用、授权变更、月报生成成功/失败/重试、入口拒绝）
 * 统一落 t_sv_change_evidence，只追加。
 * recordRequiresNew 用独立事务——入口拒绝/生成失败等场景业务回滚时证据仍须保留。
 */
@Service
public class SvEvidenceService {

    private final SvChangeEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public SvEvidenceService(SvChangeEvidenceMapper evidenceMapper, ObjectMapper objectMapper) {
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    public void record(String entityType, Long entityId, String action, String requestId,
                       String operator, Object before, Object after, String detail) {
        doRecord(entityType, entityId, action, requestId, operator, before, after, detail);
    }

    /** 独立事务留证：调用方业务回滚（如入口拒绝、生成失败）时证据不随事务丢失。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordRequiresNew(String entityType, Long entityId, String action, String requestId,
                                  String operator, Object before, Object after, String detail) {
        doRecord(entityType, entityId, action, requestId, operator, before, after, detail);
    }

    private void doRecord(String entityType, Long entityId, String action, String requestId,
                          String operator, Object before, Object after, String detail) {
        SvChangeEvidence evidence = new SvChangeEvidence();
        evidence.setEntityType(entityType);
        evidence.setEntityId(entityId == null ? 0L : entityId);
        evidence.setAction(action);
        evidence.setRequestId(requestId);
        evidence.setOperator(operator == null ? "anonymous" : operator);
        evidence.setBeforeJson(toJson(before));
        evidence.setAfterJson(toJson(after));
        evidence.setDetail(detail);
        evidenceMapper.insert(evidence);
    }

    public List<SvChangeEvidence> list(String entityType, Long entityId, String requestId) {
        QueryWrapper<SvChangeEvidence> wrapper = new QueryWrapper<>();
        wrapper.eq(entityType != null && !entityType.trim().isEmpty(), "entity_type", entityType);
        wrapper.eq(entityId != null, "entity_id", entityId);
        wrapper.eq(requestId != null && !requestId.trim().isEmpty(), "request_id", requestId);
        wrapper.orderByAsc("id");
        return evidenceMapper.selectList(wrapper);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
