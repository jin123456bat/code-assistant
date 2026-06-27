# Code Assistant

面向 IntelliJ IDEA 及所有 JetBrains IDE 的**免费 AI 编程助手插件**（IntelliJ Platform Plugin, type
`IC`）。

通过 DeepSeek API 提供三大核心功能：**Agent 对话**、**代码自动补全**、**Git Commit Message 自动生成**
。用户自带 DeepSeek API Key。

## 功能概览

| 功能                 | 说明                                                         | 文档                                           |
|--------------------|------------------------------------------------------------|----------------------------------------------|
| 💬 **Agent 对话**    | AI 编程代理：工具调用、Plan Mode、多 Agent、Skill 系统、MCP 外部工具           | [`docs/agent.md`](docs/agent.md)             |
| ⌨️ **代码自动补全**      | FIM（Fill-in-the-Middle）代码补全，PSI 上下文增强，缓存加速                 | [`docs/completion.md`](docs/completion.md)   |
| 📝 **Git Message** | 基于 `git diff` 自动生成 Conventional Commits 规范的 commit message | [`docs/git-message.md`](docs/git-message.md) |

### Agent 对话核心能力

- 9 个内置工具（读文件/写文件/编辑文件/执行 Shell/列目录/搜索内容/读诊断/调用 Skill/派生子 Agent）和 5
  个计划管理工具（创建计划/查看计划项/删除计划项/重排计划项/标记完成）
- Plan Mode：`/plan` 命令生成执行计划，自动连续执行，用户可随时暂停干预
- **LLM 自动规划（规划中）**：System Prompt 复杂度预判 + 轮次预警 + `createPlan` 工具让 LLM 主动拆分超长任务
- MCP 支持：连接外部工具服务（数据库、文件系统、API）
- Skill 系统：兼容 Claude Code SKILL.md 格式，自定义 Agent 能力
- 多 Agent 协作：父 Agent 可 spawn 子 Agent 并行处理子任务
- 上下文自动压缩（Auto-Compact）：消息超限时自动压缩旧消息为摘要
- 思考过程展示、图片粘贴、@file 引用、流式 Markdown 渲染

## 架构概览

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
│  Completion Layer (completion/ 包)                    │
│  AiCompletionProvider → context collection → FIM API │
│  CompletionCache + ContextEnhancer + PostProcessor    │
├──────────────────────────────────────────────────────┤
│  Session Layer (session/ 包)                          │
│  SessionManager + SessionStore (JSON 持久化)           │
├──────────────────────────────────────────────────────┤
│  Skills & MCP (skills/ + mcp/ 包)                     │
│  SkillManager (SKILL.md 扫描/注册)                    │
│  McpManager (MCP Server 生命周期)                      │
├──────────────────────────────────────────────────────┤
│  Actions: GenerateCommitAction (Commit 消息生成)       │
│  Provider: AnthropicOkHttpClient → DeepSeek           │
│  Completion: AiCompletionProvider → DeepSeekFimClient │
└──────────────────────────────────────────────────────┘
```

## 快速开始

1. 构建插件：`./gradlew buildPlugin`（产物在 `build/distributions/`）
2. 在 IntelliJ IDEA 中：Settings → Plugins → ⚙ → "Install Plugin from Disk" → 选择构建好的 zip
3. 在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 获取 API Key
4. 打开 Code Assistant 面板（`Ctrl+Shift+K` / `Cmd+Shift+K`）
5. 粘贴 API Key 即可开始使用

**系统要求：** IntelliJ IDEA 2024.3+（Community 或 Ultimate），macOS / Windows / Linux，JVM 21+

## 构建

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox IntelliJ IDEA
./gradlew test             # 运行全部 JUnit 测试
```

> **热加载提示：** 若需要热加载（改代码后重新编译即生效），请在 `build.gradle.kts` 中配置
`autoReloadPlugins=true`。

**环境：** JVM 21、Kotlin 2.0.21、IntelliJ Platform 2024.3（IntelliJ IDEA Community）、Gradle IntelliJ
Platform Plugin 2.2.1

## 快捷键

| 快捷键（Windows/Linux） | 快捷键（macOS）    | 操作             |
|--------------------|---------------|----------------|
| `Ctrl+Shift+K`     | `Cmd+Shift+K` | 打开/关闭 Agent 面板 |
| `Alt+P`            | `Cmd+P`       | 手动触发代码补全       |
| `↑` / `↓`          | `↑` / `↓`     | 补全候选切换         |
| `Enter`            | `Enter`       | 发送消息           |
| `Shift+Enter`      | `Shift+Enter` | 输入框换行          |
| `Escape`           | `Escape`      | 关闭 Popup（详见 [agent.md](docs/agent.md#快捷键)） |
| `Ctrl+Shift+N`     | `Cmd+Shift+N` | 新建会话           |
| `↑`（空输入框）          | `↑`（空输入框）     | 填充上一条消息        |

## 文档索引

| 文档                                                       | 说明                                   |
|----------------------------------------------------------|--------------------------------------|
| [`docs/agent.md`](docs/agent.md)                         | Agent 智能对话 — 模块总览，引用所有 Agent 功能子文档   |
| [`docs/ui.md`](docs/ui.md)                               | UI/UX 设计 — 模块总览，引用所有 UI 功能子文档        |
| [`docs/completion.md`](docs/completion.md)               | 代码自动补全 — FIM 流程、PSI 上下文增强、缓存、后处理     |
| [`docs/git-message.md`](docs/git-message.md)             | Git Message — diff 构建、Prompt 模板、流式生成 |
| [`docs/agent/correctness.md`](docs/agent/correctness.md) | Agent 正确性验证体系 — 防幻觉、代码验证、方案验证        |

## 配置项

所有配置统一在 **IDE Settings > Tools > Code Assistant** 中管理：

| 配置项           | 默认值               | 说明                  |
|---------------|-------------------|---------------------|
| API Key       | —                 | 从 PasswordSafe 安全存储 |
| Model         | `deepseek-v4-pro` | 固定 |
| 代码补全          | 启用                | 开关                  |
| 补全 max_tokens | 256               | 范围 1-1024           |
| Commit Prompt | 默认模板              | 自定义模板，`{diff}` 占位   |
| Agent 最大轮次    | 20（0=不限）          | 达到上限后自动终止           |
| 多 Agent 并发上限  | 3                 | 父 + 子 Agent 总计      |

## 开发状态

🚧 **Alpha 阶段（v2.0.0）** — 核心功能已实现，正在持续完善中。尚未发布到 JetBrains Marketplace，需从源码构建。

### 已知限制

- 仅支持 DeepSeek API，不支持其他 LLM 提供商
- MCP `resources/list` 和 `prompts/list` 暂不支持
- 多 Agent 嵌套上限 1 层（子 Agent 不可再 spawn）
- Sessions 全文搜索暂未实现，当前仅支持按标题过滤
- `Grep` 支持正则表达式匹配，不区分大小写。非法正则自动回退字面子串
- `@file` glob 匹配上限 50 个文件，超出部分由 Glob 工具告知 LLM 截断情况，LLM 自行决定是否翻页

## 反馈

- 🐛 [GitHub Issues](https://github.com/jin123456bat/code-assistant/issues) — 报告 bug 或提功能建议
- 💬 [GitHub Discussions](https://github.com/jin123456bat/code-assistant/discussions) — 使用问题、想法交流

## 许可证

[Apache License 2.0](LICENSE)
