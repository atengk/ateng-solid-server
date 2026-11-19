# ateng-solid-system-api

系统管理域对外纯 Java 契约模块（系统域错误码段 `102000~102999`），为“单体 + 微服务同源双装配”提供编译期物理隔离。

## 职责边界

- 定义跨模块 `Remote*Service` 纯 Java 接口、不可变跨模块 DTO/VO 与系统域枚举/错误码；
- **严格零框架污染**：严禁引入 Spring Web、MyBatis-Plus、Redis、持久层 Entity 或 `@FeignClient`/`@Dubbo*` 注解；
- 所有契约方法统一直接返回裸 DTO/VO（禁返回 `Result<T>`），业务失败统一 `throw new BusinessException(...)`。

## 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 同源双装配边界守则：[cloud-readiness.md](../.agents/rules/cloud-readiness.md)
