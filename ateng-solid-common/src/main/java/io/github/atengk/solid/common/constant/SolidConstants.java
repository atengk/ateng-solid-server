package io.github.atengk.solid.common.constant;

/**
 * 系统全局共享常量定义，统一收敛多租户默认标识、管理端多账号登录体系与平台超管角色编码。
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class SolidConstants {

    private SolidConstants() {
    }

    /**
     * 平台内置默认租户主键（与数据库 tenant_id BIGINT 默认值 0 对齐）
     */
    public static final Long DEFAULT_TENANT_ID = 0L;

    /**
     * 平台内置默认租户业务编码（用于登录页展示与编码检索）
     */
    public static final String DEFAULT_TENANT_CODE = "000000";

    /**
     * 平台超级管理员角色编码（禁止普通租户复用）
     */
    public static final String SUPER_ADMIN_ROLE = "super_admin";

    /**
     * 管理端多账号登录体系标识（对应请求头 satoken-admin）
     */
    public static final String ADMIN_LOGIN_TYPE = "admin";
}
