package io.github.atengk.solid;

import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import io.github.atengk.boot.core.properties.AsyncProperties;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.web.dict.EnumDictionaryRegistry;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import io.github.atengk.solid.common.event.DomainEventPublisher;
import io.github.atengk.solid.common.event.SpringDomainEventPublisher;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双模配置基线与强类型配置装配集成测试（离线 H2 test Profile 启动，验证 3 组 solid 属性、底座安全基线及 Bean 装配）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(classes = SolidApplication.class)
@ActiveProfiles("test")
class ConfigurationBaselineIntegrationTest {

    @Autowired
    private SolidTenantProperties solidTenantProperties;

    @Autowired
    private SolidAuthProperties solidAuthProperties;

    @Autowired
    private SolidSystemProperties solidSystemProperties;

    @Autowired
    private AsyncProperties asyncProperties;

    @Autowired
    private SaTokenProperties saTokenProperties;

    @Autowired
    private MybatisPlusProperties mybatisPlusProperties;

    @Autowired
    private DomainEventPublisher domainEventPublisher;

    @Autowired
    private EnumDictionaryRegistry enumDictionaryRegistry;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("应当正确扫描并绑定 solid 三组按域内聚的 @ConfigurationProperties Bean")
    void shouldBindAllSolidPropertiesBeans() {
        assertThat(solidTenantProperties.isEnabled()).isFalse();
        assertThat(solidTenantProperties.getStatusCacheTtl()).isEqualTo(Duration.ofSeconds(60));

        assertThat(solidAuthProperties.isCaptchaEnabled()).isFalse();
        assertThat(solidAuthProperties.getSessionHandleSecret())
                .isEqualTo("ateng-solid-test-session-handle-secret-key");

        assertThat(solidSystemProperties.getInit().isEnabled()).isTrue();
        assertThat(solidSystemProperties.getInit().getSuperAdminUsername()).isEqualTo("superadmin");
        assertThat(solidSystemProperties.getInit().getSuperAdminNickname()).isEqualTo("超级管理员");
        assertThat(solidSystemProperties.getInit().getSuperAdminPassword()).isEqualTo("Ateng@Test2026");

        assertThat(solidSystemProperties.getDataScope().getMaxDeptInSize()).isEqualTo(1000);
        assertThat(solidSystemProperties.getUser().getImportMaxRows()).isEqualTo(500);
        assertThat(solidSystemProperties.getUser().getMaxConcurrentImports()).isEqualTo(2);
        assertThat(solidSystemProperties.getUser().getImportFeedbackTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(solidSystemProperties.getCache().getDictTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(solidSystemProperties.getCache().getConfigTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("应当正确绑定密码慢哈希专用平台线程池与 Sa-Token / MyBatis-Plus / Storage 安全基线")
    void shouldBindInfrastructureBaselineSettings() {
        AsyncPoolProperties hashPool = asyncProperties.getPools().get("password-hash");
        assertThat(hashPool).isNotNull();
        assertThat(hashPool.getMode()).isEqualTo(AsyncExecutionMode.PLATFORM);
        assertThat(hashPool.getCorePoolSize()).isEqualTo(4);
        assertThat(hashPool.getMaxPoolSize()).isEqualTo(6);
        assertThat(hashPool.getQueueCapacity()).isEqualTo(64);

        assertThat(saTokenProperties.getPrimaryLoginType()).isEqualTo("admin");
        assertThat(saTokenProperties.getSuperAdminRoles()).containsExactly("super_admin");
        assertThat(saTokenProperties.getPermissionCache().isTenantScoped()).isTrue();
        assertThat(saTokenProperties.getTenant().getHeaderName()).isEqualTo("X-Tenant-Id");
        assertThat(saTokenProperties.getTenant().isAllowAnonymousHeader()).isFalse();
        assertThat(saTokenProperties.getTenant().isAllowSuperAdminSwitch()).isTrue();
        assertThat(saTokenProperties.getTenant().getPlatformTenantIds()).containsExactly("0");

        assertThat(mybatisPlusProperties.getDataPermission().isEnabled()).isTrue();
        assertThat(mybatisPlusProperties.getDataPermission().getFailureStrategy().name()).isEqualTo("DENY_ALL");
        assertThat(mybatisPlusProperties.getTenant().isEnabled()).isFalse();
        assertThat(mybatisPlusProperties.getCrypto().getSecretKey())
                .isEqualTo("ateng-solid-test-deterministic-key-32b");

        StorageProperties storageProperties = Binder.get(environment)
                .bind("ateng.boot.storage", StorageProperties.class)
                .get();
        assertThat(storageProperties.getType()).isEqualTo("s3");
        assertThat(storageProperties.getS3().isPathStyle()).isTrue();
        assertThat(storageProperties.getS3().isAutoCreateBucket()).isTrue();
        assertThat(storageProperties.getLocal().isDownloadEndpointEnabled()).isTrue();
        assertThat(storageProperties.getLocal().isUploadEndpointEnabled()).isFalse();
        assertThat(storageProperties.getLocal().isWhitelistPresignedDownload()).isTrue();
        assertThat(storageProperties.getLocal().isWhitelistPresignedUpload()).isFalse();
        assertThat(storageProperties.getGuard().isEnabled()).isTrue();
        assertThat(storageProperties.getGuard().isStrictMagicCheck()).isTrue();
        assertThat(storageProperties.getGuard().getAllowedExtensions())
                .containsExactlyInAnyOrder("jpg", "jpeg", "png", "webp");
        assertThat(storageProperties.getGuard().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(2));
    }

    @Test
    @DisplayName("应当默认装配 SpringDomainEventPublisher 并扫描注册全部 IBaseEnum 枚举字典")
    void shouldWireCoreInfrastructureBeans() {
        assertThat(domainEventPublisher).isInstanceOf(SpringDomainEventPublisher.class);
        assertThat(enumDictionaryRegistry.getAllOptions()).hasSizeGreaterThanOrEqualTo(6);
    }
}
