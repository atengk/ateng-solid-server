# 工程配置基线与 ArchUnit 架构守卫规格 (configuration-and-archunit-spec.md)

> **适用范围**：`ateng-solid-server` Maven 依赖管理、多环境 YAML 配置规范及测试期架构红线校验。

---

## 1. Maven POM 依赖边界与版本基线

- **无父 POM 锁死**：根 `pom.xml` 不继承外部 `spring-boot-starter-parent`，通过 `<dependencyManagement>` 顺序导入：
  1. `io.github.atengk:ateng-boot-bom`（**已锁定正式版 `1.2.0`，含 `#1076~#1080`、`#1093`、`#1094`、`#1134~#1137`、`#1163~#1167` 与 `ateng-boot-flyway`**）；
  2. `org.springframework.boot:spring-boot-dependencies:3.3.13`。
  > **并存期 BOM 顺序铁律**：未来在同仓新增 `ateng-solid-cloud/*` 启动壳引入 `ateng-cloud-bom` 时，**必须保证 `ateng-boot-bom` 声明在 `ateng-cloud-bom` 之前**，防止传递 BOM 覆写高版本 `ateng-boot`。
- **5 模块编译期依赖锁死矩阵**：

| 模块 | 允许依赖的内部模块 | 允许依赖的外部/底座 Starter | 绝对禁令 |
| :--- | :--- | :--- | :--- |
| `ateng-solid-common` | 无 | `ateng-boot-core`、`jakarta.validation-api`、`lombok` | 禁 Web / MyBatis-Plus / Redis / Cloud |
| `ateng-solid-system-api` | `common` | `jakarta.validation-api`、`lombok` | 禁 Web / MyBatis-Plus / Redis / Feign / Dubbo / Entity |
| `ateng-solid-system` | `common`、`system-api` | `ateng-boot-{web,mybatis-plus,redis,satoken,excel,storage}` | 禁 `ateng-boot-all`、`ateng-cloud-*`、禁反向依赖 `auth` |
| `ateng-solid-auth` | `common`、`system-api` | `ateng-boot-{web,redis,satoken}` | 禁依赖 `ateng-solid-system` 与任何持久层依赖 |
| `ateng-solid-bootstrap` | `auth`、`system` | `ateng-boot-flyway`、MySQL/H2、Test/ArchUnit | 禁编写业务 Controller / Service / Mapper，**首期不引入 `ateng-boot-observability`** |

### 扩展模块可插拔装配约定 (`ateng-solid-ext-*` / `ateng-solid-biz-*`)
- **只向内依赖**：扩展模块仅允许依赖 `ateng-solid-common`、`ateng-solid-system-api`（及自身业务所需的 `ateng-boot-*` Starter），**严禁依赖或篡改 `ateng-solid-system` / `ateng-solid-auth` 实现模块**；
- **AutoConfiguration 插拔**：通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` + `@ConditionalOnProperty` 自注册 Bean，增减 `bootstrap` POM 依赖即可无侵入完成模块启停；
- **白名单与枚举汇聚**：扩展模块通过实现 `SaTokenWhitelistProvider` 与 `@IBaseEnum` 自动接入安全防线和枚举端点，无需修改内核 YAML 配置。

---

## 2. 多环境双模配置文件约定 (`ateng-solid-bootstrap`)

| 文件 | 是否入库 | 用途与关键基线配置 |
| :--- | :---: | :--- |
| `application.yml` | 是 | 全局公共基线：虚拟线程 `spring.threads.virtual.enabled=true`、Jackson 时区与长整型转字符串、MyBatis-Plus 多租户忽略表、Sa-Token `primary-login-type=admin`、枚举端点 `endpoint-enabled=false`（交由上层接管）、`spring.config.import: optional:classpath:application-local.yml` |
| `application-dev.yml` | 是 | 本地开发 Profile：默认日志级别与调试友好参数，**严禁含真实库密码** |
| `application-test.yml` | 是 | 离线测试 Profile：H2 `MODE=MySQL` 内存库、Flyway 兼容开关、关闭强依赖外部 Redis，保障 `mvn test` 100% 离线自洽 |
| `application-local.example.yml` | 是 | 脱敏占位符模板（`YOUR_DB_HOST` / `YOUR_DB_PASSWORD` / `YOUR_REDIS_PASSWORD`） |
| `application-local.yml` | **否（`.gitignore`）** | 开发者本地覆盖文件，存放真实云端 MySQL / Redis 连接凭据 |

### 业务扩展配置收口 (`ateng.solid.*`)
- 统一使用 `@ConfigurationProperties(prefix = "ateng.solid.*")` 强类型绑定（时间用 `Duration`，容量用 `DataSize`）；
- **严禁**在 `@Service` / `@Component` / `@RestController` 上使用 `@RefreshScope` 或零散 `@Value`。

---

## 3. ArchUnit 自动化架构红线 (`ArchitectureGuardTest`)

所有规则在 `ateng-solid-bootstrap` 的离线单元测试套件中强制执行（CI 必过）：

1. **模块物理边界守卫**：
   - `..solid.auth..` 包中的任何类 **严禁** 访问 `..solid.system..`（仅允许访问 `..solid.system.api..` 与 `..solid.common..`）；
   - `..solid.system.api..` 包中的任何类 **严禁** 访问 Spring Web (`org.springframework.web..`)、MyBatis-Plus (`com.baomidou..`)、OpenFeign (`org.springframework.cloud.openfeign..`) 或 Dubbo (`org.apache.dubbo..`)。
2. **现代 Java 21 依赖注入守卫**：
   - 全仓库业务类 **严禁** 在字段上使用 `@Autowired` 或 `@Resource`（强制构造器注入 / `@RequiredArgsConstructor`）。
3. **Controller 零 Entity 泄漏守卫**：
   - 标注 `@RestController` / `@Controller` 的所有公开方法参数与返回值（含泛型参数）**严禁**出现 `..entity..` 包中的持久化实体类。
4. **启动模块纯净守卫**：
   - `ateng-solid-bootstrap` 根装配包不承载业务 `*Mapper` 或业务 `@RestController`。
5. **同源编码红线守卫（配套 [`.agents/rules/coding-and-architecture.md`](../../.agents/rules/coding-and-architecture.md)）**：
   - 随代码推进增量补充：Service 不得直接访问 `jakarta.servlet.http.HttpServletRequest`/`HttpServletResponse`、Mapper 层不得反向依赖 Controller/Service。
