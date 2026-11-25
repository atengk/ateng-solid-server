package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.api.enums.VisibleStatusEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.request.MenuSaveRequest;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 菜单节点类型、M/C/F 拓扑防环与降级校验及字段标准化辅助工具。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class MenuValidationSupport {

    private static final Pattern PERMS_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-*]*$");
    private static final Pattern EXTERNAL_URL_PATTERN = Pattern.compile("^https?://.+");

    private MenuValidationSupport() {
    }

    /**
     * 解析并校验必填菜单节点类型（M/C/F）。
     *
     * @param menuType 类型编码
     * @return 解析后的节点类型枚举
     */
    public static MenuTypeEnum parseRequiredMenuType(String menuType) {
        MenuTypeEnum parsed = MenuTypeEnum.fromCode(SystemContextSupport.trimToNull(menuType));
        if (parsed == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "菜单类型仅支持 M（目录）、C（菜单）或 F（按钮）");
        }
        return parsed;
    }

    /**
     * 校验父节点与目标节点类型挂接拓扑是否合法。
     *
     * @param parent     父菜单实体（顶级根传 {@code null}）
     * @param targetType 目标节点类型
     */
    public static void assertParentTypeCompatible(SysMenu parent, MenuTypeEnum targetType) {
        if (targetType == MenuTypeEnum.BUTTON) {
            if (parent == null || MenuTypeEnum.fromCode(parent.getMenuType()) != MenuTypeEnum.MENU) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "按钮权限（F）仅允许挂载在页面菜单（C）节点下");
            }
            return;
        }
        if (parent != null && MenuTypeEnum.fromCode(parent.getMenuType()) != MenuTypeEnum.DIRECTORY) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "目录（M）或菜单（C）仅允许挂载在顶级根或目录（M）节点下");
        }
    }

    /**
     * 修改菜单节点时校验防自环迁移及存在子节点时的类型降级约束。
     *
     * @param existing       既有菜单节点
     * @param targetParentId 目标父节点主键
     * @param targetType     目标节点类型
     * @param allMenus       全量菜单列表
     */
    public static void assertUpdateTopologySafe(
            SysMenu existing,
            Long targetParentId,
            MenuTypeEnum targetType,
            List<SysMenu> allMenus
    ) {
        Set<Long> subtreeIds = MenuTreeSupport.collectSubtreeIds(existing.getId(), allMenus);
        if (subtreeIds.contains(targetParentId)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "不能将菜单迁移至自身或其下级子节点下");
        }
        List<SysMenu> directChildren = allMenus.stream()
                .filter(item -> Objects.equals(item.getParentId(), existing.getId()))
                .toList();
        if (directChildren.isEmpty()) {
            return;
        }
        if (targetType == MenuTypeEnum.BUTTON) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在下级子节点的节点禁止降级为按钮权限（F）");
        }
        if (targetType == MenuTypeEnum.MENU) {
            boolean hasDirOrMenuChild = directChildren.stream().anyMatch(child -> {
                MenuTypeEnum childType = MenuTypeEnum.fromCode(child.getMenuType());
                return childType == MenuTypeEnum.DIRECTORY || childType == MenuTypeEnum.MENU;
            });
            if (hasDirOrMenuChild) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在下级目录或菜单节点时禁止将当前目录降级为页面菜单（C）");
            }
        }
        if (targetType == MenuTypeEnum.DIRECTORY) {
            boolean hasButtonChild = directChildren.stream()
                    .anyMatch(child -> MenuTypeEnum.fromCode(child.getMenuType()) == MenuTypeEnum.BUTTON);
            if (hasButtonChild) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在下级按钮权限（F）的菜单节点禁止直接变更为目录（M）");
            }
        }
    }

    /**
     * 规范化并校验菜单保存字段边界（修改场景当可选开关为 {@code null} 时保留既有数据库值）。
     *
     * @param existing             既有菜单实体（新增传 {@code null}）
     * @param request              原始请求体
     * @param nodeType             目标节点类型
     * @param parent               父菜单节点（顶级根为 {@code null}）
     * @param duplicateNameChecker 路由组件名称判重回调（返回 {@code true} 表示冲突）
     * @return 校验规范化后的菜单载荷
     */
    public static NormalizedMenuPayload normalizeAndValidate(
            SysMenu existing,
            MenuSaveRequest request,
            MenuTypeEnum nodeType,
            SysMenu parent,
            Predicate<String> duplicateNameChecker
    ) {
        CommonMenuFields base = resolveCommonFields(existing, request, nodeType, parent);
        return switch (nodeType) {
            case BUTTON -> normalizeButton(request, base);
            case DIRECTORY -> normalizeDirectory(request, base, duplicateNameChecker);
            case MENU -> normalizeMenuPage(existing, request, base, duplicateNameChecker);
        };
    }

    /**
     * 将校验规范化后的载荷写入菜单持久层实体（新增分支使用）。
     *
     * @param entity  菜单实体
     * @param payload 规范化载荷
     */
    public static void applyPayload(SysMenu entity, NormalizedMenuPayload payload) {
        entity.setParentId(payload.parentId());
        entity.setMenuType(payload.menuType());
        entity.setTitle(payload.title());
        entity.setName(payload.name());
        entity.setPath(payload.path());
        entity.setComponent(payload.component());
        entity.setRedirect(payload.redirect());
        entity.setPerms(payload.perms());
        entity.setIcon(payload.icon());
        entity.setSort(payload.sort());
        entity.setVisible(payload.visible());
        entity.setStatus(payload.status());
        entity.setKeepAlive(payload.keepAlive());
        entity.setIsExternal(payload.isExternal());
        entity.setPlatformOnly(payload.platformOnly());
    }

    private static CommonMenuFields resolveCommonFields(
            SysMenu existing,
            MenuSaveRequest request,
            MenuTypeEnum nodeType,
            SysMenu parent
    ) {
        String title = SystemContextSupport.trimToNull(request.getTitle());
        if (title == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "菜单名称不能为空");
        }
        Integer visible = resolveVisibleStatus(existing, request.getVisible());
        Integer status = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : (existing != null ? existing.getStatus() : SystemContextSupport.normalizeStatus(null));
        int sort = request.getSort() != null ? request.getSort() : 0;
        boolean platformOnly = resolvePlatformOnly(existing, request.getPlatformOnly(), parent);
        String perms = validatePerms(request.getPerms(), nodeType);
        return new CommonMenuFields(title, visible, status, sort, platformOnly, perms);
    }

    private static Integer resolveVisibleStatus(SysMenu existing, Integer rawVisible) {
        if (rawVisible == null) {
            return existing != null && existing.getVisible() != null
                    ? existing.getVisible()
                    : VisibleStatusEnum.VISIBLE.getCode();
        }
        VisibleStatusEnum parsed = VisibleStatusEnum.fromCode(rawVisible);
        if (parsed == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "显隐状态仅支持 0（显示）或 1（隐藏）");
        }
        return parsed.getCode();
    }

    private static boolean resolvePlatformOnly(SysMenu existing, Boolean rawPlatformOnly, SysMenu parent) {
        boolean resolved = rawPlatformOnly != null
                ? rawPlatformOnly
                : (existing != null ? Boolean.TRUE.equals(existing.getPlatformOnly())
                : (parent != null && Boolean.TRUE.equals(parent.getPlatformOnly())));
        if (parent != null && Boolean.TRUE.equals(parent.getPlatformOnly()) && !resolved) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "平台专属父节点下禁止挂载非平台专属子节点");
        }
        return resolved;
    }

    private static String validatePerms(String rawPerms, MenuTypeEnum nodeType) {
        String perms = SystemContextSupport.trimToNull(rawPerms);
        if (nodeType == MenuTypeEnum.BUTTON && perms == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "按钮权限（F）必须填写权限标识 perms");
        }
        if (perms != null && !PERMS_PATTERN.matcher(perms).matches()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "权限标识须符合三段式格式（如 system:user:list）");
        }
        return perms;
    }

    private static NormalizedMenuPayload normalizeButton(MenuSaveRequest request, CommonMenuFields base) {
        return new NormalizedMenuPayload(
                request.getParentId(), MenuTypeEnum.BUTTON.getCode(), base.title(), null, null, null, null,
                base.perms(), null, base.sort(), VisibleStatusEnum.VISIBLE.getCode(), base.status(),
                false, false, base.platformOnly()
        );
    }

    private static NormalizedMenuPayload normalizeDirectory(
            MenuSaveRequest request,
            CommonMenuFields base,
            Predicate<String> duplicateNameChecker
    ) {
        String path = requireRoutePath(request.getPath());
        String name = SystemContextSupport.trimToNull(request.getName());
        assertRouteNameUnique(name, duplicateNameChecker);
        return new NormalizedMenuPayload(
                request.getParentId(), MenuTypeEnum.DIRECTORY.getCode(), base.title(), name, path, null,
                SystemContextSupport.trimToNull(request.getRedirect()), base.perms(),
                SystemContextSupport.trimToNull(request.getIcon()),
                base.sort(), base.visible(), base.status(), false, false, base.platformOnly()
        );
    }

    private static NormalizedMenuPayload normalizeMenuPage(
            SysMenu existing,
            MenuSaveRequest request,
            CommonMenuFields base,
            Predicate<String> duplicateNameChecker
    ) {
        String path = requireRoutePath(request.getPath());
        String icon = SystemContextSupport.trimToNull(request.getIcon());
        String name = SystemContextSupport.trimToNull(request.getName());
        boolean external = Boolean.TRUE.equals(request.getIsExternal());

        if (external) {
            if (!EXTERNAL_URL_PATTERN.matcher(path).matches()) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "外链地址必须以 http:// 或 https:// 开头");
            }
            assertRouteNameUnique(name, duplicateNameChecker);
            return new NormalizedMenuPayload(
                    request.getParentId(), MenuTypeEnum.MENU.getCode(), base.title(), name, path, null, null,
                    base.perms(), icon, base.sort(), base.visible(), base.status(), false, true, base.platformOnly()
            );
        }

        if (name == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "页面菜单（C）路由组件名称不能为空");
        }
        String component = SystemContextSupport.trimToNull(request.getComponent());
        if (component == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "页面菜单（C）组件路径不能为空");
        }
        assertRouteNameUnique(name, duplicateNameChecker);
        boolean keepAlive = request.getKeepAlive() != null
                ? request.getKeepAlive()
                : (existing == null || !Boolean.FALSE.equals(existing.getKeepAlive()));
        return new NormalizedMenuPayload(
                request.getParentId(), MenuTypeEnum.MENU.getCode(), base.title(), name, path, component, null,
                base.perms(), icon, base.sort(), base.visible(), base.status(), keepAlive, false, base.platformOnly()
        );
    }

    private static String requireRoutePath(String rawPath) {
        String path = SystemContextSupport.trimToNull(rawPath);
        if (path == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "路由地址 path 不能为空");
        }
        return path;
    }

    private static void assertRouteNameUnique(String name, Predicate<String> duplicateNameChecker) {
        if (name != null && duplicateNameChecker.test(name)) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "路由组件名称已存在：" + name);
        }
    }

    private record CommonMenuFields(
            String title,
            Integer visible,
            Integer status,
            int sort,
            boolean platformOnly,
            String perms
    ) {
    }

    /**
     * 规范化后的菜单落库字段快照记录。
     *
     * @param parentId     父菜单主键
     * @param menuType     节点类型（M/C/F）
     * @param title        菜单或按钮名称
     * @param name         路由组件名
     * @param path         路由路径或外链地址
     * @param component    前端组件路径
     * @param redirect     目录默认重定向路径
     * @param perms        三段式权限标识
     * @param icon         图标标识
     * @param sort         显示排序
     * @param visible      侧栏显隐状态（0=显示，1=隐藏）
     * @param status       启停状态（0=正常，1=停用）
     * @param keepAlive    是否开启页面缓存
     * @param isExternal   是否为外部链接
     * @param platformOnly 是否仅平台默认租户可见维护
     */
    public record NormalizedMenuPayload(
            Long parentId,
            String menuType,
            String title,
            String name,
            String path,
            String component,
            String redirect,
            String perms,
            String icon,
            Integer sort,
            Integer visible,
            Integer status,
            boolean keepAlive,
            boolean isExternal,
            boolean platformOnly
    ) {
    }
}
