# ADR-0001: 5 模块同源双装配架构与 `*-api` 物理契约层设计

- **状态**：`Accepted`（2026-11-18 增量修订：确立“单体 + 微服务同源并存”演化路径）
- **日期**：2026-11-18
- **作者**：Ateng
- **关联模块**：`common`、`system-api`、`system`、`auth`、`bootstrap`（预留后续 `cloud-*` 并存启动壳）

---

## 1. 背景与业务痛点

`ateng-solid-server` 首期需要以模块化单体快速稳固落地；但系统最终目标并非“一次性把单体彻底重构掉变成微服务”，而是要求**单体版与基于 `ateng-cloud` 的微服务版（`solid-gateway`、`solid-auth`、`solid-system`、`solid-biz-*`）能够长期两套并存**，同时避免双仓复制分叉造成的双倍维护灾难与代码漂移。

若维持传统的 4 模块或仅口头规范，或在 `*-api` 直接耦合 RPC 注解/包装体：
1. `auth` 在编译期仍可见 `system` 的 `*Mapper` 与 `*Entity`，极易产生隐式依赖无法拆分启动；
2. 若在 `system-api` 接口贴 `@FeignClient` 会污染单体编译环境；若接口返回 `Result<T>`，则会因单体直接返回 `Result.fail` 与 `ateng-cloud-dubbo` `DubboContractAssertFilter` 自动转抛 `BusinessException` 导致两套运行态语义分裂。

---

## 2. 核心决策

1. **采用“同源双装配（现在建净界，后置挂壳体）”拓扑**：
   - 当前阶段全仓保持精简 5 模块（`common`、`system-api`、`system`、`auth`、`bootstrap`），**单体期暂不提前建空壳微服务工程，不引入 `ateng-cloud-*` 运行时依赖**；
   - `common`、`system-api`、`system`、`auth` 作为共享业务核心保持无运行态假设；未来在单体稳固后，同仓并列挂载微服务启动壳（`cloud-gateway`、`cloud-auth-app`、`cloud-system-app`）复用完全同一套核心业务模块。
2. **物理隔离 `ateng-solid-system-api` 与“裸 DTO/VO + 抛异常”统一语义**：
   - `system-api` 仅依赖 `common` 与最小校验/序列化注解，**严禁引入 Web/MyBatis/Redis 或 `@FeignClient`/`@Dubbo*` 注解**；
   - `Remote*Service` 统一直接返回裸 `DTO/VO/List/Boolean`（禁返回 `Result<T>`），业务失败统一 `throw new BusinessException(...)`，保障单体直调、`ateng-cloud-dubbo` Triple（并存默认首选）与 `ateng-cloud-feign` 自适应解包行为 100% 一致。
3. **预留六大并存边界 + ArchUnit 守护**：
   - 对称前缀（`/admin/auth/**`、`/admin/system/**`）；`auth` 零自建持久化表、跨模块零物理外键与 SQL JOIN；按域组织 Flyway 脚本兼容性；`DomainEventPublisher` 领域事件端口隔离异步审计/日志；无 Servlet API 与进程内状态耦合；`@ConfigurationProperties` 禁 `@RefreshScope` 与零散 `@Value`。
   - ArchUnit 单测自动化守护模块边界、Controller 零 Entity 泄漏与禁 `@Autowired` 字段注入。

---

## 3. 后果与权衡

- **优点**：一套前端、一套数据库脚本、一套核心业务逻辑，通过不同的启动宿主即可零漂移同时交付单体包与微服务集群包。
- **代价**：新增跨模块调用必须走 `*-api` 接口与纯 DTO/VO；异步通信需经领域事件抽象，不能图省事直接在 `auth` 中注入 `system` 的实现或 Mapper。
