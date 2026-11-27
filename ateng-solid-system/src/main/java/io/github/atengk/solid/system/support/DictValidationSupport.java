package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.request.DictDataSaveRequest;
import io.github.atengk.solid.system.request.DictTypeSaveRequest;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 动态数据字典入参规范化与领域边界校验协作者，收口字典类型编码正则、标签语义样式白名单及默认值归一化规则。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class DictValidationSupport {

    private static final Pattern DICT_TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Set<String> ALLOWED_TAG_TYPES = Set.of(
            "", "primary", "success", "warning", "danger", "info"
    );

    private DictValidationSupport() {
    }

    /**
     * 校验并规范化字典类型保存请求参数。
     *
     * @param request 字典类型保存请求
     * @param isCreate 是否为新增场景（新增时强校验 {@code dictType}）
     * @return 规范化后的字典类型载荷
     */
    public static NormalizedDictType normalizeTypePayload(DictTypeSaveRequest request, boolean isCreate) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典类型参数不能为空");
        }
        String dictName = SystemContextSupport.trimToNull(request.getDictName());
        if (dictName == null || dictName.length() < 2 || dictName.length() > 50) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典名称长度须在 2~50 个字符之间");
        }
        String dictType = isCreate ? requireValidDictType(request.getDictType())
                : SystemContextSupport.trimToNull(request.getDictType());
        Integer status = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : null;
        String remark = trimAndCheckRemark(request.getRemark());
        return new NormalizedDictType(dictName, dictType, status, remark);
    }

    /**
     * 校验字典类型编码是否符合标准格式（以小写字母开头，仅含小写字母、数字和下划线，2~64 位）。
     *
     * @param dictType 候选字典类型编码
     * @return 符合规范返回 {@code true}
     */
    public static boolean isValidDictTypeFormat(String dictType) {
        return dictType != null && DICT_TYPE_PATTERN.matcher(dictType).matches();
    }

    /**
     * 校验并规范化字典类型编码。
     *
     * @param raw 原始类型编码
     * @return 规范化后的编码
     */
    public static String requireValidDictType(String raw) {
        String dictType = SystemContextSupport.trimToNull(raw);
        if (!isValidDictTypeFormat(dictType)) {
            throw new BusinessException(
                    CommonBizResultCode.PARAM_INVALID,
                    "字典类型编码须以小写字母开头，仅含小写字母、数字和下划线（2~64 位）"
            );
        }
        return dictType;
    }

    /**
     * 校验并规范化字典数据明细保存请求参数。
     *
     * @param request  字典数据保存请求
     * @param existing 修改场景下的历史实体（新增传 {@code null}）
     * @return 规范化后的字典明细载荷
     */
    public static NormalizedDictData normalizeDataPayload(DictDataSaveRequest request, SysDictData existing) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典数据参数不能为空");
        }
        String dictLabel = SystemContextSupport.trimToNull(request.getDictLabel());
        String dictValue = SystemContextSupport.trimToNull(request.getDictValue());
        if (dictLabel == null || dictLabel.length() > 64) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典标签长度须在 1~64 个字符之间");
        }
        if (dictValue == null || dictValue.length() > 64) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典键值长度须在 1~64 个字符之间");
        }
        String rawTagType = request.getTagType() != null
                ? request.getTagType().trim()
                : (existing != null && existing.getTagType() != null ? existing.getTagType() : "");
        if (!ALLOWED_TAG_TYPES.contains(rawTagType)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "标签回显样式仅支持空串或 primary/success/warning/danger/info");
        }
        String cssClass = SystemContextSupport.trimToNull(request.getCssClass());
        if (cssClass != null && cssClass.length() > 100) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "自定义样式类名长度不能超过 100 个字符");
        }
        int sort = request.getSort() != null
                ? request.getSort()
                : (existing != null && existing.getSort() != null ? existing.getSort() : 0);
        if (sort < 0 || sort > 9999) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "显示排序号须在 0~9999 之间");
        }
        Integer isDefault = request.getIsDefault() != null
                ? request.getIsDefault()
                : (existing != null && existing.getIsDefault() != null ? existing.getIsDefault() : 0);
        if (!Integer.valueOf(0).equals(isDefault) && !Integer.valueOf(1).equals(isDefault)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "是否默认仅支持 0（否）或 1（是）");
        }
        Integer status = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : (existing != null ? existing.getStatus() : SystemContextSupport.normalizeStatus(null));
        return new NormalizedDictData(
                dictLabel, dictValue, rawTagType, cssClass, sort, isDefault, status, trimAndCheckRemark(request.getRemark())
        );
    }

    private static String trimAndCheckRemark(String raw) {
        String remark = SystemContextSupport.trimToNull(raw);
        if (remark != null && remark.length() > 500) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "备注长度不能超过 500 个字符");
        }
        return remark;
    }

    /**
     * 规范化后的字典类型载荷。
     */
    public record NormalizedDictType(String dictName, String dictType, Integer status, String remark) {
    }

    /**
     * 规范化后的字典明细载荷。
     */
    public record NormalizedDictData(
            String dictLabel,
            String dictValue,
            String tagType,
            String cssClass,
            int sort,
            Integer isDefault,
            Integer status,
            String remark
    ) {
    }
}
