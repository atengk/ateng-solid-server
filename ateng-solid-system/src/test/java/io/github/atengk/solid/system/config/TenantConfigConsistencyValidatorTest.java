package io.github.atengk.solid.system.config;

import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.request.ConfigSaveRequest;
import io.github.atengk.solid.system.request.DeptSaveRequest;
import io.github.atengk.solid.system.request.TenantCreateRequest;
import io.github.atengk.solid.system.request.UserCreateRequest;
import io.github.atengk.solid.system.request.UserResetPasswordRequest;
import io.github.atengk.solid.system.request.UserUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenantConfigConsistencyValidator} 与请求载荷 {@code toString()} 敏感字段脱敏单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class TenantConfigConsistencyValidatorTest {

    @Test
    @DisplayName("多租户双开关不一致时启动校验应当抛出 IllegalStateException，一致时正常放行")
    void shouldRejectMismatchedTenantSwitchesAndAllowConsistentPairs() {
        assertThatCode(() -> validator(false, false).validateConsistency()).doesNotThrowAnyException();
        assertThatCode(() -> validator(true, true).validateConsistency()).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator(false, true).validateConsistency())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ateng.boot.mybatis-plus.tenant.enabled=false")
                .hasMessageContaining("ateng.solid.tenant.enabled=true");

        assertThatThrownBy(() -> validator(true, false).validateConsistency())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ateng.boot.mybatis-plus.tenant.enabled=true")
                .hasMessageContaining("ateng.solid.tenant.enabled=false");
    }

    @Test
    @DisplayName("含口令、联系方式或敏感参数键值的请求 DTO toString 不得泄漏明文")
    void shouldExcludeSensitiveFieldsFromRequestToString() {
        UserCreateRequest createUser = new UserCreateRequest();
        createUser.setUsername("tester01");
        createUser.setPassword("PlainPwd#123");
        createUser.setPhone("13912345678");
        createUser.setEmail("secret.user@example.com");
        assertThat(createUser.toString())
                .contains("tester01")
                .doesNotContain("PlainPwd#123", "13912345678", "secret.user@example.com");

        UserUpdateRequest updateUser = new UserUpdateRequest();
        updateUser.setNickname("测试昵称");
        updateUser.setPhone("13800001111");
        updateUser.setEmail("update.secret@example.com");
        assertThat(updateUser.toString())
                .contains("测试昵称")
                .doesNotContain("13800001111", "update.secret@example.com");

        UserResetPasswordRequest resetPwd = new UserResetPasswordRequest();
        resetPwd.setPassword("ResetSecret#99");
        assertThat(resetPwd.toString()).doesNotContain("ResetSecret#99");

        TenantCreateRequest createTenant = new TenantCreateRequest();
        createTenant.setTenantCode("T9001");
        createTenant.setAdminPassword("TenantSecret#99");
        assertThat(createTenant.toString())
                .contains("T9001")
                .doesNotContain("TenantSecret#99");

        ConfigSaveRequest saveConfig = new ConfigSaveRequest();
        saveConfig.setConfigKey("app.api.secret");
        saveConfig.setConfigValue("sk-secret-value-xyz");
        assertThat(saveConfig.toString())
                .contains("app.api.secret")
                .doesNotContain("sk-secret-value-xyz");

        DeptSaveRequest saveDept = new DeptSaveRequest();
        saveDept.setDeptName("研发部门");
        saveDept.setPhone("13700002222");
        saveDept.setEmail("dept.secret@example.com");
        assertThat(saveDept.toString())
                .contains("研发部门")
                .doesNotContain("13700002222", "dept.secret@example.com");
    }

    private static TenantConfigConsistencyValidator validator(boolean mpEnabled, boolean solidEnabled) {
        MybatisPlusProperties mpProps = new MybatisPlusProperties();
        mpProps.getTenant().setEnabled(mpEnabled);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(solidEnabled);
        return new TenantConfigConsistencyValidator(mpProps, tenantProps);
    }
}
