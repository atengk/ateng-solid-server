# 认证会话、RBAC 与 `@DataScope` 数据权限规范 (auth-rbac-and-datascope-spec.md)

> **适用范围**：`ateng-solid-auth`、`ateng-solid-system` 与 `ateng-solid-bootstrap` 安全及权限链路设计。  
> **状态**：`Spec 2` 认证防爆破、不透明会话句柄、超管引导器与租户过期运行时拦截已定稿（Wayfinder 决策单定稿）。

---

## 1. 管理端认证与多账号隔离

- **账号体系与请求头**：
  - 管理端固定使用 `loginType = "admin"`，请求头名 `satoken-admin`（由 `LoginHelper.getStpLogic("admin")` 自动生成）；
  - 配置 `ateng.boot.satoken.primary-login-type=admin`、`token.is-share=false`（每次登录签发互不干扰的独立会话令牌，防止多端共享互相踢出或引发 `101007` 自杀拦截）、`interceptor.check-login=true`、`interceptor.login-type-routes.admin=/admin/**`；
  - 公开免鉴权接口（如登录 `POST /admin/auth/login`、验证码 `GET /admin/auth/captcha`）统一显式标注 `@Anonymous`；同时叠加 `ateng-boot-web` `@RateLimiter`（公开接口按 `LimitType.IP` `30 次 / 60s`；高敏写操作 `PUT /admin/auth/password` 与 `POST /admin/system/tenant` 按 `LimitType.USER` `5 次 / 60s`，`POST /admin/auth/profile/avatar` 与 `PUT /admin/system/user/{id}/reset-password` 按 `LimitType.USER` `10 次 / 60s`；前缀统一收口为 `${SOLID_REDIS_KEY_PREFIX:solid:}rate_limit:` 且 `fail-open: true`；`test` Profile 通过 `ateng.boot.web.ratelimit.enabled: false` 隔离）。
- **图形验证码（`CaptchaTemplate`）**：
  - `CaptchaTemplate` 归属 `ateng-boot-web`，由业务配置 `ateng.solid.auth.captcha-enabled`（默认可配开启）控制是否强校验图形验证码；
  - 校验统一调用 **`boolean verify(captchaKey, code)`**（一次一密原子烧毁），返回 `false` 时业务层显式抛出 `AuthResultCode(101001)`（因底座 `verifyOrThrow` 固定抛 HTTP `400`）。
- **登录校验顺序、口令安全与防爆破结构化透传（`101004`）**：
  - `POST /admin/auth/login` 严格按串行顺序校验：**图形验证码（`101001`）➔ 租户解析与可用性（`101005`，不计密码失败次数）➔ 防爆破前置检查（`101004`）➔ 账号与口令恒定耗时防枚举计数（`101002`/`101003`/`101004`）**；
  - **防计时枚举与低迭代哈希平滑升级**：账号不存在时执行合法 `310,000` 次迭代 `DUMMY_PASSWORD_HASH` 慢哈希比对确保恒定耗时；普通启用账号验密成功后若 `PasswordUtil.needsUpgrade(...)` 为 `true`，利用当次明文密码重新计算最新慢哈希并通过 `RemoteUserService#upgradePasswordHash` 回写落库（严禁改动 `pwd_update_time`）；
  - **双轨防爆破锁定检查与结构化剩余秒数透传**：
    - `PasswordAuthGrantHandler` 先检查客户端 IP 熔断（`LoginFailureLockService.isIpLocked(clientIp)` + `getIpLockTime(clientIp)`），再检查作用域账号锁定（`isLocked(tenantIdStr, "admin", username)` + `getDisableTime(tenantIdStr, "admin", username)`）；账号不存在或口令不匹配时调用 `recordFailure(tenantIdStr, "admin", username, clientIp)`，若返回 `0` 则同样按“先 IP 后账号”读取剩余锁定秒数并保底 `Math.max(1L, remainSeconds)`；
    - 在 `ateng-solid-auth` 定义专有异常子类 **`LoginLockedException extends BusinessException`**（携带 `lockType: "IP" | "ACCOUNT"` 与 `remainSeconds: long`）及 **`LoginLockDetailVO(String lockType, long remainSeconds)`**，并注册轻量 **`GlobalExceptionCustomizer`** Bean 拦截 `LoginLockedException` 返回标准 `Result<LoginLockDetailVO>`（`code = 101004`、`msg` 为友好中文倒计时文案、`data = { lockType, remainSeconds }`）；
    - 前端 `@atengk/core` 响应拦截器已自动将完整 `result` 挂载至 `BusinessException.cause`，`/login` 直接读取 `(err.cause as Result<LoginLockDetailVO>)?.data` 驱动秒级倒计时，彻底杜绝正则解析文案；`lockType === "ACCOUNT"` 时修改用户名或租户编码自动解除客户端倒计时，`lockType === "IP"` 时保持倒计时直至归零。
  - **密码复杂度与零明文跨模块传输**：
    - 密码存储与比对 100% 复用 `PasswordUtil.hash(...)` / `PasswordUtil.matches(...)`（`PBKDF2WithHmacSHA256`）；底座不提供复杂度校验，由 `ateng-solid-common` `PasswordPolicyValidator` 统一强制“8~20 位无空白且至少含 3 类字符”强口令策略；
    - 个人改密（`PUT /admin/auth/password`）强制校验新旧密码不可相同（`100001`）、旧密码核验（抛 `101006`）与新密码哈希计算全部在 `ateng-solid-auth` 内完成，仅将加密后的慢哈希通过 `RemoteUserService.updatePasswordHash` 写入数据库，杜绝明文口令跨模块传输；
    - 个人资料更新（`PUT /admin/auth/profile`）统一对齐 `UserContactAndDictSupport` 校验（昵称 2~30、邮箱 `<= 64`、合法手机号及动态 `sys_user_gender` 字典），落库后同步刷新 `LoginHelper.setLoginUser("admin", ...)` 内 `userName`；
    - 修改密码（`PUT /admin/auth/password`）、重置密码及停用/删除用户或租户时，联动写入 `pwd_update_time` 并通过 `LoginHelper` 强制注销全部活跃 `admin` 会话；
  - **登录/登出全链路审计事件（`LoginAuditEvent`）**：
    - `AuthService` 自身无持久化表、严禁标注 `@Transactional`，在登录成功（`action = "LOGIN"`）、登录失败（`action = "LOGIN"`，含 `101001` 验证码失败也尝试经 `resolveTenantIdForAudit` 解析租户，未命中记 `0L`）与注销登出（`action = "LOGOUT"`）时均调用 `DomainEventPublisher.publish(LoginAuditEvent)`；
    - `system` 异步监听器转调 `RemoteAuditLogService.recordLoginAudit` 落库 `sys_login_log`（通过上层自适应门面消费 `RedisIdempotentTemplate`，键 `audit:login:{eventId}`、24h TTL、`deleteOnFailure=true`，重复事件静默跳过；复用 `UserAgentUtil.parse` 解析 `browser`/`os`，透传 `AsyncContext` 四维快照），**仅在 `action != "LOGOUT"` 且 `status == 0 && userId != null` 时更新 `sys_user.login_ip`/`login_date`**（登出严禁覆写最后登录轨迹）。
- **可插拔认证策略 SPI (`AuthGrantHandler` · 归属 `ateng-solid-common`)**：
  - 接口与载荷（`AuthGrantCommand`、`AuthenticatedPrincipal`）位于 `io.github.atengk.solid.common.spi.auth`，使二期扩展模块（如 `ext-social`）仅依赖 `common` 即可实现并注册 Bean，满足不依赖 `auth` 实现模块的 ArchUnit 红线；
  - `AuthService` 专职按 `grantType`（缺省 `"password"`）路由到对应 `AuthGrantHandler`、签发 `satoken-admin` 会话并发布 `LoginAuditEvent`；账密专属防线完整内聚在 `PasswordAuthGrantHandler` 中。

### 1.1 在线会话不透明句柄架构 (`OpaqueSessionHandleCodec`)

- **深模块定位**：在 `ateng-solid-auth` 沉淀 `io.github.atengk.solid.auth.security.OpaqueSessionHandleCodec`，对外仅暴露 `encode(loginType, tenantId, tokenValue)`、`decode(loginType, tenantId, sessionId)`、`maskToken(tokenValue)` 3 个方法；
- **无状态 AES-256-GCM 签密 + 租户 AAD 绑定**：
  - 复用 `ateng-boot-core` `CryptoUtil.generateGcmIv()`（12 字节随机 IV）与 `CryptoUtil.encryptGcm(plaintext, aes256Key, iv, aad)` / `decryptGcm(...)`；
  - 明文为原始 `tokenValue` UTF-8 字节，附加认证数据 **`AAD = loginType + ":" + effectiveTenantId`**（如 `"admin:0"`），确保句柄密码学绑定于目标账号体系与工作租户，任何跨租户重放或篡改在 GCM Tag 校验期即 Fail-Closed 拦截；
  - 输出编码：`Base64.getUrlEncoder().withoutPadding().encodeToString(iv || cipherAndTag)`（约 86 位纯 `[A-Za-z0-9_-]` 字符，天然适配 URL Path）；
- **密钥治理与集群一致性**：
  - 密钥来源：`SolidAuthProperties.sessionHandleSecret`（配置键 `ateng.solid.auth.session-handle-secret`，经 SHA-256 派生固定 32 字节 AES-256 密钥）；
  - `application-local.example.yml` 声明 `${SOLID_AUTH_SESSION_HANDLE_SECRET:YOUR_SESSION_HANDLE_SECRET}`，`application-test.yml` 配置测试桩密钥；
  - 若运行期配置留空，构造期自动通过 `SecureRandom` 生成进程内一次性随机 32 字节密钥并打印 `WARN` 日志提醒“多实例集群部署必须配置统一 `session-handle-secret`”，**严禁硬编码固定默认密钥**；
- **`tokenMask` 与强退报错口径**：
  - `tokenMask`：前 6 位 + `****` + 后 4 位（如 `f9a2c1****7e3b`；长度 `<= 10` 时保底前 2 位 + `****` + 后 2 位）；
  - `DELETE /admin/auth/online/{sessionId}`：句柄解码/Base64URL/GCM 认证或 AAD 校验失败统一抛出 **`BusinessException(CommonResultCode.BAD_REQUEST, "无效或已失效的会话标识")`**（`code = 400`）；还原出 `realToken` 后若等于当前请求自身 `StpLogic.getTokenValue()` 则抛 **`AuthResultCode(101007)`**；随后调用 **`LoginHelper.kickoutByTokenForTenant("admin", realToken, effectiveTenantId)`**（目标已自然过期时返回 `false` 并幂等返回 `200`，跨租户越权由底座抛 `403` 与 AAD 形成双保险）。

### 1.2 超管启动引导器工程规范 (`SuperAdminBootstrapper`)

- **模块归属与生命周期**：归属 `ateng-solid-system`（`io.github.atengk.solid.system.bootstrap.SuperAdminBootstrapper`，实现 `ApplicationRunner`，单体 `bootstrap` 与后续微服务 `cloud-system` 同源共用），在 Flyway 迁移（`V1.0.0` + `V1.0.1`）完成后执行；
- **配置收口（`SolidSystemProperties.InitProperties`，前缀 `ateng.solid.init`）**：
  - `enabled`（默认 `true`，生产完成初始化后可手动关闭）；
  - `super-admin-username`（默认 `"superadmin"`）；
  - `super-admin-nickname`（默认 `"超级管理员"`）；
  - `super-admin-password`（默认 `""`；`application-prod.yml` 声明 `${SOLID_INIT_SUPER_ADMIN_PASSWORD:}` 允许留空随机引导；`application-test.yml` 固定测试桩口令 `"Ateng@Test2026"`，并同步开启 `spring.flyway.enabled: true` 供集成测试自动初始化表与种子）；
- **多实例并发幂等双防线**：
  1. 在 `TenantContextHolder.runWithTenant("0", ...)` 下检查租户 `0` 是否已存在任一绑定 `super_admin` 角色（`role_id = 1`）的未软删用户（按角色而非固定用户名判定，防止管理员改名后重复建号）；已存在则记 `INFO` 日志直接跳过；
  2. 若 Redis 可用（经上层可选自适应门面调用 `DistributedLockTemplate`），前置 **`tryExecute("init:super-admin", Duration.ofSeconds(5), ...)`** 分布式互斥锁（受 `ateng.boot.redis.key-prefix` 托管为默认 `solid:lock:init:super-admin`，Redisson 看门狗自动续期）消除多节点冷启动抢跑与重复 PBKDF2 计算，锁内二次检查；无 Redis 环境平滑直通；
  3. 独立事务方法内写入 `sys_user`（`tenant_id = 0`、`dept_id = 100`、`pwd_update_time = NULL`、`phone = NULL`、`email = NULL`）与 `sys_user_role`（`tenant_id = 0`、`role_id = 1`）；
  4. 底层无条件以 `uk_user_tenant_username_deleted` 兜底：若并发写入触发 `DuplicateKeyException`，在**事务外**重新查询租户 `0` 是否已存在 `super_admin` 角色用户——若已存在则判定为并发节点先完成初始化，记 `INFO` 幂等退出；若仍不存在则说明默认用户名已被普通账号占用，立即抛 `IllegalStateException` Fail-Fast 阻断启动；
- **初始口令校验与日志打印分流（严禁首次锁死与外部凭据泄漏）**：
  - **外部显式配置口令**（`super-admin-password` 非空）：入库前必须通过 `PasswordPolicyValidator` 复杂度校验（弱口令启动期 Fail-Fast 阻断），且启动日志**严禁打印任何口令明文或片段**；
  - **未配置口令自动随机生成**（`super-admin-password` 为空）：通过 `SecureRandom` 生成 **16 位必含大小写字母、数字、特殊字符 4 类且剔除 `0/O/o/1/I/l`** 的一次性强口令，仅在本次首次创建成功时通过独立醒目 `WARN` Banner **完整输出一次**并提示首次登录后立即改密与清理日志（落库 `pwd_update_time = NULL` 触发工作台改密横幅；后续重启不再生成或打印）。

---

## 2. 多租户开关、上下文解析与运行时拦截安全线

1. **多租户开关（默认关闭、可配置开启）**：
   - 由 `ateng.solid.tenant.enabled`（**默认 `false`**，联动 `ateng.boot.mybatis-plus.tenant.enabled`）控制；
   - 关闭时固定以平台默认租户 `0L`（`000000`）运行，认证前置端点下发 `tenantEnabled = false` 驱动前端隐藏租户入口，租户/套餐管理菜单与接口不可用。
2. **未登录入口租户识别（开启多租户时）**：
   - 管理端登录页可传 `tenantCode`（默认 `'000000'` 映射为 `tenantId = 0L`）或 `X-Tenant-Id`；
   - 在调用 `RemoteUserService` 校验用户名密码前，必须通过 `TenantContextHolder.runWithTenant(tenantId, ...)` 显式绑定目标租户上下文，防止查错租户或丢失租户隔离。
3. **登录后请求上下文、平台隔离与超管切换（`TenantHeaderSwitchValidator`）**：
   - 普通租户用户登录态下，始终锁定会话 `LoginUser.getTenantId()`，**绝对忽略**请求头伪造的 `X-Tenant-Id`；
   - `sys_tenant`、`sys_tenant_package`、`sys_menu`、`sys_dict` 管理菜单与写接口严格归属平台租户（`tenantId == 0L`），禁止分配进租户套餐；在线会话管理按当前生效租户隔离并防越权强退；
   - 仅当开启多租户且当前登录态同时满足 `LoginHelper.getTenantId("admin").equals("0")` 与角色 **`super_admin`** 时，允许通过 `X-Tenant-Id` 切换到目标有效租户视角；`TenantHeaderSwitchValidator`（运行于 `AtengSaTokenInterceptor` 内部）调用带缓存的 `RemoteTenantService.isTenantAvailable(targetTenantId)` 校验目标租户，若目标不存在、已停用或已过 `expire_time`，直接抛出 **`CommonBizResultCode(100002, "目标租户不存在、已停用或已过期，无法切换")`**（**绝不注销超管自身 `"0"` 号租户会话**），前端捕获 `100002` 后自动将 `TenantManager` 复位回 `"0"` 并刷新工作台；
   - 请求结束后在拦截器 `afterCompletion` 中强制清理 `TenantContextHolder`、`UserContextHolder` 与 `DataPermissionContextHolder`。
4. **租户停用 / 自然过期运行时拦截（`TenantAvailabilityInterceptor` + 零漂移状态缓存）**：
   - **常驻注册与拦截顺序**：归属 `ateng-solid-auth`（`io.github.atengk.solid.auth.security.TenantAvailabilityInterceptor`），通过 `AuthWebMvcConfigurer` **无条件常驻装配**于 Spring MVC **`order = 10`**（严格位于 `AtengSaTokenInterceptor` 默认 `order = 0` 之后），拦截 `/admin/**` 并排除 `/admin/auth/captcha`、`/admin/auth/login`、`/admin/auth/logout`；
   - **零漂移 `TenantAuthDTO` 状态缓存（归属 `ateng-solid-system` `RemoteTenantService` 内部）**：
     - 缓存键：领域相对键 `tenant:status:{tenantId}`（经 `ateng.boot.redis.key-prefix` 托管后默认物理键 `solid:tenant:status:{tenantId}`），TTL 由 `SolidTenantProperties.statusCacheTtl` 控制（默认 `60s`）；
     - 缓存值：`TenantAuthDTO` 紧凑 JSON（含 `status`、`expireTime`、不存在标记哨兵防穿透），**严禁仅缓存易漂移的布尔值**；启用 Redis 时经上层自适应门面直连纯 L2 **`RedisCacheTemplate`**（Redisson 防击穿互斥锁 + DCL + `NULL_SENTINEL` + Jitter TTL，彻底避免 Pub/Sub 抖动引发 L1 滞后），无 Redis 时平滑降级至本地有界缓存（上限 `10_000`）；
     - **0 秒自然过期截断**：每次校验命中缓存后在内存中实时比对 `status == 0 && (expireTime == null || expireTime.isAfter(LocalDateTime.now()))`；
     - **写路径即时驱逐**：`SysTenantService` 执行修改租户、停用租户或删除租户时，在事务提交后立即驱逐 `tenant:status:{tenantId}` 缓存键（停用与删除同步强退租户全员在线会话）；
   - **普通租户及切回单租户残留会话拦截**：当登录态非匿名、会话归属租户 `ownerTenantId != "0"` 时，若 `ateng.solid.tenant.enabled = false`（从多租户切回单租户关闭态后的历史残留会话）或开启多租户时 `RemoteTenantService.isTenantAvailable(tenantId)` 返回 `false`，`TenantAvailabilityInterceptor` 均立即调用 `LoginHelper.logout("admin")` 销毁当前失效令牌，并抛出 **`AuthResultCode(101005, "当前所属租户已停用或已过期，请联系平台管理员")`**；前端响应拦截器将已登录态收到的 `101005` 视同会话终止，清空 `AuthManager`、将 `TenantManager` 复位为 `"0"`、提示明确原因并跳转 `/login`。

---

## 3. 角色、权限码与 `@DataScope` 数据范围设计

1. **权限编码与超管收敛**：
   - 权限码采用三段式 `<域>:<资源>:<动作>`（例如 `system:user:list`、`system:user:create`、`system:user:update`、`system:user:delete`、`system:user:export`、`system:user:import`）；
   - 平台超管角色编码唯一收敛为 **`super_admin`**（全权限 `*:*:*`），**必须在 `application.yml` 显式配置 `ateng.boot.satoken.super-admin-roles: ["super_admin"]`** 覆盖底座默认 `["admin", "super_admin"]`；普通租户创建角色时校验禁止使用 `super_admin` 或 `admin` 保留编码。
   - **注解路由红线**：`ateng-boot:1.2.0` 已就绪 `#1076` 与 `#1077`（自动 `StpLogic` 路由与 `DataPermissionContextHolder` 自动同步），控制器直接采用标准 `@SaCheckPermission` / `@SaCheckRole`，严禁重复手写 `type = "admin"` 或自写补偿拦截器。
   - **权限缓存刷新与 RBAC 防提权守卫（`RbacPrivilegeGuard`）**：全局开启 `ateng.boot.redis.two-level.enabled=true` 后，`PermissionCacheService`（`ateng-boot:1.2.0` 已支持三参隔离与复合 `loginId` 解析，`#1093`/`#1164`）自动委派给 `TwoLevelCacheManager`（空间 `satoken:permissions` + `Caffeine` L1 + Redis Pub/Sub 集群失效广播），无 Redis 环境自动退化为单机有界内存适配；所有缓存清理与会话强退副作用必须通过 **`TransactionUtil.executeAfterCommit`** 挂接在事务提交后执行（完整失效与强退矩阵见 [`system-engines-and-testing-spec.md`](./system-engines-and-testing-spec.md) §1）——用户角色变更、停删必须调用 3 参 **`clearUserCache("admin", tenantId, userId)`**；角色菜单/状态/`roleKey` 变更必须通过 `RoleAuthorizationSupport#evictRoleAndUserCacheAfterCommit` 同时清理 **`clearRoleCache`** 与受影响用户的 **`user-roles`** 缓存；菜单或租户套餐变更在事务内联动裁剪越界 `sys_role_menu` 并调用 `clearAll()`。同时，`RbacPrivilegeGuard` 在角色授菜单与用户赋角色入口强制执行子集防提权、同租户归属及 `super_admin` / 自身保护。
2. **5 级 `@DataScope` 数据权限规范（`SolidDataPermissionHandler` 工程终稿）**：
   - 底座 `ateng-boot-mybatis-plus` 提供 `@DataScope` 注解与四参 SPI `DataPermissionHandler#getSqlSegment(Table table, Expression where, String mappedStatementId, DataScope dataScope)`（`#1173` 已默认开启 `failure-strategy: DENY_ALL`、自动对顶层/嵌套 `OrExpression` 写时复制加括号，并原样透传 `BusinessException`）；**`DataScopeType` 枚举归属 `ateng-solid-system-api`**（实现 `IBaseEnum<Integer>`），由 `ateng-solid-system` 注册 `SolidDataPermissionHandler` Bean；
   - **防递归自拦截与实时查询**：处理器内部查询当前用户实时 `sys_user.dept_id`（主键单点查询，避免管理员调动部门后会话缓存滞后）、当前租户有效角色（`status = 0 AND deleted = 0`）、`sys_role_dept` 与 `sys_dept` 子树时，**必须包裹在 `DataPermissionContextHolder.runWithoutPermission(...)` 内**；
   - 角色字段 `data_scope` 对应 5 级策略及并集规则：
     - 无有效角色：Fail-Closed 返回 **`1 = 0`**（`new EqualsTo(new LongValue(1L), new LongValue(0L))`）；
     - `1 / ALL`：任一角色为 `ALL` 直接返回 `null` 放行当前表全量（仍受 `tenant_id` 与 `deleted = 0` 隔离）；
     - `2 / CUSTOM`：合并 `sys_role_dept` 关联的部门 ID 至 `visibleDeptIds`；
     - `3 / DEPT`：加入当前用户实时 `currentDeptId`（若 `currentDeptId == null` 贡献空集）；
     - `4 / DEPT_AND_CHILD`：若 `currentDeptId != null`，基于 `idx_dept_tenant_ancestors (tenant_id, ancestors)` 用精确前缀条件（`id = currentDeptId OR ancestors = '{prefix}' OR ancestors LIKE '{prefix},%'`）服务端预解析子孙部门 ID 集合，**严禁 SQL 写 `FIND_IN_SET`**；并对最终 `visibleDeptIds` 施加安全上限（`ateng.solid.data-scope.max-dept-in-size = 1000`），超限抛出 `BusinessException(CommonBizResultCode.DATA_SCOPE_ERROR)`（`100003`）；
     - `5 / SELF`：置 `includeSelf = true`，匹配 `userColumn = currentUserId`（默认 `LongValue`，仅当 `userColumn` 为 `create_by` 时用 `StringValue`）；
   - **无部门用户 Fail-Closed 与多角色并集括号防卫**：当 `visibleDeptIds.isEmpty() && !includeSelf`（如无部门用户仅配 `DEPT`/`DEPT_AND_CHILD` 或 `CUSTOM` 未绑部门）时**强制返回 `1 = 0`**（严禁返回 `null`）；同时存在部门条件与 `SELF` 条件时防御性包裹 `new ParenthesedExpressionList<>(new OrExpression(deptExpr, selfExpr))`；
   - **列前缀与主表绑定规范**：列前缀安全取 `table.getAlias() != null ? table.getAlias().getName() + "." : ""`；多表 `LEFT JOIN` 必须绑驱动主表别名；`sys_user` 联查部门树时必须标注 **`@DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")`**（本人列为 `id` 而非 `user_id`）；平台超管由底座 `DataPermissionContextHolder.isSuperAdmin()` 自动秒级短路放行。

---

## 4. 操作审计与静态枚举端点

- **操作审计（`@OperateLog` 与异步安全 `SolidOperateLogStorageProvider`）**：
  - Controller 写操作方法标配 `@OperateLog(module = "...", type = OperateTypeEnum.XXX, description = "...")`（注意属性名为 `type`）；`OperateLogAspect` 在请求线程同步采集并填充 `OperateLogModel` 的 `traceId`/`tenantId`/`userId`/`userAgent`/`browser`/`os`/`description`，随后投递至异步 `taskExecutor` 调用 `OperateLogStorageProvider#store(logModel)`；
  - **异步线程补全真实 `userName` 硬约定**：因异步线程中 `SaTokenContext` 与 HTTP 请求已脱离，**严禁在 `store(logModel)` 中调用 `LoginHelper.getLoginUser()`**！正确实现是在 `SolidOperateLogStorageProvider#store` 中提取 `logModel.getTenantId()` 与 `logModel.getUserId()`，在 `TenantContextHolder.runWithTenant(..., () -> DataPermissionContextHolder.runWithoutPermission(...))` 内执行主键单点查询 `sysUserMapper.selectUsernameById(userId)` 补全真实账号名（查无则回退 `logModel.getUserName()`）并落库 `sys_oper_log`（含 `description`、`browser`、`os`、`operate_type`）。
- **静态枚举端点（上层自主接管）**：配置 `ateng.boot.web.enum.enabled=true`、`endpoint-enabled=false`（`ateng-boot#1094`）、`scan-packages=[io.github.atengk.solid, io.github.atengk.boot.core.audit]`（兼收业务枚举与底座 `OperateTypeEnum` 映射为 `operateType`），在 `ateng-solid-system` 自建 `SysEnumController` 注入 `EnumDictionaryRegistry` 暴露 `/admin/system/enums`（全量/单个/批量），标注 `@SaCheckLogin` 强制受管理端登录态保护并可按需扩展细粒度权限与租户过滤。
