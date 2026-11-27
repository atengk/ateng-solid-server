# ateng-solid-server 后端工程规范 (AGENTS.md)

`ateng-solid-server` 是 Java 21 LTS（虚拟线程）+ Spring Boot 3.3 + `ateng-boot-bom` 构建的 **5 模块同源双装配**后端核心（单体先行稳固，与 `ateng-cloud` 微服务启动壳长期并存）。包名 `io.github.atengk.solid.*`，依赖版本真源见 [`pom.xml`](./pom.xml)。

---

## 1. 模块边界与依赖铁律

- **5 模块单向依赖**：
  - `ateng-solid-common`（`100xxx` 公共常量、错误码、枚举、事件端口）；
  - `ateng-solid-system-api`（`102xxx` 纯 Java `Remote*Service` 契约与 DTO/VO，仅依赖 `common`；严禁 Web/ORM/Redis/Feign/Dubbo 注解与 Entity；方法直接返回裸 DTO/VO，异常直接抛 `BusinessException`，禁返回 `Result<T>`）；
  - `ateng-solid-system`（实现 `*-api`，`/admin/system/**`，Flyway 迁移置 `ateng-solid-system/src/main/resources/db/migration/system/`）；
  - `ateng-solid-auth`（`/admin/auth/**`，`101xxx`，仅依赖 `common` + `system-api`，严禁依赖 `system` 实现包，零自建持久化表）；
  - `ateng-solid-bootstrap`（单体启动宿主、双模配置、事件桥接与 ArchUnit 守卫）。
- **跨模块硬边界**：严禁跨模块注入对方 `Mapper`、跨模块 SQL JOIN 或数据库物理外键；二开/扩展（`ext-*`/`biz-*`）通过独立 Maven 模块 + `AutoConfiguration.imports` + SPI 装配，不改内核源码、不向内核主表加列（首期不建空目录）。
- **依赖克制**：按需引入 `ateng-boot-*` Starter（`core`/`web`/`mybatis-plus`/`redis`/`satoken`/`excel`/`storage`/`flyway`）；严禁引入 `ateng-boot-all`、首期 `ateng-boot-observability`、单体业务模块内 `ateng-cloud-*` 运行时依赖，以及 `hutool-all`/`guava`/`commons-collections`；不强依赖 `ateng-boot-openapi`。
- **底层零补丁与主干锁版门禁（Zero Workaround, Lock on `main`）**：遇 `ateng-boot` / `ateng-cloud` 能力不足或缺陷，严禁上层补丁将就、严禁底座写死本项目约定，**应先向对应底座提通用 Issue 并登记增强清单，未经用户明确确认严禁直接修改 `ateng-boot` / `ateng-cloud` 源码**；仅在获准协同改造后于底座 `dev` 本地改造并提交底座 `dev`，刷新 `-SNAPSHOT` 后可在 `dev` 继续开发、自测与提交（允许联调期远端 `dev` CI 暂红），**合入 `main` 与发版前必须锁回递进正式版**（由 `main` `pre-commit`、`pre-push`、`main` CI 与 `release.yml` 拦截 `-SNAPSHOT`）。

---

## 2. Java 21、持久层与安全硬约束

- **分层与对象映射**：强制 `@RequiredArgsConstructor` 构造器注入（禁 `@Autowired` 字段注入）；Controller 显式返回 `Result<T>` / `PageResult<VO>`（禁自动包装、100% 禁 Entity 出入参）；全量 `jakarta.*` 与 `java.time.*`；I/O 临界区用 `ReentrantLock` 替代 `synchronized` 防虚拟线程 Pinning，异步走受管线程池 + `AsyncContext`。首期模型转换使用 `ateng-boot-core` `BeanUtil` 或静态工厂（不引 `MapStruct`），写操作严禁无过滤覆盖 `id`/`tenantId`/`password`/`version`/`deleted`/审计列。
- **实体阶梯与 H2 双库兼容**：
  - 常规表继 `BaseLogicEntity`（`deleted BIGINT DEFAULT 0` 软删回填主键 `id`，无 `@Version`，`createBy`/`updateBy` 为 `VARCHAR(64)`）；高并发防覆盖表按需继 `BaseFullEntity`；流水日志与关联表继 `BaseIdEntity`；
  - 联合唯一索引采用 `(tenant_id, biz_key, deleted)`（平台共享表 `(biz_key, deleted)`）；
  - 业务 SQL 须 100% 兼容测试期 H2（`MODE=MySQL`）与生产 MySQL 8.x，严禁使用 `FIND_IN_SET`、`ON UPDATE CURRENT_TIMESTAMP` 或 `${}` 拼接。
- **多租户、认证与脱敏**：
  - 租户隔离表及 4 张关联表携带 `tenant_id BIGINT NOT NULL DEFAULT 0` 并实现 `TenantAware<Long>`（默认租户 `0` / `'000000'`，默认关闭开关 `ateng.solid.tenant.enabled=false`）；免过滤表为 `sys_tenant`/`sys_tenant_package`/`sys_tenant_package_menu`/`sys_menu`/`sys_dict_type`/`sys_dict_data`/`flyway_schema_history`。
  - 管理端 `/admin/**` 绑定 `loginType="admin"`（请求头 `satoken-admin`）；超管角色收敛为 `super_admin`（严禁放行 `"admin"`）；仅租户 `0` 且 `super_admin` 可受控切换 `X-Tenant-Id`。
  - 口令用 `PasswordUtil`，防爆破 `LoginFailureLockService` 键隔离 `tenantId:loginType:username`；持久化 `@FieldEncrypt`，列表/导出 `@FieldSensitive`/`@ExcelSensitive`，编辑详情保持真明文；关闭底座内置枚举端点（`ateng.boot.web.enum.endpoint-enabled=false`），自建 `/admin/system/enums`。
  - 扩展配置收口于 `@ConfigurationProperties(prefix = "ateng.solid.*")`，禁 `@RefreshScope` 与零散 `@Value`。

---

## 3. 测试验证、配置与交付底线

- **离线验证门禁**：交付前本地执行 `mvn clean test -B -T 1C -nsu`（H2 + 内存降级 + ArchUnit 全绿；`dev` 联调 `-SNAPSHOT` 期间允许远端 `dev` CI 暂红，以本地全绿为准，合入 `main` 前须锁版并恢复远端 CI 全绿）；单测修改线程上下文后须在 `@AfterEach` 清理 `UserContextHolder`/`TenantContextHolder`/`DataPermissionContextHolder`/MDC。
- **双模配置与 Git**：真实凭据仅存 `.gitignore` 忽略的 `application-local.yml`（仓库仅留大写占位模板 `application-local.example.yml`）；`dev` 分支开发，未获明确指令不提交/推送，暂存显式指定文件路径（禁 `git add .`/`-A`），Conventional Commits。

---

## 4. 按需必读文档（按场景触发）

| 触发场景（何时必先读） | 文档路径 |
| :--- | :--- |
| 设计或修改接口、错误码、分页、租户头、权限码、Excel/枚举协议 | [`docs/agents/contracts.md`](./docs/agents/contracts.md) |
| 编写新服务、拆分大类、处理事务防 N+1、写注释或做 Code Review | [`docs/agents/coding-standards.md`](./docs/agents/coding-standards.md) |
| 修改 `system-api`、跨模块调用/事件、Flyway、`Same-Token` 或 Cloud 装配边界 | [`docs/agents/cloud-readiness.md`](./docs/agents/cloud-readiness.md) |
| 遇 `ateng-boot`/`ateng-cloud` 底座缺口，或本地 `-SNAPSHOT` 跨仓联调 | [`docs/agents/foundation-first-gate.md`](./docs/agents/foundation-first-gate.md) |
| 功能通过测试后逆向沉淀模块 `README.md` / Specs / `CONTEXT.md` / `llms.txt` | [`docs/agents/documentation-specification.md`](./docs/agents/documentation-specification.md) |
| 查阅建表 DDL、RBAC/`@DataScope`、端点错误码、系统引擎与 ArchUnit 规格 | [`docs/specs/database-and-flyway-spec.md`](./docs/specs/database-and-flyway-spec.md) · [`auth-rbac-and-datascope-spec.md`](./docs/specs/auth-rbac-and-datascope-spec.md) · [`api-and-error-codes-spec.md`](./docs/specs/api-and-error-codes-spec.md) · [`configuration-and-archunit-spec.md`](./docs/specs/configuration-and-archunit-spec.md) · [`system-engines-and-testing-spec.md`](./docs/specs/system-engines-and-testing-spec.md) |
| 全局索引、上下文地图与后端架构决策 | [`llms.txt`](./llms.txt) · [`CONTEXT-MAP.md`](./CONTEXT-MAP.md) · [`docs/adr/README.md`](./docs/adr/README.md) |

## Agent skills

- **Issue tracker**: GitHub Issues (`gh` CLI) — [`docs/agents/issue-tracker.md`](./docs/agents/issue-tracker.md)
- **Triage labels**: 5 角色标签词表 — [`docs/agents/triage-labels.md`](./docs/agents/triage-labels.md)
- **Domain docs**: 多上下文（`CONTEXT-MAP.md` + 模块 `CONTEXT.md` + `docs/adr/`）— [`docs/agents/domain.md`](./docs/agents/domain.md)
