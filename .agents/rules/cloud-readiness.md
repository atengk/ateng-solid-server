# Cloud-Readiness 同源双装配与并存硬边界守则 (cloud-readiness.md)

> **作用域**：`ateng-solid-server` 全模块开发与代码评审。
> **核心架构定位**：采用**“同源双装配（单体先行稳固 + 预留 `ateng-cloud` 微服务并存扩展壳）”**模型 —— 当前专注稳固 5 模块单体研发，同时用工程边界与 ArchUnit 锁死六大并存接口；单体稳固后直接在同仓并列挂出 `cloud-*` 启动壳复用同一份核心业务代码，实现**单体与微服务两套并存、零双份代码漂移**。

---

## 1. “现在建净界，后置挂壳体”模块依赖拓扑

```text
【当前稳固期 — 5 模块单体】
ateng-solid-bootstrap  ──▶  ateng-solid-auth    ──▶  ateng-solid-system-api  ──▶  ateng-solid-common
                       └──▶ ateng-solid-system  ──▶  ateng-solid-system-api  ──▶  ateng-solid-common

【后续并存扩展 — 同仓并列挂载 Cloud 启动壳，核心 4 模块零改动】
ateng-solid-cloud-gateway  (ateng-cloud-gateway)
ateng-solid-cloud-auth     (ateng-cloud-*) ──▶ ateng-solid-auth   ──▶ ateng-solid-system-api ──▶ Dubbo/Feign 代理
ateng-solid-cloud-system   (ateng-cloud-*) ──▶ ateng-solid-system ──▶ ateng-solid-system-api ──▶ 实现暴露
```

### 1.1 `ateng-solid-system-api` 纯净律与“裸 DTO/VO + 抛异常”统一语义
- **零运行态注解侵入**：仅依赖 `ateng-solid-common` 与最小校验/序列化注解（`jakarta.validation`、`lombok`）；**严禁引入** `spring-boot-starter-web`、`mybatis-plus`、`spring-data-redis`、`ateng-boot-web`、`ateng-boot-mybatis-plus`、`@FeignClient`、`@DubboService` 或 `@DubboReference`。
- **双态调用零分裂契约（重要）**：
  - 跨模块接口统一以 `Remote*Service` 命名（如 `RemoteUserService`、`RemoteTenantService`、`RemotePermissionService`、`RemoteAuditLogService`），入参/出参均为不可变 DTO/VO，严禁暴露 `Entity` 或 `IPage<T>`。
  - **所有 `Remote*Service` 方法统一直接返回裸 `DTO`/`VO`/`List`/`Boolean`（严禁返回 `Result<T>`），业务异常统一直接 `throw new BusinessException(...)`**！原因：`ateng-cloud-dubbo` 消费端拦截器与单体 Spring Bean 直调在返 `Result<T>` 时存在“自动转抛 vs 返回包装对象”语义分歧，统一裸 DTO + 抛异常可保证单体本地直调、`Dubbo 3 Triple` RPC（并存默认首选）与 `Feign` 自适应解包三者运行行为 100% 一致。
- **`ateng-solid-auth` 绝对隔离律**：
  - `pom.xml` 中只允许依赖 `ateng-solid-common`、`ateng-solid-system-api` 与底座 Starter，**绝对禁止依赖 `ateng-solid-system` 与 `ateng-cloud-all`**（单体期内不引入任何 Cloud 运行时依赖）。

---

## 2. 六大预留并存口子（ArchUnit 自动化守护）

1. **接口路由对称（Gateway Symmetry）**：
   - 所有接口必须挂载服务域前缀：`/admin/auth/**`（归属 `auth`）、`/admin/system/**`（归属 `system`）；
   - 后续接入 `ateng-cloud-gateway` 按前缀路由转发时，前端 `ateng-solid-admin-web` 零修改即可直连单体或网关两套后端。
2. **领域数据库归属与零 `auth` 建表**：
   - 严禁定义数据库物理外键（`FOREIGN KEY`）；严禁跨模块 SQL JOIN 或跨模块注入 `*Mapper`；
   - 首期 `auth` **保持零自建持久化表**（会话/验证码/防爆破存储于 Redis），登录日志等写库动作通过事件端口/`RemoteAuditLogService` 投递交由 `system` 落库；
   - Flyway 迁移脚本遵循按领域目录内聚组织原则，同时兼容单库统一建表与未来微服务按域独立部署库 + 独立 `flyway.table`。
3. **跨模块异步事件端口 (`DomainEventPublisher`)**：
   - 跨模块异步通知（如登录日志写入、会话下线审计等）在编码期收敛至统一轻量领域事件发布端口（单体默认使用 Spring `ApplicationEvent` 异步监听器实现），未来并存微服务时可在 Cloud 启动壳无缝替换为 RocketMQ/Kafka 或 `Remote*Service` 实现，业务核心零重构。
4. **无状态与上下文去 Servlet 耦合**：
   - Service 与核心领域层禁止直接注入或读取 `HttpServletRequest`、`HttpServletResponse`、`SaSession` 底层原生 API；
   - 统一经 `UserContextHolder`、`TenantContextHolder`、`DataPermissionContextHolder`、`LoginHelper` 读取上下文，本地进程内严禁持有验证码/限流/会话可变内存状态，确保与 `ateng-cloud` Feign/Dubbo 拦截器上下文透传语义 100% 一致。
5. **网关 `Same-Token` 与超管切租户协同预留**：
   - 单体期关闭内部同源校验（`ateng.boot.satoken.same-token.enabled=false`）；微服务启动壳开启 `true`，配合网关签发 `SA-SAME-TOKEN`（详见 `ateng-cloud#202`）；
   - 微服务下游若开启网关集中鉴权与超管切租户（`ateng-cloud#201`），须注意关闭或协同下游 `AtengSaTokenInterceptor` 的自动会话覆盖策略。
6. **配置与 BOM 升级防覆盖守则**：
   - 业务扩展配置统一收口于 `io.github.atengk.solid.*.properties.*`（前缀 `ateng.solid.*`），支持 `Duration` 与 `DataSize`，**严禁**使用 `@RefreshScope` 与零散 `@Value`，加载统一遵循 `spring.config.import`；
   - **BOM 顺序铁律**：未来引入 `ateng-cloud-bom` 与升级版 `ateng-boot-bom`（如 `1.2.1+`）并存时，父 `pom.xml` 的 `<dependencyManagement>` **必须将 `ateng-boot-bom` 声明在 `ateng-cloud-bom` 之前**，防止旧传递基线覆写新版本 `ateng-boot`。

---

## 3. 底座缺口处理红线

凡在落地单体或后续扩展 Cloud 启动壳期间，遇到 `ateng-boot` 或 `ateng-cloud` 缺失能力或存在 Bug，**必须严格触发 [底层缺口即停工规范](./foundation-first-gate.md)**：先暂停对应特性开发，向 `atengk/ateng-boot` 或 `atengk/ateng-cloud` 提交 Issue 并待底座满足发版后再恢复开工。
