# ateng-solid-system

系统管理域核心业务实现模块（接口前缀 `/admin/system/**`）。

## 职责边界

- 实现 `ateng-solid-system-api` 定义的全部契约接口；
- 承载多租户、租户套餐、用户、角色（含 5 级 `@DataScope`）、菜单、部门、岗位、动态数据字典、系统参数与登录/操作日志持久化；
- 严禁反向依赖 `ateng-solid-auth`，严禁在 Controller 出入参中暴露持久层 Entity。

## 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 接口与错误码明细：[api-and-error-codes-spec.md](../docs/specs/api-and-error-codes-spec.md)
- 数据库与 Flyway 规范：[database-and-flyway-spec.md](../docs/specs/database-and-flyway-spec.md)
