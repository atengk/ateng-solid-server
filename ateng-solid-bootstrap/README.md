# ateng-solid-bootstrap

`ateng-solid-server` 单体聚合启动宿主、双模外部配置文件与 ArchUnit 架构守卫入口。

## 职责边界

- 聚合装配 `ateng-solid-auth` 与 `ateng-solid-system` 提供单体运行包（与未来同仓 `ateng-solid-cloud/*` 微服务启动壳并存）；
- 承载 `application*.yml` 双模配置（敏感凭据隔离于被 `.gitignore` 忽略的 `application-local.yml`）与 `ArchitectureGuardTest`；
- **严禁**在本模块编写业务 Controller、Service、Mapper 或私藏各领域 Flyway 脚本。

## 本地验证

```bash
mvn clean test -B -T 1C -nsu
```

## 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 工程配置与 ArchUnit 规格：[configuration-and-archunit-spec.md](../docs/specs/configuration-and-archunit-spec.md)
