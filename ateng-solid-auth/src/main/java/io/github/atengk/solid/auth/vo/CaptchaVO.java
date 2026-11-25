package io.github.atengk.solid.auth.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录前置图形验证码与多租户运行开关响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaptchaVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否开启登录图形验证码
     */
    private boolean enabled;

    /**
     * 是否开启 SaaS 多租户模式
     */
    private boolean tenantEnabled;

    /**
     * 图形验证码唯一流水键（未开启验证码时返回空串）
     */
    private String captchaKey;

    /**
     * Base64 DataURI 图形验证码内容（未开启验证码时返回空串）
     */
    private String imageBase64;

    /**
     * 验证码有效期（秒）
     */
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private long expireSeconds;
}
