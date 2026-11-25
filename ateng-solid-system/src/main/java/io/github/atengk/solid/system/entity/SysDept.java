package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import io.github.atengk.boot.mybatisplus.handler.DeterministicEncryptTypeHandler;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 组织部门树持久层实体（支持行级多租户隔离及联系字段确定性透明加解密）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true, exclude = {"phone", "email"})
@TableName(value = "sys_dept", autoResultMap = true)
public class SysDept extends BaseLogicEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 上级部门主键（顶级部门为 0）
     */
    private Long parentId;

    /**
     * 祖先部门路径链（如 {@code 0,100}，支持前缀匹配）
     */
    private String ancestors;

    /**
     * 部门名称（同租户同上级下未删除唯一）
     */
    private String deptName;

    /**
     * 显示排序
     */
    private Integer sort;

    /**
     * 部门负责人用户主键
     */
    private Long leaderUserId;

    /**
     * 联系电话（确定性加密存储，空白输入须先归一为 {@code null}）
     */
    @FieldEncrypt(deterministic = true)
    @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
    private String phone;

    /**
     * 电子邮箱（确定性加密存储，小写且空白归一为 {@code null}）
     */
    @FieldEncrypt(deterministic = true)
    @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
    private String email;

    /**
     * 部门启停状态（0=正常，1=停用）
     */
    private Integer status;
}
