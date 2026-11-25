package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.system.entity.SysConfig;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.entity.SysDictType;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysConfigMapper;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysDictTypeMapper;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 17 张表实体/Mapper 装配及 {@link ContactFieldCrypto} 确定性字段加密持久层 Layer 2 集成测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:contact_encrypt_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
class ContactFieldEncryptPersistenceTest {

    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    @Autowired
    private SysMenuMapper sysMenuMapper;
    @Autowired
    private SysDictTypeMapper sysDictTypeMapper;
    @Autowired
    private SysDictDataMapper sysDictDataMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysPostMapper sysPostMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysConfigMapper sysConfigMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysUserPostMapper sysUserPostMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysRoleDeptMapper sysRoleDeptMapper;
    @Autowired
    private SysOperLogMapper sysOperLogMapper;
    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_dept WHERE id <> 100");
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("SysUser 与 SysDept 联系字段应当落盘为 v1: 密文（含 64 位邮箱）、ORM 透明解密明文且支持等值检索")
    void shouldEncryptContactsOnPersistAndDecryptOnQuery() {
        SysDept dept = new SysDept();
        dept.setTenantId(0L);
        dept.setParentId(100L);
        dept.setAncestors("0,100");
        dept.setDeptName("安全研发部");
        dept.setSort(2);
        dept.setPhone(ContactFieldCrypto.normalizePhone(" 13800138001 "));
        dept.setEmail(ContactFieldCrypto.normalizeEmail("  DeptLead@Example.COM "));
        dept.setStatus(0);
        sysDeptMapper.insert(dept);

        // 构造恰好 64 字符明文邮箱（确定性加密后长 131 字符，验证 VARCHAR(255) 无截断溢出）
        String email64 = "a".repeat(52) + "@example.com";
        assertThat(email64).hasSize(64);

        SysUser user = new SysUser();
        user.setTenantId(0L);
        user.setDeptId(dept.getId());
        user.setUsername("sec_engineer");
        user.setNickname("安全工程师");
        user.setPassword("$pbkdf2$test-hash-placeholder");
        user.setPhone(ContactFieldCrypto.normalizePhone("13800138002"));
        user.setEmail(ContactFieldCrypto.normalizeEmail(email64.toUpperCase()));
        user.setGender("2");
        user.setStatus(0);
        sysUserMapper.insert(user);

        Map<String, Object> rawDept = jdbcTemplate.queryForMap(
                "SELECT phone, email FROM sys_dept WHERE id = ?", dept.getId());
        assertThat((String) rawDept.get("phone")).startsWith("v1:").isNotEqualTo("13800138001");
        assertThat((String) rawDept.get("email")).startsWith("v1:").isNotEqualTo("deptlead@example.com");

        Map<String, Object> rawUser = jdbcTemplate.queryForMap(
                "SELECT phone, email FROM sys_user WHERE id = ?", user.getId());
        assertThat((String) rawUser.get("phone")).startsWith("v1:").hasSize(47);
        assertThat((String) rawUser.get("email")).startsWith("v1:").hasSize(131);

        SysDept loadedDept = sysDeptMapper.selectById(dept.getId());
        assertThat(loadedDept.getPhone()).isEqualTo("13800138001");
        assertThat(loadedDept.getEmail()).isEqualTo("deptlead@example.com");

        SysUser loadedUser = sysUserMapper.selectById(user.getId());
        assertThat(loadedUser.getPhone()).isEqualTo("13800138002");
        assertThat(loadedUser.getEmail()).isEqualTo(email64);

        // 路径 A：QueryWrapper / 原生 SQL 使用 ContactFieldCrypto.encrypt*ForQuery 精确等值与批量 IN 检索（不触发 TypeHandler 二次加密）
        SysUser queriedByPhone = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                .eq("phone", ContactFieldCrypto.encryptPhoneForQuery(" 13800138002 ")));
        assertThat(queriedByPhone).isNotNull();
        assertThat(queriedByPhone.getId()).isEqualTo(user.getId());

        SysUser queriedByEmailIn = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                .in("email", ContactFieldCrypto.encryptEmailsForQuery(
                        java.util.List.of("  " + email64.toUpperCase() + " "))));
        assertThat(queriedByEmailIn).isNotNull();
        assertThat(queriedByEmailIn.getId()).isEqualTo(user.getId());

        // 路径 B：MyBatis-Plus 3.5.17 LambdaQueryWrapper 会自动附加字段上的 DeterministicEncryptTypeHandler，须传归一化明文
        SysUser queriedByLambdaPhone = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, ContactFieldCrypto.normalizePhone(" 13800138002 "))
                .eq(SysUser::getEmail, ContactFieldCrypto.normalizeEmail("  " + email64.toUpperCase() + " ")));
        assertThat(queriedByLambdaPhone).isNotNull();
        assertThat(queriedByLambdaPhone.getId()).isEqualTo(user.getId());

        assertThat(sysUserMapper.selectUsernameById(user.getId())).isEqualTo("sec_engineer");
    }

    @Test
    @DisplayName("应当放行多条 NULL 手机号、拦截重复非空加密手机号/邮箱，并在软删回填 deleted=id 后允许重建")
    void shouldHandleNullsUniqueConflictAndLogicDeleteRecreation() {
        SysUser nullPhoneUser1 = buildUser("null_phone_1", null, null);
        SysUser nullPhoneUser2 = buildUser("null_phone_2", null, null);
        sysUserMapper.insert(nullPhoneUser1);
        sysUserMapper.insert(nullPhoneUser2);

        SysUser first = buildUser("reuse_account", "13900139000", "reuse@example.com");
        sysUserMapper.insert(first);

        SysUser duplicatePhone = buildUser("other_user_phone", "13900139000", null);
        assertThatThrownBy(() -> sysUserMapper.insert(duplicatePhone))
                .isInstanceOf(DuplicateKeyException.class);

        SysUser duplicateEmail = buildUser("other_user_email", null, "  REUSE@Example.COM ");
        assertThatThrownBy(() -> sysUserMapper.insert(duplicateEmail))
                .isInstanceOf(DuplicateKeyException.class);

        int deletedRows = sysUserMapper.deleteById(first.getId());
        assertThat(deletedRows).isEqualTo(1);
        assertThat(sysUserMapper.selectById(first.getId())).isNull();
        assertThat(sysUserMapper.selectUsernameById(first.getId())).isNull();

        Long rawDeleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_user WHERE id = ?", Long.class, first.getId());
        assertThat(rawDeleted).isEqualTo(first.getId());

        SysUser recreated = buildUser("reuse_account", "13900139000", "reuse@example.com");
        sysUserMapper.insert(recreated);
        assertThat(recreated.getId()).isNotNull().isNotEqualTo(first.getId());
        assertThat(sysUserMapper.selectUsernameById(recreated.getId())).isEqualTo("reuse_account");
    }

    @Test
    @DisplayName("全部 17 个 Mapper 应当完成 Spring 装配且实体字段可正确映射 Flyway 种子记录")
    void shouldAutowireAllSeventeenMappersAndReadSeedRecords() {
        assertThat(sysTenantPackageMapper).isNotNull();
        assertThat(sysTenantPackageMenuMapper).isNotNull();
        assertThat(sysPostMapper).isNotNull();
        assertThat(sysUserRoleMapper).isNotNull();
        assertThat(sysUserPostMapper).isNotNull();
        assertThat(sysRoleMenuMapper).isNotNull();
        assertThat(sysRoleDeptMapper).isNotNull();
        assertThat(sysOperLogMapper).isNotNull();
        assertThat(sysLoginLogMapper).isNotNull();

        SysTenant defaultTenant = sysTenantMapper.selectById(0L);
        assertThat(defaultTenant).isNotNull();
        assertThat(defaultTenant.getTenantCode()).isEqualTo("000000");

        SysRole superAdminRole = sysRoleMapper.selectById(1L);
        assertThat(superAdminRole).isNotNull();
        assertThat(superAdminRole.getRoleKey()).isEqualTo("super_admin");
        assertThat(superAdminRole.getMenuCheckStrictly()).isTrue();
        assertThat(superAdminRole.getDeptCheckStrictly()).isFalse();

        SysMenu userMenu = sysMenuMapper.selectById(1100L);
        assertThat(userMenu).isNotNull();
        assertThat(userMenu.getName()).isEqualTo("SysUser");
        assertThat(userMenu.getKeepAlive()).isTrue();
        assertThat(userMenu.getPlatformOnly()).isFalse();

        SysDictType genderType = sysDictTypeMapper.selectById(10001L);
        assertThat(genderType).isNotNull();
        assertThat(genderType.getDictType()).isEqualTo("sys_user_gender");

        SysDictData unknownGender = sysDictDataMapper.selectById(11003L);
        assertThat(unknownGender).isNotNull();
        assertThat(unknownGender.getIsDefault()).isEqualTo(1);

        SysConfig initPwd = sysConfigMapper.selectById(20001L);
        assertThat(initPwd).isNotNull();
        assertThat(initPwd.getConfigKey()).isEqualTo("sys.user.initPassword");
        assertThat(initPwd.getConfigValue()).isEqualTo("Ateng@2026");
    }

    private static SysUser buildUser(String username, String phone, String email) {
        SysUser user = new SysUser();
        user.setTenantId(0L);
        user.setDeptId(100L);
        user.setUsername(username);
        user.setNickname(username);
        user.setPassword("$pbkdf2$test-hash-placeholder");
        user.setPhone(ContactFieldCrypto.normalizePhone(phone));
        user.setEmail(ContactFieldCrypto.normalizeEmail(email));
        user.setGender("2");
        user.setStatus(0);
        return user;
    }
}
