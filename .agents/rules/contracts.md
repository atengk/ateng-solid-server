---
trigger: always_on
description: "ateng-solid 全端跨端通信协议、统一响应信封、错误码段、雪花主键、多租户、权限码与导入导出数据契约唯一真源"
---

# 跨端通信协议与数据契约规范 (contracts.md)

> **作用域**：全端前后端数据交互、接口联调及跨终端边界设计。
> **唯一真源声明**：本文件主源维护于根仓库 `ateng-solid/.agents/rules/contracts.md`，各子仓库 `.agents/rules/contracts.md` 副本由 `bash scripts/workspace.sh sync-rules` 单向同步，禁止在子仓库直接修改副本造成协议漂移。

---

## 1. 接口对齐方式

- **手写强类型对齐**：后端不强依赖 `ateng-boot-openapi`，前端不使用 OpenAPI 自动代码生成工具；前端在 `src/api/*` 中手写强类型请求函数与 TypeScript 接口，字段名称、可空性与枚举值直接对照后端 Controller、Request/DTO 与 VO 保持 1:1 严格一致。

---

## 2. 统一响应、分页与业务错误码契约（对齐 `ateng-boot-core`）

### 2.1 统一响应信封 `Result<T>`
- **数据结构**：`{ code: number, msg: string, data: T, timestamp: number, success: boolean, traceId?: string }`
- **基础状态码（复用 `CommonResultCode`）**：
  - `200`：成功
  - `400`：参数校验失败或非法请求
  - `401`：未登录或令牌失效
  - `403`：权限不足或访问拦截
  - `404`：请求资源不存在
  - `409`：数据冲突、唯一键冲突或乐观锁版本不一致
  - `429`：请求过于频繁（限流拦截）
  - `500`：系统内部繁忙/异常（严禁向前端透传 SQL 或 Java 堆栈）
- **前端解包行为**：`@atengk/core` `http` 客户端默认解包直接返回 `Promise<T>`；遇 `code === 401` 自动清空本地凭据并广播 `onUnauthorized` 触发登出跳转；非 `200` 统一转译为携带 `code`、`msg`、`traceId` 的 `BusinessException`。

### 2.2 业务错误码号段分配（6 位按域分段整数）
避开 `ateng-boot` 内置号段（`200~503`、`40003`、`62001~62020`），按服务域划分并面向未来 `ateng-cloud` 拆服保持全局唯一：
- `100000 ~ 100999`：`common` 通用校验、防重幂等、限流、数据权限与文件处理异常
- `101000 ~ 101999`：`auth` 登录认证、图形验证码、防爆破锁定与会话治理异常
- `102000 ~ 102999`：`system` 租户/套餐、用户、角色、菜单、部门、岗位、字典与系统参数异常

### 2.3 统一分页契约 `PageQuery` / `PageResult<T>`
- **请求入参**：`{ current?: number, size?: number, sorts?: Array<{ property: string, direction: 'ASC' | 'DESC' }> }`
- **响应出参**：`{ records: T[], total: number, size: number, current: number, pages: number }`
- **空安全铁律**：列表与分页查询无匹配数据时，后端保底返回空数组 `[]`，严禁返回 `null`；前端使用 `@atengk/vue` `usePagination` 并配合 `AbortController` + `useLatest` 消除并发竞态。

---

## 3. 序列化、雪花主键与多租户数据规范

- **64 位雪花主键**：后端主外键统一采用 64 位 `Long`（`IdType.ASSIGN_ID`），Jackson 序列化阶段输出为字符串；前端业务模型中所有主外键 ID **统一声明为 `string`**，严禁使用 `number` 承接雪花 ID。
- **多租户开关、主键 `tenant_id` 与编码 `tenant_code`**：
  - **可开关、默认关闭**：系统支持通过配置开关（`ateng.solid.tenant.enabled`，**默认 `false`**，联动 `ateng.boot.mybatis-plus.tenant.enabled`）启停多租户隔离；关闭时固定以平台默认租户 `0`（`000000`）运行，前端依据登录前置接口返回的 `tenantEnabled` 开关自动隐藏登录页租户输入框、顶栏租户切换器及租户/套餐管理菜单。
  - 全库业务/组织/配置表及租户内多对多关联表（`sys_user_role`/`sys_user_post`/`sys_role_menu`/`sys_role_dept`）的 `tenant_id` 统一为 **`BIGINT NOT NULL DEFAULT 0`**（内置平台默认租户 ID 为 `0`，新建租户采用雪花 ID）；对外 JSON 与请求头 `X-Tenant-Id` 按雪花规范统一传字符串（默认租户传 `"0"`），与 `ateng-boot` `DefaultTenantLineHandler`（纯数字解析为 `LongValue`）100% 原生契合。
  - `000000` 固定作为平台内置默认租户的业务编码 `tenant_code`（存储于 `sys_tenant.tenant_code`），用于多租户开启时登录入口展示或租户编码检索。
- **时间与金额格式**：时间字段统一为 `yyyy-MM-dd HH:mm:ss` 字符串；金额字段使用后端 `BigDecimal` 平铺输出（禁用科学计数法），前端使用 `@atengk/core` 金融精度工具处理。
- **内存真明文与渲染脱敏**：除后端明确要求仅作脱敏展示的字段外，接口与前端状态层保持真明文流转；存储加密收敛于后端 `@FieldEncrypt`，展示掩码统一收敛于 `@Sensitive` / `@ExcelSensitive` 或前端渲染层 `formatMask*`。

---

## 4. 管理端路由、多账号鉴权与权限规范

### 4.1 多端路由前缀与对称微服务边界
- `/admin/**`：管理后台接口（对应 `loginType = "admin"`，请求头 `satoken-admin`），内部严格按服务域组织为 **`/admin/<服务域>/<资源>`**，为后续平滑接入 `ateng-cloud-gateway` 按域路由保留零改动边界：
  - `/admin/auth/**`：认证中心域（登录、注销、验证码、个人信息、在线会话）
  - `/admin/system/**`：系统管理域（租户、租户套餐、用户、角色、菜单、部门、岗位、字典、参数、日志）
- `/portal/**`、`/app/**`、`/open/**`：门户官网、移动端与公开回调前缀（预留）。
- **接口路径风格**：分页检索用 `GET /page`（复杂筛选支持 `POST /page`）；标准 CRUD 采用 RESTful 语义（`GET /{id}`、`POST` 新增、`PUT` 修改、`DELETE /{ids}` 批量删除）；状态变更或重置密码等独立动作使用动词子路径（如 `PUT /{id}/status`、`PUT /{id}/reset-password`）。

### 4.2 请求头与超管切租户契约
- `satoken-admin`：管理端独立鉴权请求头（对接后端 `LoginHelper` `loginType="admin"`）。
- `X-Tenant-Id`：多租户上下文请求头（由前端 `TenantManager` 自动注入，默认 `"0"`）。**安全约束**：普通租户用户登录态下，后端强制锁定其会话归属租户并忽略客户端伪造的 `X-Tenant-Id`；仅平台租户（`tenant_id = 0`）且具备超级管理员身份的账号，允许受控切换 `X-Tenant-Id` 预览或治理指定租户数据。
- `X-Trace-Id`：全链路追踪标识（与响应信封 `Result.traceId` 双向对齐）。

### 4.3 三段式权限标识与动态路由菜单契约
- **三段式权限标识**：前后端统一采用 `<域>:<资源>:<动作>` 小写冒号规范（如 `system:user:list`、`system:user:create`、`system:user:update`、`system:user:delete`、`system:user:export`、`system:user:import`），严禁出现同义异名动作后缀。
- **超管角色标识**：平台超级管理员角色编码统一收敛为 **`super_admin`**（全权限 `*:*:*`）；普通租户管理员使用 `tenant_admin` 等业务角色编码，严禁使用 `"admin"` 作为免检超管绕过数据权限或租户边界。
- **动态路由菜单结构**：菜单树节点包含 `id`、`parentId`、`menuType`（`M` 目录 / `C` 菜单 / `F` 按钮）、`title`、`name`、`path`、`component`、`redirect`、`perms`、`icon`、`sort`、`visible`、`status`、`keepAlive`、`isExternal`；按钮权限码列表以独立扁平字符串数组返回供前端 `AuthManager` / `v-permission` 消费。

---

## 5. 枚举字典、文件传输与 Excel 双向协议

- **静态枚举 vs 动态字典分工**：
  - 代码中强类型不可运营变更的状态枚举（如启用/停用、操作类型、数据范围类型）实现 `IBaseEnum`，后端通过 `ateng-boot-web` `EnumDictionaryController` 统一挂载于 **`/admin/system/enums`** 暴露只读选项字典；
  - 需后台运营配置、增删改的业务字典（如性别、岗位类别、通知类型等）统一走数据库 `sys_dict_type` / `sys_dict_data`，前端统一经 `@atengk/element-plus` `DictManager` 缓存消费。
- **文件下载与 Blob 双态异常嗅探**：前端发起文件下载或导出（`responseType: 'blob'`）时，若服务端返回业务报错帧（`Content-Type: application/json`），客户端拦截器前置读取并转译为 `BusinessException` 提示，禁止生成损坏假文件。
- **Excel 流式导入导出（Apache Fesod）**：导出统一通过 `ateng-boot-excel` 流式输出（支持 `@ExcelSensitive` 脱敏）；批量导入采用**整批原子校验 + JSON 明细 + 临时批注文件下载**协议——存在行级校验失败时整批不入库，后端通过 `ExcelFeedbackUtil` 生成标红批注 `.xlsx` 暂存（短 TTL 且校验操作人及租户归属），导入接口统一返回 `Result<ImportResultVO>`（含 `totalCount`/`successCount`/`failCount`/`errors[]`/`feedbackToken`），由前端 `AtengExcelFeedback` 弹窗展示错误明细并按需通过独立下载端点拉取标红批注文件。
