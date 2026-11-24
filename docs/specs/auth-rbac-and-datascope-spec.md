# 认证会话、RBAC 与 `@DataScope` 数据权限规范 (auth-rbac-and-datascope-spec.md)

> **适用范围**：`ateng-solid-auth`、`ateng-solid-system` 与 `ateng-solid-bootstrap` 安全及权限链路设计。

---

## 1. 管理端认证与多账号隔离

- **账号体系与请求头**：
  - 管理端固定使用 `loginType = "admin"`，请求头名 `satoken-admin`（由 `LoginHelper.getStpLogic("admin")` 自动生成）；
  - 配置 `ateng.boot.satoken.primary-login-type=admin`、`interceptor.check-login=true`、`interceptor.login-type-routes.admin=/admin/**`；
  - 公开免鉴权接口（如登录 `POST /admin/auth/login`、验证码 `GET /admin/auth/captcha`）统一显式标注 `@Anonymous`。
- **图形验证码（`CaptchaTemplate`）**：
  - `CaptchaTemplate` 归属 `ateng-boot-web`，由业务配置 `ateng.solid.auth.captcha-enabled`（默认可配开启）控制是否强校验图形验证码；
  - 校验统一调用 **`boolean verify(captchaKey, code)`**（一次一密原子烧毁），返回 `false` 时业务层显式抛出 `AuthResultCode(101001)`（因底座 `verifyOrThrow` 固定抛 HTTP `400`）。
- **登录校验顺序、口令安全与防爆破**：
  - `POST /admin/auth/login` 严格按串行顺序校验：**图形验证码（`101001`）➔ 租户解析与可用性（`101005`）➔ 防爆破前置检查（`LoginFailureLockService.isLocked` + `getDisableTime` 自抛 `101004` 含剩余秒数，避免底座 `checkLocked` 直接抛 `403`）➔ 账号与口令统一防枚举计数（`101002`/`101003`）**；
  - 密码存储与比对 100% 复用 `PasswordUtil.hash(...)` / `PasswordUtil.matches(...)`（`PBKDF2WithHmacSHA256`）；底座不提供复杂度校验，由 `ateng-solid` 统一强制“8~20 位无空白且至少含 3 类字符”强口令策略；
  - 防爆破调用 `LoginFailureLockService` 的 `(tenantId, "admin", account, clientIp)` 多租户作用域重载（`ateng-boot:1.2.0` 已就绪，严禁手动拼串绕过）；
  - 修改密码（`PUT /admin/auth/password`）、重置密码及停用/删除用户或租户时，联动写入 `pwd_update_time` 并通过 `LoginHelper` 强制注销全部活跃 `admin` 会话；
  - 在线会话列表（`/admin/auth/online/page`）禁止下发原始 `tokenValue`，仅下发 `tokenMask` 与不可认证的不透明句柄 `sessionId`，强退通过 `LoginHelper.kickoutByTokenForTenant` 校验租户边界；
  - 登录/登出事件统一发布领域事件交由 `RemoteAuditLogService` 异步落库 `sys_login_log`（复用底座 `UserAgentUtil.parse` 解析 `browser`/`os`，透传 `AsyncContext` 四维快照）。
- **可插拔认证策略 SPI (`AuthGrantHandler`)**：
  - 核心 `auth` 模块通过 `AuthGrantHandler` 策略接口解耦认证方式（首期仅内置 `password` 账密登录实现），`AuthService` 按请求 `grantType` 自动匹配容器中已注册的 Handler Bean；
  - 后续接入 `ext-social`（OAuth2）或短信登录时仅需实现该接口并注册为 Spring Bean，**零修改 `auth` 核心源码**。

---

## 2. 多租户开关、上下文解析与超管切租户安全线

1. **多租户开关（默认关闭、可配置开启）**：
   - 由 `ateng.solid.tenant.enabled`（**默认 `false`**，联动 `ateng.boot.mybatis-plus.tenant.enabled`）控制；
   - 关闭时固定以平台默认租户 `0L`（`000000`）运行，认证前置端点下发 `tenantEnabled = false` 驱动前端隐藏租户入口，租户/套餐管理菜单与接口不可用。
2. **未登录入口租户识别（开启多租户时）**：
   - 管理端登录页可传 `tenantCode`（默认 `'000000'` 映射为 `tenantId = 0L`）或 `X-Tenant-Id`；
   - 在调用 `RemoteUserService` 校验用户名密码前，必须通过 `TenantContextHolder.runWithTenant(tenantId, ...)` 显式绑定目标租户上下文，防止查错租户或丢失租户隔离。
3. **登录后请求上下文、平台隔离与超管切换**：
   - 普通租户用户登录态下，始终锁定会话 `LoginUser.getTenantId()`，**绝对忽略**请求头伪造的 `X-Tenant-Id`；
   - `sys_tenant`、`sys_tenant_package`、`sys_menu`、`sys_dict` 管理菜单与写接口严格归属平台租户（`tenantId == 0L`），禁止分配进租户套餐；在线会话管理按当前生效租户隔离并防越权强退；
   - 仅当开启多租户且当前登录态同时满足 `LoginHelper.getTenantId("admin").equals("0")` 与角色 **`super_admin`** 时，允许通过 `X-Tenant-Id` 校验存在性并切换到目标有效租户视角；
   - 请求结束后在拦截器 `afterCompletion` 中强制清理 `TenantContextHolder`、`UserContextHolder` 与 `DataPermissionContextHolder`。

---

## 3. 角色、权限码与 `@DataScope` 数据范围设计

1. **权限编码与超管收敛**：
   - 权限码采用三段式 `<域>:<资源>:<动作>`（例如 `system:user:list`、`system:user:create`、`system:user:update`、`system:user:delete`、`system:user:export`、`system:user:import`）；
   - 平台超管角色编码唯一收敛为 **`super_admin`**（全权限 `*:*:*`），**必须在 `application.yml` 显式配置 `ateng.boot.satoken.super-admin-roles: ["super_admin"]`** 覆盖底座默认 `["admin", "super_admin"]`；普通租户创建角色时校验禁止使用 `super_admin` 或 `admin` 保留编码。
   - **注解路由红线**：`ateng-boot:1.2.0` 已就绪 `#1076` 与 `#1077`（自动 `StpLogic` 路由与 `DataPermissionContextHolder` 自动同步），控制器直接采用标准 `@SaCheckPermission` / `@SaCheckRole`，严禁重复手写 `type = "admin"` 或自写补偿拦截器。
   - **权限缓存刷新与多租户隔离策略**：依托 `PermissionCacheService`（`ateng-boot:1.2.0` 已支持三参隔离与复合 `loginId` 解析，`#1093`/`#1164`）与 `SaTokenClusterSyncListener` 实现集群广播失效——用户角色变更、停删必须调用 3 参 **`clearUserCache("admin", tenantId, userId)`**；角色菜单变更必须调用 3 参 **`clearRoleCache("admin", tenantId, roleKey)`**；菜单或租户套餐变更在事务内联动裁剪越界 `sys_role_menu` 并调用 `clearAll()`。
2. **5 级 `@DataScope` 数据权限规范**：
   - 底座 `ateng-boot-mybatis-plus` 仅提供 `@DataScope` 注解与 `DataPermissionHandler` SPI，**`DataScopeType` 枚举由 `ateng-solid-common` 自建（实现 `IBaseEnum<Integer>`）** 并在 `ateng-solid-system` 实现 `DataPermissionHandler` Bean：
   - 角色字段 `data_scope` 对应枚举 `DataScopeType`：
     - `1 / ALL`：全部数据权限（不追加过滤）；
     - `2 / CUSTOM`：自定部门数据权限（基于 `sys_role_dept` 构造 `dept_id IN (...)`）；
     - `3 / DEPT`：本部门数据权限（`dept_id = currentDeptId`）；
     - `4 / DEPT_AND_CHILD`：本部门及以下数据权限（由服务端依据 `sys_dept.ancestors` 预解析为有界 `dept_id IN (...)`，**禁止 SQL 中写 `FIND_IN_SET` 破坏 H2 兼容与索引**）；
     - `5 / SELF`：仅本人数据权限（`user_id = currentUserId` 或 `create_by` 归属过滤）。
   - 多角色叠加时取**权限并集**（例如同时拥有 `DEPT` 与 `SELF`，按 `OR` 拼接或合并可见部门集合）；若其中包含 `ALL`，直接短路放行。
   - 平台超管通过 `DataPermissionContextHolder.isSuperAdmin()` 短路放行。

---

## 4. 操作审计与静态枚举端点

- **操作审计（`@OperateLog`）**：Controller 写操作方法标配 `@OperateLog(module = "...", type = OperateTypeEnum.XXX)`（注意属性名为 `type`）；`OperateLogAspect` 已同步填充 `OperateLogModel` 的 `userAgent`/`browser`/`os`，在 `system` 模块实现 `OperateLogStorageProvider` SPI 时须从 `LoginHelper.getLoginUser()` 补全真实 `userName` 并异步写入 `sys_oper_log`。
- **静态枚举端点（上层自主接管）**：配置 `ateng.boot.web.enum.enabled=true`、`endpoint-enabled=false`（`ateng-boot#1094`）、`scan-packages=io.github.atengk.solid`，在 `ateng-solid-system` 自建 `SysEnumController` 注入 `EnumDictionaryRegistry` 暴露 `/admin/system/enums`（全量/单个/批量），标注 `@SaCheckLogin` 强制受管理端登录态保护并可按需扩展细粒度权限与租户过滤。
