package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpLogic;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.system.request.ConfigQueryRequest;
import io.github.atengk.solid.system.request.ConfigSaveRequest;
import io.github.atengk.solid.system.service.SysConfigService;
import io.github.atengk.solid.system.vo.ConfigVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统参数管理控制器，提供租户级系统参数分页查询、键值读取及受内置保护的增删改接口
 * （路由前缀：{@code /admin/system/config}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/config")
@RequiredArgsConstructor
public class SysConfigController {

    private static final String PUBLIC_CONFIG_PREFIX = "public.";

    private final SysConfigService sysConfigService;

    /**
     * 分页查询当前工作租户下的系统参数。
     *
     * @param query 分页与筛选条件
     * @return 系统参数分页结果
     */
    @SaCheckPermission("system:config:list")
    @GetMapping("/page")
    public Result<PageResult<ConfigVO>> page(@Valid ConfigQueryRequest query) {
        return Result.success(sysConfigService.pageConfigs(query));
    }

    /**
     * 查询单条系统参数详情。
     *
     * @param id 参数主键
     * @return 参数详情
     */
    @SaCheckPermission("system:config:list")
    @GetMapping("/{id}")
    public Result<ConfigVO> detail(@PathVariable Long id) {
        return Result.success(sysConfigService.getDetail(id));
    }

    /**
     * 按参数键名分级鉴权后读取参数值（普通租户未覆盖时自动回退读取平台默认租户 {@code 0} 全局值）。
     *
     * @param configKey 参数键名
     * @return 参数键值字符串（双方均未配置时返回 {@code null}）
     */
    @SaCheckLogin
    @GetMapping("/key/{configKey}")
    public Result<String> getByKey(@PathVariable String configKey) {
        assertCanReadConfigKey(configKey);
        return Result.success(sysConfigService.getConfigValue(configKey));
    }

    /**
     * 新增系统参数。
     *
     * @param request 参数保存请求
     * @return 新建参数主键
     */
    @SaCheckPermission("system:config:create")
    @Idempotent
    @OperateLog(module = "系统参数", type = OperateTypeEnum.INSERT, description = "新增系统参数", excludeParams = {"configValue"})
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ConfigSaveRequest request) {
        return Result.success(sysConfigService.createConfig(request));
    }

    /**
     * 修改系统参数。
     *
     * @param request 参数修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:config:update")
    @OperateLog(module = "系统参数", type = OperateTypeEnum.UPDATE, description = "修改系统参数", excludeParams = {"configValue"})
    @PutMapping
    public Result<Void> update(@Valid @RequestBody ConfigSaveRequest request) {
        sysConfigService.updateConfig(request);
        return Result.success();
    }

    /**
     * 批量删除系统参数（受 {@code configType=Y} 内置防删保护拦截）。
     *
     * @param ids 逗号分隔的参数主键串
     * @return 空成功响应
     */
    @SaCheckPermission("system:config:delete")
    @OperateLog(module = "系统参数", type = OperateTypeEnum.DELETE, description = "删除系统参数")
    @DeleteMapping("/{ids}")
    public Result<Void> delete(@PathVariable String ids) {
        sysConfigService.deleteConfigs(ids);
        return Result.success();
    }

    private static void assertCanReadConfigKey(String configKey) {
        String normalizedKey = StringUtils.hasText(configKey) ? configKey.trim() : "";
        if (normalizedKey.startsWith(PUBLIC_CONFIG_PREFIX)) {
            return;
        }
        StpLogic stpLogic = LoginHelper.getStpLogic(SolidConstants.ADMIN_LOGIN_TYPE);
        if (SolidConstants.INIT_PASSWORD_CONFIG_KEY.equals(normalizedKey)) {
            stpLogic.checkPermissionOr(
                    "system:config:list",
                    "system:user:create",
                    "system:user:update",
                    "system:tenant:create"
            );
            return;
        }
        stpLogic.checkPermission("system:config:list");
    }
}
