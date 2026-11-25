package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.system.request.DictDataQueryRequest;
import io.github.atengk.solid.system.request.DictDataSaveRequest;
import io.github.atengk.solid.system.request.DictTypeQueryRequest;
import io.github.atengk.solid.system.request.DictTypeSaveRequest;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.service.SysDictQueryService;
import io.github.atengk.solid.system.service.SysDictService;
import io.github.atengk.solid.system.vo.DictDataVO;
import io.github.atengk.solid.system.vo.DictTypeOptionVO;
import io.github.atengk.solid.system.vo.DictTypeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态数据字典控制器（路由前缀：{@code /admin/system/dict}），提供字典类型与字典数据管理端点（平台租户 {@code 0} 专属），
 * 以及面向全租户登录用户的只读字典数据消费端点。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/dict")
@RequiredArgsConstructor
public class SysDictController {

    private final SysDictQueryService sysDictQueryService;
    private final SysDictService sysDictService;

    /**
     * 分页查询动态字典类型列表。
     *
     * @param query 分页与筛选条件
     * @return 字典类型分页结果
     */
    @SaCheckPermission("system:dict:list")
    @GetMapping("/type/page")
    public Result<PageResult<DictTypeVO>> pageTypes(@Valid DictTypeQueryRequest query) {
        return Result.success(sysDictQueryService.pageDictTypes(query));
    }

    /**
     * 获取正常启用状态的字典类型下拉选项。
     *
     * @return 字典类型选项列表
     */
    @SaCheckPermission("system:dict:list")
    @GetMapping("/type/options")
    public Result<List<DictTypeOptionVO>> typeOptions() {
        return Result.success(sysDictQueryService.listActiveTypeOptions());
    }

    /**
     * 查询指定字典类型详情。
     *
     * @param id 字典类型主键
     * @return 字典类型详情
     */
    @SaCheckPermission("system:dict:list")
    @GetMapping("/type/{id}")
    public Result<DictTypeVO> typeDetail(@PathVariable Long id) {
        return Result.success(sysDictQueryService.getDictTypeDetail(id));
    }

    /**
     * 新增字典类型。
     *
     * @param request 字典类型保存请求
     * @return 新建字典类型主键
     */
    @SaCheckPermission("system:dict:create")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.INSERT, description = "新增字典类型")
    @PostMapping("/type")
    public Result<Long> createType(@Valid @RequestBody DictTypeSaveRequest request) {
        return Result.success(sysDictService.createDictType(request));
    }

    /**
     * 修改字典类型（锁定 {@code dictType} 不可改）。
     *
     * @param request 字典类型修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:dict:update")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.UPDATE, description = "修改字典类型")
    @PutMapping("/type")
    public Result<Void> updateType(@Valid @RequestBody DictTypeSaveRequest request) {
        sysDictService.updateDictType(request);
        return Result.success();
    }

    /**
     * 切换字典类型启停状态。
     *
     * @param id      字典类型主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:dict:update")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.UPDATE, description = "切换字典类型状态")
    @PutMapping("/type/{id}/status")
    public Result<Void> updateTypeStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysDictService.updateDictTypeStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 批量删除字典类型。
     *
     * @param ids 逗号分隔的字典类型主键串
     * @return 空成功响应
     */
    @SaCheckPermission("system:dict:delete")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.DELETE, description = "删除字典类型")
    @DeleteMapping("/type/{ids}")
    public Result<Void> deleteTypes(@PathVariable String ids) {
        sysDictService.deleteDictTypes(ids);
        return Result.success();
    }

    /**
     * 分页查询动态字典数据明细。
     *
     * @param query 分页与筛选条件
     * @return 字典数据分页结果
     */
    @SaCheckPermission("system:dict:list")
    @GetMapping("/data/page")
    public Result<PageResult<DictDataVO>> pageData(@Valid DictDataQueryRequest query) {
        return Result.success(sysDictQueryService.pageDictData(query));
    }

    /**
     * 查询单条动态字典数据明细详情。
     *
     * @param id 字典数据主键
     * @return 字典数据详情
     */
    @SaCheckPermission("system:dict:list")
    @GetMapping("/data/{id}")
    public Result<DictDataVO> dataDetail(@PathVariable Long id) {
        return Result.success(sysDictQueryService.getDictDataDetail(id));
    }

    /**
     * 按字典类型编码只读拉取启用中的动态字典选项列表（面向全租户登录态开放消费）。
     *
     * @param dictType 字典类型编码
     * @return 启用中的字典明细列表
     */
    @SaCheckLogin
    @GetMapping("/data/type/{dictType}")
    public Result<List<DictDataVO>> listDataByType(@PathVariable String dictType) {
        return Result.success(sysDictQueryService.listActiveDataByType(dictType));
    }

    /**
     * 新增动态字典数据明细。
     *
     * @param request 字典数据保存请求
     * @return 新建字典数据主键
     */
    @SaCheckPermission("system:dict:create")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.INSERT, description = "新增字典数据")
    @PostMapping("/data")
    public Result<Long> createData(@Valid @RequestBody DictDataSaveRequest request) {
        return Result.success(sysDictService.createDictData(request));
    }

    /**
     * 修改动态字典数据明细。
     *
     * @param request 字典数据修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:dict:update")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.UPDATE, description = "修改字典数据")
    @PutMapping("/data")
    public Result<Void> updateData(@Valid @RequestBody DictDataSaveRequest request) {
        sysDictService.updateDictData(request);
        return Result.success();
    }

    /**
     * 切换动态字典数据启停状态。
     *
     * @param id      字典数据主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:dict:update")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.UPDATE, description = "切换字典数据状态")
    @PutMapping("/data/{id}/status")
    public Result<Void> updateDataStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysDictService.updateDictDataStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 批量删除动态字典数据明细。
     *
     * @param ids 逗号分隔的字典数据主键串
     * @return 空成功响应
     */
    @SaCheckPermission("system:dict:delete")
    @OperateLog(module = "数据字典", type = OperateTypeEnum.DELETE, description = "删除字典数据")
    @DeleteMapping("/data/{ids}")
    public Result<Void> deleteData(@PathVariable String ids) {
        sysDictService.deleteDictData(ids);
        return Result.success();
    }
}
