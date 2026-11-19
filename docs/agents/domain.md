# 领域文档消费与维护规范 (Domain Docs)

本仓库（`ateng-solid-server`）采用 **多上下文（Multi-Context）** Maven 多模块架构，规范工程技能在探索、设计和重构代码前如何读取和维护词汇表与架构决策记录（ADR）。

---

## 1. 探索与修改代码前必读

1. **全局上下文映射 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)**：定义 5 个核心模块（`common`、`system-api`、`system`、`auth`、`bootstrap`）的职责边界与单向无环依赖拓扑。根目录唯一维护 `CONTEXT-MAP.md`，**不在根目录创建单体 `CONTEXT.md`**。
2. **目标模块纯领域词汇表 `ateng-solid-<module>/CONTEXT.md`**：处理任一模块前必读对应词汇表，严格恪守“纯词汇表（Glossary）”定位——仅记录领域术语定义与 `_Avoid_` 禁用反模式词汇，不混杂实现类代码或配置细节。
3. **系统级架构决策记录 [`docs/adr/`](../adr/README.md)**：查阅同源双装配拓扑（ADR-0001）、分段错误码与持久层防冲突基线（ADR-0002）等核心决策。

---

## 2. 目录布局（Multi-Context）

```text
ateng-solid-server/
├── CONTEXT-MAP.md                      # 后端模块上下文与依赖关系唯一入口
├── docs/
│   ├── adr/                            # 后端架构决策记录 (ADR-0001 ~ N)
│   ├── agents/                         # Agent 技能协作配置
│   └── specs/                          # 数据库、认证权限、API/错误码与 ArchUnit 规范
├── ateng-solid-common/CONTEXT.md       # 公共原语词汇表
├── ateng-solid-system-api/CONTEXT.md   # 系统域纯契约词汇表
├── ateng-solid-system/CONTEXT.md       # 系统管理域词汇表
├── ateng-solid-auth/CONTEXT.md         # 认证与会话域词汇表
└── ateng-solid-bootstrap/CONTEXT.md    # 启动装配与迁移词汇表
```

---

## 3. 术语对齐与 ADR 治理规则

- **词汇表强一致**：类名、DTO/VO、接口路径、错误码枚举、Issue 标题与测试名严格对齐所属模块 `CONTEXT.md` 的标准术语，杜绝使用 `_Avoid_` 中列出的反模式词。
- **内联演进**：新领域概念一旦确立，立即内联更新对应 `CONTEXT.md`（若触及模块间关系，同步更新 `CONTEXT-MAP.md`）。
- **ADR 冲突显式声明**：仅在满足“难以逆转 + 缺上下文令人费解 + 存在真实权衡”三条件时新建 ADR；若方案与既有 ADR 冲突，必须显式指出（如 `> _与 ADR-0001 存在冲突，重审原因：..._`），严禁静默覆盖。
