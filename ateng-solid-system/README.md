# ateng-solid-system 系统管理域核心业务实现模块

> 一句话定位：实现 `ateng-solid-system-api` 全部跨模块契约并承载组织权限、多租户、字典参数、DDL 脚本与审计落库（接口前缀 `/admin/system/**`）；当前已完成 `Spec 1` 系统领域配置基线，DDL、实体与业务端点将于后续 `Spec 1b ~ Spec 4` 分步落地。

---

## 0. 已落地配置原语速查（`Spec 1`）

| 配置类 | 配置前缀 | 包含参数组 | 说明 | 锚定验证测试 (`*Test.java`) |
| :--- | :--- | :--- | :--- | :--- |
| [`SolidSystemProperties`](./src/main/java/io/github/atengk/solid/system/properties/SolidSystemProperties.java) | `ateng.solid` | `init`、`data-scope`、`user`、`cache` | 内聚超管引导、部门 IN 上限、导入治理与缓存 TTL；`toString` 屏蔽初始口令 | [`ConfigurationBaselineIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ConfigurationBaselineIntegrationTest.java) |

## 1. 职责边界与配置基线

- **模块边界**：实现 `ateng-solid-system-api` 定义的全部契约接口，承载 `db/migration/system/` Flyway 脚本，严禁反向依赖 `ateng-solid-auth`，严禁在 Controller 出入参中暴露持久层 Entity。
- **YAML 配置基线（提取自已验证配置）**：

```yaml
ateng:
  solid:
    init:
      enabled: true
      super-admin-username: superadmin
      super-admin-nickname: 超级管理员
    data-scope:
      max-dept-in-size: 1000
    user:
      import-max-rows: 500
      max-concurrent-imports: 2
      import-feedback-ttl: 15m
    cache:
      dict-ttl: 30m
      config-ttl: 30m
```

## 2. 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 接口与错误码明细：[api-and-error-codes-spec.md](../docs/specs/api-and-error-codes-spec.md)
- 数据库与 Flyway 规范：[database-and-flyway-spec.md](../docs/specs/database-and-flyway-spec.md)
