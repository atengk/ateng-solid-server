package io.github.atengk.solid.system.excel;

import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.write.style.ColumnWidth;

/**
 * 用户批量导入 Excel 行映射模型（共 9 列，对齐模板下载与 {@code HeaderValidationStrategy.LOOSE} 表头契约）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class UserImportDTO {

    /**
     * 登录账号（必填，2~30 位字母/数字/下划线）
     */
    @ExcelProperty("用户账号")
    @ExcelPrompt(title = "必填", value = "2~30 位字母、数字或下划线；文件内及当前租户不可重复")
    @ColumnWidth(20)
    @NotBlank(message = "用户账号不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{2,30}$", message = "用户账号须为 2~30 位字母、数字或下划线")
    private String username;

    /**
     * 用户昵称（必填，2~30 字符）
     */
    @ExcelProperty("用户昵称")
    @ExcelPrompt(title = "必填", value = "2~30 个非空白字符")
    @ColumnWidth(20)
    @NotBlank(message = "用户昵称不能为空")
    @Size(min = 2, max = 30, message = "用户昵称长度须在 2~30 个字符之间")
    private String nickname;

    /**
     * 归属部门名称或斜杠分隔全路径
     */
    @ExcelProperty("归属部门")
    @ExcelPrompt(title = "选填", value = "填写唯一部门名称（如：后端组）或 / 分隔全路径（如：总部/研发中心/后端组）")
    @ColumnWidth(28)
    private String deptName;

    /**
     * 岗位编码（多个以英文逗号分隔）
     */
    @ExcelProperty("岗位编码")
    @ExcelPrompt(title = "选填", value = "英文逗号 , 分隔一个或多个有效岗位编码")
    @ColumnWidth(22)
    private String postCodes;

    /**
     * 角色编码（多个以英文逗号分隔，禁止填 {@code super_admin}）
     */
    @ExcelProperty("角色编码")
    @ExcelPrompt(title = "选填", value = "英文逗号 , 分隔一个或多个有效角色编码（禁止填 super_admin）")
    @ColumnWidth(22)
    private String roleCodes;

    /**
     * 手机号码（11 位大陆手机号）
     */
    @ExcelProperty("手机号码")
    @ExcelPrompt(title = "选填", value = "11 位中国大陆手机号；文件内及同租户不可重复")
    @ColumnWidth(18)
    private String phone;

    /**
     * 电子邮箱（<= 64 位）
     */
    @ExcelProperty("电子邮箱")
    @ExcelPrompt(title = "选填", value = "标准邮箱格式且不超过 64 个字符；文件内及同租户不可重复")
    @ColumnWidth(26)
    private String email;

    /**
     * 性别标签（男 / 女 / 未知）
     */
    @ExcelProperty("性别")
    @ExcelPrompt(title = "选填", value = "男 / 女 / 未知（留空默认：未知）")
    @ExcelDropdown(value = {"男", "女", "未知"})
    @ColumnWidth(14)
    private String genderLabel;

    /**
     * 账号状态标签（正常 / 停用）
     */
    @ExcelProperty("状态")
    @ExcelPrompt(title = "选填", value = "正常 / 停用（留空默认：正常）")
    @ExcelDropdown(value = {"正常", "停用"})
    @ColumnWidth(14)
    private String statusLabel;
}
