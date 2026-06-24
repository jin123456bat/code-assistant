# Code Assistant

[![English](https://img.shields.io/badge/English-README-blue)](README_EN.md)

**免费 AI 编程助手插件，适用于 PhpStorm / IntelliJ IDEA 及所有 JetBrains IDE。**

基于 [DeepSeek](https://platform.deepseek.com) 大模型，提供智能代码补全和 Git Commit Message 自动生成。完全免费，自带 API Key 即可使用。

---

## 核心功能

- **多行代码补全** — 基于上下文的 FIM（Fill-in-the-Middle）智能补全，不只是单行
- **Git Commit Message 生成** — 一键分析 diff，自动生成规范的中文/英文 commit message
- **自带 Key** — 使用你自己的 DeepSeek API Key，按量付费，无订阅费

---

## 安装

### 本地构建安装

```bash
git clone https://github.com/jin123456bat/code-assistant.git
cd code-assistant
./gradlew buildPlugin
```

构建产物在 `build/distributions/` 目录，得到 `.zip` 文件后：
`Settings → Plugins → ⚙️ → Install Plugin from Disk...` 选择该 zip 文件。

---

## 获取 API Key

1. 访问 [platform.deepseek.com](https://platform.deepseek.com) 注册账号
2. 进入「API Keys」页面，点击「创建 API Key」
3. 复制生成的 Key（格式为 `sk-xxxxxxxx`）

> **费用说明**：DeepSeek 采用按量计费。`deepseek-v4-pro` 定价为 ¥2/百万 input tokens + ¥8/百万 output tokens。日常使用每次几分钱。

---

## 配置插件

打开 `Settings → Tools → Code Assistant`：

| 设置项 | 说明 | 默认值 |
|--------|------|--------|
| **API Key** | DeepSeek 密钥 | — |
| **Model** | 使用的模型 | `deepseek-v4-pro` |
| **代码补全** | 开启/关闭自动补全 | 开启 |
| **最大 Tokens** | 补全最大生成 tokens | 256 |

> API Key 使用系统密钥链（macOS Keychain / Windows Credential Manager）加密存储。

---

## 使用指南

### 代码补全

在编辑器中正常编写代码，插件会自动在光标处显示灰显补全建议。按 `Tab` 接受，`Esc` 忽略。

### Git Commit Message

打开 VCS Commit 对话框 → 点击 **Generate Commit Message** 按钮 → 等待 2-3 秒 → commit message 自动填充。

---

## 兼容性

兼容所有基于 IntelliJ Platform 的 IDE（2023.3 及以上版本）：

IntelliJ IDEA（Community / Ultimate）、PhpStorm、WebStorm、PyCharm、GoLand、RubyMine、CLion、Rider、DataGrip、Android Studio 等。

---

## 常见问题

### Q: 提示"网络连接失败"

1. 检查网络是否能访问 `https://api.deepseek.com`
2. 如果使用代理，在 IDE `Settings → HTTP Proxy` 中配置

### Q: API Key 安全吗？

API Key 使用系统密钥链加密存储，不写入任何配置文件。插件无遥测、无日志上传。

### Q: 如何减少 API 费用？

1. 补全使用小模型（如 `deepseek-v4-flash`）
2. 降低最大 tokens 值（默认 256）

---

## 隐私说明

- **API Key**：通过 IntelliJ PasswordSafe 加密存储
- **代码数据**：仅传输光标附近的代码上下文到 DeepSeek API
- **无数据收集**：不收集用户数据、不发送遥测

---

## 许可证

[Apache-2.0](LICENSE)
