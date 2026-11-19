# Issue 跟踪器与工单协作规范 (GitHub Issues)

本仓库（`atengk/ateng-solid-server`）使用 **GitHub Issues** 管理后端需求、模块规格、ArchUnit/测试任务与缺陷，统一通过 `gh` CLI 操作（标签映射详见 [`triage-labels.md`](./triage-labels.md)）。

> **底层缺口联动**：开发过程中若发现 `ateng-boot` 或 `ateng-cloud` 存在基础设施能力缺失或缺陷，严禁在本仓编写绕过补丁，须立即按 [`.agents/rules/foundation-first-gate.md`](../../.agents/rules/foundation-first-gate.md) 暂停受阻特性并向对应底座仓库提交通用化 Issue。

---

## 1. `gh` CLI 常用操作约定

- **创建工单**：`gh issue create --title "<标题>" --label "<标签>" -F <body-file>`（长文本强制使用 `-F <file>`）
- **查看工单**：`gh issue view <number> --json number,title,body,labels,comments`
- **列表查询**：`gh issue list --state open --label "ready-for-agent" --json number,title,labels,assignees`
- **关联父子工单（Sub-issues）**：`gh issue create --parent <parent> ...` 或 `gh issue edit <parent> --add-sub-issue <child>`；不支持时在子单顶部注明 `Part of #<parent>`
- **阻塞依赖（Blocking）**：优先使用原生依赖 `gh issue create --blocked-by <n>` 或 `gh issue edit <child> --add-blocked-by <blocker>`；查询未关闭阻塞数：`gh issue view <n> --json blockedBy --jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`；不可用时回退至正文顶部 `Blocked by: #<n>`
- **评论与状态流转**：`gh issue comment <number> -F <file>`、`gh issue edit <number> --add-label "<add>" --remove-label "<remove>"`、`gh issue edit <number> --add-assignee "@me"`
- **关闭工单**：先评论交付验证摘要 `gh issue comment <number> -F <summary-file>`，再执行 `gh issue close <number>`

> **目标仓库校验**：首次执行写操作前可运行 `gh repo view --json nameWithOwner` 确认目标仓库为 `atengk/ateng-solid-server`。

---

## 2. Windows PowerShell 避坑铁律与 Git 授权停止门禁

1. **特殊字符双引号包裹**：在 Windows PowerShell 中，包含 `@`、`,` 的参数必须加双引号（如 `--add-assignee "@me"`）。
2. **多行正文强制 `-F <file>`**：创建 Issue 或回写报告必须先写入会话 Scratch 临时文件再通过 `-F <file>` 传入，严禁在项目源码树内建 `scratch/` 目录，严禁正文泄漏本地盘符或凭据。
3. **显式两级 Git 授权门禁**：完成本地验证（`mvn clean test -B -T 1C -nsu`）后，未获用户明确“提交/commit”或“推送/push”指令前必须停留在工作区，严禁擅自提交、推送或关单；获准提交时统一使用 `bash scripts/commit.sh`。

---

## 3. 外部 PR 分诊开关与 Wayfinding 约定

- **PRs as a request surface: no.**（默认关闭外部 PR 作为分诊入口）。
- **Wayfinding 操作（`/wayfinder`）**：主图工单标 `wayfinder:map`，子工单通过父子关联或 `Part of #<map>` 挂载并标 `wayfinder:<type>`；认领首步 `gh issue edit <n> --add-assignee "@me"`，完成后评论结论并关单。
