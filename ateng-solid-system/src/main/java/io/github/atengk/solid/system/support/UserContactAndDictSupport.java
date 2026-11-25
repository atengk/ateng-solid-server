package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.common.validator.PasswordPolicyValidator;
import io.github.atengk.solid.system.service.SysConfigService;
import io.github.atengk.solid.system.service.SysDictQueryService;
import io.github.atengk.solid.system.vo.DictDataVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户联系方式格式规整、动态性别字典（{@code sys_user_gender}）解析与账号/昵称/初始密码通用校验支撑。
 * <p>
 * 统一收口用户管理 CRUD 与 Excel 批量导入导出之间的校验规则，杜绝手机号/邮箱正则、动态字典映射及
 * {@code sys.user.initPassword} 读取逻辑在多处重复或出现硬编码漂移。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class UserContactAndDictSupport {

    /**
     * 性别动态字典类型编码
     */
    public static final String GENDER_DICT_TYPE = "sys_user_gender";

    private static final String DEFAULT_GENDER_CODE = "2";
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 30;
    private static final int MAX_EMAIL_LENGTH = 64;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{2,30}$");
    private static final Pattern MOBILE_PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final SysDictQueryService sysDictQueryService;
    private final SysConfigService sysConfigService;

    /**
     * 校验并规范化登录账号（裁剪空白后须满足 2~30 位字母、数字或下划线）。
     *
     * @param rawUsername 原始登录账号输入
     * @return 规范化账号
     */
    public String requireValidUsername(String rawUsername) {
        return requireValidUsernameFormat(rawUsername, "用户账号须为 2~30 位字母、数字或下划线");
    }

    /**
     * 静态校验并规范化登录账号（裁剪空白后须满足 2~30 位字母、数字或下划线）。
     *
     * @param rawUsername 原始登录账号输入
     * @param errorMsg    校验失败提示信息
     * @return 规范化账号
     */
    public static String requireValidUsernameFormat(String rawUsername, String errorMsg) {
        String username = SystemContextSupport.trimToNull(rawUsername);
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, errorMsg);
        }
        return username;
    }

    /**
     * 校验并规范化用户昵称（裁剪首尾空白后长度仍须满足 2~30 个字符，阻断 {@code " a "} 绕过）。
     *
     * @param rawNickname 原始用户昵称输入
     * @return 规范化昵称
     */
    public String requireValidNickname(String rawNickname) {
        String nickname = SystemContextSupport.trimToNull(rawNickname);
        if (nickname == null || nickname.length() < MIN_NAME_LENGTH || nickname.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "用户昵称长度须在 2~30 个非空白字符之间");
        }
        return nickname;
    }

    /**
     * 校验并规范化用户管理 CRUD 请求中的手机、邮箱与性别字典值（动态校验 {@code sys_user_gender}）。
     *
     * @param rawPhone       原始手机号码
     * @param rawEmail       原始电子邮箱
     * @param rawGender      原始性别字典编码
     * @param fallbackGender 性别为空白时的回退默认值（空则回退字典默认项）
     * @return 规范化联系字段与性别切片
     */
    public NormalizedContactAndGender normalizeAndValidateProfileFields(
            String rawPhone,
            String rawEmail,
            String rawGender,
            String fallbackGender
    ) {
        String phone = ContactFieldCrypto.normalizePhone(rawPhone);
        if (phone != null && !isValidMobilePhone(phone)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "手机号码格式不合法");
        }
        String email = ContactFieldCrypto.normalizeEmail(rawEmail);
        if (email != null && !isValidEmail(email)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "电子邮箱格式不合法或长度超过 64 位");
        }
        GenderDictionary dict = loadGenderDictionary();
        String trimmedGender = SystemContextSupport.trimToNull(rawGender);
        String resolvedGender = trimmedGender != null
                ? trimmedGender
                : (StringUtils.hasText(fallbackGender) ? fallbackGender.trim() : dict.defaultCode());
        if (!dict.validCodes().contains(resolvedGender)) {
            throw new BusinessException(
                    CommonBizResultCode.PARAM_INVALID,
                    "用户性别编码不合法，可选值：" + String.join(" / ", dict.validCodes())
            );
        }
        return new NormalizedContactAndGender(phone, email, resolvedGender);
    }

    /**
     * 加载激活态 {@code sys_user_gender} 动态字典双向映射视图（同时服务于导入校验与流式导出翻译）。
     *
     * @return 性别字典映射视图
     */
    public GenderDictionary loadGenderDictionary() {
        List<DictDataVO> items = sysDictQueryService.listActiveDataByType(GENDER_DICT_TYPE);
        if (CollectionUtils.isEmpty(items)) {
            return new GenderDictionary(
                    Set.of("0", "1", "2"),
                    Map.of("男", "0", "女", "1", "未知", "2"),
                    Map.of("0", "男", "1", "女", "2", "未知"),
                    DEFAULT_GENDER_CODE
            );
        }
        Set<String> validCodes = new LinkedHashSet<>(items.size());
        Map<String, String> labelToCode = new LinkedHashMap<>(items.size());
        Map<String, String> codeToLabel = new LinkedHashMap<>(items.size());
        String defaultCode = null;
        for (DictDataVO item : items) {
            if (item == null || !StringUtils.hasText(item.getDictValue()) || !StringUtils.hasText(item.getDictLabel())) {
                continue;
            }
            String value = item.getDictValue().trim();
            String label = item.getDictLabel().trim();
            validCodes.add(value);
            labelToCode.putIfAbsent(label, value);
            codeToLabel.putIfAbsent(value, label);
            if (Objects.equals(item.getIsDefault(), 1) && defaultCode == null) {
                defaultCode = value;
            }
        }
        if (defaultCode == null) {
            defaultCode = labelToCode.getOrDefault("未知", DEFAULT_GENDER_CODE);
            validCodes.add(defaultCode);
        }
        return new GenderDictionary(
                Set.copyOf(validCodes),
                Map.copyOf(labelToCode),
                Map.copyOf(codeToLabel),
                defaultCode
        );
    }

    /**
     * 解析有效明文口令：非空时保留原始字符不做静默裁切，空白或未提供时读取 {@code sys.user.initPassword}，统一执行强口令校验。
     *
     * @param tenantId         目标工作租户主键
     * @param providedPassword 显式传入的候选密码（可空）
     * @return 通过强口令复杂度策略校验的明文密码
     */
    public String resolveAndValidatePassword(Long tenantId, String providedPassword) {
        String candidate;
        if (providedPassword != null && !providedPassword.isEmpty()) {
            candidate = providedPassword;
        } else {
            candidate = sysConfigService.getConfigValue(tenantId, SolidConstants.INIT_PASSWORD_CONFIG_KEY);
            if (candidate == null || candidate.isEmpty()) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "系统初始密码未配置且未显式提交密码");
            }
        }
        PasswordPolicyValidator.validate(candidate);
        return candidate;
    }

    /**
     * 校验已归一化手机号码是否符合中国大陆 11 位手机号格式。
     *
     * @param normalizedPhone 已去除首尾空白的手机号
     * @return 格式合法返回 {@code true}
     */
    public static boolean isValidMobilePhone(String normalizedPhone) {
        return normalizedPhone != null && MOBILE_PHONE_PATTERN.matcher(normalizedPhone).matches();
    }

    /**
     * 校验已归一化电子邮箱是否符合标准格式且长度不超过 64 位。
     *
     * @param normalizedEmail 已去除首尾空白并转小写的邮箱
     * @return 格式合法返回 {@code true}
     */
    public static boolean isValidEmail(String normalizedEmail) {
        return normalizedEmail != null
                && normalizedEmail.length() <= MAX_EMAIL_LENGTH
                && EMAIL_PATTERN.matcher(normalizedEmail).matches();
    }

    /**
     * 规范化并校验后的用户联系方式与性别切片。
     *
     * @param phone  归一化手机号（可为 {@code null}）
     * @param email  小写归一化邮箱（可为 {@code null}）
     * @param gender 合法性别字典编码
     */
    public record NormalizedContactAndGender(String phone, String email, String gender) {
    }

    /**
     * 动态字典 {@code sys_user_gender} 双向映射快照。
     *
     * @param validCodes  全部有效字典值集合
     * @param labelToCode 展示标签到字典值映射
     * @param codeToLabel 字典值到展示标签映射
     * @param defaultCode 默认性别编码
     */
    public record GenderDictionary(
            Set<String> validCodes,
            Map<String, String> labelToCode,
            Map<String, String> codeToLabel,
            String defaultCode
    ) {
    }
}
