# ADR-0007: 基于 `ateng-boot-observability` 的可观测管理面与双端口 Actuator 治理基线

- **状态**：`Accepted`
- **日期**：2026-11-28
- **作者**：Ateng
- **关联工单**：[`atengk/ateng-solid#76`](https://github.com/atengk/ateng-solid/issues/76)、[`atengk/ateng-boot#1185`](https://github.com/atengk/ateng-boot/issues/1185) (`[B24]`)、[`atengk/ateng-boot#1186`](https://github.com/atengk/ateng-boot/issues/1186) (`[B25]`)、[`atengk/ateng-boot#1187`](https://github.com/atengk/ateng-boot/issues/1187) (`[B26]`)
- **关联模块**：[`ateng-solid-bootstrap`](../../ateng-solid-bootstrap)、[`scripts/server.sh`](../../scripts/server.sh)（零侵入 `common` / `system-api` / `system` / `auth`）

---

## 1. 背景与前提约束

在 [ADR-0005](./0005-multi-env-configuration-and-pool-baseline.md) 与 [ADR-0006](../../../docs/adr/0006-stage1-minimal-stack-and-object-mapping.md) 落地后，后端首期已保持极简依赖完成核心业务闭环。随着容器化 Kubernetes 探针探测、Prometheus 指标采集、`preStop` 流量排空以及 [`scripts/server.sh`](../../scripts/server.sh) 就绪判定等运维治理诉求明确，需要在不破坏 5 模块物理边界的前提下引入可观测管理面。

正式接入前，按“底层零补丁（Foundation-First）”原则优先在 `ateng-boot` 上游 `dev` 根治三项前置问题（本地 `1.2.1-SNAPSHOT` 联调消费，合入 `main` 前统一锁回正式版）：
1. **`[B24]` (`ateng-boot#1185`)**：修复 `AtengHealthAutoConfiguration` 与 `AtengStorageAutoConfiguration` 同名 `storageHealthIndicator` 自动装配顺序竞态，保证防假死异步版存储探针可靠胜出；
2. **`[B25]` (`ateng-boot#1186`)**：新增 `AtengManagementContextSecurityConfiguration`（`@ManagementContextConfiguration(ManagementContextType.CHILD)`），使独立管理端口（`management.server.port != server.port`）子上下文自动桥接父容器 `ActuatorEndpointSecurityFilter` 并兼容 `base-path=/`；
3. **`[B26]` (`ateng-boot#1187`)**：在 `AtengGracefulShutdownAutoConfiguration`（于 `WebEndpointAutoConfiguration` 之后）注册默认 `PathMapper` 将 `@Endpoint(id = "preStop")` 稳定映射至 `/actuator/pre-stop`（尊重显式 `path-mapping` 覆写），并在 `TraceFilter` 双向豁免 `/actuator/pre-stop` 与 `/actuator/preStop`。

---

## 2. 核心决策

### 2.1 宿主单点装配与多环境端口隔离
- **宿主独占引入**：仅在 [`ateng-solid-bootstrap/pom.xml`](../../ateng-solid-bootstrap/pom.xml) 引入 `io.github.atengk:ateng-boot-observability`（由 `ateng-boot-bom` 纳管）；业务模块 `common`、`system-api`、`system`、`auth` 严禁引入可观测或 Actuator 编译依赖，亦不向业务代码注入 `@ObservedMetric`。
- **开发/测试单口、生产双口**：
  - `dev` / `test` 维持默认单端口 `8080`；
  - `prod` 开启物理管理端口隔离：`management.server.port: ${SOLID_MANAGEMENT_PORT:8081}`、`management.server.address: ${SOLID_MANAGEMENT_ADDRESS:127.0.0.1}`。

### 2.2 `preStop` 安全收口与慢请求排噪
- **端点切换**：`application.yml` 开启 `ateng.boot.web.shutdown.actuator-endpoint-enabled: true`，继续锁定 `ateng.boot.web.shutdown.mvc-endpoint-enabled: false` 关闭无鉴权 `/pre-stop` MVC 端点；并配置 `ateng.boot.web.slow-request.exclude-urls: ["/actuator/pre-stop"]` 消除 10s 流量排空导致的误报慢请求日志。
- **白名单收敛**：将 `ateng.boot.observability.security.whitelisted-endpoints` 显式收敛为 `[health, info, prometheus]`（**剔除 `pre-stop`**），确保 `/actuator/pre-stop` 与 `/actuator` 根索引默认均受安全拦截。
- **暴露与防线分层**：
  - `application.yml`（含 `dev` / `test` 默认基线）仅暴露 `health,info,prometheus`，不对外暴露 `preStop`；
  - `application-prod.yml` 在独立管理端口（`127.0.0.1:8081`）下暴露 `${SOLID_ACTUATOR_EXPOSURE_INCLUDE:health,info,prometheus,preStop}`，并默认启用 `stealth-mode: true`（未授权返回 404 隐身并发布 `ActuatorAccessDeniedEvent`）、`allowed-ip-ranges: ${SOLID_ACTUATOR_ALLOWED_IPS:127.0.0.1,::1}`、`token: ${SOLID_ACTUATOR_TOKEN:}` 与 `trusted-proxies: ${SOLID_TRUSTED_PROXIES:}`；本机 Pod `preStop` HTTP 钩子可直接工作，横向非白名单访问均被 `[B25]` 子上下文过滤器拦截。

### 2.3 K8s 黄金探针分级、去重与防刷缓存
- **Liveness 与 Readiness 分离**：
  - `management.endpoint.health.probes.enabled: true`
  - `management.endpoint.health.group.liveness.include: livenessState,ping`（严禁外部中间件瞬断诱发 Pod 死亡螺旋重启）
  - `management.endpoint.health.group.readiness.include: readinessState,dynamicDatasource,redis`（**显式排除 `storage`** 以防 S3/对象存储抖动中断核心 API 摘流，同时保留 `storage` 在 `/actuator/health` 聚合总览可见）
- **`db` 去重与探针缓存**：关闭 Spring 原生重复探针 `management.health.db.enabled: false`，统一由防假死异步 `dynamicDatasource` 探针承担数据源检查；设置 `ateng.boot.observability.health.cache-ttl: 15s` 防高频探活击穿下游中间件。
- **离线 `test` Profile 适配**：`application-test.yml` 显式设置 `ateng.boot.observability.health.redis.enabled: false`、`storage.enabled: false`、`cache-ttl: 0s`，并将 `readiness.include` 收敛为 `readinessState,dynamicDatasource`。

### 2.4 细节隐私、`build-info` 与 Prometheus 标签
- **详情分级**：`dev` / `test` 保持 `show-details: always`；`prod` 覆盖根探针、`liveness` 与 `readiness` 为 `${SOLID_ACTUATOR_SHOW_DETAILS:never}`。
- **零泄密信息端点**：在 [`ateng-solid-bootstrap/pom.xml`](../../ateng-solid-bootstrap/pom.xml) 的 `spring-boot-maven-plugin` 新增 `build-info` 目标，开启 `management.info.build.enabled: true` 并关闭 `management.info.env.enabled: false`，确保 `/actuator/info` 仅输出构建元数据。
- **统一维度标签**：各环境分别配置 `ateng.boot.observability.metrics.common-tags.env`（`dev` / `test` / `prod`），由底座自动附加 `application="ateng-solid-server"` 与受管线程池度量。

### 2.5 运维脚本 `scripts/server.sh` 联动与零破坏契约
- 新增 `--mgmt-port <port>`（联动 `SOLID_MANAGEMENT_PORT`，默认 `dev` 同业务端口、`prod` 为 `8081`），禁止通过 `--jvm-opts` / `--app-args` 传入冲突的 `-Dmanagement.server.port` 或 `--management.server.port`。
- `check` / `start` 在双端口模式下同步检测管理端口冲突；`server.meta` 持久化 `MGMT_PORT` / `MGMT_HOST`（通配地址 `0.0.0.0`/`::` 归一化为 `127.0.0.1`），`start` / `status` 输出管理探针地址。
- `start` 启动等待升级为校验业务端口监听 + `GET /actuator/health/readiness` 返回 HTTP `200`（精简镜像缺 `curl` 时自动回退端口监听）；`stop` 保持既有 `SIGTERM` 优雅停机语义不变。
- 所有新增生产环境变量均提供安全默认值，**不加入** `REQUIRED_PROD_ENV_VARS`，保持 100% 向后兼容。

---

## 3. 备选方案与权衡

| 备选方案 | 放弃原因 |
| :--- | :--- |
| **使用原生 `spring-boot-starter-actuator` 手动装配端点与过滤器** | 需在上层重复实现防假死线程池、双端口子上下文安全过滤桥接与隐身拦截逻辑，违反底座优先原则 |
| **生产环境沿用单端口暴露 Actuator** | 高峰或攻击流量下可观测抓取与业务端口争抢同一容器连接池，且管理路径更易被业务反代规则误透传 |
| **将对象存储 `storage` 纳入 `readiness` 摘流判定** | 头像存储属辅助功能域，外部对象存储瞬时抖动不应触发主管理后台 Pod 从负载均衡器集体摘流 |
| **在 4 个业务模块直接引入 Micrometer/Observability 执行打点** | 违背首期极简依赖与双装配内核零污染边界；基础设施通用指标与受管线程池度量已在 `bootstrap` 自动装配 |

---

## 4. 后果与演进边界

- **正面收益**：全链路观测指标、构建指纹、K8s 探针分级、`preStop` 排空与物理双端口安全防御开箱即用；离线 `test` 套件含单双端口全流程端到端回归覆盖。
- **代价与约束**：`dev` 阶段消费 `ateng-boot` `1.2.1-SNAPSHOT` 联调；`ateng-solid-server` 合入 `main` 与正式发版前，须待 `ateng-boot` 发布含 `B24`/`B25` 的递进正式版并锁回正式版本号。
