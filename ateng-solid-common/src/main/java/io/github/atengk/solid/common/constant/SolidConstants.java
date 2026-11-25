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
     * 平台内置默认租户主键别名，明确平台租户语义
     */
    public static final Long PLATFORM_TENANT_ID = DEFAULT_TENANT_ID;

    /**
     * 平台内置默认租户主键字符串表示
     */
    public static final String PLATFORM_TENANT_ID_STR = "0";

    /**
     * 平台内置默认租户业务编码（用于登录页展示与编码检索）
     */
    public static final String DEFAULT_TENANT_CODE = "000000";

    /**
     * 平台内置默认租户业务编码别名
     */
    public static final String PLATFORM_TENANT_CODE = DEFAULT_TENANT_CODE;

    /**
     * 平台超级管理员角色主键（与确定性初始化种子一致）
     */
    public static final Long SUPER_ADMIN_ROLE_ID = 1L;

    /**
     * 平台超级管理员角色编码（禁止普通租户复用）
     */
    public static final String SUPER_ADMIN_ROLE = "super_admin";

    /**
     * 系统保留管理员角色编码（禁止用户自定义角色占用）
     */
    public static final String RESERVED_ADMIN_ROLE = "admin";

    /**
     * 平台默认根部门主键（与确定性初始化种子一致）
     */
    public static final Long DEFAULT_ROOT_DEPT_ID = 100L;

    /**
     * 管理端多账号登录体系标识（对应请求头 satoken-admin）
     */
    public static final String ADMIN_LOGIN_TYPE = "admin";

    /**
     * 默认账号密码登录授权模式标识
     */
    public static final String DEFAULT_GRANT_TYPE = "password";

    /**
     * 新建用户默认初始密码系统参数键名
     */
    public static final String INIT_PASSWORD_CONFIG_KEY = "sys.user.initPassword";

    /**
     * 多租户请求头名称
     */
    public static final String TENANT_HEADER_NAME = "X-Tenant-Id";

    /**
     * 链路追踪请求头名称
     */
    public static final String TRACE_HEADER_NAME = "X-Trace-Id";

    /**
     * 密码慢哈希专用虚拟/平台线程池名称
     */
    public static final String PASSWORD_HASH_POOL_NAME = "password-hash";
}
