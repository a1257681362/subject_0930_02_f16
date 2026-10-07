package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 海水养殖管理用户对监管月报的授权：
 * sea_areas 为可见海区（逗号分隔，* 表示全部海区），导出/查询按此过滤；
 * caliber_manage_granted=0 时该用户访问「水产监管月报口径管理」一律在服务层拒绝，
 * 即使更换调用地址也不放行（闸门在 Service，不绑定单一 URL）。
 */
@TableName("t_sv_access_grant")
public class SvAccessGrant extends BaseEntity {

    public static final String ALL_AREAS = "*";

    private String username;
    private String seaAreas;
    private Boolean caliberManageGranted;
    private String updatedBy;
    private String remark;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getSeaAreas() { return seaAreas; }
    public void setSeaAreas(String seaAreas) { this.seaAreas = seaAreas; }
    public Boolean getCaliberManageGranted() { return caliberManageGranted; }
    public void setCaliberManageGranted(Boolean caliberManageGranted) { this.caliberManageGranted = caliberManageGranted; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
