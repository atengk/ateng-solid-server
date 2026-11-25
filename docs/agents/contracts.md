# 跨端通信协议与数据契约规范 (contracts.md)

> **唯一真源**：维护于根仓 `ateng-solid/docs/agents/contracts.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓 `docs/agents/contracts.md`；禁止在子仓直接修改副本。

---

## 1. 接口对齐与命名公约

- **手写强类型对齐**：后端不强依赖 `ateng-boot-openapi`，前端不使用 OpenAPI 代码生成；前端在 `src/api/*` 中对照后端 Controller / Request / VO 手写强类型接口，字段名、可空性与枚举值 1:1 严格一致。
- **表与索引命名**：全小写蛇形命名——内核域 `sys_*`、扩展域 `ext_*`、二开行业域 `biz_*`；主键统一 `id`；为兼容 H2 全局索引作用域，所有索引强制携带表简名前缀——防软删联合唯一索引 `uk_<表简名>_<业务列>_deleted`、无软删关联表唯一索引 `uk_<表简名>_pair`、普通检索索引 `idx_<表简名>_<列名>`。
- **配置键命名**：后端扩展配置收口于 `ateng.solid.*`（强类型 `@ConfigurationProperties`，禁 `@RefreshScope` 与零散 `@Value`）；前端环境变量统一 `VITE_*`。

---

## 2. 统一响应、错误码与分页契约（对齐 `ateng-boot-core`）

### 2.1 统一响应信封 `Result<T>`
- **结构**：`{ code: number, msg: string, data: T, timestamp: number, success: boolean, traceId?: string }`
- **基础状态码（`CommonResultCode`）**：`200` 成功、`400` 参数/请求非法、`401` 未登录或令牌失效、`403` 无权限、`404` 不存在、`409` 唯一键或乐观锁冲突、`429` 限流、`500` 系统繁忙（严禁透传 SQL 或 Java 堆栈）。
- **前端解包**：`@atengk/core` `http` 默认解包直接返回 `Promise<T>`；遇 `401` 自动清空凭据并触发 `onUnauthorized`；非 `200` 转译为带 `code`、`msg`、`traceId` 的 `BusinessException`。

### 2.2 6 位分段业务错误码（避开底座 `200~503`、`40003`、`62001~62020`）
- `100000 ~ 100999`：`common`（通用校验、幂等、限流、数据权限、文件处理）
- `101000 ~ 101999`：`auth`（登录认证、验证码、防爆破锁定、会话治理）
- `102000 ~ 102999`：`system`（租户/套餐、用户、角色、菜单、部门、岗位、字典、参数）

### 2.3 统一分页 `PageQuery` / `PageResult<T>`
- **入参**：`{ current?: number, size?: number, sorts?: Array<{ property: string, direction: 'ASC' | 'DESC' }> }`
- **出参**：`{ records: T[], total: number, size: number, current: number, pages: number }`
- **空集合铁律**：列表/分页/树/权限集合无匹配时统一返回 `[]`，严禁返回 `null` 或 `undefined`。

---

## 3. 序列化、雪花主键与多租户规范

- **64 位雪花主键**：后端统一 `Long`（`IdType.ASSIGN_ID`），Jackson 序列化为字符串；前端 TS 模型所有主外键 ID（含 `tenantId`）统一声明为 `string`，严禁 `number`。
- **多租户开关与字段**：
  - 由 `ateng.solid.tenant.enabled`（默认 `false`，联动 `ateng.boot.mybatis-plus.tenant.enabled`）控制启停；关闭时固定平台默认租户 `0`（`tenant_code = '000000'`），前端依据登录前置接口返回的 `tenantEnabled` 隐藏登录页租户输入、顶栏切换器与租户/套餐菜单。
  - 全库租户隔离表及 4 张关联表（`sys_user_role`/`sys_user_post`/`sys_role_menu`/`sys_role_dept`）统一 `tenant_id BIGINT NOT NULL DEFAULT 0`；JSON 与 `X-Tenant-Id` 按字符串传递（默认 `"0"`），与 `ateng-boot` `DefaultTenantLineHandler`（`LongValue`）原生契合。
  - `000000` 固定为平台内置默认租户业务编码（`sys_tenant.tenant_code`）。
- **时间与金额**：时间统一 `yyyy-MM-dd HH:mm:ss` 字符串；金额用后端 `BigDecimal` 平铺输出（禁科学计数法），前端用 `@atengk/core` 精度工具处理。
- **内存真明文与脱敏**：除专供脱敏只读展示的字段外，接口与前端状态保持真明文；持久化加密收敛于 `@FieldEncrypt`，展示掩码收敛于 `@Sensitive` / `@ExcelSensitive` 或前端渲染层 `formatMask*`。

---

## 4. 路由前缀、请求头与权限模型

### 4.1 多端对称路由
- 管理端独占 `/admin/**`（`loginType = "admin"`，请求头 `satoken-admin`），按服务域对称划分以兼容未来 `ateng-cloud-gateway`：
  - `/admin/auth/**`：认证中心（登录、注销、验证码、个人信息、在线会话）
  - `/admin/system/**`：系统管理（租户、套餐、用户、角色、菜单、部门、岗位、字典、参数、枚举、日志）
- 预留端前缀：`/portal/**`、`/app/**`、`/open/**`。
- 路径风格：小写连字符（如 `/tenant-package`）；分页 `GET /page`（复杂筛选可 `POST /page`）；CRUD 为 `GET /{id}`、`POST`、`PUT`、`DELETE /{ids}`；状态/专项动作采用动词子路径（如 `PUT /{id}/status`、`PUT /{id}/reset-password`）。

### 4.2 请求头与超管切租户安全边界
- `satoken-admin`：管理端独立鉴权头。
- `X-Tenant-Id`：工作租户头（默认 `"0"`）。普通租户用户登录态下后端强制锁定会话归属租户并忽略伪造头；仅平台租户（`tenant_id = 0`）且具备 `super_admin` 角色允许受控切换视角。
- `X-Trace-Id`：链路追踪标识（与 `Result.traceId` 双向对齐）。

### 4.3 三段式权限码与动态路由菜单
- **权限标识**：统一小写 `<域>:<资源>:<动作>`（如 `system:user:list`/`create`/`update`/`delete`/`export`/`import`），禁同义异名后缀。
- **超管角色**：统一收敛为 `super_admin`（全权限 `*:*:*`）；普通租户管理员使用 `tenant_admin` 等业务编码，严禁以 `"admin"` 作为免检超管绕过权限或租户边界。
- **菜单节点字段**：`id`、`parentId`、`menuType`（`M` 目录 / `C` 菜单 / `F` 按钮）、`title`、`name`、`path`、`component`、`redirect`、`perms`、`icon`、`sort`、`visible`、`status`、`keepAlive`、`isExternal`；按钮权限码以扁平字符串数组独立返回供 `AuthManager` / `v-permission` 消费。

---

## 5. 枚举字典、文件下载与 Excel 双向协议

- **静态枚举 vs 动态字典**：
  - 不可运营变更的代码枚举实现 `IBaseEnum`；后端关闭底座内置端点（`ateng.boot.web.enum.endpoint-enabled=false`），复用 `EnumDictionaryRegistry` 并在 `ateng-solid-system` 自建 `/admin/system/enums` 暴露受鉴权（`@SaCheckLogin`）的只读选项。
  - 可运营配置的业务字典走 `sys_dict_type` / `sys_dict_data`，前端经 `DictManager` 缓存消费。
- **Blob 双态异常嗅探**：前端 `responseType: 'blob'` 下载或导出遇服务端返回 `Content-Type: application/json` 时，拦截器前置解析为 `BusinessException`，避免落地损坏文件。
- **Excel 流式导入导出（Apache Fesod）**：
  - 导出走 `ateng-boot-excel` 流式输出并支持 `@ExcelSensitive` 脱敏。
  - 导入采用**整批原子校验 + JSON 明细 + 临时批注下载**：存在行级错误则整批不入库，后端经 `ExcelFeedbackUtil` 生成标红批注 `.xlsx` 暂存（短 TTL + 操作人与租户归属校验），返回 `Result<ImportResultVO>`（含 `totalCount`/`successCount`/`failCount`/`errors[]`/`feedbackToken`），前端用 `AtengExcelFeedback` 展示明细并按需拉取批注文件。
