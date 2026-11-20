---
trigger: always_on
description: "ateng-boot/ateng-cloud/ateng-ui 底层缺口即停工提单硬门禁，零降级零补丁原则与底座通用性独立验收铁律"
---

# 底层缺口即停工规范 (Foundation-First Hard Gate)

> **作用域**：`ateng-solid` 聚合工作区及全部子项目（`ateng-solid-server`、`ateng-solid-admin-web`、后续微服务启动壳与多端工程）。
> **唯一真源声明**：本文件主源维护于根仓库 `ateng-solid/.agents/rules/foundation-first-gate.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓库 `.agents/rules/foundation-first-gate.md`，禁止在子仓库擅自改动副本。
> **核心定位**：`ateng-solid` 既是用以**实战验证并反向锤炼底层底座**的试金石，也是面向企业级二次开发的**纯净上层框架**。凡底座（`ateng-boot`、`ateng-cloud`、`ateng-ui`）功能缺失或存在缺陷，**必须立即停止受阻上层开发、先完善并正式发布底座后再回归消费**；绝不在上层编写临时补丁（Workaround）将就，亦绝不为迎合上层而将底层写死耦合。

---

## 1. 三层生态拓扑与能力归属边界

| 层级 | 对应工程 / 载体 | 核心职责与独立性定位 | 禁止事项 |
| :--- | :--- | :--- | :--- |
| **第一层：通用基础设施底座** | `atengk/ateng-boot`、`atengk/ateng-cloud`、`atengk/ateng-ui` | **完全独立、跨项目通用的基础底座**，随时可脱离 `ateng-solid` 在任意第三方/外部项目单独使用 | 严禁写死 `ateng-solid` 表名（`sys_*`）、角色名（`super_admin`）、错误码（`101xxx`）或默认租户（`000000`） |
| **第二层：上层应用框架内核** | `ateng-solid`（`server` 5 模块基础框架、`admin-web` 管理台基础壳及后续端） | **验证底座 + 开箱即用的企业级上层框架**：承接多租户开关、认证会话、RBAC、动态路由、字典、审计等通用管理闭环 | 严禁编写临时绕过补丁掩盖底层缺陷；严禁把纯技术基础能力滞留在本层 |
| **第三层：二开业务扩展层** | 基于 `ateng-solid` 扩展的行业业务模块（如 `biz-*`） | 消费框架提供的契约、权限体系、多租户上下文与布局壳快速交付具体行业业务 | 严禁篡改或耦合穿透框架核心（`auth`/`system`）内部实现 |

---

## 2. 底座通用性与零项目绑定铁律 (Foundation Generality & Non-Coupling)

1. **禁止硬编码项目专属约定**：严禁要求底座写死 `ateng-solid` 专属表名、业务租户编码、专属业务错误码段，或破坏性移除外部项目依赖的既有默认行为（如默认保留兼容超管角色、高危能力默认关闭）。
2. **诉求收口为“通用抽象 + 向后兼容默认 + Opt-in 配置/SPI”**：向上游提 Issue 时，方案必须具备跨项目通用性——优先通过 `@ConfigurationProperties` 配置开关、`@ConditionalOnMissingBean` SPI 钩子、回调 Prop 或标准接口扩展暴露能力，使其他项目开箱即用不受影响，`ateng-solid` 仅通过薄配置声明自身业务约定。
3. **专属业务规则留在上层**：若某个需求纯属业务管理领域逻辑（如 `sys_user` 与 `sys_tenant_package` 配额关联校验），仅要求底座提供必要通用扩展点，业务规则严格留在 `ateng-solid`。

---

## 3. 硬停工与零降级原则 (Hard-Stop & Zero Workaround)

在上层框架设计或编码过程中，一旦识别到以下任一底层能力缺口或设计不顺手处，**必须立即执行硬停工（HARD STOP）**。**严禁在上层以“临时降级方案 / 先用拦截器绕一下”的方式掩盖缺口**，以免污染上层框架代码结构并削弱对底座的打磨闭环：

### 3.1 必须停工提 Issue 并等待底座发版的场景
1. **跨模块/跨端可复用的核心基础设施能力缺失**：
   - 后端单体基础设施：认证鉴权多账号自动路由、超管角色可配置化、`@DataScope` 与拦截器上下文协同、未登录租户头解析/超管切租户 SPI 钩子、防爆破作用域隔离、多租户角色权限缓存键隔离、数据库迁移安全守卫、审计/存储/导入导出核心机制等；
   - 后端微服务治理能力：网关统一鉴权与多租户安全扩展、Same-Token 双向契约、Feign/Dubbo 自适应解包与异常链路（含 `traceId`）还原、Sentinel 降级协同、Seata 分布式事务与租户协同、灰度与 Nacos 配置治理等；
   - 前端底座能力：HTTP 契约解包、雪花长整型防截断、`AuthManager`/`TenantManager` 隔离、核心 `useAuth`/`usePagination`/`useTabsNav` 无头状态机、`AtengProLayout`（含字符串图标解析扩展点）/`AtengTable`/`AtengQueryForm`/`AtengExcel*`/`v-permission` 等基础设施组件或指令通用扩展。
2. **底座 Defect、安全隐患、并发/虚拟线程风险或扩展点缺失**：
   - 如硬编码越权风险且不可配置覆盖、ThreadLocal/异步上下文清理遗漏、`synchronized` Pinning、第三方包共生崩溃（如 `Feign + Sentinel`）、缓存多租户串味，或因底座写死且无扩展点导致上层无法通过配置/回调直接接入等。
3. **跨两处及以上复用的通用技术增强**：
   - 明确可在 `ateng-solid` 多个业务域、未来多端工程以及其他独立外部项目中复用的纯技术能力。

### 3.2 允许上层自行配置的范畴（仅限底座能力已完整就绪）
- 纯业务领域逻辑、业务表字段编排（如 `sys_user`/`sys_role` 业务校验与组装）；
- 单应用专属路由表配置、后端 DTO 到前端视图的纯字段转换；
- **底座已提供显式配置开关、SPI 实现点、标准插槽或构造参数**的场景（例如配置 `path-prefix`、传 `superAdminRoles: ['super_admin']`、路由壳自行组合 `<KeepAlive>`、`OperateLogStorageProvider` SPI 落库实现）。

---

## 4. 五步标准执行闭环与双轴验收门禁

当触发 3.1 任一条件时，必须严格遵循以下 5 步闭环流水线（不得跳过或在上层偷写降级代码）：

1. **立即停工与范围隔离**：
   - **暂停受该底座缺口直接阻断的上层特性代码开发**（禁止编写临时绕过代码）；
   - 与缺口正交的规划文档、详细 Spec 设计或其它未受阻模块可继续推进完善。
2. **源码实测定位与查重**：
   - 核查对应底座仓库真实源码与 GitHub Issues（开放/近期关闭），锁定类/方法锚点并提炼**通用、向后兼容**的改造方案。
3. **登记增强清单并立即提报 GitHub Issue**：
   - 使用 `gh issue create -R atengk/<repo>` 向上游提交 Issue，并同步登记至根仓 [底层基础设施待增强清单](../../docs/specs/foundation-enhancements-checklist.md)。
4. **底座优先开发、独立验证与递进发版**：
   - 在对应底座仓完成改造与独立验证；允许在本地开发联调阶段按第 5 节 SOP 消费本地临时构建提前验证契合度，但**上层正式业务代码必须在底座完成递进正式发版（Maven Central / npm 正式包，严禁复用旧版本号覆盖）并锁定新版本后才可提交合入**，不得在 `dev`/`main` 残留未发版 `SNAPSHOT` 或本地 `yalc`/链接依赖。
5. **双轴验收复核与恢复开工（解封前 4 项必查）**：
   - **无项目污染审计**：底座源码、配置与测试中零 `ateng-solid` 专属命名（如 `sys_*`、`solid`、`000000`、`satoken-admin`、`101xxx`）；
   - **向后兼容与独立运行**：新特性默认保持向后兼容，底座自有单测与 Sample 工程脱离 `ateng-solid` 即可单独跑通与演示；
   - **正式版本闭环**：底座已完成递进正式发版且 `ateng-solid` 已升级锁定正式版本号；
   - **上层纯净复核**：上层仅保留简洁配置或标准 SPI/回调接入，无临时兼容判断与降级残留。确认 4 项全部达标并勾选 Checklist 后，恢复对应上层特性开工。

---

## 5. 跨仓本地联调与版本还原标准 SOP

当修改完底座但尚未推送远端发版、需先在上层验证真实集成效果时，必须严格遵守以下**可逆本地联调规程**（严禁直接覆写本地正式版本号缓存或误提交本地依赖）：

| 维度 | 后端（`ateng-boot` / `ateng-cloud` ➔ `ateng-solid-server`） | 前端（`ateng-ui` ➔ `ateng-solid-admin-web`） |
| :--- | :--- | :--- |
| **本地联调产物机制** | 底座仓使用递增 **`-SNAPSHOT`** 版本号（如 `1.2.1-SNAPSHOT`）执行 `mvn install -DskipTests` 安装至本地 Maven 仓库 | 在 `ateng-ui` 先通过 **`pnpm pack`** 将 `workspace:*` 内部协议自动转写为实体版本号，解包后执行 **`yalc publish --push`** 推送真实构建产物 |
| **避坑要点** | **严禁使用已发布或即将发布的正式版本号**（如 `1.2.0`/`1.2.1`）反复 `mvn install`，防 `~/.m2` 缓存脏读 | **严禁在 `ateng-ui` 子包目录直接裸跑 `yalc push`**（会遗留 `workspace:*` 导致独立消费仓 `pnpm` 报错）；严禁使用 `pnpm link`（防双 Vue 实例与 Symlink 路径污染） |
| **上层消费与缓存刷新** | `ateng-solid-server/pom.xml` 临时切换 `<ateng-boot.version>` 为 `-SNAPSHOT` 联调验证 | 执行 `yalc add @atengk/...` 接入 `.yalc/` 拷贝产物；若 `yalc push` 后代码未刷新，执行 `pnpm dev:force`（`vite --force`）刷新预构建缓存 |
| **退出联调与提交守门** | 底座发版正式递进号（如 `1.2.1`）后，上层改回正式版本号；`pre-commit` 自动拦截含底座 `-SNAPSHOT` 的 `pom.xml` 提交 | 底座发版 npm 新版后，执行 `yalc remove --all && pnpm up @atengk/*` 还原并升级锁文件；`.gitignore` 忽略 `.yalc/` 与 `yalc.lock`，`pre-commit` 自动拦截 `file:.yalc`/`file:../`/`link:` 提交 |
