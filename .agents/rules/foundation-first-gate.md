# 底层缺口即停工规范 (Foundation-First Hard Gate)

> **作用域**：`ateng-solid` 聚合工作区及全部子项目（`ateng-solid-server`、`ateng-solid-admin-web`、后续微服务启动壳与多端工程）。
> **唯一真源声明**：本文件主源维护于根仓库 `ateng-solid/.agents/rules/foundation-first-gate.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓库 `.agents/rules/foundation-first-gate.md`，禁止在子仓库擅自改动副本。
> **核心目标**：以业务上层项目反向打磨并验证底座工程，打造**生产实际可用、跨项目通用独立的基础设施底座（`ateng-boot`、`ateng-cloud`、`ateng-ui`）**与上层框架，坚决杜绝在上层工程通过临时 Hack/补丁掩盖底座缺陷，同时严防底层被单一业务项目绑死。

---

## 1. 纳管底座范围与仓库映射

| 底座工程 | 上游 GitHub 仓库 | 适用上层阶段 / 模块 |
| :--- | :--- | :--- |
| **`ateng-boot`** | `atengk/ateng-boot` | 单体核心、全微服务业务基础（Core / Web / MyBatis-Plus / Redis / Sa-Token / Excel / Storage / Flyway / Observability） |
| **`ateng-cloud`** | `atengk/ateng-cloud` | 双态微服务并存宿主与跨服演进（Gateway / Nacos / Feign / Dubbo / Sentinel / Traffic / Seata） |
| **`ateng-ui`** | `atengk/ateng-ui` | 全前端无头状态机、HTTP 传输与 UI 组件底座（`@atengk/core` / `@atengk/vue` / `@atengk/element-plus` / `@atengk/monitor`） |

---

## 2. 底座通用性与零项目绑定铁律 (Foundation Generality & Non-Coupling)

`ateng-boot`、`ateng-cloud`、`ateng-ui` 是面向所有外部与内部项目独立复用的通用开源底座，**绝对不能为了迎合 `ateng-solid` 而将其写死绑定到本项目的业务假设**：

1. **禁止硬编码项目专属约定**：严禁要求底座写死 `ateng-solid` 专属表名（如 `sys_*`）、业务默认租户编码（如 `'000000'`）、专属业务错误码段（如 `101xxx`/`102xxx`），或破坏性移除其他项目依赖的既有默认行为（如默认保留兼容超管角色、默认关闭需显式开启的高危开关）。
2. **诉求收口为“通用抽象 + 向后兼容默认 + Opt-in 配置/SPI”**：向上游提 Issue 时，方案必须具备跨项目通用性——优先通过 `@ConfigurationProperties` 配置开关、`@ConditionalOnMissingBean` SPI 钩子、回调 Prop 或标准接口扩展暴露能力，使其他项目开箱即用不受影响，而 `ateng-solid` 通过上层配置或薄适配器声明自身业务约定。
3. **专属业务规则留在上层**：若某个需求纯属 `ateng-solid` 独有逻辑且无法抽离为跨项目通用的技术抽象，仅要求底座提供必要扩展点，业务定制严格留在 `ateng-solid` 实现。

---

## 3. 硬停工触发门槛 (Hard-Stop Criteria)

在上层项目设计或编码过程中，一旦识别到以下任一“大功能或基础设施级别”缺口，**必须立即执行硬停工（HARD STOP）**，严禁在业务仓编写绕过补丁（Workaround）或重复造轮子：

### 3.1 必须停工提 Issue 场景
1. **跨模块/跨端可复用的核心基础设施能力缺失**：
   - 后端单体基础设施：认证鉴权多账号路由、超管角色可配置化、`@DataScope` 与拦截器上下文协同、行级多租户通用解析/扩展钩子、防爆破作用域隔离、审计/存储/导入导出核心机制等；
   - 后端微服务治理能力：网关统一鉴权与多租户安全扩展、Same-Token 双向契约、Feign/Dubbo 自适应解包与异常链路（含 `traceId`）还原、Sentinel 降级协同、Seata 分布式事务与租户协同、灰度与 Nacos 配置治理等；
   - 前端底座能力：HTTP 契约解包、雪花长整型防截断、`AuthManager`/`TenantManager` 隔离、核心 `useAuth`/`usePagination`/`useTabsNav` 无头状态机、`AtengProLayout`/`AtengTable`/`AtengQueryForm`/`AtengExcel*`/`v-permission` 等基础设施组件或指令通用扩展。
2. **底座 Defect、安全隐患、并发/虚拟线程风险或扩展点缺失**：
   - 如硬编码越权风险且不可配置覆盖、ThreadLocal/异步上下文清理遗漏、`synchronized` Pinning、第三方包共生崩溃（如 `Feign + Sentinel`）、因底座写死且无扩展点导致上层无法通过配置或 SPI 对齐业务契约等。
3. **跨两处及以上复用的通用技术增强**：
   - 明确可在 `ateng-solid` 多个业务域、未来多端工程以及其他独立外部项目中复用的纯技术能力。

### 3.2 不触发停工范畴（上层自行消化）
- 纯业务领域逻辑、业务表字段编排（如 `sys_user`/`sys_role` 业务校验与组装）；
- 单应用专属路由表配置、后端 DTO 到前端视图的纯字段转换；
- 底座已提供显式配置开关、SPI 扩展点或构造参数（例如配置 `path-prefix`、覆写 `superAdminRoles: ['super_admin']`、前端自行组合 `<KeepAlive>`）即可 100% 适配的场景。

---

## 4. 五步标准执行闭环 (Stop ➔ Issue ➔ Unblock ➔ Upgrade ➔ Resume)

当触发 3.1 任一条件时，开发与 AI Agent 必须严格遵循以下 5 步流水线，不得跳步：

1. **立即停工与上下文隔离**：
   - **暂停受该底座缺口直接阻断的当前特性/Spec 开发**（禁止强行“临时绕一下先写上层代码”）；
   - 与该缺口无依赖关联的独立文档、架构设计、纯配置骨架或其他正交模块可并行推进，保持研发节奏不空转。
2. **源码实测定位与查重**：
   - 核查对应底座仓库真实源码与 GitHub Issues（开放/近期关闭），精确锁定类/方法锚点、复用冲突原因与**通用且向后兼容**的改造方案，严禁提重复、项目强绑定或凭空猜想的 Issue。
3. **登记增强清单并立即提报 GitHub Issue**：
   - 使用 `gh issue create -R atengk/<repo>`（长正文通过 Scratch 临时文件 `-F` 传入，严禁带入本地绝对盘符或机密信息）直接向上游 `atengk/ateng-boot`、`atengk/ateng-cloud` 或 `atengk/ateng-ui` 提交 Issue；
   - 将 Issue 编号、链接、状态锚点同步登记至根仓 `docs/specs/foundation-enhancements-checklist.md`（若在单开子仓工作，记录缺口描述并提示回根仓同步清单）。
4. **等待底座满足与版本消费纪律**：
   - 本地开发与联调阶段可短暂消费底座新近本地构建（`mvn install` / 本地工作区联调）提前验证契合度；
   - 任何改动合入上层 `dev`/`main` 分支、CI 校验及发布前，**严格要求锁定已正式发布的底座版本**（Maven Release / npm 正式包，严禁跨仓长期依赖未发布 `SNAPSHOT`）。
5. **验收复核与恢复开工**：
   - 上游 Issue 关闭且底座新版本验证通过后，更新 `docs/specs/foundation-enhancements-checklist.md` 状态勾选项（如替换版本依赖并移除临时降级注释），随后恢复被阻塞上层特性的正常研发。
