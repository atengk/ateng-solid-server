# ateng-solid-system

系统管理核心领域上下文（接口前缀 `/admin/system/**`），承载多租户、租户套餐、组织架构、RBAC 权限、字典参数与系统审计。

## Language

**租户 (Tenant)**:
SaaS 多租户隔离边界主体，所有租户隔离表通过 `tenant_id` 实现行级数据隔离。
_Avoid_: 机构、客户（在鉴权隔离语义下混用）

**租户套餐 (Tenant Package)**:
平台向租户授予的菜单集合边界，决定租户管理员可见和可分配的最大菜单权限上限。
_Avoid_: 租户角色、权限包

**菜单节点 (Menu Node)**:
统一承载目录（`M`）、页面菜单（`C`）与按钮权限（`F`）的层级权限节点。
_Avoid_: 路由与权限节点拆成多套孤立表

**部门祖级路径 (Dept Ancestors)**:
部门表记录的逗号分隔祖先部门 ID 链，用于服务端高效解析本部门及以下数据范围集合。
_Avoid_: 在 SQL 中使用 `FIND_IN_SET` 破坏索引与 H2 兼容

**静态枚举与动态字典 (Static Enum vs Dynamic Dict)**:
不可运营变更的代码强类型枚举由本模块通过注入 `EnumDictionaryRegistry` 自主接管 `/admin/system/enums` 并实施 `@SaCheckLogin` 权限管控，可由运营增删改的业务选项走动态数据字典表。
_Avoid_: 将强类型代码枚举重复录入数据库字典表，或依赖未鉴权的底座默认枚举暴露端点

**租户开关 (Tenant Toggle)**:
控制全系统是否启用 SaaS 行级多租户隔离的全局策略开关（默认关闭按单租户运行；关闭时仍保留租户数据列以支持零重构开启）。
_Avoid_: 靠删表或分支代码区分单租户与多租户版本

**平台专属菜单 (Platform-Only Menu)**:
仅允许平台默认租户可见与维护的系统治理菜单（覆盖租户、租户套餐、菜单管理与动态字典管理），禁止分配给任何租户套餐。
_Avoid_: 普通租户可见平台治理菜单

**导入批注令牌 (Import Feedback Token)**:
批量导入校验失败后由服务端生成的短时效一次性凭证，用于归属校验后下载带错误标红与批注的修正工作簿。
_Avoid_: 将错误工作簿直接作为公开匿名静态链接暴露

**操作审计与登录审计 (Operate & Login Audit)**:
记录管理端写操作变更与认证登录轨迹的只追加流水记录，不含软删除与乐观锁。
_Avoid_: 业务主表软删除基类套用在流水日志表

**超管启动引导器 (Super Admin Bootstrapper)**:
归属 `ateng-solid-system` 的安全初始化组件（单体 `bootstrap` 与未来微服务 `cloud-system` 同源共享），启动时检测平台默认租户是否存在超级管理员账号，按 `ateng.solid.init.*` 配置或一次性随机强口令动态落库。
_Avoid_: 在数据库迁移 SQL 中写死固定明文或默认弱密码哈希，或在 `system-api` 暴露仅用于引导的浅接口

**确定性字段加密 (Deterministic Field Encryption)**:
对用户与部门的联系手机号、电子邮箱采用同明文恒定生成同密文的持久层加密策略，以兼顾落盘脱敏保护、数据库联合唯一索引与精确等值检索（邮箱入库前强制小写归一化、空白字符串统一归一为 `NULL`）。
_Avoid_: 对加密检索列使用随机 IV、在加密列上发起 `LIKE` 模糊检索或排序、或将空串 `""` 写入联合唯一索引列

**配额悲观行锁 (Quota Pessimistic Guard)**:
多租户开启后针对 `sys_tenant` 目标行施加 `SELECT ... FOR UPDATE` 悲观锁并校核有效用户数与 `account_count`（`-1` 或 `<= 0` 代表不限），在并发创建、停用转启用及 500 行批量导入落库阶段彻底消除账号配额超卖窗口。
_Avoid_: 在并发导入或建号入口仅做无锁先查后插导致配额穿透

**跨租户菜单裁剪 (Cross-Tenant Package Pruning)**:
平台端缩减租户套餐菜单或为租户切换更窄套餐时，在保留半选态活跃祖先目录 `M` 的前提下原子清理关联租户 `sys_role_menu` 中的越界权限，并在事务提交后全局清退权限缓存。
_Avoid_: 粗暴删除半选祖先目录破坏既定角色授权树，或在事务回滚前提前清缓存

**列表脱敏与详情真明文 (List Masking vs Detail Plaintext)**:
高频列表与树节点视图（`UserVO`、`DeptTreeVO`）的联系手机号/邮箱由服务端在 Jackson 出界序列化阶段通过 `@FieldSensitive` 动态脱敏；编辑详情（`UserDetailVO`、`DeptVO`、`PersonalProfileDTO`）保留内存真明文以支持表单无污染编辑保存。
_Avoid_: 在分页或树形列表接口直接输出全量明文手机号/邮箱，或在 `GET /{id}` 编辑详情接口回传星号掩码导致表单回写脏数据
