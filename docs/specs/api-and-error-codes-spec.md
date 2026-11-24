# 管理端接口契约与业务错误码明细规范 (api-and-error-codes-spec.md)

> **适用范围**：`ateng-solid-auth`、`/admin/auth/**` 与 `ateng-solid-system`、`/admin/system/**` 对外接口定义，与前端 `ateng-solid-admin-web/src/api/*` 保持 1:1 强类型对齐。
> **前置约定**：所有响应统一包装于 `Result<T>`；列表查询无匹配返回空数组 `[]`；主键与外键（含 `tenantId`）JSON 一律序列化为 `string`；Controller 严禁直接接收或返回 ORM `Entity`。

---

## 1. 认证与会话域 (`/admin/auth/**` · 错误码 `101xxx`)

| 端点 | 方法 | 鉴权 | 核心请求模型 | 核心响应模型 (`Result<T>`) | 说明 |
| :--- | :---: | :---: | :--- | :--- | :--- |
| `/admin/auth/captcha` | `GET` | `@Anonymous` | 无 | `CaptchaVO` (`enabled`, `tenantEnabled`, `captchaKey`, `imageBase64`, `expireSeconds`) | 复用 `CaptchaTemplate`，同时透传 `tenantEnabled` 开关控制前端租户显隐 |
| `/admin/auth/login` | `POST` | `@Anonymous` | `AdminLoginRequest` (`tenantCode`, `username`, `password`, `captchaKey`, `captchaCode`, `grantType?`) | `AdminLoginVO` (`tokenName`, `tokenValue`, `expireIn`, `tenantId`, `tenantName`) | 校验验证码、租户、防爆破与 PBKDF2 口令，签发 `satoken-admin` |
| `/admin/auth/logout` | `POST` | `@SaCheckLogin` | 无 | `Void` | 注销当前 `admin` 会话并发布登出审计事件 |
| `/admin/auth/info` | `GET` | `@SaCheckLogin` | 无 | `AdminProfileVO` (`userId`, `tenantId`, `tenantName`, `username`, `nickname`, `avatar`, `roles`, `permissions`, `passwordNeedsChange`, `loginIp`, `loginDate`) | 返回当前登录态轻量身份、改密提醒标志与扁平权限码数组 |
| `/admin/auth/routes` | `GET` | `@SaCheckLogin` | 无 | `List<RouteMenuVO>` | 返回当前用户可见的嵌套目录/菜单树（`M`/`C`，切租户时按 `platform_only` 过滤） |
| `/admin/auth/profile` | `GET` | `@SaCheckLogin` | 无 | `PersonalProfileVO` (`userId`, `tenantId`, `tenantName`, `username`, `nickname`, `email`, `phone`, `gender`, `avatar`, `deptName`, `postNames`, `roles`, `loginIp`, `loginDate`, `createTime`, `passwordNeedsChange`) | 个人中心读取完整未脱敏（内存真明文）个人资料 |
| `/admin/auth/profile` | `PUT` | `@SaCheckLogin` | `UpdateProfileRequest` (`nickname`, `email`, `phone`, `gender`) | `Void` | 更新本人基本资料（同租户校验手机号/邮箱唯一性 `102002`） |
| `/admin/auth/profile/avatar` | `POST` | `@SaCheckLogin` | `MultipartFile file`（`<=2MB`, `jpg/jpeg/png/webp`） | `AvatarUploadVO` (`avatarUrl`, `objectPath`) | 个人中心上传头像并更新本人 `sys_user.avatar`（魔数检验防伪装 `100004`） |
| `/admin/auth/password` | `PUT` | `@SaCheckLogin` | `UpdatePasswordRequest` (`oldPassword`, `newPassword`) | `Void` | 校验旧口令并更新 PBKDF2 慢哈希与 `pwd_update_time`，同步注销该用户全部 `admin` 会话 |
| `/admin/auth/online/page` | `GET` | `auth:online:list` | `OnlineSessionQuery` (`username?`, `clientIp?`, `PageQuery`) | `PageResult<OnlineSessionVO>` (`sessionId`, `tokenMask`, `currentSession`, `userId`, `username`, `nickname`, `tenantId`, `loginDevice`, `clientIp`, `loginTime`, `tokenTimeout`) | 分页查看当前租户在线会话；**禁止下发原始 `tokenValue`** |
| `/admin/auth/online/{sessionId}` | `DELETE` | `auth:online:kickout` | 路径参数 `sessionId`（不透明会话句柄） | `Void` | 后端解密还原并校对同租户边界，拦截自身会话（`101007`）后强退目标会话 |

---

## 2. 系统管理域 (`/admin/system/**` · 错误码 `102xxx`)

| 资源域 | 路径前缀 | 典型接口与权限码 | 核心说明 |
| :--- | :--- | :--- | :--- |
| **静态枚举** | `/admin/system/enums` | `GET /admin/system/enums`、`GET /admin/system/enums/{name}`、`GET / POST /admin/system/enums/batch` | 关闭底座内置端点（`endpoint-enabled=false`），由 `ateng-solid-system` `SysEnumController` 注入 `EnumDictionaryRegistry` 接管并施加 `@SaCheckLogin` 鉴权管控 |
| **租户管理** | `/admin/system/tenant` | `GET /page` (`system:tenant:list`)、`GET /options` (`@SaCheckLogin` 平台超管)、`GET /{id}`、`POST` (`create`，返回 `CreateTenantVO`)、`PUT` (`update`)、`PUT /{id}/status`、`DELETE /{ids}` (`delete`) | 仅在开启多租户且平台默认租户（`tenantId = 0`）可见与维护；禁止停用/删除/篡改租户 `0`（`102003`）；`account_count`（`-1` 不限）仅新增/启用/导入用户时增量拦截；停用/删除同步强退租户全员会话 |
| **租户套餐** | `/admin/system/tenant-package` | `GET /page` (`system:tenant-package:list`)、`GET /options`、`GET /{id}`、`POST /shrink-preview` (`update`)、`POST` (`create`)、`PUT` (`update`)、`PUT /{id}/status`、`DELETE /{ids}` (`delete`) | 仅平台租户 `0` 维护，禁止勾选 `platform_only = 1` 菜单（`102006`）；缩减菜单时提供影响预览并在事务内联动裁剪关联租户 `sys_role_menu` 并刷新缓存 |
| **用户管理** | `/admin/system/user` | `GET /page` (`system:user:list`)、`GET /options` (`@SaCheckLogin`)、`GET /{id}`、`POST` (`create`)、`PUT` (`update`)、`PUT /{id}/status`、`PUT /{id}/reset-password`、`DELETE /{ids}`、`GET /import/template`、`POST /import` (`import`)、`GET /import/feedback/{feedbackToken}`、`POST /export` (`export`) | 联动 `@DataScope`；`user/options` 供部门负责人选择；Fesod 500 行整批原子导入（`ImportResultVO` 剔除 `rowData` + 15 分钟归属隔离批注下载）；个人头像已收敛至 `/admin/auth/profile/avatar` |
| **角色管理** | `/admin/system/role` | `GET /page` (`system:role:list`)、`GET /options`、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`PUT /{id}/data-scope`、`DELETE /{ids}` | 禁止普通租户创建 `super_admin`/`admin` 保留角色编码（`102004`）；保护内置 `super_admin`（`102007`）；变动调用 3 参清缓存 |
| **菜单管理** | `/admin/system/menu` | `GET /tree` (`system:menu:list`)、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`DELETE /{id}` | 仅平台租户 `0` 维护；`menuType` 仅限 `M`/`C`/`F`；含 `platformOnly` 标记；存在子菜单或被角色/套餐引用时禁止删除（`102005`） |
| **部门管理** | `/admin/system/dept` | `GET /tree` (`system:dept:list`)、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`DELETE /{id}` | 维护 `ancestors` 祖级路径；禁选自身/子孙防自环（`100001`）；删除前校验无子部门与关联用户（`102005`） |
| **岗位管理** | `/admin/system/post` | `GET /page` (`system:post:list`)、`GET /options`、`GET /{id}`、`POST`、`PUT`、`PUT /{id}/status`、`DELETE /{ids}` | 联合唯一索引 `(tenant_id, post_code, deleted)`；被用户引用禁止删除（`102005`） |
| **数据字典** | `/admin/system/dict` | `GET /type/page`、`GET /type/options`、`GET /type/{id}`、`POST /type`、`PUT /type`、`PUT /type/{id}/status`、`DELETE /type/{ids}`、`GET /data/page`、`GET /data/{id}`、`GET /data/type/{dictType}` (`@SaCheckLogin`)、`POST /data`、`PUT /data`、`PUT /data/{id}/status`、`DELETE /data/{ids}` | 平台租户 `0` 维护，`dictType` 编辑锁定只读，`isDefault` 同类型互斥；`GET /data/type/{dictType}` 对全租户登录开放供 `DictManager` 缓存消费 |
| **系统参数** | `/admin/system/config` | `GET /page` (`system:config:list`)、`GET /{id}`、`GET /key/{configKey}` (`@SaCheckLogin`)、`POST`、`PUT`、`DELETE /{ids}` | 租户隔离配置项；内置参数（`configType = 'Y'`）禁止修改键名与删除（`102007`）；`sys.user.initPassword` 修改强口令校验 |
| **审计日志** | `/admin/system/log` | `GET /oper/page` (`system:oper-log:list`)、`GET /oper/{id}`、`GET /login/page` (`system:login-log:list`) | 严格只读分页查询（按时间倒序）与操作日志详情（`browser`/`os` 及脱敏 JSON） |

---

## 3. 6 位分段业务错误码字典

| 域 | 号段 | 枚举类 | 预留核心错误码示例 |
| :--- | :---: | :--- | :--- |
| **`common`** | `100000 ~ 100999` | `CommonBizResultCode` | `100001` 业务参数格式非法（自环、`F` 节点挂接错误、自定部门未勾选）、`100002` 非法租户切换目标、`100003` 数据权限配置异常、`100004` 上传文件非合法图片魔数/类型或大小超限、`100005` Excel 文件格式、超 `500` 行或表头校验失败 |
| **`auth`** | `101000 ~ 101999` | `AuthResultCode` | `101001` 验证码无效或已过期、`101002` 用户名或密码错误（防枚举统一提示）、`101003` 账号已停用、`101004` 连续失败已触发防爆破锁定（附带剩余秒数）、`101005` 所属租户不存在/已停用或已过期、`101006` 旧密码校验失败、`101007` 禁止强退当前自身会话 |
| **`system`** | `102000 ~ 102999` | `SystemResultCode` | `102001` 记录不存在、`102002` 编码/用户名/手机号/邮箱已存在、`102003` 禁止停用/删除/篡改平台默认租户、`102004` 禁止使用保留角色编码（`super_admin`/`admin`）、`102005` 存在子节点/关联引用禁止停用或删除、`102006` 超出租户套餐账号配额或菜单范围（含禁止套餐勾选 `platform_only`）、`102007` 禁止删除/禁用超管账号角色、登录用户自身或内置系统参数 |

---

## 4. `ateng-solid-system-api` 跨模块契约原则

- 所有 `Remote*Service` 接口方法统一直接返回裸对象/集合/布尔值（如 `AdminAuthUserDTO`、`TenantAuthDTO`、`List<MenuTreeDTO>`、`void`），**禁止返回 `Result<T>`**；
- 查找单条记录不存在时返回 `null` 或按语义抛出 `BusinessException(SystemResultCode.RECORD_NOT_FOUND)`，集合查询无匹配保底返回空集合 `Collections.emptyList()`。
