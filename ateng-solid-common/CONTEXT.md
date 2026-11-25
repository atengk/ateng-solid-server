# ateng-solid-common

后端共享基础原语与跨模块通用概念上下文（公共错误码段 `100000~100999`），不含具体业务领域逻辑与持久化依赖。

## Language

**平台默认租户 (Platform Default Tenant)**:
系统内置的平台管理租户，主键固定为 `0L`，对外展示业务编码固定为 `000000`。
_Avoid_: 默认组织、根租户 `1`

**平台超级管理员角色 (Super Admin Role)**:
平台级全局治理角色编码 `super_admin`，仅配合平台默认租户生效，不允许普通租户复用。
_Avoid_: `admin`（作为免检超管编码）

**管理端登录体系 (Admin Login Type)**:
管理后台独立的 Sa-Token 账号体系标识 `admin`，对应请求头 `satoken-admin`。
_Avoid_: 默认 `login` 体系混用

**领域事件发布端口 (Domain Event Port)**:
跨模块异步解耦的抽象事件发送边界（单体默认由 `SpringDomainEventPublisher` 走进程内事件 + `AFTER_COMMIT`，微服务并存态可通过 `@Primary` 替换为 MQ 或远程调用）。
_Avoid_: 跨模块同步强耦合调用写日志

**认证策略扩展口 (Auth Grant SPI)**:
面向可插拔认证方式（账密、二期 OAuth2/短信）的纯 Java 策略接口（`AuthGrantHandler`），下沉至 `common` 使扩展模块无需反向依赖 `auth` 实现模块即可接入。
_Avoid_: 将认证扩展 SPI 定义在 `auth` 实现模块内部导致扩展包违反模块隔离红线
