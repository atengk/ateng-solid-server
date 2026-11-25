package io.github.atengk.solid;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserPost;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.request.DeptQueryRequest;
import io.github.atengk.solid.system.request.MenuSaveRequest;
import io.github.atengk.solid.system.request.PostQueryRequest;
import io.github.atengk.solid.system.request.RoleDataScopeRequest;
import io.github.atengk.solid.system.service.SysDeptQueryService;
import io.github.atengk.solid.system.service.SysDeptService;
import io.github.atengk.solid.system.service.SysMenuService;
import io.github.atengk.solid.system.service.SysPostService;
import io.github.atengk.solid.system.service.SysRoleQueryService;
import io.github.atengk.solid.system.service.SysRoleService;
import io.github.atengk.solid.system.vo.DeptTreeVO;
import io.github.atengk.solid.system.vo.DeptVO;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import io.github.atengk.solid.system.vo.PostVO;
import io.github.atengk.solid.system.vo.RoleDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static io.github.atengk.solid.OrgAndRbacTestFixtures.deptSave;
import static io.github.atengk.solid.OrgAndRbacTestFixtures.menuSave;
import static io.github.atengk.solid.OrgAndRbacTestFixtures.postSave;
import static io.github.atengk.solid.OrgAndRbacTestFixtures.roleSave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组织架构（部门/岗位）、权限树（菜单/角色）与事务提交后缓存失效 Layer 2 H2 核心闭环集成测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:org_rbac_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgAndRbacIntegrationTest {

    private static final long TENANT_ID = 5001L;
    private static final long TENANT_TWO_ID = 5002L;

    @Autowired
    private SysDeptService sysDeptService;
    @Autowired
    private SysDeptQueryService sysDeptQueryService;
    @Autowired
    private SysPostService sysPostService;
    @Autowired
    private SysMenuService sysMenuService;
    @Autowired
    private SysRoleService sysRoleService;
    @Autowired
    private SysRoleQueryService sysRoleQueryService;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserPostMapper sysUserPostMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysRoleDeptMapper sysRoleDeptMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUpBaseFixtures() {
        SysTenant tenant = new SysTenant();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode("T5001");
        tenant.setTenantName("组织RBAC测试企业");
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);
    }

    @AfterEach
    void cleanContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("部门管理：ancestors 级联重算、防自环 100001、联系明文加解密及删停护栏 102005")
    void shouldManageDeptHierarchyAndGuards() {
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            Long rootA = sysDeptService.createDept(deptSave(null, 0L, "研发总院", 1, "13800005001", "rd@Example.COM"));
            Long rootB = sysDeptService.createDept(deptSave(null, 0L, "运营中心", 2, null, null));
            Long childA1 = sysDeptService.createDept(deptSave(null, rootA, "平台架构部", 1, null, null));
            Long grandChildA11 = sysDeptService.createDept(deptSave(null, childA1, "中间件组", 1, null, null));

            DeptVO detailA = sysDeptQueryService.getDetail(rootA);
            assertThat(detailA.getAncestors()).isEqualTo("0");
            assertThat(detailA.getPhone()).isEqualTo("13800005001");
            assertThat(detailA.getEmail()).isEqualTo("rd@example.com");

            String cipherInDb = jdbcTemplate.queryForObject(
                    "SELECT email FROM sys_dept WHERE id = ?", String.class, rootA);
            assertThat(cipherInDb).startsWith("v1:");

            assertThat(sysDeptQueryService.listActiveTreeOptions().get(0).getPhone()).isNull();

            assertThatThrownBy(() -> sysDeptService.updateDept(
                    deptSave(childA1, grandChildA11, "平台架构部", 1, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            sysDeptService.updateDept(deptSave(childA1, rootB, "平台架构部", 1, "   ", ""));
            assertThat(sysDeptQueryService.getDetail(childA1).getAncestors()).isEqualTo("0," + rootB);
            assertThat(sysDeptQueryService.getDetail(grandChildA11).getAncestors())
                    .isEqualTo("0," + rootB + "," + childA1);

            sysDeptService.updateDept(deptSave(rootA, 0L, "研发总院", 1, "  ", ""));
            assertThat(sysDeptQueryService.getDetail(rootA).getPhone()).isNull();
            assertThat(sysDeptQueryService.getDetail(rootA).getEmail()).isNull();

            assertThatThrownBy(() -> sysDeptService.updateDeptStatus(rootB, CommonStatusEnum.DISABLED.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()));

            assertThatThrownBy(() -> sysDeptService.deleteDept(childA1))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()));

            SysUser user = new SysUser();
            user.setId(5101L);
            user.setTenantId(TENANT_ID);
            user.setDeptId(grandChildA11);
            user.setUsername("dept_user");
            user.setNickname("部门成员");
            user.setPassword(PasswordUtil.hash("Ateng@5001"));
            user.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(user);

            assertThatThrownBy(() -> sysDeptService.deleteDept(grandChildA11))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()));

            sysUserMapper.deleteById(5101L);
            SysRoleDept roleDept = new SysRoleDept();
            roleDept.setTenantId(TENANT_ID);
            roleDept.setRoleId(5999L);
            roleDept.setDeptId(grandChildA11);
            sysRoleDeptMapper.insert(roleDept);

            DeptQueryRequest filter = new DeptQueryRequest();
            filter.setDeptName("中间件");
            List<DeptTreeVO> filteredTree = sysDeptQueryService.listTree(filter);
            assertThat(filteredTree).hasSize(1);
            assertThat(filteredTree.get(0).getId()).isEqualTo(rootB);
            assertThat(filteredTree.get(0).getChildren().get(0).getChildren().get(0).getId()).isEqualTo(grandChildA11);

            sysDeptService.deleteDept(grandChildA11);
            Long remainRoleDeptCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_role_dept WHERE tenant_id = ? AND dept_id = ?",
                    Long.class, TENANT_ID, grandChildA11);
            assertThat(remainRoleDeptCount).isZero();
        });
    }

    @Test
    @DisplayName("岗位管理：同租户编码/名称唯一性 102002 与用户引用防删 102005")
    void shouldEnforcePostUniquenessAndReferenceGuard() {
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            Long postId1 = sysPostService.createPost(postSave(null, "ARCH", "架构师", 1, "核心架构"));
            Long postId2 = sysPostService.createPost(postSave(null, "DEV", "研发工程师", 2, null));

            assertThatThrownBy(() -> sysPostService.createPost(postSave(null, "ARCH", "另一名称", 3, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.DUPLICATE_RESOURCE.getCode()));

            SysUser postHolder = new SysUser();
            postHolder.setId(5102L);
            postHolder.setTenantId(TENANT_ID);
            postHolder.setUsername("post_user");
            postHolder.setNickname("岗位员工");
            postHolder.setPassword(PasswordUtil.hash("Ateng@5002"));
            postHolder.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(postHolder);

            SysUserPost up = new SysUserPost();
            up.setTenantId(TENANT_ID);
            up.setUserId(5102L);
            up.setPostId(postId1);
            sysUserPostMapper.insert(up);

            assertThatThrownBy(() -> sysPostService.deletePosts(postId1 + "," + postId2))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()))
                    .hasMessageContaining("架构师");

            PostQueryRequest query = new PostQueryRequest();
            query.setPostCode("AR");
            PageResult<PostVO> page = sysPostService.pagePosts(query);
            assertThat(page.getTotal()).isEqualTo(1L);
            assertThat(page.getRecords().get(0).getPostCode()).isEqualTo("ARCH");

            sysPostService.updatePostStatus(postId2, CommonStatusEnum.DISABLED.getCode());
            assertThat(sysPostService.listActiveOptions())
                    .extracting(o -> o.getPostCode())
                    .containsExactly("ARCH");

            sysUserMapper.deleteById(5102L);
            sysPostService.deletePosts(postId1 + "," + postId2);
            assertThat(sysPostService.pagePosts(new PostQueryRequest()).getTotal()).isZero();
        });
    }

    @Test
    @DisplayName("菜单管理：非平台租户 403、M/C/F 规则 100001、引用防删 102005 与回滚不触发/提交触发 clearAll")
    void shouldManageMenuWithPlatformGuardAndTxCommitCacheClear() {
        TenantContextHolder.runWithTenant(TENANT_ID, () ->
                assertThatThrownBy(() -> sysMenuService.listMenuTree(null))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(CommonResultCode.FORBIDDEN.getCode()))
        );

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            MenuSaveRequest invalidBtn = menuSave(null, 1000L, "F", "非法按钮", null, null, null, "system:bad:btn", 1, false);
            assertThatThrownBy(() -> sysMenuService.createMenu(invalidBtn))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            Long menuId = sysMenuService.createMenu(menuSave(
                    null, 1000L, "C", "测试菜单节点", "SysAuditDemo", "/system/audit-demo",
                    "system/audit-demo/index", null, 90, false));
            Long btnId = sysMenuService.createMenu(menuSave(
                    null, menuId, "F", "测试按钮权限", null, null, null, "system:audit-demo:list", 1, false));

            assertThatThrownBy(() -> sysMenuService.updateMenu(menuSave(
                    menuId, 1000L, "F", "非法降级", null, null, null, "system:audit-demo:list", 90, false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            assertThatThrownBy(() -> sysMenuService.deleteMenu(menuId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()));

            SysRoleMenu rm = new SysRoleMenu();
            rm.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            rm.setRoleId(1L);
            rm.setMenuId(btnId);
            sysRoleMenuMapper.insert(rm);
            assertThatThrownBy(() -> sysMenuService.deleteMenu(btnId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()));
            sysRoleMenuMapper.deleteById(rm.getId());

            String cacheKey = PermissionCacheService.buildRolePermsKey("demo_guard", "admin", "0");
            permissionCacheService.set(cacheKey, "[\"cached:perm\"]", 60L);

            assertThatThrownBy(() -> sysMenuService.updateMenu(menuSave(
                    menuId, 1000L, "C", "重名冲突", "SysUser", "/system/audit-demo",
                    "system/audit-demo/index", null, 90, false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.DUPLICATE_RESOURCE.getCode()));
            assertThat(permissionCacheService.get(cacheKey)).isEqualTo("[\"cached:perm\"]");

            sysMenuService.updateMenuStatus(btnId, CommonStatusEnum.DISABLED.getCode());
            assertThat(permissionCacheService.get(cacheKey)).isNull();

            sysMenuService.deleteMenu(btnId);
            sysMenuService.deleteMenu(menuId);
        });
    }

    @Test
    @DisplayName("角色管理：保留编码 102004、内置 super_admin 102007、套餐越界 102006、CUSTOM 100001 与 3 参 clearRoleCache")
    void shouldManageRoleWithReservedAndBuiltinGuardsAndThreeArgCacheClear() {
        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(5201L);
        pkg.setPackageName("基础部门岗位套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long mid : List.of(1000L, 1400L, 1401L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(5201L);
            pm.setMenuId(mid);
            sysTenantPackageMenuMapper.insert(pm);
        }

        SysTenant tenantTwo = new SysTenant();
        tenantTwo.setId(TENANT_TWO_ID);
        tenantTwo.setTenantCode("T5002");
        tenantTwo.setTenantName("套餐受限测试企业");
        tenantTwo.setPackageId(5201L);
        tenantTwo.setAccountCount(-1);
        tenantTwo.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenantTwo);

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            assertThatThrownBy(() -> sysRoleService.updateRole(roleSave(1L, "超管改名", "super_admin", List.of())))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));
            assertThatThrownBy(() -> sysRoleService.updateRoleStatus(1L, CommonStatusEnum.DISABLED.getCode()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));
            assertThatThrownBy(() -> sysRoleService.deleteRoles("1"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));
        });

        TenantContextHolder.runWithTenant(TENANT_TWO_ID, () -> {
            assertThatThrownBy(() -> sysRoleService.createRole(roleSave(null, "越权超管", "super_admin", List.of(1000L))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESERVED_ROLE_KEY.getCode()));
            assertThatThrownBy(() -> sysRoleService.createRole(roleSave(null, "保留编码", "admin", List.of(1000L))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESERVED_ROLE_KEY.getCode()));

            assertThatThrownBy(() -> sysRoleService.createRole(roleSave(null, "越界角色", "bad_scope", List.of(1300L))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode()));

            List<MenuTreeVO> grantableTree = sysRoleQueryService.listGrantableMenuTree();
            assertThat(grantableTree).extracting(MenuTreeVO::getId).containsExactly(1000L);

            Long editorRoleId = sysRoleService.createRole(roleSave(
                    null, "部门编辑员", "editor", List.of(1000L, 1400L, 1401L)));
            Long tenantTwoDept = sysDeptService.createDept(deptSave(null, 0L, "测试总部", 1, null, null));

            RoleDataScopeRequest invalidCustom = new RoleDataScopeRequest();
            invalidCustom.setDataScope(DataScopeType.CUSTOM.getCode());
            invalidCustom.setDeptIds(List.of());
            assertThatThrownBy(() -> sysRoleService.updateRoleDataScope(editorRoleId, invalidCustom))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            RoleDataScopeRequest validCustom = new RoleDataScopeRequest();
            validCustom.setDataScope(DataScopeType.CUSTOM.getCode());
            validCustom.setDeptCheckStrictly(false);
            validCustom.setDeptIds(List.of(tenantTwoDept));
            sysRoleService.updateRoleDataScope(editorRoleId, validCustom);

            RoleDetailVO detail = sysRoleQueryService.getDetail(editorRoleId);
            assertThat(detail.getMenuIds()).containsExactlyInAnyOrder(1000L, 1400L, 1401L);
            assertThat(detail.getDeptIds()).containsExactly(tenantTwoDept);

            String keyTenantTwo = PermissionCacheService.buildRolePermsKey("editor", "admin", String.valueOf(TENANT_TWO_ID));
            String keyTenantOne = PermissionCacheService.buildRolePermsKey("editor", "admin", String.valueOf(TENANT_ID));
            permissionCacheService.set(keyTenantTwo, "[\"system:dept:list\"]", 60L);
            permissionCacheService.set(keyTenantOne, "[\"system:dept:list\"]", 60L);

            assertThatThrownBy(() -> sysRoleService.updateRole(roleSave(editorRoleId, "部门编辑员", "editor", List.of(1500L))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode()));
            assertThat(permissionCacheService.get(keyTenantTwo)).isEqualTo("[\"system:dept:list\"]");

            sysRoleService.updateRole(roleSave(editorRoleId, "部门编辑员-改", "editor", List.of(1000L, 1400L)));
            assertThat(permissionCacheService.get(keyTenantTwo)).isNull();
            assertThat(permissionCacheService.get(keyTenantOne)).isEqualTo("[\"system:dept:list\"]");

            SysUser boundUser = new SysUser();
            boundUser.setId(5209L);
            boundUser.setTenantId(TENANT_TWO_ID);
            boundUser.setUsername("role_holder");
            boundUser.setNickname("角色员工");
            boundUser.setPassword(PasswordUtil.hash("Ateng@5002"));
            boundUser.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(boundUser);

            SysUserRole ur = new SysUserRole();
            ur.setTenantId(TENANT_TWO_ID);
            ur.setUserId(5209L);
            ur.setRoleId(editorRoleId);
            sysUserRoleMapper.insert(ur);

            assertThatThrownBy(() -> sysRoleService.deleteRoles(String.valueOf(editorRoleId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.RESOURCE_IN_USE.getCode()));

            sysUserMapper.deleteById(5209L);
            permissionCacheService.set(keyTenantTwo, "[\"system:dept:list\"]", 60L);
            sysRoleService.deleteRoles(String.valueOf(editorRoleId));
            assertThat(permissionCacheService.get(keyTenantTwo)).isNull();
        });
    }
}
