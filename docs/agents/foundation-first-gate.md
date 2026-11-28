# 底层零补丁与跨仓联调门禁 SOP (foundation-first-gate.md)

> **唯一真源**：维护于根仓 `ateng-solid/docs/agents/foundation-first-gate.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓 `docs/agents/foundation-first-gate.md`；战略背景与 `2026-11-26` 修订说明见根仓 `docs/adr/0004-foundation-driven-framework-coevolution.md`。

---

## 1. 三层生态边界与独立性红线

| 层级 | 工程载体 | 核心职责 | 严禁越界 |
| :--- | :--- | :--- | :--- |
| **第一层：通用独立底座** | `atengk/ateng-boot`、`atengk/ateng-cloud`、`atengk/ateng-ui` | 跨项目通用的独立底座，可脱离 `ateng-solid` 在任意外部项目单独使用 | 严禁写死 `sys_*`、`super_admin`、`000000`、`satoken-admin`、`101xxx` 等项目专属约定 |
| **第二层：上层应用框架内核** | `ateng-solid`（`server`、`admin-web` 及后续端） | 实战锤炼底座 + 开箱即用企业级框架内核（认证、可开关多租户、RBAC、字典、审计、管理台壳） | 严禁编写临时绕过/降级补丁掩盖底座缺口；严禁将纯技术基础能力滞留本层 |
| **第三层：扩展与二开业务** | `ext-*` / `biz-*` | 装配期插拔消费框架契约与 SPI 交付行业业务 | 严禁篡改框架内核源码或向内核表直接加列 |

向上游提报需求必须收口为**通用抽象 + 向后兼容默认 + Opt-in 配置/SPI/回调 Prop**；纯业务领域规则留在 `ateng-solid`。

---

## 2. 零补丁边界判定（先改底座 vs 上层自配）

### 必须先改底座（Zero Workaround · 禁写上层补丁）
1. **核心基础设施缺失或扩展点不足**：多账号认证路由、超管角色可配置化、`@DataScope` 与拦截器协同、未登录租户头/超管切租户 SPI、防爆破与多租户缓存隔离、网关鉴权/`Same-Token`/Feign/Dubbo 异常透传、前端 HTTP 解包/长整型防截断/无头 Composables/`Ateng*` 通用组件扩展点等。
2. **底座 Defect、安全或并发风险**：硬编码越权风险、ThreadLocal/异步上下文泄漏、`synchronized` 虚拟线程 Pinning、依赖共生崩溃、缓存多租户串味等。
3. **跨两处及以上复用的纯技术能力**。

### 允许上层自行配置（无需改底座）
- 纯业务校验、实体/DTO 编排、路由表组装；
- 底座已暴露显式配置项、SPI、标准插槽或回调 Prop（如 `superAdminRoles: ['super_admin']`、`iconResolver`、`OperateLogStorageProvider`、`path-prefix`）。

---

## 3. 五步本地协同闭环与 `main` 发版四项验收

1. **禁止上层 Hack（局部聚焦底座）**：暂停在上层编写绕过补丁（不冻结 Spec、其他模块或待联调后的上层编码推进）。
2. **源码实测、建单留痕与跨仓停手边界（硬门禁）**：核查对应底座源码与 GitHub Issues，提炼通用向后兼容方案，执行 `gh issue create -R atengk/<repo>` 建单，并在根仓 `docs/specs/foundation-enhancements-checklist.md` 登记待锁版底座项。**默认到此为止：未经用户明确确认，严禁在 `ateng-solid` 会话中直接修改 `ateng-boot`、`ateng-cloud`、`ateng-ui` 等外部底座仓库源码**（避免跳过建单或与底座独立开发工作树冲突）。
3. **获准后的底座本地改造与 `dev` 协同提交**：仅当用户明确要求或确认由当前会话协同修改底座时，方可在底座仓 `dev` 完成改造、通过底座独立单测并**本地提交至底座 `dev` 分支**；按第 4 节 SOP 刷新本地 `-SNAPSHOT` / `yalc` 后，上层即可直接编码、跑绿本地离线全量测试，并**正常提交至上层 `dev` 分支**（`dev` 允许保留 `-SNAPSHOT` / `file:.yalc`；联调期间远端 `dev` CI 暂红属已知可接受状态）。
4. **特性/缺陷 Issue 独立闭环**：上层 `[server]` / `[admin-web]` 业务与缺陷 Issue 在同时满足“**关联底座改动已提交至底座 `dev` 并通过单测** + **本仓改动已提交至 `dev` 且本地全量测试全绿**”后，即可立即关闭，无需等待底座发布 Maven Central / npm 正式版；底座待发版项集中由增强清单与发版收口工单追踪。
5. **`main` 合入与发版前四项双轴验收（全绿方可合并 `main` / 打 Tag）**：
   - **无项目污染**：底座源码、配置、测试零 `ateng-solid` 专属命名；
   - **独立可运行与向后兼容**：底座单测与 Sample 脱离 `ateng-solid` 独立跑通，不破坏既有默认行为；
   - **正式版闭环**：底座已完成递进正式发布（严禁覆盖旧版本号），上层已还原锁定新正式版；
   - **上层纯净**：上层仅保留标准配置或薄 SPI/回调接入，无临时兼容判断。

---

## 4. 跨仓可逆本地联调 SOP（`dev` 协同 ➔ `main` 锁版）

| 环节 | 后端（`ateng-boot` / `ateng-cloud` ➔ `ateng-solid-server`） | 前端（`ateng-ui` ➔ `ateng-solid-admin-web`） |
| :--- | :--- | :--- |
| **本地产物推送** | 底座切换至递增 **`-SNAPSHOT`** 版本（如 `1.2.1-SNAPSHOT`）执行 `mvn install -DskipTests` | 在 `ateng-ui` 先执行 **`pnpm pack`** 消解 `workspace:*`，解包后必须附加 `--replace` 执行 **`yalc publish --push --replace`**（因 `pnpm pack` 会将 tarball 内文件 mtime 归一化为 `1985-10-26`，未改动样式字节数时省略 `--replace` 会被 Yalc 增量复制跳过，导致 JS/CSS Vue `data-v-*` scoped hash 失配） |
| **避坑红线** | 严禁使用已发布或即将发布的正式版本号反复 `mvn install`（防 `~/.m2` 脏读） | 严禁在 `ateng-ui` 子包裸跑 `yalc push`（会残留 `workspace:*`），严禁省略 `--replace` 增量推送或更新 `pnpm pack` 产物，严禁 `pnpm link`（防双 Vue 实例） |
| **上层接入与 `dev` 提交** | `ateng-solid-server/pom.xml` 切至 `-SNAPSHOT` 联调；通过本地 `mvn clean test` 后可直接提交 `dev` | 首次执行 `yalc add @atengk/...` 接入；二次刷新务必执行 **`yalc update @atengk/... --replace && pnpm install --force`**（若所用 `yalc` 版本在消费侧忽略 `update --replace`，可先 `yalc remove` 再 `yalc add` 重建 `.yalc` 并配 `pnpm install --offline --frozen-lockfile`），联调服务须运行 `pnpm dev:force` 清理 Vite 预构建缓存；通过本地四合一检查后可直接提交 `dev` |
| **还原锁版与 `main` 守门** | 合并 `main` 前待底座发正式版并锁回正式版本号；`.githooks/pre-commit`（`main`）、`pre-push`（`main`/`v*`）、`main` CI 与 `release.yml` 硬拦截 `-SNAPSHOT` | 合并 `main` 前待底座发 npm 正式版后执行 `yalc remove --all && pnpm up @atengk/*`；`.githooks/pre-commit`（`main`）、`pre-push`（`main`/`v*`）、`main` CI 与 `release.yml` 硬拦截 `file:.yalc`/`file:../`/`link:`/`.tgz` |
