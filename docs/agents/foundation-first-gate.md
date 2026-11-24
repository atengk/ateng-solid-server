# 底层缺口即停工与跨仓联调 SOP (foundation-first-gate.md)

> **唯一真源**：维护于根仓 `ateng-solid/docs/agents/foundation-first-gate.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓 `docs/agents/foundation-first-gate.md`；战略背景见根仓 `docs/adr/0004-foundation-driven-framework-coevolution.md`。

---

## 1. 三层生态边界与独立性红线

| 层级 | 工程载体 | 核心职责 | 严禁越界 |
| :--- | :--- | :--- | :--- |
| **第一层：通用独立底座** | `atengk/ateng-boot`、`atengk/ateng-cloud`、`atengk/ateng-ui` | 跨项目通用的独立底座，可脱离 `ateng-solid` 在任意外部项目单独使用 | 严禁写死 `sys_*`、`super_admin`、`000000`、`satoken-admin`、`101xxx` 等项目专属约定 |
| **第二层：上层应用框架内核** | `ateng-solid`（`server`、`admin-web` 及后续端） | 实战锤炼底座 + 开箱即用企业级框架内核（认证、可开关多租户、RBAC、字典、审计、管理台壳） | 严禁编写临时绕过/降级补丁掩盖底座缺口；严禁将纯技术基础能力滞留本层 |
| **第三层：扩展与二开业务** | `ext-*` / `biz-*` | 装配期插拔消费框架契约与 SPI 交付行业业务 | 严禁篡改框架内核源码或向内核表直接加列 |

向上游提报需求必须收口为**通用抽象 + 向后兼容默认 + Opt-in 配置/SPI/回调 Prop**；纯业务领域规则留在 `ateng-solid`。

---

## 2. 触发停工判定边界

### 必须立即停工提单（Zero Workaround）
1. **核心基础设施缺失或扩展点不足**：多账号认证路由、超管角色可配置化、`@DataScope` 与拦截器协同、未登录租户头/超管切租户 SPI、防爆破与多租户缓存隔离、网关鉴权/`Same-Token`/Feign/Dubbo 异常透传、前端 HTTP 解包/长整型防截断/无头 Composables/`Ateng*` 通用组件扩展点等。
2. **底座 Defect、安全或并发风险**：硬编码越权风险、ThreadLocal/异步上下文泄漏、`synchronized` 虚拟线程 Pinning、依赖共生崩溃、缓存多租户串味等。
3. **跨两处及以上复用的纯技术能力**。

### 允许上层自行配置（无需停工）
- 纯业务校验、实体/DTO 编排、路由表组装；
- 底座已暴露显式配置项、SPI、标准插槽或回调 Prop（如 `superAdminRoles: ['super_admin']`、`iconResolver`、`OperateLogStorageProvider`、`path-prefix`）。

---

## 3. 五步停工闭环与解封四项验收

1. **停工与范围隔离**：立即暂停受该缺口阻断的上层特性编码（正交的 Spec 设计或未受阻模块可继续推进）。
2. **源码实测与查重**：核查对应底座源码与 GitHub Issues，定位类/方法锚点并提炼通用、向后兼容方案。
3. **建单与登记**：执行 `gh issue create -R atengk/<repo>` 提交 Issue，并同步登记至根仓 `docs/specs/foundation-enhancements-checklist.md`。
4. **底座优先开发与递进发版**：在底座仓完成改造与独立验证；联调验证可按第 4 节 SOP 进行，但**上层正式代码必须在底座发布递进正式版并锁定正式版本号后才可提交合入**（`dev`/`main` 严禁残留 `-SNAPSHOT` 或本地 `yalc`/路径依赖）。
5. **解封前四项双轴验收（全绿方可恢复开工）**：
   - **无项目污染**：底座源码、配置、测试零 `ateng-solid` 专属命名；
   - **独立可运行与向后兼容**：底座单测与 Sample 脱离 `ateng-solid` 独立跑通，不破坏既有默认行为；
   - **正式版闭环**：底座已完成递进正式发布（严禁覆盖旧版本号），上层已锁定新正式版；
   - **上层纯净**：上层仅保留标准配置或薄 SPI/回调接入，无临时兼容判断。

---

## 4. 跨仓可逆本地联调 SOP（未发版前先行验证）

| 环节 | 后端（`ateng-boot` / `ateng-cloud` ➔ `ateng-solid-server`） | 前端（`ateng-ui` ➔ `ateng-solid-admin-web`） |
| :--- | :--- | :--- |
| **本地产物推送** | 底座切换至递增 **`-SNAPSHOT`** 版本（如 `1.2.1-SNAPSHOT`）执行 `mvn install -DskipTests` | 在 `ateng-ui` 先执行 **`pnpm pack`** 消解 `workspace:*`，解包后执行 **`yalc publish --push`** |
| **避坑红线** | 严禁使用已发布或即将发布的正式版本号反复 `mvn install`（防 `~/.m2` 脏读） | 严禁在 `ateng-ui` 子包裸跑 `yalc push`（会残留 `workspace:*`），严禁 `pnpm link`（防双 Vue 实例） |
| **上层接入刷新** | `ateng-solid-server/pom.xml` 临时切至 `-SNAPSHOT` 联调 | 执行 `yalc add @atengk/...` 接入；代码未刷新时运行 `pnpm dev:force`（`vite --force`）清预构建缓存 |
| **退出与提交守门** | 底座发正式版后改回正式版本号；`.githooks/pre-commit` 自动拦截含底座 `-SNAPSHOT` 的 `pom.xml` | 底座发 npm 正式版后执行 `yalc remove --all && pnpm up @atengk/*`；`.githooks/pre-commit` 自动拦截 `file:.yalc`/`file:../`/`link:` |
