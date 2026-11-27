package io.github.atengk.solid.system.support;

import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.RouteMenuDTO;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler.ScopeResolution;
import io.github.atengk.solid.system.service.RoleAuthorizationSupport;
import io.github.atengk.solid.system.service.remote.RemotePermissionServiceImpl;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard.ActorPrivilegeContext;
import io.github.atengk.solid.system.vo.DeptTreeVO;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全部 5 处递归树装配与子孙遍历方法的循环引用 {@code visited} 防栈溢出回归测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class TreeCycleGuardTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entityClass : List.of(
                SysDept.class, SysMenu.class, SysRole.class, SysRoleMenu.class,
                SysTenant.class, SysTenantPackage.class, SysTenantPackageMenu.class
        )) {
            if (TableInfoHelper.getTableInfo(entityClass) == null) {
                TableInfoHelper.initTableInfo(assistant, entityClass);
            }
        }
    }

    @Test
    @DisplayName("DeptTreeSupport.buildDeptTree 遇到脏数据环路时应当安全剪枝而不栈溢出")
    void shouldTerminateSafelyWhenDeptTreeContainsCycle() {
        SysDept root = dept(1L, 0L, "根部");
        SysDept child = dept(2L, 1L, "子部");
        SysDept backEdge = dept(1L, 2L, "环边");
        SysDept selfRoot = dept(0L, 0L, "自环零节点");

        List<DeptTreeVO> tree = DeptTreeSupport.buildDeptTree(
                List.of(root, child, backEdge, selfRoot),
                Collections.emptyMap()
        );
        assertThat(tree).isNotEmpty();
    }

    @Test
    @DisplayName("MenuTreeSupport.buildTreeWithAncestorRetention 遇到脏数据环路时应当安全剪枝而不栈溢出")
    void shouldTerminateSafelyWhenMenuTreeContainsCycle() {
        SysMenu dir1 = menu(1L, 0L, "M", "目录1", null);
        SysMenu dir2 = menu(2L, 1L, "M", "目录2", null);
        SysMenu backEdge = menu(1L, 2L, "M", "环边目录", null);

        List<MenuTreeVO> tree = MenuTreeSupport.buildTreeWithAncestorRetention(
                List.of(dir1, dir2, backEdge),
                false,
                m -> true
        );
        assertThat(tree).isNotEmpty();
    }

    @Test
    @DisplayName("RoleAuthorizationSupport 可授权树与半选子孙检测遇到菜单环路时应当安全终止")
    void shouldTerminateSafelyWhenRoleAuthorizationSupportEncountersMenuCycle() {
        SysMenuMapper menuMapper = mock(SysMenuMapper.class);
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenantPackageMapper packageMapper = mock(SysTenantPackageMapper.class);
        SysTenantPackageMenuMapper packageMenuMapper = mock(SysTenantPackageMenuMapper.class);

        SysMenu dir1 = menu(1L, 0L, "M", "目录1", null);
        SysMenu dir2 = menu(2L, 1L, "M", "目录2", null);
        SysMenu backEdge = menu(1L, 2L, "M", "环边", null);
        SysMenu leaf = menu(3L, 2L, "C", "菜单3", "sys:leaf:list");
        List<SysMenu> cyclicMenus = List.of(dir1, dir2, backEdge, leaf);

        RoleAuthorizationSupport support = new RoleAuthorizationSupport(
                menuMapper,
                mock(SysDeptMapper.class),
                tenantMapper,
                packageMapper,
                packageMenuMapper,
                mock(PermissionCacheService.class)
        );

        when(menuMapper.selectList(any()))
                .thenReturn(cyclicMenus)
                .thenReturn(List.of(dir1, leaf))
                .thenReturn(cyclicMenus);

        List<MenuTreeVO> grantable = support.buildGrantableMenuTree(0L, true);
        assertThat(grantable).isNotEmpty();

        SysTenant activeTenant = new SysTenant();
        activeTenant.setId(1L);
        activeTenant.setPackageId(88L);
        activeTenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        when(tenantMapper.selectOne(any())).thenReturn(activeTenant);

        SysTenantPackage activePackage = new SysTenantPackage();
        activePackage.setId(88L);
        activePackage.setStatus(CommonStatusEnum.NORMAL.getCode());
        when(packageMapper.selectById(88L)).thenReturn(activePackage);

        SysTenantPackageMenu pkgMenu = new SysTenantPackageMenu();
        pkgMenu.setPackageId(88L);
        pkgMenu.setMenuId(3L);
        when(packageMenuMapper.selectList(any())).thenReturn(List.of(pkgMenu));

        List<Long> valid = support.validateAndNormalizeMenuIds(1L, true, List.of(1L, 3L));
        assertThat(valid).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("RemotePermissionServiceImpl 路由构建与权限收集遇到菜单环路时应当安全终止")
    void shouldTerminateSafelyWhenRemotePermissionServiceEncountersMenuCycle() {
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
        SysMenuMapper menuMapper = mock(SysMenuMapper.class);

        SysMenu dir1 = menu(1L, 0L, "M", "目录1", null);
        SysMenu dir2 = menu(2L, 1L, "M", "目录2", null);
        SysMenu backEdge = menu(1L, 2L, "M", "环边", null);
        SysMenu leaf = menu(3L, 2L, "C", "页面3", "system:demo:view");

        when(menuMapper.selectList(any())).thenReturn(List.of(dir1, dir2, backEdge, leaf));

        SysRole role = new SysRole();
        role.setId(50L);
        role.setRoleKey("loop_role");
        role.setStatus(CommonStatusEnum.NORMAL.getCode());
        when(roleMapper.selectOne(any())).thenReturn(role);

        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(50L);
        rm.setMenuId(3L);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(rm));

        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(false);

        RemotePermissionServiceImpl service = new RemotePermissionServiceImpl(
                mock(SysUserRoleMapper.class),
                roleMapper,
                roleMenuMapper,
                menuMapper,
                mock(SysTenantPackageMapper.class),
                mock(SysTenantPackageMenuMapper.class),
                mock(io.github.atengk.solid.system.api.service.RemoteTenantService.class),
                tenantProps
        );

        List<RouteMenuDTO> routes = service.getRouteMenusByUserId(0L, 0L, 100L, true, true);
        assertThat(routes).isNotEmpty();

        List<String> perms = service.getPermissionsByRoleKey(0L, "loop_role");
        assertThat(perms).containsExactly("system:demo:view");
    }

    @Test
    @DisplayName("RbacPrivilegeGuard 权限解析与目录子孙检测遇到菜单环路时应当安全终止")
    void shouldTerminateSafelyWhenRbacPrivilegeGuardEncountersMenuCycle() {
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
        SysMenuMapper menuMapper = mock(SysMenuMapper.class);
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenantPackageMapper packageMapper = mock(SysTenantPackageMapper.class);
        SysTenantPackageMenuMapper packageMenuMapper = mock(SysTenantPackageMenuMapper.class);
        SolidDataPermissionHandler dataScopeHandler = mock(SolidDataPermissionHandler.class);

        SysMenu dir1 = menu(1L, 0L, "M", "目录1", null);
        SysMenu dir2 = menu(2L, 1L, "M", "目录2", null);
        SysMenu backEdge = menu(1L, 2L, "M", "环边", null);
        SysMenu leaf = menu(3L, 2L, "C", "页面3", "system:demo:list");
        List<SysMenu> cyclicMenus = List.of(dir1, dir2, backEdge, leaf);

        SysRole activeRole = new SysRole();
        activeRole.setId(60L);
        activeRole.setRoleKey("tenant_user");
        activeRole.setDataScope(DataScopeType.ALL.getCode());
        activeRole.setStatus(CommonStatusEnum.NORMAL.getCode());

        when(userRoleMapper.selectActiveRolesByUserId(1L, 100L)).thenReturn(List.of(activeRole));
        when(userRoleMapper.selectRoleIdsByUserId(1L, 100L)).thenReturn(List.of(60L));
        when(dataScopeHandler.resolveActorScope(1L, 100L)).thenReturn(ScopeResolution.ALLOW_ALL);

        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(60L);
        rm.setMenuId(3L);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(rm));

        SysTenant tenant = new SysTenant();
        tenant.setId(1L);
        tenant.setPackageId(88L);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(88L);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        when(packageMapper.selectById(88L)).thenReturn(pkg);

        SysTenantPackageMenu pm = new SysTenantPackageMenu();
        pm.setPackageId(88L);
        pm.setMenuId(3L);
        when(packageMenuMapper.selectList(any())).thenReturn(List.of(pm));

        when(menuMapper.selectList(any())).thenReturn(cyclicMenus);

        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);

        RbacPrivilegeGuard guard = new RbacPrivilegeGuard(
                userRoleMapper,
                mock(SysRoleMapper.class),
                roleMenuMapper,
                mock(SysRoleDeptMapper.class),
                menuMapper,
                tenantMapper,
                packageMapper,
                packageMenuMapper,
                dataScopeHandler,
                tenantProps
        );

        ActorPrivilegeContext ctx = guard.resolveActorContext(1L, 100L);
        assertThat(ctx.effectiveNonDirMenuIds()).containsExactly(3L);

        guard.assertMenusWithinActorPrivilege(1L, ctx, Set.of(1L, 3L));

        assertThat(SystemContextSupport.escapeLikePattern("  100%_!demo  ")).isEqualTo("100!%!_!!demo");
        assertThat(SystemContextSupport.escapeLikePattern("   ")).isNull();
    }

    private static SysDept dept(Long id, Long parentId, String name) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(parentId);
        dept.setDeptName(name);
        dept.setStatus(CommonStatusEnum.NORMAL.getCode());
        return dept;
    }

    private static SysMenu menu(Long id, Long parentId, String type, String title, String perms) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuType(type);
        menu.setTitle(title);
        menu.setPerms(perms);
        menu.setStatus(CommonStatusEnum.NORMAL.getCode());
        menu.setVisible(0);
        menu.setPlatformOnly(false);
        return menu;
    }
}
