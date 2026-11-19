# ateng-solid-auth

管理端认证与会话治理模块（接口前缀 `/admin/auth/**`，错误码段 `101000~101999`）。

## 职责边界

- 承担管理端登录注销（`loginType = "admin"`，`satoken-admin`）、图形验证码（`CaptchaTemplate`）、多租户作用域防爆破、个人中心改密与在线会话治理；
- **绝对模块隔离**：仅依赖 `ateng-solid-common` 与 `ateng-solid-system-api`，严禁依赖 `ateng-solid-system` 与任何持久层 Mapper/数据库表。

## 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 认证与权限规范：[auth-rbac-and-datascope-spec.md](../docs/specs/auth-rbac-and-datascope-spec.md)
