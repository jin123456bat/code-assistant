# Code Assistant

面向 IntelliJ IDEA 及所有 JetBrains IDE 的**免费 AI 编程助手插件**。

通过 DeepSeek API 提供 Agent 对话、代码补全和 Git commit message 自动生成。用户自带 DeepSeek API Key。

## 功能

- **💬 Agent 对话** — 统一聊天模式，支持工具调用（读/写文件、Shell、搜索等）
- **📋 Plan Mode** — `/plan` 命令生成可审查的执行计划，逐步确认后执行
- **⌨️ 代码补全** — FIM（Fill-in-the-Middle）代码补全
- **📝 Commit 生成** — 基于 VCS diff 自动生成 Git commit message
- **🔌 MCP 支持** — 连接外部工具服务（数据库、文件系统、API）
- **🎯 Skill 系统** — 兼容 Claude Code SKILL.md 格式，自定义 Agent 能力
- **🖼️ 图片粘贴** — 支持剪贴板图片直接粘贴到对话中
- **📎 @file 引用** — @ 文件名即可将文件内容注入上下文
- **🤖 多 Agent 协作** — 父 Agent 可 spawn 子 Agent 并行处理子任务
- **💭 思考过程** — 展示 Agent 推理过程，折叠/展开

## 开发状态

🚧 **Alpha 阶段（v2.0.0）** — 核心功能已实现，正在持续完善中。Agent Mode、Plan Mode、MCP、Skills
系统均可使用，但可能存在边缘情况。尚未发布到 JetBrains Marketplace，需从源码构建。

### 已知限制

- 仅支持 DeepSeek API（`deepseek-v4-pro` 模型），不支持其他 LLM 提供商
- MCP `resources/list` 和 `prompts/list` 暂不支持
- 多 Agent 嵌套上限 1 层（子 Agent 不可再 spawn）
- Sessions 全文搜索暂未实现（v1 仅 title 过滤）
- `searchContent` 当前仅支持单词边界匹配（v1 限制）
- `readLints` 仅支持单文件诊断
- `@file` glob 匹配上限 50 个文件

## 快速开始

1. 构建插件：`./gradlew buildPlugin`（产物在 `build/distributions/`）
2. 在 IntelliJ IDEA 中：Settings → Plugins → ⚙ → "Install Plugin from Disk" → 选择构建好的 zip
3. 在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 获取 API Key
4. 打开 Code Assistant 面板（`Ctrl+Shift+K` / `Cmd+Shift+K`）
5. 粘贴 API Key 即可开始使用

**系统要求：** IntelliJ IDEA 2024.3+（Community 或 Ultimate），macOS / Windows / Linux，JVM 21+

## 构建

```bash
# 构建插件 zip（产物在 build/distributions/）
./gradlew buildPlugin

# 启动 sandbox IntelliJ IDEA（改代码后重新编译即热加载）
./gradlew runIde

# 运行全部测试
./gradlew test
```

**环境要求：** JVM 21、Kotlin 2.0.21、IntelliJ Platform 2024.3

## 文档

| 文档                                               | 说明                        |
|--------------------------------------------------|---------------------------|
| [DESIGN.md](DESIGN.md)                           | Agent Mode 总体设计           |
| [docs/tech-spec.md](docs/tech-spec.md)           | 技术契约（接口、线程模型、JSON Schema） |
| [docs/ui-ux-spec.md](docs/ui-ux-spec.md)         | UI/UX 设计规范                |
| [docs/ui-prototype.html](docs/ui-prototype.html) | 可交互 UI 原型                 |

## 反馈

- 🐛 [GitHub Issues](https://github.com/jin123456bat/code-assistant/issues) — 报告 bug 或提功能建议
- 💬 [GitHub Discussions](https://github.com/jin123456bat/code-assistant/discussions) — 使用问题、想法交流

## 许可证

[Apache License 2.0](LICENSE)
