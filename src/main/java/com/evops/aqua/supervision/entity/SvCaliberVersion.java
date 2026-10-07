package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 监管月报统计口径版本：五指标口径整体一版。
 * 同一时刻仅一个 ACTIVE；发布新版本时旧版本置 SUPERSEDED；
 * 已生成月报冻结其引用版本与指标快照，历史月报永不回改。
 */
@TableName("t_sv_caliber_version")
public class SvCaliberVersion extends BaseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private String versionNo;
    private String status;
    private String remark;
    private String publishedBy;

    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
}
