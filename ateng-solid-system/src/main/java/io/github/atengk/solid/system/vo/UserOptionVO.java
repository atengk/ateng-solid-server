package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 用户下拉选择器轻量化视图对象（字段命名对齐前端 {@code admin-web} 用户选项契约）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class UserOptionVO {

    /**
     * 用户主键
     */
    Long userId;

    /**
     * 登录账号
     */
    String username;

    /**
     * 用户昵称
     */
    String nickname;
}
