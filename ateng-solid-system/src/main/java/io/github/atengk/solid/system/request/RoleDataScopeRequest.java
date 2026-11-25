package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 角色 5 级 {@code @DataScope} 数据范围分配请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class RoleDataScopeRequest {

    /**
     * 数据范围类型（1=全部，2=自定部门，3=本部门，4=本部门及以下，5=仅本人）
     */
    @NotNull(message = "数据权限范围不能为空")
    @Min(value = 1, message = "数据权限范围仅支持 1~5")
    @Max(value = 5, message = "数据权限范围仅支持 1~5")
    private Integer dataScope;

    /**
     * 自定部门树是否父子联动（默认 false 独立勾选）
     */
    private Boolean deptCheckStrictly;

    /**
     * 自定部门主键列表（当 dataScope=2 时至少勾选 1 个部门）
     */
    private List<Long> deptIds;
}
