package io.github.atengk.solid;

import com.zaxxer.hikari.HikariDataSource;
import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import io.github.atengk.boot.core.properties.AsyncProperties;
import io.github.atengk.boot.core.properties.CoreProperties;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.boot.redis.cache.twolevel.l1.L1MemoryCacheFactory;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.web.dict.EnumDictionaryRegistry;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.ratelimit.aspect.RateLimiterAspect;
import io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import io.github.atengk.solid.common.event.DomainEventPublisher;
import io.github.atengk.solid.common.event.SpringDomainEventPublisher;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

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
    private CoreProperties coreProperties;

    @Autowired
    private WebProperties webProperties;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectProvider<RateLimiterAspect> rateLimiterAspectProvider;

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
        assertThat(saTokenProperties.getToken().isShare()).isFalse();
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
    @DisplayName("应当正确装配 HikariCP 离线连接池、优雅停机策略与多环境分层配置基线")
    void shouldBindPoolAndMultiEnvironmentBaseline() throws IOException, ClassNotFoundException {
        assertThat(Class.forName("org.apache.commons.pool2.impl.GenericObjectPoolConfig")).isNotNull();
        assertThat(Class.forName("com.github.benmanes.caffeine.cache.Caffeine")).isNotNull();
        assertThat(L1MemoryCacheFactory.isCaffeinePresent()).isTrue();

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getPoolName()).isEqualTo("ateng-solid-hikari");
        assertThat(hikari.getMinimumIdle()).isEqualTo(1);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(5);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000L);
        assertThat(hikari.getValidationTimeout()).isEqualTo(3_000L);

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(coreProperties.getSlowAlert().getDefaultThreshold()).isEqualTo(Duration.ofMillis(500));
        assertThat(webProperties.getShutdown().getMvcEndpointEnabled()).isFalse();
        assertThat(webProperties.getShutdown().isEnabled()).isFalse();
        assertThat(webProperties.getShutdown().getDrainTimeout()).isEqualTo(Duration.ZERO);
        assertThat(webProperties.getPayloadLog().isEnabled()).isFalse();
        assertThat(webProperties.getCors().isEnabled()).isFalse();
        assertThat(webProperties.getTrustedProxies()).isEmpty();
        assertThat(webProperties.getOperateLog().getDefaultExcludeParams())
                .contains("password", "phone", "email", "adminPassword", "configValue");
        RateLimiterProperties rateLimiterProperties = Binder.get(environment)
                .bind("ateng.boot.web.ratelimit", RateLimiterProperties.class)
                .get();
        assertThat(rateLimiterProperties.isEnabled()).isFalse();
        assertThat(rateLimiterProperties.getKeyPrefix()).isEqualTo("solid:rate_limit:");
        assertThat(rateLimiterProperties.isFailOpen()).isTrue();
        assertThat(rateLimiterProperties.isIncludeHeaders()).isTrue();
        assertThat(rateLimiterAspectProvider.getIfAvailable()).isNull();

        assertThat(asyncProperties.isWaitForTasksToCompleteOnShutdown()).isTrue();
        assertThat(asyncProperties.getAwaitTermination()).isEqualTo(Duration.ofSeconds(5));
        RedisProperties redisProperties = Binder.get(environment)
                .bind("ateng.boot.redis", RedisProperties.class)
                .get();
        assertThat(redisProperties.getKeyPrefix()).isEqualTo("solid:");
        assertThat(redisProperties.getRedisson().getPingConnectionInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(redisProperties.getRedisson().getLockWatchdogTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(redisProperties.getTwoLevel().isEnabled()).isTrue();
        assertThat(redisProperties.getTwoLevel().getL1().getDefaultMaxSize()).isEqualTo(5000L);
        assertThat(redisProperties.getTwoLevel().getL1().getDefaultTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(redisProperties.getTwoLevel().getL1().getMaxSizes())
                .containsEntry("satoken:permissions", 5000L)
                .containsEntry("dict", 2000L)
                .containsEntry("config", 5000L);
        assertThat(redisProperties.getTwoLevel().getL1().getTtls())
                .containsEntry("satoken:permissions", Duration.ofMinutes(5))
                .containsEntry("dict", Duration.ofMinutes(5))
                .containsEntry("config", Duration.ofMinutes(5));
        assertThat(redisProperties.getTwoLevel().getL2().getDefaultTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(redisProperties.getTwoLevel().getL2().isCacheNullValues()).isTrue();
        assertThat(redisProperties.getTwoLevel().getL2().getNullTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(redisProperties.getTwoLevel().getL2().isJitterEnabled()).isTrue();
        assertThat(redisProperties.getTwoLevel().getL2().getMaxJitter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(redisProperties.getTwoLevel().getL2().getTtls())
                .containsEntry("satoken:permissions", Duration.ofMinutes(30));
        assertThat(mybatisPlusProperties.getBlockAttack().isEnabled()).isTrue();
        assertThat(mybatisPlusProperties.getSlowSql().isEnabled()).isTrue();
        assertThat(mybatisPlusProperties.getSqlLog().isEnabled()).isFalse();

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> baseSources = loader.load("base", new ClassPathResource("application.yml"));
        assertThat(baseSources).hasSize(1);
        PropertySource<?> base = baseSources.getFirst();
        assertThat(base.getProperty("ateng.boot.web.ratelimit.enabled")).isEqualTo(true);
        assertThat(base.getProperty("ateng.boot.web.ratelimit.key-prefix"))
                .isEqualTo("${SOLID_REDIS_KEY_PREFIX:solid:}rate_limit:");
        assertThat(base.getProperty("ateng.boot.web.ratelimit.fail-open")).isEqualTo(true);

        List<PropertySource<?>> testSources = loader.load("test", new ClassPathResource("application-test.yml"));
        assertThat(testSources).hasSize(1);
        assertThat(testSources.getFirst().getProperty("ateng.boot.web.ratelimit.enabled")).isEqualTo(false);

        List<PropertySource<?>> devSources = loader.load("dev", new ClassPathResource("application-dev.yml"));
        assertThat(devSources).hasSize(1);
        assertThat(devSources.getFirst().getProperty("spring.config.import"))
                .isEqualTo("optional:classpath:application-local.yml");
        assertThat(devSources.getFirst().getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo(10);
        assertThat(devSources.getFirst().getProperty("spring.data.redis.lettuce.pool.max-active")).isEqualTo(8);
        assertThat(devSources.getFirst().getProperty("ateng.boot.redis.redisson.connection-pool-size")).isEqualTo(16);

        List<PropertySource<?>> prodSources = loader.load("prod", new ClassPathResource("application-prod.yml"));
        assertThat(prodSources).hasSize(1);
        PropertySource<?> prod = prodSources.getFirst();
        assertThat(prod.getProperty("spring.datasource.username")).isEqualTo("${SOLID_DB_USERNAME}");
        assertThat(prod.getProperty("spring.datasource.password")).isEqualTo("${SOLID_DB_PASSWORD}");
        assertThat(prod.getProperty("spring.data.redis.host")).isEqualTo("${SOLID_REDIS_HOST}");
        assertThat(prod.getProperty("spring.data.redis.password")).isEqualTo("${SOLID_REDIS_PASSWORD}");
        assertThat(prod.getProperty("ateng.boot.mybatis-plus.crypto.secret-key"))
                .isEqualTo("${SOLID_CRYPTO_SECRET_KEY}");
        assertThat(prod.getProperty("ateng.solid.auth.session-handle-secret"))
                .isEqualTo("${SOLID_AUTH_SESSION_HANDLE_SECRET}");
        assertThat(prod.getProperty("spring.flyway.enabled")).isEqualTo("${SOLID_FLYWAY_ENABLED:false}");
        assertThat(prod.getProperty("ateng.boot.core.logging.file.enabled"))
                .isEqualTo("${SOLID_LOG_FILE_ENABLED:true}");
        assertThat(prod.getProperty("ateng.boot.web.payload-log.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("ateng.boot.mybatis-plus.sql-log.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("ateng.boot.core.slow-alert.include-args")).isEqualTo(false);
        assertThat(prod.getProperty("ateng.boot.web.trusted-proxies")).isEqualTo("${SOLID_TRUSTED_PROXIES:}");
        assertThat(prod.getProperty("ateng.boot.mybatis-plus.tenant.enabled")).isEqualTo("${SOLID_TENANT_ENABLED:false}");
        assertThat(prod.getProperty("ateng.solid.init.super-admin-password"))
                .isEqualTo("${SOLID_INIT_SUPER_ADMIN_PASSWORD}");
    }

    @Test
    @DisplayName("应当默认装配 SpringDomainEventPublisher 并扫描注册全部 IBaseEnum 枚举字典")
    void shouldWireCoreInfrastructureBeans() {
        assertThat(domainEventPublisher).isInstanceOf(SpringDomainEventPublisher.class);
        assertThat(enumDictionaryRegistry.getAllOptions()).hasSizeGreaterThanOrEqualTo(6);
    }
}
