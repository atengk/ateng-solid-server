# 架构决策记录 (Architecture Decision Records - ADR)

本目录记录 `ateng-solid-server` 后端服务工程的关键架构决策（ADR）。

## 决策矩阵总览 (Decision Matrix)

| 编号 | 决策标题 | 状态 | 核心收益与权衡 | 关联模块 | 详细文档 |
| :---: | :--- | :---: | :--- | :--- | :---: |
| **0001** | **5 模块同源双装配架构与 `*-api` 物理契约层设计** | 🟢 Accepted | 增设纯 Java 契约模块 `ateng-solid-system-api`（裸 DTO + `BusinessException`），编译期阻断跨模块依赖，支撑当下稳固单体并兼容后续并列挂载 `ateng-cloud` 启动壳实现双态并存 | 全域模块 | [查看](./0001-five-module-cloud-ready-topology.md) |
| **0002** | **6 位分段业务错误码与持久层零外键防冲突基线** | 🟢 Accepted | 确立 `100xxx/101xxx/102xxx` 分段错误码、`BaseLogicEntity`/`BaseFullEntity` 防软删重建冲突与 `tenant_id BIGINT` (`TenantAware<Long>`) 零物理外键建模基线 | `common`, `system-api`, `system`, `auth`, `bootstrap` | [查看](./0002-error-code-and-persistence-baseline.md) |
| **0003** | **后端单体与 `ateng-cloud` 微服务长期同源共存架构** | 🟢 Accepted | 确立同仓“共享业务核 + `bootstrap` 单体壳 + `ateng-solid-cloud/*` 微服务壳”长期共存布局、Dubbo/MQ 薄桥接与 Flyway 随域存放方案 | 全域模块、`bootstrap`、`ateng-solid-cloud` | [查看](./0003-monolith-and-cloud-coexistence-architecture.md) |
| **0004** | **联系字段确定性加密（`@FieldEncrypt`）与精确检索边界** | 🟢 Accepted | 确立 `sys_user`/`sys_dept` 联系字段统一确定性 SIV 加密、`ContactFieldCrypto` 收口封装、列宽放宽（`128`/`255`）及手机号精确检索权衡 | `system`、`bootstrap` | [查看](./0004-deterministic-field-encryption-and-search-boundary.md) |

---

## 维护准则

1. 仅在新增独立业务域模块、引入新中间件基础设施或变更核心架构契约时新建 ADR。
2. 局部优化优先修订既有 ADR，并同步维护本索引表与 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)。
