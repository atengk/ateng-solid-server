---
trigger: always_on
description: "ateng-solid 全仓通用代码复用、大类拆解、设计模式选型、对象映射、事务防 N+1、空安全与交付门禁规范唯一真源"
---

# 全仓代码与架构编程规范 (coding-and-architecture.md)

> **作用域**：`ateng-solid` 聚合工作区及全部已启用与未来新增子仓库（`ateng-solid-server`、`ateng-solid-admin-web`、`ateng-solid-mp`/`app`/`desktop`/`portal-web`）。
> **唯一真源声明**：本文件主源维护于根仓库 `ateng-solid/.agents/rules/coding-and-architecture.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓库 `.agents/rules/coding-and-architecture.md`；禁止在子仓库直接修改副本造成规范漂移。
> **分工边界**：本文件定义**跨仓/跨技术栈共通的硬性架构与编程铁律**；Java 21 / Spring Boot / ArchUnit 细则及 Vue 3.5 / TypeScript / TailwindCSS 细则分别维护于各子仓库 `AGENTS.md` 与专职规则文件。

---

## 1. 分层架构与依赖单向流铁律

1. **三层生态单向依赖**：
   - 严格遵守“**通用底座（`ateng-boot`/`ateng-cloud`/`ateng-ui`）➔ 上层内核（`ateng-solid`）➔ 装配期扩展与二开业务（`ext-*`/`biz-*`）**”分层边界；
   - 通用技术增强优先沉淀入底座（触发 [`.agents/rules/foundation-first-gate.md`](./foundation-first-gate.md)）；扩展与二开模块**仅允许向内依赖 `common` 与 `*-api`（或前端核心契约），严禁篡改框架内核源码或向内核主表直接加列**；
   - 首期保持最小稳定内核，禁止提前建设空业务壳目录、引入辅助监控重包或引入与 `ateng-boot` / `ateng-ui` 重叠的冗余第三方库（如 `hutool-all`、`guava`、`lodash`、`moment`、`jquery`）。
2. **终端隔离与无共享前端私有包**：
   - 各前端终端仓库（`admin-web`、`portal-web`、`mp`、`app`、`desktop`）保持物理独立与构建隔离，端间**严禁抽取私有共享 UI/源码包**；跨端复用组件下沉至 `ateng-ui`，业务规则与接口收敛于后端；
   - 每个端独占对应后端路由前缀（如 `/admin/**`）与独立 `loginType` 会话体系（如 `satoken-admin`）。
3. **后端严格分层与模块隔离**：
   - 严格遵守 `Controller（协议与参数校验）➔ Service（业务编排与事务）➔ Mapper（持久层）` 调用链，严禁 Controller 直接注入或调用 Mapper；
   - **同源双装配禁令**：严禁跨领域模块注入对方 `Mapper`、执行跨模块 SQL JOIN、定义数据库物理外键（`FOREIGN KEY`）或在 `*-api` 契约模块引入 Web / ORM / RPC 注解；
   - **零 Entity 泄漏**：Controller 出入参（含泛型嵌套）与前端状态模型**严禁直接暴露或绑定持久层 `Entity`**，须通过强类型 DTO / VO 解耦。

---

## 2. 跨端命名、契约对齐与对象映射规范

1. **统一命名公约**：
   - 数据库表与列：全小写蛇形命名（`sys_` 内核域、`ext_` 扩展域、`biz_` 行业二开域）；主键统一 `id`；防软删唯一索引统一 `uk_<业务列>_deleted`；普通索引统一 `idx_<列名>`；
   - 接口路径：严格对称服务域前缀 `/admin/<域>/<资源>`，路径段使用小写连字符（如 `/tenant-package`）；
   - 权限标识：三段式 `<域>:<资源>:<动作>`（如 `system:user:create`）；
   - 配置键：后端业务扩展统一收敛至 `ateng.solid.*`（强类型 `@ConfigurationProperties`，禁 `@RefreshScope` 与零散 `@Value`）；前端环境变量统一 `VITE_*`；
   - Git 与提交：全仓统一 `main`（稳定只读）+ `dev`（活跃开发）双分支；提交信息严格遵循 Conventional Commits（`<type>(<scope>): <中文描述>`）。
2. **对象模型分离与映射策略（Stage 1 `BeanUtil` + 显式映射）**：
   - 首期后端模型转换统一采用 `ateng-boot-core` 内置 **`BeanUtil`（`copy` / `copyList` / `copyIgnoreNull`）** 或显式静态工厂转换（如 `UserVO.from(entity)`），**首期不引入 `MapStruct` / `MapStruct-Plus`**（保持最小依赖并避免 APT 与 Lombok 链路复杂化；后续扩展评估与触发阈值见根仓 `ADR-0006`）；
   - **敏感受管字段防覆盖铁律**：在新增/修改场景执行 `BeanUtil.copy*` 向持久层 `Entity` 拷贝 DTO 时，**严禁无过滤全量覆盖 `id`、`tenantId`、`password`、`version`、`deleted`、`createBy`、`createTime`、`updateBy`、`updateTime`**；涉及密码、状态、归属租户等关键字段必须走显式受控方法赋值。

---

## 3. 代码复用、大类拆解与设计模式选型

### 3.1 公共类与公共方法提取阶梯（DRY 与防微文件碎片平衡）
1. **单类内私有方法**：仅服务于当前类主流程可读性、消除单方法深层嵌套或局部重复片段时，提取为同文件 `private` 辅助方法，不向外暴露或新建多余小文件。
2. **同域包内协作者（Rule of Three）**：当同一业务域内出现 **2~3 处及以上实质相同逻辑**，或存在独立内聚的领域计算/规则（如部门祖级与树形拼装、数据权限过滤拼装、Excel 导入行级校验、复杂 DTO/VO 视图组装）时，提取为专职协作类（如 `*Helper`、`*Assembler`、`*Validator`、`*Support`）。
3. **跨模块与下沉底座优先级**：无业务状态、跨多端/多模块复用的纯技术原语，**优先检查 `ateng-boot-core` / `@atengk/core` / `@atengk/vue` 是否已原生提供**；确属纯技术底座缺口时按 [foundation-first-gate.md](./foundation-first-gate.md) 沉入底座，跨业务模块的通用领域原语才放入 `common` / `*-api`。
4. **反碎片化红线**：严禁为了形式主义“拆小类”而制造大量仅十几行的孤立微文件或无语义单行转发包装类；局部仅一处使用的辅助逻辑与私有类型优先在当前文件或归属域内聚闭环。

### 3.2 大类与大文件膨胀治理（审查触发线 + 垂直拆解）
1. **结构审查触发线（Code Review 拆分评估信号，不搞机械截断）**：
   - **后端 Java**：单个业务类（Service/Controller/Helper）超过 **400 行**、单个方法核心逻辑超过 **50 行**、或单个类构造器注入依赖超过 **6 个 Bean** 时，必须主动评估按职责拆解；
   - **前端 Vue / TS**：单个 `.vue` SFC 超过 **400 行**、单个 `.ts` 文件超过 **500 行**时，必须拆解子组件或无头 Composable。
2. **按职责垂直拆解方向（禁止堆砌上帝类）**：
   - **Controller 按资源域拆分**：严禁把用户的增删改查、导入导出、个人中心资料与在线会话强退全塞入一个胖 Controller；按职责拆分为独立控制器（如 `SysUserController`、`SysUserImportExportController`、`AuthProfileController`、`OnlineSessionController`）。
   - **Service 按子场景与协作者拆分**：主 `*Service` 聚焦核心状态流转与写事务；当同一实体伴随“复杂批处理导入导出”、“多表聚合列表检索”、“复杂前置校验链”时，拆出专职协作者（如 `SysUserExcelService`、`SysUserQueryService` / `SysUserAssembler`、`SysUserValidator`），避免单类构造器注入十余个跨表依赖。
   - **前端视图与状态拆解**：复杂页面将表单弹窗/抽屉/复杂面板拆为同视图目录 `components/*.vue`，将表单提交、防重与分页拉取状态编排下沉至 `composables/use*.ts` 或 Pinia Store。
3. **存量增量外拆铁律**：维护或扩展已有较大稳定类时，遵循最小变更原则——新增独立复杂能力**外拆为独立协作者并在原入口轻量委托**，严禁未经授权对存量稳定代码执行推倒重写式大重构。

### 3.3 合适设计模式与更优替代方案矩阵（克制套用，简单优先）
遵循“**表驱动 / 枚举内聚 / 函数式回调能解决的，绝不生搬硬套重型设计模式**”原则，核心业务演进优先采用以下清晰范式：

| 业务痛点 | 推荐模式 / 更优替代方案 | 典型应用锚点与落地约束 |
| :--- | :--- | :--- |
| **多类型分支膨胀（> 3 支 `if-else`/`switch`）** | **策略模式 + 容器自动路由 / 查找表** | 后端：定义策略接口（如 `AuthGrantHandler`），由 Spring 注入 `List<Handler>` 构建不可变路由表按 `grantType` 分发；前端：使用 `Record<Key, Handler>` 查找表代替长 `switch` |
| **主流程强耦合非核心副动作** | **观察者模式（领域事件 `DomainEventPublisher`）** | 登录审计、下线日志、操作记录与二期扩展响应，通过发布事件 + `@EventListener`（Cloud 态桥接 MQ）解耦，不污染主链路与事务 |
| **多步准入校验与风控拦截** | **有序职责链 / 校验器管道（`List<Validator>`）** | Excel 导入行校验、租户创建配额与前置规则校验，拆为独立可单测的校验节点有序执行，支持装配期插拔 |
| **共性骨架 + 局部算法差异** | **模板方法 / 函数式接口回调** | 树结构构建、分页流式导出、分布式锁/幂等保护，复用 `TreeUtil`、`ateng-boot-excel` 与函数参数注入差异点，避免重复拷贝骨架代码 |
| **状态流转判断散落多处** | **富行为枚举（Enum Behavior）** | 在 `IBaseEnum` 枚举自身内聚状态校验与流转方法（如 `canTransitionTo(...)`、`isEnabled()`），杜绝同一套状态魔法值判断复制粘贴到多个 Service |

---

## 4. 数据库访问、事务边界与防 N+1 规范

1. **防 N+1 查询铁律**：
   - 严禁在 `for` / `stream().map(...)` 循环体内发起数据库查询、Redis 逐条请求或外部 RPC 调用；列表关联组装必须采用批量 `IN` 查询 + `Map` 内存分组映射，批量写操作统一使用批处理接口。
2. **事务边界克制**：
   - 仅在涉及多步一致性写库时声明 `@Transactional(rollbackFor = Exception.class)`（避免泛滥加在只读查询或单条简单 SQL 上）；
   - **事务内严禁执行 HTTP/RPC 远程同步调用、发送外部邮件/短信或大文件网络 I/O**，防止长事务耗尽数据库连接池并引发死锁；跨模块/异步通知在事务提交后通过领域事件端口触发。
3. **SQL 安全与双库兼容性**：
   - 严禁在 MyBatis XML 或注解 SQL 中使用 `${}` 字符串拼接传参（一律使用 `#{}` 参数绑定防 SQL 注入）；
   - 严禁在业务 SQL 中使用 H2 不兼容或破坏索引的数据库专有函数（如 `FIND_IN_SET`、`ON UPDATE CURRENT_TIMESTAMP`、自定义存储过程），确保测试期 H2（`MODE=MySQL`）与生产期 MySQL 8.x 行为 100% 一致。

---

## 5. 空安全、并发与资源确定性释放

1. **空安全契约**：
   - 查询列表、树结构、分页记录与权限集合无匹配数据时，**前后端统一返回空集合（`Collections.emptyList()` / `[]`），绝对禁止返回 `null` 或 `undefined`**；
   - `Optional<T>` 严禁作为方法入参、DTO/Entity 字段或 JSON 序列化字段，仅用于内部查询返回值；调用方必须先判空再消费。
2. **单例无状态与并发安全**：
   - Spring `@Service` / `@Component` / `@RestController` 单例类必须保持无状态，严禁使用非线程安全共享成员变量（如普通 `HashMap`、`SimpleDateFormat`）；
   - **虚拟线程防 Pinning**：后端运行于 Java 21 虚拟线程环境，I/O 临界区与阻塞等待统一使用 `ReentrantLock` 替代 `synchronized`；禁止自建无界野线程，异步执行统一走受管线程池并传递四维上下文快照（`AsyncContext`）；
   - **前端响应式安全**：严禁直接突变 `props`，严禁失去响应性的解构，严禁跳过模板引用直接用 `document.querySelector` 操纵组件内部 DOM。
3. **超时与资源确定性释放**：
   - 所有外部 HTTP / RPC / 存储 / 中间件客户端调用必须显式配置连接与读取超时；
   - 后端 I/O 流与锁必须在 `try-with-resources` 或 `finally` 中释放；前端事件监听、定时器、`Observer`、连接与 `URL.createObjectURL` 必须在 `onUnmounted` / `onScopeDispose` 中 100% 释放。

---

## 6. 异常因果链、安全边界与日志脱敏

1. **异常处理与因果链**：
   - 禁止空 `catch` 块、禁止 `e.printStackTrace()`、禁止生产代码遗留 `System.out.println` 与裸 `console.log`；
   - 禁止在同一 `catch` 块中“既打印完整堆栈又原样向外重抛”（防日志风暴）；统一降级恢复，或转换为携带业务枚举 `IResultCode` 与原始 `cause` 的 `BusinessException` 向外抛出；
   - 全局异常兜底返回严禁向客户端透传 SQL 语句、内部表名或 Java 堆栈。
2. **内存真明文与日志脱敏红线**：
   - 接口 DTO 与前端状态层保持业务字段真明文（除非服务端专供脱敏只读展示），持久化加密收敛于 `@FieldEncrypt`，展示掩码收敛于 `@Sensitive` / `@ExcelSensitive` / `formatMask*`；
   - **日志严禁打印明文密码、密钥、Token、SessionId、完整身份证/银行卡号或未脱敏手机号**，日志输出统一绑定 `traceId`。
3. **多租户与越权防线**：
   - 普通租户请求严禁采信客户端伪造的 `X-Tenant-Id`；仅平台默认租户（`tenantId = 0`）且具备 `super_admin` 角色可受控切换视角，严禁使用 `"admin"` 作为免检超管字符。

---

## 7. 双模配置隔离与离线测试铁律

1. **机密零入库（Zero-Secret Invariant）**：
   - 云端 MySQL、Redis、对象存储、第三方密钥等真实连接信息**仅限写入被 `.gitignore` 忽略的本地文件（`application-local.yml` / `.env.local`）**；
   - 仓库内示例模板（`application-local.example.yml` / `.env.example`）、配置脚本与文档统一使用大写占位符（如 `YOUR_DB_HOST`、`YOUR_DB_PASSWORD`）。
2. **离线自洽测试与上下文清理**：
   - 后端单测与 ArchUnit 守卫默认依托 H2（`MODE=MySQL`）与内存降级、前端单测依托 Vitest + `vi.mock` 实现 **100% 离线自洽通过**，不依赖外部云端数据库或真实后端进程；
   - 测试用例必须在 `@AfterEach` / `afterEach` 中清理线程上下文（`UserContextHolder`、`TenantContextHolder`、`DataPermissionContextHolder`、MDC）或前端 Storage / Pinia / `wrapper.unmount()`。

---

## 8. 注释、1:1 文档沉淀与交付门禁

1. **文件与契约注释**：
   - 新建源码文件（Java 类/接口、TS/Vue 文件、独立脚本）均须包含简洁中文文档注释与独立 `@author Ateng`、`@since YYYY-MM-DD` 元数据；
   - 公共接口、Controller、核心 Service 方法、DTO/VO 字段、前端 Props/Emits 须写清业务语义、空值行为与约束边界；仅包含 `>= 2` 步跨组件/复杂事务编排时写 `// 1.`、`// 2.` 步骤注释。
2. **Code & Test-First 1:1 文档沉淀**：
   - 任何新功能开发完成并通过全部测试后，必须严格遵循 [`docs/agents/documentation-specification.md`](../../docs/agents/documentation-specification.md) 1:1 逆向更新对应模块 `README.md`（应用五维契约与第 0 节全景速查矩阵）、`docs/specs/`、`CONTEXT.md`、`docs/adr/` 与 `llms.txt`，并通过《10 项准出自检清单》。
3. **交付前本地验证与安全暂存**：
   - 后端交付前须本地通过 `mvn clean test -B -T 1C -nsu`；前端须本地通过 `pnpm typecheck && pnpm lint && pnpm test && pnpm build`；根工作区须通过 `bash scripts/workspace.sh check-rules`；
   - 未收到明确提交/推送指令前改动停留在工作区；暂存必须显式指定文件路径（严禁 `git add .` 或 `-A`）。
