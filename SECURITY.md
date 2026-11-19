# 安全策略与漏洞披露指南 (Security Policy)

本项目十分重视代码与系统安全。如果你在 `ateng-solid-server` 中发现了疑似安全漏洞或隐患，请**切勿在公开的 GitHub Issue 或讨论区直接披露**，以防在漏洞修复前造成安全风险。

---

## 1. 支持的版本 (Supported Versions)

| 版本系列 | 是否支持安全补丁 | 说明 |
| :--- | :--- | :--- |
| `main` / 最新 Release | :white_check_mark: 支持 | 优先接收安全补丁与热修复 |
| 历史旧版本 | :x: 不再支持 | 建议尽快升级至最新版本 |

---

## 2. 漏洞报告渠道 (Reporting a Vulnerability)

### 方式一：GitHub 私密安全报告 (推荐首选)
1. 前往本仓库的 **[Security -> Advisories](https://github.com/atengk/ateng-solid-server/security/advisories/new)** 页面；
2. 点击 **「Report a vulnerability」** 提交报告（仅仓库维护者可见）。

### 方式二：安全联络邮箱
- **安全联系邮箱**：`security@example.com`（*请在邮件主题中注明 `[SECURITY VULNERABILITY - ATENG-SOLID-SERVER]`*）

---

## 3. 漏洞响应流程

- **48 小时内**确认收到报告并启动研判；
- 私有分支修复与回归测试通过后发布补丁版本并致谢发现者。
