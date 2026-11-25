package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.DeptQueryRequest;
import io.github.atengk.solid.system.support.DeptTreeSupport;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.DeptTreeVO;
import io.github.atengk.solid.system.vo.DeptVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部门树检索、轻量下拉选项与部门详情只读查询服务，隔离部门联系人敏感明文与数据权限下负责人姓名聚合。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysDeptQueryService {

    private final SystemContextSupport contextSupport;
    private final SysDeptMapper sysDeptMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 查询当前工作租户下的部门树结构，按名称或状态筛选时自动保留命中节点的祖先路径。
     *
     * @param query 检索条件
     * @return 部门树列表
     */
    public List<DeptTreeVO> listTree(DeptQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        List<SysDept> allDepts = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .orderByAsc(SysDept::getSort)
                        .orderByAsc(SysDept::getId)
        ));
        if (CollectionUtils.isEmpty(allDepts)) {
            return Collections.emptyList();
        }

        String keyword = query != null ? SystemContextSupport.trimToNull(query.getDeptName()) : null;
        Integer statusFilter = query != null ? query.getStatus() : null;
        List<SysDept> targetDepts = DeptTreeSupport.retainMatchedWithAncestors(allDepts, keyword, statusFilter);
        if (targetDepts.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, SysUser> leaderMap = loadLeaderMap(tenantId, targetDepts);
        return DeptTreeSupport.buildDeptTree(targetDepts, leaderMap);
    }

    /**
     * 查询当前工作租户下处于正常启用状态的部门下拉选项树（不透出联系电话与邮箱字段）。
     *
     * @return 脱敏后的正常部门选项树列表
     */
    public List<DeptTreeVO> listActiveTreeOptions() {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        List<SysDept> activeDepts = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .orderByAsc(SysDept::getSort)
                        .orderByAsc(SysDept::getId)
        ));
        if (CollectionUtils.isEmpty(activeDepts)) {
            return Collections.emptyList();
        }
        return DeptTreeSupport.buildDeptOptionTree(activeDepts);
    }

    /**
     * 查询指定部门详情（含联系电话与电子邮箱内存真明文）。
     *
     * @param id 部门主键
     * @return 部门详情视图对象
     * @throws BusinessException 记录不存在时抛出 {@code 102001}
     */
    public DeptVO getDetail(Long id) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标部门不存在");
        }
        SysDept dept = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectOne(
                Wrappers.<SysDept>lambdaQuery()
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getId, id)
        ));
        if (dept == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标部门不存在");
        }
        Map<Long, SysUser> leaderMap = loadLeaderMap(tenantId, List.of(dept));
        SysUser leader = dept.getLeaderUserId() != null ? leaderMap.get(dept.getLeaderUserId()) : null;
        return DeptTreeSupport.toDeptVO(dept, leader);
    }

    private Map<Long, SysUser> loadLeaderMap(Long tenantId, List<SysDept> depts) {
        Set<Long> leaderIds = new HashSet<>();
        for (SysDept dept : depts) {
            if (dept.getLeaderUserId() != null) {
                leaderIds.add(dept.getLeaderUserId());
            }
        }
        if (leaderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUser> leaders = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectList(
                        Wrappers.<SysUser>lambdaQuery()
                                .select(SysUser::getId, SysUser::getNickname, SysUser::getUsername)
                                .eq(SysUser::getTenantId, tenantId)
                                .in(SysUser::getId, leaderIds)
                ))
        );
        if (CollectionUtils.isEmpty(leaders)) {
            return Collections.emptyMap();
        }
        Map<Long, SysUser> map = new HashMap<>(leaders.size());
        for (SysUser user : leaders) {
            map.put(user.getId(), user);
        }
        return map;
    }
}
