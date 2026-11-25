package io.github.atengk.solid.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 个人中心头像直传响应视图对象（对应 {@code POST /admin/auth/profile/avatar}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvatarUploadVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 动态签发的 inline 预览访问地址
     */
    private String avatarUrl;

    /**
     * 持久化于 {@code sys_user.avatar} 的相对对象键
     */
    private String objectPath;
}
