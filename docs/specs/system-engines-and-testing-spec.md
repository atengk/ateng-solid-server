# 系统域核心引擎、并发导入、存储缓存与离线测试契约 (system-engines-and-testing-spec.md)

> **适用范围**：`ateng-solid-system` 与 `ateng-solid-bootstrap` 在 `Spec 3 ~ Spec 4` 阶段的多租户事务编排、Fesod Excel 并发原子导入、双模对象存储头像、字典/参数两级缓存、集群信号量/限流基线及后端三层离线测试契约。  
> **状态**：已同步 [`ADR-0006`](../adr/0006-redis-capability-convergence-and-adaptive-degradation.md) 修订（统一收敛至 `ateng-boot-redis` 受管能力与 `ateng.boot.redis.key-prefix`；`@DataScope` 与审计切面详见 [`auth-rbac-and-datascope-spec.md`](./auth-rbac-and-datascope-spec.md)）。

---

## 1. 多租户事务边界、配额防超卖与缓存/强退副作用矩阵

### 1.1 副作用一律后置事务提交铁律
- 所有外部副作用（`PermissionCacheService` 3 参/全量失效、租户状态缓存 `evict`、`LoginHelper.logout` / `kickoutByTokenForTenant` 强退会话、旧头像物理删除）**严禁在 `@Transactional` 方法体中段直接调用**，必须统一通过 **`TransactionUtil.executeAfterCommit(...)`** 挂接在事务成功提交后执行，彻底杜绝 DB 回滚引发误踢在线会话或脏缓存。

### 1.2 新建租户 4 步原子初始化（`POST /admin/system/tenant`）
- **Step 0（事务外前置计算）**：校验 `tenantCode`（禁 `'000000'`）、校验目标 `packageId` 存在且 `status = 0`、执行 `PasswordPolicyValidator` 强口令校验并**在事务外完成 `PasswordUtil.hash(adminPassword)`**（避免约 370ms CPU 慢哈希占用数据库连接）；
- **Step 1~4（单一短 `@Transactional(rollbackFor = Exception.class)`）**：
  1. 插入 `sys_tenant` 获取雪花主键 `newTenantId`；
  2. 切入 `TenantContextHolder.runWithTenant(String.valueOf(newTenantId), ...)` 创建默认顶级部门（`parent_id = 0`、`ancestors = "0"`、`dept_name = tenantName`）；
  3. 创建默认角色 `tenant_admin`（`role_name = "租户管理员"`、`data_scope = 1`、`menu_check_strictly = 1`、`dept_check_strictly = 0`），并在 `runWithoutTenant` 读取所选套餐中 `status = 0 AND deleted = 0 AND platform_only = 0` 的菜单集合后，通过 `BatchUtil.insertBatch` 批量落库 `sys_role_menu`；
  4. 创建默认租户管理员用户（`dept_id = rootDeptId`、`pwd_update_time = NULL`、`phone = NULL`、`email = NULL`）并写入 `sys_user_role`。任一步失败整体原子回滚，零孤儿数据。

### 1.3 租户账号配额并发防超卖（`account_count`）
- 当 `ateng.solid.tenant.enabled = true` 时，在 **新增用户**、**停用转启用用户** 与 **500 行批量导入阶段 3 短事务落库** 三处入口，事务内统一使用 `ateng-boot-mybatis-plus` 原生 **`PessimisticLockUtil.forUpdate(...)`**（兼容 MySQL 8.4 LTS 与 H2 `MODE=MySQL`）锁定 `sys_tenant` 目标行（`WHERE id = #{tenantId} AND deleted = 0`）；
- 若 `account_count > 0`，统计当前租户 `deleted = 0` 的有效用户数，校验 `currentCount + delta <= account_count`，超限立即抛出 `SystemResultCode(102006)`。

### 1.4 套餐缩减预览与跨租户 `sys_role_menu` 原子裁剪
- **`POST /admin/system/tenant-package/shrink-preview`**：纯只读接口，计算 `removedMenuIds = oldMenuIds - newMenuIds`；若非空则查询 `sys_tenant` 中绑定该 `package_id` 的活跃租户清单与被移除菜单标题返回前端确认；
- **`PUT /admin/system/tenant-package`（`@Transactional`）**：二次拦截传入 `menuIds` 中任何 `platform_only = 1` 或已停用节点（越界抛 `102006`）；更新 `sys_tenant_package` 与 `sys_tenant_package_menu`；若存在菜单缩减且有绑定租户，在 `TenantContextHolder.runWithoutTenant(...)` 下执行批量裁剪 `DELETE FROM sys_role_menu WHERE tenant_id IN (:tenantIds) AND menu_id NOT IN (:newMenuIds)`（新菜单集为空时清空该批租户全量 `sys_role_menu`），并在 `executeAfterCommit` 中触发 `permissionCacheService.clearAll()`；
- **`PUT /admin/system/tenant` 更换套餐**：若新套餐相对原套餐缩窄菜单，同样在事务内裁剪该单租户越界 `sys_role_menu`，提交后触发 `clearAll()` 与租户状态缓存驱逐。

### 1.5 权限缓存失效与在线会话强退全矩阵（均经 `executeAfterCommit`）

| 业务写操作 | 权限 / 状态缓存失效动作 | 在线会话强退动作 |
| :--- | :--- | :--- |
| 编辑用户角色 | `clearUserCache("admin", tenantIdStr, userId)` | 无 |
| 停用 / 删除用户 | `clearUserCache("admin", tenantIdStr, userId)` | `LoginHelper.logout("admin", compositeLoginId)` |
| 个人改密 / 管理员重置密码 | 无 | `LoginHelper.logout("admin", compositeLoginId)` |
| 编辑角色菜单 / 停用 / 删除角色 | `clearRoleCache("admin", tenantIdStr, roleKey)` | 无 |
| 修改菜单（`perms`/`status`/`platformOnly`）/ 缩减套餐 | `clearAll()` | 无 |
| 编辑租户基础状态或过期时间 | 驱逐受 `key-prefix` 托管的 `tenant:status:{tenantId}`（默认 `solid:tenant:status:{tenantId}`） | 无（启用/改期按实时拦截生效） |
| 停用 / 删除租户 | 驱逐受 `key-prefix` 托管的 `tenant:status:{tenantId}`（默认 `solid:tenant:status:{tenantId}`） | 分页循环 `searchOnlineUserPage("admin", queryByTenant, 1, 100)` 逐批调用 `kickoutByTokenForTenant` 清退该租户全员会话 |

---

## 2. 500 行 Fesod Excel 三段式并发导入与 `feedbackToken` 暂存架构

### 2.1 按次行数上限与流双读契约
- 文件前置拦截：仅允许 `.xlsx` 且 `<= 2MB`，执行单次 **`byte[] fileBytes = file.getBytes()`** 内存缓冲；
- 解析调用：使用 `ateng-boot#1174` 新增按次覆写重载 **`ExcelUtil.readWithResult(new ByteArrayInputStream(fileBytes), UserImportDTO.class, HeaderValidationStrategy.LOOSE, 500, importMaxRows)`**（`importMaxRows` 读自 `SolidSystemProperties.user.importMaxRows`，默认 `500`）；捕获底座超限 `BusinessException` 或 `ExcelHeaderValidationException` 统一转译为 **`CommonBizResultCode(100005)`**；
- 仅当存在任一校验错误时，构造第二个 `new ByteArrayInputStream(fileBytes)` 交由 `ExcelFeedbackUtil.renderErrorFeedback(...)` 生成原表标红批注 `.xlsx`；校验全通过零二次读取、零临时磁盘文件。

### 2.2 三段式事务解耦流水线与并发限流
1. **阶段 1（无事务，只读校验 `< 100ms`）**：
   - 借助 `ExcelReadResult#getRowRecords()`（1-based 物理行号）执行文件内账号/归一化手机/归一化邮箱查重；
   - 批量 `IN` 查询 DB 校验唯一性（手机号与邮箱须先经 `ContactFieldCrypto.encryptPhonesForQuery`/`encryptEmailsForQuery` 转确定性密文）、部门名称或 `/` 全路径解析、岗位/角色编码合法性（禁填 `super_admin`）及租户配额预检；
   - 若 `errors` 非空：渲染批注工作簿并暂存，构造 **`ImportResultVO`**（**严禁透出 `ExcelErrorRow.rowData`**），返回 `successCount = 0`，**零 PBKDF2 计算、零写事务**。
2. **阶段 2（无事务，有界并行 PBKDF2 哈希）**：
   - 接入 `ateng-boot-redis` **`RedisSemaphoreTemplate`**（名称 `user:import`，受 `ateng.boot.redis.key-prefix` 托管为默认 `solid:semaphore:user:import`，最大并发 `ateng.solid.user.max-concurrent-imports = 2`，等待 `3s` 超时抛出 `429` 提示稍后重试）实施**集群级**导入并发度闸门控制；启动期在 `DistributedLockTemplate` 保护下比对伴随元数据键（`semaphore:user:import:permits`）做增量校准（增大调 `addPermits`，调小才 `resetPermits`，防滚动发布误清在途许可）；无 Redis 时经上层自适应门面平滑降级为进程内 `Semaphore`；
   - 控制器入口附加 `ateng-boot-web` 声明式 `@RateLimiter`：`POST /admin/system/user/import`（User 维度 `5 次 / 60s`）与 `POST /admin/system/user/export`（User 维度 `10 次 / 60s`），`test` Profile 通过 `ateng.boot.web.ratelimit.enabled: false` 隔离；
   - 投递至独立有界平台线程池 **`ateng.boot.async.pools.password-hash`**（`mode: platform`、`core-size: 4`、`max-size: 6`、`queue-capacity: 64`、**严禁使用虚拟线程执行器**）并行生成 500 个独立随机盐 `PasswordUtil.hash(initPassword)`（**严禁共享同一哈希**），通过 `CompletableFuture.allOf(...).get(90, TimeUnit.SECONDS)` 有界等待汇总；
   - 前端 `POST /admin/system/user/import` 请求 Axios 超时专项放宽至 **`120_000 ms`**。
3. **阶段 3（极短 `@Transactional(rollbackFor = Exception.class)` `< 100ms`）**：
   - 开启多租户时调用 `PessimisticLockUtil.forUpdate` 锁定 `sys_tenant` 复核配额；
   - 依次调用 `BatchUtil.insertBatch` 批量落库 `sys_user`（`pwd_update_time = NULL`）、`sys_user_post`、`sys_user_role`；捕获并发唯一冲突 `DuplicateKeyException` 转译为 `102002`。

### 2.3 `feedbackToken` 15 分钟归属隔离暂存（`RedisUtil` 双 Key + 本地有界降级）
- 生成 32 位 URL 安全随机 `feedbackToken`（`IdUtil.fastSimpleUUID()`）；
- **Redis 双 Key 暂存（经上层可选自适应门面调用 `ateng-boot-redis` `RedisUtil`，受 `ateng.boot.redis.key-prefix` 托管）**：
  - `excel:feedback:meta:{token}`（默认物理键 `solid:excel:feedback:meta:{token}`）：紧凑 JSON `{ tenantId, operatorUserId, filename }`，TTL **`15m`**（`ateng.solid.user.import-feedback-ttl`）；
  - `excel:feedback:bin:{token}`（默认物理键 `solid:excel:feedback:bin:{token}`）：标红 `.xlsx` 二进制内容（硬护栏 `<= 4MB`），TTL **`15m`**；先校验 `meta` 归属再按需拉取 `bin`，利用 Redis 原生 TTL 自动回收，零孤儿文件、零额外定时清扫任务；
- **无 Redis 降级（含离线单测）**：平滑降级至进程内有界 `ConcurrentHashMap`（容量上限 `50` 条 + 写入/读取惰性过期淘汰）；
- **防越权下载（`GET /admin/system/user/import/feedback/{feedbackToken}`）**：
  - `meta` 或 `bin` 不存在/已过期 → 返回 `404`（`"批注文件已过期或不存在，请重新执行导入"`）；
  - 当前登录态 `(effectiveTenantId, currentUserId)` 与 `meta` 中 `(tenantId, operatorUserId)` 不一致 → 返回 `403`（`"无权下载他人或其他租户的导入批注文件"`）；
  - 校验通过则以 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` + RFC 5987 `Content-Disposition` 响应流输出。

---

## 3. `Spec 4` 工程接缝：双模头像存储与字典/参数缓存

### 3.1 头像存储（默认 S3/RustFS + `local` 安全回退）与 `AvatarUrlResolver`
- **默认驱动与安全配置**：默认启用 **`ateng.boot.storage.type: s3`**（适配云端 RustFS，`s3.path-style: true`、`s3.auto-create-bucket: true`，`bootstrap` 显式声明 `software.amazon.awssdk:{s3,apache-client}` `runtime` 依赖）；同时保留 `local` 模式 `#1172` 最小暴露四开关配置（`download-endpoint-enabled: true`、`upload-endpoint-enabled: false`、`whitelist-presigned-download: true`、`whitelist-presigned-upload: false`）；全程开启 `storage.guard`（`enabled: true`、`strict-magic-check: true`、`allowed-extensions: [jpg, jpeg, png, webp]`、`max-file-size: 2MB`）；
- **对象键持久化与动态签发收口 (`AvatarUrlResolver`)**：
  - `sys_user.avatar` 数据库仅存纯净相对对象键 `avatar/{tenantId}/{userId}/{uuid}.{ext}`，**严禁落库临时预签名 URL**；
  - 在 `ateng-solid-system` 沉淀深模块 `AvatarUrlResolver`：空白返回 `null`、`http(s)://` 外链原样透传、`StorageOperations` 未装配（离线单测 `storage.enabled=false`）时降级返回对象键；正常态统一调用：
    ```java
    storageOperations.getPresignedUrl(PresignedDownloadRequest.builder()
            .path(avatarKey)
            .duration(Duration.ofHours(2))
            .responseContentDisposition("inline")
            .build()).getUrl();
    ```
    （已源码验证 `S3Driver` 与 `LocalDriver` 均原生支持 `inline` 响应头，兼顾 `<img>` 渲染与新标签页直览）；
- **事务级防孤儿/防误删更换编排**：`RemoteUserService#uploadAvatar` 在 `@Transactional` 内严格执行：`uploadWithRollbackDelete` ➔ 更新 `sys_user.avatar` ➔ 旧键非空且不同时调用 `deleteAfterCommit(oldKey)`。

### 3.2 动态字典与系统参数两级缓存策略（含多租户参数兜底）
- **统一两级缓存基础设施（`TwoLevelCacheManager` + `Caffeine` + 上层自适应降级）**：
  - 全局启用 `ateng.boot.redis.two-level.enabled: true` 并引入 `caffeine` 激活 `CaffeineL1MemoryCache`（L1 默认 TTL `5m` + L2 默认 `30m` + Redis Pub/Sub 跨节点失效广播 + 空值哨兵）；无 Redis 或 `ateng.boot.redis.enabled=false` 时由上层自适应门面平滑降级为有界 `ConcurrentLruCache`（上限 `10_000`）；
- **动态字典缓存（`sys_dict_data`）**：
  - 两级缓存空间 `dict`、键 `{dictType}`（默认 L2 物理键 `solid:dict:{dictType}`），写入 L2 时读取 `ateng.solid.cache.dict-ttl`（默认 `30m`）动态驱动过期时间，封装为类型安全包装载荷规避 `List<DictDataVO>` 泛型擦除；
  - 仅缓存 `status = 0` 的有效明细列表；若对应 `sys_dict_type` 不存在或已停用，缓存空集合 `[]` 防缓存穿透；
  - 对 `sys_dict_type` 或 `sys_dict_data` 执行任何增删改/启停操作时，在 `TransactionUtil.executeAfterCommit` 中精准 `evict` 广播全集群淘汰 L1/L2；
- **系统参数缓存（`sys_config`）与多租户平台兜底回退**：
  - 两级缓存空间 `config`、键 `{tenantId}:{configKey}`（默认 L2 物理键 `solid:config:{tenantId}:{configKey}`），写入 L2 时读取 `ateng.solid.cache.config-ttl`（默认 `30m`），写操作在 `executeAfterCommit` 精准 `evict` 广播；
  - **多租户兜底读取规则**：调用 `GET /admin/system/config/key/{configKey}` 或服务端内部读取（如新建用户/重置密码/Excel 导入读取 `sys.user.initPassword`）时，先查当前生效 `tenantId`；若未命中且 `tenantId != 0L`，自动在 `TenantContextHolder.runWithTenant("0", ...)` 下回退读取平台默认租户 `0` 的全局参数值（租户自行在 `/system/config` 新建同名键后即刻覆盖生效），避免新建租户复制冗余配置行。

---

## 4. 后端三层离线切片测试契约（100% 离线 `mvn clean test`）

| 测试分层 | 执行环境与速度 | 覆盖范围与核心断言 |
| :--- | :--- | :--- |
| **Layer 1 · 纯单元测试** | 无 Spring 容器，纯 JUnit 5 + Mockito（毫秒级） | `PasswordPolicyValidator`、`ContactFieldCrypto`、`OpaqueSessionHandleCodec`（篡改/跨租户 AAD 失败）、`SolidDataPermissionHandler`（5 级与多角色并集 JSqlParser AST 结构与 `1 = 0` 断言）、Excel 批内查重与错误映射 |
| **Layer 2 · 引擎与持久层集成测** | H2 `MODE=MySQL` + Flyway（`V1.0.0` + `V1.0.1`）+ MyBatis-Plus 完整拦截链（关闭 Redis/Storage） | 17 表迁移与 58 条确定性菜单种子、`SuperAdminBootstrapper` 幂等与并发冲突兜底、`@FieldEncrypt` 落盘密文与查询回显、5 级 `@DataScope` + 多租户隔离真 SQL 执行、租户 4 步初始化回滚/配额行锁/套餐裁剪、500 行导入原子回滚与批注渲染 |
| **Layer 3 · Web 安全契约与架构守卫测** | `MockMvc`（`application-test.yml`）+ `ArchitectureGuardTest` | 登录 4 步校验链、`101004` `LoginLockDetailVO` 透传、不透明 `sessionId` 强退、`TenantAvailabilityInterceptor`（`101005`/`100002`）、`@SaCheckPermission`/`@Anonymous`、`SysEnumController` 鉴权、独立 `@RateLimiter` `429`/`Retry-After` 专项校验与 9 条 ArchUnit 硬守卫 |
