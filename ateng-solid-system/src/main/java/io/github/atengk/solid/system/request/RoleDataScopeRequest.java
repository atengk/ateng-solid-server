package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
    @Size(max = 1000, message = "自定部门数量不能超过 1000 个")
    private List<@NotNull(message = "部门主键不能为空") @Positive(message = "部门主键必须为正整数") Long> deptIds;
}
