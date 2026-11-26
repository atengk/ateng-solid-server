# ateng-solid-bootstrap

单体聚合启动装配与外部环境隔离上下文，严禁承载具体业务 Service、持久层 Mapper 或各领域 Flyway 脚本。

## Language

**单体聚合宿主 (Monolith Assembly Host)**:
将 `common`、`system-api`、`system`、`auth` 装配为单一可运行服务的启动边界，与未来同仓 `cloud-*` 微服务启动壳并存而不修改业务核心。
_Avoid_: 在启动模块编写业务 Controller、Service 或 Mapper

**五档环境分层配置 (Five-Profile Layered Configuration)**:
依托全局基线（`application`）、开发调试（`dev`，按需导入本机 `local`）、离线单测（`test`，H2 内存库隔离）、生产防呆（`prod`，强凭据零默认值 Fail-Fast）与本地模板（`local.example`）构成的确定性配置体系，阻断开发配置向生产与测试链路渗透。
_Avoid_: 在全局基线直接导入本地文件导致测试或生产受本机残留覆盖，或在入库模板留存默认真实凭据

**虚线程有界池化 (Virtual-Thread Bounded Pooling)**:
在开启 Java 21 虚拟线程的执行模型下，数据库连接池（HikariCP）与 Redis 客户端连接池（Lettuce / Redisson）按运行环境分层显式限容并开启心跳与泄漏探测，高 CPU 运算任务则隔离于专用有界平台线程池，防止虚拟线程高并发反向压垮共享中间件或钉死载体线程。
_Avoid_: 为虚拟线程无上限放大连接池、缺省引入 `commons-pool2` 却误以为 Lettuce 池化已生效

**架构红线守卫 (Architecture Guard)**:
在测试期自动化验证模块隔离边界、零 Entity 泄漏、零 `Result<T>` 契约污染、强类型配置与强制构造器注入的静态规则集。
_Avoid_: 仅靠口头约定约束跨模块依赖
