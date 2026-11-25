# ADR-0004: 联系字段确定性加密（`@FieldEncrypt`）与精确检索边界

- **状态**：`Accepted`
- **日期**：2026-11-25
- **作者**：Ateng
- **关联模块**：`ateng-solid-system`、`ateng-solid-bootstrap`（联动前端 `/system/user` 检索交互）

---

## 1. 背景与实测事实

`ateng-solid` 兼具“通用底座实战试金石”与“可直接二开的企业级开源后台框架”双定位，跨端契约要求敏感联系字段持久化加密收敛于底座 `@FieldEncrypt`，展示掩码收敛于 `@Sensitive` / `@ExcelSensitive` / 前端 `formatMask*`。

经对 `ateng-boot-mybatis-plus` 源码一手核查，明确了以下不可回避的底层物理约束：
1. 底座不存在拦截所有 SQL 参数与结果集的 MyBatis 全局加解密插件，持久层加密由 **`BaseTypeHandler<String>` + `@TableName(autoResultMap = true)`** 驱动；`@FieldEncrypt` 本身作为元数据标记驱动慢 SQL 脱敏、排序拦截与 `DynamicQueryHelper`；
2. 默认 `EncryptTypeHandler` 采用每次随机 IV，**无法支持数据库唯一索引或 `=`/`IN` 精确检索**；`DeterministicEncryptTypeHandler`（HMAC-SHA256 合成固定 IV，密文格式 `"v1:" + Base64(16B SIV || cipher)`）支持等值查询与唯一索引，但仍**绝对不支持 `LIKE` 模糊查询与 `ORDER BY`**；
3. 确定性加密对明文长度与字符字节高度敏感：11 位手机号加密后为 `47` 字符，**64 字符邮箱加密后长达 `131` 字符**（传统 `VARCHAR(64)`/`VARCHAR(128)` 会触发 MySQL `Data too long`）；HMAC-SHA256 区分大小写且 `encryptDeterministic("")` 原样返回 `""`（会导致防软删唯一索引因多条空串冲突）；
4. MyBatis-Plus 常规 `LambdaQueryWrapper.eq/in` 与 `LambdaUpdateWrapper.set` **不会自动触发 `TypeHandler`**，直接传明文会导致查不到记录或误写入裸明文。

---

## 2. 核心决策

1. **统一加密范围与列宽放宽**：
   - `SysUser.{phone, email}` 与 `SysDept.{phone, email}` 统一启用 **`@FieldEncrypt(deterministic = true)` + `@TableField(typeHandler = DeterministicEncryptTypeHandler.class)`**，并在实体类强制标注 `@TableName(value = "...", autoResultMap = true)`；
   - 两张表的 DDL 列宽统一放宽为 `phone VARCHAR(128) NULL`、`email VARCHAR(255) NULL`，`sys_user` 建立联合唯一索引 `uk_user_tenant_phone_deleted` 与 `uk_user_tenant_email_deleted`（MySQL 8.4 与 H2 `MODE=MySQL` 均放行多个 `NULL` 并拦截重复非空密文）。
2. **收口深模块 `ContactFieldCrypto` 防散落漏加密**：
   - 在 `ateng-solid-system` 下统一沉淀 `io.github.atengk.solid.system.support.ContactFieldCrypto`，收口空白转 `null`（`normalizePhone`）、小写+空白转 `null`（`normalizeEmail`）以及单值/批量确定性查重加密（`encryptPhoneForQuery`/`encryptEmailForQuery`/`encryptPhonesForQuery`/`encryptEmailsForQuery`）；
   - 严禁在业务 Service 中直接向 `LambdaQueryWrapper` 传入裸明文手机号/邮箱，更新加密列统一经 `updateById(entity)` 执行。
3. **搜索交互边界显式收敛**：
   - `/system/user` 列表搜索框 `phone` 明确收敛为**完整手机号精确匹配**（前端占位提示标注精确查询），仅 `username` 与 `nickname` 保留模糊检索。
4. **种子与密钥治理**：
   - `V1.0.1__system_init_seed.sql` 严禁写入明文 `phone`/`email`；
   - `ateng.boot.mybatis-plus.crypto.secret-key` 必须通过环境变量或本地忽略的 `application-local.yml` 显式注入固定密钥（`application-test.yml` 配置测试桩密钥），禁止依赖底座内置回退默认密钥或上线后无迁移计划地变更密钥。

---

## 3. 备选方案与权衡

| 备选方案 | 放弃原因 |
| :--- | :--- |
| **使用默认随机 IV `EncryptTypeHandler`** | 无法在数据库层建立 `uk_user_tenant_phone_deleted` / `uk_user_tenant_email_deleted` 唯一索引，且无法支持 500 行导入 `IN` 查重与手机号检索 |
| **仅加密 `sys_user`，`sys_dept` 保持明文** | `sys_dept` 与 `sys_user` 同样包含联系手机/邮箱且前台同等做脱敏渲染，拆分两套持久层写法易造成心智分裂与规范例外 |
| **首期不启用数据库加密，仅做前端脱敏** | 违背 `contracts.md` 持久化加密承诺，无法在首期验证底座 `@FieldEncrypt` 与 `BatchUtil.insertBatch` / 慢 SQL 脱敏的端到端实战闭环 |

---

## 4. 后果

- **正面收益**：在数据库落盘层真正保护个人与组织联系信息，零补丁复用底座 `DeterministicEncryptTypeHandler`、`BatchUtil.insertBatch` 与慢 SQL 参数脱敏，同时保留同租户唯一索引和 500 行批量 `IN` 查重性能。
- **代价与约束**：放弃手机号局部模糊查询（如按号段 `138` 模糊检索）；运维需像管理数据库密码一样妥善备份 `SOLID_CRYPTO_SECRET_KEY`，未来若需轮换密钥必须走离线双读重加密迁移脚本。
