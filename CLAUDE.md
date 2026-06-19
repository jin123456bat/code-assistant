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
| `PlanBar` | 331 | 置顶计划/任务条：规划中/已批准计划摘要/任务列表三态切换，折叠展开，进度条 |
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
│   │   └── planBar（置顶计划/任务条，不随消息滚动）
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

**agent 包**（`agent/`）：包含 Agent 核心实现和共享类型——`AgentLoop`、`AgentContext`（贯穿生命周期的共享上下文，含 `Task`/`TaskStatus`（对齐 Claude Code Task 系统）、`planMode`/`approvedPlan`（Plan Mode 审批）、`ImageData`/`AgentMessage`（`id: Long` 消息唯一标识 + `version: Int` 增量渲染版本号）、`skillDefs: Map<String, SkillDef>` 已加载 skill 定义）、`ToolRegistryV3`、`SkillEngine`、`AgentTool` 接口。

**跨轮对话历史**：`AgentContext.conversationHistory`（`synchronizedList<AnthropicMessage>`）跨 `sendMessage()` 调用保留完整 assistant/tool 消息，使 LLM 能感知之前的所有交互。每轮 `run()` 追加当前轮消息，`clearConversation()` 清空。线程安全：单操作由 `synchronizedList` 保护，复合操作（`toList()`+`clear()`+`addAll()`）使用 `historyLock`。

**对话压缩（Compact）**：对齐 Claude Code `/compact`。手动 `/compact` 命令 + 自动 token 阈值触发。自动触发基于 API 返回的 `inputTokens`（通过 `MessageAccumulator` 提取），达到上下文窗口 90%（可配置 10%-100%）时自动压缩。压缩逻辑：`AgentLoop.compact()` 调用 LLM 生成 200-500 字中文摘要 → 注入 `ctx.systemPrompt` → 保留最近 30 条历史记录。手动 compact 先 `stopGeneration()` 防并发。

**子 Agent（TaskTool）**：`task` 工具创建独立 `AgentLoop` 实例执行子任务，不污染主对话 history。子 Agent 完成或主 Agent 中断时通过 `finally { childLoop.stop() }` 确保线程释放。API 失败时 `callback("", "")` 通知调用方避免 5 分钟超时等待。

**ViewModel 状态机**：`ChatViewModel.Activity` 密封类表达 Agent 当前活动：
- `Idle` → 无指示器
- `Thinking` → 模型推理中（显示思考指示器或 spinner）
- `RunningTool(toolName)` → 执行工具中（显示盲文 spinner）

**适配器：`AnthropicAdapter` 是唯一的适配器**（Anthropic Messages 格式 + `input_schema` 工具）。

**工具系统**：`tools/` 下每个工具实现 `agent.AgentTool` 接口（`name` / `description` / `parameters` / `execute()`）。17 个内置工具由 `ToolRegistryV3.registerBuiltIn()` 注册：`search_code`、`read_file`（支持 `resource://` URI 读取 MCP 资源）、`write_file`、`edit_file`、`list_directory`、`execute_command`、`git_diff`、`git_log`、`git_status`、`ask_user`、`web_search`、`web_fetch`、`notebook_edit`、`task`、`code_intelligence`、`mcp_get_prompt`、`workflow`。两类工具来源统一管理：内置 / MCP（`registerMcp`）。七个元工具由 `AgentLoop.buildSdkToolDefs()` 硬编码添加：`Skill`（统一 skill 激活）、`EnterPlanMode`/`ExitPlanMode`（Plan Mode 工作流）、`TaskCreate`/`TaskUpdate`/`TaskList`/`TaskGet`（Task 执行追踪）。

**安全模型**：`AgentLoop.SAFE_TOOLS`（只读工具）+ 用户白名单 `AppSettingsService.getToolWhitelist()` 内的工具直接执行；其余工具（`write_file`、`execute_command` 等）触发内联确认——`onConfirmTool` 回调配合 `CountDownLatch`/`AtomicBoolean` 阻塞等待用户通过审批选择卡在 UI 上点确认。`read_file` 额外检查路径安全：项目内免审，项目外触发审批。

**MCP**（`mcp/`）：`McpManager` 从 `~/.claude.json`（用户全局）和 `.mcp.json`（项目根）读取 MCP server 配置，`McpClient` 支持 **stdio** 和 **HTTP** 两种传输。JSON-RPC 2.0 协议（`initialize` → `initialized` → `tools/list` → `tools/call`）。Client capabilities：`roots`+`sampling`（对齐 Claude Code）。工具发现后包装为 `McpToolAdapter` 注册到 `ToolRegistryV3.registerMcp()`。支持 `McpToolAdapter.rawArgsJson` 保留原始 JSON 参数类型。支持 **MCP Prompts**（`prompts/list` 列在 system prompt + `mcp_get_prompt` 工具按需获取渲染内容）和 **MCP Resources**（`resources/list` 列在 system prompt + `read_file` 支持 `resource://` URI 按需读取）。支持 **SSE 推送通知**（stdio 后台线程持续监听 + HTTP SSE 流，自动处理 `tools/prompts/resources/list_changed`）和 **Ping 心跳**（回复 `{}` 防止服务器超时断连）。支持 **Cancelled 取消通知**（`stop()` 时向所有服务器发送 `notifications/cancelled`）和 **自动健康检查**（`healthCheck` 在每次 agent 对话启动时运行，恢复崩溃的服务器并重新发现工具，同时重试初始连接失败的配置，最多 3 次）。`McpChangeListener` 接口在服务器推送变更时自动更新 `ToolRegistryV3` 和 `AgentContext`（线程安全：`mcpTools` 用 `ConcurrentHashMap`，`mcpPrompts`/`mcpResources` 用 `CopyOnWriteArrayList`）。MCP 加载在后台线程执行避免阻塞 EDT。通知回调使用 `bgExecutor` 线程池复用线程。

**Skills**（`agent/SkillEngine.kt`）：扫描 `<project>/.claude/skills/**/SKILL.md` 和 `~/.claude/skills/**/SKILL.md`，解析为 `SkillDef` 存入 `AgentContext.skillDefs`。对齐 Claude Code：skill 不作为独立工具注册，而是通过统一的 `Skill` 元工具（`Skill(skill=名称, args=输入)`）激活。激活后 skill prompt 注入 system prompt（而非 tool_result 消息），描述列表则始终在 system prompt 的 Skills 段落中渐进披露。支持 `/skill-name` 客户端拦截和 `preferredModel` 模型路由。YAML frontmatter 支持 `allowed-tools`（skill 激活时预批准的工具列表）和 `invoke-for`（`"user"`=仅命令菜单 / `"agent"`=仅 LLM 调用）。

**Plan Mode + Task**（对齐 Claude Code）：LLM 通过 `EnterPlanMode` 进入只读规划模式探索代码库，`ExitPlanMode` 提交方案触发用户审批。批准后通过 `TaskCreate`/`TaskUpdate`/`TaskList`/`TaskGet` 追踪执行进度。PlanBar 三态切换：规划中 / 已批准计划摘要 / 任务列表进度。

**Rules**（`agent/RulesEngine.kt`）：加载 `.claude/rules/*.md`（项目 + 全局），支持 YAML frontmatter `paths` 条件匹配当前编辑文件，匹配到的规则注入 system prompt。无 `paths` 的规则始终生效。

**Goal 目标驱动**：`/goal` 命令设置目标后 Agent 持续工作直到达标（检测"目标已达成"关键词自动终止），GoalBar 显示进度。

**会话持久化**（`session/SessionStore.kt`）：自动保存对话到 `.code-assistant/sessions/`（JSON + 原子写入），`/resume` 恢复，`/export` 导出 Markdown。增量追加：仅在新增消息时写盘。

**Token 追踪**（`ui/TokenDashboard.kt` + `ui/TokenTracker.kt`）：SDK 层捕获 `outputTokens`，AgentMessage 携带 token 数据，气泡悬停显示消耗量（半透明极小字），📊 Dashboard 天/周统计。设置中可关闭。

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

- **Git commit message 必须使用中文**：遵循 Conventional Commits 格式（`feat:`/`fix:`/`refactor:`/`chore:`/`docs:`/`test:`），但描述部分用中文撰写。示例：`feat(agent): 添加 EnterPlanMode/ExitPlanMode Plan Mode 工作流`、`fix(ui): 修复气泡对齐问题`
- **对抗审查工作流（强制）**：任何代码工作遵循 `adversarial-review-requirement` memory 文档——新功能：① 制定方案 → ② 写入文档 → ③ 按文档开发 → ④ 对抗审查（文档未更新，不允许写代码）；修 Bug/重构：① 定位根因 → ② 改代码 → ③ 对抗审查 → ④ 更新文档。

## 关键约定与坑

- **DeepSeek V4 thinking 模式已禁用**：DeepSeek V4 的 Anthropic 兼容 API 不完全支持 thinking 协议。API 返回的 thinking 签名始终为空（sigLen=0），回传无签名 thinking block 触发 400 错误。`AnthropicSdkClient` 中 `enabledThinking(8192)` 已注释，设置面板中 thinking 复选框已移除（避免用户误以为开关有效）。DeepSeek 内部通过 `<think>` 标签自动处理推理内容。如需重新启用，需先解决 DeepSeek 签名完全兼容问题。
- **Anthropic Java SDK**：HTTP/SSE 层使用官方 `com.anthropic:anthropic-java:2.40.1`，替代手写 `SseClient` + `AnthropicAdapter.buildRequest/parseSseEvent`。`AnthropicSdkClient` 封装 SDK 并提供类型安全的 streaming 回调。
- **工具参数解析使用 Gson**：`AgentLoop.parseParams()` 使用 `Gson.fromJson(json, Map::class.java)` 完整解析 JSON，嵌套对象/数组序列化为 JSON 字符串存入 `Map<String, String>`。
- **JSON 转义统一走 `shared/JsonUtils`**：`escapeJson` / `unescapeJson` 在适配器和工具 schema 里复用，不要再就地手写转义。
- **文件写入边界**：`write_file` 限制在项目目录内，防止越界。
- **API Key**：存于 IntelliJ PasswordSafe（CredentialStore），通过 `AppSettingsService` 读写，不落明文。
- **i18n**：用户可见文案走 `messages/AiAssistantBundle*.properties`（含 `_zh`），通过 `AiAssistantBundle` 读取。
- **全局键盘拦截必须聚焦守卫**：`ChatToolWindow.popupKeyDispatcher` 通过 `KeyboardFocusManager.addKeyEventDispatcher()` 注册全局钩子。必须在入口检查 `focusOwner` 是否属于聊天面板（`SwingUtilities.isDescendingFrom(focusOwner, panel)`），否则焦点在编辑器时也会触发拦截器，可能干扰 IDE 快捷键（如 `Ctrl+C`）。
- **Skill 不注册为独立工具**：对齐 Claude Code，每个 skill 不注册为独立 `AgentTool`，统一通过 `Skill` 元工具激活。`ctx.skillDefs` 存储已加载的 `SkillDef`，`buildSystemPrompt()` 生成 Skills 段落（渐进披露）。**安全限制**：`allowed-tools` 仅对 `SAFE_TOOLS` 中的只读工具放行；写操作（write_file/execute_command/edit_file 等）即使被 skill 声明也强制弹出审批卡。原因：skill 定义来自项目文件不可完全信任，纵深防御原则。
- **子 Agent 结果注入对齐 Claude Code**：后台子 Agent（`task` 后台模式、`workflow` 并行模式）的结果通过 `toolCallId` 关联到创建它的 task/workflow 工具调用。AgentLoop 将 `_toolCallId` 注入工具参数，`SubAgentRegistry.register()` 存储关联，`drainCompleted()` 回填时携带 `toolCallId`。这样 LLM 将子 Agent 结果识别为 `tool_result` 而非用户指令，消除信任边界混淆。前台子 Agent 结果直接通过 `ToolResult` 返回，无需额外处理。
- **Edit 工具**：`edit_file` 精确替换文件中唯一匹配的文本，多次匹配报错并列出位置。
- **Workflow 工具**：`workflow` 一次声明多个子任务并行/串行执行，自动收割合并结果。
- **Token 追踪**：`AgentMessage.inputTokens/outputTokens` 携带每条消息的 token 消耗。气泡悬停显示。`ctx.tokenStats` 累计全会话 token 数据。

### 已知设计决策（审查确认的设计意图，非 bug）

以下行为在对抗审查中被标记为潜在风险，但经过确认属于设计意图，对齐 Claude Code 行为：

1. **子 Agent `autoApprove=true`（`AgentType.kt`、`TaskTool.kt`）**：子 Agent 在隔离上下文中执行，免审批避免用户被大量确认弹窗打扰。安全边界由以下机制保障：子 Agent 不暴露元工具、maxLoops 受限、用户可选用 EXPLORE（只读）或 PLAN（纯规划）类型、自定义 Agent 可通过 `autoApprove=false` 覆盖。

2. **Compact 摘要注入 system prompt（`AgentLoop.kt:compactHistory`）**：摘要以系统级指令形式持久存在，对齐 Claude Code `/compact` 行为。摘要由独立 API 调用生成且 prompt 明确指示保留约定，降低了污染概率。

3. **`execute_command` 使用 `/bin/bash -c` 不做命令过滤（`ExecuteCommandTool.kt`）**：安全依赖审批机制（工具不在 SAFE_TOOLS 中，每次弹卡）。黑名单过滤会产生误报且无法覆盖新攻击模式，对齐 Claude Code 依赖用户审批的设计。用户可将信任工具加入白名单来权衡安全与便利。**辅助防御**：命令长度上限 50000 字符 + 危险模式检测（`curl | sh`、`rm -rf /` 等），检测到时在结果中附加 ⚠️ 安全警告但不阻止执行。

4. **子代理无 maxLoops 限制 + UI 停止按钮（`AgentLoop.kt`、`ToolRowFactory.kt`、`SubAgentRegistry.kt`）**：子代理使用 `MAX_SUB_LOOPS = Int.MAX_VALUE`（与主代理一致），不再限制循环轮次。防护机制仅保留 `MAX_FAILURES = 3`。UI 上 task 工具运行行显示可交互图标：默认盲文 loading 动画 → 鼠标悬停变红色停止图标 ■ → 点击后中止子代理并通知主 Agent。设计原因：复杂任务可能远超 20 轮循环；`MAX_FAILURES = 3` 已足够防止无限失败循环；用户可通过 UI 停止按钮随时终止（比固定轮次上限更灵活）；对齐 Claude Code——Claude Code 对子代理同样不设轮次上限。**后续扫描到子代理超时/长轮次问题统一标记为"不是 bug"——这是设计意图。**

## 设计规范

UI 设计 token（配色 / 字号 / 间距 / 圆角 / 交互态）见 `DESIGN.md`。改动聊天面板视觉时遵循其中的语义色与 WCAG AA 对比度约束。
