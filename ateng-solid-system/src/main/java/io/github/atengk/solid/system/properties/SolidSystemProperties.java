package io.github.atengk.solid.system.properties;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 系统管理域核心治理参数强类型配置属性（前缀：{@code ateng.solid}），统一收口超管初始化引导、数据权限上限、批量导入治理与缓存有效期配置。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ConfigurationProperties(prefix = "ateng.solid")
public class SolidSystemProperties {

    /**
     * 首次启动平台超级管理员初始化引导配置
     */
    private InitProperties init = new InitProperties();

    /**
     * 数据权限解析与 SQL 安全边界配置
     */
    private DataScopeProperties dataScope = new DataScopeProperties();

    /**
     * 用户批量导入异步处理与并发治理配置
     */
    private UserProperties user = new UserProperties();

    /**
     * 字典与系统参数分布式缓存 TTL 配置
     */
    private CacheProperties cache = new CacheProperties();

    /**
     * 平台超级管理员初始化引导配置（前缀：{@code ateng.solid.init}）。
     *
     * @author Ateng
     * @since 2026-11-25
     */
    @Data
    @ToString(exclude = "superAdminPassword")
    public static class InitProperties {

        /**
         * 是否启用首次启动自动检查并引导创建平台超级管理员
         */
        private boolean enabled = true;

        /**
         * 首次创建的超级管理员默认登录账号
         */
        private String superAdminUsername = "superadmin";

        /**
         * 首次创建的超级管理员默认展示昵称
         */
        private String superAdminNickname = "超级管理员";

        /**
         * 外部显式注入的超管初始口令（须通过复杂度策略校验；留空时系统自动生成 16 位强随机口令并仅在首次建号告警一次）
         */
        private String superAdminPassword = "";
    }

    /**
     * 数据权限作用域边界配置（前缀：{@code ateng.solid.data-scope}）。
     *
     * @author Ateng
     * @since 2026-11-25
     */
    @Data
    public static class DataScopeProperties {

        /**
         * 部门及下级子孙部门解析后允许拼接 IN 查询的最大部门 ID 数量（超限抛出 100003）
         */
        private int maxDeptInSize = 1000;
    }

    /**
     * 用户管理域导入导出治理配置（前缀：{@code ateng.solid.user}）。
     *
     * @author Ateng
     * @since 2026-11-25
     */
    @Data
    public static class UserProperties {

        /**
         * 单次 Excel 批量导入允许处理的最大有效数据行数
         */
        private int importMaxRows = 500;

        /**
         * 允许同时执行的用户导入任务最大并发数（基于信号量限流）
         */
        private int maxConcurrentImports = 2;

        /**
         * 导入错误回写报告暂存下载有效期
         */
        private Duration importFeedbackTtl = Duration.ofMinutes(15);
    }

    /**
     * 系统基础元数据缓存有效期配置（前缀：{@code ateng.solid.cache}）。
     *
     * @author Ateng
     * @since 2026-11-25
     */
    @Data
    public static class CacheProperties {

        /**
         * 动态数据字典缓存有效期
         */
        private Duration dictTtl = Duration.ofMinutes(30);

        /**
         * 系统参数键值对缓存有效期
         */
        private Duration configTtl = Duration.ofMinutes(30);
    }
}
