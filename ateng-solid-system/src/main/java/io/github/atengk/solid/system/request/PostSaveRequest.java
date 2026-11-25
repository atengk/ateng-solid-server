package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 岗位新增与修改请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class PostSaveRequest {

    /**
     * 岗位主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 岗位编码
     */
    @NotBlank(message = "岗位编码不能为空")
    @Size(min = 2, max = 64, message = "岗位编码长度须在 2~64 个字符之间")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "岗位编码仅支持字母、数字、下划线和短横线")
    private String postCode;

    /**
     * 岗位名称
     */
    @NotBlank(message = "岗位名称不能为空")
    @Size(min = 2, max = 50, message = "岗位名称长度须在 2~50 个字符之间")
    private String postName;

    /**
     * 显示排序
     */
    @NotNull(message = "显示排序不能为空")
    @Min(value = 0, message = "显示排序不能小于 0")
    @Max(value = 9999, message = "显示排序不能大于 9999")
    private Integer sort;

    /**
     * 岗位状态（0=正常，1=停用；新增默认 0）
     */
    @Min(value = 0, message = "岗位状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "岗位状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}
