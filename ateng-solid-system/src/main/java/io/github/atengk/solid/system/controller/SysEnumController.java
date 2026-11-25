package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.dict.EnumDictionaryRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端静态枚举自主接管控制器，消费底座 {@link EnumDictionaryRegistry} 对外暴露受登录态保护的只读选项查询接口
 * （路由前缀：{@code /admin/system/enums}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SaCheckLogin
@RestController
@RequestMapping("/admin/system/enums")
@RequiredArgsConstructor
public class SysEnumController {

    private final EnumDictionaryRegistry enumDictionaryRegistry;

    /**
     * 获取已注册的全部静态枚举目录与选项列表。
     *
     * @return 枚举别名到选项列表的不可变映射
     */
    @GetMapping
    public Result<Map<String, List<OptionItem<Object>>>> getAll() {
        return Result.success(enumDictionaryRegistry.getAllOptions());
    }

    /**
     * 通过 Query 参数批量查询多个静态枚举选项映射。
     *
     * @param names 枚举别名列表（可为空）
     * @return 命中的枚举别名到选项列表映射
     */
    @GetMapping("/batch")
    public Result<Map<String, List<OptionItem<Object>>>> getBatchGet(
            @RequestParam(value = "names", required = false) List<String> names
    ) {
        return Result.success(enumDictionaryRegistry.getBatchOptions(names));
    }

    /**
     * 按静态枚举别名查询单个枚举的选项列表。
     *
     * @param name 枚举别名（如 {@code commonStatus}、{@code dataScopeType}）
     * @return 匹配的选项列表
     * @throws BusinessException 枚举别名未注册时抛出 {@code 404}
     */
    @GetMapping("/{name}")
    public Result<List<OptionItem<Object>>> getByName(@PathVariable("name") String name) {
        List<OptionItem<Object>> options = enumDictionaryRegistry.getOptions(name);
        if (options == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, String.format("未找到名称为 [%s] 的枚举字典", name));
        }
        return Result.success(options);
    }

    /**
     * 通过请求体批量查询多个静态枚举选项映射。
     *
     * @param names 枚举别名列表（可为空）
     * @return 命中的枚举别名到选项列表映射
     */
    @PostMapping("/batch")
    public Result<Map<String, List<OptionItem<Object>>>> getBatchPost(
            @RequestBody(required = false) List<String> names
    ) {
        return Result.success(enumDictionaryRegistry.getBatchOptions(names));
    }
}
