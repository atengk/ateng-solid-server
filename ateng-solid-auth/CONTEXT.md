# ateng-solid-auth

管理端认证与会话治理领域上下文（接口前缀 `/admin/auth/**`，错误码段 `101000~101999`），零持久化业务表，仅依赖 `system-api` 契约与缓存。

## Language

**管理端令牌会话 (Admin Session)**:
隶属 `admin` 账号体系的独立签发（`is-share: false`）分布式登录会话与令牌载荷，每次登录签发互不干扰的独立 `tokenValue` 并通过 `satoken-admin` 请求头鉴权；低迭代历史 PBKDF2 口令哈希在登录校验成功后自动平滑升级至最新安全参数（不改动 `pwd_update_time`）。
_Avoid_: 复用默认 `login` 体系令牌，或同账号多端登录共享同一 Token 导致单端登出/踢出误伤其它端

**登录与登出审计解耦 (Login/Logout Audit Decoupling)**:
登录与登出审计事件通过显式动作标识（`LOGIN` / `LOGOUT`）区分，仅登录成功事件允许更新 `sys_user.login_ip` 与 `login_date`，登出仅追加登出审计流水。
_Avoid_: 登出回溯覆写用户最后登录 IP/时间

**图形验证码策略 (Captcha Policy)**:
可按配置开关控制的一次一密图形验证码防刷防线，校验成功后立即原子失效。
_Avoid_: 验证码可重复使用或硬编码强制开启导致自动化测试不可控

**作用域防爆破锁定 (Scoped Failure Lock)**:
按“租户 + 账号体系 + 用户名 + 客户端 IP”维度隔离的连续密码失败锁定与 IP 熔断防线，避免跨租户同名账号误锁。
_Avoid_: 仅以裸用户名作为全局锁定键

**不透明会话句柄 (Opaque Session Handle)**:
在线会话列表下发的不可用于 `satoken-admin` 认证的服务端签名映射标识（`sessionId`）及脱敏展示串（`tokenMask`），仅在强退端点于服务端还原真实令牌。
_Avoid_: 在在线会话列表接口直接向前端下发原始 `tokenValue`

**在线会话强退 (Force Logout)**:
按令牌或会话维度的即时踢出与令牌注销操作，不依赖客户端本地过期。
_Avoid_: 仅前端删除本地缓存而不注销服务端会话
