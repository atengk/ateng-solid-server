package io.github.atengk.solid.system.api.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 个人基本资料更新指令。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"email", "phone"})
public class UpdatePersonalProfileCommand implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 电子邮箱（空白值由服务层归一化为 null 以清空绑定）
     */
    private String email;

    /**
     * 手机号码（空白值由服务层归一化为 null 以清空绑定）
     */
    private String phone;

    /**
     * 性别字典值（0=男，1=女，2=未知）
     */
    private String gender;
}
