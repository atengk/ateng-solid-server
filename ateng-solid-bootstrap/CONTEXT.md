# ateng-solid-bootstrap

单体聚合启动装配、外部环境隔离与 Schema 版本化迁移上下文，严禁承载具体业务 Service 或持久层 Mapper。

## Language

**单体聚合宿主 (Monolith Assembly Host)**:
将 `common`、`system-api`、`system`、`auth` 装配为单一可运行服务的启动边界，与未来同仓 `cloud-*` 微服务启动壳并存而不修改业务核心。
_Avoid_: 在启动模块编写业务 Controller、Service 或 Mapper

**双模运行配置 (Dual-Mode Configuration)**:
生产/本地真实外部中间件连接配置严格隔离于被忽略的本地文件，测试与 CI 默认依托内存与 H2 数据库 100% 离线自洽。
_Avoid_: 将真实数据库密码或 Redis 凭据写入受版本控制的配置文件

**架构红线守卫 (Architecture Guard)**:
在测试期自动化验证模块隔离边界、零 Entity 泄漏与强制构造器注入的静态规则集。
_Avoid_: 仅靠口头约定约束跨模块依赖
