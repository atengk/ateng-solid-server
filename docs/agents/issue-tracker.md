# Issue 跟踪器与工单协作规范 (GitHub Issues)

本仓库（`atengk/ateng-solid-server`）的规划决策单与后端编码实现工单（`[server]` 前缀、`ready-for-agent`）统一收口在聚合根仓库 **`atengk/ateng-solid`** 的 **GitHub Issues** 集中追踪与串联原生 `blockedBy` 依赖链，统一通过 `gh` CLI 附加 `-R atengk/ateng-solid` 操作（标签映射详见 [`triage-labels.md`](./triage-labels.md)）。

> **单仓统一工单 + 多仓物理隔离源码**：
> - **严禁在 `atengk/ateng-solid-server` 重复创建同类编码实现单**，请统一操作 `-R atengk/ateng-solid` 并在标题使用 `[server]` 前缀；
> - 后端源码开发、本地测试、Git 提交与推送仍严格在 `ateng-solid-server` 独立仓库内执行；
> - **底层缺口联动**：开发过程中若发现 `ateng-boot` 或 `ateng-cloud` 存在基础设施能力缺失或缺陷，严禁在本仓编写绕过补丁，须立即按 [`foundation-first-gate.md`](./foundation-first-gate.md) 暂停受阻特性并向对应底座仓库（`atengk/ateng-boot` / `atengk/ateng-cloud`）提交通用化 Issue。

---

## 1. `gh` CLI 常用操作约定

- **创建工单**：`gh issue create -R atengk/ateng-solid --title "[server] <标题>" --label "<标签>" -F <body-file>`（长文本强制使用 `-F <file>`）
- **查看工单**：`gh issue view <number> -R atengk/ateng-solid --json number,title,body,labels,comments`
- **列表查询**：`gh issue list -R atengk/ateng-solid --state open --label "ready-for-agent" --json number,title,labels,assignees`
- **关联父子工单（Sub-issues）**：`gh issue create -R atengk/ateng-solid --parent <parent> ...` 或 `gh issue edit <parent> -R atengk/ateng-solid --add-sub-issue <child>`；不支持时在子单顶部注明 `Part of #<parent>`
- **阻塞依赖（Blocking）**：优先使用原生依赖 `gh issue create -R atengk/ateng-solid --blocked-by "<n1>,<n2>"` 或 `gh issue edit <child> -R atengk/ateng-solid --add-blocked-by <blocker>`；查询未关闭阻塞数：`gh issue view <n> -R atengk/ateng-solid --json blockedBy --jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`；不可用时回退至正文顶部 `Blocked by: #<n>`
- **评论与状态流转**：`gh issue comment <number> -R atengk/ateng-solid -F <file>`、`gh issue edit <number> -R atengk/ateng-solid --add-label "<add>" --remove-label "<remove>"`、`gh issue edit <number> -R atengk/ateng-solid --add-assignee "@me"`
- **关闭工单**：先评论交付验证摘要 `gh issue comment <number> -R atengk/ateng-solid -F <summary-file>`，再执行 `gh issue close <number> -R atengk/ateng-solid`

> **目标仓库校验**：在本子仓目录执行工单命令必须显式携带 `-R atengk/ateng-solid`，严禁误建至子仓或第三方上游仓。

---

## 2. Windows PowerShell 避坑铁律与 Git 授权停止门禁

1. **特殊字符双引号包裹**：在 Windows PowerShell 中，包含 `@`、`,` 的参数必须加双引号（如 `--add-assignee "@me"`）。
2. **多行正文强制 `-F <file>`**：创建 Issue 或回写报告必须先写入会话 Scratch 临时文件再通过 `-F <file>` 传入，严禁在项目源码树内建 `scratch/` 目录，严禁正文泄漏本地盘符或凭据。
3. **显式两级 Git 授权门禁**：完成本地验证（`mvn clean test -B -T 1C -nsu`）后，未获用户明确“提交/commit”或“推送/push”指令前必须停留在工作区，严禁擅自提交、推送或关单；获准提交时统一使用 `bash scripts/commit.sh`。

---

## 3. 外部 PR 分诊开关与 Wayfinding 约定

- **PRs as a request surface: no.**（默认关闭外部 PR 作为分诊入口）。
- **Wayfinding 操作（`/wayfinder`）**：主图工单标 `wayfinder:map`，子工单通过父子关联或 `Part of #<map>` 挂载并标 `wayfinder:<type>`；认领首步 `gh issue edit <n> --add-assignee "@me"`，完成后评论结论并关单。
