package io.github.atengk.solid.system.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 头像上传结果数据传输对象，返回即时可访问预览地址及落库相对对象键。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvatarUploadDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 动态签发的 inline 预览访问地址
     */
    private String avatarUrl;

    /**
     * 持久存储于 {@code sys_user.avatar} 的相对对象键
     */
    private String objectPath;
}
