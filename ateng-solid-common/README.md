# ateng-solid-common

`ateng-solid-server` 公共基础设施与跨模块零业务原语模块（公共错误码段 `100000~100999`）。

## 职责边界

- 全局系统常量（`SolidConstants`）、公共错误码（`CommonBizResultCode`）、基础枚举（`IBaseEnum` 实现）与轻量领域事件发布端口；
- 仅依赖 `ateng-boot-core` 与基础校验/编译期注解，严禁引入 Web、MyBatis-Plus、Redis、Sa-Token 或具体业务域逻辑。

## 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 工程配置与 ArchUnit 守卫：[configuration-and-archunit-spec.md](../docs/specs/configuration-and-archunit-spec.md)
