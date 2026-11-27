package io.github.atengk.solid;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.CacheEvictMessage;
import io.github.atengk.boot.redis.cache.twolevel.DefaultTwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.enums.IdempotentStatus;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedissonRateLimiterStore;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.system.api.command.LoginAuditCommand;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.service.RemoteAuditLogService;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.bootstrap.SuperAdminBootstrapper;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.service.remote.RemoteAuditLogServiceImpl;
import io.github.atengk.solid.system.support.AdaptiveRedisSupport;
import io.github.atengk.solid.system.support.DictDataCache;
import io.github.atengk.solid.system.support.ImportFeedbackStore;
import io.github.atengk.solid.system.support.ImportFeedbackStore.FeedbackFile;
import io.github.atengk.solid.system.support.SysConfigCache;
import io.github.atengk.solid.system.support.TenantStatusCache;
import io.github.atengk.solid.system.support.UserImportSemaphoreCoordinator;
import io.github.atengk.solid.system.vo.DictDataVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地真实 MySQL 与 Redis/Redisson 中间件环境下的 {@code ateng-boot-redis} 受管能力、Jackson 序列化与并发治理集成测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Tag("local")
@EnabledIfSystemProperty(named = "solid.local.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalRedisCapabilityIntegrationTest extends LocalMiddlewareTestSupport {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedisMessageListenerContainer listenerContainer;
    @Autowired
    private TwoLevelCacheManager twoLevelCacheManager;
    @Autowired
    private RedisCacheTemplate redisCacheTemplate;
    @Autowired
    private DistributedLockTemplate distributedLockTemplate;
    @Autowired
    private RedisIdempotentTemplate redisIdempotentTemplate;
    @Autowired
    private RedisSemaphoreTemplate redisSemaphoreTemplate;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private RateLimiterStore rateLimiterStore;
    @Autowired
    private AdaptiveRedisSupport adaptiveRedisSupport;
    @Autowired
    private DictDataCache dictDataCache;
    @Autowired
    private SysConfigCache sysConfigCache;
    @Autowired
    private TenantStatusCache tenantStatusCache;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private RemoteTenantService remoteTenantService;
    @Autowired
    private RemoteAuditLogService remoteAuditLogService;
    @Autowired
    private SuperAdminBootstrapper superAdminBootstrapper;
    @Autowired
    private UserImportSemaphoreCoordinator userImportSemaphoreCoordinator;
    @Autowired
    private ImportFeedbackStore importFeedbackStore;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectProvider<RedisSemaphoreTemplate> redisSemaphoreTemplateProvider;
    @Autowired
    private ObjectProvider<RedisUtil> redisUtilProvider;

    @AfterAll
    void cleanResidualIsolatedRedisKeys() {
        deleteIsolatedKeysByPattern(stringRedisTemplate, "*" + TEST_KEY_PREFIX + "*");
    }

    @Test
    @DisplayName("dev+local 环境应当真实装配 MySQL、Caffeine 两级缓存与全套 ateng-boot-redis 受管模板")
    void shouldWireRealMysqlAndAtengBootRedisManagedComponents() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            assertThat(productName).containsIgnoringCase("MySQL");
        }

        assertThat(adaptiveRedisSupport.isRedisActive()).isTrue();
        assertThat(redisProperties.getKeyPrefix()).isEqualTo(TEST_KEY_PREFIX);
        assertThat(twoLevelCacheManager).isNotNull();
        assertThat(redisCacheTemplate).isNotNull();
        assertThat(distributedLockTemplate).isNotNull();
        assertThat(redisIdempotentTemplate).isNotNull();
        assertThat(redisSemaphoreTemplate).isNotNull();
        assertThat(redisUtil).isNotNull();
        assertThat(redissonClient).isNotNull();
        assertThat(rateLimiterStore).isInstanceOf(RedissonRateLimiterStore.class);

        PermissionCacheService.PermissionCacheAdapter adapter = permissionCacheService.getAdapter();
        assertThat(adapter).isInstanceOf(PermissionCacheService.TwoLevelPermissionCacheAdapter.class);
        TwoLevelCache permissionTwoLevel = ((PermissionCacheService.TwoLevelPermissionCacheAdapter) adapter)
                .getTwoLevelCache();
        assertThat(permissionTwoLevel.getNativeCache().getClass().getSimpleName()).contains("Caffeine");
    }

    @Test
    @DisplayName("DictDataCache、SysConfigCache 与 PermissionCacheService 应当完成真实 Caffeine+Jackson+Pub/Sub 往返")
    void shouldRoundTripTwoLevelCachesWithRealJacksonAndPubSubEviction() throws Exception {
        String dictType = "lt_dict_" + TEST_RUN_ID;
        String emptyDictType = "lt_dict_empty_" + TEST_RUN_ID;
        String dictRedisKey = TEST_KEY_PREFIX + "dict:" + dictType;
        String emptyDictRedisKey = TEST_KEY_PREFIX + "dict:" + emptyDictType;

        long cfgTenantId = 990001L;
        String cfgValKey = "lt_cfg_val_" + TEST_RUN_ID;
        String cfgEmptyKey = "lt_cfg_empty_" + TEST_RUN_ID;
        String cfgNullKey = "lt_cfg_null_" + TEST_RUN_ID;
        String cfgValRedisKey = TEST_KEY_PREFIX + "config:" + cfgTenantId + ":" + cfgValKey;
        String cfgEmptyRedisKey = TEST_KEY_PREFIX + "config:" + cfgTenantId + ":" + cfgEmptyKey;
        String cfgNullRedisKey = TEST_KEY_PREFIX + "config:" + cfgTenantId + ":" + cfgNullKey;

        String permUserId = "lt_perm_" + TEST_RUN_ID;
        String rolesKey = PermissionCacheService.buildUserRolesKey(permUserId, SolidConstants.ADMIN_LOGIN_TYPE, "0");
        String permRedisKey = TEST_KEY_PREFIX + PermissionCacheService.CACHE_SPACE_NAME + ":" + rolesKey;

        String actualTopic = redisProperties.buildKey(twoLevelCacheManager.getTopic());
        List<CacheEvictMessage> capturedMessages = new CopyOnWriteArrayList<>();
        MessageListener spyListener = (message, pattern) -> {
            try {
                byte[] body = message.getBody();
                if (body != null && body.length > 0) {
                    capturedMessages.add(RedisSerializerFactory.createSafeObjectMapper()
                            .readValue(body, CacheEvictMessage.class));
                }
            } catch (Exception ignored) {
            }
        };

        try {
            // 1. DictDataCache：真实 Jackson LongToString 与 LocalDateTime L2 往返
            long snowflakeId = 1900123456789012345L;
            LocalDateTime createdAt = LocalDateTime.of(2026, 11, 27, 9, 30, 15);
            AtomicInteger dictLoaderCalls = new AtomicInteger();

            List<DictDataVO> loadedDict = dictDataCache.getOrLoad(dictType, key -> {
                dictLoaderCalls.incrementAndGet();
                return List.of(DictDataVO.builder()
                        .id(snowflakeId)
                        .dictType(dictType)
                        .dictLabel("本地真缓存")
                        .dictValue("1")
                        .tagType("success")
                        .sort(1)
                        .isDefault(1)
                        .status(0)
                        .createTime(createdAt)
                        .build());
            });
            assertThat(loadedDict).hasSize(1);
            assertThat(dictDataCache.localSize()).isZero();
            assertThat(stringRedisTemplate.hasKey(dictRedisKey)).isTrue();
            assertThat(stringRedisTemplate.getExpire(dictRedisKey, TimeUnit.SECONDS)).isBetween(1700L, 1950L);

            String rawDictJson = stringRedisTemplate.opsForValue().get(dictRedisKey);
            assertThat(rawDictJson)
                    .isNotBlank()
                    .doesNotContain("@class")
                    .contains("\"1900123456789012345\"")
                    .contains("2026-11-27 09:30:15");

            DefaultTwoLevelCache dictTwoLevel = (DefaultTwoLevelCache) twoLevelCacheManager
                    .getTwoLevelCache(DictDataCache.CACHE_SPACE);
            dictTwoLevel.evictLocal(dictType);
            assertThat(dictTwoLevel.getL1Cache().get(dictType)).isNull();

            List<DictDataVO> l2Dict = dictDataCache.getOrLoad(dictType, key -> {
                dictLoaderCalls.incrementAndGet();
                return List.of();
            });
            assertThat(dictLoaderCalls.get()).isEqualTo(1);
            assertThat(l2Dict).hasSize(1);
            assertThat(l2Dict.get(0).getId()).isEqualTo(snowflakeId);
            assertThat(l2Dict.get(0).getDictLabel()).isEqualTo("本地真缓存");
            assertThat(l2Dict.get(0).getCreateTime()).isEqualTo(createdAt);

            // 空列表缓存防穿透
            AtomicInteger emptyDictCalls = new AtomicInteger();
            assertThat(dictDataCache.getOrLoad(emptyDictType, key -> {
                emptyDictCalls.incrementAndGet();
                return Collections.emptyList();
            })).isEmpty();
            dictTwoLevel.evictLocal(emptyDictType);
            assertThat(dictDataCache.getOrLoad(emptyDictType, key -> {
                emptyDictCalls.incrementAndGet();
                return Collections.emptyList();
            })).isEmpty();
            assertThat(emptyDictCalls.get()).isEqualTo(1);

            // 2. 真实 Redis Pub/Sub 跨节点失效入站与出站闭环
            assertThat(dictTwoLevel.getL1Cache().get(dictType)).isNotNull();
            CacheEvictMessage inboundEvict = new CacheEvictMessage(
                    DictDataCache.CACHE_SPACE,
                    dictType,
                    "remote-peer-" + TEST_RUN_ID,
                    false
            );
            redisTemplate.convertAndSend(actualTopic, inboundEvict);
            awaitUntil(Duration.ofSeconds(3),
                    () -> dictTwoLevel.getL1Cache().get(dictType) == null,
                    "等待远端 Pub/Sub 失效消息清除本地 Caffeine L1 超时");

            listenerContainer.addMessageListener(spyListener, new ChannelTopic(actualTopic));
            capturedMessages.clear();
            dictDataCache.getOrLoad(dictType, key -> List.of());
            assertThat(dictTwoLevel.getL1Cache().get(dictType)).isNotNull();

            dictDataCache.evict(dictType);
            assertThat(dictTwoLevel.getL1Cache().get(dictType)).isNull();
            assertThat(stringRedisTemplate.hasKey(dictRedisKey)).isFalse();
            awaitUntil(Duration.ofSeconds(3),
                    () -> capturedMessages.stream().anyMatch(msg ->
                            DictDataCache.CACHE_SPACE.equals(msg.getCacheName())
                                    && dictType.equals(msg.getKey())
                                    && twoLevelCacheManager.getInstanceId().equals(msg.getInstanceId())),
                    "等待本节点 evict 发布 Pub/Sub 出站广播超时");

            // 3. SysConfigCache：真实值、空字符串与负向哨兵穿透 L2 往返
            AtomicInteger valCalls = new AtomicInteger();
            AtomicInteger emptyCalls = new AtomicInteger();
            AtomicInteger nullCalls = new AtomicInteger();
            assertThat(sysConfigCache.getOrLoad(cfgTenantId, cfgValKey, () -> {
                valCalls.incrementAndGet();
                return "Ateng@Init2026";
            })).isEqualTo("Ateng@Init2026");
            assertThat(sysConfigCache.getOrLoad(cfgTenantId, cfgEmptyKey, () -> {
                emptyCalls.incrementAndGet();
                return "";
            })).isEmpty();
            assertThat(sysConfigCache.getOrLoad(cfgTenantId, cfgNullKey, () -> {
                nullCalls.incrementAndGet();
                return null;
            })).isNull();
            assertThat(sysConfigCache.localSize()).isZero();
            assertThat(stringRedisTemplate.hasKey(cfgValRedisKey)).isTrue();
            assertThat(stringRedisTemplate.hasKey(cfgEmptyRedisKey)).isTrue();
            assertThat(stringRedisTemplate.hasKey(cfgNullRedisKey)).isTrue();
            assertThat(stringRedisTemplate.opsForValue().get(cfgValRedisKey)).doesNotContain("@class");

            TwoLevelCache configTwoLevel = twoLevelCacheManager.getTwoLevelCache(SysConfigCache.CACHE_SPACE);
            configTwoLevel.evictLocal(SysConfigCache.buildCacheKey(cfgTenantId, cfgValKey));
            configTwoLevel.evictLocal(SysConfigCache.buildCacheKey(cfgTenantId, cfgEmptyKey));
            configTwoLevel.evictLocal(SysConfigCache.buildCacheKey(cfgTenantId, cfgNullKey));

            assertThat(sysConfigCache.getOrLoad(cfgTenantId, cfgValKey, () -> {
                valCalls.incrementAndGet();
                return "wrong";
            })).isEqualTo("Ateng@Init2026");
            assertThat(sysConfigCache.getOrLoad(cfgTenantId, cfgEmptyKey, () -> {
                emptyCalls.incrementAndGet();
                return "wrong";
            })).isEmpty();
            assertThat(sysConfigCache.getOrLoad(cfgTenantId, cfgNullKey, () -> {
                nullCalls.incrementAndGet();
                return "wrong";
            })).isNull();
            assertThat(valCalls.get()).isEqualTo(1);
            assertThat(emptyCalls.get()).isEqualTo(1);
            assertThat(nullCalls.get()).isEqualTo(1);

            sysConfigCache.evict(cfgTenantId, cfgValKey);
            sysConfigCache.evict(cfgTenantId, cfgEmptyKey);
            sysConfigCache.evict(cfgTenantId, cfgNullKey);
            assertThat(stringRedisTemplate.hasKey(cfgValRedisKey)).isFalse();
            assertThat(stringRedisTemplate.hasKey(cfgEmptyRedisKey)).isFalse();
            assertThat(stringRedisTemplate.hasKey(cfgNullRedisKey)).isFalse();

            // 4. PermissionCacheService：真实 TwoLevelCacheManager + L1/L2 驱逐链路
            permissionCacheService.set(rolesKey, "[\"role_demo\"]", Duration.ofMinutes(5));
            assertThat(stringRedisTemplate.hasKey(permRedisKey)).isTrue();

            permissionCacheService.clearLocalUserCache(SolidConstants.ADMIN_LOGIN_TYPE, "0", permUserId);
            assertThat(permissionCacheService.get(rolesKey)).isEqualTo("[\"role_demo\"]");

            permissionCacheService.clearUserCache(SolidConstants.ADMIN_LOGIN_TYPE, "0", permUserId);
            assertThat(stringRedisTemplate.hasKey(permRedisKey)).isFalse();
            assertThat(permissionCacheService.get(rolesKey)).isNull();
        } finally {
            listenerContainer.removeMessageListener(spyListener, new ChannelTopic(actualTopic));
            stringRedisTemplate.delete(List.of(
                    dictRedisKey, emptyDictRedisKey, cfgValRedisKey, cfgEmptyRedisKey, cfgNullRedisKey, permRedisKey
            ));
        }
    }

    @Test
    @DisplayName("TenantStatusCache 应当经真实 RedisCacheTemplate 完成 L2 序列化、空哨兵与实时过期截断")
    void shouldRoundTripTenantStatusL2CacheWithNullSentinelAndRealtimeExpiry() {
        long activeTenantId = 1900998801123456789L;
        long packageId = 1900998800000000001L;
        long missingTenantId = 1900998802123456789L;
        long expiredTenantId = 1900998803123456789L;

        String activeKey = TEST_KEY_PREFIX + TenantStatusCache.buildCacheKey(activeTenantId);
        String missingKey = TEST_KEY_PREFIX + TenantStatusCache.buildCacheKey(missingTenantId);
        String expiredKey = TEST_KEY_PREFIX + TenantStatusCache.buildCacheKey(expiredTenantId);
        String platformKey = TEST_KEY_PREFIX + TenantStatusCache.buildCacheKey(SolidConstants.PLATFORM_TENANT_ID);

        try {
            // 1. 正常租户 L2 写入与 LongToString / LocalDateTime 还原
            LocalDateTime futureExpire = LocalDateTime.now().plusHours(2).withNano(0);
            AtomicInteger activeCalls = new AtomicInteger();
            TenantAuthDTO first = tenantStatusCache.getOrLoad(activeTenantId, id -> {
                activeCalls.incrementAndGet();
                return TenantAuthDTO.builder()
                        .tenantId(activeTenantId)
                        .tenantCode("LT_ACTIVE")
                        .tenantName("本地激活租户")
                        .packageId(packageId)
                        .expireTime(futureExpire)
                        .status(0)
                        .build();
            });
            TenantAuthDTO second = tenantStatusCache.getOrLoad(activeTenantId, id -> {
                activeCalls.incrementAndGet();
                return null;
            });
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(activeCalls.get()).isEqualTo(1);
            assertThat(tenantStatusCache.localSize()).isZero();
            assertThat(second.getTenantId()).isEqualTo(activeTenantId);
            assertThat(second.getPackageId()).isEqualTo(packageId);
            assertThat(second.getExpireTime()).isEqualTo(futureExpire);
            assertThat(second.isAvailableAt(LocalDateTime.now())).isTrue();

            assertThat(stringRedisTemplate.hasKey(activeKey)).isTrue();
            assertThat(stringRedisTemplate.getExpire(activeKey, TimeUnit.SECONDS)).isPositive();
            assertThat(stringRedisTemplate.opsForValue().get(activeKey))
                    .doesNotContain("@class")
                    .contains("\"1900998801123456789\"");

            // 2. 不存在租户写入 __ATENG_CACHE_NULL__ 防穿透哨兵
            AtomicInteger missingCalls = new AtomicInteger();
            assertThat(tenantStatusCache.getOrLoad(missingTenantId, id -> {
                missingCalls.incrementAndGet();
                return null;
            })).isNull();
            assertThat(stringRedisTemplate.opsForValue().get(missingKey))
                    .contains(RedisCacheTemplate.NULL_SENTINEL);
            assertThat(tenantStatusCache.getOrLoad(missingTenantId, id -> {
                missingCalls.incrementAndGet();
                return TenantAuthDTO.builder().tenantId(missingTenantId).status(0).build();
            })).isNull();
            assertThat(missingCalls.get()).isEqualTo(1);

            // 3. 已过期租户从真实 Redis 读回后按实时 expireTime 0 秒截断
            LocalDateTime pastExpire = LocalDateTime.now().minusSeconds(1).withNano(0);
            tenantStatusCache.getOrLoad(expiredTenantId, id -> TenantAuthDTO.builder()
                    .tenantId(expiredTenantId)
                    .tenantCode("LT_EXPIRED")
                    .tenantName("本地过期租户")
                    .expireTime(pastExpire)
                    .status(0)
                    .build());
            TenantAuthDTO loadedExpired = tenantStatusCache.getOrLoad(expiredTenantId, id -> null);
            assertThat(loadedExpired).isNotNull();
            assertThat(loadedExpired.isAvailableAt(LocalDateTime.now())).isFalse();
            assertThat(loadedExpired.isAvailableAt(pastExpire.minusSeconds(1))).isTrue();

            // 4. 真实 MySQL + Redis 联动读取平台租户 0
            TenantAuthDTO platformTenant = remoteTenantService.getTenantById(SolidConstants.PLATFORM_TENANT_ID);
            assertThat(platformTenant).isNotNull();
            assertThat(remoteTenantService.isTenantAvailable(SolidConstants.PLATFORM_TENANT_ID)).isTrue();
            assertThat(stringRedisTemplate.hasKey(platformKey)).isTrue();

            // 5. 驱逐后物理键立即消失
            tenantStatusCache.evict(activeTenantId);
            tenantStatusCache.evict(missingTenantId);
            tenantStatusCache.evict(expiredTenantId);
            tenantStatusCache.evict(SolidConstants.PLATFORM_TENANT_ID);
            assertThat(stringRedisTemplate.hasKey(activeKey)).isFalse();
            assertThat(stringRedisTemplate.hasKey(missingKey)).isFalse();
            assertThat(stringRedisTemplate.hasKey(expiredKey)).isFalse();
            assertThat(stringRedisTemplate.hasKey(platformKey)).isFalse();
        } finally {
            stringRedisTemplate.delete(List.of(activeKey, missingKey, expiredKey, platformKey));
        }
    }

    @Test
    @DisplayName("SuperAdminBootstrapper 应当在真实 Redisson 锁与 MySQL 下保持互斥与并发幂等")
    void shouldEnforceRealRedissonLockAndIdempotencyForSuperAdminBootstrapper() throws Exception {
        String isolatedUsername = "lt_super_" + TEST_RUN_ID;
        String lockRedisKey = redisProperties.buildKey("lock:", SuperAdminBootstrapper.INIT_LOCK_KEY);

        // 1. 验证若本地库已存在超管，原生引导器执行不写入测试用户
        if (sysUserRoleMapper.countBoundActiveUsersByRole(
                SolidConstants.PLATFORM_TENANT_ID, SolidConstants.SUPER_ADMIN_ROLE_ID) > 0) {
            superAdminBootstrapper.run(new DefaultApplicationArguments());
            Integer existingIsolated = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_user WHERE username = ?", Integer.class, isolatedUsername);
            assertThat(existingIsolated).isZero();
        }

        // 2. 验证真实 Redisson 锁持有期间争锁超时返回 false 且释放后键消失
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holderThread = new Thread(() -> {
            try {
                distributedLockTemplate.tryExecute(SuperAdminBootstrapper.INIT_LOCK_KEY, Duration.ofSeconds(3), () -> {
                    holderEntered.countDown();
                    try {
                        holderRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable ex) {
                holderFailure.set(ex);
                holderEntered.countDown();
            }
        });
        holderThread.start();
        try {
            assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(holderFailure.get()).isNull();
            assertThat(stringRedisTemplate.hasKey(lockRedisKey)).isTrue();

            AtomicBoolean contenderInvoked = new AtomicBoolean(false);
            boolean acquired = adaptiveRedisSupport.tryExecuteWithLock(
                    SuperAdminBootstrapper.INIT_LOCK_KEY,
                    Duration.ofMillis(300),
                    () -> contenderInvoked.set(true)
            );
            assertThat(acquired).isFalse();
            assertThat(contenderInvoked).isFalse();
        } finally {
            holderRelease.countDown();
            holderThread.join(5000);
        }
        assertThat(stringRedisTemplate.hasKey(lockRedisKey)).isFalse();

        // 3. 真实并发 Redisson 锁 + 真实 MySQL 事务建号幂等（仅拦截 countBoundActiveUsersByRole 统计隔离账号）
        SysUserRoleMapper isolatedRoleMapperProxy = (SysUserRoleMapper) Proxy.newProxyInstance(
                SysUserRoleMapper.class.getClassLoader(),
                new Class<?>[]{SysUserRoleMapper.class},
                (proxy, method, args) -> {
                    if ("countBoundActiveUsersByRole".equals(method.getName())) {
                        Long tenantId = (Long) args[0];
                        Long roleId = (Long) args[1];
                        Integer count = jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(1)
                                FROM sys_user_role ur
                                INNER JOIN sys_user u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
                                WHERE ur.tenant_id = ? AND ur.role_id = ? AND u.username = ? AND u.deleted = 0
                                """,
                                Integer.class,
                                tenantId, roleId, isolatedUsername
                        );
                        return count != null ? count : 0;
                    }
                    return method.invoke(sysUserRoleMapper, args);
                }
        );

        SolidSystemProperties props = new SolidSystemProperties();
        props.getInit().setEnabled(true);
        props.getInit().setSuperAdminUsername(isolatedUsername);
        props.getInit().setSuperAdminNickname("本地压测超管");
        props.getInit().setSuperAdminPassword("Ateng@Local2026");

        SuperAdminBootstrapper concurrentBootstrapper = new SuperAdminBootstrapper(
                props, sysUserMapper, sysRoleMapper, sysDeptMapper,
                isolatedRoleMapperProxy, transactionTemplate, adaptiveRedisSupport
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Runnable runner = () -> {
            ready.countDown();
            try {
                startGate.await(5, TimeUnit.SECONDS);
                concurrentBootstrapper.run(new DefaultApplicationArguments());
            } catch (Throwable ex) {
                errors.add(ex);
            }
        };
        Thread t1 = new Thread(runner);
        Thread t2 = new Thread(runner);
        t1.start();
        t2.start();
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();
            t1.join(15_000);
            t2.join(15_000);

            assertThat(errors).isEmpty();
            List<Long> createdIds = jdbcTemplate.queryForList(
                    "SELECT id FROM sys_user WHERE tenant_id = 0 AND username = ? AND deleted = 0",
                    Long.class,
                    isolatedUsername
            );
            assertThat(createdIds).hasSize(1);
            Integer roleBindings = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_user_role WHERE tenant_id = 0 AND user_id = ? AND role_id = ?",
                    Integer.class,
                    createdIds.get(0),
                    SolidConstants.SUPER_ADMIN_ROLE_ID
            );
            assertThat(roleBindings).isEqualTo(1);
        } finally {
            List<Long> createdIds = jdbcTemplate.queryForList(
                    "SELECT id FROM sys_user WHERE username = ?", Long.class, isolatedUsername);
            for (Long uid : createdIds) {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", uid);
                jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", uid);
            }
            stringRedisTemplate.delete(lockRedisKey);
        }
    }

    @Test
    @DisplayName("RemoteAuditLogServiceImpl 应当在真实 RedisIdempotentTemplate 与 MySQL 下实现幂等跳过与失败删除重试")
    void shouldEnforceRealRedisIdempotencyAndRetryOnFailureForLoginAudit() {
        String eventId = "lt-audit-" + TEST_RUN_ID;
        String traceId = "lt-trace-" + TEST_RUN_ID;
        String retryEventId = "lt-audit-retry-" + TEST_RUN_ID;
        String retryTraceId = "lt-trace-retry-" + TEST_RUN_ID;
        String auditUsername = "lt_audit_" + TEST_RUN_ID;

        String relativeKey = RemoteAuditLogServiceImpl.buildEventIdempotentKey(eventId);
        String retryRelativeKey = RemoteAuditLogServiceImpl.buildEventIdempotentKey(retryEventId);

        try {
            // 1. 首次落库 + 重复投递静默跳过
            LoginAuditCommand cmd = LoginAuditCommand.builder()
                    .eventId(eventId)
                    .occurredAt(LocalDateTime.of(2026, 11, 27, 9, 45, 0))
                    .tenantId(SolidConstants.PLATFORM_TENANT_ID)
                    .traceId(traceId)
                    .userId(null)
                    .username(auditUsername)
                    .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                    .clientIp("198.51.100.50")
                    .userAgent("Mozilla/5.0 Chrome/131.0")
                    .status(LogStatusEnum.FAIL.getCode())
                    .message("本地幂等测试失败审计")
                    .build();

            remoteAuditLogService.recordLoginAudit(cmd);
            remoteAuditLogService.recordLoginAudit(cmd);

            assertThat(redisIdempotentTemplate.getStatus(relativeKey)).isEqualTo(IdempotentStatus.SUCCESS);
            String physicalKey = redisIdempotentTemplate.formatKey(relativeKey);
            assertThat(stringRedisTemplate.getExpire(physicalKey, TimeUnit.SECONDS))
                    .isGreaterThan(Duration.ofHours(23).toSeconds());

            Integer persistedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_login_log WHERE trace_id = ?", Integer.class, traceId);
            assertThat(persistedCount).isEqualTo(1);

            // 2. 落库失败时 deleteOnFailure=true 删除幂等键并允许二次重试成功
            AtomicInteger insertAttempts = new AtomicInteger();
            SysLoginLogMapper failingOnceMapper = (SysLoginLogMapper) Proxy.newProxyInstance(
                    SysLoginLogMapper.class.getClassLoader(),
                    new Class<?>[]{SysLoginLogMapper.class},
                    (proxy, method, args) -> {
                        if ("insert".equals(method.getName()) && insertAttempts.incrementAndGet() == 1) {
                            throw new DataAccessResourceFailureException("simulated db error");
                        }
                        return method.invoke(sysLoginLogMapper, args);
                    }
            );
            RemoteAuditLogServiceImpl retryService = new RemoteAuditLogServiceImpl(
                    failingOnceMapper, sysUserMapper, transactionTemplate, adaptiveRedisSupport
            );
            LoginAuditCommand retryCmd = LoginAuditCommand.builder()
                    .eventId(retryEventId)
                    .occurredAt(LocalDateTime.of(2026, 11, 27, 9, 46, 0))
                    .tenantId(SolidConstants.PLATFORM_TENANT_ID)
                    .traceId(retryTraceId)
                    .userId(null)
                    .username(auditUsername)
                    .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                    .clientIp("198.51.100.51")
                    .userAgent("Mozilla/5.0 Edge/130.0")
                    .status(LogStatusEnum.FAIL.getCode())
                    .message("本地失败重试审计")
                    .build();

            assertThatThrownBy(() -> retryService.recordLoginAudit(retryCmd))
                    .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(redisIdempotentTemplate.getStatus(retryRelativeKey)).isNull();
            Integer countAfterFailure = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_login_log WHERE trace_id = ?", Integer.class, retryTraceId);
            assertThat(countAfterFailure).isZero();

            retryService.recordLoginAudit(retryCmd);
            assertThat(redisIdempotentTemplate.getStatus(retryRelativeKey)).isEqualTo(IdempotentStatus.SUCCESS);
            Integer countAfterRetry = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_login_log WHERE trace_id = ?", Integer.class, retryTraceId);
            assertThat(countAfterRetry).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM sys_login_log WHERE trace_id IN (?, ?)", traceId, retryTraceId);
            redisIdempotentTemplate.delete(relativeKey);
            redisIdempotentTemplate.delete(retryRelativeKey);
        }
    }

    @Test
    @DisplayName("UserImportSemaphoreCoordinator 应当在真实 Redisson RSemaphore 下完成启停校准与并发 429 拦截")
    void shouldCalibrateAndEnforceRealRedissonImportSemaphore() throws Exception {
        String permitsMetaFullKey = RedisUtil.buildKey(UserImportSemaphoreCoordinator.PERMITS_META_KEY);
        String calibrationLockFullKey = redisProperties.buildKey("lock:", UserImportSemaphoreCoordinator.CALIBRATION_LOCK_KEY);

        // 验证容器启动阶段已完成首次自动校准
        assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME))
                .isEqualTo(2);
        assertThat(RedisUtil.get(permitsMetaFullKey, Integer.class)).isEqualTo(2);

        try {
            // 1. 重置后验证首次校准、扩容与在途保留
            redisSemaphoreTemplate.delete(UserImportSemaphoreCoordinator.SEMAPHORE_NAME);
            stringRedisTemplate.delete(permitsMetaFullKey);

            newCoordinator(2).calibrateClusterPermits();
            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME)).isEqualTo(2);
            assertThat(RedisUtil.get(permitsMetaFullKey, Integer.class)).isEqualTo(2);

            assertThat(redisSemaphoreTemplate.tryAcquire(
                    UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 1, Duration.ZERO)).isTrue();
            try {
                newCoordinator(4).calibrateClusterPermits();
                assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME))
                        .isEqualTo(3);
            } finally {
                redisSemaphoreTemplate.release(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 1);
            }
            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME)).isEqualTo(4);
            assertThat(RedisUtil.get(permitsMetaFullKey, Integer.class)).isEqualTo(4);

            // 2. 信号量键被外部删除后紧接扩容应走 trySetPermits 全量恢复
            redisSemaphoreTemplate.delete(UserImportSemaphoreCoordinator.SEMAPHORE_NAME);
            newCoordinator(5).calibrateClusterPermits();
            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME)).isEqualTo(5);
            assertThat(RedisUtil.get(permitsMetaFullKey, Integer.class)).isEqualTo(5);

            // 3. 缩容触发 resetPermits
            newCoordinator(2).calibrateClusterPermits();
            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME)).isEqualTo(2);
            assertThat(RedisUtil.get(permitsMetaFullKey, Integer.class)).isEqualTo(2);

            // 4. 严格互斥校准锁被占用时跳过校准，绝不无锁破坏基线
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch lockRelease = new CountDownLatch(1);
            Thread lockHolder = new Thread(() -> {
                RLock lock = redissonClient.getLock(calibrationLockFullKey);
                lock.lock();
                try {
                    lockHeld.countDown();
                    lockRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
            lockHolder.start();
            try {
                assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
                newCoordinator(3).calibrateClusterPermits();
                assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME))
                        .isEqualTo(2);
                assertThat(RedisUtil.get(permitsMetaFullKey, Integer.class)).isEqualTo(2);
            } finally {
                lockRelease.countDown();
                lockHolder.join(5000);
            }

            // 5. 并发度 1 时验证真实排队超时 429 与异常释放归还
            UserImportSemaphoreCoordinator singlePermitCoordinator = newCoordinator(1);
            singlePermitCoordinator.calibrateClusterPermits();
            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME))
                    .isEqualTo(1);

            CountDownLatch permitEntered = new CountDownLatch(1);
            CountDownLatch permitRelease = new CountDownLatch(1);
            Thread importer = new Thread(() -> singlePermitCoordinator.executeWithPermit(() -> {
                permitEntered.countDown();
                try {
                    permitRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }));
            importer.start();
            try {
                assertThat(permitEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> singlePermitCoordinator.executeWithPermit(() -> "blocked"))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode()));
            } finally {
                permitRelease.countDown();
                importer.join(5000);
            }

            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME))
                    .isEqualTo(1);
            assertThat(singlePermitCoordinator.executeWithPermit(() -> "recovered")).isEqualTo("recovered");

            assertThatThrownBy(() -> singlePermitCoordinator.executeWithPermit(() -> {
                throw new IllegalStateException("simulated import failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(redisSemaphoreTemplate.availablePermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME))
                    .isEqualTo(1);
        } finally {
            redisSemaphoreTemplate.delete(UserImportSemaphoreCoordinator.SEMAPHORE_NAME);
            stringRedisTemplate.delete(List.of(permitsMetaFullKey, calibrationLockFullKey));
            userImportSemaphoreCoordinator.calibrateClusterPermits();
        }
    }

    @Test
    @DisplayName("ImportFeedbackStore 应当在真实 RedisUtil 下完成 LongToString 双键往返与 meta-first 403/404 隔离")
    void shouldPersistAndIsolateImportFeedbackDualKeysWithRealLongToString() {
        long tenantId = 1900555566667777888L;
        long operatorUserId = 1900111122223333444L;
        byte[] sampleBytes = "local-feedback-xlsx-bytes".getBytes(StandardCharsets.UTF_8);

        String token = importFeedbackStore.save(tenantId, operatorUserId, "本地批注验证.xlsx", sampleBytes);
        String metaKey = RedisUtil.buildKey(ImportFeedbackStore.META_KEY_PREFIX + token);
        String binKey = RedisUtil.buildKey(ImportFeedbackStore.BIN_KEY_PREFIX + token);

        try {
            assertThat(stringRedisTemplate.hasKey(metaKey)).isTrue();
            assertThat(stringRedisTemplate.hasKey(binKey)).isTrue();
            assertThat(stringRedisTemplate.getExpire(metaKey, TimeUnit.SECONDS)).isGreaterThan(800L);
            assertThat(stringRedisTemplate.getExpire(binKey, TimeUnit.SECONDS)).isGreaterThan(800L);
            assertThat(importFeedbackStore.localSize()).isZero();

            String rawMetaJson = stringRedisTemplate.opsForValue().get(metaKey);
            assertThat(rawMetaJson)
                    .isNotBlank()
                    .doesNotContain("@class")
                    .contains("\"1900555566667777888\"")
                    .contains("\"1900111122223333444\"");

            FeedbackFile loaded = importFeedbackStore.load(token, tenantId, operatorUserId);
            assertThat(loaded.filename()).isEqualTo("本地批注验证.xlsx");
            assertThat(loaded.content()).isEqualTo(sampleBytes);

            // 删除 bin 后仍能先验 meta 返回 403，证明越权请求绝不触碰 bin 大对象
            stringRedisTemplate.delete(binKey);
            assertThatThrownBy(() -> importFeedbackStore.load(token, tenantId + 1, operatorUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
            assertThatThrownBy(() -> importFeedbackStore.load(token, tenantId, operatorUserId + 1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));

            assertThatThrownBy(() -> importFeedbackStore.load(token, tenantId, operatorUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
            assertThatThrownBy(() -> importFeedbackStore.load("0123456789abcdef0123456789abcdef", tenantId, operatorUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
        } finally {
            stringRedisTemplate.delete(List.of(metaKey, binKey));
        }
    }

    private UserImportSemaphoreCoordinator newCoordinator(int maxConcurrent) {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getUser().setMaxConcurrentImports(maxConcurrent);
        return new UserImportSemaphoreCoordinator(
                adaptiveRedisSupport,
                redisSemaphoreTemplateProvider,
                redisUtilProvider,
                props
        );
    }
}
