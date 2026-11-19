# 贡献指南 (Contributing Guide)

感谢你参与 `ateng-solid-server` 后端服务工程的研发与贡献！

---

## 1. 分支与开发模型

本仓库统一遵循 **`main`（稳定发布只读）+ `dev`（活跃开发）双分支** 模型：

1. 确保本地激活提交校验钩子并处于最新 `dev` 分支：
   ```bash
   git config core.hooksPath .githooks
   git checkout dev
   git pull origin dev
   git checkout -b feat/your-feature-name
   ```
2. 编码须严格遵循 [`AGENTS.md`](./AGENTS.md)、[`.agents/rules/contracts.md`](./.agents/rules/contracts.md) 与 [`.agents/rules/cloud-readiness.md`](./.agents/rules/cloud-readiness.md)；
3. 提交前必须确保全量离线 H2 测试通过：
   ```bash
   mvn clean test -B -T 1C -nsu
   ```
4. 向本仓库 `dev` 分支发起 **Pull Request**（发版时由 `dev` 合并至 `main`）。

---

## 2. Commit 提交规范

统一遵循 Conventional Commits：`<type>(<scope>): <清晰中文描述>`（推荐 `scope`：`common`、`system-api`、`system`、`auth`、`bootstrap`、`flyway`、`docs`、`ci`）。

```bash
# 交互式向导（Windows PowerShell / CMD 可直接用 bash 运行）
bash scripts/commit.sh

# 非交互式（先精准 git add 目标文件）
git add <file-path>
bash scripts/commit.sh -t feat -s auth -m "实现管理端账号密码登录与验证码校验" -y
```

---

## 3. 特性即文档与 PR 门禁

- 新增对外 API、配置属性（`ateng.solid.*`）、错误码或数据库迁移脚本，必须在同一 PR 中同步补充相关设计文档与可验证测试用例；
- PR 标题须符合 Conventional Commits，推荐采用 **Squash and merge** 合入 `dev`。

---

## 4. 发版流程

仅允许在干净同步的 `main` 分支通过 GitHub Actions 网页端 `workflow_dispatch`、原生 `git tag vX.Y.Z` 或 `bash scripts/release.sh` 发起发版。
