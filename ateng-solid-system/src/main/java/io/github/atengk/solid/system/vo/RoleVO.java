package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 角色分页列表视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class RoleVO {

    /**
     * 角色主键
     */
    Long id;

    /**
     * 角色名称
     */
    String roleName;

    /**
     * 角色编码
     */
    String roleKey;

    /**
     * 数据权限范围（1=ALL，2=CUSTOM，3=DEPT，4=DEPT_AND_CHILD，5=SELF）
     */
    Integer dataScope;

    /**
     * 菜单权限树是否父子联动
     */
    Boolean menuCheckStrictly;

    /**
     * 自定部门树是否父子联动
     */
    Boolean deptCheckStrictly;

    /**
     * 显示排序
     */
    Integer sort;

    /**
     * 角色状态（0=正常，1=停用）
     */
    Integer status;

    /**
     * 备注
     */
    String remark;

    /**
     * 是否为平台内置超级管理员角色
     */
    boolean builtinSuperAdmin;

    /**
     * 创建时间
     */
    LocalDateTime createTime;
}
