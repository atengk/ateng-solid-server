# ADR-0006: `ateng-boot-redis` 核心能力收敛、两级缓存分级与可选自适应降级基线

- **状态**：`Accepted`
- **日期**：2026-11-27
- **作者**：Ateng
- **关联模块**：`ateng-solid-system`、`ateng-solid-auth`、`ateng-solid-bootstrap`

---

## 1. 背景与现状矛盾

在完成首期 `Spec 1`~`Spec 4` 落地后，核查 `ateng-solid-server` 对 `ateng-boot-redis` 的实际消费情况暴露出如下结构性脱节：

1. **引强库却全量手写轮子**：`ateng-solid-system` 与 `ateng-solid-auth` 均引入了 `ateng-boot-redis`，`application.yml` 也已配置 `ateng.boot.redis.key-prefix` 与 `redisson.*`，但业务代码中没有任何类注入 `TwoLevelCacheManager`、`RedisCacheTemplate`、`DistributedLockTemplate`、`RedisIdempotentTemplate`、`RedisSemaphoreTemplate` 或 `RedisUtil`。相反，7 处核心机制分别在业务层手写了 `StringRedisTemplate`（`TenantStatusCache`、`DictDataCache`、`SysConfigCache`、`BoundedDualModeCache`、`ImportFeedbackStore`）、原生 Lua 解锁脚本（`SuperAdminBootstrapper`）、本地 `LinkedHashMap` 去重（`RemoteAuditLogServiceImpl`）与单机 JVM `Semaphore`（`SysUserExcelService`）。
2. **两级缓存未激活**：`ateng.boot.redis.two-level.enabled` 默认保持 `false` 且 `bootstrap` 未引入 `caffeine`，导致 `ateng-boot-satoken` 内置的 `PermissionCacheService` 以及字典/参数高频元数据均停留在单层 Redis 或手写本地降级，无法享受 `Caffeine` L1 本地极速命中与跨节点 Redis Pub/Sub 失效协同。
3. **物理键命名空间割裂**：`application.yml` 声明了 `ateng.boot.redis.key-prefix: ${SOLID_REDIS_KEY_PREFIX:solid:}`，但存量业务类普遍硬编码 `ateng:solid:...` 前缀，绕过了底座自动前缀装配，导致多环境共享同一 Redis 实例时 `SOLID_REDIS_KEY_PREFIX` 隔离失效。
4. **离线单测与无 Redis 降级冲突**：`application-test.yml` 排除了 `RedisAutoConfiguration` 并置 `ateng.boot.redis.enabled=false`；在此模式下 `AtengRedisAutoConfiguration` 不会注册任何 `ateng-boot-redis` 模板 Bean。若直接强依赖底座模板注入，将破坏 100% 离线 H2 单测及单机无 Redis 降级模式；同时依据 `foundation-first-gate` 规范，未经确认严禁为了适配上层离线降级而污染纯 Redis 底座模块。

---

## 2. 核心决策

### 2.1 上层统一可选 Redis 自适应门面（零改动底座）
- 保持 `ateng-boot-redis` 作为纯 Redis 基础设施提供方，不修改底座源码；
- 在 `ateng-solid-system` 沉淀统一的轻量可选自适应门面（参考 `PermissionCacheService` 自适应模式），通过 `ObjectProvider<...>` 探测容器中的 `TwoLevelCacheManager`、`RedisCacheTemplate`、`DistributedLockTemplate`、`RedisIdempotentTemplate`、`RedisSemaphoreTemplate` 与 `RedisUtil`（并结合 `ateng.boot.redis.enabled` 开关校验）；
- 当 Redis 启用且模板可用时 100% 委派给 `ateng-boot-redis` 受管模板执行；当 `ateng.boot.redis.enabled=false`（含 `test` Profile 离线单测及单机降级场景）时平滑退化为进程内有界 LRU、局部锁、有界去重表或 JVM `Semaphore`，替换分散的 `RedisActivationResolver` 与 `BoundedDualModeCache`。

### 2.2 物理键命名空间全量收口至 `ateng.boot.redis.key-prefix`
- 废除业务代码与 Spec 中全部硬编码的 `ateng:solid:` 字符串前缀，业务层统一仅传入领域相对键，由 `ateng.boot.redis.key-prefix`（默认 `${SOLID_REDIS_KEY_PREFIX:solid:}`）统一管控全库物理前缀：

| 业务场景 | 底座消费组件 | 领域相对键 / 空间 | 默认生效物理键（`key-prefix = solid:`） |
| :--- | :--- | :--- | :--- |
| 角色与权限缓存 | `TwoLevelCacheManager`（经 `PermissionCacheService`） | 空间 `satoken:permissions` | `solid:satoken:permissions:<key>` |
| 动态数据字典缓存 | `TwoLevelCacheManager` | 空间 `dict`，键 `{dictType}` | `solid:dict:{dictType}` |
| 系统参数键值缓存 | `TwoLevelCacheManager` | 空间 `config`，键 `{tenantId}:{configKey}` | `solid:config:{tenantId}:{configKey}` |
| 租户状态与有效期缓存 | `RedisCacheTemplate` | 键 `tenant:status:{tenantId}` | `solid:tenant:status:{tenantId}` |
| 导入错误批注双键暂存 | `RedisUtil` | `excel:feedback:{meta,bin}:{token}` | `solid:excel:feedback:{meta,bin}:{token}` |
| 超管首次初始化锁 | `DistributedLockTemplate` | 键 `init:super-admin` | `solid:lock:init:super-admin` |
| 登录审计事件幂等去重 | `RedisIdempotentTemplate` | 键 `audit:login:{eventId}` | `solid:idempotent:audit:login:{eventId}` |
| 用户批量导入并发信号量 | `RedisSemaphoreTemplate` | 名称 `user:import` | `solid:semaphore:user:import` |
| Web 接口防刷限流 | `@RateLimiter`（`ateng-boot-web`） | `ateng.boot.web.ratelimit.key-prefix` | `solid:rate_limit:<limitType>:<target>` |

### 2.3 缓存分级策略（`TwoLevelCacheManager` + `Caffeine` vs `RedisCacheTemplate`）
- **开启两级缓存并引入 `Caffeine`**：在 `application.yml` 显式开启 `ateng.boot.redis.two-level.enabled: true`，并在 `ateng-solid-bootstrap/pom.xml` 引入 Spring Boot BOM 纳管的 `com.github.ben-manes.caffeine:caffeine`，使 `L1MemoryCacheFactory` 装配 `CaffeineL1MemoryCache`（W-TinyLFU），同步激活 `PermissionCacheService`（`satoken:permissions`）跨节点 L1+L2+Pub/Sub 协同；
- **两级缓存空间定容**：在 `application.yml` 中配置 `satoken:permissions`（L1 `max-size=5000, ttl=5m`; L2 `ttl=30m`）、`dict`（L1 `max-size=2000, ttl=5m`）、`config`（L1 `max-size=5000, ttl=5m`）；`DictDataCache`（封装类型安全载荷规避 `List<DictDataVO>` 泛型擦除）与 `SysConfigCache` 在写入 L2 时仍读取 `ateng.solid.cache.{dict-ttl,config-ttl}` 动态驱动 TTL（默认 `30m`），并在 `TransactionUtil.executeAfterCommit` 精准 `evict` 广播全集群淘汰 L1；
- **安全状态强一致分级**：`TenantStatusCache` 被 `TenantAvailabilityInterceptor` 于每次已登录请求高频调用，一旦平台停用或删除租户必须即时生效、绝不允许节点因 Pub/Sub 短暂抖动而在 L1 残留误放行，因此明确收口至纯 L2 的 **`RedisCacheTemplate`**（兼得 Redisson 分布式互斥防击穿、DCL、空值哨兵与 Jitter TTL），保留内存中实时 `expireTime` 0 秒截断校验。

### 2.4 锁、幂等、并发导入信号量与高风险限流收敛
- **初始化分布式锁（`SuperAdminBootstrapper`）**：收口至 `DistributedLockTemplate.tryExecute("init:super-admin", Duration.ofSeconds(5), ...)`，替代原 `SETNX` 0 等待与手写 Lua 解锁脚本；既享 Redisson 看门狗续期与慢锁审计，又消除节点 B 在节点 A 尚未提交事务前抢跑对外放行流量的竞态，并在超时或进锁后二次复核数据库与 `DuplicateKeyException`；
- **事件幂等去重（`RemoteAuditLogServiceImpl`）**：收口至 `RedisIdempotentTemplate`（`audit:login:{eventId}`，24h TTL，`deleteOnFailure=true`），重复事件静默跳过而不向异步监听调用方抛错；
- **导入批注暂存（`ImportFeedbackStore`）**：保持 `meta` 与 `bin` 双键分离（先校验租户与操作人归属再按需拉取最大 4MB 的 Base64 内容，避免 403 越权探测放大 Redis 带宽），将 `StringRedisTemplate` 替换为 `RedisUtil`；
- **集群并发导入信号量（`SysUserExcelService`）**：收口至 `RedisSemaphoreTemplate`（`user:import`，等待 `3s` 超时抛出 `429`）；启动期在 `DistributedLockTemplate` 保护下比对伴随元数据键（`semaphore:user:import:permits`）与当前 `ateng.solid.user.max-concurrent-imports`：首次不存在时 `trySetPermits`，仅在配置实际增大时增量调用 `addPermits(delta)`、仅在配置调小时调用 `resetPermits`，防止多实例滚动重启时无条件重置抹掉其他节点在途导入许可；
- **高风险入口限流（`@RateLimiter`）**：复用 `ateng-boot-web` 声明式 `@RateLimiter`（Redisson ➔ Redis ➔ InMemory 三级自适应），在 `AuthController`（`GET /captcha` IP `30/60s`、`POST /login` IP `30/60s`）与 `SysUserImportExportController`（`POST /import` User `5/60s`、`POST /export` User `10/60s`）启用限流；在 `application.yml` 配置 `ateng.boot.web.ratelimit.key-prefix: ${SOLID_REDIS_KEY_PREFIX:solid:}rate_limit:` 与 `fail-open: true`；在 `application-test.yml` 设 `ateng.boot.web.ratelimit.enabled: false` 隔离高频集成测试，另设专项测试验证 `429` 与 `Retry-After`；
- **克制边界**：对暂无对应业务队列/过滤需求的 `RedisStreamTemplate`、`RedisDelayedQueue` 与 `RedisBloomFilter` 暂不强行接入。

---

## 3. 备选方案与权衡

| 备选方案 | 放弃原因 |
| :--- | :--- |
| **提单修改 `ateng-boot-redis` 使其在 `enabled=false` 时自动装配本地内存兜底** | 让纯 Redis 基础设施底座承担非分布式内存语义会导致职责混杂且需跨仓改版等待锁版；由上层按 `PermissionCacheService` 同款自适应门面收口更符合分层边界 |
| **将 `TenantStatusCache` 一并放入 `TwoLevelCacheManager`** | 租户停用/过期属于安全访问阻断边界，虽可通过短 L1 TTL（如 `10s`）缩小窗口，但极端 Pub/Sub 断连仍存在短暂误放行风险；纯 L2 `RedisCacheTemplate` 以微秒级单次 Redis 往返换取严格集群一致与跨节点防击穿锁 |
| **`SysUserExcelService` 维持单机 JVM `Semaphore` 或改用 `RRateLimiter`** | 单机 `Semaphore` 在多副本部署下无法限制共享 DB 行锁与导入总并发；`RRateLimiter` 为时间速率限流而非在途并发度控制，故采用 `RedisSemaphoreTemplate` 并辅以对比式启动校准 |
| **在 Controller 手写调用 `ateng-boot-redis` `RedisRateLimiterClient`** | `ateng-boot-web` 已原生提供三层自适应 `@RateLimiter` 切面（含 `Retry-After` 响应头与 `InMemory` 降级），直接复用可避免重复造轮子与上层再套限流适配层 |

---

## 4. 后果与运维约束

- **正面收益**：彻底消除上层对 `StringRedisTemplate` 与手写 Lua 锁的散落依赖；RBAC 权限与字典/参数获得 `Caffeine` L1+L2+Pub/Sub 加速；所有物理键统一受控于 `SOLID_REDIS_KEY_PREFIX`；离线 H2 单测零阻塞。
- **运维与演进约束**：
  - `RSemaphore` 许可不绑定独立租约 TTL：正常停机依赖 `server.shutdown: graceful`（20s 排空）在 `finally` 中释放许可；若某容器实例在执行 500 行导入期间遭遇 `SIGKILL`（`kill -9`）强杀，可能残余 1 个未归还许可，运维可通过删除 Redis 键 `solid:semaphore:user:import`（或配合重启校准）即时自愈；
  - 集群部署下修改 `ateng.solid.user.max-concurrent-imports` 时建议在低峰期滚动发布，使伴随配额键对比增量逻辑平滑对齐；
  - 未来微服务拆分至 `ateng-solid-cloud/*` 时，`system` 核内已使用的受管模板与统一键前缀可直接跨进程共享，无需再改业务代码。
