package io.github.atengk.solid.system.excel;

import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysUser;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.write.style.ColumnWidth;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 用户管理流式导出 Excel 视图对象（共 9 列，手机号与邮箱由 {@link ExcelSensitive} 透明脱敏，严禁导出密码哈希列）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserExportVO {

    /**
     * 用户雪花主键（字符串格式防 Excel 数值精度丢失）
     */
    @ExcelProperty("用户ID")
    @ColumnWidth(22)
    private String id;

    /**
     * 登录账号
     */
    @ExcelProperty("用户账号")
    @ColumnWidth(20)
    private String username;

    /**
     * 用户昵称
     */
    @ExcelProperty("用户昵称")
    @ColumnWidth(20)
    private String nickname;

    /**
     * 归属部门名称
     */
    @ExcelProperty("归属部门")
    @ColumnWidth(24)
    private String deptName;

    /**
     * 手机号码（导出自动脱敏）
     */
    @ExcelProperty("手机号码")
    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    @ColumnWidth(18)
    private String phone;

    /**
     * 电子邮箱（导出自动脱敏）
     */
    @ExcelProperty("电子邮箱")
    @ExcelSensitive(SensitiveType.EMAIL)
    @ColumnWidth(28)
    private String email;

    /**
     * 性别展示标签
     */
    @ExcelProperty("性别")
    @ColumnWidth(12)
    private String genderLabel;

    /**
     * 账号状态展示标签
     */
    @ExcelProperty("状态")
    @ColumnWidth(12)
    private String statusLabel;

    private static final DateTimeFormatter EXPORT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> FALLBACK_GENDER_LABELS = Map.of("0", "男", "1", "女", "2", "未知");

    /**
     * 创建时间（{@code yyyy-MM-dd HH:mm:ss}）
     */
    @ExcelProperty("创建时间")
    @ColumnWidth(22)
    private String createTime;

    /**
     * 将用户持久实体与部门/字典翻译结果转换为 9 列导出视图对象。
     *
     * @param user              用户实体
     * @param deptName          归属部门名称（可为空）
     * @param genderCodeToLabel 动态性别字典编码到标签映射
     * @return 用户导出视图对象
     */
    public static UserExportVO from(SysUser user, String deptName, Map<String, String> genderCodeToLabel) {
        String genderCode = user.getGender() != null ? user.getGender() : "2";
        Map<String, String> resolvedMap = genderCodeToLabel != null && !genderCodeToLabel.isEmpty()
                ? genderCodeToLabel
                : FALLBACK_GENDER_LABELS;
        String genderLabel = resolvedMap.getOrDefault(
                genderCode,
                FALLBACK_GENDER_LABELS.getOrDefault(genderCode, "未知")
        );
        String statusLabel = CommonStatusEnum.NORMAL.getCode().equals(user.getStatus()) ? "正常" : "停用";
        String createTimeStr = user.getCreateTime() != null ? EXPORT_TIME_FORMATTER.format(user.getCreateTime()) : "";
        return UserExportVO.builder()
                .id(user.getId() != null ? String.valueOf(user.getId()) : "")
                .username(user.getUsername())
                .nickname(user.getNickname())
                .deptName(deptName != null ? deptName : "")
                .phone(user.getPhone())
                .email(user.getEmail())
                .genderLabel(genderLabel)
                .statusLabel(statusLabel)
                .createTime(createTimeStr)
                .build();
    }
}
