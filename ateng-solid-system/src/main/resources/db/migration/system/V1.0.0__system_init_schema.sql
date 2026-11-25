-- ============================================================================
-- ateng-solid-system 初始化建表脚本（全库 17 张表）
-- 兼容 MySQL 8.4 LTS 与 H2 2.2.224 (MODE=MySQL;DATABASE_TO_LOWER=TRUE)
-- 约束：零物理外键、无 ON UPDATE CURRENT_TIMESTAMP、全表统一 utf8mb4_0900_ai_ci、
--       所有索引带表简名前缀防 H2 Schema 全局索引重名、软删表显式 deleted BIGINT DEFAULT 0
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 平台共享表（6 张，多租户 ignore-tables 豁免）
-- ----------------------------------------------------------------------------

CREATE TABLE sys_tenant (
    id            BIGINT       NOT NULL COMMENT '主键ID（平台默认租户固定为0，其余为雪花ID）',
    tenant_code   VARCHAR(32)  NOT NULL COMMENT '租户业务编码（平台默认租户固定为000000）',
    tenant_name   VARCHAR(50)  NOT NULL COMMENT '租户名称',
    package_id    BIGINT       NULL COMMENT '关联租户套餐ID（平台默认租户0为NULL）',
    expire_time   DATETIME     NULL COMMENT '过期时间（NULL表示永久有效）',
    account_count INT          NOT NULL DEFAULT -1 COMMENT '账号配额上限（-1不限；>=1限制有效用户总数）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    remark        VARCHAR(500) NULL COMMENT '备注',
    create_by     VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_by     VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    deleted       BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code_deleted (tenant_code, deleted),
    KEY idx_tenant_package_id (package_id),
    KEY idx_tenant_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户信息表';

CREATE TABLE sys_tenant_package (
    id                  BIGINT       NOT NULL COMMENT '主键ID',
    package_name        VARCHAR(50)  NOT NULL COMMENT '套餐名称（全局未删唯一）',
    menu_check_strictly TINYINT      NOT NULL DEFAULT 1 COMMENT '菜单树是否父子联动（1联动 0独立）',
    status              TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    remark              VARCHAR(500) NULL COMMENT '备注',
    create_by           VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time         DATETIME     NOT NULL COMMENT '创建时间',
    update_by           VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time         DATETIME     NOT NULL COMMENT '更新时间',
    deleted             BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_pkg_name_deleted (package_name, deleted),
    KEY idx_tenant_pkg_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户套餐表';

CREATE TABLE sys_tenant_package_menu (
    id         BIGINT NOT NULL COMMENT '雪花主键ID',
    package_id BIGINT NOT NULL COMMENT '租户套餐ID',
    menu_id    BIGINT NOT NULL COMMENT '菜单ID（严禁关联 platform_only = 1 节点）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pkg_menu_pair (package_id, menu_id),
    KEY idx_pkg_menu_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户套餐与菜单关联表';

CREATE TABLE sys_menu (
    id            BIGINT       NOT NULL COMMENT '主键ID（初始种子使用 1000~9999 分段确定性ID）',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID（顶级目录为0）',
    menu_type     CHAR(1)      NOT NULL COMMENT '菜单类型（M目录 C菜单 F按钮）',
    title         VARCHAR(50)  NOT NULL COMMENT '菜单/权限显示名称',
    name          VARCHAR(64)  NULL COMMENT '前端路由组件名（C非外链必填PascalCase，全局唯一）',
    path          VARCHAR(255) NULL COMMENT '路由路径或外链URL（F节点为NULL）',
    component     VARCHAR(255) NULL COMMENT '前端视图路径（相对 src/views/；仅C非外链必填）',
    redirect      VARCHAR(255) NULL COMMENT '目录默认重定向路径（仅M节点使用）',
    perms         VARCHAR(100) NULL COMMENT '三段式权限标识（F节点必填；初始种子M/C为NULL）',
    icon          VARCHAR(64)  NULL COMMENT '图标名称（@element-plus/icons-vue）',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '显示排序（升序）',
    visible       TINYINT      NOT NULL DEFAULT 0 COMMENT '侧栏显隐（0显示 1隐藏）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    keep_alive    TINYINT      NOT NULL DEFAULT 1 COMMENT '页面是否缓存（1缓存 0不缓存）',
    is_external   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否外链（0否 1是）',
    platform_only TINYINT      NOT NULL DEFAULT 0 COMMENT '是否平台专属（1仅平台租户0可见维护且禁止入选套餐）',
    create_by     VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_by     VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    deleted       BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_name_deleted (name, deleted),
    KEY idx_menu_parent_sort (parent_id, sort),
    KEY idx_menu_perms (perms),
    KEY idx_menu_platform_status (platform_only, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单与按钮权限表';

CREATE TABLE sys_dict_type (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    dict_name   VARCHAR(50)  NOT NULL COMMENT '字典名称',
    dict_type   VARCHAR(64)  NOT NULL COMMENT '字典类型编码（编辑锁定只读）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    remark      VARCHAR(500) NULL COMMENT '备注',
    create_by   VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_by   VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    deleted     BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_type_code_deleted (dict_type, deleted),
    KEY idx_dict_type_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态字典类型表';

CREATE TABLE sys_dict_data (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    dict_type   VARCHAR(64)  NOT NULL COMMENT '所属字典类型编码',
    dict_label  VARCHAR(64)  NOT NULL COMMENT '字典展示标签',
    dict_value  VARCHAR(64)  NOT NULL COMMENT '字典键值（同 dict_type 内未删唯一）',
    tag_type    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '回显标签样式（空串/primary/success/warning/danger/info）',
    css_class   VARCHAR(100) NULL COMMENT '自定义扩展CSS类名',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '显示排序（升序）',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认（0否 1是，同 dict_type 事务内互斥唯一）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    remark      VARCHAR(500) NULL COMMENT '备注',
    create_by   VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_by   VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    deleted     BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_data_type_value_deleted (dict_type, dict_value, deleted),
    KEY idx_dict_data_type_sort (dict_type, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态字典数据表';

-- ----------------------------------------------------------------------------
-- 2. 租户隔离核心表（5 张，均含 tenant_id BIGINT NOT NULL DEFAULT 0）
-- ----------------------------------------------------------------------------

CREATE TABLE sys_dept (
    id             BIGINT       NOT NULL COMMENT '主键ID（平台总部默认100）',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID（0为平台默认租户）',
    parent_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '上级部门ID（顶级为0）',
    ancestors      VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖先路径链（如 0,100，支持前缀匹配）',
    dept_name      VARCHAR(50)  NOT NULL COMMENT '部门名称（同租户同上级下未删唯一）',
    sort           INT          NOT NULL DEFAULT 0 COMMENT '显示排序',
    leader_user_id BIGINT       NULL COMMENT '部门负责人用户ID',
    phone          VARCHAR(128) NULL COMMENT '联系电话（确定性加密存储，空串归NULL）',
    email          VARCHAR(255) NULL COMMENT '电子邮箱（确定性加密存储，小写归一、空串归NULL）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    create_by      VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time    DATETIME     NOT NULL COMMENT '创建时间',
    update_by      VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time    DATETIME     NOT NULL COMMENT '更新时间',
    deleted        BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dept_tenant_parent_name_deleted (tenant_id, parent_id, dept_name, deleted),
    KEY idx_dept_tenant_parent_sort (tenant_id, parent_id, sort),
    KEY idx_dept_tenant_ancestors (tenant_id, ancestors)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门表';

CREATE TABLE sys_post (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID（0为平台默认租户）',
    post_code   VARCHAR(64)  NOT NULL COMMENT '岗位编码（同租户未删唯一）',
    post_name   VARCHAR(50)  NOT NULL COMMENT '岗位名称（同租户未删唯一）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '显示排序',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    remark      VARCHAR(500) NULL COMMENT '备注',
    create_by   VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_by   VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    deleted     BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_tenant_code_deleted (tenant_id, post_code, deleted),
    UNIQUE KEY uk_post_tenant_name_deleted (tenant_id, post_name, deleted),
    KEY idx_post_tenant_sort (tenant_id, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='岗位表';

CREATE TABLE sys_role (
    id                  BIGINT       NOT NULL COMMENT '主键ID（平台内置 super_admin 固定为1）',
    tenant_id           BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID（0为平台默认租户）',
    role_name           VARCHAR(50)  NOT NULL COMMENT '角色名称',
    role_key            VARCHAR(64)  NOT NULL COMMENT '角色编码（同租户未删唯一；普通租户禁填 super_admin/admin）',
    data_scope          TINYINT      NOT NULL DEFAULT 1 COMMENT '数据范围（1全部 2自定部门 3本部门 4本部门及以下 5仅本人）',
    menu_check_strictly TINYINT      NOT NULL DEFAULT 1 COMMENT '菜单权限树是否父子联动（1联动 0独立）',
    dept_check_strictly TINYINT      NOT NULL DEFAULT 0 COMMENT '自定部门树是否父子联动（0独立勾选 1联动）',
    sort                INT          NOT NULL DEFAULT 0 COMMENT '显示排序',
    status              TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    remark              VARCHAR(500) NULL COMMENT '备注',
    create_by           VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time         DATETIME     NOT NULL COMMENT '创建时间',
    update_by           VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time         DATETIME     NOT NULL COMMENT '更新时间',
    deleted             BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tenant_key_deleted (tenant_id, role_key, deleted),
    KEY idx_role_tenant_sort (tenant_id, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

CREATE TABLE sys_user (
    id              BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID（0为平台默认租户）',
    dept_id         BIGINT       NULL COMMENT '归属部门ID（允许为NULL）',
    username        VARCHAR(64)  NOT NULL COMMENT '登录账号（同租户未删唯一，编辑锁定只读）',
    nickname        VARCHAR(64)  NOT NULL COMMENT '用户昵称',
    password        VARCHAR(128) NOT NULL COMMENT 'PBKDF2-HMAC-SHA256 慢哈希口令',
    pwd_update_time DATETIME     NULL COMMENT '最近自助改密时间（为空表示处于初始或重置密码状态）',
    email           VARCHAR(255) NULL COMMENT '电子邮箱（确定性加密存储，小写归一、空串归NULL）',
    phone           VARCHAR(128) NULL COMMENT '手机号码（确定性加密存储，空串归NULL）',
    gender          VARCHAR(16)  NOT NULL DEFAULT '2' COMMENT '性别（对齐动态字典 sys_user_gender：0男 1女 2未知）',
    avatar          VARCHAR(500) NULL COMMENT '头像相对对象键（严禁存临时预签名URL）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
    login_ip        VARCHAR(64)  NULL COMMENT '最近登录IP',
    login_date      DATETIME     NULL COMMENT '最近登录时间',
    remark          VARCHAR(500) NULL COMMENT '备注',
    create_by       VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_by       VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time     DATETIME     NOT NULL COMMENT '更新时间',
    deleted         BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username_deleted (tenant_id, username, deleted),
    UNIQUE KEY uk_user_tenant_phone_deleted (tenant_id, phone, deleted),
    UNIQUE KEY uk_user_tenant_email_deleted (tenant_id, email, deleted),
    KEY idx_user_tenant_dept (tenant_id, dept_id),
    KEY idx_user_tenant_status (tenant_id, status),
    KEY idx_user_tenant_create_time (tenant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE sys_config (
    id           BIGINT        NOT NULL COMMENT '主键ID',
    tenant_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '租户ID（0为平台默认租户）',
    config_name  VARCHAR(100)  NOT NULL COMMENT '参数名称',
    config_key   VARCHAR(100)  NOT NULL COMMENT '参数键名（同租户未删唯一；内置项禁删改键名）',
    config_value VARCHAR(2000) NOT NULL COMMENT '参数键值',
    config_type  CHAR(1)       NOT NULL DEFAULT 'N' COMMENT '参数类型（Y系统内置 N业务自建）',
    remark       VARCHAR(500)  NULL COMMENT '备注',
    create_by    VARCHAR(64)   NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人',
    create_time  DATETIME      NOT NULL COMMENT '创建时间',
    update_by    VARCHAR(64)   NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人',
    update_time  DATETIME      NOT NULL COMMENT '更新时间',
    deleted      BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_tenant_key_deleted (tenant_id, config_key, deleted),
    KEY idx_config_tenant_type (tenant_id, config_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统参数表';

-- ----------------------------------------------------------------------------
-- 3. 租户隔离多对多关联表（4 张）
-- ----------------------------------------------------------------------------

CREATE TABLE sys_user_role (
    id        BIGINT NOT NULL COMMENT '雪花主键ID',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    user_id   BIGINT NOT NULL COMMENT '用户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role_pair (tenant_id, user_id, role_id),
    KEY idx_user_role_tenant_role (tenant_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与角色关联表';

CREATE TABLE sys_user_post (
    id        BIGINT NOT NULL COMMENT '雪花主键ID',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    user_id   BIGINT NOT NULL COMMENT '用户ID',
    post_id   BIGINT NOT NULL COMMENT '岗位ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_post_pair (tenant_id, user_id, post_id),
    KEY idx_user_post_tenant_post (tenant_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与岗位关联表';

CREATE TABLE sys_role_menu (
    id        BIGINT NOT NULL COMMENT '雪花主键ID',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    menu_id   BIGINT NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu_pair (tenant_id, role_id, menu_id),
    KEY idx_role_menu_menu_id (menu_id),
    KEY idx_role_menu_tenant_menu (tenant_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色与菜单关联表';

CREATE TABLE sys_role_dept (
    id        BIGINT NOT NULL COMMENT '雪花主键ID',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    dept_id   BIGINT NOT NULL COMMENT '部门ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_dept_pair (tenant_id, role_id, dept_id),
    KEY idx_role_dept_tenant_dept (tenant_id, dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色与自定部门关联表';

-- ----------------------------------------------------------------------------
-- 4. 租户隔离只追加审计日志表（2 张）
-- ----------------------------------------------------------------------------

CREATE TABLE sys_oper_log (
    id               BIGINT       NOT NULL COMMENT '雪花主键ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    trace_id         VARCHAR(64)  NULL COMMENT '全链路追踪ID',
    user_id          BIGINT       NULL COMMENT '操作人用户ID',
    user_name        VARCHAR(64)  NULL COMMENT '操作人账号/昵称',
    module           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作模块名',
    operate_type     INT          NOT NULL DEFAULT 0 COMMENT '操作类型（对齐 OperateTypeEnum.code）',
    description      VARCHAR(255) NULL COMMENT '操作语义描述',
    request_method   VARCHAR(16)  NULL COMMENT 'HTTP请求方法',
    request_url      VARCHAR(500) NULL COMMENT 'HTTP请求路径',
    client_ip        VARCHAR(64)  NULL COMMENT '客户端IP',
    operate_location VARCHAR(128) NULL COMMENT '操作归属地（首期可空）',
    browser          VARCHAR(64)  NULL COMMENT '浏览器',
    os               VARCHAR(64)  NULL COMMENT '操作系统',
    request_params   TEXT         NULL COMMENT '脱敏后请求参数JSON',
    response_result  TEXT         NULL COMMENT '响应摘要JSON（失败置NULL）',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '执行状态（0成功 1失败）',
    cost_time        BIGINT       NOT NULL DEFAULT 0 COMMENT '执行耗时（毫秒）',
    error_msg        TEXT         NULL COMMENT '脱敏后错误摘要',
    extra_json       TEXT         NULL COMMENT '扩展JSON',
    operate_time     DATETIME     NOT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_oper_log_tenant_time (tenant_id, operate_time),
    KEY idx_oper_log_tenant_trace (tenant_id, trace_id),
    KEY idx_oper_log_tenant_user_time (tenant_id, user_id, operate_time),
    KEY idx_oper_log_tenant_module_type (tenant_id, module, operate_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志表';

CREATE TABLE sys_login_log (
    id             BIGINT       NOT NULL COMMENT '雪花主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    trace_id       VARCHAR(64)  NULL COMMENT '全链路追踪ID',
    username       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '登录尝试账号',
    login_type     VARCHAR(32)  NOT NULL DEFAULT 'admin' COMMENT '账号体系标识',
    client_ip      VARCHAR(64)  NULL COMMENT '客户端IP',
    login_location VARCHAR(128) NULL COMMENT '登录归属地（首期可空）',
    browser        VARCHAR(64)  NULL COMMENT '浏览器',
    os             VARCHAR(64)  NULL COMMENT '操作系统',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '登录状态（0成功 1失败）',
    msg            VARCHAR(500) NULL COMMENT '提示消息',
    login_time     DATETIME     NOT NULL COMMENT '登录时间',
    PRIMARY KEY (id),
    KEY idx_login_log_tenant_time (tenant_id, login_time),
    KEY idx_login_log_tenant_trace (tenant_id, trace_id),
    KEY idx_login_log_tenant_user_time (tenant_id, username, login_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录审计日志表';
