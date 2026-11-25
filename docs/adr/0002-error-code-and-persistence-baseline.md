# ADR-0002: 6 位分段业务错误码与持久层零外键防冲突基线

- **状态**：`Accepted`
- **日期**：2026-11-18
- **作者**：Ateng
- **关联模块**：全域模块

---

## 1. 背景

1. `ateng-boot` 底座自身已占用 `200~503`、`40003`、`62001~62020` 等状态码，业务服务必须规划互不重叠且面向未来微服务可水平扩展的业务错误码体系。
2. 多租户与软删除表在使用唯一索引（如用户名、角色编码、字典类型）时，传统 `deleted TINYINT` 会导致同一编码删除后无法重建。

---

## 2. 核心决策

1. **6 位按服务域分段业务错误码（实现 `IResultCode`）**：
   - 通用 HTTP 语义复用 `CommonResultCode`（`200`、`400`、`401`、`403`、`404`、`409`、`429`、`500`）；
   - `100000 ~ 100999`：`ateng-solid-common`（参数/幂等/限流/数据权限/文件处理）；
   - `101000 ~ 101999`：`ateng-solid-auth`（认证/验证码/锁定/会话）；
   - `102000 ~ 102999`：`ateng-solid-system-api`（租户/套餐/用户/角色/菜单/部门/岗位/字典/参数）。
2. **持久层继承基类与防唯一键冲突模型（对齐 `ateng-boot#1134`）**：
   - 第一阶段全部 10 张核心业务/组织/配置主表统一继承 **`BaseLogicEntity`**（`deleted BIGINT NOT NULL DEFAULT 0`，逻辑删除自动将 `deleted` 更新为自身雪花 `id`；**不引入 `@Version` 乐观锁列**；`create_by`/`update_by` 为 `VARCHAR(64)`）；
   - **`BaseFullEntity`**（在 `BaseLogicEntity` 基础上追加 `@Version Integer version`，初始默认值统一为 `0`）留待二期或行业二开的高并发状态流转表按需继承；
   - 审计流水表（`sys_oper_log`、`sys_login_log`）与 5 张多对多关联表继承 **`BaseIdEntity`**；11 张多租户隔离表统一实现 **`TenantAware<Long>`**；
   - 全库字符集与排序规则统一 `utf8mb4` / `utf8mb4_0900_ai_ci`（对齐 MySQL 8.4 LTS 默认排序且兼容 H2 `MODE=MySQL`）；为规避 H2 索引名 Schema 全局唯一限制，所有索引强制携带表简名前缀（`uk_<表简名>_<字段>_deleted` / `idx_<表简名>_<字段>`，如 `uk_user_tenant_username_deleted`、`idx_oper_log_tenant_trace`）。
3. **无物理外键与 `ateng-boot-flyway`**：
   - 全库表严禁创建 `FOREIGN KEY`，关联一致性由 Service 层与事务保证；
   - 使用 `ateng-boot-flyway`（`bootstrap` 显式引入 `org.flywaydb:flyway-mysql` `runtime` 方言包）管理版本化 DDL 与 `< 100,000` 确定性种子数据，生产环境自动开启 `clean` 与高危 DDL 硬熔断。
