package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 部门新增与修改请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"phone", "email"})
public class DeptSaveRequest {

    /**
     * 部门主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 上级部门主键（顶级部门传 0）
     */
    @NotNull(message = "上级部门不能为空")
    @Min(value = 0, message = "上级部门标识无效")
    private Long parentId;

    /**
     * 部门名称
     */
    @NotBlank(message = "部门名称不能为空")
    @Size(min = 2, max = 50, message = "部门名称长度须在 2~50 个字符之间")
    private String deptName;

    /**
     * 显示排序
     */
    @NotNull(message = "显示排序不能为空")
    @Min(value = 0, message = "显示排序不能小于 0")
    @Max(value = 9999, message = "显示排序不能大于 9999")
    private Integer sort;

    /**
     * 部门负责人用户主键（可选）
     */
    private Long leaderUserId;

    /**
     * 联系电话（可选，允许空白串以清空绑定）
     */
    @Size(max = 20, message = "联系电话长度不能超过 20 个字符")
    private String phone;

    /**
     * 电子邮箱（可选，允许空白串以清空绑定）
     */
    @Size(max = 64, message = "电子邮箱长度不能超过 64 个字符")
    private String email;

    /**
     * 部门状态（0=正常，1=停用；新增默认 0）
     */
    @Min(value = 0, message = "部门状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "部门状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}
