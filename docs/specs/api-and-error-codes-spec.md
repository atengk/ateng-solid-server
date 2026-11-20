# 管理端接口契约与业务错误码明细规范 (api-and-error-codes-spec.md)

> **适用范围**：`ateng-solid-auth`、`/admin/auth/**` 与 `ateng-solid-system`、`/admin/system/**` 对外接口定义，与前端 `ateng-solid-admin-web/src/api/*` 保持 1:1 强类型对齐。
> **前置约定**：所有响应统一包装于 `Result<T>`；列表查询无匹配返回空数组 `[]`；主键与外键（含 `tenantId`）JSON 一律序列化为 `string`；Controller 严禁直接接收或返回 ORM `Entity`。

---

## 1. 认证与会话域 (`/admin/auth/**` · 错误码 `101xxx`)

| 端点 | 方法 | 鉴权 | 核心请求模型 | 核心响应模型 (`Result<T>`) | 说明 |
| :--- | :---: | :---: | :--- | :--- | :--- |
| `/admin/auth/captcha` | `GET` | `@Anonymous` | 无 | `CaptchaVO` (`enabled`, `tenantEnabled`, `captchaKey`, `imageBase64`) | 复用 `CaptchaTemplate`，同时透传 `tenantEnabled` 开关控制前端租户显隐 |
| `/admin/auth/login` | `POST` | `@Anonymous` | `AdminLoginRequest` (`tenantCode`, `username`, `password`, `captchaKey`, `captchaCode`) | `AdminLoginVO` (`tokenName`, `tokenValue`, `expireIn`) | 校验租户、验证码、防爆破与 PBKDF2 口令，签发 `satoken-admin` |
| `/admin/auth/logout` | `POST` | 登录态 | 无 | `Void` | 注销当前 `admin` 会话并发布登出审计事件 |
| `/admin/auth/info` | `GET` | 登录态 | 无 | `AdminProfileVO` (`userId`, `tenantId`, `username`, `nickname`, `avatar`, `roles`, `permissions`) | 返回当前登录态身份与扁平权限码数组 |
| `/admin/auth/routes` | `GET` | 登录态 | 无 | `List<RouteMenuVO>` | 返回当前用户可见的嵌套目录/菜单树（`M`/`C`） |
| `/admin/auth/profile` | `PUT` | 登录态 | `UpdateProfileRequest` (`nickname`, `email`, `phone`, `gender`, `avatar`) | `Void` | 更新本人基本资料 |
| `/admin/auth/password` | `PUT` | 登录态 | `UpdatePasswordRequest` (`oldPassword`, `newPassword`) | `Void` | 校验旧口令并更新 PBKDF2 慢哈希 |
| `/admin/auth/online/page` | `GET` | `auth:online:list` | `OnlineUserQuery` (`username`, `clientIp`, `PageQuery`) | `PageResult<OnlineSessionVO>` | 分页查看管理端在线会话 |
| `/admin/auth/online/{tokenValue}` | `DELETE` | `auth:online:kickout` | 路径参数 `tokenValue` | `Void` | 强制踢出指定在线会话 |

---

## 2. 系统管理域 (`/admin/system/**` · 错误码 `102xxx`)

| 资源域 | 路径前缀 | 典型接口与权限码 | 核心说明 |
| :--- | :--- | :--- | :--- |
| **静态枚举** | `/admin/system/enums` | `GET /admin/system/enums`、`GET /admin/system/enums/{alias}` | 由 `ateng-boot-web` `EnumDictionaryController` 暴露 `IBaseEnum`（受登录态保护） |
| **租户管理** | `/admin/system/tenant` | `GET /page` (`system:tenant:list`)、`GET /{id}`、`POST` (`create`)、`PUT` (`update`)、`DELETE /{ids}` (`delete`)、`PUT /{id}/status` | 仅在开启多租户且平台默认租户（`tenantId = 0`）可见与维护；禁止删除租户 `0`；`account_count` 仅新增/启用用户时增量拦截 |
| **租户套餐** | `/admin/system/tenant-package` | `GET /page` (`system:tenant-package:list`)、`GET /options`、`POST`、`PUT`、`DELETE /{ids}` | 仅平台租户 `0` 维护，禁止勾选平台专属菜单；缩减菜单时事务内联动裁剪关联租户角色越界菜单并刷新缓存 |
| **用户管理** | `/admin/system/user` | `GET /page` (`system:user:list`)、`GET /{id}`、`POST`、`PUT`、`DELETE /{ids}`、`PUT /{id}/status`、`PUT /{id}/reset-password`、`POST /import` (`import`)、`GET /import/feedback/{feedbackToken}`、`POST /export` (`export`)、`POST /avatar` | 联动 `@DataScope`、Fesod 整批原子校验（`ImportResultVO` + 临时批注下载）与 `ateng-boot-storage` 头像存储 |
| **角色管理** | `/admin/system/role` | `GET /page` (`system:role:list`)、`GET /options`、`GET /{id}`、`POST`、`PUT`、`DELETE /{ids}`、`PUT /{id}/data-scope` | 禁止普通租户创建 `super_admin`/`admin` 保留角色编码 |
| **菜单管理** | `/admin/system/menu` | `GET /tree` (`system:menu:list`)、`GET /{id}`、`POST`、`PUT`、`DELETE /{id}` | `menuType` 仅限 `M`/`C`/`F`；删除前须校验无子菜单 |
| **部门管理** | `/admin/system/dept` | `GET /tree` (`system:dept:list`)、`GET /{id}`、`POST`、`PUT`、`DELETE /{id}` | 维护 `ancestors` 祖级路径；删除前校验无子部门与关联用户 |
| **岗位管理** | `/admin/system/post` | `GET /page` (`system:post:list`)、`GET /options`、`POST`、`PUT`、`DELETE /{ids}` | 联合唯一索引 `(tenant_id, post_code, deleted)` |
| **数据字典** | `/admin/system/dict` | `GET /type/page`、`POST /type`、`PUT /type`、`DELETE /type/{ids}`、`GET /data/page`、`GET /data/type/{dictType}`、`POST /data`、`PUT /data`、`DELETE /data/{ids}` | `GET /data/type/{dictType}` 供前端 `DictManager` 缓存消费 |
| **系统参数** | `/admin/system/config` | `GET /page` (`system:config:list`)、`GET /key/{configKey}`、`POST`、`PUT`、`DELETE /{ids}` | 租户隔离配置项，内置系统参数禁止删除 |
| **审计日志** | `/admin/system/log` | `GET /oper/page` (`system:oper-log:list`)、`GET /oper/{id}`、`GET /login/page` (`system:login-log:list`) | 只读分页查询，支持按操作人、模块、状态、时间区间检索 |

---

## 3. 6 位分段业务错误码字典

| 域 | 号段 | 枚举类 | 预留核心错误码示例 |
| :--- | :---: | :--- | :--- |
| **`common`** | `100000 ~ 100999` | `CommonBizResultCode` | `100001` 参数格式非法、`100002` 非法租户切换目标、`100003` 数据权限配置异常、`100004` 文件类型或大小超限、`100005` 导入模板校验失败 |
| **`auth`** | `101000 ~ 101999` | `AuthResultCode` | `101001` 验证码无效或已过期、`101002` 用户名或密码错误、`101003` 账号已停用、`101004` 连续失败已触发防爆破锁定、`101005` 所属租户不存在或已过期、`101006` 旧密码校验失败、`101007` 禁止强退当前自身会话 |
| **`system`** | `102000 ~ 102999` | `SystemResultCode` | `102001` 记录不存在、`102002` 编码/用户名已存在、`102003` 禁止操作平台默认租户、`102004` 禁止使用保留角色编码（`super_admin`/`admin`）、`102005` 存在子节点禁止删除、`102006` 超出租户套餐账号配额或菜单范围、`102007` 禁止禁用或删除超级管理员账号 |

---

## 4. `ateng-solid-system-api` 跨模块契约原则

- 所有 `Remote*Service` 接口方法统一直接返回裸对象/集合/布尔值（如 `AdminAuthUserDTO`、`TenantAuthDTO`、`List<MenuTreeDTO>`、`void`），**禁止返回 `Result<T>`**；
- 查找单条记录不存在时返回 `null` 或按语义抛出 `BusinessException(SystemResultCode.RECORD_NOT_FOUND)`，集合查询无匹配保底返回空集合 `Collections.emptyList()`。
