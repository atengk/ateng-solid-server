package io.github.atengk.solid;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.request.DeptSaveRequest;
import io.github.atengk.solid.system.request.MenuSaveRequest;
import io.github.atengk.solid.system.request.RoleDataScopeRequest;
import io.github.atengk.solid.system.service.SysDeptService;
import io.github.atengk.solid.system.service.SysMenuService;
import io.github.atengk.solid.system.service.SysRoleQueryService;
import io.github.atengk.solid.system.service.SysRoleService;
import io.github.atengk.solid.system.vo.MenuTreeVO;
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
import static io.github.atengk.solid.OrgAndRbacTestFixtures.roleSave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组织架构与权限树关键边界联动 Layer 2 H2 集成测试（半选套餐授权兼容、{@code roleKey} 重命名联动驱逐用户角色缓存、
 * {@code platformOnly} 级联收紧裁剪与菜单/部门状态降级约束）。
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
class OrgAndRbacEdgeIntegrationTest {

    private static final long HALF_CHECK_TENANT_ID = 5011L;

    @Autowired
    private SysDeptService sysDeptService;
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
    private SysMenuMapper sysMenuMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUpFixtures() {
        SysTenantPackage halfPkg = new SysTenantPackage();
        halfPkg.setId(5301L);
        halfPkg.setPackageName("独立勾选半选目录测试套餐");
        halfPkg.setMenuCheckStrictly(false);
        halfPkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(halfPkg);

        // 套餐仅勾选子页面 1400 与按钮 1401，未写入父目录 1000
        for (Long mid : List.of(1400L, 1401L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(5301L);
            pm.setMenuId(mid);
            sysTenantPackageMenuMapper.insert(pm);
        }

        SysTenant tenant = new SysTenant();
        tenant.setId(HALF_CHECK_TENANT_ID);
        tenant.setTenantCode("T5011");
        tenant.setTenantName("半选套餐租户");
        tenant.setPackageId(5301L);
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("角色半选套餐兼容、roleKey 重命名联动清理用户缓存与 CUSTOM 切换清理旧绑定")
    void shouldSupportHalfCheckedPackageAndEvictUserRolesOnRoleKeyRename() {
        TenantContextHolder.runWithTenant(HALF_CHECK_TENANT_ID, () -> {
            // 1. /role/menu-tree 返回携带父目录 1000 的完整树；保存角色传入 [1000, 1400, 1401] 应成功，仅传空目录 [1000] 应 102006
            List<MenuTreeVO> tree = sysRoleQueryService.listGrantableMenuTree();
            assertThat(tree).extracting(MenuTreeVO::getId).containsExactly(1000L);

            assertThatThrownBy(() -> sysRoleService.createRole(roleSave(null, "空目录越权", "empty_dir", List.of(1000L))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode()));

            Long roleId = sysRoleService.createRole(roleSave(
                    null, "半选兼容角色", "half_compat", List.of(1000L, 1400L, 1401L)));
            Long deptId = sysDeptService.createDept(deptSave(null, 0L, "半选部门", 1, null, null));

            // 2. 先分配 CUSTOM 再切换至 DEPT，必须清理历史 sys_role_dept 残余行
            RoleDataScopeRequest customReq = new RoleDataScopeRequest();
            customReq.setDataScope(DataScopeType.CUSTOM.getCode());
            customReq.setDeptIds(List.of(deptId));
            sysRoleService.updateRoleDataScope(roleId, customReq);
            assertThat(sysRoleQueryService.getDetail(roleId).getDeptIds()).containsExactly(deptId);

            RoleDataScopeRequest deptReq = new RoleDataScopeRequest();
            deptReq.setDataScope(DataScopeType.DEPT.getCode());
            sysRoleService.updateRoleDataScope(roleId, deptReq);
            assertThat(sysRoleQueryService.getDetail(roleId).getDeptIds()).isEmpty();

            // 3. 修改 roleKey 时同时清理旧 roleKey、新 roleKey 与绑定用户的 user-roles 缓存
            SysUser holder = new SysUser();
            holder.setId(5399L);
            holder.setTenantId(HALF_CHECK_TENANT_ID);
            holder.setUsername("rename_holder");
            holder.setNickname("改码员工");
            holder.setPassword(PasswordUtil.hash("Ateng@5011"));
            holder.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(holder);

            SysUserRole ur = new SysUserRole();
            ur.setTenantId(HALF_CHECK_TENANT_ID);
            ur.setUserId(5399L);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);

            String oldRoleCache = PermissionCacheService.buildRolePermsKey("half_compat", "admin", String.valueOf(HALF_CHECK_TENANT_ID));
            String newRoleCache = PermissionCacheService.buildRolePermsKey("half_compat_v2", "admin", String.valueOf(HALF_CHECK_TENANT_ID));
            String userRoleCache = PermissionCacheService.buildUserRolesKey("5399", "admin", String.valueOf(HALF_CHECK_TENANT_ID));
            permissionCacheService.set(oldRoleCache, "[\"system:dept:list\"]", 60L);
            permissionCacheService.set(newRoleCache, "[\"stale\"]", 60L);
            permissionCacheService.set(userRoleCache, "[\"half_compat\"]", 60L);

            sysRoleService.updateRole(roleSave(roleId, "半选兼容角色-新", "half_compat_v2", List.of(1000L, 1400L)));
            assertThat(permissionCacheService.get(oldRoleCache)).isNull();
            assertThat(permissionCacheService.get(newRoleCache)).isNull();
            assertThat(permissionCacheService.get(userRoleCache)).isNull();

            sysUserMapper.deleteById(5399L);
            sysRoleService.deleteRoles(String.valueOf(roleId));
            sysDeptService.deleteDept(deptId);
        });
    }

    @Test
    @DisplayName("菜单 platformOnly 级联收紧裁剪、降级规则与停用父部门下编辑启用校验 100001")
    void shouldCascadePlatformOnlyPruneAndEnforceTopologyAndDeptEnableGuards() {
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            Long dirId = sysMenuService.createMenu(menuSave(
                    null, 0L, "M", "扩展目录", "ExtDir", "/ext-dir", null, null, 91, false));
            Long pageId = sysMenuService.createMenu(menuSave(
                    null, dirId, "C", "扩展页面", "ExtPage", "/ext-dir/page", "ext/page/index", null, 1, false));
            Long btnId = sysMenuService.createMenu(menuSave(
                    null, pageId, "F", "扩展按钮", null, null, null, "ext:page:query", 1, false));

            // 存在下级 C 时禁将 M 降级为 C；存在下级 F 时禁将 C 变更为 M
            assertThatThrownBy(() -> sysMenuService.updateMenu(menuSave(
                    dirId, 0L, "C", "降级测试", "ExtDir", "/ext-dir", "ext/dir/index", null, 91, false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));
            assertThatThrownBy(() -> sysMenuService.updateMenu(menuSave(
                    pageId, dirId, "M", "升级目录", "ExtPage", "/ext-dir/page", null, null, 1, false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            // 绑定至普通租户套餐 5301 与非平台角色，然后收紧目录 dirId platformOnly=true -> 全部子孙转 true 并裁剪关联
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(5301L);
            pm.setMenuId(pageId);
            sysTenantPackageMenuMapper.insert(pm);

            SysRoleMenu rm = new SysRoleMenu();
            rm.setTenantId(HALF_CHECK_TENANT_ID);
            rm.setRoleId(5998L);
            rm.setMenuId(btnId);
            sysRoleMenuMapper.insert(rm);

            MenuSaveRequest tightenReq = menuSave(dirId, 0L, "M", "扩展目录", "ExtDir", "/ext-dir", null, null, 91, true);
            sysMenuService.updateMenu(tightenReq);

            SysMenu refreshedBtn = sysMenuMapper.selectById(btnId);
            assertThat(refreshedBtn.getPlatformOnly()).isTrue();
            Long remainPkgCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_tenant_package_menu WHERE menu_id IN (?, ?, ?)",
                    Long.class, dirId, pageId, btnId);
            Long remainRoleCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_role_menu WHERE tenant_id = ? AND menu_id IN (?, ?, ?)",
                    Long.class, HALF_CHECK_TENANT_ID, dirId, pageId, btnId);
            assertThat(remainPkgCount).isZero();
            assertThat(remainRoleCount).isZero();

            sysMenuService.deleteMenu(btnId);
            sysMenuService.deleteMenu(pageId);
            sysMenuService.deleteMenu(dirId);
        });

        TenantContextHolder.runWithTenant(HALF_CHECK_TENANT_ID, () -> {
            Long parentDept = sysDeptService.createDept(deptSave(null, 0L, "停用测试父部门", 1, null, null));
            Long childDept = sysDeptService.createDept(deptSave(null, parentDept, "停用测试子部门", 1, null, null));
            sysDeptService.updateDeptStatus(childDept, CommonStatusEnum.DISABLED.getCode());
            sysDeptService.updateDeptStatus(parentDept, CommonStatusEnum.DISABLED.getCode());

            DeptSaveRequest enableChildViaUpdate = deptSave(childDept, parentDept, "停用测试子部门", 1, null, null);
            enableChildViaUpdate.setStatus(CommonStatusEnum.NORMAL.getCode());
            assertThatThrownBy(() -> sysDeptService.updateDept(enableChildViaUpdate))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            sysDeptService.deleteDept(childDept);
            sysDeptService.deleteDept(parentDept);
        });
    }
}
