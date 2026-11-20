---
trigger: always_on
description: "ateng-solid-server 5 模块同源双装配（单体与 ateng-cloud 微服务长期共存）架构边界与 ArchUnit 守护守则"
---

# Cloud-Readiness 同源双装配与并存硬边界守则 (cloud-readiness.md)

> **作用域**：`ateng-solid-server` 全模块开发与代码评审。
> **核心架构定位**：采用**“同源双装配（单体先行稳固 + `ateng-cloud` 微服务长期并存）”**模型 —— 不是上线微服务后抛弃单体，而是两套运行形态长期在同一套核心业务模块（`common` / `*-api` / `system` / `auth` / `ext-*` / `biz-*`）之上并行交付，通过物理边界与启动壳桥接确保**零双份业务代码漂移**。

---

## 1. 同仓双态并存目录与模块依赖拓扑

```text
ateng-solid-server/
├── pom.xml                                  # 父 POM：dependencyManagement 顺序导入 ateng-boot-bom -> ateng-cloud-bom(并存期) -> spring-boot-dependencies
├── ateng-solid-common/                      # 【共享内核】常量、通用错误码(100xxx)、上下文原语、DomainEventPublisher 端口
├── ateng-solid-system-api/                  # 【共享契约】纯 Java：Remote*Service、DTO/VO、枚举与错误码(102xxx) —— 零框架注解
├── ateng-solid-system/                      # 【共享实现】系统域 Controller(/admin/system/**)、Service、Mapper、Entity
│   └── src/main/resources/db/migration/system/   # Flyway DDL & Seed 随域内聚，单体与 Cloud 共用同一份 SQL
├── ateng-solid-auth/                        # 【共享实现】认证域 Controller(/admin/auth/**)、AuthGrantHandler SPI（仅依赖 common + system-api）
│
├── ateng-solid-ext-*/                       # 【二期共享扩展，首期不建空目录】装配期插拔，两态通用
├── ateng-solid-biz-*/                       # 【二开行业业务，首期不建空目录】biz-*-api + biz-*，两态通用
│
├── ateng-solid-bootstrap/                   # 【装配态 1：单体宿主（首期落地，长期保留）】
│   ├── .../SolidApplication.java            # 聚合启动 auth + system + 本地 Spring ApplicationEvent 桥接 + 超管引导器
│   └── .../application*.yml                 # 单体总配置（same-token.enabled=false）
│
└── ateng-solid-cloud/                       # 【装配态 2：微服务宿主群（Spec 5 并列挂载，长期共存）】
    ├── pom.xml                              # Cloud 壳子聚合 POM（按需引入 ateng-cloud-* 运行时）
    ├── ateng-solid-cloud-gateway/           # 网关壳：/admin/auth/** & /admin/system/** 路由转发 + 集中鉴权 + SA-SAME-TOKEN 签发
    ├── ateng-solid-cloud-auth/              # 认证服务壳：依赖 ateng-solid-auth + Dubbo/Sentinel/Nacos，@DubboReference 桥接 Remote*Service Bean + 跨服事件桥接
    └── ateng-solid-cloud-system/            # 系统服务壳：依赖 ateng-solid-system + Dubbo/Sentinel/Nacos/Seata，薄 Facade 包装 @DubboService
```

### 1.1 `ateng-solid-system-api` 纯净律与“裸 DTO/VO + 抛异常”统一语义
- **零运行态注解侵入**：仅依赖 `ateng-solid-common` 与最小校验/序列化注解（`jakarta.validation`、`lombok`）；**严禁引入** `spring-boot-starter-web`、`mybatis-plus`、`spring-data-redis`、`ateng-boot-web`、`ateng-boot-mybatis-plus`、`@FeignClient`、`@DubboService` 或 `@DubboReference`。
- **双态调用零分裂契约（重要）**：
  - 跨模块接口统一以 `Remote*Service` 命名（如 `RemoteUserService`、`RemoteTenantService`、`RemotePermissionService`、`RemoteAuditLogService`），入参/出参均为不可变 DTO/VO，严禁暴露 `Entity` 或 `IPage<T>`。
  - **所有 `Remote*Service` 方法统一直接返回裸 `DTO`/`VO`/`List`/`Boolean`（严禁返回 `Result<T>`），业务异常统一直接 `throw new BusinessException(...)`**！原因：`ateng-cloud-dubbo` 消费端拦截器与单体 Spring Bean 直调在返 `Result<T>` 时存在“自动转抛 vs 返回包装对象”语义分歧，统一裸 DTO + 抛异常可保证单体本地直调、`Dubbo 3 Triple` RPC（并存默认首选）与 `Feign` 自适应解包三者运行行为 100% 一致。
- **`ateng-solid-auth` 绝对隔离律**：
  - `pom.xml` 中只允许依赖 `ateng-solid-common`、`ateng-solid-system-api` 与底座 Starter，**绝对禁止依赖 `ateng-solid-system` 与 `ateng-cloud-all`**（单体期内不引入任何 Cloud 运行时依赖）。

---

## 2. 六大并存接缝与桥接机制（ArchUnit 自动化守护）

1. **接口路由对称（Gateway Symmetry）**：
   - 所有接口必须挂载服务域前缀：`/admin/auth/**`（归属 `auth`）、`/admin/system/**`（归属 `system`）；
   - 前端 `ateng-solid-admin-web` 零修改即可透明直连单体（`bootstrap`）或微服务网关（`cloud-gateway`）。
2. **领域数据库归属、Flyway 共享与零 `auth` 建表**：
   - 严禁定义数据库物理外键（`FOREIGN KEY`）；严禁跨模块 SQL JOIN 或跨模块注入 `*Mapper`；
   - 首期 `auth` **保持零自建持久化表**（会话/验证码/防爆破存储于 Redis），登录日志等写库动作通过事件端口/`RemoteAuditLogService` 投递交由 `system` 落库；
   - Flyway 迁移脚本归属于对应业务域模块内部（如 `ateng-solid-system/src/main/resources/db/migration/system/`），兼容单体全量单库建表与微服务各服务按域独立数据库 + 独立 `flyway.table`。
3. **跨模块异步事件与 RPC 壳桥接**：
   - 跨模块异步通知（如登录日志写入、会话下线审计等）在编码期收敛至统一 `DomainEventPublisher` 端口：单体由 `bootstrap` 装配 Spring `ApplicationEvent` 异步监听器实现；微服务由 `cloud-*` 壳无缝替换为 MQ 或异步 `Remote*Service` 实现，业务核心零重构；
   - 跨模块同步调用：`bootstrap` 直接注入 `system` 的 Spring Bean；`cloud-system` 由薄代理类标注 `@DubboService` 委托给 `system` 原生实现，`cloud-auth` 通过 `@DubboReference` 注册为 `Remote*Service` Spring Bean 供 `auth` 无感注入。
4. **无状态与上下文去 Servlet 耦合**：
   - Service 与核心领域层禁止直接注入或读取 `HttpServletRequest`、`HttpServletResponse`、`SaSession` 底层原生 API；
   - 统一经 `UserContextHolder`、`TenantContextHolder`、`DataPermissionContextHolder`、`LoginHelper` 读取上下文，本地进程内严禁持有验证码/限流/会话可变内存状态，确保与 `ateng-cloud` Feign/Dubbo 拦截器上下文透传语义 100% 一致。
5. **网关 `Same-Token` 与超管切租户协同预留**：
   - 单体期关闭内部同源校验（`ateng.boot.satoken.same-token.enabled=false`）；微服务启动壳开启 `true`，配合网关签发 `SA-SAME-TOKEN`（详见 `ateng-cloud#202`）；
   - 微服务下游开启网关集中鉴权与超管切租户（`ateng-cloud#201`）时，需协同下游 `AtengSaTokenInterceptor` 的租户头策略防止重复覆盖。
6. **配置与 BOM 升级防覆盖守则**：
   - 业务扩展配置统一收口于 `io.github.atengk.solid.*.properties.*`（前缀 `ateng.solid.*`），支持 `Duration` 与 `DataSize`，**严禁**使用 `@RefreshScope` 与零散 `@Value`，加载统一遵循 `spring.config.import`；
   - **BOM 顺序铁律**：未来引入 `ateng-cloud-bom` 与升级版 `ateng-boot-bom` 并存时，父 `pom.xml` 的 `<dependencyManagement>` **必须将 `ateng-boot-bom` 声明在 `ateng-cloud-bom` 之前**，防止旧传递基线覆写新版本 `ateng-boot`。

---

## 3. 底座缺口处理红线

凡在落地单体或后续扩展 Cloud 启动壳期间，遇到 `ateng-boot` 或 `ateng-cloud` 缺失能力或存在 Bug，**必须严格触发 [底层缺口即停工规范](./foundation-first-gate.md)**：先暂停对应特性开发，向 `atengk/ateng-boot` 或 `atengk/ateng-cloud` 提交 Issue 并待底座满足发版后再恢复开工。
