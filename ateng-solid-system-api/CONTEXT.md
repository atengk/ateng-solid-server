# ateng-solid-system-api

系统管理域对外纯 Java 契约上下文（错误码段 `102000~102999`），定义跨模块接口与传输模型，零 Web、ORM 与 RPC 框架注解耦合。

## Language

**跨模块契约服务 (Remote Service Contract)**:
以 `Remote*Service` 命名的纯 Java 业务契约接口（覆盖用户、租户、权限与审计日志），统一直接返回裸 DTO/VO 并在业务失败时抛出 `BusinessException`。
_Avoid_: 契约方法返回 `Result<T>`、在 `-api` 模块暴露持久化 Entity 或贴 `@FeignClient`/`@Dubbo*` 注解

**五级数据权限范围 (DataScopeType)**:
决定角色可见数据行范围的枚举定义，包含全部（`ALL`）、自定部门（`CUSTOM`）、本部门（`DEPT`）、本部门及以下（`DEPT_AND_CHILD`）与仅本人（`SELF`）。
_Avoid_: 角色数据权限硬编码数字散落业务代码

**系统域错误码 (System Result Code)**:
归属系统管理域的 6 位业务异常码体系（`102000~102999`），跨单体与微服务形态保持全局唯一。
_Avoid_: 直接复用 HTTP 状态码表达细分业务失败原因
