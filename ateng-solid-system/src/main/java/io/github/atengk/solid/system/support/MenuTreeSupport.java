package io.github.atengk.solid.system.support;

import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import io.github.atengk.solid.system.vo.MenuVO;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 菜单树拓扑装配、祖先回溯筛选、子孙遍历与租户治理菜单识别公共辅助工具。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class MenuTreeSupport {

    /**
     * V1.0.1 确定性种子约定的多租户治理顶级目录主键
     */
    private static final long TENANT_GOVERNANCE_DIR_ID = 3000L;
    private static final String TENANT_ROOT_PATH = "/tenant";
    private static final Set<String> TENANT_ROUTE_NAMES = Set.of("SysTenant", "SysTenantPackage");
    private static final Set<String> TENANT_ROUTE_PATHS = Set.of("/system/tenant", "/system/tenant-package");
    private static final String TENANT_PERMS_PREFIX = "system:tenant";

    private MenuTreeSupport() {
    }

    /**
     * 判断菜单节点是否属于仅多租户开启时展示的租户治理菜单。
     *
     * @param menu 菜单实体
     * @return 属于租户治理菜单返回 {@code true}
     */
    public static boolean isTenantGovernanceMenu(SysMenu menu) {
        if (menu == null) {
            return false;
        }
        if (Objects.equals(menu.getId(), TENANT_GOVERNANCE_DIR_ID)) {
            return true;
        }
        String path = menu.getPath();
        if (TENANT_ROOT_PATH.equals(path) || (path != null && TENANT_ROUTE_PATHS.contains(path))) {
            return true;
        }
        String name = menu.getName();
        if (name != null && TENANT_ROUTE_NAMES.contains(name)) {
            return true;
        }
        return StringUtils.hasText(menu.getPerms()) && menu.getPerms().trim().startsWith(TENANT_PERMS_PREFIX);
    }

    /**
     * 按父节点分组有序菜单列表。
     *
     * @param menus 有序菜单列表
     * @return 按 {@code parentId} 分组的有序映射
     */
    public static Map<Long, List<SysMenu>> groupByParentId(List<SysMenu> menus) {
        if (CollectionUtils.isEmpty(menus)) {
            return Collections.emptyMap();
        }
        Map<Long, List<SysMenu>> grouped = new LinkedHashMap<>();
        for (SysMenu menu : menus) {
            Long parentId = menu.getParentId() != null ? menu.getParentId() : 0L;
            grouped.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(menu);
        }
        return grouped;
    }

    /**
     * 收集指定菜单节点及其所有子孙节点的主键集合（用于修改父节点时的防自环校验）。
     *
     * @param rootId   当前目标节点主键
     * @param allMenus 全量菜单列表
     * @return 包含自身及全部子孙主键的集合
     */
    public static Set<Long> collectSubtreeIds(Long rootId, List<SysMenu> allMenus) {
        if (rootId == null || CollectionUtils.isEmpty(allMenus)) {
            return Collections.emptySet();
        }
        Map<Long, List<SysMenu>> childrenMap = groupByParentId(allMenus);
        Set<Long> visited = new HashSet<>();
        collectRecursive(rootId, childrenMap, visited);
        return visited;
    }

    /**
     * 根据节点谓词筛选菜单并自动保留命中节点的全部祖先链路，保持原始排序输出树结构。
     *
     * @param allMenus  全量有序菜单列表
     * @param hasFilter 是否存在筛选条件；{@code false} 时保留全部有效节点
     * @param matcher   节点直接命中条件
     * @return 菜单树集合
     */
    public static List<MenuTreeVO> buildTreeWithAncestorRetention(
            List<SysMenu> allMenus,
            boolean hasFilter,
            Predicate<SysMenu> matcher
    ) {
        if (CollectionUtils.isEmpty(allMenus)) {
            return Collections.emptyList();
        }
        Set<Long> visibleIds;
        if (!hasFilter) {
            visibleIds = new HashSet<>(allMenus.size());
            for (SysMenu menu : allMenus) {
                visibleIds.add(menu.getId());
            }
        } else {
            visibleIds = resolveMatchedWithAncestors(allMenus, matcher);
            if (visibleIds.isEmpty()) {
                return Collections.emptyList();
            }
        }
        return assembleTree(allMenus, visibleIds);
    }

    /**
     * 将菜单实体转换为扁平详情视图对象。
     *
     * @param menu 菜单实体
     * @return 菜单详情视图
     */
    public static MenuVO toMenuVO(SysMenu menu) {
        return MenuVO.builder()
                .id(menu.getId())
                .parentId(menu.getParentId())
                .menuType(menu.getMenuType())
                .title(menu.getTitle())
                .name(menu.getName())
                .path(menu.getPath())
                .component(menu.getComponent())
                .redirect(menu.getRedirect())
                .perms(menu.getPerms())
                .icon(menu.getIcon())
                .sort(menu.getSort())
                .visible(menu.getVisible())
                .status(menu.getStatus())
                .keepAlive(menu.getKeepAlive())
                .isExternal(menu.getIsExternal())
                .platformOnly(menu.getPlatformOnly())
                .createTime(menu.getCreateTime())
                .build();
    }

    /**
     * 将菜单实体及子树转换为树节点视图对象。
     *
     * @param menu     菜单实体
     * @param children 子树集合
     * @return 菜单树节点视图
     */
    public static MenuTreeVO toMenuTreeVO(SysMenu menu, List<MenuTreeVO> children) {
        return MenuTreeVO.builder()
                .id(menu.getId())
                .parentId(menu.getParentId())
                .menuType(menu.getMenuType())
                .title(menu.getTitle())
                .name(menu.getName())
                .path(menu.getPath())
                .component(menu.getComponent())
                .redirect(menu.getRedirect())
                .perms(menu.getPerms())
                .icon(menu.getIcon())
                .sort(menu.getSort())
                .visible(menu.getVisible())
                .status(menu.getStatus())
                .keepAlive(menu.getKeepAlive())
                .isExternal(menu.getIsExternal())
                .platformOnly(menu.getPlatformOnly())
                .createTime(menu.getCreateTime())
                .children(CollectionUtils.isEmpty(children) ? Collections.emptyList() : children)
                .build();
    }

    private static Set<Long> resolveMatchedWithAncestors(List<SysMenu> allMenus, Predicate<SysMenu> matcher) {
        Map<Long, SysMenu> nodeMap = new HashMap<>(allMenus.size());
        for (SysMenu menu : allMenus) {
            nodeMap.put(menu.getId(), menu);
        }
        Set<Long> visibleIds = new HashSet<>();
        for (SysMenu menu : allMenus) {
            if (!matcher.test(menu)) {
                continue;
            }
            SysMenu cursor = menu;
            while (cursor != null && visibleIds.add(cursor.getId())) {
                Long parentId = cursor.getParentId();
                cursor = (parentId == null || parentId == 0L) ? null : nodeMap.get(parentId);
            }
        }
        return visibleIds;
    }

    private static List<MenuTreeVO> assembleTree(List<SysMenu> allMenus, Set<Long> visibleIds) {
        Map<Long, List<SysMenu>> childrenMap = new LinkedHashMap<>();
        List<SysMenu> roots = new ArrayList<>();
        for (SysMenu menu : allMenus) {
            if (!visibleIds.contains(menu.getId())) {
                continue;
            }
            Long parentId = menu.getParentId();
            if (parentId == null || parentId == 0L || !visibleIds.contains(parentId)) {
                roots.add(menu);
            } else {
                childrenMap.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(menu);
            }
        }
        if (roots.isEmpty()) {
            return Collections.emptyList();
        }
        List<MenuTreeVO> tree = new ArrayList<>(roots.size());
        for (SysMenu root : roots) {
            tree.add(buildNodeRecursive(root, childrenMap));
        }
        return tree;
    }

    private static MenuTreeVO buildNodeRecursive(SysMenu current, Map<Long, List<SysMenu>> childrenMap) {
        List<SysMenu> rawChildren = childrenMap.get(current.getId());
        if (CollectionUtils.isEmpty(rawChildren)) {
            return toMenuTreeVO(current, Collections.emptyList());
        }
        List<MenuTreeVO> childVos = new ArrayList<>(rawChildren.size());
        for (SysMenu child : rawChildren) {
            childVos.add(buildNodeRecursive(child, childrenMap));
        }
        return toMenuTreeVO(current, childVos);
    }

    private static void collectRecursive(Long currentId, Map<Long, List<SysMenu>> childrenMap, Set<Long> collector) {
        if (!collector.add(currentId)) {
            return;
        }
        List<SysMenu> children = childrenMap.get(currentId);
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (SysMenu child : children) {
            collectRecursive(child.getId(), childrenMap, collector);
        }
    }
}
