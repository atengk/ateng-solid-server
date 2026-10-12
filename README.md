# ateng-solid-server

**ateng-solid-server** 是 **ateng-solid** 全端多端数字化系统的后端核心服务工程。

---

## 1. 工程定位

- **架构形态**：Spring Boot 3 模块化单体架构（Modular Monolith）。
- **职责范围**：内聚持久化数据访问、业务逻辑处理、安全鉴权体系与 API 接口契约输出，为管理后台 Web 端、移动端等各终端提供统一后端支撑。

---

## 2. 运行环境要求

- **JDK**：OpenJDK 21 LTS 及以上
- **构建工具**：Apache Maven 3.9+
- **数据库/缓存**：MySQL 8.x / Redis 7.x
