## 变更说明
<!-- 请简要说明此 PR 解决的问题、涉及的模块与业务意图 -->

## 关联 Issue
- 修复/关联: close #

## 变更类型
- [ ] `feat`: 新增功能
- [ ] `fix`: 缺陷修复
- [ ] `docs`: 文档变动
- [ ] `style`: 格式调整（不影响业务逻辑）
- [ ] `refactor`: 代码重构
- [ ] `perf`: 性能优化
- [ ] `test`: 补充或重构测试
- [ ] `build`: Maven 依赖或构建配置变动
- [ ] `ci`: CI/CD 流水线变动
- [ ] `chore`: 常规维护
- [ ] `revert`: 回滚先前提交

## 后端与 Cloud-Readiness 自检清单
- [ ] 提交信息与 PR 标题符合 Conventional Commits 规范
- [ ] 遵守 5 模块隔离：未在 `auth` 直接依赖 `system` 实现类与 Mapper，无跨模块 SQL JOIN
- [ ] Controller 显式返回 `Result<T>`，100% 隔离持久化 Entity（零透传）
- [ ] 业务配置统一使用 `@ConfigurationProperties(prefix = "ateng.solid.*")`，禁用 `@RefreshScope`
- [ ] 本地 H2 离线单测 (`mvn clean test -B -T 1C -nsu`) 已全部通过
- [ ] 若涉及对外接口或表结构变更，已同步更新文档与 Flyway 脚本
