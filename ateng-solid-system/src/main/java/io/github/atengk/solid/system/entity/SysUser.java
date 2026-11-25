package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import io.github.atengk.boot.mybatisplus.handler.DeterministicEncryptTypeHandler;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 管理端用户主表持久层实体（支持行级多租户隔离及联系字段确定性透明加解密）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true, exclude = {"password", "phone", "email"})
@TableName(value = "sys_user", autoResultMap = true)
public class SysUser extends BaseLogicEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 归属部门主键（允许为空）
     */
    private Long deptId;

    /**
     * 登录账号（同租户未删除唯一，编辑锁定只读）
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * PBKDF2-HMAC-SHA256 慢哈希口令（禁止序列化与日志打印）
     */
    @JsonIgnore
    private String password;

    /**
     * 最近一次自助修改密码时间（为空表示处于初始或重置密码状态）
     */
    private LocalDateTime pwdUpdateTime;

    /**
     * 电子邮箱（确定性加密存储，小写且空白归一为 {@code null}）
     */
    @FieldEncrypt(deterministic = true)
    @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
    private String email;

    /**
     * 手机号码（确定性加密存储，空白输入须先归一为 {@code null}）
     */
    @FieldEncrypt(deterministic = true)
    @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
    private String phone;

    /**
     * 性别字典值（对齐 {@code sys_user_gender}：0=男，1=女，2=未知）
     */
    private String gender;

    /**
     * 头像相对对象键（严禁持久化临时预签名 URL）
     */
    private String avatar;

    /**
     * 账号启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 最近登录客户端 IP
     */
    private String loginIp;

    /**
     * 最近登录时间
     */
    private LocalDateTime loginDate;

    /**
     * 备注
     */
    private String remark;
}
