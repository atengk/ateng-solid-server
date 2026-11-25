package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 动态字典数据明细新增与修改请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class DictDataSaveRequest {

    /**
     * 字典明细主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 所属字典类型编码（修改时若传值须与原类型一致）
     */
    @Size(max = 64, message = "字典类型编码长度不能超过 64 个字符")
    private String dictType;

    /**
     * 字典展示标签
     */
    @NotBlank(message = "字典标签不能为空")
    @Size(min = 1, max = 64, message = "字典标签长度须在 1~64 个字符之间")
    private String dictLabel;

    /**
     * 字典真实键值
     */
    @NotBlank(message = "字典键值不能为空")
    @Size(min = 1, max = 64, message = "字典键值长度须在 1~64 个字符之间")
    private String dictValue;

    /**
     * 回显标签语义样式（空串 / primary / success / warning / danger / info）
     */
    @Size(max = 32, message = "标签样式长度不能超过 32 个字符")
    private String tagType;

    /**
     * 自定义扩展 CSS 类名
     */
    @Size(max = 100, message = "自定义样式类名长度不能超过 100 个字符")
    private String cssClass;

    /**
     * 显示排序（升序）
     */
    @NotNull(message = "显示排序不能为空")
    @Min(value = 0, message = "显示排序不能小于 0")
    @Max(value = 9999, message = "显示排序不能大于 9999")
    private Integer sort;

    /**
     * 是否默认项（0=否，1=是）
     */
    @Min(value = 0, message = "是否默认仅支持 0（否）或 1（是）")
    @Max(value = 1, message = "是否默认仅支持 0（否）或 1（是）")
    private Integer isDefault;

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
