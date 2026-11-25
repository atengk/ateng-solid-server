# 数据库建模与 Flyway 迁移规范 (database-and-flyway-spec.md)

> **适用范围**：`ateng-solid-server` 全库 17 表 DDL 精确设计、持久层实体映射、确定性字段加密与 Flyway 迁移脚本编写。  
> **状态**：第一阶段（`Spec 1 ~ Spec 4`）工程终稿（Wayfinder 决策单定稿，严禁编码阶段擅自偏移列类型、默认值与索引命名）。

---

## 1. 全局命名、双库兼容与字段类型铁律

1. **字符集、排序规则与双库兼容（MySQL 8.4 LTS & H2 `2.2.224 MODE=MySQL`）**：
   - 全库 17 张表统一使用 **`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`**（与云端 MySQL 8.4 LTS 服务端默认排序规则 100% 对齐，消除跨表/临时表 `Illegal mix of collations` 风险；已实测兼容 H2 `2.2.224` `MODE=MySQL;DATABASE_TO_LOWER=TRUE`）。
   - **禁止使用 H2 不兼容的 MySQL 专属语法**：严禁使用 `ON UPDATE CURRENT_TIMESTAMP`、自定义存储过程、触发器、视图函数或反斜杠字符集前缀；时间审计字段统一交由 MyBatis-Plus `DefaultMetaObjectHandler` 填充。
   - **Flyway MySQL 方言包硬依赖**：`ateng-boot-flyway` 将 `org.flywaydb:flyway-mysql` 声明为 `<optional>true</optional>`，因此 `ateng-solid-bootstrap/pom.xml`（及后续 `ateng-solid-cloud-system`）**必须显式声明 `org.flywaydb:flyway-mysql`（`runtime`）**；JDBC URL 模板须携带 `allowPublicKeyRetrieval=true` 适配 MySQL 8.4 默认 `caching_sha2_password` 认证插件（实机联调如遇 Flyway `10.10.0` / `mysql-connector-j 8.3.0` 版本校验告警，可按需在父 POM 覆盖升至 `8.4+` / `10.15+`）。
   - **零物理外键**：严禁使用 `FOREIGN KEY` 或级联外键约束，引用一致性由 Service 层与数据库事务保证。

2. **表与索引命名铁律（防 H2 Schema 全局索引重名）**：
   - 全小写蛇形命名：内核域 `sys_*`、扩展域 `ext_*`、二开行业域 `biz_*`；主键统一 `id BIGINT NOT NULL`（64 位雪花主键 `IdType.ASSIGN_ID`）。
   - **表简名前缀强制规则**：经实测 H2 `2.2.224`（即便开启 `MODE=MySQL`）中索引名为 **Schema 全局作用域**而非表内作用域，跨表重名索引会直接阻断迁移。全库唯一索引与普通索引必须携带表简名前缀：
     - 防软删联合唯一索引：`uk_<表简名>_<业务列>_deleted`（如 `uk_user_tenant_username_deleted`）
     - 无软删关联表唯一索引：`uk_<表简名>_pair`（如 `uk_user_role_pair`）
     - 普通检索索引：`idx_<表简名>_<列名>`（如 `idx_oper_log_tenant_trace`、`idx_login_log_tenant_trace`）

3. **实体基类与公共列硬规范（对齐 `ateng-boot#1134` 重构体系）**：
   - **`BaseLogicEntity`（第一阶段全部 10 张业务主表默认基类，不引入 `version` 乐观锁列）**：
     - `id BIGINT NOT NULL COMMENT '主键ID'`
     - `create_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人'`
     - `create_time DATETIME NOT NULL COMMENT '创建时间'`
     - `update_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM' COMMENT '更新人'`
     - `update_time DATETIME NOT NULL COMMENT '更新时间'`
     - `deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标识（0未删除，删除后回填主键ID）'`（`@TableLogic(value = "0", delval = "id")`）
     - > **底座填充边界硬约束**：`DefaultMetaObjectHandler#insertFill` 仅自动填充审计四字段与 `tenantId`，**不会填充 `deleted` 与 `version`**；同时 `AtengSqlInjector` 的 `InsertBatchSomeColumn` 主动排除了 `@TableLogic` 字段。因此所有软删表 **必须** 在 DDL 显式声明 `deleted BIGINT NOT NULL DEFAULT 0`。
   - **`BaseFullEntity`（带 `@Version Integer version`）**：第一阶段 10 张内核主表均不启用乐观锁（避免僵尸列、避免前端无 409 重试协议及 `sys_user` 异步更新登录信息误触发 CAS 冲突）；留待二期或行业二开的高并发状态流转表按需继承。
   - **`BaseIdEntity`**：全部 5 张多对多关联表与 2 张只追加审计日志表继承，仅含 64 位雪花主键 `id`（无软删与多余审计列）。
   - **多租户隔离边界（`TenantAware<Long>`）**：11 张租户隔离表统一追加 `tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID（0为平台默认租户）'`，实体声明 `private Long tenantId;` 并实现 `TenantAware<Long>`；6 张平台共享表 + `flyway_schema_history` 列入 `ateng.boot.mybatis-plus.tenant.ignore-tables`。
   - **枚举与字典列类型规范**：
     - 动态业务字典列（如 `sys_user.gender` 对应 `sys_user_gender`）统一使用 `VARCHAR(16) NOT NULL DEFAULT '...'`，与 `sys_dict_data.dict_value` 字符串值 1:1 对齐；
     - 静态整数枚举与布尔开关列统一 `TINYINT NOT NULL DEFAULT ...`（`sys_oper_log.operate_type` 为 `INT NOT NULL DEFAULT 0`，对齐 `OperateTypeEnum.code`）；
     - 静态单字符枚举（`sys_menu.menu_type`、`sys_config.config_type`）统一 `CHAR(1) NOT NULL`。

---

## 2. 17 张表实体分层总览与多租户归属矩阵

| 分类 | 表名 | 继承基类 | `TenantAware<Long>` | 特殊类级注解 | 说明 |
| :--- | :--- | :---: | :---: | :--- | :--- |
| **平台共享 (6)** | `sys_tenant` | `BaseLogicEntity` | 否（`ignore-tables`） | 无 | 平台租户主表；`id=0` 不可软删停用 |
| | `sys_tenant_package` | `BaseLogicEntity` | 否（`ignore-tables`） | 无 | 租户套餐定义表 |
| | `sys_tenant_package_menu` | `BaseIdEntity` | 否（`ignore-tables`） | 无 | 套餐-菜单关联表 |
| | `sys_menu` | `BaseLogicEntity` | 否（`ignore-tables`） | 无 | 统一承载 `M`/`C`/`F` 权限节点 |
| | `sys_dict_type` | `BaseLogicEntity` | 否（`ignore-tables`） | 无 | 动态字典类型主表（平台专属维护） |
| | `sys_dict_data` | `BaseLogicEntity` | 否（`ignore-tables`） | 无 | 动态字典明细表（全租户只读消费） |
| **租户核心 (5)** | `sys_dept` | `BaseLogicEntity` | **是** | **`autoResultMap = true`** | 组织部门树（含 `ancestors` 与加密联系字段） |
| | `sys_post` | `BaseLogicEntity` | **是** | 无 | 岗位主表 |
| | `sys_role` | `BaseLogicEntity` | **是** | 无 | 角色与 `@DataScope` 数据范围表 |
| | `sys_user` | `BaseLogicEntity` | **是** | **`autoResultMap = true`** | 管理端用户主表（含加密联系字段与对象键头像） |
| | `sys_config` | `BaseLogicEntity` | **是** | 无 | 租户隔离系统参数表 |
| **租户关联 (4)** | `sys_user_role` | `BaseIdEntity` | **是** | 无 | 用户-角色关联表 |
| | `sys_user_post` | `BaseIdEntity` | **是** | 无 | 用户-岗位关联表 |
| | `sys_role_menu` | `BaseIdEntity` | **是** | 无 | 角色-菜单关联表 |
| | `sys_role_dept` | `BaseIdEntity` | **是** | 无 | 角色-自定部门关联表（`CUSTOM` 数据权限） |
| **租户审计 (2)** | `sys_oper_log` | `BaseIdEntity` | **是** | 无 | 只追加操作审计日志表 |
| | `sys_login_log` | `BaseIdEntity` | **是** | 无 | 只追加登录审计日志表 |

---

## 3. 全库 17 表精确 DDL 字段与索引清单（`V1.0.0__system_init_schema.sql`）

> 约定：下方各 `BaseLogicEntity` 表均隐含统一 5 个尾部公共列：`create_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM'`、`create_time DATETIME NOT NULL`、`update_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM'`、`update_time DATETIME NOT NULL`、`deleted BIGINT NOT NULL DEFAULT 0`，下文表格不再重复罗列，编写 SQL 时逐表完整展开。

### 3.1 平台共享表（6 张）

#### 1. `sys_tenant`（租户信息表 · `BaseLogicEntity`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键（平台默认租户固定为 `0`，受 `102003` 保护永不软删；其余为雪花 ID） |
| `tenant_code` | `VARCHAR(32) NOT NULL` | 租户业务编码（平台默认租户固定 `'000000'`，编辑锁定只读） |
| `tenant_name` | `VARCHAR(50) NOT NULL` | 租户名称 |
| `package_id` | `BIGINT NULL` | 关联租户套餐 ID（平台默认租户 `0` 为 `NULL`） |
| `expire_time` | `DATETIME NULL` | 过期时间（`NULL` 表示永久有效） |
| `account_count` | `INT NOT NULL DEFAULT -1` | 账号配额上限（`-1` 不限；`>= 1` 限制有效用户总数） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_tenant_code_deleted (tenant_code, deleted)`、`KEY idx_tenant_package_id (package_id)`、`KEY idx_tenant_status (status)`

#### 2. `sys_tenant_package`（租户套餐表 · `BaseLogicEntity`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键 |
| `package_name` | `VARCHAR(50) NOT NULL` | 套餐名称（全局未删唯一） |
| `menu_check_strictly` | `TINYINT NOT NULL DEFAULT 1` | 菜单树是否父子联动（`1` 联动 / `0` 独立） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_tenant_pkg_name_deleted (package_name, deleted)`、`KEY idx_tenant_pkg_status (status)`

#### 3. `sys_tenant_package_menu`（租户套餐与菜单关联表 · `BaseIdEntity`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 雪花主键 |
| `package_id` | `BIGINT NOT NULL` | 租户套餐 ID |
| `menu_id` | `BIGINT NOT NULL` | 菜单 ID（严禁关联 `platform_only = 1` 节点） |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_pkg_menu_pair (package_id, menu_id)`、`KEY idx_pkg_menu_menu_id (menu_id)`

#### 4. `sys_menu`（菜单与按钮权限表 · `BaseLogicEntity`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键（初始种子使用 `1000 ~ 9999` 分段确定性 ID） |
| `parent_id` | `BIGINT NOT NULL DEFAULT 0` | 父菜单 ID（顶级目录为 `0`） |
| `menu_type` | `CHAR(1) NOT NULL` | 类型（`M` 目录 / `C` 菜单 / `F` 按钮） |
| `title` | `VARCHAR(50) NOT NULL` | 菜单/权限显示名称 |
| `name` | `VARCHAR(64) NULL` | 前端路由组件名（`C` 非外链必填 PascalCase，全局唯一，供 `<KeepAlive :include>`；其余 `NULL`） |
| `path` | `VARCHAR(255) NULL` | 路由路径或外链 URL（`F` 节点为 `NULL`） |
| `component` | `VARCHAR(255) NULL` | 前端视图路径（相对 `src/views/`；仅 `C` 非外链必填） |
| `redirect` | `VARCHAR(255) NULL` | 目录默认重定向路径（仅 `M` 节点使用） |
| `perms` | `VARCHAR(100) NULL` | 三段式权限标识（`F` 节点必填；初始种子 `M`/`C` 为 `NULL`） |
| `icon` | `VARCHAR(64) NULL` | 图标名称（`@element-plus/icons-vue`） |
| `sort` | `INT NOT NULL DEFAULT 0` | 显示排序（升序） |
| `visible` | `TINYINT NOT NULL DEFAULT 0` | 侧栏显隐（`0` 显示 / `1` 隐藏，对齐 `VisibleStatusEnum`） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `keep_alive` | `TINYINT NOT NULL DEFAULT 1` | 页面是否缓存（`1` 缓存 / `0` 不缓存；`M`/`F`/外链/日志页为 `0`） |
| `is_external` | `TINYINT NOT NULL DEFAULT 0` | 是否外链（`0` 否 / `1` 是） |
| `platform_only` | `TINYINT NOT NULL DEFAULT 0` | 是否平台专属（`1` 仅平台租户 `0` 可见维护且禁止入选套餐） |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_menu_name_deleted (name, deleted)`、`KEY idx_menu_parent_sort (parent_id, sort)`、`KEY idx_menu_perms (perms)`、`KEY idx_menu_platform_status (platform_only, status)`

#### 5. `sys_dict_type`（动态字典类型表 · `BaseLogicEntity`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键 |
| `dict_name` | `VARCHAR(50) NOT NULL` | 字典名称 |
| `dict_type` | `VARCHAR(64) NOT NULL` | 字典类型编码（编辑锁定只读） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_dict_type_code_deleted (dict_type, deleted)`、`KEY idx_dict_type_status (status)`

#### 6. `sys_dict_data`（动态字典数据表 · `BaseLogicEntity`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键 |
| `dict_type` | `VARCHAR(64) NOT NULL` | 所属字典类型编码 |
| `dict_label` | `VARCHAR(64) NOT NULL` | 字典展示标签 |
| `dict_value` | `VARCHAR(64) NOT NULL` | 字典键值（同 `dict_type` 内未删唯一） |
| `tag_type` | `VARCHAR(32) NOT NULL DEFAULT ''` | 回显标签样式（`''`/`primary`/`success`/`warning`/`danger`/`info`） |
| `css_class` | `VARCHAR(100) NULL` | 自定义扩展 CSS 类名 |
| `sort` | `INT NOT NULL DEFAULT 0` | 排序（升序） |
| `is_default` | `TINYINT NOT NULL DEFAULT 0` | 是否默认（`0` 否 / `1` 是，同 `dict_type` 事务内互斥唯一） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_dict_data_type_value_deleted (dict_type, dict_value, deleted)`、`KEY idx_dict_data_type_sort (dict_type, sort)`

---

### 3.2 租户隔离核心表（5 张 · 均含 `tenant_id BIGINT NOT NULL DEFAULT 0`）

#### 7. `sys_dept`（部门表 · `BaseLogicEntity` + `TenantAware<Long>`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键（平台总部默认 `100`） |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `parent_id` | `BIGINT NOT NULL DEFAULT 0` | 上级部门 ID（顶级为 `0`） |
| `ancestors` | `VARCHAR(500) NOT NULL DEFAULT '0'` | 祖先路径链（如 `'0,100'`，支持前缀匹配 `LIKE '0,100,%'`，禁 `FIND_IN_SET`） |
| `dept_name` | `VARCHAR(50) NOT NULL` | 部门名称（同租户同上级下未删唯一） |
| `sort` | `INT NOT NULL DEFAULT 0` | 显示排序 |
| `leader_user_id` | `BIGINT NULL` | 部门负责人用户 ID |
| `phone` | `VARCHAR(128) NULL` | 联系电话（**确定性加密** `DeterministicEncryptTypeHandler`，空串归 `NULL`） |
| `email` | `VARCHAR(255) NULL` | 电子邮箱（**确定性加密** `DeterministicEncryptTypeHandler`，小写归一、空串归 `NULL`） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_dept_tenant_parent_name_deleted (tenant_id, parent_id, dept_name, deleted)`、`KEY idx_dept_tenant_parent_sort (tenant_id, parent_id, sort)`、`KEY idx_dept_tenant_ancestors (tenant_id, ancestors)`

#### 8. `sys_post`（岗位表 · `BaseLogicEntity` + `TenantAware<Long>`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `post_code` | `VARCHAR(64) NOT NULL` | 岗位编码（同租户未删唯一） |
| `post_name` | `VARCHAR(50) NOT NULL` | 岗位名称（同租户未删唯一） |
| `sort` | `INT NOT NULL DEFAULT 0` | 显示排序 |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_post_tenant_code_deleted (tenant_id, post_code, deleted)`、`UNIQUE KEY uk_post_tenant_name_deleted (tenant_id, post_name, deleted)`、`KEY idx_post_tenant_sort (tenant_id, sort)`

#### 9. `sys_role`（角色表 · `BaseLogicEntity` + `TenantAware<Long>`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键（平台内置 `super_admin` 固定为 `1`） |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `role_name` | `VARCHAR(50) NOT NULL` | 角色名称（表单校验 `2~30`；不加数据库唯一约束） |
| `role_key` | `VARCHAR(64) NOT NULL` | 角色编码（同租户未删唯一；普通租户严禁使用 `super_admin`/`admin`） |
| `data_scope` | `TINYINT NOT NULL DEFAULT 1` | 数据范围（`1=ALL`, `2=CUSTOM`, `3=DEPT`, `4=DEPT_AND_CHILD`, `5=SELF`） |
| `menu_check_strictly` | `TINYINT NOT NULL DEFAULT 1` | 菜单权限树是否父子联动（`1` 联动 / `0` 独立） |
| `dept_check_strictly` | `TINYINT NOT NULL DEFAULT 0` | 自定部门树是否父子联动（`0` 独立勾选 / `1` 联动） |
| `sort` | `INT NOT NULL DEFAULT 0` | 显示排序 |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_role_tenant_key_deleted (tenant_id, role_key, deleted)`、`KEY idx_role_tenant_sort (tenant_id, sort)`

#### 10. `sys_user`（用户表 · `BaseLogicEntity` + `TenantAware<Long>`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `dept_id` | `BIGINT NULL` | 归属部门 ID（允许为 `NULL`；无部门用户遇部门数据权限走 `1 = 0` 兜底） |
| `username` | `VARCHAR(64) NOT NULL` | 登录账号（同租户未删唯一，编辑锁定只读） |
| `nickname` | `VARCHAR(64) NOT NULL` | 用户昵称 |
| `password` | `VARCHAR(128) NOT NULL` | PBKDF2-HMAC-SHA256 慢哈希（默认 310,000 次迭代定长 88 字符） |
| `pwd_update_time` | `DATETIME NULL` | 最近自助改密时间（新建/重置/超管引导置 `NULL`，触发工作台改密提醒） |
| `email` | `VARCHAR(255) NULL` | 电子邮箱（**确定性加密** `DeterministicEncryptTypeHandler`，64 位明文加密长 131；小写归一、空串归 `NULL`） |
| `phone` | `VARCHAR(128) NULL` | 手机号码（**确定性加密** `DeterministicEncryptTypeHandler`，11 位明文加密长 47；空串归 `NULL`） |
| `gender` | `VARCHAR(16) NOT NULL DEFAULT '2'` | 性别（对齐动态字典 `sys_user_gender`：`0` 男 / `1` 女 / `2` 未知） |
| `avatar` | `VARCHAR(500) NULL` | 头像相对对象键（如 `avatar/0/1001/<uuid>.png`；**严禁存临时预签名 URL**） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 状态（`0` 正常 / `1` 停用） |
| `login_ip` | `VARCHAR(64) NULL` | 最近登录 IP |
| `login_date` | `DATETIME NULL` | 最近登录时间 |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_user_tenant_username_deleted (tenant_id, username, deleted)`、`UNIQUE KEY uk_user_tenant_phone_deleted (tenant_id, phone, deleted)`、`UNIQUE KEY uk_user_tenant_email_deleted (tenant_id, email, deleted)`、`KEY idx_user_tenant_dept (tenant_id, dept_id)`、`KEY idx_user_tenant_status (tenant_id, status)`、`KEY idx_user_tenant_create_time (tenant_id, create_time)`

#### 11. `sys_config`（系统参数表 · `BaseLogicEntity` + `TenantAware<Long>`）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 主键 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `config_name` | `VARCHAR(100) NOT NULL` | 参数名称 |
| `config_key` | `VARCHAR(100) NOT NULL` | 参数键名（同租户未删唯一；内置项禁止修改键名与删除） |
| `config_value` | `VARCHAR(2000) NOT NULL` | 参数键值（UI 校验 `1~500`，库表留 4 倍余量） |
| `config_type` | `CHAR(1) NOT NULL DEFAULT 'N'` | 参数类型（`Y` 系统内置 / `N` 业务自建，对齐 `ConfigTypeEnum`） |
| `remark` | `VARCHAR(500) NULL` | 备注 |

- **索引**：`PRIMARY KEY (id)`、`UNIQUE KEY uk_config_tenant_key_deleted (tenant_id, config_key, deleted)`、`KEY idx_config_tenant_type (tenant_id, config_type)`

---

### 3.3 租户隔离多对多关联表（4 张 · `BaseIdEntity` + `TenantAware<Long>`）

| 表名 | 列清单（均 `NOT NULL`，`tenant_id DEFAULT 0`） | 索引清单（全局唯一表简名前缀） |
| :--- | :--- | :--- |
| `sys_user_role` | `id BIGINT`、`tenant_id BIGINT`、`user_id BIGINT`、`role_id BIGINT` | `PRIMARY KEY (id)`、`UNIQUE KEY uk_user_role_pair (tenant_id, user_id, role_id)`、`KEY idx_user_role_tenant_role (tenant_id, role_id)` |
| `sys_user_post` | `id BIGINT`、`tenant_id BIGINT`、`user_id BIGINT`、`post_id BIGINT` | `PRIMARY KEY (id)`、`UNIQUE KEY uk_user_post_pair (tenant_id, user_id, post_id)`、`KEY idx_user_post_tenant_post (tenant_id, post_id)` |
| `sys_role_menu` | `id BIGINT`、`tenant_id BIGINT`、`role_id BIGINT`、`menu_id BIGINT` | `PRIMARY KEY (id)`、`UNIQUE KEY uk_role_menu_pair (tenant_id, role_id, menu_id)`、`KEY idx_role_menu_menu_id (menu_id)`、`KEY idx_role_menu_tenant_menu (tenant_id, menu_id)` |
| `sys_role_dept` | `id BIGINT`、`tenant_id BIGINT`、`role_id BIGINT`、`dept_id BIGINT` | `PRIMARY KEY (id)`、`UNIQUE KEY uk_role_dept_pair (tenant_id, role_id, dept_id)`、`KEY idx_role_dept_tenant_dept (tenant_id, dept_id)` |

---

### 3.4 租户隔离只追加审计日志表（2 张 · `BaseIdEntity` + `TenantAware<Long>`）

#### 16. `sys_oper_log`（操作审计日志表）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 雪花主键 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `trace_id` | `VARCHAR(64) NULL` | 全链路追踪 ID |
| `user_id` | `BIGINT NULL` | 操作人用户 ID |
| `user_name` | `VARCHAR(64) NULL` | 操作人真实账号/昵称（由 `OperateLogStorageProvider` 补全） |
| `module` | `VARCHAR(64) NOT NULL DEFAULT ''` | 操作模块名 |
| `operate_type` | `INT NOT NULL DEFAULT 0` | 操作类型（对齐 `OperateTypeEnum.code` `0~10`） |
| `description` | `VARCHAR(255) NULL` | 操作语义描述（承接 `@OperateLog(description = "...")`） |
| `request_method` | `VARCHAR(16) NULL` | HTTP 请求方法 |
| `request_url` | `VARCHAR(500) NULL` | HTTP 请求路径 |
| `client_ip` | `VARCHAR(64) NULL` | 客户端 IP |
| `operate_location` | `VARCHAR(128) NULL` | 操作归属地（首期可空，前端空值展示 `--`） |
| `browser` | `VARCHAR(64) NULL` | 浏览器（底座 `UserAgentUtil` 同步解析） |
| `os` | `VARCHAR(64) NULL` | 操作系统（底座 `UserAgentUtil` 同步解析） |
| `request_params` | `TEXT NULL` | 脱敏后请求参数 JSON |
| `response_result` | `TEXT NULL` | 响应摘要 JSON（失败置 `NULL`） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 执行状态（`0` 成功 / `1` 失败） |
| `cost_time` | `BIGINT NOT NULL DEFAULT 0` | 执行耗时（毫秒） |
| `error_msg` | `TEXT NULL` | 脱敏后错误摘要（严禁落库原始堆栈/SQL） |
| `extra_json` | `TEXT NULL` | 扩展 JSON（RunAs 等附加信息） |
| `operate_time` | `DATETIME NOT NULL` | 操作时间 |

- **索引**：`PRIMARY KEY (id)`、`KEY idx_oper_log_tenant_time (tenant_id, operate_time)`、`KEY idx_oper_log_tenant_trace (tenant_id, trace_id)`、`KEY idx_oper_log_tenant_user_time (tenant_id, user_id, operate_time)`、`KEY idx_oper_log_tenant_module_type (tenant_id, module, operate_type)`

#### 17. `sys_login_log`（登录审计日志表）
| 列名 | 类型与约束 | 业务说明 |
| :--- | :--- | :--- |
| `id` | `BIGINT NOT NULL` | 雪花主键 |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0` | 租户 ID |
| `trace_id` | `VARCHAR(64) NULL` | 全链路追踪 ID |
| `username` | `VARCHAR(64) NOT NULL DEFAULT ''` | 登录尝试账号 |
| `login_type` | `VARCHAR(32) NOT NULL DEFAULT 'admin'` | 账号体系标识 |
| `client_ip` | `VARCHAR(64) NULL` | 客户端 IP |
| `login_location` | `VARCHAR(128) NULL` | 登录归属地（首期可空） |
| `browser` | `VARCHAR(64) NULL` | 浏览器（`UserAgentUtil.parse`） |
| `os` | `VARCHAR(64) NULL` | 操作系统（`UserAgentUtil.parse`） |
| `status` | `TINYINT NOT NULL DEFAULT 0` | 登录状态（`0` 成功 / `1` 失败） |
| `msg` | `VARCHAR(500) NULL` | 提示消息 |
| `login_time` | `DATETIME NOT NULL` | 登录时间 |

- **索引**：`PRIMARY KEY (id)`、`KEY idx_login_log_tenant_time (tenant_id, login_time)`、`KEY idx_login_log_tenant_trace (tenant_id, trace_id)`、`KEY idx_login_log_tenant_user_time (tenant_id, username, login_time)`

---

## 4. 确定性字段加密契约与 `ContactFieldCrypto` 深模块规范

> 完整架构权衡详见后端决策记录 [ADR-0004](../adr/0004-deterministic-field-encryption-and-search-boundary.md)。

1. **统一加密范围与实体注解**：
   - `SysUser` 与 `SysDept` 两个实体类必须同时声明 **`@TableName(value = "...", autoResultMap = true)`**；
   - `SysUser.{phone, email}` 与 `SysDept.{phone, email}` 4 个字段统一标注：
     ```java
     @FieldEncrypt(deterministic = true)
     @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
     private String phone;
     ```
2. **收口深模块 `ContactFieldCrypto`（`io.github.atengk.solid.system.support`）**：
   - 严禁业务 Service 直接传未归一化明文或分散手写 `trim`/`toLowerCase`/`EncryptTypeHandler.encryptDeterministic(...)`；全部收口于无状态工具类 `ContactFieldCrypto` 的 6 个方法：
     - `String normalizePhone(String raw)`：`trim()` 后若为空白统一返回 `null`（防空串 `""` 触发 `uk_user_tenant_phone_deleted` 唯一键冲突）；
     - `String normalizeEmail(String raw)`：`trim().toLowerCase(Locale.ROOT)` 后若为空白统一返回 `null`（消除 HMAC-SHA256 大小写敏感导致的重复绕过）；
     - `String encryptPhoneForQuery(String raw)` / `String encryptEmailForQuery(String raw)`：先归一化，非空时调用 `EncryptTypeHandler.encryptDeterministic(...)` 返回确定性密文，空值返回 `null`；
     - `List<String> encryptPhonesForQuery(Collection<String> raws)` / `List<String> encryptEmailsForQuery(Collection<String> raws)`：批量去重归一化并加密，专供 500 行用户导入 `IN` 批量查重使用。
3. **写路径与查询红线（实测适配 MyBatis-Plus `3.5.17`）**：
   - **禁止 `LIKE` 与 `ORDER BY`**：`/system/user` 列表 `phone` 筛选仅支持完整手机号精确匹配——使用 `QueryWrapper` 字符串列名或原生 SQL/XML 时调用 `eq("phone", ContactFieldCrypto.encryptPhoneForQuery(query.getPhone()))`；使用 `LambdaQueryWrapper`（`3.5.17` `AbstractLambdaWrapper` 会通过 `ColumnCache.mapping` 自动挂载字段 `DeterministicEncryptTypeHandler`，若传密文会触发二次加密）时统一传 `eq(SysUser::getPhone, ContactFieldCrypto.normalizePhone(query.getPhone()))`；
   - **更新必须走 `updateById(entity)`**：3 参 `LambdaUpdateWrapper.set(SysUser::getPhone, val)` 不自动挂载 `TypeHandler`（直接传明文会写裸明文）；更新联系字段统一经 `updateById(entity)`（实体字段先经 `normalize*` 赋值），清空为 `NULL` 时显式 `.set(SysUser::getPhone, null)`；
   - **种子与密钥红线**：`V1.0.1__system_init_seed.sql` 严禁写入明文联系信息；`ateng.boot.mybatis-plus.crypto.secret-key` 必须在 `application-local.yml`/环境变量外部化固定配置（`application-test.yml` 配置离线测试桩密钥），上线后无离线重加密方案严禁更换密钥。

---

## 5. Flyway 迁移配置与 `V1.0.1__system_init_seed.sql` 确定性 ID 分段规范

### 5.1 脚本目录与安全治理
- 脚本归属：`ateng-solid-system/src/main/resources/db/migration/system/`（单体 `bootstrap` 与后续 `cloud-system` 100% 共享同一份脚本）；
- 版本命名：`V1.0.0__system_init_schema.sql`（17 张表与索引）、`V1.0.1__system_init_seed.sql`（确定性非敏感元数据种子）；已合入 `dev`/`main` 的迁移文件严禁修改，后续变更仅允许追加新版本号脚本；
- 安全开关：`validate-on-migrate: true`、`out-of-order: false`、`ateng-boot-flyway` 生产环境 `ProductionSafetyGuard` 自动拦截 `clean` 与未豁免 `DROP/TRUNCATE`；
- 超管账号初始化仍严格遵循“SQL 脱敏 + `SuperAdminBootstrapper` 动态 PBKDF2 落库”，**严禁在 SQL 中硬编码超管密码哈希**。

### 5.2 `< 100,000` 语义化确定性种子 ID 分段规范（零雪花碰撞）

> `ateng-boot-core` `Snowflake`（`DEFAULT_EPOCH = 2026-01-01`，时间戳左移 22 位）生成值恒定 `> 10^14`，因此 `< 100,000` 号段与运行期雪花 ID 数学上 **100% 零碰撞**，且大幅提升离线单测断言可读性。种子审计四字段统一使用确定性字面量 `create_by = 'SYSTEM'`、`update_by = 'SYSTEM'`、`create_time = '2026-01-01 00:00:00'`、`update_time = '2026-01-01 00:00:00'`（禁 `NOW()` 防跨时区漂移）。

| 表名 | 分配号段 | 确定性种子 ID 与核心记录 |
| :--- | :---: | :--- |
| **`sys_tenant`** | `0` | `0`：`tenant_code='000000'`、`tenant_name='平台默认租户'`、`package_id=NULL`、`expire_time=NULL`、`account_count=-1` |
| **`sys_role`** | `1 ~ 99` | `1`：`tenant_id=0`、`role_name='超级管理员'`、`role_key='super_admin'`、`data_scope=1`、`menu_check_strictly=1`、`dept_check_strictly=0`（**不为 `super_admin` 预插 `sys_role_menu`**，由超管免检与路由放行统一接管） |
| **`sys_dept`** | `100 ~ 999` | `100`：`tenant_id=0`、`parent_id=0`、`ancestors='0'`、`dept_name='平台总部'`、`sort=1`（`phone`/`email` 均为 `NULL`） |
| **`sys_menu`** | `1000 ~ 9999` | 共 **58 条**（4 `M` + 12 `C` + **42 `F`**，见 §5.3 完整明细表）；所有 `*:list` 统一作为 `F` 叶子节点，所有 `M`/`C` 节点 `perms = NULL` |
| **`sys_dict_type`** | `10001 ~ 10999` | `10001`：`sys_user_gender`（用户性别）；`10002`：`sys_yes_no`（系统是否） |
| **`sys_dict_data`** | `11001 ~ 19999` | `11001`：`sys_user_gender / 0=男 (primary, sort=1)`；`11002`：`1=女 (warning, sort=2)`；`11003`：`2=未知 (info, is_default=1, sort=3)`；`11101`：`sys_yes_no / Y=是 (primary, sort=1)`；`11102`：`N=否 (info, is_default=1, sort=2)` |
| **`sys_config`** | `20001 ~ 29999` | `20001`：`tenant_id=0`、`config_name='用户管理-初始默认密码'`、`config_key='sys.user.initPassword'`、`config_value='Ateng@2026'`、`config_type='Y'` |

### 5.3 `sys_menu` 全量 58 条确定性种子 ID 映射表（4 `M` + 12 `C` + 42 `F`）

> 规则：`M` 目录千位步进（`1000/2000/3000/4000`）；`C` 菜单百位步进（`+100`）；`F` 按钮个位顺序号（`+1..+99`）。`基础配置 (2000)` 的 `redirect` 设为 `'/system/config'`（因普通租户无 `数据字典`）。

| `id` | `parent_id` | 类型 | 标题 (`title`) | 路由名 / 路径 / 组件 / 重定向 | `perms` | `keep_alive` | `platform_only` |
| :---: | :---: | :---: | :--- | :--- | :--- | :---: | :---: |
| **`1000`** | `0` | `M` | 组织权限 | `path='/system'`, `redirect='/system/user'`, `icon='Setting'` | `NULL` | `0` | `0` |
| `1100` | `1000` | `C` | 用户管理 | `SysUser` · `/system/user` · `system/user/index` | `NULL` | `1` | `0` |
| `1101`–`1106` | `1100` | `F` | 查询/新增/修改/删除/导入/导出 | - | `system:user:{list,create,update,delete,import,export}` | `0` | `0` |
| `1200` | `1000` | `C` | 角色管理 | `SysRole` · `/system/role` · `system/role/index` | `NULL` | `1` | `0` |
| `1201`–`1204` | `1200` | `F` | 查询/新增/修改/删除 | - | `system:role:{list,create,update,delete}` | `0` | `0` |
| `1300` | `1000` | `C` | 菜单管理 | `SysMenu` · `/system/menu` · `system/menu/index` | `NULL` | `1` | **`1`** |
| `1301`–`1304` | `1300` | `F` | 查询/新增/修改/删除 | - | `system:menu:{list,create,update,delete}` | `0` | **`1`** |
| `1400` | `1000` | `C` | 部门管理 | `SysDept` · `/system/dept` · `system/dept/index` | `NULL` | `1` | `0` |
| `1401`–`1404` | `1400` | `F` | 查询/新增/修改/删除 | - | `system:dept:{list,create,update,delete}` | `0` | `0` |
| `1500` | `1000` | `C` | 岗位管理 | `SysPost` · `/system/post` · `system/post/index` | `NULL` | `1` | `0` |
| `1501`–`1504` | `1500` | `F` | 查询/新增/修改/删除 | - | `system:post:{list,create,update,delete}` | `0` | `0` |
| **`2000`** | `0` | `M` | 基础配置 | `path='/config'`, `redirect='/system/config'`, `icon='Tools'` | `NULL` | `0` | `0` |
| `2100` | `2000` | `C` | 数据字典 | `SysDict` · `/system/dict` · `system/dict/index` | `NULL` | `1` | **`1`** |
| `2101`–`2104` | `2100` | `F` | 查询/新增/修改/删除 | - | `system:dict:{list,create,update,delete}` | `0` | **`1`** |
| `2200` | `2000` | `C` | 系统参数 | `SysConfig` · `/system/config` · `system/config/index` | `NULL` | `1` | `0` |
| `2201`–`2204` | `2200` | `F` | 查询/新增/修改/删除 | - | `system:config:{list,create,update,delete}` | `0` | `0` |
| **`3000`** | `0` | `M` | 多租户治理 | `path='/tenant'`, `redirect='/system/tenant'`, `icon='Grid'` | `NULL` | `0` | **`1`** |
| `3100` | `3000` | `C` | 租户管理 | `SysTenant` · `/system/tenant` · `system/tenant/index` | `NULL` | `1` | **`1`** |
| `3101`–`3104` | `3100` | `F` | 查询/新增/修改/删除 | - | `system:tenant:{list,create,update,delete}` | `0` | **`1`** |
| `3200` | `3000` | `C` | 租户套餐 | `SysTenantPackage` · `/system/tenant-package` · `system/tenant-package/index` | `NULL` | `1` | **`1`** |
| `3201`–`3204` | `3200` | `F` | 查询/新增/修改/删除 | - | `system:tenant-package:{list,create,update,delete}` | `0` | **`1`** |
| **`4000`** | `0` | `M` | 审计安全 | `path='/audit'`, `redirect='/system/oper-log'`, `icon='Monitor'` | `NULL` | `0` | `0` |
| `4100` | `4000` | `C` | 操作日志 | `SysOperLog` · `/system/oper-log` · `system/oper-log/index` | `NULL` | `0` | `0` |
| `4101` | `4100` | `F` | 操作日志查询 | - | `system:oper-log:list` | `0` | `0` |
| `4200` | `4000` | `C` | 登录日志 | `SysLoginLog` · `/system/login-log` · `system/login-log/index` | `NULL` | `0` | `0` |
| `4201` | `4200` | `F` | 登录日志查询 | - | `system:login-log:list` | `0` | `0` |
| `4300` | `4000` | `C` | 在线会话 | `OnlineSession` · `/system/online` · `system/online/index` | `NULL` | `0` | `0` |
| `4301`–`4302` | `4300` | `F` | 在线会话查询/强退 | - | `auth:online:{list,kickout}` | `0` | `0` |
