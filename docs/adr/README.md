# 架构决策记录 (Architecture Decision Records - ADR)

本目录记录 `ateng-solid-server` 后端服务工程的关键架构决策（ADR）。

## 决策矩阵总览 (Decision Matrix)

| 编号 | 决策标题 | 状态 | 核心收益与权衡 | 关联模块 | 详细文档 |
| :---: | :--- | :---: | :--- | :--- | :---: |
| **0001** | **5 模块同源双装配架构与 `*-api` 物理契约层设计** | 🟢 Accepted | 增设纯 Java 契约模块 `ateng-solid-system-api`（裸 DTO + `BusinessException`），编译期阻断跨模块依赖，支撑当下稳固单体并兼容后续并列挂载 `ateng-cloud` 启动壳实现双态并存 | 全域模块 | [查看](./0001-five-module-cloud-ready-topology.md) |
| **0002** | **6 位分段业务错误码与持久层零外键防冲突基线** | 🟢 Accepted | 确立 `100xxx/101xxx/102xxx` 分段错误码、`BaseDeleteIdEntity` 防软删重建冲突与 `tenant_id BIGINT` 零物理外键建模基线 | `common`, `system-api`, `system`, `auth`, `bootstrap` | [查看](./0002-error-code-and-persistence-baseline.md) |

---

## 维护准则

1. 仅在新增独立业务域模块、引入新中间件基础设施或变更核心架构契约时新建 ADR。
2. 局部优化优先修订既有 ADR，并同步维护本索引表与 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)。
