# 架构决策记录 (Architecture Decision Records - ADR)

本目录记录 `ateng-solid-server` 后端服务工程的关键架构决策（ADR）。

## 决策矩阵总览 (Decision Matrix)

| 编号 | 决策标题 | 状态 | 核心收益与权衡 | 关联模块 | 详细文档 |
| :---: | :--- | :---: | :--- | :--- | :---: |
| **0001** | **5 模块同源双装配架构与 `*-api` 物理契约层设计** | 🟢 Accepted | 增设纯 Java 契约模块 `ateng-solid-system-api`（裸 DTO + `BusinessException`），编译期阻断跨模块依赖，支撑当下稳固单体并兼容后续并列挂载 `ateng-cloud` 启动壳实现双态并存 | 全域模块 | [查看](./0001-five-module-cloud-ready-topology.md) |
| **0002** | **6 位分段业务错误码与持久层零外键防冲突基线** | 🟢 Accepted | 确立 `100xxx/101xxx/102xxx` 分段错误码、`BaseLogicEntity`/`BaseFullEntity` 防软删重建冲突与 `tenant_id BIGINT` (`TenantAware<Long>`) 零物理外键建模基线 | `common`, `system-api`, `system`, `auth`, `bootstrap` | [查看](./0002-error-code-and-persistence-baseline.md) |
| **0003** | **后端单体与 `ateng-cloud` 微服务长期同源共存架构** | 🟢 Accepted | 确立同仓“共享业务核 + `bootstrap` 单体壳 + `ateng-solid-cloud/*` 微服务壳”长期共存布局、Dubbo/MQ 薄桥接与 Flyway 随域存放方案 | 全域模块、`bootstrap`、`ateng-solid-cloud` | [查看](./0003-monolith-and-cloud-coexistence-architecture.md) |
| **0004** | **联系字段确定性加密（`@FieldEncrypt`）与精确检索边界** | 🟢 Accepted | 确立 `sys_user`/`sys_dept` 联系字段统一确定性 SIV 加密、`ContactFieldCrypto` 收口封装、列宽放宽（`128`/`255`）及手机号精确检索权衡 | `system`、`bootstrap` | [查看](./0004-deterministic-field-encryption-and-search-boundary.md) |
| **0005** | **多环境配置分层、Redis 双池化装配与虚线程连接池定容基线** | 🟢 Accepted | 确立 `base+dev+test+prod+local` 五档隔离（`local` 仅随 `dev` 导入、`prod` 强凭据零默认值 Fail-Fast）、BOM 引入 `commons-pool2` 激活 Lettuce+Redisson 双池与 HikariCP 三档定容、无 Actuator 下关闭 `/pre-stop` MVC 端点 | `bootstrap`（联动 `admin-web`） | [查看](./0005-multi-env-configuration-and-pool-baseline.md) |
| **0006** | **`ateng-boot-redis` 核心能力收敛、两级缓存分级与可选自适应降级基线** | 🟢 Accepted | 沉淀上层可选 Redis 自适应门面零改底座，全量收口至 `ateng.boot.redis.key-prefix`（`solid:`），启用 `Caffeine` + `TwoLevelCacheManager` 加速权限/字典/参数，`TenantStatusCache` 走纯 L2 `RedisCacheTemplate`，收敛初始化锁/审计幂等/集群导入信号量/批注双键与 `@RateLimiter` 限流基线 | `system`、`auth`、`bootstrap` | [查看](./0006-redis-capability-convergence-and-adaptive-degradation.md) |

---

## 维护准则

1. 仅在新增独立业务域模块、引入新中间件基础设施或变更核心架构契约时新建 ADR。
2. 局部优化优先修订既有 ADR，并同步维护本索引表与 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)。
