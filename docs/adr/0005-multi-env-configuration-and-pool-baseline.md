# ADR-0005: 多环境配置分层、Redis 双池化装配与虚线程连接池定容基线

- **状态**：`Accepted`
- **日期**：2026-11-26
- **作者**：Ateng
- **关联模块**：`ateng-solid-bootstrap`（联动 `ateng-solid-admin-web` `.env.*` 与 `VITE_API_TIMEOUT`）

---

## 1. 背景与实测事实

在完成首期 `Spec 1`~`Spec 4` 核心业务及外部云中间件真机联调后，复盘全仓配置暴露出以下工程与基础设施事实：

1. **生产 Profile 缺失与 `import` 隔离边界模糊**：原 `application.yml` 直接声明 `spring.config.import: optional:classpath:application-local.yml` 且缺失 `application-prod.yml`；若开发机存在被 `.gitignore` 忽略的 `application-local.yml` 并构建 JAR，可能导致 `prod` 或 `test` 在特定组合下受本地覆盖文件影响。
2. **Lettuce 连接池依赖静默缺失**：实测 `ateng-boot-redis` 未强制传递 `org.apache.commons:commons-pool2`，运行时成品包仅包含 `HikariCP 5.1.0`、`lettuce-core 6.3.2` 与 `redisson 3.44.0`；在 Spring Boot 3.3 下，缺失 `commons-pool2` 会导致 YAML 中的 `spring.data.redis.lettuce.pool.*` 配置**被静默忽略**。
3. **Java 21 虚拟线程下的池化与超时失配**：`application.yml` 全局开启 `spring.threads.virtual.enabled=true`，但 HikariCP、Redisson、Tomcat 容器与主异步执行器均未显式配置跨环境池大小、心跳保活与泄漏检测（另注意 Spring Boot 对 `spring.datasource.hikari.*` 超时项按 `long` 毫秒值绑定，而非 `Duration` 字符串）。
4. **无 Actuator 场景下 `/pre-stop` 隐式暴露与 `prod` 防呆告警**：`ateng-boot-web` `ProductionSanityChecker` 会在活跃 Profile 为 `prod` 时巡检 OpenAPI/Knife4j/PayloadLog/H2Console；同时 `AtengGracefulShutdownAutoConfiguration` 在无 `spring-boot-starter-actuator` 时会默认降级注册开放 MVC 端点 `/pre-stop`（`PreStopController` 不含免鉴权标记，易受外部探测或被拦截报错）。

---

## 2. 核心决策

1. **后端五档物理隔离配置体系（`base + dev + test + prod + local`）**：
   - `application.yml` 仅保留跨环境公共安全基线、Tomcat 容器调优（`uri-encoding: UTF-8`、gzip 压缩、`accept-count: 200`、`max-connections: 8192`）、`server.shutdown: graceful`（`20s` 相位超时）、Hikari/Redis 基础超时与 `ateng.boot.{core,web,async,redis,satoken,mybatis-plus,storage}` 公共策略；
   - 将 `spring.config.import: "optional:classpath:application-local.yml"` **下沉至 `application-dev.yml`**，确保 `test` 与 `prod` 物理隔离本地开发配置文件；
   - 新增 `application-prod.yml`：所有敏感凭据与内部地址（`SOLID_DB_HOST`/`USERNAME`/`PASSWORD`、`SOLID_REDIS_HOST`/`PASSWORD`、`SOLID_CRYPTO_SECRET_KEY`、`SOLID_AUTH_SESSION_HANDLE_SECRET`、`SOLID_S3_*`）**不设默认值（Fail-Fast）**；`flyway.enabled` 默认为 `${SOLID_FLYWAY_ENABLED:false}` 避免多实例并发迁移风险；开启 `ateng.boot.core.logging.file` 异步双文件滚动归档（`100MB/30d/10GB`）；显式关闭 `sql-log`、`payload-log`、`springdoc`、`knife4j`、`h2.console` 与 `*` 通配跨域，仅保留脱敏后的 `slow-sql`、`slow-request` 与 `core.slow-alert`。
2. **显式引入 `commons-pool2` 并实施虚线程三档定容**：
   - 在 `ateng-solid-bootstrap/pom.xml` 引入 Spring Boot BOM 纳管的 `org.apache.commons:commons-pool2`，激活 `spring.data.redis.lettuce.pool.*` 使双核 Redis（Lettuce + Redisson）连接池均真实可控；
   - 按环境三档定容：
     - `test`（H2）：Hikari `1~5`、`keepalive-time=0`、禁用优雅停机等待（`0s`）确保单测秒级起停；
     - `dev`（联调共享低配中间件）：Hikari `2~10` + `leak-detection-threshold=10000`；Lettuce `min=2, max=8`；Redisson `threads=4, netty-threads=4, pool=16, min-idle=4`；
     - `prod`（环境变量可覆写）：Hikari `${SOLID_DB_POOL_MIN:5}`~`${SOLID_DB_POOL_MAX:20}` + `keepalive-time=300000` + `max-lifetime=1680000`（28 分钟，早于云 NAT/MySQL 默认 30 分钟断连）；Lettuce `4~16`；Redisson `pool=32, min-idle=8, ping-connection-interval=30s`；主虚拟异步执行器限定 `concurrency-limit`（`dev=64`/`prod=256`）及停机等待。
3. **现阶段暂不引入 Actuator 并收口 `/pre-stop` MVC 降级端点**（> 演进注记：二期已按 [ADR-0007](./0007-observability-and-actuator-governance.md) 在 `ateng-solid-bootstrap` 引入 `ateng-boot-observability`，将 `actuator-endpoint-enabled` 置为 `true` 并延续 `mvc-endpoint-enabled: false`）：
   - 保持首期精简依赖面，暂不向 `ateng-solid-bootstrap` 引入 `spring-boot-starter-actuator`；
   - 显式设置 `ateng.boot.web.shutdown.mvc-endpoint-enabled: false`，依靠 `server.shutdown: graceful` 与 `SmartLifecycle` 处理 `SIGTERM` 在途请求排空，消除多余路由暴露。
4. **前端三环境文件追踪与超时对齐**：
   - 前端 `.gitignore` 精准放行非敏感 `!.env.development` 与 `!.env.production`（仍严密忽略 `.env`、`.env.local` 与 `*.local`）；
   - 新增 `src/vite-env.d.ts` 声明只读 `ImportMetaEnv`，引入 `VITE_API_TIMEOUT=15000` 交由 `resolveApiTimeoutMs` 安全解析兜底，同步将 Vite `/admin` 开发代理超时设至 `120_000ms` 对齐用户批量导入链路。

---

## 3. 备选方案与权衡

| 备选方案 | 放弃原因 |
| :--- | :--- |
| **不引 `commons-pool2`，仅保留 Lettuce 单连接并放任 YAML 忽略** | 易误导开发者调大/调小 `lettuce.pool.*` 参数却无实际运行时效果；引入 Spring Boot BOM 已有 `commons-pool2` 零上游改造风险且内存开销极小 |
| **现阶段直接引入 `spring-boot-starter-actuator`** | 首期单体定位保持极简依赖与最小攻击面；后续演进 K8s/云观测或迁移 `ateng-cloud` 时再行启用 `/actuator/health` 与 `/actuator/pre-stop` 即可 |
| **将 `local` 配置导入留在 `application.yml` 根层级** | 存在本地构建产物或测试在特定环境下意外吸入本机 `application-local.yml` 开关（如 `SOLID_TENANT_ENABLED`）的串味隐患 |
| **在虚线程模型下大幅放大 Hikari `maximum-pool-size`（如 100+）** | 虚拟线程虽便宜但数据库连接及行锁并发受制于数据库 `innodb_buffer_pool_size` 与 CPU 核数，过大会放大连接争用与云库 OOM 风险 |

---

## 4. 后果与演进路线

- **正面收益**：开发、离线测试与生产配置职责物理清晰；杜绝 `prod` 缺失敏感变量静默跑偏；MySQL 与 Redis 双引擎连接池、超时保活、泄漏探测及前端超时全链路闭环可控。
- **代价与演进边界**：生产部署方必须显式提供全部零默认值 `SOLID_*` 环境变量方能启动成功；未来如接入 Kubernetes `preStop` HTTP 钩子或微服务注册中心探针，仅需在 `bootstrap` 增引 `spring-boot-starter-actuator` 并将 `mvc-endpoint-enabled` / `management.endpoints` 按需打开，无需改动任何业务代码。（> 已按 [ADR-0007](./0007-observability-and-actuator-governance.md) 在 `bootstrap` 落地 `ateng-boot-observability`、`prod` 双端口隔离及全部带默认值可选环境变量。）
