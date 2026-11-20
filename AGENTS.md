# ateng-solid-server 后端工程规范 (AGENTS.md)

本文件为 `ateng-solid-server` 独立仓库的完整自洽研发规范，同时配合公共同步规约 [`.agents/rules/contracts.md`](./.agents/rules/contracts.md)、[`.agents/rules/foundation-first-gate.md`](./.agents/rules/foundation-first-gate.md) 与同源双装配守则 [`.agents/rules/cloud-readiness.md`](./.agents/rules/cloud-readiness.md) 生效。单开本子仓即可独立完成研发、测试与交付。

---

## 1. 技术基线与依赖规范

- **运行基线**：Java 21 LTS（启用虚拟线程 `spring.threads.virtual.enabled=true`）、Spring Boot `3.3.13`、Maven `3.9+`；基础坐标 `io.github.atengk`，基础包名 `io.github.atengk.solid.*`。
- **BOM 统管与按需引入**：父 POM 通过 `<scope>import</scope>` 导入 `ateng-boot-bom`（`1.2.0`+），各业务模块按需引入具体 `ateng-boot-*` Starter（如 `core`、`web`、`mybatis-plus`、`redis`、`satoken`、`excel`、`storage`、`flyway`、`observability`）；**严禁**笼统依赖 `ateng-boot-all`，**单体业务模块禁止引入 `ateng-cloud-*` 运行时依赖**（未来并存微服务引入 `ateng-cloud-bom` 时须确保 `ateng-boot-bom` 声明在其之前）。
- **底层缺口即停工与零补丁硬门禁**：开发任何通用或基础设施级功能时，若 `ateng-boot` 或 `ateng-cloud` 不足或存在缺陷，**必须立即暂停该特性编码并向上游提交通用改造 Issue（严禁在上层写临时绕过/降级代码将就，严禁在底座硬编码本项目约定）**，待底座独立验收并发版后再恢复上层开发，详见 [`.agents/rules/foundation-first-gate.md`](./.agents/rules/foundation-first-gate.md)。
- **三层扩展界限与装配期插拔（内核 vs `ext-*`/`biz-*`）**：通用技术能力沉入 `ateng-boot`/`ateng-cloud`；认证与通用系统治理保留于 `ateng-solid-{common,system-api,system,auth}` 最小稳定内核；扩展特性与行业二开（`ateng-solid-ext-*` / `biz-*`）采用装配期独立 Maven 模块 + `AutoConfiguration.imports` + 内核 SPI（`AuthGrantHandler`、`DomainEventPublisher`、`SaTokenWhitelistProvider`）+ 独立 Flyway 迁移，只依赖 `*-api` 与 `common`，严禁修改内核源码或向内核表直接加列（首期不建空模块）。
- **零臃肿依赖**：严禁引入 `hutool-all`、`guava`、`commons-collections` 等重型工具包；通用原语优先使用 JDK 21 与 `ateng-boot-core`（`IdUtil`、`JsonUtil`、`PasswordUtil`、`DesensitizeUtil`、`BusinessAssert`、`ValidatorUtil`、`IBaseEnum`）。
- **不强依赖 OpenAPI**：不将 `ateng-boot-openapi` 作为代码生成或联调前提，Controller/DTO/VO 手写清晰 Javadoc 并与前端契约 1:1 对齐。

---

## 2. 5 模块 Cloud-Ready 拓扑与隔离红线

为支持“**同源双装配（先稳固单体、后置并列挂载 `ateng-cloud` 微服务启动壳，两套形态长期并存共用同一核心业务）**”，全仓当前采用精简 **5 模块架构**（单体期不提前建立空跑 Cloud 壳）：

1. **`ateng-solid-common`**：全局常量、公共错误码（`100xxx`）、上下文与事件端口原语、基础枚举/DTO/VO。
2. **`ateng-solid-system-api`**（仅依赖 `common`）：**纯 Java 对外契约层**（`RemoteUserService`、`RemoteTenantService`、`RemotePermissionService`、`RemoteAuditLogService` 等接口、跨模块 DTO/VO、系统域枚举与错误码 `102xxx`），**绝对禁止依赖 Spring Web、MyBatis-Plus、Redis、持久层 Entity 或 `@FeignClient`/`@Dubbo*` 注解**；方法统一**直接返回裸 DTO/VO（禁返回 `Result<T>`）并在异常时直接 `throw new BusinessException(...)`**，以保证单体直调、Dubbo Triple RPC（并存默认首选）与 Feign 自适应解包三者语义 100% 一致。
3. **`ateng-solid-system`**（依赖 `common`、`system-api`）：实现 `*-api` 契约接口，内聚 `sys_*` 的 Controller（`/admin/system/**`）、Service、Mapper、Entity。
4. **`ateng-solid-auth`**（**仅依赖 `common` 与 `system-api`，严禁依赖 `system` 实现包**）：认证中心（`/admin/auth/**`）、验证码、登录防爆破、会话与在线用户治理，错误码段 `101xxx`。
5. **`ateng-solid-bootstrap`**：单体聚合启动入口、`application*.yml`、Flyway 迁移脚本与跨模块事件装配。

### 跨模块硬边界（面向 `ateng-cloud` + ArchUnit 守卫）
- 严禁跨模块注入对方的 `Mapper` 或执行跨模块业务表 SQL JOIN；
- 严禁跨模块传递持久化 `Entity`（通信一律走 `*-api` 接口与 DTO/VO）；
- 严禁在表结构中使用数据库物理外键（`FOREIGN KEY`）；
- 单测须纳入 **ArchUnit 架构守卫**，自动校验“Controller 零 Entity 出入参”、“`auth` 零依赖 `system` 实现类与 Mapper”、“禁止 `@Autowired` 字段注入”。

---

## 3. 核心编码、Java 21 与数据安全契约

### 3.1 依赖注入与分层隔离
- **强制构造器注入**：统一采用 Lombok `@RequiredArgsConstructor` 注入 `private final` 依赖字段；**绝对禁止 `@Autowired` 属性字段注入**。
- **Controller 薄层与显式强类型响应**：Controller 必须显式返回 `Result<T>`（分页入参 `PageQuery`，出参 `PageResult<VO>`），禁止 `@ResponseBodyAdvice` 自动包装；**100% 禁止 Controller 接收或返回持久层 Entity**。
- **入参校验双防线**：Controller 使用 `jakarta.validation`（`@Valid` / `@Validated`、批量用 `ValidList<E>`、分组用 `ValidationGroups`）；Service 使用 `BusinessAssert` 与 `ValidatorUtil`。
- **单例无状态与时间 API**：`@Service`/`@Component`/`@RestController` 保持无状态；全量使用 `java.time.*`（严禁 `java.util.Date`、`Calendar`），全量使用 `jakarta.*`（严禁 `javax.*`）。

### 3.2 持久层实体阶梯、多租户与唯一键防冲突
- **实体基类与审计列**：
  - 核心业务/组织/配置表继承 **`BaseDeleteIdEntity`**（`deleted BIGINT DEFAULT 0`，软删动态回填主键 `id`；含 `@Version Integer version` 乐观锁；`createBy`/`updateBy` 为 `String`，DDL 为 `VARCHAR(64)`）；
  - 流水日志表（`sys_oper_log`、`sys_login_log`）继承 **`BaseIdEntity`**（无软删与乐观锁）。
  - 联合唯一索引标准：`(tenant_id, biz_key, deleted)`（平台共享表为 `(biz_key, deleted)`），彻底避免软删重建报 Duplicate Key。
- **行级多租户（`BIGINT` 对齐 `ateng-boot`）**：
  - 租户隔离表统一携带 `tenant_id BIGINT NOT NULL DEFAULT 0`（平台默认租户 ID 为 `0`，业务编码 `tenant_code = '000000'`，与 `DefaultTenantLineHandler` 原生 `LongValue(0)` 完美匹配）；
  - 平台免过滤白名单（`ignore-tables`）：`sys_tenant`、`sys_tenant_package`、`sys_menu`、`sys_dict_type`、`sys_dict_data`、`flyway_schema_history`。
  - 仅允许租户 `0` 且具备 `super_admin` 身份的超管受控通过 `X-Tenant-Id` 切换视角；普通用户强制锁定会话归属租户。
- **Schema 版本化迁移**：采用 `ateng-boot-flyway`（内置生产 `clean` 硬熔断与初始化顺序屏障），脚本按域目录内聚于 `db/migration/system/`（首期置于 `ateng-solid-bootstrap/src/main/resources/db/migration/system/`，并预留多域独立 `locations` 与独立 `flyway.table` 扩展口子）。

### 3.3 安全、审计与虚拟线程并发
- **后台多账号鉴权**：管理端路由 `/admin/**` 绑定 `loginType = "admin"`（请求头 `satoken-admin`）；超级管理员角色标识收敛为 **`super_admin`**（严禁普通租户角色命名为 `admin`）；权限码遵循三段式 `<域>:<资源>:<动作>`。
- **口令与防爆破**：口令统一使用 `PasswordUtil.hash/matches`（纯 JDK `PBKDF2WithHmacSHA256`）；防爆破调用 `LoginFailureLockService` 时显式隔离租户与登录体系维度（如 `tenantId + ":" + loginType + ":" + username`）。
- **内存真明文不变量**：内存中始终维持原始明文；持久化加密收敛于 `@FieldEncrypt`，接口展示与 Excel 导出脱敏收敛于 `@Sensitive` / `@ExcelSensitive`。
- **虚拟线程防 Pinning**：涉及 I/O 或临界区同步统一使用 `ReentrantLock` 替代 `synchronized`；异步任务使用 `ateng-boot-core` 受管线程池与 `AsyncContext` 透传四维上下文，**严禁自建野线程**。
- **因果异常与日志规范**：禁止空 `catch`、`System.out.println`、`e.printStackTrace()` 与“日志后原样重抛”；统一转换或抛出 `BusinessException(IResultCode, msg, cause)` 保留因果链，使用 `@Slf4j` 输出，全局切面严禁外泄 SQL/堆栈。

---

## 4. 双模配置、测试准出与 Git 纪律

- **双模配置隔离**：云端 MySQL / Redis 凭据仅写入被 `.gitignore` 忽略的 `application-local.yml`（通过 `optional:classpath:application-local.yml` 挂载），仓库内仅提交大写占位符模板 `application-local.example.yml`。
- **离线自洽单测铁律**：单测与集成测试默认依托 H2（`MODE=MySQL`）与 `ateng-boot` 内存降级 100% 离线跑通；修改上下文后必须在 `@AfterEach` 清理 `UserContextHolder`、`TenantContextHolder`、`DataPermissionContextHolder`、MDC；源码修改后须先通过 `mvn clean test -B -T 1C -nsu` 再汇报交付。
- **双分支与安全暂存**：`main` 只读发布，`dev` 活跃开发；未获用户明确“提交/推送”指令前修改保留在工作区；暂存必须显式指定文件路径（严禁 `git add .` / `-A`），遵循 Conventional Commits（可通过 `bash scripts/commit.sh` 提交）。
- **跨仓本地联调与版本守卫**：本地联调未发版的 `ateng-boot` / `ateng-cloud` 时，底座必须采用递增 `-SNAPSHOT`（如 `1.2.1-SNAPSHOT`）执行 `mvn install`（严禁覆写正式版号污染 `~/.m2`）；正式提交前必须锁回已发布的递进正式版，`.githooks/pre-commit` 自动拦截暂存区 `pom.xml` 中的底座 `-SNAPSHOT` 版本。
- **文件注释与文档同步**：新建源码类标配中文 Javadoc、`@author Ateng`、`@since YYYY-MM-DD`；核心业务流程 >= 2 步才标 `// 1.` 步骤注释；新增 API/配置/错误码须同步更新对应文档与 `CONTEXT.md`。

---

## 5. AI 交付前自检清单 (Pre-flight Checklist)

- [ ] **模块与 Cloud 边界**：`auth` 是否零依赖 `system` 实现与 Mapper？`*-api` 是否保持无框架注解且统一返回裸 DTO/VO（不返回 `Result<T>`）？是否零跨模块 SQL JOIN、物理外键与跨模块领域事件穿透？
- [ ] **零 Entity 泄漏**：Controller 出入参是否 100% 隔离持久层 Entity，统一返回显式 `Result<T>` / `PageResult<VO>`？
- [ ] **多租户与超管防越权**：隔离表是否含 `tenant_id BIGINT`？超管角色是否收敛为 `super_admin` 且未放行 `"admin"`？
- [ ] **现代 Java 21 规范**：是否全量使用 `@RequiredArgsConstructor` 构造器注入、`jakarta.*`、`java.time.*` 与 `ReentrantLock`？
- [ ] **配置规范**：自定义配置是否全量收敛至 `@ConfigurationProperties(prefix = "ateng.solid.*")` 且无 `@RefreshScope` / 零散热更 `@Value`？
- [ ] **空安全与异常处理**：列表查询无匹配是否返回空集合而非 `null`？异常是否保留因果链且无 Log-and-Throw？
- [ ] **验证与机密卫生**：是否已通过 `mvn clean test -B -T 1C -nsu` 全绿（含 ArchUnit 守卫）？无真实凭据硬编码？

---

## 6. 专职规约与文档导航

- 底层缺口即停工硬门禁（同步副本）：[`.agents/rules/foundation-first-gate.md`](./.agents/rules/foundation-first-gate.md)
- 跨端通信与数据契约（同步副本）：[`.agents/rules/contracts.md`](./.agents/rules/contracts.md)
- 同源双装配与并存边界守则：[`.agents/rules/cloud-readiness.md`](./.agents/rules/cloud-readiness.md)
- 全仓上下文拓扑：[`CONTEXT-MAP.md`](./CONTEXT-MAP.md)
- 后端架构决策记录：[`docs/adr/README.md`](./docs/adr/README.md)
- 数据库建模与 Flyway 规范：[`docs/specs/database-and-flyway-spec.md`](./docs/specs/database-and-flyway-spec.md)
- 认证、RBAC 与 `@DataScope` 规范：[`docs/specs/auth-rbac-and-datascope-spec.md`](./docs/specs/auth-rbac-and-datascope-spec.md)
- 接口契约与业务错误码明细：[`docs/specs/api-and-error-codes-spec.md`](./docs/specs/api-and-error-codes-spec.md)
- 工程配置与 ArchUnit 守卫规格：[`docs/specs/configuration-and-archunit-spec.md`](./docs/specs/configuration-and-archunit-spec.md)

---

## Agent skills

### Issue tracker

本仓库使用 GitHub Issues（通过 `gh` CLI）跟踪后端需求、规格与缺陷。详见 [`docs/agents/issue-tracker.md`](./docs/agents/issue-tracker.md)。

### Triage labels

采用标准 5 角色分诊标签词表（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。详见 [`docs/agents/triage-labels.md`](./docs/agents/triage-labels.md)。

### Domain docs

本仓库采用多上下文（multi-context）结构（根 `CONTEXT-MAP.md` + 各子模块 `CONTEXT.md` + `docs/adr/`）。详见 [`docs/agents/domain.md`](./docs/agents/domain.md)。
