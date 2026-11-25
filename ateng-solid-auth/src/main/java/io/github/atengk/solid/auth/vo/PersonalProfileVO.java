package io.github.atengk.solid.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 个人中心完整内存真明文资料视图对象（对应 {@code GET /admin/auth/profile}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"email", "phone"})
public class PersonalProfileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户主键（JSON 序列化为字符串）
     */
    private Long userId;

    /**
     * 所属租户主键（JSON 序列化为字符串）
     */
    private Long tenantId;

    /**
     * 所属租户名称
     */
    private String tenantName;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 解密后的电子邮箱真明文
     */
    private String email;

    /**
     * 解密后的手机号码真明文
     */
    private String phone;

    /**
     * 性别字典值（0=男，1=女，2=未知）
     */
    private String gender;

    /**
     * 动态签发的头像可访问地址
     */
    private String avatar;

    /**
     * 所属部门名称
     */
    private String deptName;

    /**
     * 所属岗位名称集合
     */
    @Builder.Default
    private List<String> postNames = Collections.emptyList();

    /**
     * 拥有的角色展示集合
     */
    @Builder.Default
    private List<String> roles = Collections.emptyList();

    /**
     * 最近一次登录客户端 IP
     */
    private String loginIp;

    /**
     * 最近一次登录时间
     */
    private LocalDateTime loginDate;

    /**
     * 账号创建时间
     */
    private LocalDateTime createTime;

    /**
     * 是否仍需自助修改初始或重置密码
     */
    private boolean passwordNeedsChange;
}
