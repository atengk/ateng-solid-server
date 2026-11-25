package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统参数新增与修改请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class ConfigSaveRequest {

    /**
     * 参数主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 参数名称
     */
    @NotBlank(message = "参数名称不能为空")
    @Size(min = 2, max = 100, message = "参数名称长度须在 2~100 个字符之间")
    private String configName;

    /**
     * 参数键名
     */
    @NotBlank(message = "参数键名不能为空")
    @Size(min = 2, max = 100, message = "参数键名长度须在 2~100 个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9._-]{2,100}$", message = "参数键名仅支持字母、数字、点、下划线和短横线（2~100 位）")
    private String configKey;

    /**
     * 参数键值
     */
    @NotBlank(message = "参数键值不能为空")
    @Size(min = 1, max = 2000, message = "参数键值长度须在 1~2000 个字符之间")
    private String configValue;

    /**
     * 参数类型（Y=系统内置，N=业务自建；新增默认 N）
     */
    private String configType;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}
