# 工程配置基线与 ArchUnit 架构守卫规格 (configuration-and-archunit-spec.md)

> **适用范围**：`ateng-solid-server` Maven 依赖管理、多环境 YAML 配置规范及测试期架构红线校验。

---

## 1. Maven POM 依赖边界与版本基线

- **无父 POM 锁死**：根 `pom.xml` 不继承外部 `spring-boot-starter-parent`，通过 `<dependencyManagement>` 顺序导入：
  1. `io.github.atengk:ateng-boot-bom`（**已锁定正式版 `1.2.0`，含 `#1076~#1080`、`#1093`、`#1094`、`#1134~#1137`、`#1163~#1167` 与 `ateng-boot-flyway`**）；
  2. `org.springframework.boot:spring-boot-dependencies:3.3.13`。
  > **并存期 BOM 顺序铁律**：未来在同仓新增 `ateng-solid-cloud/*` 启动壳引入 `ateng-cloud-bom` 时，**必须保证 `ateng-boot-bom` 声明在 `ateng-cloud-bom` 之前**，防止传递 BOM 覆写高版本 `ateng-boot`。
- **5 模块编译期依赖锁死矩阵**：

| 模块 | 允许依赖的内部模块 | 允许依赖的外部/底座 Starter | 绝对禁令 |
| :--- | :--- | :--- | :--- |
| `ateng-solid-common` | 无 | `ateng-boot-core`、`jakarta.validation-api`、`lombok` | 禁 Web / MyBatis-Plus / Redis / Cloud |
| `ateng-solid-system-api` | `common` | `jakarta.validation-api`、`lombok` | 禁 Web / MyBatis-Plus / Redis / Feign / Dubbo / Entity |
| `ateng-solid-system` | `common`、`system-api` | `ateng-boot-{web,mybatis-plus,redis,satoken,excel,storage}` | 禁 `ateng-boot-all`、`ateng-cloud-*`、禁反向依赖 `auth` |
| `ateng-solid-auth` | `common`、`system-api` | `ateng-boot-{web,redis,satoken}` | 禁依赖 `ateng-solid-system` 与任何持久层依赖 |
| `ateng-solid-bootstrap` | `auth`、`system` | `ateng-boot-flyway`、`commons-pool2`、`caffeine`（Spring Boot BOM 纳管）、`flyway-mysql` (`runtime`)、`software.amazon.awssdk:{s3,apache-client}` (`runtime`)、MySQL/H2、Test/ArchUnit | 禁编写业务 Controller / Service / Mapper，**首期暂不引入 `ateng-boot-observability` 与 `spring-boot-starter-actuator`** |

### 1.1 5 模块精确包拓扑与原语归属矩阵（`Spec 1` 定稿）

| 模块 | 根包与子包结构 | 核心归属原语 / 契约 / 组件 |
| :--- | :--- | :--- |
| **`ateng-solid-common`** | `io.github.atengk.solid.common.`<br>`{constant, result, enums, validator, event, spi.auth, properties}` | `SolidConstants`、`CommonBizResultCode`（`100xxx`）、`CommonStatusEnum`、`LogStatusEnum`、`PasswordPolicyValidator`、`DomainEvent` / `DomainEventPublisher` / `SpringDomainEventPublisher` / `LoginAuditEvent`、`AuthGrantHandler` SPI（`AuthGrantCommand`/`AuthenticatedPrincipal`）、`SolidTenantProperties`（`ateng.solid.tenant`） |
| **`ateng-solid-system-api`** | `io.github.atengk.solid.system.api.`<br>`{result, enums, service, dto, command}` | `SystemResultCode`（`102xxx`）、`DataScopeType`、`MenuTypeEnum`、`ConfigTypeEnum`、`VisibleStatusEnum`、4 个纯 Java `Remote*Service` 契约接口及跨模块裸 DTO/Command（严禁 Web/ORM/RPC 注解与 `Result<T>`） |
| **`ateng-solid-auth`** | `io.github.atengk.solid.auth.`<br>`{result, properties, controller, service, grant, security, vo, request}` | `AuthResultCode`（`101xxx`）、`SolidAuthProperties`（`ateng.solid.auth`）、`PasswordAuthGrantHandler`、`AuthService`、个人中心与在线会话控制器及认证安全组件（零持久层、零存储依赖） |
| **`ateng-solid-system`** | `io.github.atengk.solid.system.`<br>`{properties, bootstrap, controller, service, mapper, entity, listener, security, ...}` | `SolidSystemProperties`（`ateng.solid.init`/`user`）、`SuperAdminBootstrapper`（单体与 `cloud-system` 共用）、`Remote*ServiceImpl`、`LoginAuditEventListener`、`DataPermissionHandler`、`TenantHeaderSwitchValidator`、业务控制器/服务/Mapper 与 `db/migration/system/` |
| **`ateng-solid-bootstrap`** | `io.github.atengk.solid` | `SolidApplication`、多环境 `application*.yml` 双模基线、离线 `ArchitectureGuardTest`（严禁承载业务 Controller/Service/Mapper） |

### 扩展模块可插拔装配约定 (`ateng-solid-ext-*` / `ateng-solid-biz-*`)
- **只向内依赖**：扩展模块仅允许依赖 `ateng-solid-common`、`ateng-solid-system-api`（及自身业务所需的 `ateng-boot-*` Starter），**严禁依赖或篡改 `ateng-solid-system` / `ateng-solid-auth` 实现模块**；
- **AutoConfiguration 插拔**：通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` + `@ConditionalOnProperty` 自注册 Bean，增减 `bootstrap` POM 依赖即可无侵入完成模块启停；
- **白名单与枚举汇聚**：扩展模块通过实现 `SaTokenWhitelistProvider` 与 `@IBaseEnum` 自动接入安全防线和枚举端点，无需修改内核 YAML 配置。

---

## 2. 多环境分层配置文件约定 (`ateng-solid-bootstrap`)

| 文件 | 是否入库 | 用途与关键基线配置 |
| :--- | :---: | :--- |
| `application.yml` | 是 | 全局公共基线：① 容器与并发：Tomcat（`uri-encoding: UTF-8`、gzip ≥2KB、`accept-count: 200`、`max-connections: 8192`、`connection-timeout: 15s`、`keep-alive-timeout: 30s`）、`server.shutdown: graceful`（`20s` 相位超时）、Java 21 虚拟线程、`logging.charset.{console,file}=UTF-8`、Jackson `GMT+8`、Multipart `5MB/10MB`；② 中间件公共超时与保活：HikariCP（`connection-timeout=10000ms`、`validation-timeout=3000ms`、`idle-timeout=10m`、`max-lifetime=28m`、`keepalive-time=5m`）、Redis（`connect-timeout: 5s`、`timeout: 3s`、`key-prefix: ${SOLID_REDIS_KEY_PREFIX:solid:}`）、Redisson NAT 心跳 `ping-connection-interval=30s` & 看门狗 `30s`、两级缓存 `ateng.boot.redis.two-level.enabled=true`（`satoken:permissions` `5000/5m/30m`、`dict` `2000/5m`、`config` `5000/5m`）；③ 运行治理：`ateng.boot.core.{logging,slow-alert}`、`ateng.boot.web.{trace,shutdown(mvc-endpoint-enabled:false),slow-request,payload-log:false,operate-log,security-headers,enum,ratelimit(key-prefix:${SOLID_REDIS_KEY_PREFIX:solid:}rate_limit:,fail-open:true),idempotent(enabled:true,key-prefix:${SOLID_REDIS_KEY_PREFIX:solid:}idempotent:,fail-open:true)}`、`ateng.boot.async` 主虚拟线程停机等待与 `password-hash` 专用平台池（`4~6/1000`）、Sa-Token 安全基线、MyBatis-Plus（`block-attack`/`pagination:500`/`slow-sql:1s`/`data-permission:DENY_ALL`/多租户忽略表）、S3+Guard 存储基线 |
| `application-dev.yml` | 是 | 本地开发 Profile：独占声明 `spring.config.import: optional:classpath:application-local.yml`；Hikari（`2~10`，10s 泄漏探测）、Lettuce（`2~8`）与 Redisson（`threads=4, netty=4, pool=16, min-idle=4`）；`async.concurrency-limit=64`；`satoken.token.active-timeout=2h`；开启 `sql-log`（脱敏美化）、`web.log`、`cors`（`localhost` / `5173`）与 `io.github.atengk.solid: DEBUG` 日志 |
| `application-test.yml` | 是 | 离线测试 Profile：H2 `MODE=MySQL`、Hikari（`1~5`，关闭 keepalive 与泄漏探测）、启用 Flyway（`spring.flyway.enabled=true`）、排除 Spring Redis 自动装配并关闭 Redis/Storage/Shutdown（`0s`）/CORS/SQL-Log/Web 限流与全局默认幂等（`ateng.boot.web.{ratelimit,idempotent}.enabled=false`）、配置测试桩超管凭据与测试桩密钥，**物理隔离 `application-local.yml`** |
| `application-prod.yml` | 是 | 生产环境 Profile（零默认值 Fail-Fast）：强制外部注入 `SOLID_DB_{HOST,USERNAME,PASSWORD}`、`SOLID_REDIS_{HOST,PASSWORD}`、`SOLID_CRYPTO_SECRET_KEY`、`SOLID_AUTH_SESSION_HANDLE_SECRET`、`SOLID_S3_{ENDPOINT,BUCKET,ACCESS_KEY,SECRET_KEY}`；`flyway.enabled=${SOLID_FLYWAY_ENABLED:false}`；Hikari（`5~20` + 30s 泄漏探测）、Lettuce（`4~16`）、Redisson（`8~32`，自适应线程）；`async.concurrency-limit=256`；开启 `ateng.boot.core.logging.file` 异步双文件滚动归档（`100MB/30天/10GB`）；显式禁用 `sql-log`/`payload-log`/`springdoc`/`knife4j`/`h2.console` 与通配 CORS，保留异步脱敏 `slow-sql`、`slow-request` 与 `login-defense` |
| `application-local.example.yml` | 是 | 脱敏占位符模板（MySQL 8.4 LTS、Redis 8.x、连接池 `${SOLID_*}` 可选覆盖、S3/RustFS、密钥、`SOLID_TENANT_ENABLED` 及可选 `local` 存储注释；**零明文真实主机/凭据**） |
| `application-local.yml` | **否（`.gitignore`）** | 开发者本地覆盖文件（仅随 `dev` 或显式 `local` Profile 加载），存放 `${SOLID_*}` 映射或本机直连参数 |

### 生产与本地环境关键变量契约（`${SOLID_*}`）
- **Fail-Fast 强凭据（`prod` 必填，无默认值）**：`SOLID_DB_HOST`、`SOLID_DB_USERNAME`、`SOLID_DB_PASSWORD`、`SOLID_REDIS_HOST`、`SOLID_REDIS_PASSWORD`、`SOLID_CRYPTO_SECRET_KEY`、`SOLID_AUTH_SESSION_HANDLE_SECRET`、`SOLID_S3_ENDPOINT`、`SOLID_S3_BUCKET`、`SOLID_S3_ACCESS_KEY`、`SOLID_S3_SECRET_KEY`。
- **可调参数（带安全默认值）**：`SOLID_DB_PORT(3306)`、`SOLID_DB_NAME(ateng_solid)`、`SOLID_DB_USE_SSL(prod:true / local:false)`、`SOLID_DB_ALLOW_PUBLIC_KEY_RETRIEVAL(prod:false / local:true)`、`SOLID_DB_POOL_{MIN,MAX}`、`SOLID_DB_{CONNECTION_TIMEOUT,VALIDATION_TIMEOUT,IDLE_TIMEOUT,MAX_LIFETIME,KEEPALIVE_TIME,LEAK_DETECTION}_MS`、`SOLID_REDIS_{PORT,DATABASE,KEY_PREFIX}`、`SOLID_REDIS_POOL_{MAX,MAX_IDLE,MIN_IDLE,MAX_WAIT}`、`SOLID_REDISSON_{THREADS,NETTY_THREADS,POOL_MAX,POOL_MIN}`、`SOLID_FLYWAY_ENABLED(prod:false / local:true)`、`SOLID_LOG_{FILE_ENABLED,PATH,MAX_FILE_SIZE,MAX_HISTORY,TOTAL_SIZE_CAP}`、`SOLID_ASYNC_CONCURRENCY_LIMIT(256)`、`SOLID_CORS_{ENABLED,ALLOWED_ORIGINS}`、`SOLID_STORAGE_TYPE(s3)`、`SOLID_S3_{PATH_STYLE,AUTO_CREATE_BUCKET}`、`SOLID_TENANT_ENABLED(false)`、`SOLID_AUTH_CAPTCHA_ENABLED(true)`、`SOLID_INIT_ENABLED(true)`。

### 业务扩展配置收口 (`ateng.solid.*`)
- 按模块域内聚为 3 个强类型 `@ConfigurationProperties` 类（时间用 `Duration`，容量用 `DataSize`），**严禁**在业务类上使用 `@RefreshScope` 或 `@Value`：
  1. `SolidTenantProperties`（`common`，前缀 `ateng.solid.tenant`）：`enabled = false`、`statusCacheTtl = Duration.ofSeconds(60)`；
  2. `SolidAuthProperties`（`auth`，前缀 `ateng.solid.auth`）：`captchaEnabled = true`、`sessionHandleSecret = ""`（单机留空自动随机生成 32B 密钥并 `WARN`，集群须配置统一密钥）；
  3. `SolidSystemProperties`（`system`，前缀 `ateng.solid`）：`init.{enabled = true, superAdminUsername = "superadmin", superAdminNickname = "超级管理员", superAdminPassword = ""}`、`dataScope.maxDeptInSize = 1000`、`user.{importMaxRows = 500, maxConcurrentImports = 2, importFeedbackTtl = Duration.ofMinutes(15)}`、`cache.{dictTtl = Duration.ofMinutes(30), configTtl = Duration.ofMinutes(30)}` 等系统参数。

### 增量底座本地联调与锁版提醒（`Spec 3` / `Spec 4`）
- `B14~B17`（`ateng-boot#1171`–`#1174`）均已在上游 `dev` 闭环关单；本地开发按 [`foundation-first-gate.md`](../agents/foundation-first-gate.md) SOP 消费 `1.2.1-SNAPSHOT` 验证，**正式提交合入 `dev`/`main` 前须待 `ateng-boot` 发布递进正式版并锁回正式版本号**（`pre-commit` 自动拦截 `-SNAPSHOT`）。

---

## 3. ArchUnit 自动化架构红线 (`ArchitectureGuardTest` · 9 条硬守卫)

所有规则在 `ateng-solid-bootstrap` 的离线单元测试套件中强制执行（CI 必过）：

1. **模块物理边界守卫（`auth` 隔离）**：`..solid.auth..` 包中的任何类 **严禁** 访问 `..solid.system..`（仅允许访问 `..solid.system.api..` 与 `..solid.common..`）。
2. **契约层纯净守卫（`system-api` 零框架污染）**：`..solid.system.api..` 包中的任何类 **严禁** 访问 Spring Web (`org.springframework.web..`)、MyBatis-Plus (`com.baomidou..`)、OpenFeign (`org.springframework.cloud.openfeign..`)、Dubbo (`org.apache.dubbo..`) 或 Servlet API (`jakarta.servlet..`)。
3. **现代 Java 21 依赖注入守卫**：全仓库业务类 **严禁** 在字段上使用 `@Autowired` 或 `@Resource`（强制构造器注入 / `@RequiredArgsConstructor`）。
4. **Controller 零 Entity 泄漏守卫**：标注 `@RestController` / `@Controller` 的所有公开方法参数与返回值（含泛型参数）**严禁**出现 `..entity..` 包中的持久化实体类。
5. **启动模块纯净守卫**：`ateng-solid-bootstrap` 根装配包不承载业务 `*Mapper` 或业务 `@RestController`/`@Controller`。
6. **Service 零 Servlet 耦合守卫**：`..solid..service..` 包中的任何类 **严禁** 依赖 `jakarta.servlet..`（保障双装配与虚拟线程/异步可测性）。
7. **Mapper 零逆向依赖守卫**：`..solid..mapper..` 包中的任何类 **严禁** 依赖 `..controller..` 或 `..service..`。
8. **`system-api` 裸 DTO/VO 守卫**：`..solid.system.api..` 中所有公开方法参数与返回值（含泛型参数）**严禁** 出现 `io.github.atengk.boot.core.model.Result`（测试期硬守卫 `ADR-0001` 跨态异常语义一致性）。
9. **强类型配置收口守卫**：全仓库业务类 **严禁** 使用 `@Value`（`org.springframework.beans.factory.annotation.Value`）或 `@RefreshScope`（强制走 `@ConfigurationProperties`）。
