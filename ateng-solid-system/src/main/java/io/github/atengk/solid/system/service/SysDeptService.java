package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.DeptSaveRequest;
import io.github.atengk.solid.system.support.AdaptiveRedisSupport;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import io.github.atengk.solid.system.support.DeptTreeSupport;
import io.github.atengk.solid.system.support.DeptTreeSupport.ContactPair;
import io.github.atengk.solid.system.support.SystemContextSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 部门新增、修改、状态切换与删除写操作服务，采用外层按租户隔离的互斥锁包裹内层编程式事务，
 * 确保事务提交后释放锁并落地防互环、{@code ancestors} 长度/深度（500/10）约束、启用负责人校验与删停行锁保护。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysDeptService {

    private static final String DEPT_TREE_LOCK_PREFIX = "tree:dept:";

    private final SystemContextSupport contextSupport;
    private final AdaptiveRedisSupport adaptiveRedisSupport;
    private final TransactionTemplate transactionTemplate;
    private final SysDeptMapper sysDeptMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleDeptMapper sysRoleDeptMapper;

    /**
     * 新增部门并自动拼接维护 {@code ancestors} 祖级路径。
     *
     * @param request 部门保存请求
     * @return 新建部门主键
     */
    public Long createDept(DeptSaveRequest request) {
        if (request == null || request.getParentId() == null || request.getParentId() < DeptTreeSupport.ROOT_PARENT_ID) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "部门上级标识无效");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long parentId = request.getParentId();
        String deptName = DeptTreeSupport.requireNormalizedDeptName(request.getDeptName());
        Integer status = SystemContextSupport.normalizeStatus(request.getStatus());
        int sort = request.getSort() != null ? request.getSort() : 0;
        ContactPair contact = DeptTreeSupport.normalizeAndValidateContact(request.getPhone(), request.getEmail());

        return supplyInLockedDeptTx(tenantId, () -> {
            String ancestors = resolveAncestorsForCreate(tenantId, parentId);
            validateLeaderUserIfPresent(tenantId, request.getLeaderUserId());
            assertSiblingDeptNameUnique(tenantId, parentId, deptName, null);

            SysDept entity = new SysDept();
            entity.setTenantId(tenantId);
            entity.setParentId(parentId);
            entity.setAncestors(ancestors);
            entity.setDeptName(deptName);
            entity.setSort(sort);
            entity.setLeaderUserId(request.getLeaderUserId());
            entity.setPhone(contact.phone());
            entity.setEmail(contact.email());
            entity.setStatus(status);

            try {
                TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.insert(entity));
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同级部门下已存在相同部门名称", ex);
            }
            return entity.getId();
        });
    }

    /**
     * 修改部门信息，在互斥锁与事务内校验防自环与上级可用性，若变更上级则级联校验并重算子孙 {@code ancestors}。
     *
     * @param request 部门修改请求
     */
    public void updateDept(DeptSaveRequest request) {
        if (request == null || request.getId() == null || request.getParentId() == null
                || request.getParentId() < DeptTreeSupport.ROOT_PARENT_ID) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改部门时主键与上级标识不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long newParentId = request.getParentId();
        String deptName = DeptTreeSupport.requireNormalizedDeptName(request.getDeptName());
        Integer requestedStatus = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : null;
        ContactPair contact = DeptTreeSupport.normalizeAndValidateContact(request.getPhone(), request.getEmail());

        executeInLockedDeptTx(tenantId, () -> {
            SysDept existing = requireDeptForUpdate(tenantId, request.getId());
            Integer targetStatus = requestedStatus != null ? requestedStatus : existing.getStatus();
            int sort = request.getSort() != null ? request.getSort()
                    : (existing.getSort() != null ? existing.getSort() : 0);

            // 1. 校验防自环、层级与路径长度上限、新上级可用性、子部门停用约束及同级重名
            String newAncestors = resolveAncestorsForUpdate(tenantId, existing, newParentId, targetStatus);
            if (CommonStatusEnum.DISABLED.getCode().equals(targetStatus)
                    && !CommonStatusEnum.DISABLED.getCode().equals(existing.getStatus())) {
                assertNoActiveChildren(tenantId, existing);
            }
            validateLeaderUserIfPresent(tenantId, request.getLeaderUserId());
            assertSiblingDeptNameUnique(tenantId, newParentId, deptName, existing.getId());

            // 2. 更新当前部门并支持置 NULL 清空联系字段；祖级变化时级联重算并校检全部子孙深度与长度
            updateCurrentDeptFields(tenantId, existing.getId(), newParentId, newAncestors, deptName,
                    sort, request.getLeaderUserId(), contact, targetStatus);
            if (!Objects.equals(existing.getAncestors(), newAncestors)) {
                cascadeUpdateChildrenAncestors(tenantId, existing.getId(), existing.getAncestors(), newAncestors);
            }
        });
    }

    /**
     * 切换部门启停状态；停用时校验不存在正常状态的子部门，启用时校验上级部门未处于停用态。
     *
     * @param id     部门主键
     * @param status 目标状态（0=正常，1=停用）
     */
    public void updateDeptStatus(Long id, Integer status) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Integer targetStatus = SystemContextSupport.requireValidStatus(status);
        executeInLockedDeptTx(tenantId, () -> {
            SysDept existing = requireDeptForUpdate(tenantId, id);
            if (CommonStatusEnum.DISABLED.getCode().equals(targetStatus)) {
                assertNoActiveChildren(tenantId, existing);
            } else if (!Objects.equals(existing.getParentId(), DeptTreeSupport.ROOT_PARENT_ID)) {
                assertParentNormalForEnable(tenantId, existing.getParentId());
            }

            LambdaUpdateWrapper<SysDept> wrapper = Wrappers.<SysDept>lambdaUpdate()
                    .eq(SysDept::getTenantId, tenantId)
                    .eq(SysDept::getId, id)
                    .set(SysDept::getStatus, targetStatus);
            TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.update(new SysDept(), wrapper));
        });
    }

    /**
     * 删除指定部门；持互斥锁与 {@code FOR UPDATE} 行锁校验子部门与引用关系，存在引用阻断抛出 {@code 102005}。
     *
     * @param id 部门主键
     */
    public void deleteDept(Long id) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        executeInLockedDeptTx(tenantId, () -> {
            requireDeptForUpdate(tenantId, id);

            Long childCount = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectCount(
                    Wrappers.<SysDept>lambdaQuery()
                            .eq(SysDept::getTenantId, tenantId)
                            .eq(SysDept::getParentId, id)
            ));
            if (childCount != null && childCount > 0L) {
                throw new BusinessException(SystemResultCode.RESOURCE_IN_USE, "存在下级部门，禁止删除");
            }

            // 数据权限隔离下仍须检索全租户活跃用户占用情况，防止持限权账号误删他部门占用的节点
            Long userCount = DataPermissionContextHolder.runWithoutPermission(() ->
                    TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectCount(
                            Wrappers.<SysUser>lambdaQuery()
                                    .eq(SysUser::getTenantId, tenantId)
                                    .eq(SysUser::getDeptId, id)
                    ))
            );
            if (userCount != null && userCount > 0L) {
                throw new BusinessException(SystemResultCode.RESOURCE_IN_USE, "该部门下仍有关联用户，请先调整部门后再删除");
            }

            long soleCustomRoleCount = DataPermissionContextHolder.runWithoutPermission(() ->
                    TenantContextHolder.runWithTenant(tenantId, () -> sysRoleDeptMapper.countSoleBoundCustomRoles(tenantId, id))
            );
            if (soleCustomRoleCount > 0L) {
                throw new BusinessException(SystemResultCode.RESOURCE_IN_USE,
                        "存在仅绑定该部门的自定数据权限角色，请先调整角色数据范围后再删除");
            }

            TenantContextHolder.runWithTenant(tenantId, () -> {
                sysRoleDeptMapper.delete(
                        Wrappers.<SysRoleDept>lambdaQuery()
                                .eq(SysRoleDept::getTenantId, tenantId)
                                .eq(SysRoleDept::getDeptId, id)
                );
                sysDeptMapper.deleteById(id);
            });
        });
    }

    private <T> T supplyInLockedDeptTx(Long tenantId, Supplier<T> action) {
        return adaptiveRedisSupport.supplyWithMutexLock(
                DEPT_TREE_LOCK_PREFIX + tenantId,
                null,
                () -> transactionTemplate.execute(txStatus -> action.get())
        );
    }

    private void executeInLockedDeptTx(Long tenantId, Runnable action) {
        adaptiveRedisSupport.executeWithMutexLock(
                DEPT_TREE_LOCK_PREFIX + tenantId,
                null,
                () -> transactionTemplate.executeWithoutResult(txStatus -> action.run())
        );
    }

    private SysDept requireDeptForUpdate(Long tenantId, Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标部门不存在");
        }
        SysDept dept = TenantContextHolder.runWithTenant(tenantId, () -> {
            LambdaQueryWrapper<SysDept> lockQuery = Wrappers.<SysDept>lambdaQuery()
                    .eq(SysDept::getTenantId, tenantId)
                    .eq(SysDept::getId, id);
            PessimisticLockUtil.forUpdate(lockQuery);
            return sysDeptMapper.selectOne(lockQuery);
        });
        if (dept == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标部门不存在");
        }
        return dept;
    }

    private String resolveAncestorsForCreate(Long tenantId, Long parentId) {
        if (Objects.equals(parentId, DeptTreeSupport.ROOT_PARENT_ID)) {
            return DeptTreeSupport.ROOT_ANCESTORS;
        }
        SysDept parent = requireParentDept(tenantId, parentId);
        if (!CommonStatusEnum.NORMAL.getCode().equals(parent.getStatus())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "所选上级部门已停用，不可挂载下级部门");
        }
        String ancestors = DeptTreeSupport.buildChildAncestors(parent);
        DeptTreeSupport.validateAncestorsBounds(ancestors);
        return ancestors;
    }

    private String resolveAncestorsForUpdate(
            Long tenantId,
            SysDept existing,
            Long newParentId,
            Integer targetStatus
    ) {
        if (Objects.equals(newParentId, existing.getId())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "上级部门不能选择当前部门自身");
        }
        if (Objects.equals(newParentId, DeptTreeSupport.ROOT_PARENT_ID)) {
            return DeptTreeSupport.ROOT_ANCESTORS;
        }
        SysDept newParent = requireParentDept(tenantId, newParentId);
        String selfPrefix = DeptTreeSupport.buildChildAncestors(existing);
        String parentAncestors = newParent.getAncestors();
        if (Objects.equals(parentAncestors, selfPrefix)
                || (parentAncestors != null && parentAncestors.startsWith(selfPrefix + ","))) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "上级部门不能选择当前部门或下级部门");
        }
        boolean changingParent = !Objects.equals(newParentId, existing.getParentId());
        boolean enablingDept = CommonStatusEnum.NORMAL.getCode().equals(targetStatus)
                && !CommonStatusEnum.NORMAL.getCode().equals(existing.getStatus());
        if ((changingParent || enablingDept) && !CommonStatusEnum.NORMAL.getCode().equals(newParent.getStatus())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "上级部门处于停用状态，不可挂载或启用子部门");
        }
        String newAncestors = DeptTreeSupport.buildChildAncestors(newParent);
        DeptTreeSupport.validateAncestorsBounds(newAncestors);
        return newAncestors;
    }

    private void assertParentNormalForEnable(Long tenantId, Long parentId) {
        SysDept parent = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectOne(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getId, parentId)
        ));
        if (parent != null && !CommonStatusEnum.NORMAL.getCode().equals(parent.getStatus())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "上级部门处于停用状态，请先启用上级部门");
        }
    }

    private SysDept requireParentDept(Long tenantId, Long parentId) {
        SysDept parent = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectOne(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getId, parentId)
        ));
        if (parent == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "所选上级部门不存在");
        }
        return parent;
    }

    private void assertNoActiveChildren(Long tenantId, SysDept dept) {
        String prefix = DeptTreeSupport.buildChildAncestors(dept);
        Long activeCount = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectCount(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .and(w -> w.eq(SysDept::getAncestors, prefix)
                                .or(nested -> SystemContextSupport.applyPrefixLike(nested, SysDept::getAncestors, prefix + ",")))
        ));
        if (activeCount != null && activeCount > 0L) {
            throw new BusinessException(SystemResultCode.RESOURCE_IN_USE, "该部门存在未停用的下级部门，请先停用下级部门");
        }
    }

    private void assertSiblingDeptNameUnique(Long tenantId, Long parentId, String deptName, Long excludeId) {
        Long count = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectCount(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getParentId, parentId)
                        .eq(SysDept::getDeptName, deptName)
                        .ne(excludeId != null, SysDept::getId, excludeId)
        ));
        if (count != null && count > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同级部门下已存在相同部门名称");
        }
    }

    private void validateLeaderUserIfPresent(Long tenantId, Long leaderUserId) {
        if (leaderUserId == null) {
            return;
        }
        Long count = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectCount(
                        Wrappers.<SysUser>lambdaQuery()
                                .eq(SysUser::getTenantId, tenantId)
                                .eq(SysUser::getId, leaderUserId)
                                .eq(SysUser::getStatus, CommonStatusEnum.NORMAL.getCode())
                ))
        );
        if (count == null || count <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "指定的部门负责人在当前租户下不存在或已停用");
        }
    }

    private void updateCurrentDeptFields(
            Long tenantId,
            Long deptId,
            Long parentId,
            String ancestors,
            String deptName,
            Integer sort,
            Long leaderUserId,
            ContactPair contact,
            Integer status
    ) {
        LambdaUpdateWrapper<SysDept> wrapper = Wrappers.<SysDept>lambdaUpdate()
                .eq(SysDept::getTenantId, tenantId)
                .eq(SysDept::getId, deptId)
                .set(SysDept::getParentId, parentId)
                .set(SysDept::getAncestors, ancestors)
                .set(SysDept::getDeptName, deptName)
                .set(SysDept::getSort, sort)
                .set(SysDept::getLeaderUserId, leaderUserId)
                .set(SysDept::getPhone, ContactFieldCrypto.encryptPhoneForQuery(contact.phone()))
                .set(SysDept::getEmail, ContactFieldCrypto.encryptEmailForQuery(contact.email()))
                .set(SysDept::getStatus, status);
        try {
            TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.update(new SysDept(), wrapper));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同级部门下已存在相同部门名称", ex);
        }
    }

    private void cascadeUpdateChildrenAncestors(
            Long tenantId,
            Long deptId,
            String oldAncestors,
            String newAncestors
    ) {
        String oldPrefix = oldAncestors + "," + deptId;
        String newPrefix = newAncestors + "," + deptId;
        List<SysDept> descendants = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(
                Wrappers.<SysDept>lambdaQuery()
                        .select(SysDept::getId, SysDept::getAncestors)
                        .eq(SysDept::getTenantId, tenantId)
                        .and(w -> w.eq(SysDept::getAncestors, oldPrefix)
                                .or(nested -> SystemContextSupport.applyPrefixLike(nested, SysDept::getAncestors, oldPrefix + ",")))
        ));
        if (CollectionUtils.isEmpty(descendants)) {
            return;
        }
        List<SysDept> updates = new ArrayList<>(descendants.size());
        for (SysDept child : descendants) {
            if (child.getId() != null && child.getAncestors() != null && child.getAncestors().startsWith(oldPrefix)) {
                String updatedAncestors = newPrefix + child.getAncestors().substring(oldPrefix.length());
                DeptTreeSupport.validateAncestorsBounds(updatedAncestors);
                SysDept patch = new SysDept();
                patch.setId(child.getId());
                patch.setAncestors(updatedAncestors);
                updates.add(patch);
            }
        }
        if (!updates.isEmpty()) {
            TenantContextHolder.runWithTenant(tenantId, () -> BatchUtil.updateBatch(sysDeptMapper, updates));
        }
    }
}
