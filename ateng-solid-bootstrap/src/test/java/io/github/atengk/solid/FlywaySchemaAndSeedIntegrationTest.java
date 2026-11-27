package io.github.atengk.solid;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 全库 17 表建表（V1.0.0）与确定性元数据种子（V1.0.1）Layer 2 集成测试（H2 MODE=MySQL）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:flyway_seed_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
class FlywaySchemaAndSeedIntegrationTest {

    private static final List<String> ALL_TABLES = List.of(
            "sys_tenant",
            "sys_tenant_package",
            "sys_tenant_package_menu",
            "sys_menu",
            "sys_dict_type",
            "sys_dict_data",
            "sys_dept",
            "sys_post",
            "sys_role",
            "sys_user",
            "sys_config",
            "sys_user_role",
            "sys_user_post",
            "sys_role_menu",
            "sys_role_dept",
            "sys_oper_log",
            "sys_login_log"
    );

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("应当成功执行 V1.0.0 ~ V1.0.2 迁移并创建全库 17 张表及角色名称唯一索引")
    void shouldMigrateAllSeventeenTables() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied)
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1.0.0", "1.0.1", "1.0.2");
        assertThat(flyway.info().pending()).isEmpty();

        for (String table : ALL_TABLES) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count)
                    .as("数据表 %s 应当存在且可正常查询", table)
                    .isNotNull()
                    .isGreaterThanOrEqualTo(0);
        }

        Integer roleNameIdxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE UPPER(TABLE_NAME) = 'SYS_ROLE' AND UPPER(INDEX_NAME) = 'UK_ROLE_TENANT_NAME_DELETED'",
                Integer.class
        );
        assertThat(roleNameIdxCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("应当精确落库平台默认租户、超级管理员角色（无角色菜单预插）与平台总部部门种子")
    void shouldSeedTenantRoleDeptAndKeepUserRoleMenuEmpty() {
        Map<String, Object> tenant = jdbcTemplate.queryForMap(
                "SELECT id, tenant_code, tenant_name, package_id, expire_time, account_count, status, deleted, create_by "
                        + "FROM sys_tenant WHERE id = 0"
        );
        assertThat(tenant.get("tenant_code")).isEqualTo("000000");
        assertThat(tenant.get("tenant_name")).isEqualTo("平台默认租户");
        assertThat(tenant.get("package_id")).isNull();
        assertThat(tenant.get("expire_time")).isNull();
        assertThat(((Number) tenant.get("account_count")).intValue()).isEqualTo(-1);
        assertThat(((Number) tenant.get("status")).intValue()).isEqualTo(0);
        assertThat(((Number) tenant.get("deleted")).longValue()).isZero();
        assertThat(tenant.get("create_by")).isEqualTo("SYSTEM");

        Map<String, Object> role = jdbcTemplate.queryForMap(
                "SELECT id, tenant_id, role_name, role_key, data_scope, menu_check_strictly, dept_check_strictly, status, deleted "
                        + "FROM sys_role WHERE id = 1"
        );
        assertThat(((Number) role.get("tenant_id")).longValue()).isZero();
        assertThat(role.get("role_name")).isEqualTo("超级管理员");
        assertThat(role.get("role_key")).isEqualTo("super_admin");
        assertThat(((Number) role.get("data_scope")).intValue()).isEqualTo(1);
        assertThat(((Number) role.get("menu_check_strictly")).intValue()).isEqualTo(1);
        assertThat(((Number) role.get("dept_check_strictly")).intValue()).isEqualTo(0);
        assertThat(((Number) role.get("status")).intValue()).isEqualTo(0);
        assertThat(((Number) role.get("deleted")).longValue()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role_menu", Integer.class)).isZero();

        Map<String, Object> dept = jdbcTemplate.queryForMap(
                "SELECT id, tenant_id, parent_id, ancestors, dept_name, phone, email, sort, status, deleted "
                        + "FROM sys_dept WHERE id = 100"
        );
        assertThat(((Number) dept.get("tenant_id")).longValue()).isZero();
        assertThat(((Number) dept.get("parent_id")).longValue()).isZero();
        assertThat(dept.get("ancestors")).isEqualTo("0");
        assertThat(dept.get("dept_name")).isEqualTo("平台总部");
        assertThat(dept.get("phone")).isNull();
        assertThat(dept.get("email")).isNull();
        assertThat(((Number) dept.get("sort")).intValue()).isEqualTo(1);
        assertThat(((Number) dept.get("status")).intValue()).isEqualTo(0);
        assertThat(((Number) dept.get("deleted")).longValue()).isZero();

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    @DisplayName("应当精确落库 58 条确定性菜单（4 M + 12 C + 42 F）及基础配置目录重定向")
    void shouldSeedFiftyEightMenusWithExactBreakdown() {
        Integer totalMenus = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE deleted = 0", Integer.class);
        Integer dirCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'M' AND deleted = 0", Integer.class);
        Integer pageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'C' AND deleted = 0", Integer.class);
        Integer buttonCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'F' AND deleted = 0", Integer.class);
        Integer platformOnlyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE platform_only = 1 AND deleted = 0", Integer.class);

        assertThat(totalMenus).isEqualTo(58);
        assertThat(dirCount).isEqualTo(4);
        assertThat(pageCount).isEqualTo(12);
        assertThat(buttonCount).isEqualTo(42);
        assertThat(platformOnlyCount).isEqualTo(21);

        String configDirRedirect = jdbcTemplate.queryForObject(
                "SELECT redirect FROM sys_menu WHERE id = 2000", String.class);
        assertThat(configDirRedirect).isEqualTo("/system/config");

        Integer mcWithPerms = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_type IN ('M', 'C') AND perms IS NOT NULL", Integer.class);
        Integer fWithoutPerms = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'F' AND (perms IS NULL OR perms = '')", Integer.class);
        assertThat(mcWithPerms).isZero();
        assertThat(fWithoutPerms).isZero();
    }

    @Test
    @DisplayName("应当精确落库 2 组动态字典及默认初始密码内置系统参数")
    void shouldSeedDictionariesAndInitPasswordConfig() {
        List<String> dictTypes = jdbcTemplate.queryForList(
                "SELECT dict_type FROM sys_dict_type WHERE deleted = 0 ORDER BY id ASC", String.class);
        assertThat(dictTypes).containsExactly("sys_user_gender", "sys_yes_no");

        Integer genderDataCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dict_data WHERE dict_type = 'sys_user_gender' AND deleted = 0", Integer.class);
        Integer yesNoDataCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dict_data WHERE dict_type = 'sys_yes_no' AND deleted = 0", Integer.class);
        assertThat(genderDataCount).isEqualTo(3);
        assertThat(yesNoDataCount).isEqualTo(2);

        String defaultGender = jdbcTemplate.queryForObject(
                "SELECT dict_value FROM sys_dict_data WHERE dict_type = 'sys_user_gender' AND is_default = 1",
                String.class
        );
        String defaultYesNo = jdbcTemplate.queryForObject(
                "SELECT dict_value FROM sys_dict_data WHERE dict_type = 'sys_yes_no' AND is_default = 1",
                String.class
        );
        assertThat(defaultGender).isEqualTo("2");
        assertThat(defaultYesNo).isEqualTo("N");

        Map<String, Object> initPwdConfig = jdbcTemplate.queryForMap(
                "SELECT id, tenant_id, config_name, config_key, config_value, config_type, deleted "
                        + "FROM sys_config WHERE id = 20001"
        );
        assertThat(((Number) initPwdConfig.get("tenant_id")).longValue()).isZero();
        assertThat(initPwdConfig.get("config_key")).isEqualTo("sys.user.initPassword");
        assertThat(initPwdConfig.get("config_value")).isEqualTo("Ateng@2026");
        assertThat(initPwdConfig.get("config_type")).isEqualTo("Y");
        assertThat(((Number) initPwdConfig.get("deleted")).longValue()).isZero();
    }
}
