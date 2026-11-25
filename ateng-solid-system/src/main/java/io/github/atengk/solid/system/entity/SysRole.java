package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 角色与数据权限持久层实体（支持行级多租户隔离）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseLogicEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 角色编码（同租户未删除唯一；普通租户禁止使用保留编码）
     */
    private String roleKey;

    /**
     * 数据权限范围（1=ALL，2=CUSTOM，3=DEPT，4=DEPT_AND_CHILD，5=SELF）
     */
    private Integer dataScope;

    /**
     * 菜单权限树是否父子联动（true=联动，false=独立）
     */
    private Boolean menuCheckStrictly;

    /**
     * 自定部门树是否父子联动（false=独立勾选，true=联动）
     */
    private Boolean deptCheckStrictly;

    /**
     * 显示排序
     */
    private Integer sort;

    /**
     * 角色启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;
}
