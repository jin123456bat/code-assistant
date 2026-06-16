# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 🌐 语言声明

**本项目所有输出必须使用简体中文**，包括但不限于：代码注释、文档、Git commit message、PR 描述、Issue 回复、对话回复。禁止输出英文内容。

## 项目概述

IntelliJ IDEA 的开源 AI 编程 Agent 插件（IntelliJ Platform plugin，type `IC`，兼容所有 JetBrains IDE）。基于 Kotlin + Swing，通过 DeepSeek 的 **Anthropic 兼容 Messages API**（`/anthropic/v1/messages`）驱动一个可自主调用工具的 Agent 循环。用户自带 DeepSeek API Key。

## 常用命令

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox IntelliJ IDEA（autoReloadPlugins=true：改代码后重新编译即热加载，无需重启）
./gradlew test             # 运行全部 JUnit 测试
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest"           # 单个测试类
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest.方法名"     # 单个测试方法
```

环境：JVM 17、Kotlin 1.9.22、IntelliJ Platform 2023.3（IntelliJ IDEA Community）、Gradle IntelliJ Plugin 1.17.4。

## 架构（big picture）

请求从上到下的调用链：

```
ChatToolWindowFactory → ChatToolWindow (Swing UI, ~1590 行)
    → ChatViewModel  (UI 桥接，轻量 ViewModel，含 Activity 状态机)
        → AgentLoop (agent，Agent 主循环)
            → AnthropicSdkClient (Anthropic Java SDK 封装)
            → ToolRegistryV3    (工具分发)
```

### UI 层（`ui/` 包，~2200 行）

| 组件 | 行数 | 职责 |
|---|---|---|
| `ChatBubble` | 176 | **自测量气泡**：`getPreferredSize/getMaximumSize` 实时按 viewport 宽度计算尺寸，hug content + max-width 上限。画圆角背景+描边 |
| `BubbleFactory` | 123 | 气泡工厂：构造内容（用户 HTML JTextPane / AI markdown）+ 包进 ChatBubble + 放进 rowPanel（X_AXIS + glue 做左右对齐） |
| `ChatTheme` | 278 | 设计 token 单一来源：语义色（JBColor 明暗双值）、间距、圆角、字体、宽度约束。所有 UI 代码禁止硬编码值 |
| `ToolRowFactory` | 604 | 工具/思考行 + 审批选项 + RefChip：统一左栏竖线 + 折叠/展开。含 toolCallRow、toolResultRow、thinkingRow、runningRow、errorCardRow、buildApprovalOptions |
| `SelectionCard` | 452 | ask_user 选择卡：单选/多选模式，cheveron 高亮 + hover |
| `PlanBar` | 331 | 置顶计划条：折叠看摘要（标题+进度+迷你进度条），展开看步骤列表 |
| `SimpleDiff` | 140 | 行级 diff 计算（LCS 算法），由测试驱动 |
| `PathUtils` | 30 | 路径安全：canonical path 前缀校验，供 AgentLoop 审批 + 工具执行复用 |
| `AskUserBridge` | 87 | ask_user 工具与 UI 之间的桥接：全局 handler + CountDownLatch 同步 |
| `MarkdownRenderer` | 405 | Markdown → Swing JPanel 渲染（标题、列表、代码块、内联代码） |

### 布局架构

```
panel (BorderLayout)
├── NORTH  → errorBanner（错误/警告横幅）
├── CENTER → conversationPanel (BorderLayout)
│   ├── NORTH  → northStack (BoxLayout.Y_AXIS)
│   │   ├── conversationHeader（"对话"标题 + 新会话按钮）
│   │   └── planBar（置顶执行计划，不随消息滚动）
│   └── CENTER → conversationScrollPane
│       └── conversationContainer (BoxLayout.Y_AXIS, JBScrollPane 强制视图宽=视口宽)
│           ├── 用户气泡 row（[glue] [ChatBubble] → 靠右）
│           ├── AI 气泡 row（[ChatBubble] [glue] → 靠左）
│           ├── 工具行 / 思考行 / 审批选项 / 选择卡
│           └── 空态提示 / 流式气泡
└── SOUTH  → inputPanel（引用芯片 + textarea + 发送按钮）
```

**关键布局机制：**
- `conversationContainer`：直接作为 `JBScrollPane` 视图。`HORIZONTAL_SCROLLBAR_NEVER` 强制视图宽度 = 视口宽，确保 rowPanel 的 X_AXIS glue 有足够空间把气泡推到正确对侧
- 气泡行对齐：`rowPanel`（X_AXIS）+ `Box.createHorizontalGlue()`。用户气泡 `[glue][bubble]` → 靠右；AI 气泡 `[bubble][glue]` → 靠左。等价 CSS `justify-content: flex-end / flex-start`
- `ChatBubble.getMaximumSize() = preferredSize`（hug content），确保气泡不被拉伸，glue 才能把它推到对侧

**会话渲染（`rebuildConversation`，增量 + 版本号变更检测）：**

`rebuildConversation()` 负责将 `viewModel.messages` 同步到 `conversationContainer`。渲染策略分为两种：

| 触发条件 | 策略 | 说明 |
|---------|------|------|
| `needFullRebuild = true` | 全量重建 | `removeAll()` + 清空追踪后重新渲染所有消息 |
| `renderedMsgVersions.size > displayMessages.size` | 全量重建 | 消息减少（clear/delete）自动触发 |
| 其他（新增消息、原地更新） | 增量渲染 | 仅处理版本号变化的消息 |

**增量渲染核心机制：**

```
AgentMessage.version (Int, 默认 0)
    ↓ copy() 时递增（onToolResult / onConfirmTool / addThinkingMessage）
renderedMsgVersions: Map<msgId → version>   ← 上次渲染的版本号
msgIdToComponent:    Map<msgId → Component>  ← 已渲染组件引用
```

增量循环逻辑：
1. 遍历 `displayMessages`，比较 `renderedMsgVersions[msg.id]` vs `msg.version`
2. 新消息（map 中无记录）→ 创建组件并添加到容器
3. 版本号不匹配（原地更新）→ 先从容器移除旧组件（`msgIdToComponent`），再创建新组件
4. 版本号匹配 → 跳过

**回调精简**：`onMessagesChanged` 是 `rebuildConversation()` 的**唯一驱动入口**。`onToolExecute`/`onToolResult` 回调已删除（原先它们设置 `needFullRebuild = true` 并重复调用 `rebuildConversation()`，导致每次工具事件 2 次全量重建）。

**流式组件管理**：`streamingThinkingRow` 和 `streamingBubble` 由 `updateStreamingThinking()`/`updateStreamingBubble()` 驱动更新。`rebuildConversation()` 在创建新流式组件前会显式移除旧引用（`.parent` 为 null 时回调方法会自动重建），避免增量模式下重复。

### Agent 层

**Agent 循环**（`agent/AgentLoop.kt`，核心）：在后台 `Thread` 上跑 `while` 循环（`MAX_LOOPS=100`，连续失败 `MAX_FAILURES=3` 即中止）。每轮调用模型 → 若返回 `tool_use` 则执行工具并把结果回填到 `history` 继续下一轮 → 若返回纯文本则结束。所有 UI 更新通过回调（`onMessage`/`onStreaming`/`onToolExecute`…）经 `invokeLater`/`invokeAndWait` 切回 EDT。

**agent 包**（`agent/`）：包含 Agent 核心实现和共享类型——`AgentLoop`、`AgentContext`（贯穿生命周期的共享上下文，含 `Plan`/`Step`/`ImageData`/`AgentMessage`（`id: Long` 消息唯一标识 + `version: Int` 增量渲染版本号）、`skillDefs: Map<String, SkillDef>` 已加载 skill 定义）、`ToolRegistryV3`、`SkillEngine`、`AgentTool` 接口。

**跨轮对话历史**：`AgentContext.conversationHistory`（`synchronizedList<AnthropicMessage>`）跨 `sendMessage()` 调用保留完整 assistant/tool 消息，使 LLM 能感知之前的所有交互。每轮 `run()` 追加当前轮消息，`clearConversation()` 清空。线程安全：单操作由 `synchronizedList` 保护，复合操作（`toList()`+`clear()`+`addAll()`）使用 `historyLock`。

**对话压缩（Compact）**：对齐 Claude Code `/compact`。手动 `/compact` 命令 + 自动 token 阈值触发。自动触发基于 API 返回的 `inputTokens`（通过 `MessageAccumulator` 提取），达到上下文窗口 90%（可配置 10%-100%）时自动压缩。压缩逻辑：`AgentLoop.compact()` 调用 LLM 生成 200-500 字中文摘要 → 注入 `ctx.systemPrompt` → 保留最近 30 条历史记录。手动 compact 先 `stopGeneration()` 防并发。

**子 Agent（TaskTool）**：`task` 工具创建独立 `AgentLoop` 实例执行子任务，不污染主对话 history。子 Agent 完成或主 Agent 中断时通过 `finally { childLoop.stop() }` 确保线程释放。API 失败时 `callback("", "")` 通知调用方避免 5 分钟超时等待。

**ViewModel 状态机**：`ChatViewModel.Activity` 密封类表达 Agent 当前活动：
- `Idle` → 无指示器
- `Thinking` → 模型推理中（显示思考指示器或 spinner）
- `RunningTool(toolName)` → 执行工具中（显示盲文 spinner）

**适配器：`AnthropicAdapter` 是唯一的适配器**（Anthropic Messages 格式 + `input_schema` 工具）。

**工具系统**：`tools/` 下每个工具实现 `agent.AgentTool` 接口（`name` / `description` / `parameters` / `execute()`）。15 个内置工具由 `ToolRegistryV3.registerBuiltIn()` 注册：`search_code`、`read_file`（支持 `resource://` URI 读取 MCP 资源）、`write_file`、`list_directory`、`execute_command`、`git_diff`、`git_log`、`git_status`、`ask_user`、`web_search`、`web_fetch`、`notebook_edit`、`task`、`code_intelligence`、`mcp_get_prompt`。两类工具来源统一管理：内置 / MCP（`registerMcp`）。三个元工具由 `AgentLoop.buildSdkToolDefs()` 硬编码添加：`Skill`（统一 skill 激活）、`create_plan`、`update_plan_step`。

**安全模型**：`AgentLoop.SAFE_TOOLS`（只读工具）+ 用户白名单 `AppSettingsService.getToolWhitelist()` 内的工具直接执行；其余工具（`write_file`、`execute_command` 等）触发内联确认——`onConfirmTool` 回调配合 `CountDownLatch`/`AtomicBoolean` 阻塞等待用户通过审批选择卡在 UI 上点确认。`read_file` 额外检查路径安全：项目内免审，项目外触发审批。

**MCP**（`mcp/`）：`McpManager` 从 `~/.claude.json`（用户全局）和 `.mcp.json`（项目根）读取 MCP server 配置，`McpClient` 支持 **stdio** 和 **HTTP** 两种传输。JSON-RPC 2.0 协议（`initialize` → `initialized` → `tools/list` → `tools/call`）。Client capabilities：`roots`+`sampling`（对齐 Claude Code）。工具发现后包装为 `McpToolAdapter` 注册到 `ToolRegistryV3.registerMcp()`。支持 `McpToolAdapter.rawArgsJson` 保留原始 JSON 参数类型。支持 **MCP Prompts**（`prompts/list` 列在 system prompt + `mcp_get_prompt` 工具按需获取渲染内容）和 **MCP Resources**（`resources/list` 列在 system prompt + `read_file` 支持 `resource://` URI 按需读取）。支持 **SSE 推送通知**（stdio 后台线程持续监听 + HTTP SSE 流，自动处理 `tools/prompts/resources/list_changed`）和 **Ping 心跳**（回复 `{}` 防止服务器超时断连）。支持 **Cancelled 取消通知**（`stop()` 时向所有服务器发送 `notifications/cancelled`）和 **自动健康检查**（`healthCheck` 在每次 agent 对话启动时运行，恢复崩溃的服务器并重新发现工具，同时重试初始连接失败的配置，最多 3 次）。`McpChangeListener` 接口在服务器推送变更时自动更新 `ToolRegistryV3` 和 `AgentContext`（线程安全：`mcpTools` 用 `ConcurrentHashMap`，`mcpPrompts`/`mcpResources` 用 `CopyOnWriteArrayList`）。MCP 加载在后台线程执行避免阻塞 EDT。通知回调使用 `bgExecutor` 线程池复用线程。

**Skills**（`agent/SkillEngine.kt`）：扫描 `<project>/.claude/skills/**/SKILL.md` 和 `~/.claude/skills/**/SKILL.md`，解析为 `SkillDef` 存入 `AgentContext.skillDefs`。对齐 Claude Code：skill 不作为独立工具注册，而是通过统一的 `Skill` 元工具（`Skill(skill=名称, args=输入)`）激活。激活后 skill prompt 注入 system prompt（而非 tool_result 消息），描述列表则始终在 system prompt 的 Skills 段落中渐进披露。支持 `/skill-name` 客户端拦截和 `preferredModel` 模型路由。

**Plan**：LLM 通过 `create_plan` 元工具自主决定是否创建执行计划，不再使用本地关键词判定。

### 输入区域（Composer）

对齐设计稿 `Chat UI Redesign.html` 的 `.composer` 结构：

```
inputPanel (border: Empty(8,12,12,12), bg: winBg)
  └── composerBox (BorderLayout, bg: inputBg, 圆角, focus→蓝色)
        ├── NORTH  → chipPanel (WrapLayout, 引用/图片芯片)
        ├── CENTER → JBScrollPane → inputArea (JTextArea, placeholder)
        └── SOUTH  → toolbar (➕ 加号 + → 发送按钮)
```

**按钮圆角实现**：JLabel 设为 `isOpaque=false`，在 `paintComponent` 中用 `fillRoundRect` 画圆角背景，再用 `super.paintComponent(g)` 画文字（border 画填充会覆盖文字）。发送按钮 `userBg` 底 + 8px 圆角，加号 `inputBg` 底（默认融合）+ hover 时 `chipHoverBg` 底 + `chipBorder` 描边 + 6px 圆角。

**引用芯片**：输入区 chipPanel 和气泡底部 footer chips 统一使用 `paintComponent` 画 `chipBg` 圆角底 + `chipBorder` 描边。`messageRefChips` 存储在 `ChatViewModel` 中，在 `onMessagesChanged` 触发前写入，确保气泡渲染时 chips 已存在。

**文件路径点击跳转**：`FilePathNavigator` 工具类，通过 `MouseListener + viewToModel2D` 检测点击位置的文件路径/URL。Markdown 气泡的 JTextPane 和工具结果的 JTextArea 均附加此监听器。文件路径 → IDE 跳转，http/https/ftp URL → `BrowserUtil.browse()` 浏览器打开。

**横幅**：`errorLabel`/`warningLabel` 重写 `getMaximumSize()` 返回 `Int.MAX_VALUE` 宽度确保文字居中全宽。输入文字时 `DocumentListener` 自动 `hideError()`。

## 编码约定

- **Git commit message 必须使用中文**：遵循 Conventional Commits 格式（`feat:`/`fix:`/`refactor:`/`chore:`/`docs:`/`test:`），但描述部分用中文撰写。示例：`feat(agent): 添加 update_plan_step 元工具`、`fix(ui): 修复气泡对齐问题`
- **文档优先（强制）**：任何代码改动或功能改动，**先更新文档，再写代码**——`DESIGN.md`（设计规范/交互行为）和 `PROJECT.md`（项目结构/架构/配置）是唯一真相来源。代码是文档的实现，文档未更新则不允许开始写代码。流程：① 更新文档描述目标行为和方案 → ② 按文档实现代码 → ③ 验证代码与文档一致。

## 关键约定与坑

- **DeepSeek V4 thinking 回传**：启用 thinking 模式时，assistant 回复中的 `thinking` content block 必须随后续请求传回 API。`AnthropicSdkClient.buildSdkMessage()` 构建 `ThinkingBlockParam` 时 `.signature()` 始终传入（空字符串也传，满足 SDK `checkRequired` 要求）。`AgentLoop` 中 thinking 存在与否的判断条件仅需 `thinking.isNotBlank()`，不再要求 `signature.isNotBlank()`（DeepSeek V4 可能返回无签名的 thinking）。
- **Anthropic Java SDK**：HTTP/SSE 层使用官方 `com.anthropic:anthropic-java:2.40.1`，替代手写 `SseClient` + `AnthropicAdapter.buildRequest/parseSseEvent`。`AnthropicSdkClient` 封装 SDK 并提供类型安全的 streaming 回调。
- **工具参数解析使用 Gson**：`AgentLoop.parseParams()` 使用 `Gson.fromJson(json, Map::class.java)` 完整解析 JSON，嵌套对象/数组序列化为 JSON 字符串存入 `Map<String, String>`。
- **JSON 转义统一走 `shared/JsonUtils`**：`escapeJson` / `unescapeJson` 在适配器和工具 schema 里复用，不要再就地手写转义。
- **文件写入边界**：`write_file` 限制在项目目录内，防止越界。
- **API Key**：存于 IntelliJ PasswordSafe（CredentialStore），通过 `AppSettingsService` 读写，不落明文。
- **i18n**：用户可见文案走 `messages/AiAssistantBundle*.properties`（含 `_zh`），通过 `AiAssistantBundle` 读取。
- **全局键盘拦截必须聚焦守卫**：`ChatToolWindow.popupKeyDispatcher` 通过 `KeyboardFocusManager.addKeyEventDispatcher()` 注册全局钩子。必须在入口检查 `focusOwner` 是否属于聊天面板（`SwingUtilities.isDescendingFrom(focusOwner, panel)`），否则焦点在编辑器时也会触发拦截器，可能干扰 IDE 快捷键（如 `Ctrl+C`）。
- **Skill 不注册为独立工具**：对齐 Claude Code，每个 skill **不再**作为独立 `AgentTool` 注册到 `ToolRegistryV3`。统一通过 `Skill` 元工具激活，prompt 注入 system prompt。`ctx.skillDefs` 存储已加载的 `SkillDef`，`buildSystemPrompt()` 生成 Skills 段落（渐进披露）。

## 设计规范

UI 设计 token（配色 / 字号 / 间距 / 圆角 / 交互态）见 `DESIGN.md`。改动聊天面板视觉时遵循其中的语义色与 WCAG AA 对比度约束。
