# ateng-solid-server

**ateng-solid-server** 是 **ateng-solid** 全端开源上层框架的后端核心，基于独立通用的 [`ateng-boot`](https://github.com/atengk/ateng-boot) 基础设施底座构建，采用 **5 模块同源双装配（单体先行稳固 + 预留 [`ateng-cloud`](https://github.com/atengk/ateng-cloud) 并存挂壳）架构**。它既是验证并完善 `ateng-boot` / `ateng-cloud` 的实战工程，也为二开扩展行业业务模块（`biz-*`）提供纯净、零补丁的后端脚手架内核。

---

## 1. 工程定位与 5 模块拓扑

- **架构形态**：Java 21 LTS（虚拟线程）+ Spring Boot + `ateng-boot` Maven 多模块（精确依赖版本见 [父 POM](./pom.xml)）。
- **Maven 模块划分**（`groupId: io.github.atengk`，基础包名 `io.github.atengk.solid.*`）：
  1. `ateng-solid-common`：全局常量、通用错误码（`100xxx`）、基础枚举与上下文辅助；
  2. `ateng-solid-system-api`：系统域纯 Java 契约接口（`Remote*Service`）、跨模块 DTO/VO 与错误码（`102xxx`）；
  3. `ateng-solid-system`：系统管理域实现（`/admin/system/**`、租户与套餐、RBAC + `@DataScope`、组织、枚举/字典、参数、日志与 Flyway 域内迁移脚本）；
  4. `ateng-solid-auth`：认证与会话中心（`/admin/auth/**`、`loginType="admin"`、验证码、防爆破、在线会话、`101xxx`）；
  5. `ateng-solid-bootstrap`：单体聚合启动入口、`application*.yml`、跨模块本地事件装配与 ArchUnit 架构守卫。

---

## 2. 首期 MVP 范围

1. **认证与会话**：管理端登录（`/admin/auth/**`、`satoken-admin`）、可开关图形验证码、防爆破、个人资料与 PBKDF2 改密、在线会话强退。
2. **多租户与套餐**：行级多租户（`tenant_id BIGINT`，平台默认租户 `0` / 编码 `000000`）、租户套餐（`sys_tenant_package`）能力上限控制、平台超管受控切换租户视角。
3. **组织与 RBAC**：用户（含头像上传与 Excel 导入导出）、角色（含 5 级 `@DataScope` 数据权限）、菜单权限树、部门树、岗位。
4. **枚举、字典与参数**：上层自建控制器接管静态枚举端点 `/admin/system/enums`（`@SaCheckLogin` 管控）、动态数据字典 `sys_dict` 与系统参数 `sys_config`。
5. **操作与登录审计**：登录日志、操作审计日志（`@OperateLog` SPI 异步落库）。

---

## 3. 运行环境、启动脚本与测试

- **运行基线**：OpenJDK 21 LTS + Apache Maven 3.9+ + MySQL 8.4 LTS / Redis 8.x（Schema 演进采用 `ateng-boot-flyway`）。
- **五档分层配置**：默认 `dev` 自动导入 `.gitignore` 忽略的 `application-local.yml`（模板见 `application-local.example.yml`）；`prod` 强制零默认值 `${SOLID_*}` Fail-Fast 注入敏感凭据与分级连接池/文件日志；`test` 依托 H2（`MODE=MySQL`）与内存降级 100% 离线自洽并物理隔离本地配置。
- **服务启停与构建脚本（[`scripts/server.sh`](./scripts/server.sh)）**：支持源码仓内开发联调与独立 Fat JAR 生产部署两种形态（无子命令时默认输出 `--help`；所有带值选项兼容 `--opt=val` 写法，支持通过 `--jvm-opts`、`--app-args`、`SOLID_APP_ARGS` 及末尾 `--` 向 `SpringApplication` 透传参数）：

```bash
# 0. 查看完整参数说明（无参默认等同于 --help）
bash scripts/server.sh

# 1. 源码仓内：巡检、增量构建并后台启动（dev 默认加载 application-local.yml）
bash scripts/server.sh check
bash scripts/server.sh start --build
bash scripts/server.sh start --port=8081 --jvm-opts="-Xms512m -Xmx1g" -- --logging.level.root=DEBUG
bash scripts/server.sh status
bash scripts/server.sh logs -f

# 2. 独立部署态（将 ateng-solid-bootstrap-*.jar 与 scripts/server.sh 同置运行目录）：
bash scripts/server.sh start --profile prod --env-file .env.prod --app-args "--spring.main.lazy-initialization=true"
bash scripts/server.sh logs --error -n 200
bash scripts/server.sh stop
```
- **核心规范入口**：
  - 独立开发行为准则：[`AGENTS.md`](./AGENTS.md)
  - 底层缺口即停工与联调 SOP（同步副本）：[`docs/agents/foundation-first-gate.md`](./docs/agents/foundation-first-gate.md)
  - 跨端契约规范（同步副本）：[`docs/agents/contracts.md`](./docs/agents/contracts.md)
  - 编码、架构拆解与注释规范（同步副本）：[`docs/agents/coding-standards.md`](./docs/agents/coding-standards.md)
  - 文档 1:1 保真规范（同步副本）：[`docs/agents/documentation-specification.md`](./docs/agents/documentation-specification.md)
  - 同源双装配守则：[`docs/agents/cloud-readiness.md`](./docs/agents/cloud-readiness.md)
  - 后端 AI 全景索引：[`llms.txt`](./llms.txt)
  - 上下文地图：[`CONTEXT-MAP.md`](./CONTEXT-MAP.md)
  - 架构决策记录：[`docs/adr/README.md`](./docs/adr/README.md)
  - 详细工程规范：[`database-and-flyway-spec.md`](./docs/specs/database-and-flyway-spec.md) | [`auth-rbac-and-datascope-spec.md`](./docs/specs/auth-rbac-and-datascope-spec.md) | [`api-and-error-codes-spec.md`](./docs/specs/api-and-error-codes-spec.md) | [`configuration-and-archunit-spec.md`](./docs/specs/configuration-and-archunit-spec.md) | [`system-engines-and-testing-spec.md`](./docs/specs/system-engines-and-testing-spec.md)

---

## 4. 开发协作与开源协议

- 仓库采用 `main`（稳定只读）+ `dev`（活跃开发）双分支，提交流程详见 [贡献指南 (`CONTRIBUTING.md`)](./CONTRIBUTING.md)，安全漏洞披露详见 [安全策略 (`SECURITY.md`)](./SECURITY.md)。
- 基于 [Apache License 2.0](./LICENSE) 协议开源。
