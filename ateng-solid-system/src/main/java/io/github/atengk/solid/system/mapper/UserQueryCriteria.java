package io.github.atengk.solid.system.mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户分页与导出持久化查询条件载体（专供 {@link SysUserMapper} 使用，避免 Mapper 层反向依赖请求 DTO）。
 *
 * @param tenantId       当前生效工作租户主键
 * @param username       账号或昵称模糊关键字
 * @param nickname       昵称模糊关键字
 * @param encryptedPhone 经确定性加密后的完整手机号密文（精确等值匹配）
 * @param status         账号启停状态
 * @param deptIds        展开部门子树后的目标部门主键集合
 * @param ids            指定导出用户主键集合
 * @param beginTime      创建时间起始边界
 * @param endTime        创建时间截止边界
 * @param defaultOrder   是否在 SQL 尾部追加默认时间与主键倒序排列
 * @author Ateng
 * @since 2026-11-25
 */
public record UserQueryCriteria(
        Long tenantId,
        String username,
        String encryptedPhone,
        Integer status,
        List<Long> deptIds,
        List<Long> ids,
        LocalDateTime beginTime,
        LocalDateTime endTime,
        boolean defaultOrder
) {
}
