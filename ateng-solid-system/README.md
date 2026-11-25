# ateng-solid-system 系统管理域核心业务实现模块

> 一句话定位：实现 `ateng-solid-system-api` 全部跨模块契约并承载组织权限、多租户、字典参数、审计落库及 `db/migration/system/` Flyway 脚本（接口前缀 `/admin/system/**`）；当前已完成 `Spec 1` 系统领域配置基线、17 张表 Flyway 迁移（`V1.0.0`/`V1.0.1`）、17 个持久层 Entity/Mapper 与 `ContactFieldCrypto` 确定性加密原语，业务 Service 与 Controller 将于后续 `Spec 2 ~ Spec 4` 分步落地。

---

## 0. 已落地契约、持久层与配置原语速查（`Spec 1`）

| 能力域 | 产物 / 入口 | 核心机制 / 配置键 | 边界与核心约定 | 锚定验证测试 (`*Test.java`) |
| :--- | :--- | :--- | :--- | :--- |
| 系统配置基线 | [`SolidSystemProperties`](./src/main/java/io/github/atengk/solid/system/properties/SolidSystemProperties.java) | `ateng.solid.{init,data-scope,user,cache}` | 内聚超管引导、部门 IN 上限、导入治理与缓存 TTL；`toString` 屏蔽初始口令 | [`ConfigurationBaselineIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ConfigurationBaselineIntegrationTest.java) |
| Flyway 建表 | [`V1.0.0__system_init_schema.sql`](./src/main/resources/db/migration/system/V1.0.0__system_init_schema.sql) | 17 张表（6 共享 + 5 核 + 4 关联 + 2 审计） | 统一 `utf8mb4_0900_ai_ci`、无外键、表简名索引前缀、软删显式 `deleted BIGINT DEFAULT 0` | [`FlywaySchemaAndSeedIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/FlywaySchemaAndSeedIntegrationTest.java) |
| Flyway 种子 | [`V1.0.1__system_init_seed.sql`](./src/main/resources/db/migration/system/V1.0.1__system_init_seed.sql) | `< 100,000` 确定性号段种子 | `sys_tenant(0)`、`sys_role(1)`、`sys_dept(100)`、58 条菜单、2 组字典与 `sys.user.initPassword`（无密码哈希） | [`FlywaySchemaAndSeedIntegrationTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/FlywaySchemaAndSeedIntegrationTest.java) |
| 持久层实体与 Mapper | `io.github.atengk.solid.system.{entity,mapper}` | 17 实体（`BaseLogicEntity`/`BaseIdEntity` + `` `TenantAware<Long>` ``）+ 17 `` `AtengBaseMapper<T>` `` | `SysUser`/`SysDept` 标注 `autoResultMap = true` 及 `@FieldEncrypt(deterministic = true)`；`SysUserMapper#selectUsernameById` | [`ContactFieldEncryptPersistenceTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ContactFieldEncryptPersistenceTest.java) |
| 联系字段确定性加密 | [`ContactFieldCrypto`](./src/main/java/io/github/atengk/solid/system/support/ContactFieldCrypto.java) | `ateng.boot.mybatis-plus.crypto.secret-key` | 6 个无状态方法：空白转 `null`、邮箱小写归一化、单值/批量去重确定性加密 | [`ContactFieldCryptoTest`](./src/test/java/io/github/atengk/solid/system/support/ContactFieldCryptoTest.java) · [`ContactFieldEncryptPersistenceTest`](../ateng-solid-bootstrap/src/test/java/io/github/atengk/solid/ContactFieldEncryptPersistenceTest.java) |

---

## 1. 职责边界与依赖隔离

- **模块边界**：实现 `ateng-solid-system-api` 定义的全部契约接口，承载 `db/migration/system/` Flyway 脚本，严禁反向依赖 `ateng-solid-auth`，严禁在 Controller 出入参中暴露持久层 Entity。
- **持久层继承基类矩阵**：
  - 10 张核心主表继承 `BaseLogicEntity`（`deleted BIGINT NOT NULL DEFAULT 0`，逻辑删除自动回填自身雪花主键 `id`，无 `@Version`）：`SysTenant`、`SysTenantPackage`、`SysMenu`、`SysDictType`、`SysDictData`、`SysDept`、`SysPost`、`SysRole`、`SysUser`、`SysConfig`；
  - 7 张关联与只追加审计日志表继承 `BaseIdEntity`：`SysTenantPackageMenu`、`SysUserRole`、`SysUserPost`、`SysRoleMenu`、`SysRoleDept`、`SysOperLog`、`SysLoginLog`；
  - 上述 11 张租户隔离表统一实现 `` `TenantAware<Long>` ``；17 个 Mapper 统一标注 `@Mapper` 并继承 `` `AtengBaseMapper<T>` ``（兼容 `BatchUtil.insertBatch` 原生批量操作）。

---

## 2. 配置基线与联系字段确定性加密

### 2.1 核心配置片段（提取自 `application.yml` 与本地/测试 Profile）

```yaml
spring:
  flyway:
    # application.yml 默认 false；application-test.yml 与 application-local.example.yml 置为 true
    enabled: true
    locations:
      - classpath:db/migration/system
    validate-on-migrate: true
    out-of-order: false

ateng:
  boot:
    mybatis-plus:
      crypto:
        secret-key: ${SOLID_CRYPTO_SECRET_KEY:YOUR_CRYPTO_SECRET_KEY}
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

### 2.2 `ContactFieldCrypto` 调用约定与双路径边界

`SysUser` 与 `SysDept` 均已标注 `@TableName(value = "...", autoResultMap = true)`，`phone`（`VARCHAR(128)`）与 `email`（`VARCHAR(255)`）统一标注 `@FieldEncrypt(deterministic = true)` 与 `@TableField(typeHandler = DeterministicEncryptTypeHandler.class)`：

```java
// 1. 实体写入（insert / updateById / BatchUtil.insertBatch）：统一先经 normalize* 归一化
user.setPhone(ContactFieldCrypto.normalizePhone(rawPhone));
user.setEmail(ContactFieldCrypto.normalizeEmail(rawEmail));
sysUserMapper.insert(user);

// 2. QueryWrapper / 原生 SQL / 批量 IN 查重（无自动 TypeHandler）：使用 encrypt*ForQuery
SysUser byPhone = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
        .eq("phone", ContactFieldCrypto.encryptPhoneForQuery(rawPhone)));
List<String> encryptedEmails = ContactFieldCrypto.encryptEmailsForQuery(rawEmails);

// 3. LambdaQueryWrapper（MyBatis-Plus 3.5.17 会通过 ColumnCache.mapping 自动挂载 TypeHandler）：须传 normalize*
SysUser byLambda = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
        .eq(SysUser::getPhone, ContactFieldCrypto.normalizePhone(rawPhone)));
```

- **禁止行为**：严禁对加密列使用 `LIKE` 模糊检索或 `ORDER BY` 排序；严禁向 `LambdaQueryWrapper` 传入 `encrypt*ForQuery` 密文（会触发二次加密）；严禁向 3 参 `LambdaUpdateWrapper#set` 直接传入裸明文（更新优先走 `updateById`，清空联系字段用 `.set(SysUser::getPhone, null)`）。

---

## 3. 关联文档

- 领域词汇表：[`CONTEXT.md`](./CONTEXT.md)
- 接口与错误码明细：[api-and-error-codes-spec.md](../docs/specs/api-and-error-codes-spec.md)
- 数据库与 Flyway 规范：[database-and-flyway-spec.md](../docs/specs/database-and-flyway-spec.md)
- 确定性加密决策记录：[ADR-0004](../docs/adr/0004-deterministic-field-encryption-and-search-boundary.md)
