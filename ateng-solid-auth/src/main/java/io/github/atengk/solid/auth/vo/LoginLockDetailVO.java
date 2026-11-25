package io.github.atengk.solid.auth.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录防爆破锁定（错误码 101004）透传给前端的结构化倒计时详情视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginLockDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 锁定维度标识（{@code "IP"} 或 {@code "ACCOUNT"}）
     */
    private String lockType;

    /**
     * 剩余解除锁定秒数（以 JSON 数值返回供前端驱动秒级倒计时）
     */
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private long remainSeconds;
}
