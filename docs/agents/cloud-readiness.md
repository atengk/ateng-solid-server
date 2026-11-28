# 同源双装配与并存边界守则 (cloud-readiness.md)

> **适用时机**：设计或修改 `ateng-solid-system-api` 跨模块契约、调整模块间依赖、规划 Flyway/异步事件/安全上下文，或装配未来 `ateng-solid-cloud/*` 微服务壳时必读。
> **核心模型**：**同源双装配（单体 `bootstrap` 与 `ateng-cloud` 微服务启动壳长期并存）**，共享同一套业务实现模块（`common` / `*-api` / `system` / `auth` / `ext-*` / `biz-*`），零双份业务代码漂移。

---

## 1. 双态并存拓扑

```text
ateng-solid-server/
├── pom.xml                        # 父 POM：dependencyManagement 顺序 ateng-boot-bom -> ateng-cloud-bom -> spring-boot-dependencies
├── ateng-solid-common/            # 共享内核：常量、100xxx 错误码、上下文原语、DomainEventPublisher 端口
├── ateng-solid-system-api/        # 纯 Java 契约：Remote*Service、DTO/VO、枚举、102xxx 错误码（零框架注解）
├── ateng-solid-system/            # 系统实现：/admin/system/**、实现 *-api、db/migration/system/
├── ateng-solid-auth/              # 认证实现：/admin/auth/**、101xxx（仅依赖 common + system-api，零自建表）
├── ateng-solid-ext-* / biz-*/     # 装配期扩展与二开（首期不建空目录，两态通用）
├── ateng-solid-bootstrap/         # 装配态 1：单体宿主（SolidApplication + Spring 本地事件桥接，same-token=false）
└── ateng-solid-cloud/             # 装配态 2：微服务宿主群（Spec 5 并列挂载，长期共存）
    ├── ateng-solid-cloud-gateway/ # 网关：/admin/auth/** & /admin/system/** 路由 + 集中鉴权 + SA-SAME-TOKEN
    ├── ateng-solid-cloud-auth/    # 认证壳：@DubboReference 桥接 Remote*Service + 跨服事件桥接
    └── ateng-solid-cloud-system/  # 系统壳：薄 Facade 包装 @DubboService 委托给 system 原生实现
```

---

## 2. `system-api` 纯净律与“裸 DTO + 抛异常”双态统一语义

- **零运行态框架侵入**：`ateng-solid-system-api` 仅依赖 `ateng-solid-common`、`jakarta.validation`、`lombok`；严禁引入 `spring-boot-starter-web`、`mybatis-plus`、`spring-data-redis`、`ateng-boot-web`、`ateng-boot-mybatis-plus`、`@FeignClient`、`@DubboService`、`@DubboReference` 或持久化 `Entity` / `IPage<T>`。
- **裸返回值 + 直接抛异常**：跨模块接口统一命名 `Remote*Service`（如 `RemoteUserService`、`RemoteTenantService`、`RemotePermissionService`、`RemoteAuditLogService`），方法**直接返回裸 DTO/VO/`List`/`Boolean`（严禁返回 `Result<T>`）**，业务异常直接 `throw new BusinessException(...)`。原因：避免 `ateng-cloud-dubbo` 消费端拦截器与单体 Bean 直调在 `Result<T>` 包装上产生语义分歧，确保单体直调、Dubbo 3 Triple RPC（并存首选）与 Feign 自适应解包行为 100% 一致。
- **`auth` 绝对隔离**：`ateng-solid-auth` 的 `pom.xml` 严禁依赖 `ateng-solid-system` 与 `ateng-cloud-all`（单体期不引入任何 Cloud 运行时依赖）。

---

## 3. 六大并存接缝（配合 ArchUnit 守护）

1. **路由对称（Gateway Symmetry）**：全部管理端接口按服务域挂接 `/admin/auth/**` 与 `/admin/system/**`，前端 `ateng-solid-admin-web` 零修改即可直连单体或网关。
2. **数据库归属与域内 Flyway**：严禁物理外键（`FOREIGN KEY`）、跨模块 SQL JOIN 与跨模块注入 `*Mapper`；首期 `auth` 零自建持久化表（会话/验证码/防爆破走 Redis，登录日志通过事件或 `RemoteAuditLogService` 交由 `system` 落库）；Flyway 迁移脚本随域内聚于 `ateng-solid-system/src/main/resources/db/migration/system/`，兼容单体单库与微服务分库 + 独立 `flyway.table`。
3. **跨模块事件、RPC 与运行时 SPI 桥接**：
   - 跨模块异步通知收敛至 `DomainEventPublisher`（单体用 Spring `ApplicationEvent`，Cloud 壳替换为 MQ/异步 RPC）；跨模块同步调用在单体直注 Spring Bean，在 Cloud 壳由薄代理包装 `@DubboService` / `@DubboReference`；
   - `ateng-solid-system` 内已沉淀的 3 个运行时 SPI 须在 `Spec 5` 做好 Cloud 态对称闭环：`SolidUserRolePermissionLoader`、`TenantHeaderSwitchValidatorImpl`（协同网关 `ateng-cloud#201`），以及 `@OperateLog` 存储适配 `SolidOperateLogStorageProvider`（需为非 `system` 微服务壳补充事件或 `RemoteAuditLogService` 远端写日志通路，详见 [`ADR-0003`](../adr/0003-monolith-and-cloud-coexistence-architecture.md) §2.4）。
4. **去 Servlet 耦合、无状态与可观测多壳适配**：Service 与领域层不直接读取 `HttpServletRequest`/`HttpServletResponse`/`SaSession`，统一经 `UserContextHolder`、`TenantContextHolder`、`DataPermissionContextHolder`、`LoginHelper` 获取上下文；进程内不持有验证码/限流/会话可变内存状态，确保与 `ateng-cloud` 上下文透传语义一致。微服务各壳（含无库 `cloud-auth` 与 WebFlux `cloud-gateway`）复用 [`ADR-0007`](../adr/0007-observability-and-actuator-governance.md) 管理面治理基线时须独立裁剪 `readiness` 探针组与安全适配。
5. **网关 `Same-Token` 与超管切租户协同**：单体设 `ateng.boot.satoken.same-token.enabled=false`；微服务启动壳设为 `true` 配合网关签发 `SA-SAME-TOKEN`（`ateng-cloud#202`），并协同网关集中鉴权与下游 `AtengSaTokenInterceptor` 租户头防重复覆盖（`ateng-cloud#201`）。
6. **配置收口、BOM 顺序与第 16 条架构守卫**：业务配置收口于 `io.github.atengk.solid.*.properties.*`（前缀 `ateng.solid.*`，支持 `Duration`/`DataSize`，禁 `@RefreshScope` 与零散 `@Value`，使用 `spring.config.import`）；并存 `ateng-cloud-bom` 时，父 `pom.xml` `<dependencyManagement>` 必须将 **`ateng-boot-bom` 声明在 `ateng-cloud-bom` 之前**，并发起前核对 `ateng-cloud:1.1.0` 与 `ateng-boot` 增量版本兼容矩阵、规划 `ArchitectureGuardTest` 第 16 条（禁共享核引入 Cloud/Dubbo 依赖）及多壳发布构建产物矩阵。

> 演进中遇 `ateng-boot` / `ateng-cloud` 能力缺口或缺陷，严格遵循 [`foundation-first-gate.md`](./foundation-first-gate.md)：先向底座提交通用 Issue 并在增强清单登记，**未经用户明确确认严禁直接修改外部底座仓库源码**（获准后方可在底座 `dev` 改造并用 `-SNAPSHOT` 协同验证）；待全阶段收口后统一发布底座正式版并锁回 `main`。
