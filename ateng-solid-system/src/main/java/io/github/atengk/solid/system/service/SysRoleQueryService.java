package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.request.RoleQueryRequest;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard.ActorPrivilegeContext;
import io.github.atengk.solid.system.support.RoleViewSupport;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import io.github.atengk.solid.system.vo.RoleDetailVO;
import io.github.atengk.solid.system.vo.RoleOptionVO;
import io.github.atengk.solid.system.vo.RoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 角色分页检索、下拉选项隔离、可授权菜单树与角色绑定详情只读查询服务。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysRoleQueryService {

    private final SystemContextSupport contextSupport;
    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysRoleDeptMapper sysRoleDeptMapper;
    private final RoleAuthorizationSupport authorizationSupport;
    private final RbacPrivilegeGuard rbacPrivilegeGuard;

    /**
     * 分页查询当前工作租户下的角色列表（客户端未显式传排序字段时默认按 {@code sort ASC, id ASC}）。
     *
     * @param query 分页与筛选参数
     * @return 角色分页结果
     */
    public PageResult<RoleVO> pageRoles(RoleQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        RoleQueryRequest safeQuery = query != null ? query : new RoleQueryRequest();
        Page<SysRole> pageRequest = PageUtil.toPage(
                safeQuery, SysRole.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        String roleName = SystemContextSupport.trimToNull(safeQuery.getRoleName());
        String roleKey = SystemContextSupport.trimToNull(safeQuery.getRoleKey());
        Integer status = safeQuery.getStatus();

        Page<SysRole> rolePage = TenantContextHolder.runWithTenant(tenantId, () -> {
            LambdaQueryWrapper<SysRole> wrapper = Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, tenantId)
                    .like(roleName != null, SysRole::getRoleName, roleName)
                    .like(roleKey != null, SysRole::getRoleKey, roleKey)
                    .eq(status != null, SysRole::getStatus, status);
            if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
                wrapper.orderByAsc(SysRole::getSort).orderByAsc(SysRole::getId);
            }
            return sysRoleMapper.selectPage(pageRequest, wrapper);
        });

        List<SysRole> records = SystemContextSupport.safeList(rolePage.getRecords());
        if (records.isEmpty()) {
            return PageResult.of(Collections.emptyList(), rolePage.getTotal(), rolePage.getCurrent(), rolePage.getSize(), rolePage.getPages());
        }
        List<RoleVO> voList = new ArrayList<>(records.size());
        for (SysRole role : records) {
            voList.add(RoleViewSupport.toRoleVO(tenantId, role));
        }
        return PageResult.of(voList, rolePage.getTotal(), rolePage.getCurrent(), rolePage.getSize(), rolePage.getPages());
    }

    /**
     * 获取当前工作租户下可分配角色选项列表（非平台超管视角隐藏 {@code super_admin}）。
     *
     * @return 角色选项集合
     */
    public List<RoleOptionVO> listActiveOptions() {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        boolean showSuperAdmin = RoleViewSupport.isPlatformSuperAdminView(tenantId);

        List<SysRole> roles = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .eq(SysRole::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .orderByAsc(SysRole::getSort)
                        .orderByAsc(SysRole::getId)
        ));
        if (CollectionUtils.isEmpty(roles)) {
            return Collections.emptyList();
        }
        List<RoleOptionVO> options = new ArrayList<>(roles.size());
        for (SysRole role : roles) {
            if (!showSuperAdmin && SolidConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getRoleKey())) {
                continue;
            }
            options.add(RoleOptionVO.builder()
                    .id(role.getId())
                    .roleName(role.getRoleName())
                    .roleKey(role.getRoleKey())
                    .build());
        }
        return options.isEmpty() ? Collections.emptyList() : options;
    }

    /**
     * 获取当前工作租户在角色表单中允许勾选的菜单树（普通租户受套餐边界与 {@code platform_only} 限制，非超管再按自身权限交集裁剪）。
     *
     * @return 可授权菜单树
     */
    public List<MenuTreeVO> listGrantableMenuTree() {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        ActorPrivilegeContext actor = rbacPrivilegeGuard.resolveActorContext(
                tenantId, contextSupport.resolveCurrentActorUserId()
        );
        return authorizationSupport.buildGrantableMenuTree(tenantId, contextSupport.isMultiTenantEnabled(), actor);
    }

    /**
     * 查询指定角色详情及绑定的菜单 ID、自定部门 ID 列表。
     *
     * @param id 角色主键
     * @return 角色详情
     */
    public RoleDetailVO getDetail(Long id) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        if (id == null || id <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "角色主键无效");
        }
        SysRole role = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .eq(SysRole::getId, id)
        ));
        if (role == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标角色不存在或无权访问");
        }

        List<Long> menuIds = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMenuMapper.selectList(
                Wrappers.<SysRoleMenu>lambdaQuery()
                        .select(SysRoleMenu::getMenuId)
                        .eq(SysRoleMenu::getTenantId, tenantId)
                        .eq(SysRoleMenu::getRoleId, role.getId())
                        .orderByAsc(SysRoleMenu::getId)
        ).stream().map(SysRoleMenu::getMenuId).filter(Objects::nonNull).toList());

        List<Long> deptIds = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleDeptMapper.selectList(
                Wrappers.<SysRoleDept>lambdaQuery()
                        .select(SysRoleDept::getDeptId)
                        .eq(SysRoleDept::getTenantId, tenantId)
                        .eq(SysRoleDept::getRoleId, role.getId())
                        .orderByAsc(SysRoleDept::getId)
        ).stream().map(SysRoleDept::getDeptId).filter(Objects::nonNull).toList());

        return RoleViewSupport.toRoleDetailVO(tenantId, role, menuIds, deptIds);
    }
}
