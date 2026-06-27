# Code Assistant Agent Mode — 技术契约

关联设计文档：[`../DESIGN.md`](../DESIGN.md)。本文档定义所有组件的公开接口、数据契约和线程模型。

---

## 一、包结构

```
com.aiassistant/
├── agent/
│   ├── ToolModels.kt          // 14 个 Tool 数据类（9 个内置 + 5 个计划）+ @JsonClassDescription 注解
│   ├── ToolInput.kt           // 工具输入参数类
│   ├── ToolRegistry.kt        // 工具注册中心
│   ├── ToolExecutor.kt        // 工具执行分发器（when 路由）
│   ├── AgentLoop.kt           // Agent 主循环
│   ├── AgentSession.kt        // 会话状态机
│   ├── PlanExecutor.kt        // Plan Mode
│   └── MultiAgentManager.kt   // 多 Agent（phase 5）
├── skills/
│   ├── SkillManager.kt        // Skill 扫描/注册（含 Skill 数据类）
│   └── SkillModels.kt         // Skill 数据类（⏳ 待独立：当前内嵌于 SkillManager）
├── mcp/
│   ├── McpManager.kt          // MCP Server 生命周期
│   └── McpConfigStore.kt      // 配置读写（⏳ 待独立：当前内嵌于 McpManager）
├── session/
│   ├── SessionStore.kt        // JSON 持久化（含 SessionModels 数据类）
│   ├── SessionManager.kt      // 会话 CRUD
│   └── SessionModels.kt       // 数据类（⏳ 待独立：当前内嵌于 SessionStore）
├── ui/
│   ├── ChatToolWindowFactory.kt
│   ├── ChatToolWindow.kt      // 顶层容器
│   ├── TabBar.kt              // 顶部导航
│   ├── AppColors.kt           // 颜色令牌
│   ├── MessageBus.kt          // 事件总线
│   ├── SelectionListener.kt   // 编辑器选中监听
│   ├── OpenChatAction.kt      // 快捷键 Action
│   ├── page/                  // 8 个 Page 文件（含 PlaceholderPage 占位，运行时仅 7 个页面可见）
│   │   ├── WelcomePage.kt
│   │   ├── ChatPage.kt
│   │   ├── SessionsPage.kt
│   │   ├── TokenUsagePage.kt
│   │   ├── McpPage.kt
│   │   ├── SkillsPage.kt
│   │   ├── SettingsPage.kt      // 关于页面 + 快捷键参考（Agent 设置已迁移到 SettingsConfigurable）
│   │   └── PlaceholderPage.kt  // 占位页面
│   └── chat/                  // 聊天 UI 组件
│       ├── ChatBubbleRenderer.kt
│       ├── ChatViewModel.kt
│       ├── ChatInputArea.kt
│       ├── ToolCallCard.kt
│       ├── PlanCard.kt
│       ├── RoundedBorder.kt    // 圆角边框
│       └── SimpleDiff.kt       // LCS diff 算法
└── actions/
    └── GenerateCommitAction.kt
```

---

## 二、核心组件接口

### 2.1 AgentLoop

> **设计原理：** 见 [`agent.md` 第三节 > Agent Loop](../docs/agent.md#三agent-loop核心逻辑) 和 [`agent.md` 第八节 > 上下文自动压缩](../docs/agent.md#八上下文自动压缩auto-compact)。

**职责：** Agent 主循环——发送消息到 LLM、解析 tool_use、调度工具执行、回传结果。

```
AgentLoop
├── 构造: AgentLoop(session, toolRegistry, client, settings)
├── run(userMessage: String): Flow<AgentEvent>
│   AgentEvent = TextDelta | ToolCallStarted | ToolCallCompleted | TurnCompleted | Error | StateChanged
// StateChanged(newState: AgentState) — 状态变更事件，覆盖 AWAITING_APPROVAL/EXECUTING/IDLE/CANCELLED/ERROR 等转换
├── cancel()
├── isRunning: Boolean
├── currentState: AgentState            // 从 AgentSession 读取
├── compactIfNeeded(system: String, messages: List, tools: String, modelLimit): Boolean
│                         // 估算实际总 token = system + messages + tools。超过 compactThreshold × modelLimit 则压缩。
│                         // compact 后 Skill 正文从磁盘重新注入，@file 文件内容不重新注入（LLM 应通过 Read 重新读取）
├── compactThreshold: Double = 0.7      // 触发压缩的上下文使用率阈值。选 0.7：token 估算误差 ±20%，最坏低估时 0.7/0.8=87.5% 仍在 1M 窗口内；若设 0.8 则最坏已达 100%
├── modelContextLimit: Int = 1_000_000  // 模型上下文窗口大小（DeepSeek V4 的 1M tokens，写死）
├── maxAutoContinue: Int = 5            // max_tokens 自动续写最大次数（单轮内连续续写链长度），防止死循环
├── autoContinueMessage: String = "继续" // max_tokens 时自动发送的续写消息
├── continueStreak: Int = 0              // 当前轮内续写链已执行次数，end_turn 后归零
├── turnWarningRatio: Double = 0.6      // ⏳ 规划中：轮次预警触发比例（turn >= maxTurns * ratio 时附加系统提示）
└── turnWarningMessage: String          // ⏳ 规划中：轮次预警文本模板
```

**线程模型：**

- `run()` 在 `ApplicationManager.executeOnPooledThread()` 上执行（非 EDT）
- 流式 token 通过 `Flow<AgentEvent>.collect()` 接收，在 collector 处 `invokeLater` 切到 EDT
- `cancel()` 可在任意线程调用
- Write 内部通过 `invokeAndWait` 切到 EDT

**约束：**

- 单个 AgentSession 同时只能有一个 `run()` 在执行
- AgentEvent 的发射在 background thread，UI 订阅者负责线程切换

**工具结果自检反馈环（⏳ 规划中）：**

每次 tool result 回传 LLM 前，根据结果类型自动附加检查提示，防止 LLM 忽略异常：

```
fun annotateToolResult(toolName: String, result: ToolResult): String {
    val hints = mutableListOf<String>()
    when (toolName) {
        "Read" -> if (result.content.contains("不存在")) hints += "提示：文件不存在，请确认路径是否正确。不要假设文件内容。"
        "Grep" -> {
            if (result.content.contains("未找到匹配")) hints += "提示：未找到匹配。不要假设代码存在，考虑用 Glob 确认文件位置。"
            if (result.content.contains("已截断")) hints += "提示：搜索结果已截断，可能有遗漏。如需修改代码，建议先用更精确的关键词确认范围。"
        }
        "Bash" -> {
            if (result.exitCode != 0) hints += "⚠️ 命令执行失败。请分析错误原因后决定下一步，不要忽略此错误继续执行。"
            if (!result.stderr.isNullOrEmpty()) hints += "⚠️ 命令有错误输出，请检查 stderr 内容。"
        }
        "readLints"   -> if (result.content.contains("错误:")) hints += "⚠️ 文件存在编译错误。请先解决这些错误再继续修改代码。"
        "Glob"   -> if (result.content.contains("已截断")) hints += "提示：目录列表已截断。可用 dirPath/maxDepth 缩小范围，或使用 offset 参数翻页获取更多条目。"
    }
    return if (hints.isNotEmpty()) result.content + "\n\n" + hints.joinToString("\n") else result.content
}
```

**约束：** 提示信息仅用于引导 LLM，不改变 `ToolResult` 的数据结构。`content` 字段仍然是工具返回的原始内容，提示作为追加文本存在于传给 LLM 的最终字符串中。

---

### 2.2 AgentSession

**职责：** 会话状态机、消息列表、关联 Plan。

```
AgentSession
├── id: String (UUID)
├── messages: List<Message>           // 只读视图，通过 addMessage() 追加
├── state: AgentState
├── plan: Plan?                       // 关联的计划（最多一个）
├── runningProcesses: Set<ProcessHandle>  // 当前会话的 Shell 进程
├── filesReadThisTurn: Set<String>        // ⏳ 规划中：当前 turn 中已读取的文件路径，用于 Read 前置校验
├── compactSummary: String?               // compact 后的对话摘要，null 表示未执行过 compact
├── compactCount: Int = 0                 // 已执行 compact 的次数
│
├── addMessage(msg: Message)
├── setState(newState: AgentState)
├── cancel()                          // 标记 cancelled=true，清理 runningProcesses
├── onStateChanged: (AgentState) -> Unit   // 状态变更回调（UI 注册）
├── onMessageAdded: (Message) -> Unit      // 消息追加回调
├── rollbackTo(messageId: String)         // 回退：标记 messageId 之后的消息 deleted=true，保留之前历史
└── totalTokens: TokenUsage                // 输入/输出 token 累计

AgentState = IDLE | PROCESSING | AWAITING_APPROVAL | EXECUTING | CANCELLED | ERROR

Message:
├── id: String
├── role: USER | ASSISTANT | SYSTEM | TOOL_CALL | TOOL_RESULT | ERROR
├── content: String
├── timestamp: Instant
├── toolCalls: List<ToolCallRecord>?   // ASSISTANT 消息可能含工具调用
├── tokenUsage: TokenDelta?            // ASSISTANT 消息的 token 消耗
├── feedback: String?                  // 用户反馈 "positive" | "negative"，仅 ASSISTANT 消息
└── deleted: Boolean = false           // 回退标记，true 时持久化保留但 UI 不渲染。撤销回退时恢复为 false

TokenUsage:
├── inputTokens: Long
├── outputTokens: Long
└── timestamp: Instant
// TokenUsage 是 Session 级累计聚合，TokenDelta（见 2.4 ChatMessage）是单条消息级增量。两者关系见 2.4。
```

---

### 2.3 ToolRegistry

> **设计原理：** 工具系统防幻觉方案见 [`agent.md` 第十五节](../docs/agent.md#十五防止-llm-幻觉)，代码正确性验证方案见 [`agent.md` 第十六节](../docs/agent.md#十六agent-代码改动正确性验证)，方案正确性验证见 [`agent.md` 第十七节](../docs/agent.md#十七agent-方案正确性验证)。

**职责：** 工具注册、查找、列表，统一管理内置工具 + MCP 工具。

```
ToolRegistry
├── register(name: String, toolClass: Class<*>, info: ToolInfo)  // 注册工具
├── get(name: String): Class<*>?       // 按名查找 tool class
├── listAll(): List<Class<*>>          // 所有已注册工具 class
├── listBuiltin(): List<String>        // 内置工具名称列表
└── toToolDefinitions(): List<String>  // 转为工具名称列表

ToolInfo (ToolRegistry 内部类):
├── name: String                       // Read, Write, ...
├── description: String                // LLM 看到的工具描述，**必须包含上限声明**
└── usage: String                      // 使用示例

工具数据类 (ToolModels.kt):
  9 个内置工具 @JsonClassDescription 注解的 data class + 5 个计划任务管理工具（createPlan/listSteps/deleteStep/reorderSteps/markStepDone），通过 SDK toolFromClass() 生成 JSON Schema。
  工具执行分发 → ToolExecutor.kt 通过 when(toolName) 路由。

ToolInput.kt:
  存放每个工具的参数基类/接口（如 ToolInput 接口，定义 timeout 公共字段）。
  9 个具体 ToolInput 数据类（ReadInput / WriteInput / ...），各带 @JsonClassDescription。
  Anthropic SDK 通过 toolFromClass() 将 ToolInput 类转为 JSON Schema。
  与 ToolModels.kt 的关系：ToolModels.kt 存放工具元数据（名称/描述/上限），ToolInput.kt 存放参数定义。
```

**工具描述中的上限声明：**

每个工具的 `description` 字段必须包含返回值上限，让 LLM **事前知道**限制，而非仅靠事后截断标注。各工具
description 应包含的上限声明：

| 工具             | description 中应包含的上限声明                                                                                        |
|----------------|--------------------------------------------------------------------------------------------------------------|
| `Read`         | `单次最多返回 500 行。如果文件行数超过此限制，返回内容会被截断，请使用 startLine 参数分页读取剩余内容。`                                                |
| `Grep`         | `最多返回 50 条匹配。如果匹配数超过此限制，结果会被截断，请使用更精确的搜索词缩小范围。`                                                              |
| `Glob`         | `最多返回 50 个条目（文件+目录）。如果超出此限制，结果会被截断并在返回值中标注。请用 dirPath/maxDepth 缩小范围，或用 offset 参数翻页获取更多条目。`                   |
| `Bash`         | `最多返回 200 行输出（stdout+stderr）。如果超出此限制，传给 LLM 的内容会被截断，完整输出保留在 IDE 工具卡片中。timeout 参数由 LLM 根据命令类型自行判断传入（秒），0=不限。` |
| `readLints`    | `最多返回 50 条诊断，按 severity 排序（ERROR > WARNING > INFO）。如果超出此限制，低严重度诊断可能不显示。`                                     |
| `Edit`         | `newString 最多 3000 行。超过此限制的操作会被拒绝。`                                                                          |
| `Write`        | `内容最多 3000 行。超过此限制的操作会被拒绝。`                                                                                  |
| `Skill`        | `执行指定 Skill。LLM 根据用户需求自主判断触发时机，将 SKILL.md 内容作为消息注入 conversation。`                                            |
| `Task`         | `子 Agent 结果摘要最多 2000 tokens。完整执行过程保存为独立 Session。`                                                            |
| `createPlan`   | `创建执行计划，最多 20 步。计划创建后自动开始执行，LLM 可用 listSteps/deleteStep/reorderSteps 管理。`                                    |
| `listSteps`    | `查看当前计划的所有步骤及状态，无参数。`                                                                                        |
| `deleteStep`   | `删除指定步骤（仅 PENDING 状态可删）。`                                                                                    |
| `reorderSteps` | `重排剩余 PENDING 步骤的执行顺序，传入新的 stepId 序列。`                                                                       |
| `markStepDone` | `将指定步骤标记为 DONE（PENDING/EXECUTING 状态均可标记）。LLM 确认步骤已通过其他方式完成时调用。`                                              |

> **双重告知原则：** 上限同时存在于工具描述（事前，LLM 调用前就知道）和返回值截断标注（事后，LLM
> 拿到结果后确认）。两者不可互相替代——事前告知防止误判，事后标注确保发现遗漏。

**执行分发（每个 AgentTool 内部）：**

- Read / Glob / Grep / ReadLints → Background Thread
- Write / Edit → `invokeAndWait { WriteCommandAction }`
- Bash → Background Thread（`ProcessHandler` + 实时 `onOutput` 回调）
- Task → `MultiAgentManager.spawn()`
- CreatePlan / ListTasks / DeleteTask / ReorderTasks / MarkTaskDone → `PlanExecutor`（计划任务管理）

**超时机制：** 每个工具都包含必填的 `timeout` 参数（秒），由 LLM 在 tool call 时传入。
0=不限。Bash 超时时强制 `destroyForcibly()` 终止进程。其他 I/O 工具的 timeout
由 ToolExecutor 统一读取，目前作为安全兜底（操作通常很快完成）。

**文件操作前置校验（⏳ 规划中）：**

`ToolExecutor` 在执行文件操作工具前做前置校验，防止 LLM 幻觉导致非法操作：

| 校验项               | 适用工具                 | 规则                                                                |
|-------------------|----------------------|-------------------------------------------------------------------|
| 文件存在性             | `Read`、`Edit`（非新建）   | VFS 中不存在 → 拒绝执行，返回错误                                              |
| Read 前置           | `Edit`、`Write`（覆盖模式） | 当前 turn 中该文件未被 `Read` 过 → 拒绝执行，返回 `"请先用 Read 读取 {filePath} 后再修改"` |
| modificationStamp | `Edit`、`Write`（覆盖模式） | 文件 stamp 与上次 `Read` 时不一致 → 拒绝执行，返回 `"文件已被外部修改"`                   |

**校验失败恢复规则：** 每种拒绝返回的错误消息中自带恢复指引，Agent Loop 不自动重试。LLM
需根据错误消息中的指引自行决定下一步：

| 校验失败类型    | 错误消息中的指引                        | LLM 的恢复动作                        |
|-----------|---------------------------------|----------------------------------|
| 文件不存在     | `"提示：使用 Glob 查看目录结构"`           | 用 `Glob` 确认路径后重新调用               |
| Read 前置   | `"请先用 Read 读取 {filePath} 后再修改"` | 调用 `Read` 读取文件后重新 `Edit`/`Write` |
| stamp 不匹配 | `"请使用 Read 重新读取文件后再试"`          | 调用 `Read` 获取最新内容和 stamp 后重新修改    |

**约束：**
- 文件存在性校验在 `ToolExecutor` 分发前执行（统一入口）
- `Read` 前置校验依赖 `AgentSession` 维护的 `filesReadThisTurn: Set<String>`，每次 `Read` 成功时追加，每个
  turn 开始时清空
- `modificationStamp` 校验已有实现，此处补充 `Read` 前置校验

**修改后自动 `readLints`（⏳ 规划中）：**

`Edit` 或 `Write` 成功执行后，`ToolExecutor` 自动对被修改文件静默运行 `readLints`，将诊断结果追加到
tool result 中：

| 结果 | 行为 |
|------|------|
| 有新 ERROR | toolResult 尾部追加 `⚠️ 该文件修改后存在 N 个编译错误，请检查并修复:` + 错误列表 |
| 有新 WARNING | 追加 `该文件修改后有 N 个警告:` + 警告列表（最多 5 条） |
| 无新问题 | 不追加额外内容 |

**约束：**
- 静默执行：不创建额外 ToolCallCard，结果仅作为 tool result 的附加文本
- 对比增量：记录修改前文件的 lint 数量，只报告**新增**的问题
- 不阻塞：`readLints` 快速返回（IDE 内存数据），不影响 Agent Loop 节奏

**回归测试智能提示（⏳ 规划中）：**

`Edit`/`Write` 成功后，根据文件路径附加测试建议：

| 修改文件路径模式 | 附加提示 |
|-----------------|---------|
| `src/main/**/*.kt`、`src/main/**/*.java` | `提示：修改了 {fileName}，建议运行相关测试。如用 Gradle：./gradlew test --tests "*{ClassName}*"` |
| `src/test/**/*.kt`、`src/test/**/*.java` | `提示：修改了测试文件 {fileName}，建议运行：./gradlew test --tests "{TestClassName}"` |
| `build.gradle.kts`、`build.gradle` | `⚠️ 修改了构建配置，建议运行 ./gradlew build 验证` |

**改动影响范围分析（⏳ 规划中）：**

`Edit`/`Write` 成功后，若修改涉及方法名/类名变更（通过简单正则匹配 `fun/class/val/var` 后的标识符变更），自动
`Grep` 搜索该标识符在项目中的引用，结果追加提示：

```
{oldSymbol} 在 N 个文件中仍有引用: file1.kt:30, file2.kt:55...
请确认这些引用是否需要联动修改。
```

**关键操作确认（⏳ 规划中）：**

对以下高影响操作，`ToolExecutor` 在执行前将 Agent 状态切换为 `AWAITING_APPROVAL`，弹出确认对话框：

| 操作类型      | 触发条件                                                            | 确认提示                                       |
|-----------|-----------------------------------------------------------------|--------------------------------------------|
| 公共 API 变更 | `Edit`/`Write` 修改了方法签名（检测 `fun ` 行变更）且文件被 ≥3 个其他文件引用            | `将修改公共方法 {methodName}。Grep 发现 N 处引用。确认执行？` |
| 新增依赖      | `Edit`/`Write` 在 `build.gradle.kts` 中新增 `implementation(...)` 行 | `将在构建配置中新增依赖。确认？`                          |
| 大范围修改     | 同一 turn 中累计修改 ≥5 个文件                                            | `本 turn 已修改 M 个文件。是否继续？建议考虑用 /plan 拆分。`    |
| 删除文件      | `Bash` 命令中包含 `rm ` 且目标在项目目录内                                    | `将删除文件 {path}。确认？`（已有危险命令确认，此处补充文件路径识别）    |

**约束：** 关键操作确认复用现有审批 UI（`CountDownLatch` + Dialog），与 Shell 危险命令确认共用同一机制。确认无超时，等待用户手动处理。

**同类代码自动参考（⏳ 规划中）：**

`Read` 成功返回后，Agent Loop 自动分析文件特征并附加风格参考：

```
Read 工具执行成功后
  → 分析文件特征（缩进风格、命名模式、检测到的框架/工具类）
  → 用 FilenameIndex 找同目录下同扩展名的其他文件（取 Top-3）
  → tool result 底部追加:
     "📋 风格参考: 该文件使用 {indent} 缩进，方法名 {naming}。
      同目录类似文件: {sibling1}, {sibling2}, {sibling3}（可用 Read 查看）"
```

LLM 在修改这个文件前就有了明确的风格参照，减少风格不一致的方案错误。

---

### 2.4 ChatViewModel

**职责：** 聊天页面的 UI 状态管理——消息列表、流式 token buffer、发送逻辑。

```
ChatViewModel
├── messages: ObservableList<ChatMessage>  // UI 绑定列表（只读）
├── streamingToken: ObservableString       // 当前流式 token（UI 绑定）
├── session: AgentSession                  // 当前绑定的会话
├── inputState: InputState                 // 输入区域状态
│
├── sendMessage(text: String, attachments: List<FileRef>, images: List<ImageRef>)
│   → 调用 AgentLoop.run() → collect AgentEvent → 更新 messages/streamingToken
├── cancelGeneration()                     // 停止按钮
├── addFileRef(ref: FileRef)               // 添加文件引用（手动或选中）
├── removeFileRef(ref: FileRef)
├── updateSelectionRef(file: String, lines: IntRange?, content: String)  // 更新选中引用
├── clearSelectionRef()                    // 取消选中时清除
├── addImage(image: ImageRef)              // 粘贴图片
├── removeImage(imageId: String)
│
├── clearSession()                          // 清空当前会话（/clear 和 /new 行为一致）
│   → session.messages.clear()
│   → session.compactSummary = null, session.compactCount = 0
│   → session.plan = null
│   → session.totalTokens 归零
│   → 复用当前 session.id，不新建文件
│
├── rollbackToMessage(messageId: String)     // 回退：标记 messageId 之后的消息 deleted=true
├── undoRollback()                           // 撤销回退：恢复最近回退的消息 deleted=false
└── hasPendingRollback: Boolean              // 是否存在可撤销的回退
│
└── InputState:
    ├── manualRefs: List<FileRef>          // @file 手动引用（可多个）
    ├── selectionRef: FileRef?             // 选中代码引用（仅一个）
    ├── images: List<ImageRef>             // 粘贴的图片（可多个，单次 ≤ 5 张）
    └── tokenCount: Int                    // 当前输入估算 token 数（不含图片）

ImageRef:
├── id: String (UUID)
├── fileName: String                       // 剪贴板元数据提取，fallback="clipboard.png"
├── mimeType: String                       // image/png | image/jpeg | image/gif | image/webp | image/bmp
├── sizeBytes: Long                        // 原始大小，≤ 5MB
├── base64Data: String                     // Base64 + data: URL 前缀
├── thumbnail: BufferedImage               // 48×48 缩略图，用于 UI tag

FileRef:
├── filePath: String
├── displayName: String                    // 如 "UserService.kt:40-60"
├── lineStart: Int?
├── lineEnd: Int?
├── content: String                        // 注入 LLM 的文件内容
└── source: MANUAL | SELECTION

ChatMessage:
├── id: String
├── type: USER_TEXT | AGENT_TEXT | TOOL_CALL | TOOL_RESULT | ERROR | SYSTEM
├── content: String                        // Markdown 源码
├── toolCall: ToolCallUIData?              // TOOL_CALL 类型的额外数据
├── timestamp: Instant
└── tokenDelta: TokenDelta?                // AGENT_TEXT 的单条消息级 token 增量

// TokenDelta 是单条消息的增量记录。TokenUsage（见 2.2 AgentSession）是整个 Session 的累计聚合。
// Session.totalTokens = Σ(TokenDelta) + compact 丢弃消息的估算值。

ToolCallUIData:
├── toolName: String
├── parameters: Map<String, Any>
├── state: PENDING | AWAITING_APPROVAL | EXECUTING | DONE | ERROR | TIMEOUT | REJECTED | CANCELLED
├── result: String?
├── durationMs: Long?
└── planStepId: String?                    // 如果属于某个 Plan Step
```

---

### 2.5 ChatBubbleRenderer

**职责：** 将 ChatMessage 渲染为 Swing 组件。

```
ChatBubbleRenderer
├── render(msg: ChatMessage): JComponent
│   → 根据 msg.type 分发：
│     USER_TEXT   → 右对齐蓝色气泡（JTextPane, HTML）
│     AGENT_TEXT  → 左对齐 Markdown 气泡（BlockSequence）
│     TOOL_CALL   → ToolCallCard
│     ERROR       → 红色错误气泡 + [重试]
│     SYSTEM      → 居中灰色分隔线
│
├── renderStreaming(markdownText: String): JComponent
│   → 流式 Markdown → BlockSequence（30ms batch flush）
│
└── BlockSequence:
    ├── 内部结构：List<Block>
    │   Block = TextBlock | CodeBlock | HeaderBlock | ListBlock | QuoteBlock
    ├── TextBlock   → JTextPane (HTML, 段落/加粗/斜体/行内代码)
    ├── CodeBlock   → EditorTextField (语法高亮, 只读, viewer=true)
    ├── HeaderBlock → JTextPane (大号字体)
    ├── ListBlock   → JTextPane (项目符号/编号列表)
    └── QuoteBlock  → JTextPane (灰色左边框)
```

---

### 2.6 ToolCallCard & PlanCard

```
ToolCallCard
├── 构造: ToolCallCard(toolName: String, params: String, initialState: ToolCallState)
├── setState(state: ToolCallState, result: String?, durationMs: Long?)
├── setRejected()                          // 用户拒绝审批
├── setCancelled()                         // 用户取消
├── isExpanded: Boolean = false            // 折叠/展开，默认折叠。AWAITING_APPROVAL 始终 true（不可折叠）；EXECUTING 始终 true（不可折叠）
├── toggleExpanded()                       // 切换折叠/展开。头部点击触发，箭头 ▾/▶ 切换。AWAITING_APPROVAL/EXECUTING 状态下忽略操作
├── renderDiff(oldText: String, newText: String)  // ⏳ 规划中：Edit 成功后渲染可视化 Diff
│                                               //   用 SimpleDiff（LCS 算法），ADD 绿色/DEL 红色/CTX 灰色
└── 渲染: JPanel (带箭头 + 状态图标 + 参数区 + 结果滚动区(max-height=240px) + 可视化 Diff + 底部耗时。折叠态仅显示头部)

PlanCard
├── 构造: PlanCard(plan: Plan)
├── setStepState(stepId: String, state: StepState)   // 更新单步状态
├── setCurrentStepIndex(index: Int)                  // 高亮当前步骤
├── isExpanded: Boolean = false                      // 折叠/展开，默认折叠。EXECUTING 状态下自动展开且忽略折叠操作（强制展开）
├── toggleExpanded()                                 // 切换折叠/展开。折叠态显示标题行+任务摘要+当前执行中步骤（EXECUTING），无执行中步骤时仅显示标题+摘要+进度
├── onStepDeleted: ((stepId: String) -> Unit)?       // 用户删除单步回调 → PlanExecutor.deleteStep()
└── 渲染: JPanel (计划摘要 + 步骤列表，单步行末 [✕] 仅 PENDING 和 ERROR 状态可见)
// PlanCard 仅负责 UI 展示步骤列表和 PENDING 步骤行末的单步删除入口。不提供暂停/继续/删除计划等全局按钮——暂停/继续跟随全局发送/停止按钮（用户点发送按钮后变为停止按钮 ⏹，点击停止即暂停）。
// 计划任务管理由 LLM 通过 listSteps/deleteStep/reorderSteps/markStepDone 工具自主完成。
// 头部可点击折叠/展开步骤列表。EXECUTING 状态下不可折叠（确保用户可见当前执行步骤）。全部步骤 DONE/DELETED 后 PlanCard 消失。
```

---

### 2.7 PlanExecutor

**职责：** 计划执行器——生成计划、自动执行步骤、持久化。默认自动连续执行，用户可随时暂停干预。

**生命周期：** 创建计划后自动开始，逐步执行直到全部 DONE 或被用户终止（DELETED）。

**入口：** 用户 `/plan` 命令 或 LLM 通过 `createPlan` 工具主动创建。两者触发 `generatePlan()` 流程一致。

```
PlanExecutor
├── 构造: PlanExecutor(session: AgentSession, agentLoop: AgentLoop)
├── generatePlan(task: String): Plan       // /plan 或 createPlan → LLM 输出 → 4 层解析 → Plan
├── createPlanFromTool(task: String, steps: List<PlanStepInput>): Plan  // LLM 通过 createPlan 工具主动创建
├── executeNextStep(): StepResult          // 自动执行下一步，完成后自动继续
├── pause()                                // 暂停（内部 API，触发入口为全局停止按钮 ⏹，非 PlanCard 独立按钮）→ 完成当前 Step 后停止自动推进，Plan 保持 EXECUTING
├── resume()                               // 继续（内部 API，触发入口为全局发送按钮，非 PlanCard 独立按钮）→ 恢复自动连续执行
├── deletePlan()                           // 终止（内部 API，由 LLM 通过工具自主调用）→ 所有剩余步骤标记 DELETED，Plan 标记 DELETED
├── deleteStep(stepId: String)             // 用户或 LLM 删除单步（仅 PENDING），剩余步骤自动继续
├── reorderSteps(stepIds: List<String>)    // LLM 重排剩余 PENDING 步骤顺序
├── listSteps(): List<PlanStep>            // LLM 查看当前计划状态
├── currentPlan: Plan?                     // 当前计划
├── isPaused: Boolean                      // 是否处于暂停状态（非 Plan 状态，Plan 保持 EXECUTING）
│
└── Plan 解析流程 (4 层):
    1. 提取 ```json``` → Gson → Plan
    2. 搜索裸 { ... } → Gson → Plan
    3. 正则 "Step N:" / "步骤 N:" → 文本拆分 → Plan
    4. 原始文本 → Plan(summary=原始文本, steps=[Step(description=原始文本)])

Plan:
├── id: String
├── status: PENDING | EXECUTING | DONE | DELETED
├── summary: String                        // 一句话描述
├── steps: List<PlanStep>
├── currentStepIndex: Int                  // 当前执行到第几步 (0-based)
├── createdAt: Instant
└── updatedAt: Instant

PlanStep:
├── id: String
├── description: String                    // 步骤描述
├── tool: String                           // 建议工具名。LLM 应优先使用但不强制
├── files: List<String>                    // 涉及文件（含行号 "UserService.kt:40-60"）
├── status: PENDING | EXECUTING | DONE | DELETED
├── result: String?                        // 执行结果
└── retryCount: Int

StepResult:
├── stepId: String
├── status: DONE | DELETED
├── output: String                         // LLM 输出
└── toolCalls: List<ToolCallRecord>        // 该步骤使用的工具调用记录
```

---

### 2.8 SessionStore & SessionManager

```
SessionStore
├── save(session: AgentSession): String    // 返回文件路径，原子写入（.tmp → ATOMIC_MOVE）
├── load(sessionId: String): AgentSession? // 读取 + 解析 + auto-repair
├── delete(sessionId: String)
├── listAll(): List<SessionIndex>          // 读 index.json
├── acquireLock(sessionId): FileLock?      // 跨进程写锁（FileChannel.tryLock）
└── releaseLock(sessionId)

SessionManager
├── createSession(title: String): AgentSession  // title 初始为临时值（如"新会话"），第一条消息后异步生成
├── getSession(id: String): AgentSession?
├── getAllSessions(): List<SessionIndex>
├── deleteSession(id: String)
├── setCurrentSession(session: AgentSession)  // Chat 页面绑定
├── currentSession: AgentSession?
├── searchSessions(query: String): List<SessionIndex>  // title.contains()
├── getTotalTokenUsage(range: DAY | MONTH | ALL): TokenAggregation
└── generateTitle(sessionId: String)           // 异步 LLM 调用生成标题（≤20字, max_tokens=64）

SessionIndex:
├── id: String
├── title: String
├── createdAt: Instant
├── updatedAt: Instant
├── messageCount: Int
├── totalTokens: Long
// totalTokens = inputTokens + outputTokens 累加值，从 API usage 返回值获取
├── toolCallCount: Int
├── parentId: String?                  // 子会话关联父会话 ID，null 表示顶级会话
└── hasActivePlan: Boolean

TokenAggregation:
├── periods: List<TokenPeriod>
├── grandTotal: Long
└── estimatedCost: BigDecimal

TokenPeriod:
├── date: LocalDate
├── inputTokens: Long
└── outputTokens: Long
```

---

### 2.9 SkillManager

```
SkillManager
├── loadSkills(basePath: Path): List<Skill>    // 扫描 .code-assistant/skills/、.claude/skills/、~/.claude/skills/、.codex/skills/、~/.codex/skills/。同名 Skill 按 Code-Assistant > Claude > Codex 优先级，同平台项目级优先于用户级，先扫到的覆盖后扫到的。安装 Skill 统一写入 .code-assistant/skills/
├── getEnabledSkills(): List<Skill>
├── enableSkill(name: String)
├── disableSkill(name: String)
├── getByCommand(command: String): Skill?               // 按 /command 查找（如 /review）
├── getSystemPromptExtension(): String               // Skill 列表（名称 + 截断描述），注入 System Prompt 末尾，不包含正文
└── getTriggeredContent(skills: List<Skill>): String // LLM 调用 Skill 工具时获取 SKILL.md 正文，作为消息注入 conversation

Skill:
├── name: String
├── description: String
├── command: String                   // YAML `command` 字段（如 "review"）
├── requiredTools: List<String>       // YAML `tools` 字段
├── content: String                   // SKILL.md 正文
├── enabled: Boolean
└── hasMissingTools: Boolean          // 声明的工具是否全部存在于 ToolRegistry
```

---

### 2.10 McpManager & McpConfigStore

```
McpManager
├── loadConfig(): McpConfig            // 从 McpConfigStore 读取
├── connect(serverId: String)          // 启动子进程 + MCP 握手
├── disconnect(serverId: String)       // shutdown → destroy
├── getTools(serverId: String): List<AgentTool>
├── testConnection(serverId: String): ConnectionResult  // initialize 握手 + 5s 超时
├── dispose()                          // 遍历所有 server → shutdown → destroyForcibly
│
├── onServerStateChanged: ((serverId, McpServerState) -> Unit)?
│
└── ConnectionResult:
    ├── success: Boolean
    ├── toolCount: Int?
    ├── errorMessage: String?
    └── latencyMs: Long?

McpServerState:
    CONFIGURED | INITIALIZING | RUNNING | CRASHED | STOPPED | DISCONNECTED | ERROR | INIT_ERROR
// INIT_ERROR：初始化握手超时（退避重试 3 分钟后仍失败），需用户手动 [重连]

McpConfigStore
├── load(): McpConfig
├── save(config: McpConfig)
└── configPath: Path                     // <project>/.code-assistant/mcp-config.json

McpConfig:
└── servers: List<McpServerConfig>

McpServerConfig:
├── id: String
├── command: String
├── args: List<String>
├── env: Map<String, String>            // 敏感值建议环境变量注入
└── enabled: Boolean
```

---

### 2.11 ChatToolWindow（顶层容器）

```
ChatToolWindow
├── 构造: ChatToolWindow(project: Project)
├── tabBar: TabBar                       // 顶部导航
├── pageContainer: JPanel(CardLayout)    // 页面切换容器
├── pages: Map<PageId, Page>             // 懒加载页面缓存
│
├── navigateTo(pageId: PageId)           // 切换页面
├── getCurrentPage(): PageId
├── registerPage(pageId: PageId, factory: () -> Page)
│
├── onPageChanged: ((PageId) -> Unit)?   // 页面切换回调
└── dispose():                           // 清理顺序见 ../DESIGN.md 6.2

PageId: WELCOME | CHAT | SESSIONS | TOKEN_USAGE | MCP | SKILLS | SETTINGS

所有页面直接继承 JPanel，通过 CardLayout 管理切换。页面懒加载，首次点击对应 Tab 时创建。
```

---

### 2.12 TabBar

```
TabBar
├── 构造: TabBar(pages: List<PageId>, onSelect: (PageId) -> Unit)
├── setSelected(pageId: PageId)
├── setBadge(pageId: PageId, text: String?)    // 页面标签上的角标（如计划执行中数）
├── setEnabled(pageId: PageId, enabled: Boolean)  // Welcome 页面控制导航禁用
├── getSelected(): PageId
└── 渲染: JPanel (FlowLayout, 等宽按钮)
```

---

### 2.13 ChatPage（聊天主页面）

```
ChatPage extends JPanel
├── 构造: ChatPage(sessionManager, chatViewModel)
├── messageList: JScrollPane             // 消息列表
├── inputArea: ChatInputArea             // 输入区域
├── renderMessage(msg: ChatMessage)      // 渲染一条消息
├── scrollToBottom()                     // 自动滚动
├── onAutoScrollPaused: Boolean          // 用户上滚 > 50px → 暂停 → 显示 ↓ 按钮
│
└── 组件树:
    ChatPage (BorderLayout)
      ├── NORTH: 标题行（会话标题 + 新会话/清空按钮）
      ├── CENTER: JScrollPane
      │         └── messageContainer (BoxLayout.Y_AXIS)
      │               ├── ChatBubble (历史消息)
      │               ├── ToolCallCard (工具调用)
      │               ├── PlanCard (计划)
      │               └── StreamingBubble (流式渲染中)
      └── SOUTH: ChatInputArea
                 ├── TagsRow (FlowLayout: 文件+图片混合 tag, 输入框上方)
                 ├── JTextArea (文本区域, 无边框)
                 └── BottomBar: [+]按钮 + @提示 + [→]发送
```

### 2.14 ChatInputArea（输入区域组件）

```
ChatInputArea
├── 构造: ChatInputArea(viewModel: ChatViewModel)
├── textArea: JTextArea                       // 输入框（无边框，自适应 2-10 行）
├── tagsRow: JPanel                           // Tags 行（FlowLayout，文件+图片混合排列，位于输入框上方）
├── addFileButton: JButton                    // [+] 按钮 → 弹出文件选择 Popup
├── sendButton: JButton                       // [→] 发送按钮
├── hintLabel: JLabel                         // "@ 选择文件" 提示
│
├── onSlashTrigger(): Unit                    // 输入 `/` → 弹出指令列表 Popup
│   → 内容: 内置指令 (/plan, /clear) + 已启用 Skills 的 command
│   → ↑↓ 选择，Enter 确认，Esc 关闭，输入实时过滤
│
├── onAtTrigger(): Unit                       // 输入 `@` 或点击 [+] → 弹出文件搜索 Popup
│   → 文件按子目录分组展示，支持嵌套目录
│   → ↑↓ 选择，Enter 确认 → 添加 tag 到 Tags 行
│
├── handlePopupKeyDown(event: KeyEvent): Boolean  // Popup 键盘导航 (↑↓ Enter Esc)
│   → 返回 true 表示已处理（消费事件）
│
├── onPasteImage(image: BufferedImage)        // 剪贴板图片粘贴回调
│   → 缩放（长边 max 2048px，保持比例）
│   → PNG 编码 → Base64 → data:image/png;base64,...
│   → ChatViewModel.addImage(ImageRef)
│   → 渲染 48×48 缩略图 tag [🖼 filename ✕]
│
└── 剪贴板监听：
    → Toolkit.getDefaultToolkit().systemClipboard
    → 检测 DataFlavor.imageFlavor
    → Ctrl+V / Cmd+V 时若剪贴板含图片 → onPasteImage()
    → 支持格式: PNG / JPEG / GIF / WebP / BMP
    → 单张 ≤ 5MB，单次粘贴 ≤ 5 张
```

---

### 2.15 ApprovalDialog（审批弹窗）

**职责：** 工具执行前的用户审批，阻塞 Agent Loop 等待用户操作。

```
ApprovalDialog
├── 类型: MODAL（阻塞父窗口），可拖动
├── 布局: BorderLayout
│   ├── NORTH: JLabel 标题行
│   │   ├── 首次审批: "允许 {toolName} 执行？"（蓝色图标）
│   │   ├── 危险命令: "⚠️ 危险操作"（红色图标，不可跳过）
│   │   └── 关键操作: "确认 {操作类型}？"（黄色图标）
│   ├── CENTER: JTextPane（只读）
│   │   ├── 说明行（如"Code Assistant 将执行: rm -rf /path"）
│   │   └── 代码块展示完整命令/参数
│   └── SOUTH: JPanel(FlowLayout.RIGHT) 按钮行
│       ├── [允许一次] — 执行本次，下次再弹（默认按钮）
│       ├── [允许此会话] — 仅首次审批场景，会话内后续不弹
│       ├── [拒绝] — 不执行，ToolCallCard → REJECTED
│       └── 危险命令无"允许此会话"按钮
├── 超时: 无超时（CountDownLatch 永久等待。非 bug：Agent Loop 运行在后台线程池，阻塞不占 EDT；审批弹窗是模态 Dialog，用户离开前必须处理。加超时反而引入竞态——超时后 Agent 继续执行但用户可能刚好回来点了批准）
│   └── Agent 状态保持 AWAITING_APPROVAL，Agent Loop 阻塞在 latch.await()
└── 生命周期: dispose() 时必须 countDown() 释放 latch，防止 EDT 死锁
```

**审批触发规则：**

| 场景         | 触发条件                                                | 审批类型           |
|------------|-----------------------------------------------------|----------------|
| 首次工具使用     | 每个会话每种工具首次调用                                        | 首次审批（可"允许此会话"） |
| Shell 危险命令 | `rm -rf /`, `git push --force`, `sudo`, `chmod 777` | 危险命令确认（不可跳过）   |
| 公共 API 变更  | Edit/Write 修改 `public`/`open` 方法签名                  | 关键操作确认（⏳ 规划中）  |
| 大范围修改      | 同一 turn 修改 ≥5 个文件                                   | 关键操作确认（⏳ 规划中）  |
| 文件删除       | Bash 含 `rm ` 且目标在项目内                                | 关键操作确认（⏳ 规划中）  |

---

## 三、messageBus 事件契约

| Topic                   | 消息类型              | 字段                                                                     | 发布者            | 订阅者                    |
|-------------------------|-------------------|------------------------------------------------------------------------|----------------|------------------------|
| `SessionChanged`        | `SessionEvent`    | `sessionId: String, type: CREATED\|UPDATED\|DELETED`                   | SessionManager | SessionsPage, ChatPage |
| `AgentStateChanged`     | `AgentStateEvent` | `sessionId: String, oldState: AgentState, newState: AgentState`        | AgentSession   | ChatPage, TabBar       |
| `TokenUsageUpdated`     | `TokenUsageEvent` | `sessionId: String, delta: TokenDelta`                                 | AgentSession   | TokenUsagePage         |
| `McpServerStateChanged` | `McpServerEvent`  | `serverId: String, oldState: McpServerState, newState: McpServerState` | McpManager     | McpPage                |
| `ApiKeyValidated`       | `ApiKeyEvent`     | `state: VALID\|INVALID\|UNKNOWN`                                       | WelcomePage    | ChatPage, SettingsPage |
| `PlanStateChanged`      | `PlanStateEvent`  | `sessionId: String, planStatus: PlanStatus, currentStep: Int`          | PlanExecutor   | ChatPage, SessionsPage |
| `PageSwitched`          | `PageEvent`       | `from: PageId, to: PageId`                                             | ChatToolWindow | 所有 Page                |

---

## 四、线程模型总览

```
┌─────────────────────────────────────────────────────────┐
│ EDT (Event Dispatch Thread)                              │
│  ├─ 所有 Swing 组件操作（创建/更新/repaint）               │
│  ├─ ChatBubbleRenderer.render()→JComponent               │
│  ├─ ToolCallCard.setState() → UI 更新                    │
│  ├─ invokeLater { chatViewModel.messages.add(...) }      │
│  ├─ WriteCommandAction.runWriteCommandAction { ... }     │ ← invokeAndWait 进入
│  └─ 事件总线 publish（project.messageBus.syncPublisher）  │
├─────────────────────────────────────────────────────────┤
│ ApplicationManager.executeOnPooledThread()                │
│  ├─ AgentLoop.run()                                      │
│  ├─ ToolRegistry 非 EDT 工具执行                         │
│  │   ├─ Read / Glob / Grep / ReadLints │
│  │   ├─ Bash (ProcessHandler, listener 在 bg thread) │
│  │   └─ Task → 新 AgentLoop                        │
│  ├─ SessionStore.save() (JSON 写入)                      │
│  └─ McpManager 连接/握手                                  │
├─────────────────────────────────────────────────────────┤
│ Swing Timer (javax.swing.Timer, 运行在 EDT)               │
│  └─ ChatBubbleRenderer 30ms batch flush                  │
├─────────────────────────────────────────────────────────┤
│ ProcessHandler listener                                  │
│  └─ Bash onTextAvailable → batch buffer → Timer(100ms)│
│                                                    → EDT │
└─────────────────────────────────────────────────────────┘
```

**跨线程通信规则：**

- Background → UI：`ApplicationManager.invokeLater { ... }` 或 `SwingUtilities.invokeLater { ... }`
- UI → Background：`ApplicationManager.executeOnPooledThread { ... }`
- UI 等待 Background：`invokeAndWait` 用于 Write/Edit
- Background 等待 UI：`CountDownLatch` 用于审批弹窗（无超时——Agent Loop 在后台线程，不阻塞
  EDT；审批弹窗模态，用户必响应；加超时反而引入竞态风险）
- 流式批量 flush：Swing Timer 在 EDT 上合并连续 token

---

## 五、数据流时序

### 5.1 用户发送消息

```
ChatInputArea.enterPressed()
  → ChatViewModel.sendMessage(text, attachments, images)
    → buildContext(text, attachments, images)  // 组装 @file + 选中代码 + 图片到 userContent
    → AgentSession.addMessage(USER, userContent)
    → AgentSession.setState(PROCESSING)
    → AgentLoop.run(userContent)
        → while (turn < maxTurns && !cancelled):
            → if (stopReason != "max_tokens") turn++  // 仅用户消息触发的正常 API 调用计数，续写不增加 turn
            → 构建 params：
                system = SystemPromptBuilder.build(toolRegistry, skillManager)
                messages = session.messages       // 每轮从 session.messages 重建
                tools = toolRegistry.toToolDefinitions()
            → compactIfNeeded(system, messages, tools, modelContextLimit)  // 估算实际总 token（system + messages + tools），超 70% 阈值则压缩 messages
                → 如触发压缩：独立 API 调用（不带 tools, max_tokens=1024）生成摘要
                → session.messages = [摘要消息, ...近期原文(≥2轮)]
                → 重建 params.messages = session.messages
                → SessionStore.save()
            → client.messages().createStreaming(params)
              .forEach { chunk →
                when (chunk):
                  ContentBlockDelta.text →
                    emit(TextDelta(text))       // → ViewModel → UI
                  ContentBlockStart.toolUse →
                    emit(ToolCallStarted(name))  // → ToolCallCard(PENDING)
                  MessageStop →
                    when (stopReason):
                      "end_turn"       → emit(TurnCompleted(usage)) → 退出 while
                      "max_tokens"     → continueStreak++
                                         if (continueStreak > maxAutoContinue):
                                           emit Error + 退出 while
                                         else:
                                           params.messages += UserMessage("继续") → continue while
                                         （续写不增加 turn：while 顶部跳过 turn++。"继续"不持久化，
                                         仅当前会话生命周期内有效，重启 IDE 后不自动续写）
                      "stop_sequence"  → emit(TurnCompleted(usage)) → 退出 while（尾部标注）
              }
            → for each toolUse:
                if (requiresApproval):
                  emit(AgentState.AWAITING_APPROVAL)
                  latch = CountDownLatch(1)
                  invokeLater { showDialog() → latch.countDown() }
                  latch.await()  // 无超时，等待用户手动处理
                if (approved):
                  ToolCallCard.setState(EXECUTING)
                  result = tool.execute(params, session, onOutput)
                  ToolCallCard.setState(DONE/ERROR)
                  emit(ToolCallCompleted(name, result))
                  params = params.add(toolCallResult)

    → 流式中断处理：
        主动停止（三件事同时做）:
          → cancelled=true （退出 Agent while 循环）
          → client.close() （取消当前 HTTP 请求）
          → destroyForcibly()：遍历当前 AgentSession 的 runningProcesses 销毁子进程
            - 杀其他 Agent 的进程
            - 杀 MCP Server 进程
          → 已累积的 BetaMessageAccumulator 内容持久化到 session.messages
          → 当前 streamingBuf 中未 flush 的内容丢弃
          → 已解析但未执行的 tool call → CANCELLED，不持久化
          → Agent 状态 → IDLE
        被动断连（SocketTimeoutException）:
          → 已渲染内容保留 + 持久化（含尾部 [连接中断] 标注）
          → 用户点击 [重试] → 发送相同 params 重新开始当前 turn
    → AgentSession.setState(IDLE)
    → SessionStore.save(session)
    → messageBus.publish(TokenUsageUpdated)
```

### 5.2 Plan 执行

```
ChatViewModel.sendMessage("/plan 重构 UserService", ...)
  → PlanExecutor.generatePlan(task)
    → AgentLoop.run("plan-request:" + task)  // 生成计划
    → parsePlan(llmOutput)  // 4 层解析
    → PlanCard 渲染
    → 自动开始执行

PlanExecutor.executeNextStep() 自动循环:
  → 检查 isPaused，若 true 则跳过本次执行
  → 获取 nextStep (currentStepIndex 对应的 PENDING step)
  → 构建 stepPrompt: "执行步骤: {step.description}。文件: {step.files}。工具: {step.tool}"
  → AgentLoop.run(stepPrompt)
  → 更新 step.status + step.result
  → currentStepIndex++
  → 自动继续执行下一步
  → 全部 DONE → PlanCard 消失

用户暂停:
  → 点全局停止按钮(⏹) → PlanExecutor.pause() → isPaused = true
  → PlanExecutor 完成当前 Step 后停止
  → 点全局发送按钮 → PlanExecutor.resume() → isPaused = false → 恢复自动循环
  → LLM 调用 deleteStep 删除所有步骤 → PlanExecutor.deletePlan() → 剩余步骤 DELETED，Plan DELETED

LLM 通过工具管理计划:
  listSteps()    → 查看步骤状态
  deleteStep(id) → 删除 PENDING 步骤，执行时自动跳过
  reorderSteps([id...]) → 重排 PENDING 步骤顺序
```

---

## 六、持久化 JSON Schema

### 6.1 Session JSON

```json
{
  "id": "uuid",
  "parentId": null,
  "title": "重构 UserService",
  "createdAt": "2026-06-24T14:30:00Z",
  "updatedAt": "2026-06-24T14:45:00Z",
  "messages": [
    {
      "id": "msg-1",
      "role": "USER",
      "content": "帮我重构 UserService",
      "timestamp": "2026-06-24T14:30:00Z"
    },
    {
      "id": "msg-2",
      "role": "ASSISTANT",
      "content": "好的，让我先读取文件...",
      "timestamp": "2026-06-24T14:30:05Z",
      "toolCalls": [
        {
          "id": "tooluse-1",
          "name": "Read",
          "parameters": {
            "filePath": "UserService.kt",
            "startLine": null,
            "endLine": null
          },
          "result": "...",
          "state": "DONE",
          "durationMs": 15
        }
      ],
      "tokenUsage": {
        "inputTokens": 1200,
        "outputTokens": 400
      }
    }
  ],
  "plan": {
    "id": "plan-1",
    "status": "EXECUTING",
    "summary": "重构 UserService.findById 为 suspend 函数",
    "currentStepIndex": 1,
    "steps": [
      {
        "id": "step-1",
        "description": "读取 UserService.kt",
        "tool": "Read",
        "files": [
          "UserService.kt:40-60"
        ],
        "status": "DONE",
        "result": "成功读取 156 行",
        "retryCount": 0
      },
      {
        "id": "step-2",
        "description": "修改方法签名为 suspend fun",
        "tool": "Edit",
        "files": [
          "UserService.kt"
        ],
        "status": "PENDING",
        "retryCount": 0
      }
    ],
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:35:00Z"
  },
  "totalTokens": {
    "inputTokens": 5200,
    "outputTokens": 3000
  },
  "compactSummary": "用户任务为重构 UserService.findById 为 suspend 函数。已完成：读取 UserService.kt 和 UserController.kt。当前正在修改方法签名...",
  "compactCount": 2
}
```

### 6.2 Session Index

```json
[
  {
    "id": "uuid",
    "title": "重构 UserService",
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:45:00Z",
    "messageCount": 12,
    "totalTokens": 8200,
    "toolCallCount": 5,
    "hasActivePlan": true,
    "parentId": null
  }
]
```

### 6.3 MCP Config

```json
{
  "servers": [
    {
      "id": "mysql",
      "command": "npx",
      "args": [
        "-y",
        "@anthropic/mcp-server-mysql",
        "--db",
        "mydb"
      ],
      "env": {
        "DB_HOST": "localhost"
      },
      "enabled": true
    }
  ]
}
```

---

## 七、Settings 持久化

Agent 设置已迁移到 IDE SettingsConfigurable（Settings > Tools > Code Assistant），与代码补全、Commit
生成统一管理。面板内 Settings 页面简化为关于页 + 快捷键参考。存储复用 `PasswordSafe`（API Key）和
`PropertiesComponent`（其余配置）。

```
AppSettingsService（SettingsConfigurable 读写）
├── apiKey: String                               // PasswordSafe 存储
├── model: String = "deepseek-v4-pro"            // 下拉选择（V4 Flash / V4 Pro）
├── completionEnabled: Boolean = true             // 代码补全开关
├── commitPrompt: String                          // Commit 消息模板（{diff} 占位）
├── maxAgentTurns: Int                      // 最大轮次，默认 20。每轮 = 一次 API 调用（含续写）。0 时内部转为 Int.MAX_VALUE（不限轮次）
└── maxConcurrentAgents: Int (default 3)          // 多 Agent 并发上限
```

---

## 八、System Prompt

以下为 AgentLoop 构建 MessageCreateParams 时使用的 system prompt 完整目标态。标注 ⏳
规划中的段落暂不注入，其余段落原文直接使用，不可改写。

> **语言选择说明：** System Prompt 使用中文，因为 DeepSeek V4 对中英文混合 prompt 支持良好，
> 且目标用户为中文开发者。如后续支持其他语言用户，可抽取为 i18n 模板（`AiAssistantBundle` 已预留国际化机制）。

### 8.1 Agent 基础 System Prompt

> **设计原理：** 防幻觉规则见 [`agent.md` 第十五节](../docs/agent.md#十五防止-llm-幻觉)，代码验证流程见 [`agent.md` 第十六节](../docs/agent.md#十六agent-代码改动正确性验证)，方案设计原则见 [`agent.md` 第十七节](../docs/agent.md#十七agent-方案正确性验证)。

```
你是 Code Assistant，一个运行在 JetBrains IDE 中的智能编程助手。你可以：
- 阅读项目中的任何文件
- 修改文件内容（精确替换或完整覆盖）
- 执行 Shell 命令
- 列出目录结构
- 搜索代码内容
- 读取 IDE 诊断信息（错误和警告）
- 启动子代理处理子任务

当前项目：{projectName}
项目路径：{projectBasePath}
当前文件：{currentFileName}
<!-- currentFileName 取 IDE 编辑器中最上层可见 Tab 的文件名；无打开文件时为空字符串 -->

## 工具使用原则

1. 先用 Read 或 Glob 获取足够信息，再使用 Write/Edit 修改代码。
2. 修改代码前，先读取目标文件的完整内容或足够上下文。
3. Edit 的 oldString 必须在文件中唯一且精确匹配。如果不确定 oldString，先用 Read 读取目标区域。
4. Shell 命令的工作目录默认为项目根目录。长时间运行的命令（如 gradle build）是正常的，不需要手动终止。
5. 所有文件路径使用项目内相对路径。

## 任务复杂度判断（⏳ 规划中）

在开始执行任务前，先评估复杂度。如果满足以下任一条件，在回复开头列出简要执行计划（目标、步骤、涉及文件）再开始：

- 涉及 3 个以上文件的修改
- 可能需要多次编译/测试验证
- 用户描述中有"重构""迁移""全部""整个项目""所有""统一"等大范围关键词
- 用户要求同时做多件事

如果执行中途发现任务比预期复杂（如实际涉及文件远多于预期），应建议用户使用 /plan 拆分，或直接调用 createPlan 工具创建正式计划。

## 回复风格

- 使用中文回复
- 代码块使用正确的语言标记（```kotlin、```java、```json 等）
- 修改文件前简要说明变更内容
- 执行 Shell 命令前说明命令用途

## 防止幻觉

1. 绝不编造不存在的 API、类名、方法名。引用任何 API 前必须通过 Read 或 Grep 确认其真实存在。
2. 不确定文件是否存在时，先用 Glob 或 Read 确认，不要假设路径。
3. 修改代码前必须用 Read 读取目标区域的真实内容，不要凭记忆或猜测。
4. Shell 命令执行后，先检查退出码和 stderr，再根据实际结果（而非预期结果）决定下一步。
5. 如果信息不足，主动说明"我需要先读取 X 文件来确认"，而不是猜测。

## 代码修改后的验证流程（⏳ 规划中）

每次修改代码后，按以下顺序验证：

1. 如果修改的是 Kotlin/Java 等编译型语言文件，用 readLints 检查是否有新引入的错误。
2. 如果 lints 无错误，考虑运行编译或相关测试（如 ./gradlew build 或对应模块的 test）。
3. 如果测试失败，分析失败原因并修复，不要跳过失败的测试。
4. 如果项目没有现成测试或编译耗时过长，至少用 Read 重新读取修改区域确认改动符合预期。

## 方案设计原则（⏳ 规划中）

在动手修改代码前，先理解项目现状：

1. 如果任务是修改/扩展已有功能，先用 Grep 搜索项目中类似实现（如"项目中其他 Service 类长什么样"），以现有模式为模板。
2. 如果任务是新增功能，先在项目中找一个最相似的文件通读，保持风格一致（命名、结构、错误处理方式）。
3. 优先复用项目已有的工具类、基类、扩展函数，不要自己从头写。
4. 选择方案时遵循项目已有的复杂度水平——如果项目里其他 Service 都是单文件 200 行，你就不该引入多层抽象。
5. 只改和任务直接相关的代码，不要顺便重构不相关的文件。

## 方案自检清单（⏳ 规划中）

每次提出修改方案前，在回复中简要自检：

1. **模式对齐**：项目里有没有类似实现可以参考？我是否遵循了？
2. **最简单方案**：有没有更简单的写法？我是否过度设计了？
3. **影响范围**：这个修改会影响多少调用者？有没有遗漏的联动修改？
4. **破坏性**：是不是 Breaking Change？如果是，用户知道吗？
```

### 8.2 System Prompt 组装逻辑

`AgentLoop` 在构建 `MessageCreateParams` 时，按以下顺序组装 system prompt：

```
systemContent = [
  基础 System Prompt（8.1 节原文，变量替换 projectName/projectBasePath/currentFileName）,

  "## 可用工具\n" + ToolRegistry.generateToolDescriptions(),
  // 每个工具的 name + JSON Schema（由 Anthropic SDK 的 @JsonClassDescription 自动生成）。
  // 工具描述中必须包含上限声明，示例格式:
  // "- Read(filePath: string, startLine?: int, endLine?: int, timeout: int): 读取项目内指定文件的内容。单次最多返回 500 行，超出请用 startLine 分页。"
  // 上限声明规则见 2.3 ToolRegistry > 工具描述中的上限声明。

  SkillManager.getSystemPromptExtension(),
  // "## 可用 Skills\n- code-review: 审查代码质量 (触发词: review, 审查)\n- refactor: 重构代码 (触发词: 重构, refactor)"

  SkillManager.getByCommand(command)?.content,
  // 仅当用户输入 /command 时注入对应的 SKILL.md 正文。
  // 格式: "\n## Skill: {name}\n{content}"
]
```

### 8.3 buildContext() 伪代码

`ChatViewModel.sendMessage()` 调用 `buildContext()` 将用户文本、文件引用、选中代码、图片组装为 LLM 可接受的
message content。

```
fun buildContext(
    text: String,
    attachments: List<FileRef>,   // @file 手动引用 + 选中代码
    images: List<ImageRef>         // 剪贴板图片
): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()

    // 1. 先添加文本 block
    //    文本内容 = 用户原始消息 + 文件引用内容前缀
    val textWithFiles = buildString {
        // 1a. 附件文件内容（每个 @file 一个 block 前缀）
        for (ref in attachments) {
            val header = when (ref.source) {
                SELECTION -> "[Selection from ${ref.displayName}]\n"
                MANUAL   -> "[File: ${ref.displayName}]\n"
            }
            append("$header${ref.content}\n[/${if (ref.source == SELECTION) "Selection" else "File"}]\n\n")
        }
        // 1b. 用户原始消息
        append(text)
    }
    blocks.add(ContentBlock.text(textWithFiles))

    // 2. 再添加图片 blocks（每个图片一个 image block）
    for (img in images) {
        blocks.add(ContentBlock.image(
            ImageSource(base64Data = img.base64Data, mediaType = img.mimeType)
        ))
    }

    return blocks
}
```

**ContentBlock 顺序规则：** 文本 block 在前、图片 blocks 在后。多个图片按粘贴顺序排列。

**去重规则：** 同一文件既被 @file 引用（完整文件）又被选中（部分行）→ attachments 列表中去掉选中引用，只保留完整文件引用，但在
header 中附加行号提示 `[File: UserService.kt — 用户关注行 40-60]`。

**@file glob 上限：** 单次 @file glob 匹配上限 **50 个文件**。超出部分不注入，Glob 工具在返回值中告知
LLM 截断情况（共 N 个文件、当前返回范围、翻页 offset 参数），LLM 自行判断是否需要翻页获取更多文件。

**生命周期（对齐 Claude Code）：** `@file` 内容注入当前轮 USER 消息，持久化到 Session JSON。compact
时与普通消息一同压缩为摘要——文件内容快照**不会**在 compact 后重新注入。LLM 如需再次查看文件，必须通过
`Read` 工具从磁盘重新读取，不应依赖旧消息中的过期内容。详见 [
`agent.md` §八 > compact 后上下文重建](../docs/agent.md#压缩策略)。

## 九、每个 Tool 的 execute() 返回值格式

以下为 `ToolResult.content` 的**精确格式契约**。LLM 收到的就是这些字符串。格式不一致会导致 LLM 行为不可预测。

### Read

```
成功:
[文件: {filePath} ({totalLines} 行 total, {returnedLines} 行已返回)]
{fileContent...}
[文件结束: {filePath}]

截断:
[文件: {filePath} ({totalLines} 行 total, 已截断到 {maxLines} 行)]
{fileContent...}
... (共 {totalLines} 行，已截断到 {maxLines} 行。如需查看剩余内容，请用 startLine 参数分页读取)
[文件结束: {filePath}]

文件不存在:
错误: 文件 "{filePath}" 不存在。请检查路径是否正确。
提示: 使用 Glob 工具查看目录结构。
```

### Write

```
成功:
✅ 文件已写入: {filePath} ({lineCount} 行, {byteCount} 字节)
操作类型: {新建 | 覆盖}

内容过长:
错误: 内容过长 ({actualLines} 行，上限 {maxLines} 行)。请拆分为多次写入或减少内容。

写入失败:
错误: 写入 "{filePath}" 失败: {exceptionMessage}
```

### Edit

```
成功:
✅ 已修改: {filePath}
替换了 {replacedLineCount} 行 → {newLineCount} 行
{修改前 3 行}
---
{修改后 3 行}

oldString 未找到:
错误: 在 "{filePath}" 中未找到 oldString。
提示: 请使用 Read 读取目标区域，确认 oldString 精确匹配文件内容（包括空白字符）。
附近内容({nearbyLineStart}-{nearbyLineEnd}行):
{nearbyContent}

oldString 匹配到 {count} 处:
错误: oldString 在 "{filePath}" 中匹配到 {count} 处，必须唯一。
请选择其中一处，使用更长的 oldString 使其唯一：
- 第 {line1} 行: "{context1}"
- 第 {line2} 行: "{context2}"
- ...（最多显示 5 处）

文件被外部修改:
错误: "{filePath}" 已被外部修改（上次读取 stamp={oldStamp}，当前 stamp={newStamp}）。
请使用 Read 重新读取文件后再试。

空 oldString + 文件不存在:
✅ 文件已创建: {filePath} ({lineCount} 行, {byteCount} 字节)

文件不存在（非新建模式，前置校验拒绝）:
错误: 文件 "{filePath}" 不存在，拒绝执行 Edit。
提示：使用 Glob 查看目录结构。

文件未 Read（前置校验拒绝）:
错误: 请先用 Read 读取 {filePath} 后再修改。
提示: 当前 turn 中该文件未被 Read，拒绝执行 Edit。先用 Read 读取文件内容和 modification stamp 后再试。

自动 readLints 结果（⏳ 规划中，Edit/Write 成功后自动追加）:
⚠️ 该文件修改后存在 2 个编译错误:
  45:12: Unresolved reference: findById [ERROR]
  78:5: Type mismatch: inferred type is String but Unit was expected [ERROR]
```
（无新诊断时不追加）

**UI 展示（⏳ 规划中）：** `Edit` 成功后，ToolCallCard 内联展示 `SimpleDiff` 生成的可视化 diff（ADD 绿色/DEL
红色/CTX 灰色），替换当前的前后 3 行文本对比。

### Bash

**返回值格式（⏳ 强化版，错误优先）：**

```
成功（退出码 0 + stderr 空）:
$ {command}
{stdout}
退出码: 0 | 耗时: {duration}s | {outputLineCount} 行输出

成功但有 stderr（退出码 0 + stderr 非空，构建工具常见）:
$ {command}
{stdout}
⚠️ 命令成功但有以下输出（stderr）:
{stderr}
退出码: 0 | 耗时: {duration}s

超时（timeout 由 LLM 在 tool call 时传入，>0 时生效）:
$ {command}
超时: 命令执行超过 {timeout}s，已强制终止

输出截断:
$ {command}
{stdout}
... (共 {totalLines} 行输出，完整输出见 IDE 工具卡片)
退出码: {exitCode} | 耗时: {duration}s

命令失败（退出码非零，强化标注）:
⚠️ 命令执行失败 (退出码: {exitCode})
STDERR:
{stderr}
STDOUT:
{stdout}
耗时: {duration}s
提示: 检查命令参数是否正确，路径是否存在。
```

**格式说明：**
- ⚠️ 标记的前置确保 LLM 在流式解析时先看到错误信息
- 失败时 stderr 排在 stdout 前面（错误信息优先）
- 成功但 stderr 非空的情况单独处理（如 gradle 的 warning 输出在 stderr），不误报为失败

### Glob

```
成功:
{dirPath}/
├── {dir1}/
│   ├── {file1} ({size})
│   └── {file2} ({size})
├── {dir2}/
│   └── ...
└── {file3} ({size})

{totalFiles} 个文件, {totalDirs} 个目录

截断:
{dirPath}/
├── ...
... (共 {totalFiles} 个文件, {totalDirs} 个目录，已截断到 {maxEntries} 条目，当前第 {start}-{end} 个。用 dirPath/maxDepth 缩小范围，或用 offset={nextOffset} 翻页获取更多)

目录不存在:
错误: 目录 "{dirPath}" 不存在。请检查路径。
```

### Grep

```
找到 {matchCount} 条匹配:
{filePath1}:{line1}: {content1}
{filePath2}:{line2}: {content2}
...

截断:
找到 {totalMatches} 条匹配，已截断到 {maxMatches} 条:
{filePath}:{line}: {content}
...
如有必要，请使用更精确的搜索词缩小范围以获取完整结果。

无结果:
未找到匹配 "{query}" 的内容。请尝试:
- 检查搜索词拼写
- 使用更简短的搜索词
- 确认搜索的文件类型正确

索引未就绪:
项目正在建立索引 ({progress}%)，搜索结果可能不完整。当前结果:
{partialResults}
```

### readLints

```
文件: {filePath}
{errorCount} 个错误, {warningCount} 个警告, {infoCount} 个提示:

错误:
{line}:{column}: {message} [{code}]

警告:
{line}:{column}: {message} [{code}]

提示:
{line}:{column}: {message} [{code}]

截断:
按严重级别排序（错误 > 警告 > 提示），已截断到 {maxItems} 条:
{items}
还有 {remainingCount} 条未显示。

索引未就绪:
项目正在建立索引 ({progress}%)，诊断结果可能不完整。当前结果:
{partialDiagnostics}
```

### Task

```
子任务: {taskSummary}
状态: {completed | failed | cancelled}
结果摘要:
{summary (≤ 2000 tokens)}
详情: sub-session #{sessionId}
```

### createPlan

```
✅ 已创建执行计划: {taskSummary}
步骤数: {stepCount}
步骤列表:
1. {step1.description} — 工具: {step1.tool}, 文件: {step1.files}
2. {step2.description} — 工具: {step2.tool}, 文件: {step2.files}
...
计划已持久化，自动开始执行。
```

### listSteps

```
当前计划: {summary}
进度: {doneCount}/{totalCount} 已完成

步骤列表:
1. ✅ {step1.description} — DONE
2. 🔄 {step2.description} — EXECUTING
3. ⬜ {step3.description} — PENDING
4. 🗑 {step4.description} — DELETED
```

### deleteStep

```
✅ 已删除步骤: {stepId} — "{description}"
剩余步骤: {remainingCount} 步，继续自动执行。

拒绝（步骤非 PENDING 状态）:
❌ 无法删除步骤 {stepId}：当前状态为 {status}，仅 PENDING 状态的步骤可删除。
```

### reorderSteps

```
✅ 已重排剩余步骤:
新顺序:
1. {step2.description} (原 step-2)
2. {step3.description} (原 step-3)
3. {step1.description} (原 step-1)
继续自动执行。

参数无效（stepId 不完整或不匹配）:
❌ 重排失败：提供的 stepId 列表与当前 PENDING 步骤不匹配。
当前 PENDING 步骤: step-1, step-3, step-5
提供的: step-1, step-5
```

### markStepDone

```
✅ 已将步骤 {stepId} — "{description}" 标记为 DONE。
剩余步骤: {remainingCount} 步，继续自动执行。

拒绝（步骤当前为 DONE/DELETED 状态）:
❌ 无法标记步骤 {stepId}：当前状态为 {status}。DONE/DELETED 状态的步骤已是终态，无法再标记为完成。
```

**通用约束：**

- 所有 `ToolResult` 的 `content` 字段不包含 Markdown 代码块包裹（LLM 自己加代码块）
- 文件内容原样返回，不添加额外转义
- `{variable}` 为实际值占位符
- `errorMessage` 字段仅在 `success=false` 时有值

---

## 十、Token 估算（统一策略）

整个项目使用统一的 Token 估算方法，所有依赖 token 计数的场景均调用此方法。

**公式（伪代码）：**

```
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val bytes = text.encodeToByteArray().size
    val asciiOnly = text.count { it.code <= 127 }
    val nonAscii = text.length - asciiOnly
    // 英文/代码 ~4 字节/token，中文 ~0.67 token/字符（即 1.5 字符/token）
    return max(bytes / 4, asciiOnly / 4 + (nonAscii * 3) / 2)
}
```

**精度说明：** 启发式估算，误差 ±20%。API 返回的 `usage` 字段为精确值，优先使用。估算仅用于"写入前"
的场景（compact 阈值判定、输入框预览），持久化时使用 API 返回的精确值。

**适用场景及上限：**

| 场景                        | 上限                     | 方法                         |
|---------------------------|------------------------|----------------------------|
| Auto-Compact 触发判定         | 1M × 0.7 = 700K tokens | `estimateTokens()` 估算      |
| 输入框实时 token 预览            | 无上限（仅展示）               | `estimateTokens()` 估算      |
| `session.totalTokens` 持久化 | 精确值                    | API `usage` 字段，fallback 估算 |
| 子 Agent 结果摘要截断            | 2000 tokens            | `estimateTokens()` 估算截断点   |
| 工具返回值截断                   | 见工具截断表（按行）             | 不依赖 token 估算               |

---

## 十一、Swing 布局规范

以下为关键 UI 组件的 LayoutManager 选择。**必须使用指定的 LayoutManager，不可替换。**

| 组件             | LayoutManager                             | 说明                                                                                         |
|----------------|-------------------------------------------|--------------------------------------------------------------------------------------------|
| ChatToolWindow | `BorderLayout`                            | NORTH=TabBar, CENTER=pageContainer(CardLayout)                                             |
| TabBar         | `JPanel(FlowLayout.LEFT, hgap=0, vgap=0)` | 纯图标按钮，`setPreferredSize(Dimension(44, 32))`。7 个 Tab × 44px = 308px                         |
| ChatPage       | `BorderLayout`                            | NORTH=标题行, CENTER=JScrollPane, SOUTH=ChatInputArea                                         |
| 消息容器           | `JPanel` → `BoxLayout.Y_AXIS`             | 消息气泡垂直排列，用 `Box.createVerticalStrut(8)` 间隔                                                 |
| 用户气泡           | `JPanel(BorderLayout)` → 右对齐              | `setMaximumSize(Dimension(maxWidth, ...))` 限制宽度为面板的 70%                                    |
| Agent 气泡       | `BubblePanel(BoxLayout.Y_AXIS)`           | 文本段+代码块+文本段垂直排列，用 `Box.createVerticalStrut(4)` 间隔                                          |
| ToolCallCard   | `JPanel(BorderLayout)`                    | NORTH=头部(图标+名称+状态), CENTER=折叠面板(JPanel.BoxLayout_Y_AXIS)                                   |
| PlanCard       | `JPanel(BorderLayout)`                    | NORTH=摘要行, CENTER=步骤列表(BoxLayout.Y_AXIS)                                                   |
| ChatInputArea  | `JPanel(BorderLayout)`                    | NORTH=TagsRow(FlowLayout: 文件+图片), CENTER=JTextArea, SOUTH=底部栏(FlowLayout: [+]按钮+@提示+[→]发送) |
| WelcomePage    | `JPanel(GridBagLayout)`                   | 居中单列，GridBagConstraints.fill=HORIZONTAL, insets=Insets(8,20,8,20)                          |
| SessionsPage   | `JPanel(BorderLayout)`                    | NORTH=搜索栏, CENTER=JScrollPane→JPanel(BoxLayout.Y_AXIS) 会话卡片列表                              |
| TokenUsagePage | `JPanel(BorderLayout)`                    | NORTH=时间选择行(FlowLayout), CENTER=JPanel→上部sparkline(JComponent)+下部JTable                    |
| McpPage        | `JPanel(BorderLayout)`                    | NORTH=标题+添加按钮, CENTER=JScrollPane→JPanel(BoxLayout.Y_AXIS) server卡片列表                      |
| SkillsPage     | `JPanel(BorderLayout)`                    | NORTH=标题+操作按钮, CENTER=JScrollPane→JPanel(BoxLayout.Y_AXIS) skill卡片列表                       |
| SettingsPage   | `JPanel(BoxLayout.Y_AXIS)`                | 关于卡片 + 快捷键参考卡片 + IDE 设置入口卡片，垂直排列                                                           |

**组件样式规范：**

| 属性          | 值                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------|
| 用户气泡背景色     | `Color(232, 240, 254)` (#E8F0FE)                                                                                        |
| Agent 气泡背景色 | `Color(255, 255, 255)` (#FFFFFF) + 左边框 `Color(59, 130, 246)` (#3B82F6) 3px                                              |
| 错误气泡背景色     | `Color(254, 226, 226)` (#FEE2E2)                                                                                        |
| 系统消息颜色      | `Color(156, 163, 175)` (#9CA3AF)                                                                                        |
| 代码块背景色      | `Color(246, 248, 250)` (#F6F8FA)                                                                                        |
| 字体          | 中文/英文统一: `Font("SansSerif", PLAIN, 14)`。代码块: `Font("JetBrains Mono", PLAIN, 13)`，不可用时降级 `Font("Monospaced", PLAIN, 13)` |
| 圆角          | 用户气泡: 12px, Agent 气泡: 12px, 代码块: 8px, 工具卡片: 8px                                                                         |
| 间距          | 气泡间: 8px, 气泡内部 padding: 12px, 代码块 margin: 8px 0                                                                         |
| 输入框最小行数     | 2 行, 最大 10 行, 自动扩展                                                                                                      |
| TabBar 按钮大小 | 44×32 px（纯图标）, 当前页面高亮 `Color(59, 130, 246)` 底边框 2px                                                                     |
