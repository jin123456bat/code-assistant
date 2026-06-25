# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 🌐 语言声明

**本项目所有输出必须使用简体中文**，包括但不限于：代码注释、文档、Git commit message、PR 描述、Issue 回复、对话回复。禁止输出英文内容。

## 项目概述

面向 IntelliJ IDEA 及所有 JetBrains IDE 的免费 AI 编程助手插件（IntelliJ Platform Plugin, type `IC`
）。通过 DeepSeek API 提供 Agent 对话、代码补全和 Git commit message 自动生成。用户自带 DeepSeek API
Key。

## 常用命令

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox IntelliJ IDEA（autoReloadPlugins=true：改代码后重新编译即热加载，无需重启）
./gradlew test             # 运行全部 JUnit 测试
```

环境：JVM 21、Kotlin 2.0.21、IntelliJ Platform 2024.3（IntelliJ IDEA Community）、Gradle IntelliJ Platform
Plugin 2.2.1。

## 架构（概览）

完整设计见 [`DESIGN.md`](DESIGN.md)，技术契约见 [`docs/tech-spec.md`](docs/tech-spec.md)。

```
┌──────────────────────────────────────────────────────┐
│  UI Layer (ui/ 包)                                    │
│  TabBar → CardLayout 切换 7 个页面                      │
│  ├─ ui/page/: WelcomePage, ChatPage, SessionsPage,   │
│  │            TokenUsagePage, McpPage, SkillsPage,   │
│  │            SettingsPage                           │
│  └─ ui/chat/: ChatBubbleRenderer, ChatViewModel,     │
│              ChatInputArea, ToolCallCard, PlanCard    │
├──────────────────────────────────────────────────────┤
│  Agent Layer (agent/ 包)                              │
│  AgentLoop → stream → parse → executeTool → feedback │
│  AgentSession (状态机) + PlanExecutor (计划执行)       │
│  ToolRegistry + ToolExecutor (工具注册/分发)           │
│  MultiAgentManager (多 Agent 调度)                     │
├──────────────────────────────────────────────────────┤
│  Session Layer (session/ 包)                          │
│  SessionManager + SessionStore (JSON 持久化)           │
├──────────────────────────────────────────────────────┤
│  Skills & MCP (skills/ + mcp/ 包)                     │
│  SkillManager (SKILL.md 扫描/注册)                    │
│  McpManager (MCP Server 生命周期)                      │
├──────────────────────────────────────────────────────┤
│  Provider: AnthropicOkHttpClient → DeepSeek           │
│  Completion: CompletionProvider → DeepSeekFimClient   │
└──────────────────────────────────────────────────────┘
```

### 各层关键组件

| 层          | 核心文件                                                                                                              | 要点                                                                                                |
|------------|-------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| Agent      | `AgentLoop`, `AgentSession`, `ToolRegistry`, `ToolExecutor`, `ToolModels`, `PlanExecutor`, `MultiAgentManager`    | 8 个工具（readFile/writeFile/editFile/runShell/listFiles/searchContent/readLints/spawnAgent），while 循环 |
| Completion | `CompletionProvider`, `DeepSeekFimClient`, `CompletionContextCollector`, `CompletionCache`, `CompletionStats`     | FIM 补全 + PSI 增强上下文                                                                                |
| Session    | `SessionManager`, `SessionStore`                                                                                  | JSON 持久化：.tmp → ATOMIC_MOVE + FileLock                                                            |
| Skills     | `SkillManager`                                                                                                    | SKILL.md 扫描/解析/交叉验证工具声明                                                                           |
| MCP        | `McpManager`                                                                                                      | MCP Server 生命周期（启动/握手/心跳/崩溃重启）                                                                    |
| UI         | `AppColors`, `ChatToolWindow`, `TabBar`, `MessageBus`, `SelectionListener`, `OpenChatAction` + 7 page + 5 chat 组件 | 统一颜色令牌，亮/暗主题                                                                                      |

## Skill routing

当用户请求匹配可用 Skill 时，通过 Skill 工具调用。不确定时也调用。

关键路由规则（以下 Skills 需预先安装到 `.code-assistant/skills/` 目录，未安装时 Agent 应直接处理请求）：

| 场景                  | 路由                                             |
|---------------------|------------------------------------------------|
| 产品想法/头脑风暴           | `/office-hours`                                |
| 战略/范围               | `/plan-ceo-review`                             |
| 架构                  | `/plan-eng-review`                             |
| 设计系统/方案审查           | `/design-consultation` 或 `/plan-design-review` |
| 完整审查流水线             | `/autoplan`                                    |
| Bug/错误              | `/investigate`                                 |
| QA/测试               | `/qa` 或 `/qa-only`                             |
| 代码审查/diff 检查        | `/review`                                      |
| 视觉打磨                | `/design-review`                               |
| 发布/部署/PR            | `/ship` 或 `/land-and-deploy`                   |
| 保存进度                | `/context-save`                                |
| 恢复上下文               | `/context-restore`                             |
| 编写 backlog-ready 规格 | `/spec`                                        |

> 以上 Skills 来自 GStack 生态，需按需安装。未安装的 Skill 调用将静默失败，Agent 应回退到直接处理。

## DeepSeek Tool Calls Strict 模式

**当前状态：SDK 层面已开启 strict。**

Agent 通过 Anthropic Java SDK 的 Beta API 构建请求，`toolFromClass()` 默认设置 `strict: true` +
`additionalProperties: false` + 全部属性 `required`。当前使用 Anthropic
兼容端点 `https://api.deepseek.com/anthropic`。

**已知问题：** DeepSeek 原生 `/beta` 端点存在
bug（[#1069](https://github.com/deepseek-ai/DeepSeek-V3/issues/1069)），`strict: true` +
`additionalProperties: false` 同时使用会导致 JSON 格式错误。当前 `/anthropic` 端点不受影响。如需切换到原生
API，需等待 bug 修复。

详细 Schema 生成流程见 [`docs/tech-spec.md`](docs/tech-spec.md)。

**参考文档：**

- [DeepSeek Tool Calls 文档](https://api-docs.deepseek.com/zh-cn/guides/tool_calls)
- [DeepSeek Anthropic API 兼容文档](https://api-docs.deepseek.com/zh-cn/guides/anthropic_api)
