package io.github.atengk.solid;

import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.template.LockAction;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.solid.common.constant.SolidConstants;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import io.github.atengk.solid.common.validator.PasswordPolicyValidator;
import io.github.atengk.solid.system.bootstrap.SuperAdminBootstrapper;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.support.AdaptiveRedisSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SuperAdminBootstrapper} 首次启动超管引导、幂等重入、口令策略与分布式锁 Layer 2 集成测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:superadmin_bootstrap_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SuperAdminBootstrapperIntegrationTest {

    private static final Pattern GENERATED_PWD_PATTERN = Pattern.compile("初始密码:\\s*(\\S+)");
    private static final String AMBIGUOUS_CHARS = "0Oo1Il";

    @Autowired
    private SuperAdminBootstrapper bootstrapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysDeptMapper sysDeptMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Environment> emptyEnvProvider() {
        return mock(ObjectProvider.class);
    }

    private static AdaptiveRedisSupport offlineAdaptiveSupport() {
        return adaptiveSupport(null, emptyEnvProvider());
    }

    private static AdaptiveRedisSupport adaptiveSupport(
            DistributedLockTemplate lockTemplate,
            ObjectProvider<Environment> environmentProvider
    ) {
        return new AdaptiveRedisSupport(
                environmentProvider,
                providerOf(null),
                providerOf(null),
                providerOf(lockTemplate),
                providerOf(null)
        );
    }

    private SuperAdminBootstrapper newBootstrapper(
            SolidSystemProperties properties,
            AdaptiveRedisSupport adaptiveRedisSupport
    ) {
        return new SuperAdminBootstrapper(
                properties,
                sysUserMapper,
                sysRoleMapper,
                sysDeptMapper,
                sysUserRoleMapper,
                transactionTemplate,
                adaptiveRedisSupport
        );
    }

    private static SolidSystemProperties initProps(boolean enabled, String username, String password) {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getInit().setEnabled(enabled);
        props.getInit().setSuperAdminUsername(username);
        props.getInit().setSuperAdminNickname("超级管理员");
        props.getInit().setSuperAdminPassword(password);
        return props;
    }

    private void resetUsers() {
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @Test
    @Order(1)
    @DisplayName("应用首次启动应当自动创建平台超级管理员并绑定角色 1 与部门 100 且 pwd_update_time 为空")
    void shouldSeedSuperAdminOnApplicationStartup() {
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT id, tenant_id, dept_id, username, nickname, password, pwd_update_time, status, deleted "
                        + "FROM sys_user WHERE tenant_id = 0 AND username = 'superadmin' AND deleted = 0"
        );
        assertThat(((Number) user.get("dept_id")).longValue()).isEqualTo(SolidConstants.DEFAULT_ROOT_DEPT_ID);
        assertThat(user.get("nickname")).isEqualTo("超级管理员");
        assertThat(user.get("pwd_update_time")).isNull();
        assertThat(((Number) user.get("status")).intValue()).isZero();
        assertThat(PasswordUtil.matches("Ateng@Test2026", (String) user.get("password"))).isTrue();

        Long userId = ((Number) user.get("id")).longValue();
        Integer roleBindingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user_role WHERE tenant_id = 0 AND user_id = ? AND role_id = ?",
                Integer.class,
                userId,
                SolidConstants.SUPER_ADMIN_ROLE_ID
        );
        assertThat(roleBindingCount).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("重复触发或已将超管用户名改名后再次触发应当幂等跳过且不重复建号")
    void shouldSkipIdempotentlyOnRerunAndAfterSuperAdminRenamed() throws Exception {
        bootstrapper.run(new DefaultApplicationArguments());
        Integer countAfterRerun = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class);
        assertThat(countAfterRerun).isEqualTo(1);

        jdbcTemplate.update("UPDATE sys_user SET username = 'boss_admin' WHERE username = 'superadmin'");
        bootstrapper.run(new DefaultApplicationArguments());

        Integer countAfterRename = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class);
        assertThat(countAfterRename).isEqualTo(1);
        Integer originalUsernameCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE username = 'superadmin'", Integer.class);
        assertThat(originalUsernameCount).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("外部注入弱口令或空白口令时应当 Fail-Fast 抛出 IllegalStateException 且日志零泄漏口令")
    void shouldFailFastOnWeakExternalPasswordWithoutLoggingSecret(CapturedOutput output) {
        resetUsers();
        String weakSecret = "Weak12";
        SuperAdminBootstrapper weakRunner = newBootstrapper(
                initProps(true, "superadmin", weakSecret),
                offlineAdaptiveSupport()
        );

        assertThatThrownBy(() -> weakRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(weakSecret);
        assertThat(output.getOut() + output.getErr()).doesNotContain(weakSecret);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("外部口令留空时应当生成不含 0Oo1Il 的 16 位强随机密码、仅打印一次并在复用时不重复打印")
    void shouldGenerateSixteenCharPasswordExcludingAmbiguousCharsOnceWhenEmpty(CapturedOutput output) throws Exception {
        resetUsers();
        SuperAdminBootstrapper autoPwdRunner = newBootstrapper(
                initProps(true, "superadmin", ""),
                offlineAdaptiveSupport()
        );

        autoPwdRunner.run(new DefaultApplicationArguments());

        String logs = output.getOut() + output.getErr();
        Matcher matcher = GENERATED_PWD_PATTERN.matcher(logs);
        assertThat(matcher.find()).as("首次空密码建号应当在日志打印一次随机初始密码横幅").isTrue();
        String generated = matcher.group(1);
        assertThat(generated).hasSize(16);
        assertThat(PasswordPolicyValidator.isValid(generated)).isTrue();
        for (char c : AMBIGUOUS_CHARS.toCharArray()) {
            assertThat(generated).doesNotContain(String.valueOf(c));
        }

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE tenant_id = 0 AND username = 'superadmin' AND deleted = 0",
                String.class
        );
        assertThat(PasswordUtil.matches(generated, storedHash)).isTrue();

        // 幂等重入不得再次生成或打印横幅
        int firstCount = 0;
        Matcher allMatches = GENERATED_PWD_PATTERN.matcher(logs);
        while (allMatches.find()) {
            firstCount++;
        }
        autoPwdRunner.run(new DefaultApplicationArguments());
        String logsAfterSecondRun = output.getOut() + output.getErr();
        Matcher secondMatches = GENERATED_PWD_PATTERN.matcher(logsAfterSecondRun);
        int secondCount = 0;
        while (secondMatches.find()) {
            secondCount++;
        }
        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    @Order(5)
    @DisplayName("平台存在未绑定超管角色的同名普通用户时应当抛出 IllegalStateException 防止篡权")
    void shouldThrowIllegalStateWhenUsernameCollidesWithRegularUser() {
        resetUsers();
        SysUser regularUser = new SysUser();
        regularUser.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        regularUser.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
        regularUser.setUsername("superadmin");
        regularUser.setNickname("普通占号用户");
        regularUser.setPassword(PasswordUtil.hash("Ateng@Regular1"));
        regularUser.setGender("2");
        regularUser.setStatus(0);
        sysUserMapper.insert(regularUser);

        SuperAdminBootstrapper collisionRunner = newBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                offlineAdaptiveSupport()
        );

        assertThatThrownBy(() -> collisionRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user_role", Integer.class)).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("应当经 AdaptiveRedisSupport 委派 DistributedLockTemplate（init:super-admin / 5s）并在超时/降级/关闭态守卫建号")
    @SuppressWarnings("unchecked")
    void shouldRespectOptionalRedisDistributedLock() throws InterruptedException {
        assertThat(SuperAdminBootstrapper.INIT_LOCK_KEY).isEqualTo("init:super-admin");
        assertThat(SuperAdminBootstrapper.INIT_LOCK_KEY).doesNotContain("ateng:solid:");

        // 1. 争锁 5s 超时未获得锁 -> 跳过建号
        resetUsers();
        DistributedLockTemplate contendedLock = mock(DistributedLockTemplate.class);
        when(contendedLock.tryExecute(
                eq(SuperAdminBootstrapper.INIT_LOCK_KEY),
                eq(Duration.ofSeconds(5)),
                any(LockAction.class)
        )).thenReturn(false);

        SuperAdminBootstrapper contendedRunner = newBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                adaptiveSupport(contendedLock, emptyEnvProvider())
        );
        contendedRunner.run(new DefaultApplicationArguments());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class)).isZero();

        // 2. 结合真实 DistributedLockTemplate + Mock RLock 校验物理锁键、5s 等待、看门狗与释放；以及冲突异常透传不重放
        resetUsers();
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock rLock = mock(RLock.class);
        when(redissonClient.getLock("solid:lock:init:super-admin")).thenReturn(rLock);
        when(rLock.tryLock(5000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisProperties redisProps = new RedisProperties();
        redisProps.setKeyPrefix("solid:");
        DistributedLockTemplate realTemplate = new DistributedLockTemplate(redissonClient, redisProps);

        SuperAdminBootstrapper acquiredRunner = newBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                adaptiveSupport(realTemplate, emptyEnvProvider())
        );
        acquiredRunner.run(new DefaultApplicationArguments());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class)).isEqualTo(1);
        verify(redissonClient).getLock("solid:lock:init:super-admin");
        verify(rLock).tryLock(5000L, TimeUnit.MILLISECONDS);
        verify(rLock).unlock();

        // 持锁内业务逻辑抛出 IllegalStateException 时应当直接透传且正常解锁，不得触发降级重放
        resetUsers();
        SysUser conflictUser = new SysUser();
        conflictUser.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        conflictUser.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
        conflictUser.setUsername("superadmin");
        conflictUser.setNickname("锁内占号冲突");
        conflictUser.setPassword(PasswordUtil.hash("Ateng@Conflict1"));
        conflictUser.setGender("2");
        conflictUser.setStatus(0);
        sysUserMapper.insert(conflictUser);

        assertThatThrownBy(() -> acquiredRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verify(rLock, times(2)).unlock();

        // 3. 锁模板故障异常 -> 降级直通落库，依赖 DB 二次复核与 DuplicateKeyException 兜底
        resetUsers();
        DistributedLockTemplate failingLock = mock(DistributedLockTemplate.class);
        when(failingLock.tryExecute(
                eq(SuperAdminBootstrapper.INIT_LOCK_KEY),
                eq(Duration.ofSeconds(5)),
                any(LockAction.class)
        )).thenThrow(new IllegalStateException("redisson unavailable"));

        newBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                adaptiveSupport(failingLock, emptyEnvProvider())
        ).run(new DefaultApplicationArguments());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class)).isEqualTo(1);

        // 4. ateng.boot.redis.enabled=false -> 跳过 DistributedLockTemplate 直接落库
        resetUsers();
        DistributedLockTemplate bypassedLock = mock(DistributedLockTemplate.class);
        MockEnvironment disabledEnv = new MockEnvironment().withProperty("ateng.boot.redis.enabled", "false");
        newBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                adaptiveSupport(bypassedLock, providerOf(disabledEnv))
        ).run(new DefaultApplicationArguments());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user", Integer.class)).isEqualTo(1);
        verifyNoInteractions(bypassedLock);
    }

    @Test
    @Order(7)
    @DisplayName("DuplicateKeyException 事务外二次复核应当区分并发超管胜出与非超管占号冲突")
    void shouldDistinguishConcurrentWinnerVsRegularCollisionOutsideTxOnDuplicateKey() {
        // 1. 并发节点抢先完成超管落库且当前事务触发 DuplicateKeyException -> 事务外复核命中绑定超管并幂等跳过
        resetUsers();
        SysUser winnerUser = new SysUser();
        winnerUser.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        winnerUser.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
        winnerUser.setUsername("winner");
        winnerUser.setNickname("先发节点超管");
        winnerUser.setPassword(PasswordUtil.hash("Ateng@Test2026"));
        winnerUser.setGender("2");
        winnerUser.setStatus(0);
        sysUserMapper.insert(winnerUser);

        TransactionTemplate throwingWithConcurrentWinnerTx = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            jdbcTemplate.update(
                    "INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (?, 0, ?, 1)",
                    900001L,
                    winnerUser.getId()
            );
            throw new DuplicateKeyException("duplicate username");
        }).when(throwingWithConcurrentWinnerTx).executeWithoutResult(any());

        SuperAdminBootstrapper concurrentRunner = new SuperAdminBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                sysUserMapper,
                sysRoleMapper,
                sysDeptMapper,
                sysUserRoleMapper,
                throwingWithConcurrentWinnerTx,
                offlineAdaptiveSupport()
        );
        concurrentRunner.run(new DefaultApplicationArguments());

        // 2. 仍无绑定超管（如非超管普通账号占号导致冲突）-> 事务外复核无绑定超管并抛出 IllegalStateException
        resetUsers();
        TransactionTemplate throwingWithoutSuperAdminTx = mock(TransactionTemplate.class);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate regular username"))
                .when(throwingWithoutSuperAdminTx).executeWithoutResult(any());

        SuperAdminBootstrapper orphanConflictRunner = new SuperAdminBootstrapper(
                initProps(true, "superadmin", "Ateng@Test2026"),
                sysUserMapper,
                sysRoleMapper,
                sysDeptMapper,
                sysUserRoleMapper,
                throwingWithoutSuperAdminTx,
                offlineAdaptiveSupport()
        );
        assertThatThrownBy(() -> orphanConflictRunner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
    }
}
