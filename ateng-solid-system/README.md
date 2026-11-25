# ateng-solid-system 系统管理域核心业务实现模块

> 一句话定位：实现 `ateng-solid-system-api` 全部跨模块契约并承载组织权限、5 级 `@DataScope` 数据权限引擎、多租户、字典参数、审计落库及 `db/migration/system/` Flyway 脚本（接口前缀 `/admin/system/**`）；当前已完成 `Spec 1` 基础设施基线、`Spec 2` 认证安全契约底座及 `Spec 3` 组织架构（部门/岗位）、权限树（菜单/角色）与 `@DataScope` 数据权限后端闭环，用户全功能及多租户/字典参数 CRUD 将于后续 `Spec 3 ~ Spec 4` 逐步落地。

---

## 0. 对外契约、核心引擎与持久化全景速查 (`Spec 1 ~ Spec 3`)

| 能力域 | 产物 / 入口 | 核心机制 / SPI / 配置键 | 核心输入 / 输出与边界约定 | 锚定验证测试 (`*Test.java`) |
| :--- | :--- | :--- | :--- | :--- |
| 系统配置基线 | [`SolidSystemProperties`](./src/main/java/io/github/atengk/solid/system/properties/SolidSystemProperties.java) | `ateng.solid.{init,data-scope,user,cache}` · [`SolidTenantProperties`](../ateng-solid-common/src/main/java/io/github/atengk/solid/common/properties/SolidTenantProperties.java) (`ateng.solid.tenant.*`) | 内聚超管引导、部门 IN 上限、导入治理与缓存 TTL；`toString` 屏蔽初始口令 | [`ConfigurationBaselineIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ConfigurationBaselineIntegrationTest.java) |
| Flyway 建表与种子 | [`V1.0.0`](./src/main/resources/db/migration/system/V1.0.0__system_init_schema.sql) · [`V1.0.1`](./src/main/resources/db/migration/system/V1.0.1__system_init_seed.sql) | 17 张表 + `< 100,000` 确定性号段 | `utf8mb4_0900_ai_ci`、无外键；内置租户 `0`、角色 `1`、部门 `100`、58 条菜单与 2 组字典（无密码哈希） | [`FlywaySchemaAndSeedIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/FlywaySchemaAndSeedIntegrationTest.java) |
| 持久层实体与 Mapper | `io.github.atengk.solid.system.{entity,mapper}` | 17 实体（`BaseLogicEntity`/`BaseIdEntity` + `` `TenantAware<Long>` ``）+ 17 `` `AtengBaseMapper<T>` `` | `SysUser`/`SysDept` 标注 `@FieldEncrypt(deterministic = true)`；`SysUserRoleMapper#selectActiveRolesByUserId`（含 `data_scope`）与引用批量查 | [`ContactFieldEncryptPersistenceTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ContactFieldEncryptPersistenceTest.java) |
| 5 级 `@DataScope` 数据权限引擎 | [`SolidDataPermissionHandler`](./src/main/java/io/github/atengk/solid/system/security/SolidDataPermissionHandler.java) | `DataPermissionHandler` · `ObjectProvider<Mapper>` · `ateng.solid.data-scope.max-dept-in-size` | 支持 `ALL`/`CUSTOM`/`DEPT`/`DEPT_AND_CHILD`/`SELF` 并集加括号、`ancestors` 前缀解析、超限抛 `100003` 与无角色/无部门 Fail-Closed `1 = 0` | [`SolidDataPermissionHandlerTest`](./src/test/java/io/github/atengk/solid/system/security/SolidDataPermissionHandlerTest.java) · [`DataScopeH2SqlIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/DataScopeH2SqlIntegrationTest.java) |
| 部门管理 (`/admin/system/dept`) | [`SysDeptController`](./src/main/java/io/github/atengk/solid/system/controller/SysDeptController.java) · [`SysDeptService`](./src/main/java/io/github/atengk/solid/system/service/SysDeptService.java) · [`SysDeptQueryService`](./src/main/java/io/github/atengk/solid/system/service/SysDeptQueryService.java) | `/tree`、`GET /options` (`@SaCheckLogin`)、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`DELETE /{id}` · `system:dept:*` | 祖先回溯筛选；`options` 脱敏隐去联系明文；变更上级防自环 `100001` 与 `BatchUtil` 批量级联重算 `ancestors`；子部门/用户占用阻断 `102005` | [`OrgAndRbacIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacIntegrationTest.java) · [`OrgAndRbacMockMvcTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacMockMvcTest.java) |
| 岗位管理 (`/admin/system/post`) | [`SysPostController`](./src/main/java/io/github/atengk/solid/system/controller/SysPostController.java) · [`SysPostService`](./src/main/java/io/github/atengk/solid/system/service/SysPostService.java) | `/page`、`GET /options` (`@SaCheckLogin`)、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`DELETE /{ids}` · `system:post:*` | 同租户编码/名称唯一校验 `102002`；批量删除校验 `sys_user_post`（关联未删除用户）拦截 `102005` 并点名岗位名称 | [`OrgAndRbacIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacIntegrationTest.java) · [`OrgAndRbacMockMvcTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacMockMvcTest.java) |
| 菜单管理 (`/admin/system/menu`) | [`SysMenuController`](./src/main/java/io/github/atengk/solid/system/controller/SysMenuController.java) · [`SysMenuService`](./src/main/java/io/github/atengk/solid/system/service/SysMenuService.java) | `/tree`、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`DELETE /{id}` · `system:menu:*` | 严格锁定平台默认租户 `0`（非 `0` 抛 `403`）；M/C/F 拓扑及降级守卫 `100001`；`platformOnly` 收紧级联裁剪；提交后 `clearAll()` | [`OrgAndRbacIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacIntegrationTest.java) · [`OrgAndRbacEdgeIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacEdgeIntegrationTest.java) |
| 角色管理 (`/admin/system/role`) | [`SysRoleController`](./src/main/java/io/github/atengk/solid/system/controller/SysRoleController.java) · [`SysRoleService`](./src/main/java/io/github/atengk/solid/system/service/SysRoleService.java) · [`SysRoleQueryService`](./src/main/java/io/github/atengk/solid/system/service/SysRoleQueryService.java) · [`RoleAuthorizationSupport`](./src/main/java/io/github/atengk/solid/system/service/RoleAuthorizationSupport.java) | `/page`、`/options` (`/menu-tree` 套餐交集)、`/{id}`、`POST`、`PUT`、`PUT /{id}/status`、`PUT /{id}/data-scope`、`DELETE /{ids}` | 禁用保留编码 `102004`；保护 `super_admin` `102007`；套餐越界 `102006`（兼容半选目录）；`CUSTOM` 至少 1 部门 `100001`；提交后 3 参 `clearRoleCache` | [`OrgAndRbacIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacIntegrationTest.java) · [`OrgAndRbacEdgeIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/OrgAndRbacEdgeIntegrationTest.java) |
| 超管启动引导器 | [`SuperAdminBootstrapper`](./src/main/java/io/github/atengk/solid/system/bootstrap/SuperAdminBootstrapper.java) | `ApplicationRunner` · `ateng:solid:init:super-admin:lock` (30s) | `role_id=1` 绑定判重跳过；外部强口令校验 Fail-Fast；留空生成 16 位无歧义随机口令；`DuplicateKeyException` 事务外复核 | [`SuperAdminBootstrapperIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/SuperAdminBootstrapperIntegrationTest.java) |
| 租户服务、零漂移缓存与切头校验 | [`RemoteTenantServiceImpl`](./src/main/java/io/github/atengk/solid/system/service/remote/RemoteTenantServiceImpl.java) · [`TenantStatusCache`](./src/main/java/io/github/atengk/solid/system/support/TenantStatusCache.java) · [`TenantHeaderSwitchValidatorImpl`](./src/main/java/io/github/atengk/solid/system/security/TenantHeaderSwitchValidatorImpl.java) | `RemoteTenantService` · `TenantHeaderSwitchValidator` · `ateng:solid:tenant:status:{tenantId}` (TTL 60s) | Redis 紧凑 JSON + 负向哨兵 + 本地 10,000 上限降级；读时 0 秒过期截断；单租户禁切与异常租户抛 `100002` 且不注销超管会话 | [`TenantStatusCacheTest`](./src/test/java/io/github/atengk/solid/system/support/TenantStatusCacheTest.java) · [`RemoteServicesIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/RemoteServicesIntegrationTest.java) |
| 权限服务与加载器 SPI | [`RemotePermissionServiceImpl`](./src/main/java/io/github/atengk/solid/system/service/remote/RemotePermissionServiceImpl.java) · [`SolidUserRolePermissionLoader`](./src/main/java/io/github/atengk/solid/system/security/SolidUserRolePermissionLoader.java) | `RemotePermissionService` · `UserRolePermissionLoader` 3 参重载 | 隔离非 `admin` 体系；停用祖先级联剪枝、`platform_only=1` 拦截、`!tenantEnabled` 隐藏多租户目录与空 `M` 裁剪；超管返回 `["*:*:*"]` | [`RemoteServicesIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/RemoteServicesIntegrationTest.java) |
| 用户远程服务与头像引擎 | [`RemoteUserServiceImpl`](./src/main/java/io/github/atengk/solid/system/service/remote/RemoteUserServiceImpl.java) · [`AvatarStorageHelper`](./src/main/java/io/github/atengk/solid/system/support/AvatarStorageHelper.java) · [`AvatarUrlResolver`](./src/main/java/io/github/atengk/solid/system/support/AvatarUrlResolver.java) | `RemoteUserService` · `StorageOperations` · `StorageGuard` | 超管跨租户切换摘要解析；空白转 `NULL`/冲突抛 `102002`；头像强魔数守卫 `100004` + 回滚删除 + 2h `inline` 预签名 | [`AvatarUrlResolverTest`](./src/test/java/io/github/atengk/solid/system/support/AvatarUrlResolverTest.java) · [`RemoteServicesIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/RemoteServicesIntegrationTest.java) |
| 联系字段确定性加密与可选 Redis 开关 | [`ContactFieldCrypto`](./src/main/java/io/github/atengk/solid/system/support/ContactFieldCrypto.java) · [`RedisActivationResolver`](./src/main/java/io/github/atengk/solid/system/support/RedisActivationResolver.java) | `ateng.boot.mybatis-plus.crypto.secret-key` · `ateng.boot.redis.enabled` | 空白转 `null`/邮箱小写归一化与单值批量确定性加密；`redis.enabled=false` 时统一抑制误连 `localhost:6379` | [`ContactFieldCryptoTest`](./src/test/java/io/github/atengk/solid/system/support/ContactFieldCryptoTest.java) · [`ContactFieldEncryptPersistenceTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ContactFieldEncryptPersistenceTest.java) |
| 登录审计落库与异步监听 | [`RemoteAuditLogServiceImpl`](./src/main/java/io/github/atengk/solid/system/service/remote/RemoteAuditLogServiceImpl.java) · [`LoginAuditEventListener`](./src/main/java/io/github/atengk/solid/system/listener/LoginAuditEventListener.java) | `RemoteAuditLogService` · `@Async @EventListener` (`LoginAuditEvent`) | 按 `eventId` 执行 24h 幂等去重；解析浏览器/OS；仅登录成功同步回写最近登录 IP 与时间 | [`RemoteServicesIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/RemoteServicesIntegrationTest.java) |

---

## 1. 模块定位与依赖边界

- **依赖拓扑约束**：本模块实现 `ateng-solid-system-api` 契约，单向依赖 `ateng-solid-common` 与 `ateng-solid-system-api`，**严禁**反向依赖 `ateng-solid-auth`；对外 Controller 严禁暴露持久层 `Entity`，`Service` 层严禁依赖 `jakarta.servlet.*`。
- **持久层继承基类矩阵**：
  - 10 张核心主表继承 `BaseLogicEntity`（`deleted BIGINT NOT NULL DEFAULT 0`，逻辑删除回填自身雪花主键 `id`）：`SysTenant`、`SysTenantPackage`、`SysMenu`、`SysDictType`、`SysDictData`、`SysDept`、`SysPost`、`SysRole`、`SysUser`、`SysConfig`；
  - 7 张关联与只追加审计日志表继承 `BaseIdEntity`：`SysTenantPackageMenu`、`SysUserRole`、`SysUserPost`、`SysRoleMenu`、`SysRoleDept`、`SysOperLog`、`SysLoginLog`；
  - 上述 11 张租户隔离表统一实现 `` `TenantAware<Long>` ``，17 个 Mapper 统一继承 `` `AtengBaseMapper<T>` ``。

---

## 2. 核心架构与数据流转

```mermaid
flowchart LR
    Auth["ateng-solid-auth (通过 Remote*Service 与领域事件解耦)"] --> TenantSvc["RemoteTenantServiceImpl<br/>➔ TenantStatusCache"]
    Auth --> UserSvc["RemoteUserServiceImpl<br/>➔ AvatarStorageHelper / AvatarUrlResolver"]
    Auth --> PermSvc["RemotePermissionServiceImpl<br/>➔ SolidUserRolePermissionLoader"]
    Auth -. "DomainEventPublisher.publish(LoginAuditEvent)" .-> AuditLsn["@Async LoginAuditEventListener<br/>➔ RemoteAuditLogServiceImpl"]
    SaToken["AtengSaTokenInterceptor / CachedStpInterface"] --> SwitchSPI["TenantHeaderSwitchValidatorImpl (100002)"]
    SaToken --> PermSPI["SolidUserRolePermissionLoader (3参)"]
    MP["AtengDataPermissionInnerInterceptor"] --> DataPerm["SolidDataPermissionHandler (ObjectProvider<Mapper> · Fail-Closed)"]
    WebCtrl["Sys{Dept,Post,Menu,Role}Controller"] --> AppSvc["Query/Write Service ➔ TransactionUtil.executeAfterCommit"]
    AppSvc --> Cache["PermissionCacheService (3参 clearRoleCache/clearUserCache · clearAll)"]
```

- **动态路由菜单四重剪枝顺序**：归属租户校验（非超管禁跨租户）➔ 非平台租户状态及激活套餐校验 ➔ 全量树自顶向下级联剪枝（停用祖先、`platform_only=1`、单租户模式隐藏 `3000` 多租户治理目录）➔ 剥离 `F` 按钮并裁剪空 `M` 目录。

---

## 3. 核心业务域实现（配置 · 调用 · 边界闭环）

### 3.1 平台超管启动引导 (`SuperAdminBootstrapper`)

**1. 专属配置片段**

```yaml
ateng:
  solid:
    init:
      enabled: true                  # 是否启用首次启动自动检查并引导创建平台超级管理员
      super-admin-username: superadmin # 平台默认租户 0 内超管账号
      super-admin-nickname: 超级管理员   # 展示昵称
      super-admin-password: ${SOLID_INIT_SUPER_ADMIN_PASSWORD:} # 留空首次建号生成 16 位强随机口令
```

**2. 启动引导核心调用流**

```java
// Spring Boot 启动完成后按 ApplicationRunner 自动调度（无需手工调用）
// 1. 以 role_id = 1（super_admin）绑定关系判重：已存在有效超管即记录 INFO 日志幂等跳过（改名亦不重插）
// 2. 启用 Redis 时抢占 30s 锁 ateng:solid:init:super-admin:lock（Lua 比对令牌原子释放）
// 3. TransactionTemplate 单短事务写 sys_user(tenant_id=0, dept_id=100, pwd_update_time=null) 与 sys_user_role(role_id=1)
```

**3. 边界与防御**
- 外部注入非空口令须通过 `PasswordPolicyValidator` 校验，不合格立即抛出 `IllegalStateException` 且**严禁日志或异常打印原始口令**；留空自动生成不含 `0/O/o/1/I/l` 的 16 位强口令且仅在首次创建时通过独立 `WARN` Banner 输出一次。
- 发生 `DuplicateKeyException` 时在事务回滚之外二次复查绑定状态：并发超管胜出则静默跳过，普通账号占用默认名则 Fail-Fast 抛 `IllegalStateException`。

### 3.2 多租户缓存、切换防越权与动态路由

**1. 专属配置片段**

```yaml
ateng:
  boot:
    satoken:
      primary-login-type: admin
      super-admin-roles: [super_admin]
      tenant:
        header-name: X-Tenant-Id
        allow-anonymous-header: false
        allow-super-admin-switch: true
        platform-tenant-ids: ["0"]
  solid:
    tenant:
      enabled: false          # false=单租户开箱模式；true=开启 SaaS 行级隔离与跨租户切换
      status-cache-ttl: 60s   # 缓存有效期；读取时仍实时校对 expireTime 实现 0 秒截断
```

**2. 契约调用示例**

```java
// 1. 认证前/切租户校验：缓存命中紧凑 JSON 后实时执行 TenantAuthDTO#isAvailableAt(LocalDateTime.now())
boolean available = remoteTenantService.isTenantAvailable(targetTenantId);

// 2. 登录工作台与动态路由拉取（按生效视角自动裁剪平台专属、未入选套餐与停用目录）
List<RouteMenuDTO> menus = remotePermissionService.getRouteMenusByUserId(
        ownerTenantId, effectiveTenantId, userId, isSuperAdmin, tenantProperties.isEnabled()
);
```

**3. 边界与防御**
- `TenantStatusCache` 对不存在租户写入空哨兵 `{}` 防缓存穿透；当 `ateng.boot.redis.enabled=false` 时自动切换容量上限 `10,000` 的本地有界并发缓存（由 `RedisActivationResolver` 协同屏蔽默认 `6379` 探测）。
- `TenantHeaderSwitchValidatorImpl` 在单租户模式下拒绝一切非 `0` 切换请求，在多租户模式下拦截非法格式、匿名头与不可用租户并抛出 `100002`（`INVALID_TENANT_SWITCH`），**绝不调用注销接口**，保护超管 `"0"` 会话在线。

### 3.3 联系字段加密、个人资料维护与头像预览签发

**1. 专属配置片段**

```yaml
ateng:
  boot:
    mybatis-plus:
      crypto:
        secret-key: ${SOLID_CRYPTO_SECRET_KEY:YOUR_CRYPTO_SECRET_KEY}
    storage:
      type: s3
      guard:
        enabled: true
        strict-magic-check: true
        allowed-extensions: [jpg, jpeg, png, webp]
        max-file-size: 2MB
```

**2. 契约调用示例**

```java
// 1. 联系字段归一化写入与 LambdaUpdateWrapper 清空绑定
remoteUserService.updatePersonalProfile(tenantId, userId, UpdatePersonalProfileCommand.builder()
        .nickname("平台运营").phone("13800138000").email("ops@example.com").gender("1").build());

// 2. 头像安全上传（返回相对对象键与 2h inline 预签名地址，Storage 关闭时自动降级对象键）
AvatarUploadDTO uploaded = remoteUserService.uploadAvatar(tenantId, userId, cmd);
String previewUrl = avatarUrlResolver.resolve(uploaded.getObjectPath());
```

**3. 边界与防御**
- `SysUser.phone`/`email` 严禁发起 `LIKE` 模糊检索或 `ORDER BY`；`LambdaQueryWrapper` 传入 `ContactFieldCrypto.normalize*`，`QueryWrapper` 与 `LambdaUpdateWrapper#set` 传入 `ContactFieldCrypto.encrypt*ForQuery`；个人资料同租户撞手机号或小写归一化撞邮箱抛 `102002`。
- `RemoteUserServiceImpl#uploadAvatar` 由 `AvatarStorageHelper` 前置执行扩展名、体积与二进制魔数守卫（拒接抛 `100004`）；随后于单事务内调用 `uploadWithRollbackDelete` 落盘相对对象键，并在提交后注册 `deleteAfterCommit` 安全删旧非外链键。
- `RemoteUserServiceImpl#getAdminProfile` 仅放行平台已绑定 `super_admin` 角色的账号按切换后 `effectiveTenantId` 查摘要，普通账号跨租户一概拒绝并抛 `102001`。

### 3.4 异步登录审计与领域事件监听

**1. 专属配置片段**

```yaml
spring:
  threads:
    virtual:
      enabled: true   # 异步事件监听（@Async）自动依托虚拟线程及 AsyncContext 快照透传 traceId 与租户上下文
```

**2. 领域事件与落库调用示例**

```java
// 认证域发布标准领域事件（无需直接依赖 system 模块）
domainEventPublisher.publish(LoginAuditEvent.builder()
        .tenantId(tenantId).traceId(traceId).userId(userId).username("admin")
        .loginType("admin").clientIp("10.0.0.12").userAgent(userAgent)
        .status(LogStatusEnum.SUCCESS.getCode()).message("登录成功").build());
```

**3. 边界与防御**
- `LoginAuditEventListener` 对异步执行异常记 `error` 日志并隔离，绝不因审计故障阻塞登录或登出响应。
- `RemoteAuditLogServiceImpl` 通过 `eventId` 实施 24 小时幂等去重（DB 失败回滚释放去重标记），安全截断超长列防止 SQL 截断报错，并仅在认证成功（`status = 0`）且 `userId != null` 时更新用户最近登录 IP 与时间。

### 3.5 5 级 `@DataScope` 数据权限引擎 (`SolidDataPermissionHandler`)

**1. 专属配置片段**

```yaml
ateng:
  boot:
    mybatis-plus:
      data-permission:
        enabled: true
        failure-strategy: DENY_ALL   # 数据权限解析失败严格阻断访问
  solid:
    data-scope:
      max-dept-in-size: 1000         # DEPT_AND_CHILD / 并集可见部门 IN 集合安全上限（超限抛 100003）
```

**2. 注解绑定与策略示例**

```java
// 用户联查部门树标准驱动表别名声明（本人列为 u.id）
@DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")
List<Long> selectVisibleUserIds();
```

**3. 边界与防御**
- 处理器构造器全部通过 `ObjectProvider<Mapper>` 延迟解析，打破 `SqlSessionFactory -> MybatisPlusInterceptor -> DataPermissionHandler -> Mapper` 启动期循环依赖；内部全部检索包裹在 `DataPermissionContextHolder.runWithoutPermission` + `TenantContextHolder.runWithTenant` 内严防自拦截递归与跨租户越权。
- 5 级策略按多角色并集解析：`ALL` 任一命中即放行（返回 `null`，仍受 `tenant_id` 与 `deleted = 0` 保护）；`DEPT_AND_CHILD` 通过精确 `ancestors` 前缀条件（`ancestors = '{prefix}' OR ancestors LIKE '{prefix},%'`）服务端预解析，禁用 `FIND_IN_SET`；`SELF` 按 `userColumn = "create_by"` 走 `StringValue`，其余走 `LongValue`；部门 `IN` 与 `SELF` 并存时显式包裹 `ParenthesedExpressionList(OrExpression)`；无有效角色或无可用部门/本人权限严格 Fail-Closed 返回 `1 = 0`。

### 3.6 组织架构（部门/岗位）与权限树（菜单/角色）闭环

**1. 专属配置与控制器路由总览**

```yaml
ateng:
  solid:
    tenant:
      enabled: false   # false 时 /admin/system/role/menu-tree 与权限树同步隐藏 3000 多租户治理菜单
```

```
GET/POST/PUT/DELETE  /admin/system/dept/{tree,options,{id},{id}/status}
GET/POST/PUT/DELETE  /admin/system/post/{page,options,{id},{id}/status,{ids}}
GET/POST/PUT/DELETE  /admin/system/menu/{tree,{id},{id}/status}      # 严格锁定平台租户 0
GET/POST/PUT/DELETE  /admin/system/role/{page,options,menu-tree,{id},{id}/status,{id}/data-scope,{ids}}
```

**2. 核心写路径与事务提交后缓存联动示例**

```json
PUT /admin/system/role/2001/data-scope
{
  "dataScope": 2,
  "deptCheckStrictly": false,
  "deptIds": ["100", "101"]
}
```

**3. 边界与防御**
- **部门（`SysDeptService` / `SysDeptQueryService`）**：变更上级严防选自身或子孙（`100001`），事务内调用 `BatchUtil.updateBatch` 级联重算子孙 `ancestors`；联系人更新通过 `LambdaUpdateWrapper#set` 显式传 `ContactFieldCrypto.encrypt*ForQuery` 支持置 `NULL` 清空；`/options` 下拉树屏蔽手机号与邮箱；停用含未停用子部门或删除含子部门/活跃用户一律抛出 `102005`（用户数查询关闭 `@DataScope` 防限权误删）。
- **岗位（`SysPostService`）**：同租户下 `postCode` 与 `postName` 唯一（`102002`）；批量删除经 `SysUserPostMapper.selectReferencedPostIds`（关联 `u.deleted = 0`）校验，任一岗位被活跃用户引用即整批拦截（`102005`）并点名具体岗位名称。
- **菜单（`SysMenuService`）**：全部读写接口强制校验平台租户 `0`（非 `0` 抛 `403`）；严格校验 `M`/`C`/`F` 拓扑、`perms` 三段式正则、外链 `^https?://.+`、路由组件 `name` 全树唯一（`102002`）与节点降级冲突（`100001`）；`platformOnly` 由 `false` 收紧为 `true` 时级联收紧子树并裁剪非平台绑定；删停/改写成功后于 `TransactionUtil.executeAfterCommit` 触发 `permissionCacheService.clearAll()`。
- **角色（`SysRoleService` / `SysRoleQueryService`）**：禁用保留编码 `super_admin`/`admin`（`102004`）；平台内置 `super_admin` 角色禁止改停删与改数据范围（`102007`）；`/options` 非平台超管视角隐藏 `super_admin`；普通租户 `/menu-tree` 与 `createRole`/`updateRole` 严格校验套餐边界及 `platformOnly=false`（越界抛 `102006`，并兼容 `menu_check_strictly=0` 半选套餐回传的父目录半选节点）；变更菜单/状态/删除提交后调用 3 参 `clearRoleCache("admin", tenantId, roleKey)`，若同时修改 `roleKey` 则联动清理绑定用户的 `user-roles` 缓存。

---

## 4. 扩展点 (SPI) 与非功能防线

| 扩展点 / 防线 | 实现类 | 核心职责与并发/安全约束 |
| :--- | :--- | :--- |
| 底座 `TenantHeaderSwitchValidator` | [`TenantHeaderSwitchValidatorImpl`](./src/main/java/io/github/atengk/solid/system/security/TenantHeaderSwitchValidatorImpl.java) | 校验 `X-Tenant-Id` 与 `ateng.solid.tenant.enabled`，拒绝非法切换抛 `100002` |
| 底座 `UserRolePermissionLoader` | [`SolidUserRolePermissionLoader`](./src/main/java/io/github/atengk/solid/system/security/SolidUserRolePermissionLoader.java) | 实现单参/双参/三参重载，隔离 `admin` 体系并桥接 `CachedStpInterface` 二级缓存 |
| 底座 `DataPermissionHandler` | [`SolidDataPermissionHandler`](./src/main/java/io/github/atengk/solid/system/security/SolidDataPermissionHandler.java) | 5 级数据权限 SQL AST 改写，`ObjectProvider` 解耦 Bean 循环，Fail-Closed `1 = 0` |
| 缓存、锁与事务后置副作用防线 | [`TenantStatusCache`](./src/main/java/io/github/atengk/solid/system/support/TenantStatusCache.java) · [`SuperAdminBootstrapper`](./src/main/java/io/github/atengk/solid/system/bootstrap/SuperAdminBootstrapper.java) · [`RoleAuthorizationSupport`](./src/main/java/io/github/atengk/solid/system/service/RoleAuthorizationSupport.java) | `ReentrantLock` 保护有界本地缓存与去重表；Lua Token 原子锁释放；全部缓存失效严格挂接 `TransactionUtil.executeAfterCommit` |

---

## 5. 完整配置清单、错误码与关联文档附录

### 5.1 本模块核心配置清单 (`application.yml`)

| 配置键 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `ateng.solid.tenant.enabled` | `false` | SaaS 多租户开关（联动切换校验与租户菜单显隐） |
| `ateng.solid.tenant.status-cache-ttl` | `60s` | 租户状态缓存有效期（读取时仍实时校对 `expireTime`） |
| `ateng.solid.init.enabled` | `true` | 是否启动超级管理员自动初始化引导 |
| `ateng.solid.init.super-admin-username` | `superadmin` | 首次启动默认平台超管账号 |
| `ateng.solid.init.super-admin-nickname` | `超级管理员` | 首次启动默认平台超管昵称 |
| `ateng.solid.init.super-admin-password` | `""` | 外置超管初始口令（留空生成 16 位强随机口令并 WARN 打印一次） |
| `ateng.solid.data-scope.max-dept-in-size` | `1000` | 部门 IN 子查询最大部门集合上限 |
| `ateng.solid.user.{import-max-rows,max-concurrent-imports,import-feedback-ttl}` | `500` / `2` / `15m` | 批量导入行数上限、并发信号量与批注暂存有效期 |
| `ateng.solid.cache.{dict-ttl,config-ttl}` | `30m` / `30m` | 字典与参数缓存 TTL |

### 5.2 本模块触发错误码清单

| 错误码 | 枚举常量 | 触发场景 |
| :--- | :--- | :--- |
| `100001` | `CommonBizResultCode.PARAM_INVALID` | 部门/菜单防自环与 `M/C/F` 拓扑/降级错误、禁用上级下启用子部门、自定数据权限 `CUSTOM` 部门为空、非法状态或个人资料空入参 |
| `100002` | `CommonBizResultCode.INVALID_TENANT_SWITCH` | 跨租户头 `X-Tenant-Id` 目标不存在、已停用、已过期、匿名带头或单租户模式越权切租户 |
| `100003` | `CommonBizResultCode.DATA_SCOPE_ERROR` | `@DataScope` 解析后可见部门数超过 `ateng.solid.data-scope.max-dept-in-size` 上限 |
| `100004` | `CommonBizResultCode.FILE_GUARD_REJECTED` | 上传头像为空、非 `jpg/jpeg/png/webp`、超 2MB 或魔数不匹配 |
| `102001` | `SystemResultCode.RECORD_NOT_FOUND` | 目标部门、岗位、菜单、角色、用户或租户记录不存在 |
| `102002` | `SystemResultCode.DUPLICATE_RESOURCE` | 同级部门重名、同租户岗位编码/名称重复、全树路由组件名重复、同租户角色编码/名称重复或个人资料手机号/邮箱冲突 |
| `102004` | `SystemResultCode.RESERVED_ROLE_KEY` | 创建或修改角色使用保留编码 `super_admin` 或 `admin` |
| `102005` | `SystemResultCode.RESOURCE_IN_USE` | 停用含未停用子部门；删除仍存在子节点、套餐/角色引用或活跃用户绑定的部门/岗位/菜单/角色 |
| `102006` | `SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED` | 普通租户角色授权越界勾选套餐外或 `platform_only=true` 菜单、单租户授予多租户治理菜单 |
| `102007` | `SystemResultCode.SYSTEM_BUILTIN_PROTECTED` | 试图编辑、停用、变更数据范围或删除平台内置 `super_admin` 角色 |

### 5.3 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 接口与错误码明细：[api-and-error-codes-spec.md](../docs/specs/api-and-error-codes-spec.md)
- 数据库与 Flyway 规范：[database-and-flyway-spec.md](../docs/specs/database-and-flyway-spec.md)
- 认证、RBAC 与 `@DataScope` 规范：[auth-rbac-and-datascope-spec.md](../docs/specs/auth-rbac-and-datascope-spec.md)
- 系统域核心引擎与测试契约：[system-engines-and-testing-spec.md](../docs/specs/system-engines-and-testing-spec.md)
- 确定性加密决策记录：[ADR-0004](../docs/adr/0004-deterministic-field-encryption-and-search-boundary.md)
