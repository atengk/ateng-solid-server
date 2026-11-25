package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 动态字典类型新增与修改请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class DictTypeSaveRequest {

    /**
     * 字典类型主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 字典名称
     */
    @NotBlank(message = "字典名称不能为空")
    @Size(min = 2, max = 50, message = "字典名称长度须在 2~50 个字符之间")
    private String dictName;

    /**
     * 字典类型编码（新增必填且创建后锁定只读）
     */
    @Size(max = 64, message = "字典类型编码长度不能超过 64 个字符")
    @Pattern(regexp = "^$|^[a-z][a-z0-9_]{1,63}$", message = "字典类型编码须以小写字母开头，由小写字母、数字和下划线组成（2~64 位）")
    private String dictType;

    /**
     * 启停状态（0=正常，1=停用；新增默认 0）
     */
    @Min(value = 0, message = "状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}
