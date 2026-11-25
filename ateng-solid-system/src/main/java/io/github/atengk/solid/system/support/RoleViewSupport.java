package io.github.atengk.solid.system.support;

import cn.dev33.satoken.SaManager;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.request.RoleSaveRequest;
import io.github.atengk.solid.system.vo.RoleDetailVO;
import io.github.atengk.solid.system.vo.RoleVO;

import java.util.List;

/**
 * 角色内置超管保护、保留编码校验与视图对象装配辅助工具。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class RoleViewSupport {

    private RoleViewSupport() {
    }

    /**
     * 判断角色编码是否命中系统保留关键词（{@code super_admin} / {@code admin}）。
     *
     * @param roleKey 角色编码
     * @return 命中返回 {@code true}
     */
    public static boolean isReservedRoleKey(String roleKey) {
        return SolidConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(roleKey)
                || SolidConstants.RESERVED_ADMIN_ROLE.equalsIgnoreCase(roleKey);
    }

    /**
     * 判断角色是否属于平台内置超级管理员角色。
     *
     * @param tenantId 工作租户主键
     * @param role     角色实体
     * @return 属于平台内置超管返回 {@code true}
     */
    public static boolean isBuiltinSuperAdmin(Long tenantId, SysRole role) {
        return SolidConstants.PLATFORM_TENANT_ID.equals(tenantId)
                && (SolidConstants.SUPER_ADMIN_ROLE_ID.equals(role.getId())
                || SolidConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getRoleKey()));
    }

    /**
     * 断言目标角色非平台内置超级管理员角色，命中时统一抛出 {@code 102007}。
     *
     * @param tenantId 工作租户主键
     * @param role     角色实体
     * @param message  拦截提示信息
     */
    public static void assertNotBuiltinSuperAdmin(Long tenantId, SysRole role, String message) {
        if (isBuiltinSuperAdmin(tenantId, role)) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, message);
        }
    }

    /**
     * 判断当前请求上下文是否处于平台超管视角（用于角色下拉框决定是否显式保留 {@code super_admin}）。
     *
     * @param tenantId 工作租户主键
     * @return 平台超管视角返回 {@code true}
     */
    public static boolean isPlatformSuperAdminView(Long tenantId) {
        if (!SolidConstants.PLATFORM_TENANT_ID.equals(tenantId)) {
            return false;
        }
        if (DataPermissionContextHolder.isSuperAdmin()) {
            return true;
        }
        return SaManager.getSaTokenContext().isValid()
                && LoginHelper.isSuperAdmin(SolidConstants.ADMIN_LOGIN_TYPE);
    }

    /**
     * 校验并规范化角色保存请求的基础字段，阻断系统保留角色编码（{@code 102004}）；修改时可选状态为 {@code null} 保留既有值。
     *
     * @param existing 既有角色实体（新增传 {@code null}）
     * @param request  角色保存请求
     * @return 规范化角色载荷
     */
    public static NormalizedRolePayload validateAndNormalizePayload(SysRole existing, RoleSaveRequest request) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "角色请求参数不能为空");
        }
        String roleName = SystemContextSupport.trimToNull(request.getRoleName());
        String roleKey = SystemContextSupport.trimToNull(request.getRoleKey());
        if (roleName == null || roleName.length() < 2 || roleName.length() > 50) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "角色名称长度须在 2~50 个字符之间");
        }
        if (roleKey == null || roleKey.length() < 2 || roleKey.length() > 64) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "角色编码长度须在 2~64 个字符之间");
        }
        if (isReservedRoleKey(roleKey)) {
            throw new BusinessException(SystemResultCode.RESERVED_ROLE_KEY, "禁止使用保留角色编码：" + roleKey);
        }
        Integer status = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : (existing != null ? existing.getStatus() : SystemContextSupport.normalizeStatus(null));
        int sort = request.getSort() != null ? request.getSort() : 0;
        boolean menuCheckStrictly = request.getMenuCheckStrictly() != null
                ? request.getMenuCheckStrictly()
                : (existing == null || !Boolean.FALSE.equals(existing.getMenuCheckStrictly()));
        String remark = SystemContextSupport.trimToNull(request.getRemark());
        return new NormalizedRolePayload(roleName, roleKey, sort, status, menuCheckStrictly, remark);
    }

    /**
     * 装配角色列表分页视图对象。
     *
     * @param tenantId 工作租户主键
     * @param role     角色实体
     * @return 角色分页视图对象
     */
    public static RoleVO toRoleVO(Long tenantId, SysRole role) {
        return RoleVO.builder()
                .id(role.getId())
                .roleName(role.getRoleName())
                .roleKey(role.getRoleKey())
                .dataScope(role.getDataScope())
                .menuCheckStrictly(role.getMenuCheckStrictly())
                .deptCheckStrictly(role.getDeptCheckStrictly())
                .sort(role.getSort())
                .status(role.getStatus())
                .remark(role.getRemark())
                .builtinSuperAdmin(isBuiltinSuperAdmin(tenantId, role))
                .createTime(role.getCreateTime())
                .build();
    }

    /**
     * 装配角色详情视图对象。
     *
     * @param tenantId 工作租户主键
     * @param role     角色实体
     * @param menuIds  关联菜单主键集合
     * @param deptIds  关联自定部门主键集合
     * @return 角色详情视图对象
     */
    public static RoleDetailVO toRoleDetailVO(Long tenantId, SysRole role, List<Long> menuIds, List<Long> deptIds) {
        return RoleDetailVO.builder()
                .id(role.getId())
                .roleName(role.getRoleName())
                .roleKey(role.getRoleKey())
                .dataScope(role.getDataScope())
                .menuCheckStrictly(role.getMenuCheckStrictly())
                .deptCheckStrictly(role.getDeptCheckStrictly())
                .sort(role.getSort())
                .status(role.getStatus())
                .remark(role.getRemark())
                .builtinSuperAdmin(isBuiltinSuperAdmin(tenantId, role))
                .createTime(role.getCreateTime())
                .menuIds(SystemContextSupport.safeList(menuIds))
                .deptIds(SystemContextSupport.safeList(deptIds))
                .build();
    }

    /**
     * 规范化后的角色请求基础属性记录。
     *
     * @param roleName          角色名称
     * @param roleKey           角色编码
     * @param sort              显示排序
     * @param status            角色状态（0=正常，1=停用）
     * @param menuCheckStrictly 菜单权限树是否父子联动
     * @param remark            备注信息
     */
    public record NormalizedRolePayload(
            String roleName,
            String roleKey,
            int sort,
            Integer status,
            boolean menuCheckStrictly,
            String remark
    ) {
    }
}
