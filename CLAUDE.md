# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

PhpStorm 的开源 AI 编程 Agent 插件（IntelliJ Platform plugin，type `PS`）。基于 Kotlin + Swing，通过 DeepSeek 的 **Anthropic 兼容 Messages API**（`/anthropic/v1/messages`）驱动一个可自主调用工具的 Agent 循环。用户自带 DeepSeek API Key。

## 常用命令

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox PhpStorm（autoReloadPlugins=true：改代码后重新编译即热加载，无需重启）
./gradlew test             # 运行全部 JUnit 测试
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest"           # 单个测试类
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest.方法名"     # 单个测试方法
```

环境：JVM 17、Kotlin 1.9.22、IntelliJ Platform 2023.3（PhpStorm）、Gradle IntelliJ Plugin 1.17.4。

## 架构（big picture）

请求从上到下的调用链：

```
ChatToolWindowFactory → ChatToolWindow (Swing UI, ~1448 行)
    → ChatViewModel  (UI 桥接，轻量 ViewModel，含 Activity 状态机)
        → AgentLoop (agent，Agent 主循环)
            → AnthropicAdapter (构建请求 / 解析 SSE 事件)
            → SseClient        (流式 HTTP)
            → ToolRegistryV3   (工具分发)
```

### UI 层（`ui/` 包，~2200 行）

| 组件 | 行数 | 职责 |
|---|---|---|
| `ChatBubble` | 168 | **自测量气泡**：`getPreferredSize/getMaximumSize` 实时按 viewport 宽度计算尺寸，hug content + max-width 上限。画圆角背景+描边 |
| `BubbleFactory` | 99 | 气泡工厂：构造内容（用户 HTML JTextPane / AI markdown）+ 包进 ChatBubble + 放进 rowPanel（X_AXIS + glue 做左右对齐） |
| `ChatTheme` | 66 | 设计 token 单一来源：语义色（JBColor 明暗双值）、间距、圆角、字体、宽度约束 |
| `ToolRowFactory` | 442 | 工具/思考行：统一左栏竖线 + 折叠/展开。含 toolCallRow、toolResultRow、thinkingRow、runningRow、errorCardRow |
| `PermissionCard` | 430 | 工具权限确认卡：圆角卡片 + 选项列表 + diff 预览 + 确认态。支持 danger 变体（execute_command） |
| `SelectionCard` | 452 | ask_user 选择卡：单选/多选模式，cheveron 高亮 + hover |
| `PlanBar` | 282 | 置顶计划条：折叠看摘要（标题+进度+迷你进度条），展开看步骤列表 |
| `SimpleDiff` | 140 | 行级 diff 计算（LCS 算法），供 PermissionCard diff 预览使用 |
| `AskUserBridge` | 89 | ask_user 工具与 UI 之间的桥接：全局 handler + CountDownLatch 同步 |
| `MarkdownRenderer` | ~290 | Markdown → Swing JPanel 渲染（标题、列表、代码块、内联代码） |

### 布局架构

```
panel (BorderLayout)
├── NORTH  → errorBanner（错误/警告横幅）
├── CENTER → conversationPanel (BorderLayout)
│   ├── NORTH  → northStack (BoxLayout.Y_AXIS)
│   │   ├── conversationHeader（"对话"标题 + 新会话按钮）
│   │   └── planBar（置顶执行计划，不随消息滚动）
│   └── CENTER → conversationScrollPane
│       └── conversationWrapper (BoxLayout.Y_AXIS，无 glue → 内容顶部对齐)
│           └── conversationContainer (BoxLayout.Y_AXIS)
│               ├── 用户气泡 row（[glue] [ChatBubble] → 靠右）
│               ├── AI 气泡 row（[ChatBubble] [glue] → 靠左）
│               ├── 工具行 / 思考行 / 权限卡 / 选择卡
│               └── 空态提示 / 流式气泡
└── SOUTH  → inputPanel（引用芯片 + textarea + 发送按钮）
```

**关键布局机制：**
- `conversationWrapper`：BoxLayout.Y_AXIS **无 vertical glue**，内容天然从顶部开始，多余空间留白在底部。这是 web `flex-direction: column` 的 Swing 等价
- 气泡行对齐：`rowPanel`（X_AXIS）+ `Box.createHorizontalGlue()`。用户气泡 `[glue][bubble]` → 靠右；AI 气泡 `[bubble][glue]` → 靠左。等价 CSS `justify-content: flex-end / flex-start`
- `ChatBubble.getMaximumSize() = preferredSize`（hug content），确保气泡不被拉伸，glue 才能把它推到对侧

### Agent 层

**Agent 循环**（`agent/AgentLoop.kt`，核心）：在后台 `Thread` 上跑 `while` 循环（`MAX_LOOPS=100`，连续失败 `MAX_FAILURES=3` 即中止）。每轮调用模型 → 若返回 `tool_use` 则执行工具并把结果回填到 `history` 继续下一轮 → 若返回纯文本则结束。所有 UI 更新通过回调（`onMessage`/`onStreaming`/`onToolExecute`…）经 `invokeLater`/`invokeAndWait` 切回 EDT。

**agent 包**（`agent/`）：包含 Agent 核心实现和共享类型——`AgentLoop`、`AgentContext`（贯穿生命周期的共享上下文，含 `Plan`/`Step`/`ImageData`/`AgentMessage`）、`ToolRegistryV3`、`SkillEngine`、`AgentTool` 接口。

**ViewModel 状态机**：`ChatViewModel.Activity` 密封类表达 Agent 当前活动：
- `Idle` → 无指示器
- `Thinking` → 模型推理中（显示思考指示器或 spinner）
- `RunningTool(toolName)` → 执行工具中（显示盲文 spinner）

**适配器：`AnthropicAdapter` 是唯一的适配器**（Anthropic Messages 格式 + `input_schema` 工具）。

**工具系统**：`tools/` 下每个工具实现 `agent.AgentTool` 接口（`name` / `description` / `parameters` / `execute()`）。9 个内置工具由 `ToolRegistryV3.registerBuiltIn()` 注册：`search_code`、`read_file`、`write_file`、`list_directory`、`execute_command`、`git_diff`、`git_log`、`git_status`、`ask_user`。三类工具来源统一管理：内置 / MCP（`registerMcp`）/ Skill（`registerSkills`）。`buildToolsJson()` 生成 Anthropic `input_schema` 并**带缓存**，注册新工具时通过 `invalidateCache()` 失效。

**安全模型**：`AgentLoop.SAFE_TOOLS`（只读工具）+ 用户白名单 `AppSettingsService.getToolWhitelist()` 内的工具直接执行；其余工具（`write_file`、`execute_command` 等）触发内联确认——`onConfirmTool` 回调配合 `CountDownLatch`/`AtomicBoolean` 阻塞等待用户通过 `PermissionCard` 在 UI 上点确认。

**MCP**（`mcp/`）：`McpManager` 从 `~/.claude.json`（用户全局）和 `.mcp.json`（项目根）读取 MCP server 配置，`McpClient` 走 stdio JSON-RPC，把远程 tool 包装成 `AgentTool` 注入注册中心。

**Skills**（`agent/SkillEngine.kt`）：扫描 `<project>/.claude/skills/**/SKILL.md` 和 `~/.claude/skills/**/SKILL.md`，把每个 skill 包装成一个特殊 `AgentTool`。

**Plan**：LLM 通过 `create_plan` 元工具自主决定是否创建执行计划，不再使用本地关键词判定。

## 关键约定与坑

- **DeepSeek V4 兼容**：`AnthropicAdapter.buildRequest()` 在每个 `tool_use` content block 前**预置一个空 `thinking` block**（`{"type":"thinking","thinking":""}`），这是 DeepSeek V4 API 的硬性要求，不要删。
- **SSE 解析手写**：`AnthropicAdapter.parseSseEvent()` 和 `extractJsonString()` 用字符串扫描而非 JSON 库解析事件；`SseClient` 已剥离 `data: ` 前缀，传入的是裸 JSON。修改事件格式时两边都要动。
- **工具参数解析用正则**：`AgentLoop.parseParams()` 用正则 `"(\w+)"\s*:\s*(...)` 从模型返回的 JSON 里抽参数，**不是完整 JSON 解析**——嵌套对象/数组参数会失效，新工具尽量用扁平的字符串/数字参数。
- **JSON 转义统一走 `shared/JsonUtils`**：`escapeJson` / `unescapeJson` 在适配器和工具 schema 里复用，不要再就地手写转义。
- **文件写入边界**：`write_file` 限制在项目目录内，防止越界。
- **API Key**：存于 IntelliJ PasswordSafe（CredentialStore），通过 `AppSettingsService` 读写，不落明文。
- **i18n**：用户可见文案走 `messages/AiAssistantBundle*.properties`（含 `_zh`），通过 `AiAssistantBundle` 读取。

## 设计规范

UI 设计 token（配色 / 字号 / 间距 / 圆角 / 交互态）见 `DESIGN.md`。改动聊天面板视觉时遵循其中的语义色与 WCAG AA 对比度约束。
