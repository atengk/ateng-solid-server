# 编码规范、架构拆解与注释准则 (coding-standards.md)

> **唯一真源**：维护于根仓 `ateng-solid/docs/agents/coding-standards.md`，由 `bash scripts/workspace.sh sync-rules` 单向同步至各子仓 `docs/agents/coding-standards.md`。
> **适用时机**：编写新业务服务/复杂页面、拆分大类与公共逻辑、编写文档/逻辑注释，或执行 `/code-review` 前必读。

---

## 1. 公共提取阶梯（DRY 与反碎片化平衡）

1. **单类内私有方法**：仅服务当前类主流程可读性、消除深层嵌套或局部重复时，提取为同文件 `private` 辅助方法，不新建多余小文件。
2. **同域协作者（Rule of Three）**：同一业务域出现 **2~3 处以上实质相同逻辑**，或存在独立内聚的领域规则（如部门树/祖级拼装、数据权限过滤、Excel 行校验、复杂 DTO/VO 组装）时，提取为专职协作者（`*Helper`、`*Assembler`、`*Validator`、`*Support`）。
3. **底座复用优先**：跨模块/跨端的纯技术原语优先复用 `ateng-boot-core`、`@atengk/core`、`@atengk/vue`；确属底座缺口按 [`foundation-first-gate.md`](./foundation-first-gate.md) 下沉底座，仅跨业务模块的通用领域原语置入 `common` / `*-api`。
4. **反微文件碎片**：不为了形式主义制造十几行的孤立微文件或无语义单行转发壳；单处使用的私有逻辑与局部类型在同文件闭环内聚。

---

## 2. 大类与大文件膨胀治理

### 2.1 结构审查触发线（Code Review 拆分评估信号，不机械截断）
- **后端 Java**：单类超过 **400 行**、单方法核心逻辑超过 **50 行**，或构造器注入超过 **6 个 Bean**；
- **前端 Vue / TS**：单 `.vue` SFC 超过 **400 行**，或单 `.ts` 文件超过 **500 行**。

### 2.2 垂直拆解与存量演进
- **Controller 按资源职责拆分**：将核心 CRUD、导入导出、个人中心、在线会话拆分至独立控制器（如 `SysUserController`、`SysUserImportExportController`、`AuthProfileController`、`OnlineSessionController`）。
- **Service 按子场景拆协作者**：主 `*Service` 聚焦核心状态流转与写事务；复杂导入导出、多表聚合查询、复杂校验链外拆为 `*ExcelService`、`*QueryService`、`*Assembler`、`*Validator`。
- **前端视图与状态拆解**：复杂页面将表单弹窗/抽屉/复杂面板拆至同视图 `components/*.vue`，把请求、防重与分页编排下沉至 `composables/use*.ts` 或 Pinia Store。
- **存量大文件增量外拆**：维护既有大文件遵循最小变更原则，新增复杂能力外拆为独立协作者并在原入口轻量委托，不对存量稳定代码做无授权全量重写。

---

## 3. 设计模式克制选型矩阵

遵循“**表驱动 / 枚举内聚 / 函数式回调能解决的，不套重型模式**”原则：

| 业务痛点 | 推荐范式 | 落地锚点 |
| :--- | :--- | :--- |
| 多类型分支（> 3 支 `if-else`/`switch`） | 策略模式 + 不可变路由表 | 后端用接口（如 `AuthGrantHandler`）+ Spring `List<Handler>` 建路由表；前端用 `Record<Key, Handler>` 查找表 |
| 主流程耦合非核心副动作 | 领域事件（观察者） | 审计/登录日志/下线通知经 `DomainEventPublisher` 在事务外解耦，Cloud 态无缝切 MQ |
| 多步准入校验与风控拦截 | 有序校验管道（`List<Validator>`） | Excel 行校验、租户配额与前置规则拆为可独立单测的有序校验节点 |
| 共性骨架 + 局部算法差异 | 模板方法 / 函数式回调 | 树构建、流式导出、分布式锁/幂等复用底座工具并注入回调差异点 |
| 状态流转判断散落多处 | 富行为枚举（`IBaseEnum`） | 状态校验与流转规则内聚于枚举方法（如 `canTransitionTo(...)`、`isEnabled()`） |

---

## 4. 数据库访问、事务、异常与并发底线

- **防 N+1 查询**：严禁在 `for` / `stream().map(...)` 循环内发起 DB 查询、Redis 逐条请求或外部 RPC；列表关联组装统一用批量 `IN` 查询 + `Map` 分组，批量写用批处理接口。
- **事务边界克制**：仅多步一致性写库声明 `@Transactional(rollbackFor = Exception.class)`；**事务内严禁发起 HTTP/RPC 远程同步调用、邮件/短信或大文件网络 I/O**，跨模块异步通知在事务提交后经领域事件触发。
- **空安全与并发**：集合无匹配返回空集合（`Collections.emptyList()` / `[]`），禁返回 `null`；`Optional<T>` 严禁作方法入参、实体字段或序列化字段；单例 Bean 保持无状态（禁共享 `HashMap`/`SimpleDateFormat`）；所有外部 HTTP/RPC/中间件客户端显式配置连接与读取超时，I/O 与锁经 `try-with-resources`/`finally` 释放。
- **异常与日志闭环**：严禁空 `catch`、`e.printStackTrace()`、`System.out.println`、生产裸 `console.log`；遵循“要么记录日志并降级恢复，要么封装因果链 `BusinessException(IResultCode, msg, cause)` 抛出”，**严禁同一 `catch` 块既打印堆栈又原样重抛（Log-and-Throw）**。

---

## 5. 规范注释与代码内部逻辑注释

### 5.1 文件与类级文档注释（Doc Comments）
- **时机与豁免**：新建源码文件与脚本时必须编写；修改存量文件保留原作者与既有头注释；配置文件（YAML/JSON）与 Markdown 豁免。
- **语法与位置安全**：遵循语言原生注释范式（Java Javadoc / TS JSDoc / Shell 顶部 `#` 注释），不破坏语法结构与文件级指令（如 Shell `#!/usr/bin/env bash`、Vue `<script setup lang="ts">` 顶部指令）。
- **固定排版**：首段直述核心业务或技术职责（**严禁添加 `职责说明：`、`功能：` 等字面量前缀**）；空行隔开元数据，独立分行标注 `@author Ateng` 与 `@since YYYY-MM-DD`（写入真实日期）。

### 5.2 契约、方法与字段注释
- **强制覆盖范围**：对外 Controller、`Remote*Service` 契约接口、核心公共 Service、核心 Entity / DTO / VO 字段，以及前端组件 `Props` / `Emits`、`src/api/*` 接口与核心 `composables`。
- **信息增量**：使用 `@param` / `@return` / `@throws` 说明业务含义、空值约定、格式约束与特殊返回值；自解释私有小方法与标准 `@Override` 方法免写冗余注释。

### 5.3 代码内部逻辑与步骤注释
- **主干步骤注释（`// 1.`）**：仅在包含多阶段业务编排、跨组件协同、事务流转或复杂分支（**步骤数 ≥ 2**）的方法内，使用 `// 1. ...`、`// 2. ...`（Shell 用 `# 1. ...`）提纲化标注关键阶段；**严禁出现单个孤立 `// 1.` 伪步骤，严禁对简单 CRUD、单步透传或“校验-调用-返回”线性逻辑强刷步骤号**。
- **聚焦“为什么（Why）”而非逐行翻译**：复杂逻辑优先抽离自解释私有方法，避免给显而易见的代码写“翻译式注释”；行内注释聚焦**意图与防御性决策**（如并发锁范围、幂等防重原因、软删防冲突策略、H2/MySQL 兼容取舍、前端事件防重原因）。
- **自解释命名与待办闭环**：优先以清晰命名、具名常量和枚举表达语义，严禁用注释为魔法值打补丁；改代码时同步更新或清理失效注释；确因外部依赖未就绪需留待办时统一标注 `// TODO(Ateng): <原因与后续动作>`。
