package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.entity.SysDictType;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysDictDataMapper.DictTypeDataCount;
import io.github.atengk.solid.system.mapper.SysDictTypeMapper;
import io.github.atengk.solid.system.request.DictDataSaveRequest;
import io.github.atengk.solid.system.request.DictTypeSaveRequest;
import io.github.atengk.solid.system.support.DictDataCache;
import io.github.atengk.solid.system.support.DictValidationSupport;
import io.github.atengk.solid.system.support.DictValidationSupport.NormalizedDictData;
import io.github.atengk.solid.system.support.DictValidationSupport.NormalizedDictType;
import io.github.atengk.solid.system.support.SystemContextSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态数据字典写操作服务：严格锁定平台默认租户 {@code 0} 维护，负责字典类型编辑锁、含明细删除保护（{@code 102005}）、
 * 明细 {@code isDefault} 事务内同类型互斥唯一以及提交后精确缓存驱逐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysDictService {

    private final SystemContextSupport contextSupport;
    private final SysDictTypeMapper sysDictTypeMapper;
    private final SysDictDataMapper sysDictDataMapper;
    private final DictDataCache dictDataCache;

    /**
     * 新增字典类型并校验全局未删除编码唯一性。
     *
     * @param request 字典类型保存请求
     * @return 新建字典类型主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createDictType(DictTypeSaveRequest request) {
        contextSupport.requirePlatformTenantId();
        NormalizedDictType payload = DictValidationSupport.normalizeTypePayload(request, true);
        Integer effectiveStatus = SystemContextSupport.normalizeStatus(payload.status());

        Long createdId = TenantContextHolder.runWithoutTenant(() -> {
            assertDictTypeUnique(payload.dictType(), null);
            SysDictType entity = new SysDictType();
            entity.setDictName(payload.dictName());
            entity.setDictType(payload.dictType());
            entity.setStatus(effectiveStatus);
            entity.setRemark(payload.remark());
            try {
                sysDictTypeMapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "字典类型编码已存在", ex);
            }
            return entity.getId();
        });
        TransactionUtil.executeAfterCommit(() -> dictDataCache.evict(payload.dictType()));
        return createdId;
    }

    /**
     * 修改字典类型基本信息（严格锁定 {@code dictType} 只读不可篡改）。
     *
     * @param request 字典类型修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDictType(DictTypeSaveRequest request) {
        contextSupport.requirePlatformTenantId();
        if (request == null || request.getId() == null || request.getId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改字典类型时主键不能为空");
        }
        SysDictType existing = requireDictTypeExists(request.getId());
        NormalizedDictType payload = DictValidationSupport.normalizeTypePayload(request, false);
        if (payload.dictType() != null && !existing.getDictType().equals(payload.dictType())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典类型编码创建后锁定只读，禁止修改");
        }

        Integer effectiveStatus = payload.status() != null ? payload.status() : existing.getStatus();
        LambdaUpdateWrapper<SysDictType> wrapper = Wrappers.<SysDictType>lambdaUpdate()
                .eq(SysDictType::getId, existing.getId())
                .set(SysDictType::getDictName, payload.dictName())
                .set(SysDictType::getStatus, effectiveStatus)
                .set(SysDictType::getRemark, payload.remark());
        TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.update(new SysDictType(), wrapper));
        TransactionUtil.executeAfterCommit(() -> dictDataCache.evict(existing.getDictType()));
    }

    /**
     * 切换字典类型启停状态，并在提交后清理对应字典缓存。
     *
     * @param id     字典类型主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDictTypeStatus(Long id, Integer status) {
        contextSupport.requirePlatformTenantId();
        Integer targetStatus = SystemContextSupport.requireValidStatus(status);
        SysDictType existing = requireDictTypeExists(id);

        LambdaUpdateWrapper<SysDictType> wrapper = Wrappers.<SysDictType>lambdaUpdate()
                .eq(SysDictType::getId, existing.getId())
                .set(SysDictType::getStatus, targetStatus);
        TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.update(new SysDictType(), wrapper));
        TransactionUtil.executeAfterCommit(() -> dictDataCache.evict(existing.getDictType()));
    }

    /**
     * 批量删除字典类型；若任意目标类型下存在未删除字典明细则整批阻断抛出 {@code 102005}。
     *
     * @param ids 逗号分隔的字典类型主键串
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictTypes(String ids) {
        contextSupport.requirePlatformTenantId();
        List<Long> idList = SystemContextSupport.parsePathIds(ids);

        List<SysDictType> existingTypes = TenantContextHolder.runWithoutTenant(
                () -> sysDictTypeMapper.selectByIds(idList)
        );
        if (existingTypes == null || existingTypes.size() != idList.size()) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分或全部待删除字典类型不存在");
        }

        List<String> dictTypeCodes = existingTypes.stream().map(SysDictType::getDictType).toList();
        List<DictTypeDataCount> counts = TenantContextHolder.runWithoutTenant(
                () -> sysDictDataMapper.countActiveByDictTypes(dictTypeCodes)
        );
        Map<String, Long> countMap = new HashMap<>(dictTypeCodes.size());
        if (!CollectionUtils.isEmpty(counts)) {
            for (DictTypeDataCount item : counts) {
                countMap.put(item.getDictType(), item.getTotal() != null ? item.getTotal() : 0L);
            }
        }
        for (SysDictType type : existingTypes) {
            long childCount = countMap.getOrDefault(type.getDictType(), 0L);
            if (childCount > 0L) {
                throw new BusinessException(
                        SystemResultCode.RESOURCE_IN_USE,
                        "字典类型【" + type.getDictName() + "】下尚有 " + childCount + " 条字典数据，请先清空后再删除"
                );
            }
        }

        TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.deleteByIds(idList));
        TransactionUtil.executeAfterCommit(() -> dictTypeCodes.forEach(dictDataCache::evict));
    }

    /**
     * 新增动态字典明细；当 {@code isDefault = 1} 时在同一事务内将该类型下其他明细置 {@code 0} 保持互斥唯一。
     *
     * @param request 字典数据保存请求
     * @return 新建字典数据主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createDictData(DictDataSaveRequest request) {
        contextSupport.requirePlatformTenantId();
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "字典数据参数不能为空");
        }
        String dictType = DictValidationSupport.requireValidDictType(request.getDictType());
        NormalizedDictData payload = DictValidationSupport.normalizeDataPayload(request, null);

        Long createdId = TenantContextHolder.runWithoutTenant(() -> {
            assertDictTypeCodeExists(dictType);
            assertDictValueUnique(dictType, payload.dictValue(), null);
            if (Integer.valueOf(1).equals(payload.isDefault())) {
                clearOtherDefaultsInTx(dictType, null);
            }

            SysDictData entity = new SysDictData();
            entity.setDictType(dictType);
            entity.setDictLabel(payload.dictLabel());
            entity.setDictValue(payload.dictValue());
            entity.setTagType(payload.tagType());
            entity.setCssClass(payload.cssClass());
            entity.setSort(payload.sort());
            entity.setIsDefault(payload.isDefault());
            entity.setStatus(payload.status());
            entity.setRemark(payload.remark());
            try {
                sysDictDataMapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同字典类型下该字典键值已存在", ex);
            }
            return entity.getId();
        });
        TransactionUtil.executeAfterCommit(() -> dictDataCache.evict(dictType));
        return createdId;
    }

    /**
     * 修改动态字典明细；保持所属 {@code dictType} 只读一致，并在 {@code isDefault = 1} 时事务内互斥唯一。
     *
     * @param request 字典数据修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDictData(DictDataSaveRequest request) {
        contextSupport.requirePlatformTenantId();
        if (request == null || request.getId() == null || request.getId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改字典数据时主键不能为空");
        }
        SysDictData existing = requireDictDataExists(request.getId());
        String requestedType = SystemContextSupport.trimToNull(request.getDictType());
        if (requestedType != null && !existing.getDictType().equals(requestedType)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "禁止篡改字典数据所属类型编码");
        }

        String dictType = existing.getDictType();
        NormalizedDictData payload = DictValidationSupport.normalizeDataPayload(request, existing);

        TenantContextHolder.runWithoutTenant(() -> {
            assertDictValueUnique(dictType, payload.dictValue(), existing.getId());
            if (Integer.valueOf(1).equals(payload.isDefault())) {
                clearOtherDefaultsInTx(dictType, existing.getId());
            }
            LambdaUpdateWrapper<SysDictData> wrapper = Wrappers.<SysDictData>lambdaUpdate()
                    .eq(SysDictData::getId, existing.getId())
                    .set(SysDictData::getDictLabel, payload.dictLabel())
                    .set(SysDictData::getDictValue, payload.dictValue())
                    .set(SysDictData::getTagType, payload.tagType())
                    .set(SysDictData::getCssClass, payload.cssClass())
                    .set(SysDictData::getSort, payload.sort())
                    .set(SysDictData::getIsDefault, payload.isDefault())
                    .set(SysDictData::getStatus, payload.status())
                    .set(SysDictData::getRemark, payload.remark());
            try {
                sysDictDataMapper.update(new SysDictData(), wrapper);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同字典类型下该字典键值已存在", ex);
            }
        });
        TransactionUtil.executeAfterCommit(() -> dictDataCache.evict(dictType));
    }

    /**
     * 切换字典数据启停状态，并在提交后清理所属类型缓存。
     *
     * @param id     字典数据主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDictDataStatus(Long id, Integer status) {
        contextSupport.requirePlatformTenantId();
        Integer targetStatus = SystemContextSupport.requireValidStatus(status);
        SysDictData existing = requireDictDataExists(id);

        LambdaUpdateWrapper<SysDictData> wrapper = Wrappers.<SysDictData>lambdaUpdate()
                .eq(SysDictData::getId, existing.getId())
                .set(SysDictData::getStatus, targetStatus);
        TenantContextHolder.runWithoutTenant(() -> sysDictDataMapper.update(new SysDictData(), wrapper));
        TransactionUtil.executeAfterCommit(() -> dictDataCache.evict(existing.getDictType()));
    }

    /**
     * 批量删除字典数据明细，并在提交后逐一清理关联字典类型缓存。
     *
     * @param ids 逗号分隔的字典数据主键串
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictData(String ids) {
        contextSupport.requirePlatformTenantId();
        List<Long> idList = SystemContextSupport.parsePathIds(ids);

        List<SysDictData> existingList = TenantContextHolder.runWithoutTenant(
                () -> sysDictDataMapper.selectByIds(idList)
        );
        if (existingList == null || existingList.size() != idList.size()) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分或全部待删除字典数据不存在");
        }

        Set<String> affectedTypes = new LinkedHashSet<>(existingList.size());
        for (SysDictData item : existingList) {
            affectedTypes.add(item.getDictType());
        }
        TenantContextHolder.runWithoutTenant(() -> sysDictDataMapper.deleteByIds(idList));
        TransactionUtil.executeAfterCommit(() -> affectedTypes.forEach(dictDataCache::evict));
    }

    private void clearOtherDefaultsInTx(String dictType, Long excludeId) {
        LambdaUpdateWrapper<SysDictData> clearWrapper = Wrappers.<SysDictData>lambdaUpdate()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getIsDefault, 1)
                .ne(excludeId != null, SysDictData::getId, excludeId)
                .set(SysDictData::getIsDefault, 0);
        sysDictDataMapper.update(new SysDictData(), clearWrapper);
    }

    private SysDictType requireDictTypeExists(Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典类型不存在");
        }
        SysDictType existing = TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.selectById(id));
        if (existing == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典类型不存在");
        }
        return existing;
    }

    private SysDictData requireDictDataExists(Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典数据不存在");
        }
        SysDictData existing = TenantContextHolder.runWithoutTenant(() -> sysDictDataMapper.selectById(id));
        if (existing == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典数据不存在");
        }
        return existing;
    }

    private void assertDictTypeCodeExists(String dictType) {
        Long count = sysDictTypeMapper.selectCount(
                Wrappers.<SysDictType>lambdaQuery().eq(SysDictType::getDictType, dictType)
        );
        if (count == null || count == 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "所属字典类型【" + dictType + "】不存在");
        }
    }

    private void assertDictTypeUnique(String dictType, Long excludeId) {
        Long count = sysDictTypeMapper.selectCount(
                Wrappers.<SysDictType>lambdaQuery()
                        .eq(SysDictType::getDictType, dictType)
                        .ne(excludeId != null, SysDictType::getId, excludeId)
        );
        if (count != null && count > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "字典类型编码【" + dictType + "】已存在");
        }
    }

    private void assertDictValueUnique(String dictType, String dictValue, Long excludeId) {
        Long count = sysDictDataMapper.selectCount(
                Wrappers.<SysDictData>lambdaQuery()
                        .eq(SysDictData::getDictType, dictType)
                        .eq(SysDictData::getDictValue, dictValue)
                        .ne(excludeId != null, SysDictData::getId, excludeId)
        );
        if (count != null && count > 0L) {
            throw new BusinessException(
                    SystemResultCode.DUPLICATE_RESOURCE,
                    "字典键值【" + dictValue + "】在类型【" + dictType + "】下已存在"
            );
        }
    }
}
