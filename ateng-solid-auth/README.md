# ateng-solid-auth 管理端认证与会话治理模块

> 一句话定位：承担管理端登录注销、图形验证码、防爆破锁定、个人中心与在线会话治理（接口前缀 `/admin/auth/**`，错误码段 `101000~101999`）；当前已完成 `Spec 1` 错误码与强类型配置基线，认证处理器与 HTTP 端点将于后续 `Spec 2` 落地。

---

## 0. 已落地基础原语速查（`Spec 1`）

| 能力域 | 核心类 | 关键配置项 / 号段 | 说明 | 锚定验证测试 (`*Test.java`) |
| :--- | :--- | :--- | :--- | :--- |
| 认证错误码 | [`AuthResultCode`](./src/main/java/io/github/atengk/solid/auth/result/AuthResultCode.java) | `101001`–`101007`（7 项） | 实现 `IResultCode`，全局号段隔离零碰撞 | [`ResultCodeSegmentTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ResultCodeSegmentTest.java) |
| 认证配置 | [`SolidAuthProperties`](./src/main/java/io/github/atengk/solid/auth/properties/SolidAuthProperties.java) | `ateng.solid.auth.{captcha-enabled, session-handle-secret}` | 默认开启图形验证码；`toString` 屏蔽句柄密钥 | [`ConfigurationBaselineIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ConfigurationBaselineIntegrationTest.java) |

## 1. 职责边界

- **绝对模块隔离**：仅依赖 `ateng-solid-common` 与 `ateng-solid-system-api`，严禁依赖 `ateng-solid-system` 与任何持久层 Mapper/数据库表（由 `ArchitectureGuardTest` 规则 1 守卫）。
- **配置示例（提取自已验证基线）**：

```yaml
ateng:
  solid:
    auth:
      captcha-enabled: true
      session-handle-secret: ${SOLID_AUTH_SESSION_HANDLE_SECRET:YOUR_SESSION_HANDLE_SECRET}
```

## 2. 认证错误码附录（`101000~101999`）

| 枚举常量 | 错误码 | 默认提示文案 |
| :--- | :---: | :--- |
| `CAPTCHA_INVALID` | `101001` | 图形验证码无效或已过期，请重新输入 |
| `BAD_CREDENTIALS` | `101002` | 用户名或密码错误 |
| `ACCOUNT_DISABLED` | `101003` | 账号已被停用，请联系管理员处理 |
| `LOGIN_LOCKED` | `101004` | 连续登录失败次数过多，请稍后重试 |
| `TENANT_UNAVAILABLE` | `101005` | 所属租户不存在、已停用或已过期，请联系平台管理员 |
| `OLD_PASSWORD_MISMATCH` | `101006` | 当前密码输入有误，请核对后重试 |
| `CANNOT_KICKOUT_SELF` | `101007` | 当前会话正在使用中，不可强退自身会话 |

## 3. 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 认证与权限规范：[auth-rbac-and-datascope-spec.md](../docs/specs/auth-rbac-and-datascope-spec.md)
