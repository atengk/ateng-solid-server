package io.github.atengk.solid.system.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.validator.PasswordPolicyValidator;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.support.AdaptiveRedisSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 平台首次启动超级管理员自动初始化引导器：支持外部强口令策略 Fail-Fast、留空生成 16 位无歧义强随机口令、
 * 经 {@link AdaptiveRedisSupport} 委派分布式锁防并发重复建号及事务外 {@link DuplicateKeyException} 二次复核。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminBootstrapper implements ApplicationRunner {

    /**
     * 平台超管初始化分布式锁领域相对键（受 {@code ateng.boot.redis.key-prefix} 托管，默认物理键 {@code solid:lock:init:super-admin}）
     */
    public static final String INIT_LOCK_KEY = "init:super-admin";

    private static final Duration INIT_LOCK_WAIT_TIME = Duration.ofSeconds(5);
    private static final int GENERATED_PASSWORD_LENGTH = 16;
    private static final String DEFAULT_NICKNAME = "超级管理员";
    /**
     * 对齐动态字典 {@code sys_user_gender} 默认未知性别值（0=男，1=女，2=未知）
     */
    private static final String UNKNOWN_GENDER_DICT_VALUE = "2";

    // 显式剔除 0 / O / o / 1 / I / l 易混淆字符，并保证 4 类字符均至少出现 1 位
    private static final String UPPER_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER_CHARS = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGIT_CHARS = "23456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*-_=+";
    private static final String ALL_PASSWORD_CHARS = UPPER_CHARS + LOWER_CHARS + DIGIT_CHARS + SPECIAL_CHARS;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SolidSystemProperties systemProperties;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final TransactionTemplate transactionTemplate;
    private final AdaptiveRedisSupport adaptiveRedisSupport;

    @Override
    public void run(ApplicationArguments args) {
        SolidSystemProperties.InitProperties init = systemProperties != null ? systemProperties.getInit() : null;
        if (init == null || !init.isEnabled()) {
            log.debug("平台超级管理员初始化引导开关已关闭，跳过执行");
            return;
        }

        String username = init.getSuperAdminUsername() != null ? init.getSuperAdminUsername().trim() : "";
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("平台超级管理员初始化配置错误：super-admin-username 不能为空");
        }

        String configuredPassword = init.getSuperAdminPassword();
        boolean hasExternalPassword = configuredPassword != null && !configuredPassword.isEmpty();
        if (hasExternalPassword && !PasswordPolicyValidator.isValid(configuredPassword)) {
            throw new IllegalStateException("外部配置的超级管理员初始密码不符合安全策略（须 8~20 位，不含空白字符且至少包含大写、小写、数字、特殊字符中的 3 类）");
        }

        if (hasBoundSuperAdmin()) {
            log.info("平台默认租户已存在有效超级管理员账号，跳过初始化引导");
            return;
        }

        boolean executed = adaptiveRedisSupport.tryExecuteWithLock(
                INIT_LOCK_KEY,
                INIT_LOCK_WAIT_TIME,
                () -> doBootstrap(init, username, configuredPassword, hasExternalPassword)
        );
        if (!executed) {
            if (hasBoundSuperAdmin()) {
                log.info("等待平台超级管理员初始化分布式锁超时（{}s），其他实例已完成建号，当前节点跳过",
                        INIT_LOCK_WAIT_TIME.toSeconds());
            } else {
                log.warn("等待平台超级管理员初始化分布式锁超时（{}s）且尚未检测到超管绑定记录，当前节点跳过重复初始化",
                        INIT_LOCK_WAIT_TIME.toSeconds());
            }
        }
    }

    private void doBootstrap(
            SolidSystemProperties.InitProperties init,
            String username,
            String configuredPassword,
            boolean hasExternalPassword
    ) {
        if (hasBoundSuperAdmin()) {
            log.info("平台默认租户已存在有效超级管理员账号，跳过初始化引导");
            return;
        }
        if (findPlatformUserByUsername(username) != null) {
            throw new IllegalStateException(
                    "平台租户中已存在未绑定超管角色的同名账号，无法完成超管初始化: username=" + username);
        }

        verifyPlatformSeedsExist();

        String rawPassword = hasExternalPassword ? configuredPassword : generateStrongRandomPassword();
        String passwordHash = PasswordUtil.hash(rawPassword);
        String nickname = StringUtils.hasText(init.getSuperAdminNickname())
                ? init.getSuperAdminNickname().trim()
                : DEFAULT_NICKNAME;

        boolean created = persistSuperAdminAtomically(username, nickname, passwordHash);
        if (created) {
            logBootstrapSuccess(username, rawPassword, hasExternalPassword);
        }
    }

    private boolean persistSuperAdminAtomically(String username, String nickname, String passwordHash) {
        AtomicBoolean inserted = new AtomicBoolean(false);
        try {
            transactionTemplate.executeWithoutResult(status -> TenantContextHolder.runWithTenant(
                    SolidConstants.PLATFORM_TENANT_ID,
                    () -> {
                        if (hasBoundSuperAdminInternal()) {
                            log.info("平台默认租户已存在有效超级管理员账号，跳过事务落库");
                            return;
                        }
                        if (findPlatformUserByUsernameInternal(username) != null) {
                            throw new IllegalStateException(
                                    "平台租户中已存在同名普通账号，无法初始化超级管理员: username=" + username);
                        }
                        insertSuperAdminAndRoleBinding(username, nickname, passwordHash);
                        inserted.set(true);
                    }
            ));
        } catch (DuplicateKeyException ex) {
            // 事务已回滚，须在事务外读取已提交状态区分并发超管胜出还是同名普通账号占位
            if (hasBoundSuperAdmin()) {
                log.info("并发初始化已由其他实例提交平台超级管理员，当前节点幂等跳过");
                return false;
            }
            throw new IllegalStateException(
                    "平台超级管理员初始化遭遇唯一键冲突，且不存在有效超管角色绑定，请检查账号冲突: username=" + username,
                    ex
            );
        }
        return inserted.get();
    }

    private void insertSuperAdminAndRoleBinding(String username, String nickname, String passwordHash) {
        SysUser user = new SysUser();
        user.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        user.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(passwordHash);
        user.setPwdUpdateTime(null);
        user.setGender(UNKNOWN_GENDER_DICT_VALUE);
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        user.setRemark("系统首次启动自动初始化的平台超级管理员");
        sysUserMapper.insert(user);

        SysUserRole userRole = new SysUserRole();
        userRole.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        userRole.setUserId(user.getId());
        userRole.setRoleId(SolidConstants.SUPER_ADMIN_ROLE_ID);
        sysUserRoleMapper.insert(userRole);
    }

    private void logBootstrapSuccess(String username, String rawPassword, boolean hasExternalPassword) {
        if (hasExternalPassword) {
            log.info("平台超级管理员账号初始化完成（已应用外部注入口令）: username={}", username);
        } else {
            log.warn("""
                    
                    ====================================================================
                      [ateng-solid] 首次启动自动创建平台超级管理员成功（请妥善保存并在首次登录后修改）
                      登录账号: {}
                      初始密码: {}
                    ====================================================================
                    """, username, rawPassword);
        }
    }

    private void verifyPlatformSeedsExist() {
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysRole superAdminRole = sysRoleMapper.selectById(SolidConstants.SUPER_ADMIN_ROLE_ID);
            if (superAdminRole == null || !CommonStatusEnum.NORMAL.getCode().equals(superAdminRole.getStatus())) {
                throw new IllegalStateException("平台内置超级管理员角色（id=1）缺失或未启用，请先完成 Flyway 初始化种子迁移");
            }
            SysDept rootDept = sysDeptMapper.selectById(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            if (rootDept == null || !CommonStatusEnum.NORMAL.getCode().equals(rootDept.getStatus())) {
                throw new IllegalStateException("平台默认根部门（id=100）缺失或未启用，请先完成 Flyway 初始化种子迁移");
            }
        });
    }

    private boolean hasBoundSuperAdmin() {
        return Boolean.TRUE.equals(TenantContextHolder.runWithTenant(
                SolidConstants.PLATFORM_TENANT_ID,
                this::hasBoundSuperAdminInternal
        ));
    }

    private boolean hasBoundSuperAdminInternal() {
        return sysUserRoleMapper.countBoundActiveUsersByRole(
                SolidConstants.PLATFORM_TENANT_ID,
                SolidConstants.SUPER_ADMIN_ROLE_ID
        ) > 0;
    }

    private SysUser findPlatformUserByUsername(String username) {
        return TenantContextHolder.runWithTenant(
                SolidConstants.PLATFORM_TENANT_ID,
                () -> findPlatformUserByUsernameInternal(username)
        );
    }

    private SysUser findPlatformUserByUsernameInternal(String username) {
        return sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, SolidConstants.PLATFORM_TENANT_ID)
                        .eq(SysUser::getUsername, username)
        );
    }

    private static String generateStrongRandomPassword() {
        char[] chars = new char[GENERATED_PASSWORD_LENGTH];
        chars[0] = pickRandomChar(UPPER_CHARS);
        chars[1] = pickRandomChar(LOWER_CHARS);
        chars[2] = pickRandomChar(DIGIT_CHARS);
        chars[3] = pickRandomChar(SPECIAL_CHARS);
        for (int i = 4; i < GENERATED_PASSWORD_LENGTH; i++) {
            chars[i] = pickRandomChar(ALL_PASSWORD_CHARS);
        }
        // Fisher-Yates 洗牌打散固定占位，保证随机字符分布均匀
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char pickRandomChar(String alphabet) {
        return alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length()));
    }
}
