# 管理端接口契约与业务错误码明细规范 (api-and-error-codes-spec.md)

> **适用范围**：`ateng-solid-auth`、`/admin/auth/**` 与 `ateng-solid-system`、`/admin/system/**` 对外接口定义，与前端 `ateng-solid-admin-web/src/api/*` 保持 1:1 强类型对齐。
> **前置约定**：所有响应统一包装于 `Result<T>`；列表查询无匹配返回空数组 `[]`；主键与外键（含 `tenantId`）JSON 一律序列化为 `string`；Controller 严禁直接接收或返回 ORM `Entity`。

---

## 1. 认证与会话域 (`/admin/auth/**` · 错误码 `101xxx`)

| 端点 | 方法 | 鉴权 | 核心请求模型 | 核心响应模型 (`Result<T>`) | 说明 |
| :--- | :---: | :---: | :--- | :--- | :--- |
| `/admin/auth/captcha` | `GET` | `@Anonymous` | 无 | `CaptchaVO` (`enabled`, `tenantEnabled`, `captchaKey`, `imageBase64`, `expireSeconds`) | 复用 `CaptchaTemplate`，同时透传 `tenantEnabled` 开关控制前端租户显隐 |
| `/admin/auth/login` | `POST` | `@Anonymous` | `AdminLoginRequest` (`tenantCode`, `username`, `password`, `captchaKey`, `captchaCode`, `grantType?`) | `AdminLoginVO` (`tokenName`, `tokenValue`, `expireIn`, `tenantId`, `tenantName`) | 校验验证码、租户、防爆破与 PBKDF2 口令，签发 `satoken-admin`；触发 `101004` 时经 `GlobalExceptionCustomizer` 在 `Result.data` 透传 `LoginLockDetailVO` (`{ lockType: "IP" \| "ACCOUNT", remainSeconds }`) |
| `/admin/auth/logout` | `POST` | `@SaCheckLogin` | 无 | `Void` | 注销当前 `admin` 会话并发布登出审计事件 |
| `/admin/auth/info` | `GET` | `@SaCheckLogin` | 无 | `AdminProfileVO` (`userId`, `tenantId`, `tenantName`, `tenantEnabled`, `username`, `nickname`, `avatar`, `roles`, `permissions`, `passwordNeedsChange`, `loginIp`, `loginDate`) | 返回当前登录态轻量身份、全局多租户开关、改密提醒标志与扁平权限码数组 |
| `/admin/auth/routes` | `GET` | `@SaCheckLogin` | 无 | `List<RouteMenuVO>` | 返回当前用户可见的嵌套目录/菜单树（`M`/`C`，切租户时按 `platform_only` 过滤） |
| `/admin/auth/profile` | `GET` | `@SaCheckLogin` | 无 | `PersonalProfileVO` (`userId`, `tenantId`, `tenantName`, `username`, `nickname`, `email`, `phone`, `gender`, `avatar`, `deptName`, `postNames`, `roles`, `loginIp`, `loginDate`, `createTime`, `passwordNeedsChange`) | 个人中心读取完整未脱敏（内存真明文）个人资料 |
| `/admin/auth/profile` | `PUT` | `@SaCheckLogin` | `UpdateProfileRequest` (`nickname`, `email`, `phone`, `gender`) | `Void` | 更新本人基本资料（同租户校验手机号/邮箱唯一性 `102002`） |
| `/admin/auth/profile/avatar` | `POST` | `@SaCheckLogin` | `MultipartFile file`（`<=2MB`, `jpg/jpeg/png/webp`） | `AvatarUploadVO` (`avatarUrl`, `objectPath`) | 个人中心上传头像并更新本人 `sys_user.avatar`（魔数检验防伪装 `100004`） |
| `/admin/auth/password` | `PUT` | `@SaCheckLogin` | `UpdatePasswordRequest` (`oldPassword`, `newPassword`) | `Void` | 校验旧口令并更新 PBKDF2 慢哈希与 `pwd_update_time`，同步注销该用户全部 `admin` 会话 |
| `/admin/auth/online/page` | `GET` | `auth:online:list` | `OnlineSessionQuery` (`username?`, `clientIp?`, `PageQuery`) | `PageResult<OnlineSessionVO>` (`sessionId`, `tokenMask`, `currentSession`, `userId`, `username`, `nickname`, `tenantId`, `loginDevice`, `clientIp`, `loginTime`, `tokenTimeout`) | 分页查看当前租户在线会话；**禁止下发原始 `tokenValue`** |
| `/admin/auth/online/{sessionId}` | `DELETE` | `auth:online:kickout` | 路径参数 `sessionId`（无状态 AES-256-GCM + 租户 AAD Base64URL 签密句柄） | `Void` | 句柄损坏/篡改/跨租户 AAD 不匹配返 `400`；拦截自身会话抛 `101007`；调 `kickoutByTokenForTenant` 强退（目标已自然过期幂等返 `200`） |

---

## 2. 系统管理域 (`/admin/system/**` · 错误码 `102xxx`)

| 资源域 | 路径前缀 | 典型接口与权限码 | 核心说明 |
| :--- | :--- | :--- | :--- |
| **静态枚举** | `/admin/system/enums` | `GET /admin/system/enums`、`GET /admin/system/enums/{name}`、`GET / POST /admin/system/enums/batch` (`<=100` 项) | 关闭底座内置端点（`endpoint-enabled=false`），由 `ateng-solid-system` `SysEnumController` 注入 `EnumDictionaryRegistry` 接管并施加 `@SaCheckLogin` 鉴权与 Bean Validation 校验 |
| **租户管理** | `/admin/system/tenant` | `GET /page` (`system:tenant:list`)、`GET /options` (`@SaCheckLogin` 平台超管)、`GET /{id}`、`POST` (`create`，`@Idempotent` 返回 `CreateTenantVO`)、`PUT` (`update`)、`PUT /{id}/status`、`DELETE /{ids}` (`<=100` 个) | 仅在开启多租户且平台默认租户（`tenantId = 0`）可见与维护；禁止停用/删除/篡改租户 `0`（`102003`）；`account_count`（`-1` 不限）仅新增/启用/导入用户时增量拦截；停用/删除/改过期时间失效同步分批强退租户全员会话 |
| **租户套餐** | `/admin/system/tenant-package` | `GET /page` (`system:tenant-package:list`)、`GET /options` (`system:tenant-package:list \| system:tenant:{list,create,update}` `OR`)、`GET /{id}`、`POST /shrink-preview` (`update`)、`POST` (`create`，`@Idempotent`)、`PUT` (`update`)、`PUT /{id}/status`、`DELETE /{ids}` (`<=100` 个) | 仅平台租户 `0` 维护，禁止勾选 `platform_only = 1` 菜单（`102006`）；缩减菜单时提供影响预览并在事务内联动裁剪关联租户 `sys_role_menu` 并刷新缓存 |
| **用户管理** | `/admin/system/user` | `GET /page` (`system:user:list`)、`GET /options` (`system:dept:create \| system:dept:update \| system:user:list` `OR`，上限 `200` 条)、`GET /{id}`、`POST` (`create`，`@Idempotent`)、`PUT` (`update`)、`PUT /{id}/status`、`PUT /{id}/reset-password`、`DELETE /{ids}` (`<=100` 个)、`GET /import/template`、`POST /import` (`import`，`@Idempotent` 文件指纹)、`GET /import/feedback/{feedbackToken}`、`POST /export` (`export`，`<=50,000` 行) | 全路径联动 `@DataScope`（含 `/options` 与导入写范围校验）；`user/options` 供部门负责人与选人场景；Fesod 500 行三段式并发导入（`ImportResultVO` 剔除 `rowData` + 15 分钟归属隔离批注下载）与 50,000 行流式导出；个人头像已收敛至 `/admin/auth/profile/avatar` |
| **角色管理** | `/admin/system/role` | `GET /page` (`system:role:list`)、`GET /options` (`@SaCheckLogin`)、`GET /menu-tree` (`system:role:{list,create,update}` `OR`)、`GET /{id}`、`POST` (`@Idempotent`)、`PUT`、`PUT /{id}/status`、`PUT /{id}/data-scope`、`DELETE /{ids}` (`<=100` 个) | 禁用保留编码 `super_admin`/`admin`（`102004`）；`uk_role_tenant_name_deleted` 联合唯一；`RbacPrivilegeGuard` 子集防提权与内置 `super_admin` 保护（`102007`）；变动经 `evictRoleAndUserCacheAfterCommit` 双清缓存 |
| **菜单管理** | `/admin/system/menu` | `GET /tree` (`@Valid`、`system:menu:list`)、`GET /{id}`、`POST` (`@Idempotent`)、`PUT`、`PUT /{id}/status`、`DELETE /{id}` | 仅平台租户 `0` 维护；`menuType` 仅限 `M`/`C`/`F`；深度上限 `10` 层；含 `platformOnly` 标记；引用统计过滤已删租户（`sys_tenant.deleted = 0`），存在子菜单或被活跃角色/套餐引用时禁止删除（`102005`） |
| **部门管理** | `/admin/system/dept` | `GET /tree` (`@Valid`、`system:dept:list`)、`GET /options` (`@SaCheckLogin`)、`GET /{id}`、`POST` (`@Idempotent`)、`PUT`、`PUT /{id}/status`、`DELETE /{id}` | 维护 `ancestors`（`<=500` 字符、深度 `<= 10` 层）并禁含 `/`；`/options` 隐去联系方式；禁选自身/子孙防自环与禁用负责人（`100001`）；删除前校验无子部门、活跃用户及独占绑定 `CUSTOM` 角色（`102005`） |
| **岗位管理** | `/admin/system/post` | `GET /page` (`system:post:list`)、`GET /options`、`GET /{id}`、`POST` (`@Idempotent`)、`PUT`、`PUT /{id}/status`、`DELETE /{ids}` (`<=100` 个) | 联合唯一索引 `(tenant_id, post_code, deleted)`；被用户引用禁止删除（`102005`） |
| **数据字典** | `/admin/system/dict` | `GET /type/page`、`GET /type/options`、`GET /type/{id}`、`POST /type` (`@Idempotent`)、`PUT /type`、`PUT /type/{id}/status`、`DELETE /type/{ids}` (`<=100` 个)、`GET /data/page`、`GET /data/{id}`、`GET /data/type/{dictType}` (`@SaCheckLogin`)、`POST /data` (`@Idempotent`)、`PUT /data`、`PUT /data/{id}/status`、`DELETE /data/{ids}` (`<=100` 个) | 平台租户 `0` 维护，`dictType` 编辑锁定只读，`isDefault` 同类型互斥；`GET /data/type/{dictType}` 对全租户登录开放供 `DictManager` 缓存消费 |
| **系统参数** | `/admin/system/config` | `GET /page` (`system:config:list`)、`GET /{id}`、`GET /key/{configKey}`（控制器 `@SaCheckLogin` + 服务层三级鉴权）、`POST` (`@Idempotent`)、`PUT`、`DELETE /{ids}` (`<=100` 个) | 租户隔离配置项；`/key/{configKey}` 对 `public.*` 放行登录态、`sys.user.initPassword` 放行查/建/改用户或建租户 OR 权限、其余需 `system:config:list`；内置参数（`configType = 'Y'`）禁止修改键名与删除（`102007`）；`sys.user.initPassword` 修改强口令校验 |
| **审计日志** | `/admin/system/log` | `GET /oper/page` (`system:oper-log:list`)、`GET /oper/{id}`、`GET /login/page` (`system:login-log:list`) | 严格只读分页查询（按时间倒序）与操作日志详情（`browser`/`os` 及脱敏 JSON） |

---

## 3. 6 位分段业务错误码字典

| 域 | 号段 | 枚举类 | 预留核心错误码示例 |
| :--- | :---: | :--- | :--- |
| **`common`** | `100000 ~ 100999` | `CommonBizResultCode` | `100001` 业务参数格式非法（自环、`ancestors > 500` 或树深度 `> 10`、`F` 节点挂接错误、自定部门未勾选、批量主键超过 `100` 个、导出条数超过 `50,000` 行或时间反序）、`100002` 非法租户切换目标（`TenantHeaderSwitchValidator` 拦截超管切至失效租户，不注销超管自身 `"0"` 会话）、`100003` 数据权限配置异常、`100004` 上传文件非合法图片魔数/类型或大小超限、`100005` Excel 文件格式、超 `500` 行或表头校验失败 |
| **`auth`** | `101000 ~ 101999` | `AuthResultCode` | `101001` 验证码无效或已过期、`101002` 用户名或密码错误（防枚举统一提示）、`101003` 账号已停用、`101004` 连续失败已触发防爆破锁定（`Result.data` 附带 `LoginLockDetailVO { lockType, remainSeconds }`）、`101005` 所属租户不存在/已停用或已过期（登录及运行时 `TenantAvailabilityInterceptor` 拦截普通租户并注销会话）、`101006` 旧密码校验失败、`101007` 禁止强退当前自身会话 |
| **`system`** | `102000 ~ 102999` | `SystemResultCode` | `102001` 记录不存在、`102002` 编码/用户名/手机号/邮箱已存在、`102003` 禁止停用/删除/篡改平台默认租户、`102004` 禁止使用保留角色编码（`super_admin`/`admin`）、`102005` 存在子节点/关联引用禁止停用或删除、`102006` 超出租户套餐账号配额或菜单范围（含禁止套餐勾选 `platform_only`）、`102007` 禁止删除/禁用超管账号角色、登录用户自身或内置系统参数 |

---

## 4. `ateng-solid-system-api` 跨模块契约与领域事件精确签名（`Spec 1` 定稿）

### 4.1 通用铁律
- 所有 `Remote*Service` 接口方法统一直接返回裸对象/集合/布尔值，**严禁返回 `Result<T>`**（由 `ArchitectureGuardTest` 规则 8 强制校验）；
- 所有依赖租户隔离的契约方法均**显式传入 `Long tenantId`**，`system` 实现侧统一通过 `TenantContextHolder.runWithTenant(String.valueOf(tenantId), ...)` 显式定界，严禁隐式依赖调用方线程上下文；
- 查找单条记录不存在时返回 `null`（防登录枚举）或按语义抛出 `BusinessException(SystemResultCode.RECORD_NOT_FOUND)`，集合查询无匹配保底返回空集合 `Collections.emptyList()`；
- **零明文密码跨模块传输**：个人改密时旧密码验证（抛 `AuthResultCode(101006)`）与新密码复杂度校验/PBKDF2 慢哈希全部在 `ateng-solid-auth` 完成，仅将慢哈希串经 `updatePasswordHash` 交由 `system` 落库。

### 4.2 4 个 `Remote*Service` 精确方法清单（共 16 方法）

| 契约接口 | 方法签名 | 返回空/异常约定与核心职责 |
| :--- | :--- | :--- |
| **`RemoteUserService`** | `AdminAuthUserDTO getAuthUserByUsername(Long tenantId, String username)` | 不存在返回 `null`；含 `userId`, `tenantId`, `deptId`, `username`, `nickname`, `passwordHash`, `status`, `pwdUpdateTime`, `loginIp`, `loginDate` |
| | `AdminAuthUserDTO getAuthUserById(Long tenantId, Long userId)` | 不存在返回 `null`；供改密核对旧哈希 |
| | `AdminProfileDTO getAdminProfile(Long tenantId, Long userId)` | 不存在抛 `102001`；返回 `/admin/auth/info` 轻量摘要（含动态签发 `inline` `avatarUrl`、`tenantName`、`pwdUpdateTime`、`loginIp`、`loginDate`） |
| | `PersonalProfileDTO getPersonalProfile(Long tenantId, Long userId)` | 不存在抛 `102001`；返回 `/admin/auth/profile` 完整内存真明文资料（含 `email`/`phone`/`gender`/`deptName`/`postNames`/`roles`/`avatarUrl`/`createTime`） |
| | `void updatePersonalProfile(Long tenantId, Long userId, UpdatePersonalProfileCommand cmd)` | 校验昵称（2~30）、手机号/邮箱格式、动态 `sys_user_gender` 字典（`100001`）与同租户查重（`102002`） |
| | `AvatarUploadDTO uploadAvatar(Long tenantId, Long userId, UploadAvatarCommand cmd)` | 入参 `originalFilename`/`contentType`/`byte[] content`（零 `MultipartFile`）；事务外魔数校验 + 上传新对象 ➔ `TransactionTemplate` 短事务加 `forUpdate` 锁更新 `sys_user.avatar` ➔ 事务失败静默补偿删除新对象 / 提交后删除旧对象，返回 `inline` 预签名 `avatarUrl` |
| | `void updatePasswordHash(Long tenantId, Long userId, String encodedPassword, LocalDateTime pwdUpdateTime)` | 落库新 PBKDF2 慢哈希与 `pwd_update_time` |
| | `void upgradePasswordHash(Long tenantId, Long userId, String encodedPassword)` | 登录验密成功后平滑升级低迭代 PBKDF2 慢哈希（不改动 `pwd_update_time`） |
| **`RemoteTenantService`** | `TenantAuthDTO getTenantByCode(String tenantCode)` | 不存在返回 `null`；含 `tenantId`, `tenantCode`, `tenantName`, `packageId`, `expireTime`, `status` |
| | `TenantAuthDTO getTenantById(Long tenantId)` | 不存在返回 `null` |
| | `boolean isTenantAvailable(Long tenantId)` | 校验存在、未删、`status == 0` 且未过 `expireTime` |
| **`RemotePermissionService`** | `List<String> getRoleKeysByUserId(Long tenantId, Long userId)` | 无匹配返回 `[]`；对接底座 `UserRolePermissionLoader` |
| | `List<String> getPermissionsByRoleKey(Long tenantId, String roleKey)` | 无匹配返回 `[]`；支持租户维度角色权限隔离缓存 |
| | `List<String> getDirectPermissionsByUserId(Long tenantId, Long userId)` | 首期保底返回 `[]` |
| | `List<RouteMenuDTO> getRouteMenusByUserId(Long ownerTenantId, Long effectiveTenantId, Long userId, boolean superAdmin, boolean tenantEnabled)` | 无匹配返回 `[]`；在 `system` 内统一完成 `M`/`C` 树组装、停用剪枝、`platform_only` 与 `tenantEnabled` 过滤 |
| **`RemoteAuditLogService`** | `void recordLoginAudit(LoginAuditCommand cmd)` | 幂等写入 `sys_login_log`（复用 `UserAgentUtil.parse` 解析 `browser`/`os`）；仅 `action != "LOGOUT"` 且 `status == 0 && userId != null` 时同步更新 `sys_user.login_ip` 与 `login_date` |

### 4.3 跨模块领域事件与认证策略 SPI（`ateng-solid-common`）
- **`DomainEventPublisher`**（`io.github.atengk.solid.common.event`）：提供 `publish(DomainEvent)` 与 `publishAfterCommit(DomainEvent)`；默认由 `SpringDomainEventPublisher`（基于 `ApplicationEventPublisher` + `AFTER_COMMIT` + `AsyncContext` 四维透传）装配，`Spec 5` 微服务壳通过 `@Primary` 适配器无侵入替换。
- **首期唯一跨模块异步事件 `LoginAuditEvent`**：携带 `eventId`、`occurredAt`、`tenantId`、`traceId`、`userId`（可空）、`username`、`loginType`、`action`（`"LOGIN"`/`"LOGOUT"`）、`clientIp`、`userAgent`、`status`、`message`；`auth` 发布后由 `system` 异步监听器转调 `RemoteAuditLogService.recordLoginAudit`。
- **`AuthGrantHandler` SPI**（`io.github.atengk.solid.common.spi.auth`）：`String getGrantType()` + `AuthenticatedPrincipal authenticate(AuthGrantCommand command)`；`AuthService` 按 `grantType` 路由调用并负责 Sa-Token 签发与事件发布，图形验证码/租户解析/防爆破/账密核验内聚在 `PasswordAuthGrantHandler` 中。
