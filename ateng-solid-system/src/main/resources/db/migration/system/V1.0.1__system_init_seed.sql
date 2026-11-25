-- ============================================================================
-- ateng-solid-system 确定性初始元数据种子脚本（< 100,000 号段，零雪花碰撞）
-- 安全红线：严禁在 SQL 中写入任何管理员密码哈希或明文手机/邮箱；
--          审计时间戳固定为 '2026-01-01 00:00:00'、操作人固定为 'SYSTEM'
-- ============================================================================

-- 1. 平台默认租户（号段 0）
INSERT INTO sys_tenant (
    id, tenant_code, tenant_name, package_id, expire_time, account_count, status, remark,
    create_by, create_time, update_by, update_time, deleted
) VALUES (
    0, '000000', '平台默认租户', NULL, NULL, -1, 0, '系统内置平台默认租户',
    'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0
);

-- 2. 平台内置超级管理员角色（号段 1 ~ 99；不预插 sys_role_menu，由超管免检统一接管）
INSERT INTO sys_role (
    id, tenant_id, role_name, role_key, data_scope, menu_check_strictly, dept_check_strictly,
    sort, status, remark, create_by, create_time, update_by, update_time, deleted
) VALUES (
    1, 0, '超级管理员', 'super_admin', 1, 1, 0,
    1, 0, '平台内置超级管理员角色', 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0
);

-- 3. 平台默认顶级部门（号段 100 ~ 999）
INSERT INTO sys_dept (
    id, tenant_id, parent_id, ancestors, dept_name, sort, leader_user_id, phone, email, status,
    create_by, create_time, update_by, update_time, deleted
) VALUES (
    100, 0, 0, '0', '平台总部', 1, NULL, NULL, NULL, 0,
    'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0
);

-- 4. 全量菜单与按钮权限树（号段 1000 ~ 9999，共 58 条：4 M + 12 C + 42 F）
INSERT INTO sys_menu (
    id, parent_id, menu_type, title, name, path, component, redirect, perms, icon,
    sort, visible, status, keep_alive, is_external, platform_only,
    create_by, create_time, update_by, update_time, deleted
) VALUES
    -- 1000 组织权限目录
    (1000, 0, 'M', '组织权限', NULL, '/system', NULL, '/system/user', NULL, 'Setting',
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 1100 用户管理 (1 C + 6 F)
    (1100, 1000, 'C', '用户管理', 'SysUser', '/system/user', 'system/user/index', NULL, NULL, 'User',
     1, 0, 0, 1, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1101, 1100, 'F', '用户查询', NULL, NULL, NULL, NULL, 'system:user:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1102, 1100, 'F', '用户新增', NULL, NULL, NULL, NULL, 'system:user:create', NULL,
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1103, 1100, 'F', '用户修改', NULL, NULL, NULL, NULL, 'system:user:update', NULL,
     3, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1104, 1100, 'F', '用户删除', NULL, NULL, NULL, NULL, 'system:user:delete', NULL,
     4, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1105, 1100, 'F', '用户导入', NULL, NULL, NULL, NULL, 'system:user:import', NULL,
     5, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1106, 1100, 'F', '用户导出', NULL, NULL, NULL, NULL, 'system:user:export', NULL,
     6, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 1200 角色管理 (1 C + 4 F)
    (1200, 1000, 'C', '角色管理', 'SysRole', '/system/role', 'system/role/index', NULL, NULL, 'UserFilled',
     2, 0, 0, 1, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1201, 1200, 'F', '角色查询', NULL, NULL, NULL, NULL, 'system:role:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1202, 1200, 'F', '角色新增', NULL, NULL, NULL, NULL, 'system:role:create', NULL,
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1203, 1200, 'F', '角色修改', NULL, NULL, NULL, NULL, 'system:role:update', NULL,
     3, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1204, 1200, 'F', '角色删除', NULL, NULL, NULL, NULL, 'system:role:delete', NULL,
     4, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 1300 菜单管理（平台专属 platform_only=1）(1 C + 4 F)
    (1300, 1000, 'C', '菜单管理', 'SysMenu', '/system/menu', 'system/menu/index', NULL, NULL, 'Menu',
     3, 0, 0, 1, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1301, 1300, 'F', '菜单查询', NULL, NULL, NULL, NULL, 'system:menu:list', NULL,
     1, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1302, 1300, 'F', '菜单新增', NULL, NULL, NULL, NULL, 'system:menu:create', NULL,
     2, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1303, 1300, 'F', '菜单修改', NULL, NULL, NULL, NULL, 'system:menu:update', NULL,
     3, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1304, 1300, 'F', '菜单删除', NULL, NULL, NULL, NULL, 'system:menu:delete', NULL,
     4, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 1400 部门管理 (1 C + 4 F)
    (1400, 1000, 'C', '部门管理', 'SysDept', '/system/dept', 'system/dept/index', NULL, NULL, 'OfficeBuilding',
     4, 0, 0, 1, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1401, 1400, 'F', '部门查询', NULL, NULL, NULL, NULL, 'system:dept:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1402, 1400, 'F', '部门新增', NULL, NULL, NULL, NULL, 'system:dept:create', NULL,
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1403, 1400, 'F', '部门修改', NULL, NULL, NULL, NULL, 'system:dept:update', NULL,
     3, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1404, 1400, 'F', '部门删除', NULL, NULL, NULL, NULL, 'system:dept:delete', NULL,
     4, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 1500 岗位管理 (1 C + 4 F)
    (1500, 1000, 'C', '岗位管理', 'SysPost', '/system/post', 'system/post/index', NULL, NULL, 'Postcard',
     5, 0, 0, 1, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1501, 1500, 'F', '岗位查询', NULL, NULL, NULL, NULL, 'system:post:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1502, 1500, 'F', '岗位新增', NULL, NULL, NULL, NULL, 'system:post:create', NULL,
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1503, 1500, 'F', '岗位修改', NULL, NULL, NULL, NULL, 'system:post:update', NULL,
     3, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (1504, 1500, 'F', '岗位删除', NULL, NULL, NULL, NULL, 'system:post:delete', NULL,
     4, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 2000 基础配置目录（普通租户无数据字典，默认重定向至 /system/config）
    (2000, 0, 'M', '基础配置', NULL, '/config', NULL, '/system/config', NULL, 'Tools',
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 2100 数据字典（平台专属 platform_only=1）(1 C + 4 F)
    (2100, 2000, 'C', '数据字典', 'SysDict', '/system/dict', 'system/dict/index', NULL, NULL, 'Collection',
     1, 0, 0, 1, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2101, 2100, 'F', '字典查询', NULL, NULL, NULL, NULL, 'system:dict:list', NULL,
     1, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2102, 2100, 'F', '字典新增', NULL, NULL, NULL, NULL, 'system:dict:create', NULL,
     2, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2103, 2100, 'F', '字典修改', NULL, NULL, NULL, NULL, 'system:dict:update', NULL,
     3, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2104, 2100, 'F', '字典删除', NULL, NULL, NULL, NULL, 'system:dict:delete', NULL,
     4, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 2200 系统参数 (1 C + 4 F)
    (2200, 2000, 'C', '系统参数', 'SysConfig', '/system/config', 'system/config/index', NULL, NULL, 'Operation',
     2, 0, 0, 1, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2201, 2200, 'F', '参数查询', NULL, NULL, NULL, NULL, 'system:config:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2202, 2200, 'F', '参数新增', NULL, NULL, NULL, NULL, 'system:config:create', NULL,
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2203, 2200, 'F', '参数修改', NULL, NULL, NULL, NULL, 'system:config:update', NULL,
     3, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (2204, 2200, 'F', '参数删除', NULL, NULL, NULL, NULL, 'system:config:delete', NULL,
     4, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 3000 多租户治理目录（整体平台专属 platform_only=1）
    (3000, 0, 'M', '多租户治理', NULL, '/tenant', NULL, '/system/tenant', NULL, 'Grid',
     3, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 3100 租户管理 (1 C + 4 F)
    (3100, 3000, 'C', '租户管理', 'SysTenant', '/system/tenant', 'system/tenant/index', NULL, NULL, 'Suitcase',
     1, 0, 0, 1, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3101, 3100, 'F', '租户查询', NULL, NULL, NULL, NULL, 'system:tenant:list', NULL,
     1, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3102, 3100, 'F', '租户新增', NULL, NULL, NULL, NULL, 'system:tenant:create', NULL,
     2, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3103, 3100, 'F', '租户修改', NULL, NULL, NULL, NULL, 'system:tenant:update', NULL,
     3, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3104, 3100, 'F', '租户删除', NULL, NULL, NULL, NULL, 'system:tenant:delete', NULL,
     4, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 3200 租户套餐 (1 C + 4 F)
    (3200, 3000, 'C', '租户套餐', 'SysTenantPackage', '/system/tenant-package', 'system/tenant-package/index', NULL, NULL, 'Tickets',
     2, 0, 0, 1, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3201, 3200, 'F', '套餐查询', NULL, NULL, NULL, NULL, 'system:tenant-package:list', NULL,
     1, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3202, 3200, 'F', '套餐新增', NULL, NULL, NULL, NULL, 'system:tenant-package:create', NULL,
     2, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3203, 3200, 'F', '套餐修改', NULL, NULL, NULL, NULL, 'system:tenant-package:update', NULL,
     3, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (3204, 3200, 'F', '套餐删除', NULL, NULL, NULL, NULL, 'system:tenant-package:delete', NULL,
     4, 0, 0, 0, 0, 1, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 4000 审计安全目录
    (4000, 0, 'M', '审计安全', NULL, '/audit', NULL, '/system/oper-log', NULL, 'Monitor',
     4, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 4100 操作日志 (1 C + 1 F, keep_alive=0)
    (4100, 4000, 'C', '操作日志', 'SysOperLog', '/system/oper-log', 'system/oper-log/index', NULL, NULL, 'Document',
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (4101, 4100, 'F', '操作日志查询', NULL, NULL, NULL, NULL, 'system:oper-log:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 4200 登录日志 (1 C + 1 F, keep_alive=0)
    (4200, 4000, 'C', '登录日志', 'SysLoginLog', '/system/login-log', 'system/login-log/index', NULL, NULL, 'Key',
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (4201, 4200, 'F', '登录日志查询', NULL, NULL, NULL, NULL, 'system:login-log:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),

    -- 4300 在线会话 (1 C + 2 F, keep_alive=0)
    (4300, 4000, 'C', '在线会话', 'OnlineSession', '/system/online', 'system/online/index', NULL, NULL, 'Connection',
     3, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (4301, 4300, 'F', '在线会话查询', NULL, NULL, NULL, NULL, 'auth:online:list', NULL,
     1, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (4302, 4300, 'F', '在线会话强退', NULL, NULL, NULL, NULL, 'auth:online:kickout', NULL,
     2, 0, 0, 0, 0, 0, 'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0);

-- 5. 动态字典类型（号段 10001 ~ 10999）
INSERT INTO sys_dict_type (
    id, dict_name, dict_type, status, remark,
    create_by, create_time, update_by, update_time, deleted
) VALUES
    (10001, '用户性别', 'sys_user_gender', 0, '用户性别动态业务字典',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (10002, '系统是否', 'sys_yes_no', 0, '系统通用是否标识字典',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0);

-- 6. 动态字典明细（号段 11001 ~ 19999）
INSERT INTO sys_dict_data (
    id, dict_type, dict_label, dict_value, tag_type, css_class, sort, is_default, status, remark,
    create_by, create_time, update_by, update_time, deleted
) VALUES
    (11001, 'sys_user_gender', '男', '0', 'primary', NULL, 1, 0, 0, '性别男',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (11002, 'sys_user_gender', '女', '1', 'warning', NULL, 2, 0, 0, '性别女',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (11003, 'sys_user_gender', '未知', '2', 'info', NULL, 3, 1, 0, '性别未知（默认）',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (11101, 'sys_yes_no', '是', 'Y', 'primary', NULL, 1, 0, 0, '系统是',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0),
    (11102, 'sys_yes_no', '否', 'N', 'info', NULL, 2, 1, 0, '系统否（默认）',
     'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0);

-- 7. 内置系统参数（号段 20001 ~ 29999）
INSERT INTO sys_config (
    id, tenant_id, config_name, config_key, config_value, config_type, remark,
    create_by, create_time, update_by, update_time, deleted
) VALUES (
    20001, 0, '用户管理-初始默认密码', 'sys.user.initPassword', 'Ateng@2026', 'Y',
    '新增用户、重置密码与批量导入时默认预置的初始口令（受内置保护禁止删除或更改键名）',
    'SYSTEM', '2026-01-01 00:00:00', 'SYSTEM', '2026-01-01 00:00:00', 0
);
