package io.github.atengk.solid;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysConfig;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.entity.SysDictType;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysConfigMapper;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysDictTypeMapper;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.ConfigQueryRequest;
import io.github.atengk.solid.system.request.DictDataQueryRequest;
import io.github.atengk.solid.system.request.DictTypeQueryRequest;
import io.github.atengk.solid.system.request.LoginLogQueryRequest;
import io.github.atengk.solid.system.request.OperLogQueryRequest;
import io.github.atengk.solid.system.request.PostQueryRequest;
import io.github.atengk.solid.system.request.RoleQueryRequest;
import io.github.atengk.solid.system.request.TenantPackageQueryRequest;
import io.github.atengk.solid.system.request.TenantQueryRequest;
import io.github.atengk.solid.system.request.UserQueryRequest;
import io.github.atengk.solid.system.service.SysConfigService;
import io.github.atengk.solid.system.service.SysDictQueryService;
import io.github.atengk.solid.system.service.SysLogQueryService;
import io.github.atengk.solid.system.service.SysPostService;
import io.github.atengk.solid.system.service.SysRoleQueryService;
import io.github.atengk.solid.system.service.SysTenantPackageQueryService;
import io.github.atengk.solid.system.service.SysTenantQueryService;
import io.github.atengk.solid.system.service.SysUserQueryService;
import io.github.atengk.solid.system.vo.DictDataVO;
import io.github.atengk.solid.system.vo.LoginLogVO;
import io.github.atengk.solid.system.vo.OperLogVO;
import io.github.atengk.solid.system.vo.PostVO;
import io.github.atengk.solid.system.vo.RoleVO;
import io.github.atengk.solid.system.vo.TenantPackageVO;
import io.github.atengk.solid.system.vo.TenantVO;
import io.github.atengk.solid.system.vo.UserOptionVO;
import io.github.atengk.solid.system.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 5 切片全链路 SQL {@code LIKE} 通配符转义与非法排序回退默认排序（{@code pageRequest.orders()}）H2 集成测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=false",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:sql_like_sort_cycle_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlLikeSortAndTreeCycleIntegrationTest {

    @Autowired
    private SysPostMapper sysPostMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysConfigMapper sysConfigMapper;
    @Autowired
    private SysDictTypeMapper sysDictTypeMapper;
    @Autowired
    private SysDictDataMapper sysDictDataMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysOperLogMapper sysOperLogMapper;
    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;

    @Autowired
    private SysPostService sysPostService;
    @Autowired
    private SysRoleQueryService sysRoleQueryService;
    @Autowired
    private SysConfigService sysConfigService;
    @Autowired
    private SysDictQueryService sysDictQueryService;
    @Autowired
    private SysUserQueryService sysUserQueryService;
    @Autowired
    private SysTenantQueryService sysTenantQueryService;
    @Autowired
    private SysTenantPackageQueryService sysTenantPackageQueryService;
    @Autowired
    private SysLogQueryService sysLogQueryService;

    @BeforeAll
    void seedTestRecords() {
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            // 1. 岗位（sort=99 先插入且含字面量 % 和 _，sort=10 后插入正常对照行）
            insertPost(8101L, "post_pct_1", "100%_通配对照A", 99);
            insertPost(8102L, "post_plain_2", "普通岗位对照B", 10);

            // 2. 角色（sort=99 与 sort=10）
            insertRole(8201L, "role_%_key", "角色%_通配对照A", 99);
            insertRole(8202L, "role_plain_key", "角色普通对照B", 10);

            // 3. 参数
            insertConfig(8301L, "cfg_%_key", "参数%_对照名称", "v1");
            insertConfig(8302L, "cfg_plain_key", "普通参数对照", "v2");

            // 4. 字典类型与数据
            insertDictType(8401L, "dict_%_type", "字典%_对照名");
            insertDictType(8402L, "dict_plain_type", "普通字典对照名");
            insertDictData(8501L, "dict_plain_type", "标签%_通配对照A", "v1", 99);
            insertDictData(8502L, "dict_plain_type", "标签普通对照B", "v2", 10);

            // 5. 用户（含字面量 % / _ 账号与正常对照账号）
            insertUser(8601L, "pct_%_user", "通配_昵称");
            insertUser(8602L, "plain_user", "普通昵称");

            // 6. 操作日志与登录日志（t-5m 先插 id=8701/8801，t 后插 id=8702/8802）
            LocalDateTime older = LocalDateTime.now().minusMinutes(5);
            LocalDateTime newer = LocalDateTime.now();
            insertOperLog(8701L, "mod_%_log", "actor_对照A", older);
            insertOperLog(8702L, "mod_plain", "actor_对照B", newer);
            insertLoginLog(8801L, "login_对照A", "10.0_%_1", older);
            insertLoginLog(8802L, "login_对照B", "10.0.0.2", newer);

            // 7. 部门树用于 ancestors right-prefix 验证
            insertDept(8901L, 0L, "0", "总部8901");
            insertDept(8902L, 8901L, "0,8901", "子部8902");
        });

        TenantContextHolder.runWithoutTenant(() -> {
            LocalDateTime older = LocalDateTime.now().minusMinutes(5);
            LocalDateTime newer = LocalDateTime.now();
            insertPackage(8911L, "套餐%_对照A", older);
            insertPackage(8912L, "套餐普通对照B", newer);

            insertTenant(8921L, "t_pct_1", "租户%_对照A", 8911L, older);
            insertTenant(8922L, "t_plain_2", "租户普通对照B", 8912L, newer);
        });
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        DataPermissionContextHolder.clear();
    }

    @Test
    @DisplayName("全站模糊检索传入字面量 % 与 _ 时仅应当精确匹配字面量记录而非通配命中全部行")
    void shouldEscapePercentAndUnderscoreWildcardsAcrossAllLikeQueries() {
        DataPermissionContextHolder.setSuperAdmin(true);
        TenantContextHolder.setTenantId(SolidConstants.PLATFORM_TENANT_ID);

        // Post LIKE % 与 _
        PostQueryRequest postPct = new PostQueryRequest();
        postPct.setPostName("%");
        assertThat(sysPostService.pagePosts(postPct).getRecords())
                .extracting(PostVO::getId)
                .containsExactly(8101L);

        PostQueryRequest postUnderscore = new PostQueryRequest();
        postUnderscore.setPostCode("_1");
        assertThat(sysPostService.pagePosts(postUnderscore).getRecords())
                .extracting(PostVO::getId)
                .containsExactly(8101L);

        // Role LIKE %
        RoleQueryRequest rolePct = new RoleQueryRequest();
        rolePct.setRoleKey("%");
        assertThat(sysRoleQueryService.pageRoles(rolePct).getRecords())
                .extracting(RoleVO::getId)
                .containsExactly(8201L);

        // Config LIKE %
        ConfigQueryRequest cfgPct = new ConfigQueryRequest();
        cfgPct.setConfigKey("%");
        assertThat(sysConfigService.pageConfigs(cfgPct).getRecords())
                .hasSize(1)
                .first()
                .extracting(v -> v.getId())
                .isEqualTo(8301L);

        // DictType & DictData LIKE %
        DictTypeQueryRequest dictTypePct = new DictTypeQueryRequest();
        dictTypePct.setDictType("%");
        assertThat(sysDictQueryService.pageDictTypes(dictTypePct).getRecords())
                .hasSize(1)
                .first()
                .extracting(v -> v.getId())
                .isEqualTo(8401L);

        DictDataQueryRequest dictDataPct = new DictDataQueryRequest();
        dictDataPct.setDictType("dict_plain_type");
        dictDataPct.setDictLabel("%");
        assertThat(sysDictQueryService.pageDictData(dictDataPct).getRecords())
                .extracting(DictDataVO::getId)
                .containsExactly(8501L);

        // User page & options LIKE %
        UserQueryRequest userPct = new UserQueryRequest();
        userPct.setUsername("%");
        PageResult<UserVO> userPage = sysUserQueryService.pageUsers(userPct);
        assertThat(userPage.getRecords())
                .extracting(UserVO::getId)
                .containsExactly(8601L);
        assertThat(sysUserQueryService.listActiveUserOptions("%"))
                .extracting(UserOptionVO::getUserId)
                .containsExactly(8601L);

        // OperLog & LoginLog LIKE %
        OperLogQueryRequest operPct = new OperLogQueryRequest();
        operPct.setModule("%");
        assertThat(sysLogQueryService.pageOperLogs(operPct).getRecords())
                .extracting(OperLogVO::getId)
                .containsExactly(8701L);

        LoginLogQueryRequest loginPct = new LoginLogQueryRequest();
        loginPct.setClientIp("%");
        assertThat(sysLogQueryService.pageLoginLogs(loginPct).getRecords())
                .extracting(LoginLogVO::getId)
                .containsExactly(8801L);

        // Tenant & Package LIKE %
        TenantPackageQueryRequest pkgPct = new TenantPackageQueryRequest();
        pkgPct.setPackageName("%");
        assertThat(sysTenantPackageQueryService.pagePackages(pkgPct).getRecords())
                .extracting(TenantPackageVO::getId)
                .containsExactly(8911L);

        TenantQueryRequest tenantPct = new TenantQueryRequest();
        tenantPct.setTenantName("%");
        assertThat(sysTenantQueryService.pageTenants(tenantPct).getRecords())
                .extracting(TenantVO::getId)
                .containsExactly(8921L);
    }

    @Test
    @DisplayName("传入非法排序列名被 PageUtil 过滤后仍应当依托 pageRequest.orders() 回退默认确定性排序")
    void shouldFallbackToDefaultOrderingWhenSortsFilteredByPageUtil() {
        TenantContextHolder.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        List<SortItem> evilSort = List.of(new SortItem("nonExistentColumn", true));

        PostQueryRequest postQuery = new PostQueryRequest();
        postQuery.setPostName("对照");
        postQuery.setSorts(evilSort);
        assertThat(sysPostService.pagePosts(postQuery).getRecords())
                .extracting(PostVO::getId)
                .containsExactly(8102L, 8101L);

        RoleQueryRequest roleQuery = new RoleQueryRequest();
        roleQuery.setRoleName("对照");
        roleQuery.setSorts(evilSort);
        assertThat(sysRoleQueryService.pageRoles(roleQuery).getRecords())
                .extracting(RoleVO::getId)
                .containsExactly(8202L, 8201L);

        ConfigQueryRequest cfgQuery = new ConfigQueryRequest();
        cfgQuery.setConfigName("对照");
        cfgQuery.setSorts(evilSort);
        assertThat(sysConfigService.pageConfigs(cfgQuery).getRecords())
                .extracting(v -> v.getId())
                .containsExactly(8301L, 8302L);

        DictTypeQueryRequest dictTypeQuery = new DictTypeQueryRequest();
        dictTypeQuery.setDictName("对照");
        dictTypeQuery.setSorts(evilSort);
        assertThat(sysDictQueryService.pageDictTypes(dictTypeQuery).getRecords())
                .extracting(v -> v.getId())
                .containsExactly(8401L, 8402L);

        DictDataQueryRequest dictDataQuery = new DictDataQueryRequest();
        dictDataQuery.setDictType("dict_plain_type");
        dictDataQuery.setDictLabel("对照");
        dictDataQuery.setSorts(evilSort);
        assertThat(sysDictQueryService.pageDictData(dictDataQuery).getRecords())
                .extracting(DictDataVO::getId)
                .containsExactly(8502L, 8501L);

        OperLogQueryRequest operQuery = new OperLogQueryRequest();
        operQuery.setUserName("对照");
        operQuery.setSorts(evilSort);
        assertThat(sysLogQueryService.pageOperLogs(operQuery).getRecords())
                .extracting(OperLogVO::getId)
                .containsExactly(8702L, 8701L);

        LoginLogQueryRequest loginQuery = new LoginLogQueryRequest();
        loginQuery.setUsername("对照");
        loginQuery.setSorts(evilSort);
        assertThat(sysLogQueryService.pageLoginLogs(loginQuery).getRecords())
                .extracting(LoginLogVO::getId)
                .containsExactly(8802L, 8801L);

        TenantPackageQueryRequest pkgQuery = new TenantPackageQueryRequest();
        pkgQuery.setPackageName("对照");
        pkgQuery.setSorts(evilSort);
        assertThat(sysTenantPackageQueryService.pagePackages(pkgQuery).getRecords())
                .extracting(TenantPackageVO::getId)
                .containsExactly(8912L, 8911L);

        TenantQueryRequest tenantQuery = new TenantQueryRequest();
        tenantQuery.setTenantName("对照");
        tenantQuery.setSorts(evilSort);
        assertThat(sysTenantQueryService.pageTenants(tenantQuery).getRecords())
                .extracting(TenantVO::getId)
                .containsExactly(8922L, 8921L);
    }

    private void insertPost(Long id, String code, String name, int sort) {
        SysPost post = new SysPost();
        post.setId(id);
        post.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        post.setPostCode(code);
        post.setPostName(name);
        post.setSort(sort);
        post.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysPostMapper.insert(post);
    }

    private void insertRole(Long id, String key, String name, int sort) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        role.setRoleKey(key);
        role.setRoleName(name);
        role.setSort(sort);
        role.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysRoleMapper.insert(role);
    }

    private void insertConfig(Long id, String key, String name, String val) {
        SysConfig config = new SysConfig();
        config.setId(id);
        config.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        config.setConfigKey(key);
        config.setConfigName(name);
        config.setConfigValue(val);
        config.setConfigType("N");
        sysConfigMapper.insert(config);
    }

    private void insertDictType(Long id, String type, String name) {
        SysDictType dt = new SysDictType();
        dt.setId(id);
        dt.setDictType(type);
        dt.setDictName(name);
        dt.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysDictTypeMapper.insert(dt);
    }

    private void insertDictData(Long id, String type, String label, String val, int sort) {
        SysDictData dd = new SysDictData();
        dd.setId(id);
        dd.setDictType(type);
        dd.setDictLabel(label);
        dd.setDictValue(val);
        dd.setSort(sort);
        dd.setIsDefault(0);
        dd.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysDictDataMapper.insert(dd);
    }

    private void insertUser(Long id, String username, String nickname) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        u.setDeptId(8901L);
        u.setUsername(username);
        u.setNickname(nickname);
        u.setPassword("hashed");
        u.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(u);
    }

    private void insertOperLog(Long id, String module, String userName, LocalDateTime time) {
        SysOperLog log = new SysOperLog();
        log.setId(id);
        log.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        log.setTraceId("tr-" + id);
        log.setUserId(1L);
        log.setUserName(userName);
        log.setModule(module);
        log.setOperateType(1);
        log.setStatus(0);
        log.setOperateTime(time);
        sysOperLogMapper.insert(log);
    }

    private void insertLoginLog(Long id, String username, String ip, LocalDateTime time) {
        SysLoginLog log = new SysLoginLog();
        log.setId(id);
        log.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        log.setTraceId("tl-" + id);
        log.setUsername(username);
        log.setLoginType("admin");
        log.setClientIp(ip);
        log.setStatus(0);
        log.setLoginTime(time);
        sysLoginLogMapper.insert(log);
    }

    private void insertDept(Long id, Long parentId, String ancestors, String name) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        dept.setParentId(parentId);
        dept.setAncestors(ancestors);
        dept.setDeptName(name);
        dept.setSort(1);
        dept.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysDeptMapper.insert(dept);
    }

    private void insertPackage(Long id, String name, LocalDateTime createTime) {
        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(id);
        pkg.setPackageName(name);
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        pkg.setCreateTime(createTime);
        sysTenantPackageMapper.insert(pkg);
    }

    private void insertTenant(Long id, String code, String name, Long pkgId, LocalDateTime createTime) {
        SysTenant t = new SysTenant();
        t.setId(id);
        t.setTenantCode(code);
        t.setTenantName(name);
        t.setPackageId(pkgId);
        t.setAccountCount(10);
        t.setStatus(CommonStatusEnum.NORMAL.getCode());
        t.setCreateTime(createTime);
        sysTenantMapper.insert(t);
    }
}
