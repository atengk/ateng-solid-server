# ADR-0003: 后端单体与 `ateng-cloud` 微服务长期同源共存架构

- **状态**：`Accepted`
- **日期**：2026-11-20
- **作者**：Ateng
- **关联模块**：`ateng-solid-server` 全模块、`ateng-solid-bootstrap`、规划中 `ateng-solid-cloud/*`

---

## 1. 背景与演进诉求

在实际企业级交付中，“模块化单体”与“分布式微服务”并非一次性替代关系，而是**长期并存的双态交付形态**：
1. 绝大多数中小规模或内网项目需要单 Jar 极简部署（`ateng-solid-bootstrap`），低运维成本、零注册中心/网关依赖；
2. 大型集群或高并发业务需要基于 `ateng-cloud`（Gateway + Nacos + Dubbo/Feign + Sentinel + Seata）按服务域独立扩缩容与故障隔离；
3. 若为两种形态各自维护一套仓库或业务分支，极易在数次迭代后发生不可逆的功能分叉与双倍 Bug 维修成本；若强行让业务模块直接贴 `@DubboService` / `@FeignClient` 注解，又会污染单体编译与运行环境。

---

## 2. 核心架构方案

### 2.1 同仓“共享业务核 + 双套独立启动壳”目录模型
在单一仓库 `ateng-solid-server` 内维持两类模块分层：
1. **共享业务核（运行态中立，两态复用 100% 同一份源码）**：
   - `ateng-solid-common`、`ateng-solid-system-api`、`ateng-solid-system`、`ateng-solid-auth`（以及后续按域装配的 `ateng-solid-ext-*`、`ateng-solid-biz-*`）；
   - 这些模块严禁引入 `ateng-cloud-*` 运行时依赖、RPC 注解或硬编码本地进程假设。
2. **装配态 1 — 单体启动壳（`ateng-solid-bootstrap`）**：
   - 引入所有需要的核心与扩展实现模块，打包为单一可运行单体应用；
   - 跨模块接口（`Remote*Service`）直接由 Spring 容器同进程本地注入；领域事件走 `ApplicationEvent` 异步监听器；关闭 `same-token` 内网二次校验。
3. **装配态 2 — 微服务启动壳群（`ateng-solid-cloud/`，Spec 5 并列挂载）**：
   - **`ateng-solid-cloud-gateway`**：引入 `ateng-cloud-gateway`，按 `/admin/auth/**`、`/admin/system/**` 路径对称路由至下游服务，集中执行 Sa-Token 登录校验、超管切租户透传（`ateng-cloud#201`）与 `SA-SAME-TOKEN` 签发（`ateng-cloud#202`）。
   - **`ateng-solid-cloud-system`**：引入 `ateng-solid-system` + `ateng-cloud-{nacos,dubbo,sentinel,seata}`；仅在启动壳内编写薄适配器 `RemoteSystemDubboFacade` 标注 `@DubboService` 委托给 `system` 原生 `Remote*ServiceImpl`。
   - **`ateng-solid-cloud-auth`**：仅引入 `ateng-solid-auth`（绝对不引入 `ateng-solid-system`）+ `ateng-cloud-{nacos,dubbo,sentinel}`；在启动壳配置类中通过 `@DubboReference` 将远端服务注册为标准 Spring Bean（`RemoteUserService`、`RemoteTenantService`、`RemotePermissionService`、`RemoteAuditLogService`），使 `auth` 核心业务零感知地以同样方式注入调用；同时提供 Cloud 态的 `DomainEventPublisher` 实现（MQ 或异步 RPC 投递日志）。

### 2.2 数据库与 Flyway 共存策略
1. **脚本归属领域模块**：将 `db/migration/system/` 迁入 **`ateng-solid-system/src/main/resources/db/migration/system/`**（后续 `ext-*`/`biz-*` 同理随域自持 `db/migration/<domain>/`），消除写在 `bootstrap` 导致 `cloud-system` 无法共用的缺陷。
2. **双库模式平滑兼容**：
   - **单体共享单库**：`bootstrap` 配置 `spring.flyway.locations=classpath:db/migration/system,...` 统一迁移全部域表；
   - **微服务独立分库或共享逻辑库**：`cloud-system` 仅扫描自身依赖域脚本；若多个微服务共享同一物理库实例，各服务通过 `spring.flyway.table=flyway_schema_history_<domain>` 隔离迁移历史表（由 `ateng-boot-flyway` 原生多目标与安全护栏支撑）。
3. **跨服事务原则**：以“本地表操作 + 领域事件最终一致”为主；仅在跨服务强资金/库存扣减链路按需在 Cloud 启动壳挂载 `ateng-cloud-seata`。

### 2.3 构建与持续集成守护
- 在引入 `ateng-cloud-bom` 后，父 POM `<dependencyManagement>` **必须保持 `ateng-boot-bom` 在 `ateng-cloud-bom` 之前**；
- Spec 5 落地后，CI 流水线同时编译并执行单体（`bootstrap`）与微服务（`ateng-solid-cloud/*`）的单元测试与 ArchUnit 架构守卫，从构建层阻止单态漂移或反向污染。

### 2.4 Spec 5 开工前置接缝清单（微服务启动前必查）
在正式装配 `ateng-solid-cloud/{gateway,auth,system}` 前，以下跨阶段接缝须在 Blueprint ➔ Spec ➔ Ticket 阶段逐项落锤：
1. **`ateng-solid-system` 中 3 个运行时 SPI 的微服务桥接**：
   - `SolidUserRolePermissionLoader`（`system` 已实现并内部桥接 `RemotePermissionService`）：在 `cloud-auth` 须通过 `@DubboReference` 订阅系统域能力并保持 `cached/per-request` 语义对称；
   - `TenantHeaderSwitchValidatorImpl`（超管 `X-Tenant-Id` 切换校验）：须与 `cloud-gateway` 全局拦截器协同 `ateng-cloud#201`，确保网关与下游 `AtengSaTokenInterceptor` 不发生租户上下文重复覆盖或丢失；
   - `SolidOperateLogStorageProvider`（`@OperateLog` 存储 SPI）：单体直接注入写入 `sys_oper_log`；在微服务拆壳（如 `cloud-auth` 内写操作或后续扩展服务）时，须在 `system-api` 补充 `OperateAuditEvent` / `RemoteAuditLogService#recordOperateLog` 契约并通过事件总线或异步 RPC 归集落库；
2. **`ADR-0007` 可观测与 `Actuator` 多壳适配**：
   - `ateng-solid-cloud-auth` 零自建持久化库，不可直接启用 `dynamicDatasource` 就绪探针，须覆盖收敛 `readiness.include` 并保持 `/actuator/**` 安全基线对称；
   - `ateng-solid-cloud-gateway` 运行于响应式 WebFlux 栈，须核验 `ateng-boot-observability`（若引入）或 Spring Cloud Gateway 的双端口、`stealth-mode` 与 `/actuator/pre-stop` 兼容边界；
3. **`ArchitectureGuardTest` 第 16 条硬守卫规划**：在 `Spec 5` 新增第 16 条 ArchUnit 规则，阻断共享业务核（`common`/`system-api`/`system`/`auth`）任何代码引入 `org.springframework.cloud..`、`org.apache.dubbo..` 或 `io.github.atengk.cloud..`；
4. **CI 与多启动壳产物矩阵规划**：扩展发布与验证工作流（`.github/workflows/release.yml` 等），由单体单一产物升级为同步产出 `ateng-solid-bootstrap` 与 `ateng-solid-cloud-{gateway,auth,system}` 多态构建产物；
5. **底座 BOM 与快照兼容核对**：开工前复核 `ateng-cloud:1.1.0` 与当前 `ateng-boot:1.2.1-SNAPSHOT`（含 `B14~B26`）的传递依赖兼容矩阵；按既定发布策略，允许 `Spec 5` 继续于 `dev` 协同底座 `-SNAPSHOT` 联调改造，待全阶段通过后统一发布底座正式版并合并 `main`。

---

## 3. 后果与权衡

- **优点**：一套前端、一套后端核心实现、一套 SQL 迁移脚本，同时产出单体部署包与微服务集群镜像，无分支合并地狱、无双倍缺陷修复。
- **代价**：需要在 `system-api` 严格执行“裸 DTO/VO + 抛 `BusinessException`（禁 `Result<T>`）”、`auth` 严禁依赖 `system`、`cloud-*` 启动壳保留薄 RPC/Event 胶水类，并依赖 ArchUnit 强制守卫。
