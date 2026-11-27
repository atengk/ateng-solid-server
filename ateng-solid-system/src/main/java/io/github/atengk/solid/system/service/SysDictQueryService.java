package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.entity.SysDictType;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysDictTypeMapper;
import io.github.atengk.solid.system.request.DictDataQueryRequest;
import io.github.atengk.solid.system.request.DictTypeQueryRequest;
import io.github.atengk.solid.system.support.DictDataCache;
import io.github.atengk.solid.system.support.DictValidationSupport;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.DictDataVO;
import io.github.atengk.solid.system.vo.DictTypeOptionVO;
import io.github.atengk.solid.system.vo.DictTypeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;

/**
 * 动态数据字典查询与缓存消费服务：管理端类型/明细分页与详情严格锁定平台租户 {@code 0}，
 * {@link #listActiveDataByType(String)} 面向全租户登录态提供带穿透防护的只读字典选项缓存消费。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysDictQueryService {

    private final SystemContextSupport contextSupport;
    private final SysDictTypeMapper sysDictTypeMapper;
    private final SysDictDataMapper sysDictDataMapper;
    private final DictDataCache dictDataCache;

    /**
     * 分页查询动态字典类型列表（仅限平台默认租户 0 维护检索）。
     *
     * @param query 分页与筛选条件
     * @return 字典类型分页结果
     */
    public PageResult<DictTypeVO> pageDictTypes(DictTypeQueryRequest query) {
        contextSupport.requirePlatformTenantId();
        DictTypeQueryRequest safeQuery = query != null ? query : new DictTypeQueryRequest();
        Page<SysDictType> pageRequest = PageUtil.toPage(
                safeQuery, SysDictType.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        String dictName = SystemContextSupport.trimToNull(safeQuery.getDictName());
        String dictType = SystemContextSupport.trimToNull(safeQuery.getDictType());
        LambdaQueryWrapper<SysDictType> wrapper = Wrappers.<SysDictType>lambdaQuery()
                .like(dictName != null, SysDictType::getDictName, dictName)
                .like(dictType != null, SysDictType::getDictType, dictType)
                .eq(safeQuery.getStatus() != null, SysDictType::getStatus, safeQuery.getStatus());
        if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
            wrapper.orderByAsc(SysDictType::getId);
        }

        Page<SysDictType> result = TenantContextHolder.runWithoutTenant(
                () -> sysDictTypeMapper.selectPage(pageRequest, wrapper)
        );
        return SystemContextSupport.toPageResult(result, SysDictQueryService::toDictTypeVO);
    }

    /**
     * 获取正常启用状态的动态字典类型下拉选项（仅限平台默认租户 0）。
     *
     * @return 启用中的字典类型选项列表
     */
    public List<DictTypeOptionVO> listActiveTypeOptions() {
        contextSupport.requirePlatformTenantId();
        List<SysDictType> types = TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.selectList(
                Wrappers.<SysDictType>lambdaQuery()
                        .eq(SysDictType::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .orderByAsc(SysDictType::getId)
        ));
        if (CollectionUtils.isEmpty(types)) {
            return Collections.emptyList();
        }
        return types.stream()
                .map(type -> DictTypeOptionVO.builder()
                        .id(type.getId())
                        .dictName(type.getDictName())
                        .dictType(type.getDictType())
                        .status(type.getStatus())
                        .build())
                .toList();
    }

    /**
     * 查询指定字典类型详情（仅限平台默认租户 0）。
     *
     * @param id 字典类型主键
     * @return 字典类型详情
     */
    public DictTypeVO getDictTypeDetail(Long id) {
        contextSupport.requirePlatformTenantId();
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典类型不存在");
        }
        SysDictType type = TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.selectById(id));
        if (type == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典类型不存在");
        }
        return toDictTypeVO(type);
    }

    /**
     * 分页查询动态字典数据明细（仅限平台默认租户 0）。
     *
     * @param query 分页与筛选条件
     * @return 字典数据分页结果
     */
    public PageResult<DictDataVO> pageDictData(DictDataQueryRequest query) {
        contextSupport.requirePlatformTenantId();
        DictDataQueryRequest safeQuery = query != null ? query : new DictDataQueryRequest();
        Page<SysDictData> pageRequest = PageUtil.toPage(
                safeQuery, SysDictData.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        String dictType = SystemContextSupport.trimToNull(safeQuery.getDictType());
        String dictLabel = SystemContextSupport.trimToNull(safeQuery.getDictLabel());
        LambdaQueryWrapper<SysDictData> wrapper = Wrappers.<SysDictData>lambdaQuery()
                .eq(dictType != null, SysDictData::getDictType, dictType)
                .like(dictLabel != null, SysDictData::getDictLabel, dictLabel)
                .eq(safeQuery.getStatus() != null, SysDictData::getStatus, safeQuery.getStatus());
        if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
            wrapper.orderByAsc(SysDictData::getSort).orderByAsc(SysDictData::getId);
        }

        Page<SysDictData> result = TenantContextHolder.runWithoutTenant(
                () -> sysDictDataMapper.selectPage(pageRequest, wrapper)
        );
        return SystemContextSupport.toPageResult(result, SysDictQueryService::toDictDataVO);
    }

    /**
     * 查询单条动态字典数据明细详情（仅限平台默认租户 0）。
     *
     * @param id 字典数据主键
     * @return 字典数据详情
     */
    public DictDataVO getDictDataDetail(Long id) {
        contextSupport.requirePlatformTenantId();
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典数据不存在");
        }
        SysDictData data = TenantContextHolder.runWithoutTenant(() -> sysDictDataMapper.selectById(id));
        if (data == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "字典数据不存在");
        }
        return toDictDataVO(data);
    }

    /**
     * 按字典类型编码只读拉取启用中的动态字典明细（全租户登录态开放，消费 {@link DictDataCache} 30m 缓存；
     * 若字典类型不存在或已停用则返回并缓存空集合 {@code []}）。
     *
     * @param dictType 字典类型编码
     * @return 启用中的字典明细列表
     */
    public List<DictDataVO> listActiveDataByType(String dictType) {
        String normalizedType = SystemContextSupport.trimToNull(dictType);
        if (!DictValidationSupport.isValidDictTypeFormat(normalizedType)) {
            return Collections.emptyList();
        }
        return dictDataCache.getOrLoad(normalizedType, this::loadActiveDataFromDb);
    }

    private List<DictDataVO> loadActiveDataFromDb(String dictType) {
        return TenantContextHolder.runWithoutTenant(() -> {
            SysDictType type = sysDictTypeMapper.selectOne(
                    Wrappers.<SysDictType>lambdaQuery()
                            .eq(SysDictType::getDictType, dictType)
                            .eq(SysDictType::getStatus, CommonStatusEnum.NORMAL.getCode())
            );
            if (type == null) {
                return Collections.emptyList();
            }
            List<SysDictData> dataList = sysDictDataMapper.selectList(
                    Wrappers.<SysDictData>lambdaQuery()
                            .eq(SysDictData::getDictType, dictType)
                            .eq(SysDictData::getStatus, CommonStatusEnum.NORMAL.getCode())
                            .orderByAsc(SysDictData::getSort)
                            .orderByAsc(SysDictData::getId)
            );
            if (CollectionUtils.isEmpty(dataList)) {
                return Collections.emptyList();
            }
            return dataList.stream().map(SysDictQueryService::toDictDataVO).toList();
        });
    }

    private static DictTypeVO toDictTypeVO(SysDictType entity) {
        return DictTypeVO.builder()
                .id(entity.getId())
                .dictName(entity.getDictName())
                .dictType(entity.getDictType())
                .status(entity.getStatus())
                .remark(entity.getRemark())
                .createTime(entity.getCreateTime())
                .build();
    }

    private static DictDataVO toDictDataVO(SysDictData entity) {
        return DictDataVO.builder()
                .id(entity.getId())
                .dictType(entity.getDictType())
                .dictLabel(entity.getDictLabel())
                .dictValue(entity.getDictValue())
                .tagType(entity.getTagType() != null ? entity.getTagType() : "")
                .cssClass(entity.getCssClass())
                .sort(entity.getSort())
                .isDefault(entity.getIsDefault())
                .status(entity.getStatus())
                .remark(entity.getRemark())
                .createTime(entity.getCreateTime())
                .build();
    }
}
