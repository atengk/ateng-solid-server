package io.github.atengk.solid.system.api.result;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统管理域业务错误码枚举（号段：102000 ~ 102999），覆盖资源缺失、唯一约束冲突、平台保留保护、引用约束与租户套餐越界校验。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum SystemResultCode implements IResultCode {

    /**
     * 目标实体或数据记录不存在
     */
    RECORD_NOT_FOUND(102001, "目标记录不存在"),

    /**
     * 编码、用户名、手机号或邮箱在同一隔离域内已存在
     */
    DUPLICATE_RESOURCE(102002, "编码或唯一标识已存在"),

    /**
     * 禁止停用、删除或篡改平台内置默认租户
     */
    PLATFORM_TENANT_PROTECTED(102003, "平台默认租户受系统保护，禁止停用、删除或篡改"),

    /**
     * 禁止使用或篡改系统保留角色编码（super_admin、admin）
     */
    RESERVED_ROLE_KEY(102004, "禁止使用保留角色编码 super_admin 或 admin"),

    /**
     * 存在下级子节点或业务关联引用，禁止停用或删除
     */
    RESOURCE_IN_USE(102005, "存在下级子节点或关联引用，禁止停用或删除"),

    /**
     * 超出租户套餐账号配额或越权勾选套餐范围之外的菜单（含平台专属菜单）
     */
    TENANT_QUOTA_OR_SCOPE_EXCEEDED(102006, "超出当前租户套餐账号配额或菜单权限范围"),

    /**
     * 禁止删除或停用超级管理员账号/角色、当前登录用户自身或内置系统参数
     */
    SYSTEM_BUILTIN_PROTECTED(102007, "禁止删除或禁用系统内置核心资源及当前登录用户");

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 默认错误提示文案
     */
    private final String msg;
}
