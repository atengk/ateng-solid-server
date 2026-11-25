package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 通用资源启停状态切换请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class StatusUpdateRequest {

    /**
     * 目标状态（0=正常，1=停用）
     */
    @NotNull(message = "目标状态不能为空")
    @Min(value = 0, message = "目标状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "目标状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}
