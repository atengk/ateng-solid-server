# 数据库建模与 Flyway 迁移规范 (database-and-flyway-spec.md)

> **适用范围**：`ateng-solid-server` 全库 DDL 设计、实体映射与 Flyway 迁移脚本编写。

---

## 1. 全局命名与字段类型铁律

1. **字符集与引擎（MySQL 8.x）**：统一使用 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`；H2 测试脚本需保持标准兼容（`MODE=MySQL`）。
2. **表与索引命名**：
   - 全小写蛇形命名；系统域核心表统一 `sys_` 前缀；
   - 主键 `id BIGINT NOT NULL`（64 位雪花主键 `IdType.ASSIGN_ID`）；
   - 普通索引 `idx_<列名>`；防软删冲突联合唯一索引 `uk_<业务列>_deleted`。
3. **公共基类列映射（对齐 `ateng-boot#1134` 重构体系）**：
   - **`BaseLogicEntity` 表必填审计与软删列（常规主表默认首选，无强制乐观锁）**：
     - `create_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM'`
     - `create_time DATETIME NOT NULL`
     - `update_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM'`
     - `update_time DATETIME NOT NULL`
     - `deleted BIGINT NOT NULL DEFAULT 0`（软删除由 MyBatis-Plus `@TableLogic(value="0", delval="id")` 回填自身 `id`）
     - > **底座填充边界提示**：`DefaultMetaObjectHandler#insertFill` 仅自动填充审计四字段与 `tenantId`，**不会填充 `deleted` 与 `version`**；普通单条 `insert` 依赖 DDL 显式声明 `NOT NULL DEFAULT 0`（或构造期初值）。
   - **`BaseFullEntity` 表追加乐观锁列（仅需并发 CAS 防覆盖表按需继承）**：
     - `version INT NOT NULL DEFAULT 0`（与重构后的 `BatchUtil` 默认初始值 `0` 严格对齐）
   - **多租户隔离表追加列**：`tenant_id BIGINT NOT NULL DEFAULT 0`（平台默认租户 ID `0`，实体实现 **`TenantAware<Long>`**）。
   - **零物理外键**：严禁使用 `FOREIGN KEY` 或级联外键约束。

---

## 2. 首期核心表清单与域归属

### 2.1 平台共享表（列入 `ignore-tables`，不走行级租户过滤）
| 表名 | 继承基类 | 核心字段与唯一索引约定 |
| :--- | :--- | :--- |
| `sys_tenant` | `BaseLogicEntity` | `id`（默认租户为 `0`）、`tenant_code`（唯一索引 `(tenant_code, deleted)`，默认 `'000000'`）、`tenant_name`、`package_id`、`expire_time`（`NULL` 永久有效）、`account_count`（`-1` 不限 / `>= 1` 上限）、`status`、`remark` |
| `sys_tenant_package` | `BaseLogicEntity` | `package_name`（唯一索引 `(package_name, deleted)`）、`menu_check_strictly`（`TINYINT NOT NULL DEFAULT 1`）、`status`、`remark` |
| `sys_tenant_package_menu` | `BaseIdEntity` | `(package_id, menu_id)` 关联唯一键（无软删与审计冗余字段） |
| `sys_menu` | `BaseLogicEntity` | `parent_id`、`menu_type` (`M`/`C`/`F`)、`title`、`name`、`path`、`component`、`redirect`、`perms`、`icon`、`sort`、`visible`、`status`、`keep_alive`、`is_external`、`platform_only`（`TINYINT NOT NULL DEFAULT 0`，标记平台专属菜单与按钮） |
| `sys_dict_type` | `BaseLogicEntity` | `dict_name`、`dict_type`（唯一索引 `(dict_type, deleted)`）、`status`、`remark` |
| `sys_dict_data` | `BaseLogicEntity` | `dict_type`、`dict_label`、`dict_value`、`tag_type`、`css_class`、`sort`、`is_default`、`status`、`remark` |

### 2.2 租户隔离表（强制行级 `tenant_id` 过滤，实体实现 `TenantAware<Long>`）
| 表名 | 继承基类 | 核心字段与唯一索引约定 |
| :--- | :--- | :--- |
| `sys_dept` | `BaseLogicEntity` | `tenant_id`、`parent_id`、`ancestors`（逗号分隔祖先路径，如 `0,100`）、`dept_name`、`sort`、`leader_user_id`、`phone`、`email`、`status` |
| `sys_post` | `BaseLogicEntity` | `tenant_id`、`post_code`（唯一索引 `(tenant_id, post_code, deleted)`）、`post_name`、`sort`、`status`、`remark` |
| `sys_role` | `BaseLogicEntity` | `tenant_id`、`role_name`、`role_key`（唯一索引 `(tenant_id, role_key, deleted)`，**禁止普通租户使用 `super_admin`/`admin`**）、`data_scope`、`menu_check_strictly`（`TINYINT NOT NULL DEFAULT 1`）、`dept_check_strictly`（`TINYINT NOT NULL DEFAULT 0`）、`sort`、`status`、`remark` |
| `sys_user` | `BaseLogicEntity` | `tenant_id`、`dept_id`、`username`（唯一索引 `(tenant_id, username, deleted)`）、`nickname`、`password`（PBKDF2 慢哈希 `VARCHAR(128)`）、`pwd_update_time`（`DATETIME NULL`，新建/重置置空触发改密提醒）、`email`、`phone`、`gender`、`avatar`、`status`、`login_ip`、`login_date`、`remark` |
| `sys_config` | `BaseLogicEntity` | `tenant_id`、`config_name`、`config_key`（唯一索引 `(tenant_id, config_key, deleted)`）、`config_value`、`config_type`（`'Y'` 系统内置 / `'N'` 业务自建）、`remark` |
| `sys_user_role` / `sys_user_post` / `sys_role_menu` / `sys_role_dept` | `BaseIdEntity` | 均携带 `tenant_id BIGINT NOT NULL DEFAULT 0` 随业务租户隔离，承载多对多绑定关系，不跨模块关联 |
| `sys_oper_log` | `BaseIdEntity` | `tenant_id`、`trace_id`、`user_id`、`user_name`、`module`、`operate_type`（`INT NOT NULL`，对齐 `OperateTypeEnum.code`）、`request_method`、`request_url`、`client_ip`、`operate_location`、`browser`（`VARCHAR(64) NULL`）、`os`（`VARCHAR(64) NULL`）、`request_params`、`response_result`、`status`、`cost_time`、`error_msg`、`extra_json`、`operate_time` |
| `sys_login_log` | `BaseIdEntity` | `tenant_id`、`trace_id`、`username`、`login_type`、`client_ip`、`login_location`、`browser`、`os`、`status`、`msg`、`login_time` |

---

## 3. Flyway 迁移与双模配置规范

1. **目录与版本命名（兼容单体全量建表与微服务按域并存分库）**：
   - 首期仅 `system` 拥有持久化表（`auth` **零自建持久化表**），脚本**直接随领域置于 `ateng-solid-system/src/main/resources/db/migration/system/`**，保障单体 `ateng-solid-bootstrap` 与后续 `ateng-solid-cloud-system` 100% 共享同一份建表与种子脚本，由各自装配层挂载 `spring.flyway.locations`；
   - 命名遵循 `V<主>.<次>.<补丁>__<域>_<语义描述>.sql`（如 `V1.0.0__system_init_schema.sql`、`V1.0.1__system_init_seed.sql`）；后续新增带库表的扩展模块（`ext-*` / `biz-*`）或独立业务服务域时，须自带 `classpath:db/migration/<module>/` 并配置独立迁移源与独立历史表（`table` 隔离），**严禁把扩展表混写进内核初始 SQL，严禁直接向内核主表加字段**（扩展字段统一建 `1:1` 子表关联）。
   - 已发布或合入 `dev`/`main` 的历史脚本**严禁修改内容**，任何字段变更必须追加新版本脚本。
2. **H2 与 MySQL 双模兼容与安全初始化约定**：
   - 避免使用 H2 `MODE=MySQL` 不支持的 MySQL 专属语法（如 `ON UPDATE CURRENT_TIMESTAMP`、自定义存储过程、特殊字符集前缀语法）；时间字段由 MyBatis-Plus `DefaultMetaObjectHandler` 统一填充；
   - `V1.0.1__system_init_seed.sql` 仅初始化默认租户（`id = 0`、`tenant_code = '000000'`）、`super_admin` 角色、初始菜单与动态字典等非敏感元数据，**严禁在 SQL 脚本中硬编码超管密码哈希**；初始超管账号与 PBKDF2 口令由 `ateng-solid-bootstrap` 的启动引导器（`ApplicationRunner`）按 `ateng.solid.init.super-admin-password`（未配置则首次启动随机生成 16 位强口令并打印一次；`test` Profile 使用固定测试桩口令）动态写入；
   - 多租户默认关闭（`ateng.solid.tenant.enabled=false`、`ateng.boot.mybatis-plus.tenant.enabled=false`），但全库表仍保留 `tenant_id BIGINT NOT NULL DEFAULT 0` 与租户表结构，开启即可零改表切换 SaaS 多租户。
3. **`ateng-boot-flyway` 安全配置**：
   - 保持 `validate-on-migrate: true`、`out-of-order: false`；
   - 生产 Profile 严禁开启 `clean`（由 `ateng-boot-flyway` `ProductionSafetyGuard` 自动熔断保护）。
4. **第一阶段 `V1.0.1__system_init_seed.sql` 确定性初始化清单**：
   - **默认租户与组织角色**：`sys_tenant`（`id=0`, `tenant_code='000000'`, `account_count=-1`, `expire_time=NULL`）、`sys_dept`（`id=100`, `tenant_id=0`, `ancestors='0'`, `dept_name='平台总部'`）、`sys_role`（`id=1`, `tenant_id=0`, `role_key='super_admin'`, `data_scope=1`）；
   - **初始菜单树（`sys_menu`）**：包含 4 个领域目录（`M`）、12 个页面菜单（`C`）与对应 `F` 权限按钮，其中`多租户治理`目录及下属`租户管理`（`/system/tenant`）、`租户套餐`（`/system/tenant-package`），以及`菜单管理`（`/system/menu`）、`数据字典`（`/system/dict`）及其全部子 `F` 按钮标记为 `platform_only = 1`；
   - **内置动态字典与系统参数**：`sys_user_gender`（`0=男`、`1=女`、`2=未知 [is_default=1]`）、`sys_yes_no`（`Y=是`、`N=否 [is_default=1]`）与 `sys.user.initPassword = 'Ateng@2026'`（`config_type='Y'`）。完整矩阵核对见根仓 `docs/specs/phase1-coding-readiness-checklist.md`。
