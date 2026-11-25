package io.github.atengk.solid;

import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.request.DeptSaveRequest;
import io.github.atengk.solid.system.request.MenuSaveRequest;
import io.github.atengk.solid.system.request.PostSaveRequest;
import io.github.atengk.solid.system.request.RoleSaveRequest;

import java.util.List;

/**
 * 组织架构与权限树集成测试共享请求构建工具，消除测试文件间样本请求构造样板代码。
 *
 * @author Ateng
 * @since 2026-11-25
 */
final class OrgAndRbacTestFixtures {

    private OrgAndRbacTestFixtures() {
    }

    static DeptSaveRequest deptSave(Long id, Long parentId, String deptName, int sort, String phone, String email) {
        DeptSaveRequest req = new DeptSaveRequest();
        req.setId(id);
        req.setParentId(parentId);
        req.setDeptName(deptName);
        req.setSort(sort);
        req.setPhone(phone);
        req.setEmail(email);
        req.setStatus(CommonStatusEnum.NORMAL.getCode());
        return req;
    }

    static PostSaveRequest postSave(Long id, String code, String name, int sort, String remark) {
        PostSaveRequest req = new PostSaveRequest();
        req.setId(id);
        req.setPostCode(code);
        req.setPostName(name);
        req.setSort(sort);
        req.setStatus(CommonStatusEnum.NORMAL.getCode());
        req.setRemark(remark);
        return req;
    }

    static MenuSaveRequest menuSave(
            Long id,
            Long parentId,
            String menuType,
            String title,
            String name,
            String path,
            String component,
            String perms,
            int sort,
            boolean platformOnly
    ) {
        MenuSaveRequest req = new MenuSaveRequest();
        req.setId(id);
        req.setParentId(parentId);
        req.setMenuType(menuType);
        req.setTitle(title);
        req.setName(name);
        req.setPath(path);
        req.setComponent(component);
        req.setPerms(perms);
        req.setSort(sort);
        req.setVisible(0);
        req.setStatus(CommonStatusEnum.NORMAL.getCode());
        req.setKeepAlive("C".equals(menuType));
        req.setIsExternal(false);
        req.setPlatformOnly(platformOnly);
        return req;
    }

    static RoleSaveRequest roleSave(Long id, String name, String key, List<Long> menuIds) {
        RoleSaveRequest req = new RoleSaveRequest();
        req.setId(id);
        req.setRoleName(name);
        req.setRoleKey(key);
        req.setSort(1);
        req.setStatus(CommonStatusEnum.NORMAL.getCode());
        req.setMenuCheckStrictly(true);
        req.setMenuIds(menuIds);
        return req;
    }
}
