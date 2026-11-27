package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.vo.CreateTenantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 新增 SaaS 租户 4 步短事务原子初始化支撑组件（建租户 ➔ 根部门 ➔ {@code tenant_admin} 角色与套餐菜单 ➔ 默认管理员用户）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class TenantInitializationSupport {

    /**
     * 新建租户自动创建的默认租户管理员角色编码
     */
    public static final String TENANT_ADMIN_ROLE_KEY = "tenant_admin";

    private static final String TENANT_ADMIN_ROLE_NAME = "租户管理员";
    private static final String DEFAULT_GENDER_CODE = "2";
    private static final int MAX_NICKNAME_LENGTH = 30;

    private final SysTenantMapper sysTenantMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    /**
     * 在单短事务内顺序完成新租户主表、根部门、{@code tenant_admin} 角色及套餐菜单、初始管理员用户落库。
     *
     * @param command 已完成事务外强口令慢哈希的初始化命令
     * @return 初始化完成摘要视图
     */
    @Transactional(rollbackFor = Exception.class)
    public CreateTenantVO provisionNewTenantAtomically(ProvisionTenantCommand command) {
        // 1. 插入 sys_tenant 并获取新分配的雪花租户主键
        SysTenant tenant = insertTenantRecord(command);
        Long newTenantId = tenant.getId();

        // 2. 切入新租户上下文创建根部门
        // 3. 创建默认 tenant_admin 角色并写入套餐有效非平台专属菜单授权
        // 4. 创建初始租户管理员账号（pwd_update_time 为空）并绑定角色
        TenantContextHolder.runWithTenant(newTenantId, () -> {
            SysDept rootDept = insertRootDept(newTenantId, command.tenantName());
            SysRole tenantAdminRole = insertTenantAdminRole(newTenantId);
            insertRoleMenus(newTenantId, tenantAdminRole.getId(), command.packageMenuIds());
            insertTenantAdminUser(newTenantId, rootDept.getId(), tenantAdminRole.getId(), command);
        });

        return CreateTenantVO.builder()
                .tenantId(newTenantId)
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .adminUsername(command.adminUsername())
                .build();
    }

    private SysTenant insertTenantRecord(ProvisionTenantCommand command) {
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(command.tenantCode());
        tenant.setTenantName(command.tenantName());
        tenant.setPackageId(command.packageId());
        tenant.setExpireTime(command.expireTime());
        tenant.setAccountCount(command.accountCount());
        tenant.setStatus(command.status());
        tenant.setRemark(command.remark());
        try {
            TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.insert(tenant));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "租户编码已被占用", ex);
        }
        return tenant;
    }

    private SysDept insertRootDept(Long tenantId, String tenantName) {
        SysDept rootDept = new SysDept();
        rootDept.setTenantId(tenantId);
        rootDept.setParentId(0L);
        rootDept.setAncestors("0");
        rootDept.setDeptName(DeptTreeSupport.normalizeRootDeptName(tenantName));
        rootDept.setSort(1);
        rootDept.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysDeptMapper.insert(rootDept);
        return rootDept;
    }

    private SysRole insertTenantAdminRole(Long tenantId) {
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setRoleName(TENANT_ADMIN_ROLE_NAME);
        role.setRoleKey(TENANT_ADMIN_ROLE_KEY);
        role.setDataScope(DataScopeType.ALL.getCode());
        role.setMenuCheckStrictly(Boolean.TRUE);
        role.setDeptCheckStrictly(Boolean.FALSE);
        role.setSort(1);
        role.setStatus(CommonStatusEnum.NORMAL.getCode());
        role.setRemark("租户初始化自动创建的默认租户管理员角色");
        sysRoleMapper.insert(role);
        return role;
    }

    private void insertRoleMenus(Long tenantId, Long roleId, List<Long> packageMenuIds) {
        if (CollectionUtils.isEmpty(packageMenuIds)) {
            return;
        }
        List<SysRoleMenu> mappings = new ArrayList<>(packageMenuIds.size());
        for (Long menuId : packageMenuIds) {
            SysRoleMenu mapping = new SysRoleMenu();
            mapping.setTenantId(tenantId);
            mapping.setRoleId(roleId);
            mapping.setMenuId(menuId);
            mappings.add(mapping);
        }
        BatchUtil.insertBatch(sysRoleMenuMapper, mappings);
    }

    private void insertTenantAdminUser(
            Long tenantId,
            Long rootDeptId,
            Long tenantAdminRoleId,
            ProvisionTenantCommand command
    ) {
        SysUser adminUser = new SysUser();
        adminUser.setTenantId(tenantId);
        adminUser.setDeptId(rootDeptId);
        adminUser.setUsername(command.adminUsername());
        adminUser.setNickname(resolveAdminNickname(command.tenantName()));
        adminUser.setPassword(command.encodedPassword());
        adminUser.setPwdUpdateTime(null);
        adminUser.setPhone(null);
        adminUser.setEmail(null);
        adminUser.setGender(DEFAULT_GENDER_CODE);
        adminUser.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(adminUser);

        SysUserRole userRole = new SysUserRole();
        userRole.setTenantId(tenantId);
        userRole.setUserId(adminUser.getId());
        userRole.setRoleId(tenantAdminRoleId);
        sysUserRoleMapper.insert(userRole);
    }

    private static String resolveAdminNickname(String tenantName) {
        String candidate = tenantName + TENANT_ADMIN_ROLE_NAME;
        return candidate.length() <= MAX_NICKNAME_LENGTH ? candidate : TENANT_ADMIN_ROLE_NAME;
    }

    /**
     * 新建租户 4 步初始化命令参数。
     */
    public record ProvisionTenantCommand(
            String tenantCode,
            String tenantName,
            Long packageId,
            Integer accountCount,
            LocalDateTime expireTime,
            Integer status,
            String remark,
            String adminUsername,
            String encodedPassword,
            List<Long> packageMenuIds
    ) {
    }
}
