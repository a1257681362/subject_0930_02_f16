package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 监管月报模块变更证据：口径发布/停用、授权变更、月报生成（成功/失败/重试）均留痕，只追加。
 */
@TableName("t_sv_change_evidence")
public class SvChangeEvidence extends BaseEntity {

    public static final String ENTITY_CALIBER_VERSION = "CALIBER_VERSION";
    public static final String ENTITY_ACCESS_GRANT = "ACCESS_GRANT";
    public static final String ENTITY_REPORT = "REPORT";
    public static final String ENTITY_GENERATE_TASK = "GENERATE_TASK";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_GENERATE = "GENERATE";
    public static final String ACTION_GENERATE_FAIL = "GENERATE_FAIL";
    public static final String ACTION_RETRY = "RETRY";
    public static final String ACTION_DENY = "DENY";

    private String entityType;
    private Long entityId;
    private String action;
    private String requestId;
    private String operator;
    private String beforeJson;
    private String afterJson;
    private String detail;

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }
    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
