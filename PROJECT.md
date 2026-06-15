# Code Assistant - 项目文档

## 项目概述

Code Assistant 是 IntelliJ IDEA 的开源 AI 编程 Agent 插件（IntelliJ Platform plugin，type `IC`，兼容所有 JetBrains IDE）。基于 Kotlin + Swing，通过 DeepSeek 的 **Anthropic 兼容 Messages API**（`/anthropic/v1/messages`）驱动一个可自主调用工具的 Agent 循环。用户自带 DeepSeek API Key。

**核心能力：**

- **Agent 模式**：AI 自主规划并执行多步骤任务，使用内置工具（搜索代码、读写文件、执行命令、Git 操作等）
- **流式聊天**：Markdown 渲染 + 语法高亮，实时流式输出
- **工具系统**：14 个内置工具 + MCP（Model Context Protocol）扩展 + 统一 Skill 元工具（对齐 Claude Code）
- **计划模式**：LLM 自主决定是否创建执行计划，并跟踪步骤进度
- **安全机制**：工具审批流、白名单、文件越界防护
- **输入增强**：文件引用、编辑器选区自动引用、图片粘贴、斜杠命令

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| 运行时 | JVM 17 |
| 平台 | IntelliJ Platform 2023.3 (IntelliJ IDEA Community) |
| 构建 | Gradle (IntelliJ Plugin 1.17.4) |
| UI 框架 | Swing (JBUI, JTextPane, BoxLayout) |
| AI API | DeepSeek Anthropic 兼容 Messages API (SSE 流式) |
| Markdown 渲染 | org.jetbrains:markdown + JTextPane HTML |
| MCP 协议 | JSON-RPC 2.0 over stdio |
| 安全存储 | IntelliJ PasswordSafe (CredentialStore) |

## 项目结构

```
src/main/kotlin/com/aiassistant/
├── ChatToolWindow.kt          # Swing UI 主窗口 (工厂 + 窗口, ~1590 行)
├── ChatViewModel.kt           # UI 桥接 ViewModel (Activity 状态机)
├── AppSettingsService.kt      # 应用级配置: API Key, MCP Config, 白名单, 模型选择
├── AnthropicAdapter.kt        # Anthropic Messages 类型定义 (AnthropicMessage/ParsedEvent，仅测试用)
├── AnthropicSdkClient.kt      # Anthropic Java SDK 封装层 (streaming + 类型安全)
├── MarkdownRenderer.kt        # Markdown → Swing JPanel 渲染器
├── AiAssistantBundle.kt       # i18n 资源包读取器
├── AppLogger.kt               # 插件日志（com.intellij.diagnostic.Logger 封装）
│
├── agent/                     # Agent 核心实现 + 共享类型
│   ├── AgentLoop.kt           # Agent 主循环 (while 循环 + 工具调用分发)
│   ├── AgentContext.kt        # 共享上下文 (Plan/Step/ImageData/AgentMessage，含 id/version 用于增量渲染)
│   ├── ToolRegistryV3.kt      # 统一工具注册中心 (内置/MCP)
│   ├── SkillEngine.kt         # Skill 加载引擎（SkillDef → ctx.skillDefs，统一 Skill 元工具激活）
│   └── AgentTool.kt           # 工具接口定义 (AgentTool/ToolParameter/ToolResult)
│
├── tools/                     # 14 个内置工具实现
│   ├── ReadFileTool.kt        # 读取文件（项目内免审，项目外触发审批）
│   ├── WriteFileTool.kt       # 写入文件 (含越界防护)
│   ├── SearchCodeTool.kt      # 搜索代码
│   ├── ListDirectoryTool.kt   # 列出目录
│   ├── ExecuteCommandTool.kt  # 执行命令
│   ├── GitTool.kt             # Git diff / Git log / Git status（三个工具合并在一个文件）
│   ├── AskUserTool.kt         # 用户交互
│   ├── WebSearchTool.kt       # 网络搜索
│   ├── WebFetchTool.kt        # 获取网页（支持断线重连、编码检测）
│   ├── NotebookEditTool.kt    # Jupyter Notebook 编辑
│   ├── TaskTool.kt            # 子任务
│   └── CodeIntelligenceTool.kt # PSI 代码智能（跳转定义/查找引用/类型信息等）
│
├── mcp/                       # MCP 支持
│   ├── McpManager.kt          # MCP 服务器生命周期管理
│   ├── McpClient.kt           # JSON-RPC stdio 客户端
│   └── McpServerConfig.kt     # 服务器配置解析
│
├── ui/                        # UI 组件 (~2600 行)
│   ├── ChatTheme.kt           # 设计 token 单一来源
│   ├── ChatBubble.kt          # 自测量聊天气泡
│   ├── BubbleFactory.kt       # 气泡工厂
│   ├── ToolRowFactory.kt      # 工具/思考折叠行工厂 + 审批选项（含 ApprovalActions/RefChip）
│   ├── SelectionCard.kt       # ask_user 选择卡 (单选/多选)
│   ├── PlanBar.kt             # 置顶执行计划条
│   ├── SimpleDiff.kt          # 行级 diff 计算
│   ├── AskUserBridge.kt       # ask_user 工具 ↔ UI 桥接
│   ├── MarkdownRenderer.kt     # Markdown → Swing JPanel（位于 com.aiassistant 根包）
│   └── WrapLayout.kt            # 可换行 FlowLayout 变体（preferredSize 基于容器宽度模拟换行）
│
├── actions/                   # IntelliJ Actions
│   ├── OpenChatAction.kt      # 打开聊天窗口
│   ├── SendToChatAction.kt    # 编辑器右键发送到聊天
│   ├── GenerateCommitAction.kt # AI 生成 commit message
│   └── OpenAiToolWindowOnStartup.kt # 启动时打开窗口
│
├── shared/                    # 共享工具
│   ├── JsonUtils.kt           # JSON 转义/反转义
│   └── PathUtils.kt           # 路径安全校验（canonical path 前缀比对）
│
└── SettingsConfigurable.kt     # 设置面板
```

## 核心架构

### 请求流程

```
ChatToolWindowFactory → ChatToolWindow (Swing UI, ~1590 行)
    → ChatViewModel  (UI 桥接，轻量 ViewModel，含 Activity 状态机)
        → AgentLoop (agent，Agent 主循环)
            → AnthropicSdkClient (Anthropic Java SDK 封装，HTTP/SSE)
            → ToolRegistryV3   (工具注册与分发)
```

**关键数据流**：
- `ChatViewModel.sendMessage()` 发送后立即设置 `streamingThinking="等待 AI 回复..."` 和 `activity=Activity.Thinking`，消除等待空白
- `resolveSkillInvocation()` 返回 `SkillResolution(displayContent, llmContent, skillName, preferredModel)`——将 skill prompt 注入 LLM 上下文的同时，保留用户可见文本用于气泡展示
- `AgentLoop.run(activatedSkill=...)` 接收客户端激活的 skill 名称，存入 `AgentContext.activatedSkill`
- `AgentLoop.callAnthropic()` 返回 `AnthropicResponse(textContent, thinking, thinkingSignature, toolCalls)`
- `AnthropicSdkClient.mergeConsecutiveSameRole()` 将连续同 role 的 `AnthropicMessage`（含 `thinking`/`thinkingSignature` 字段）合并为单个 `MessageParam`，确保 DeepSeek V4 thinking 回传要求

### Agent 循环

AgentLoop 是核心调度器，在后台 `Thread` 上运行 `while` 循环：

- **最大轮次**：`MAX_LOOPS=100`
- **连续失败上限**：`MAX_FAILURES=3`，达到后中止
- **流程**：每轮调用模型 → 若返回 `tool_use` 则执行工具并把结果回填到 `history` → 继续下一轮 → 若返回纯文本则结束
- **UI 更新**：所有回调（`onMessage`/`onStreaming`/`onToolExecute`...）通过 `invokeLater`/`invokeAndWait` 切回 EDT

### 工具系统

三类工具来源统一由 `ToolRegistryV3` 管理：

1. **内置工具**（14 个）：`search_code`、`read_file`、`write_file`、`list_directory`、`execute_command`、`git_diff`、`git_log`、`git_status`、`ask_user`、`web_search`、`web_fetch`、`notebook_edit`、`task`、`code_intelligence`
2. **MCP 工具**：通过 `registerMcp()` 注册，来自 MCP 服务器的工具发现
### 元工具（Meta-Tools）

AgentLoop 内置三个不由 ToolRegistryV3 管理的元工具，由 `buildSdkToolDefs()` 硬编码注入：

- **`Skill(skill, args)`**：统一 skill 激活入口（对齐 Claude Code）。激活后 prompt 注入 system prompt，支持模型路由

- **`create_plan`**：LLM 自主决定是否创建执行计划。`input_schema` 含嵌套 `items` 结构（`ToolParameter` 无法表达）
- **`update_plan_step`**：更新计划步骤状态（`in_progress` / `done` / `failed`）

### 安全模型

- **直接执行**：`SAFE_TOOLS`（只读工具）+ 用户白名单 `AppSettingsService.getToolWhitelist()` 内的工具无需确认
- **内联确认**：`write_file`、`execute_command` 等工具触发 `onConfirmTool` 回调，通过 `CountDownLatch` + `AtomicBoolean` 阻塞等待用户在 UI 上点击确认（最多等 10 分钟）
- **文件越界防护**：`write_file` 仅允许写入项目目录内的文件

### MCP（Model Context Protocol）

- `McpManager` 管理多个 MCP 服务器的生命周期
- 使用 Claude 的 MCP 配置：`~/.claude.json`（全局）+ `.mcp.json`（项目级），解析 `mcpServers.{name}.{type,command,args,env,url}`（支持连字符服务器名和 JSON 转义引号）
- `McpClient` 支持 **stdio** 和 **HTTP** 两种传输，使用 JSON-RPC 2.0 协议（`initialize` → `initialized` → `tools/list` → `tools/call`）。Client capabilities：`roots`+`sampling`
- **Prompts**：`prompts/list` 发现 → system prompt `## MCP Prompts` 段落 + `mcp_get_prompt` 工具按需渲染
- **Resources**：`resources/list` 发现 → system prompt `## MCP Resources` 段落 + `read_file` 支持 `resource://` URI 按需读取
- **Ping**：回复服务器心跳探测 `{}`，防止超时断连
- **Cancelled**：`stop()` 时向所有服务器发送 `notifications/cancelled`，释放服务器资源
- **自动健康检查**：每次 agent 对话启动时运行（`AgentLoop.run()` 后台线程），恢复崩溃服务器并重新发现工具，同时重试初始连接失败的配置（最多 3 次）
- **McpChangeListener**：服务器 SSE 推送变更时自动更新 `ToolRegistryV3`（工具）和 `AgentContext`（prompts/resources）。工具变更采用全量采集模式，防止单服务器更新时清除其他服务器工具
- **线程安全**：`mcpTools` 用 `ConcurrentHashMap`，`mcpPrompts`/`mcpResources` 用 `CopyOnWriteArrayList`，`clients`/`configs`/`restartCount` 用 `ConcurrentHashMap`。通知回调使用 `bgExecutor` 线程池。MCP 加载在后台线程执行避免阻塞 EDT
- 远程工具通过 `McpToolAdapter` 包装为 `AgentTool` 注入注册中心
- **参数类型保留**：`McpToolAdapter.rawArgsJson` 在 AgentLoop 执行前注入原始 JSON
- **SSE 推送**：stdio 后台线程持续读取 + HTTP SSE 流，自动处理 `notifications/{tools,prompts,resources}/list_changed`

### Skills（对齐 Claude Code）

**加载：** `SkillEngine` 扫描 `<project>/.claude/skills/**/SKILL.md` 和 `~/.claude/skills/**/SKILL.md`。解析为 `SkillDef`（`name`/`description`/`prompt`/`preferredModel`），存入 `AgentContext.skillDefs`。全深度遍历，跳过隐藏目录，同名 skill 项目级优先。

**SKILL.md 格式：** YAML front matter（`name`、`description`、`model`） + Markdown 正文。`model` 可选，声明后激活时自动切换模型。

**统一 Skill 元工具：** 对齐 Claude Code，skill **不**注册为独立 `AgentTool`。统一通过一个 `Skill` 元工具激活（`Skill(skill=名称, args=输入)`），由 `AgentLoop.buildSdkToolDefs()` 硬编码注入。

**两种激活路径：**

| 路径 | 触发方式 | 机制 |
|------|---------|------|
| 客户端拦截 | 用户输入 `/skill-name` | `resolveSkillInvocation()` 从 `ctx.skillDefs` 查找，prompt 注入 **system prompt** |
| LLM 调用 | 调用 `Skill(skill=名称, args=输入)` 工具 | `AgentLoop` 硬编码处理，同样注入 system prompt + 模型路由 |

**渐进披露：** skill 描述列表始终在 system prompt 的 `## Skills` 段落中。激活的 skill 从列表中排除，防止重复激活。已激活 skill 的完整 prompt 通过 `activatedSkillPrompt` 注入 system prompt 顶部。

**模型路由：** SKILL.md 的 `model:` 字段声明首选模型。激活时自动切换，`onModelRouted` 回调通知 UI。

## 开发指南

### 环境要求

- JDK 17
- Gradle 7.x+（通过 wrapper）
- Kotlin 1.9.22

### 常用命令

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox IntelliJ IDEA（改代码后重新编译即热加载）
./gradlew test             # 运行全部 JUnit 测试
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest"           # 单个测试类
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest.方法名"     # 单个测试方法
```

### 调试方式

- `runIde` 启动 sandbox，`autoReloadPlugins=true` 支持热加载
- 在 sandbox IDE 中安装插件后，右侧工具窗打开 "Code Assistant"
- 日志输出通过 `AppLogger` 查看

## 配置说明

### API Key

- 存储：IntelliJ PasswordSafe（CredentialStore），不落明文
- 配置路径：`Settings > Tools > Code Assistant > API Key`
- 注册地址：`platform.deepseek.com`

### 模型选择

- `deepseek-v4-flash`：默认，快速、工具调用
- `deepseek-v4-pro`：复杂编码、深度推理

### MCP 配置

- 读取 Claude Code 的 MCP 配置：`~/.claude.json`（`mcpServers` 字段）
- 项目级：`.mcp.json`（Claude Code 格式）
- 格式：
  ```json
  {
    "mcpServers": {
      "server-name": {
        "type": "stdio",
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
        "env": {"KEY": "VALUE"}
      }
    }
  }
  ```

### Skills

**目录结构：**
- 项目级：`<project>/.claude/skills/<skill-name>/SKILL.md`
- 全局：`~/.claude/skills/<skill-name>/SKILL.md`
- 支持嵌套子目录（如 `gstack/review/SKILL.md`）

**SKILL.md 格式：**
```yaml
---
name: skill-name          # 必填
description: 简短描述      # 必填
model: claude-sonnet-4-6  # 可选，声明后激活时自动切换模型
---
# Skill 指引正文（Markdown）
```

**使用方式（对齐 Claude Code）：**
- 输入 `/skill-name`，从斜杠菜单选中后填充到输入框，发送时 `resolveSkillInvocation()` 从 `ctx.skillDefs` 查找并注入 system prompt
- LLM 通过统一 `Skill(skill=名称, args=输入)` 元工具动态激活，`AgentLoop` 硬编码处理
- 两种路径均将 prompt 注入 system prompt（非消息历史），支持 `preferredModel` 模型路由
- `ChatViewModel.getSkills()` 从 `ctx.skillDefs` 读取，供 UI 斜杠菜单展示

### 工具白名单

- 用户可通过审批选择卡的"始终允许"按钮将工具加入白名单
- 存储：`PropertiesComponent`，key 为 `AI_Coding_Assistant.TOOL_WHITELIST`
- 白名单内工具直接执行，不弹出确认卡

## 关键约定与坑

- **DeepSeek V4 thinking 回传**：启用 thinking 模式时，assistant 回复中的 `thinking` content block（含 `signature`）必须随后续请求传回 API。`AnthropicMessage` 新增 `thinking` 和 `thinkingSignature` 字段存储。`mergeConsecutiveSameRole()` 负责将 thinking + text + tool_use 合并为单条消息，`buildSdkMessage()` 构建含 `ThinkingBlockParam` 的完整 content blocks。
- **流式气泡清理**：`streamingBubble` 必须存 row（container 的直接子组件），不能存 ChatBubble（row 的子组件），否则 `container.remove(streamingBubble)` 找不到直接子组件导致流式气泡永远删不掉。
- **错误恢复**：`ChatViewModel.onError` 回调中清理全部流式状态（`isStreaming=false`、`streamingContent=""`、`streamingThinking=""`、`isThinking=false`、`activity=Idle`）并触发 `onMessagesChanged` 强制 UI 重建，移除残留流式组件。`onStateChange(false)` 在停止时同样清理 `streamingContent`。
- **发送后等待指示**：`sendMessage()` 中设置 `streamingThinking="等待 AI 回复..."` + `activity=Activity.Thinking`，立即显示等待指示器，收到首个 streaming 事件后自动替换。
- **增量渲染**：消息通过 `id` + `version` 追踪变更，`renderedMsgVersions` 和 `msgIdToComponent` 配合实现增量更新。消息减少时通过 `renderedMsgVersions.size > displayMessages.size` 自动触发全量重建。
- **Anthropic Java SDK**：HTTP/SSE 层使用官方 `com.anthropic:anthropic-java:2.40.1`，替代手写 `SseClient`。
- **工具参数解析使用 Gson**：`AgentLoop.parseParams()` 使用 `Gson.fromJson(json, Map::class.java)` 完整解析 JSON，嵌套对象/数组序列化为 JSON 字符串
- **i18n**：用户可见文案走 `messages/AiAssistantBundle*.properties`，通过 `AiAssistantBundle` 读取
