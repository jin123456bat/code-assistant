# Agent Loop

>
关联文档：[工具系统](./tools.md)、[Plan Mode](./plan.md)、[上下文管理](./context.md)、[多 Agent 协作](./multi-agent.md)

## 一、主循环

Agent Loop 是 Agent 模式的核心——一个手写的 while 循环，负责将用户消息发送到 LLM、解析
tool_use、调度工具执行、回传结果，直到对话自然结束或被用户中断。

```
val effectiveMaxTurns = if (maxTurns == 0) Int.MAX_VALUE else maxTurns  // 0=不限，内部转为最大值
var continueStreak = 0                           // 当前轮续写链长度（每轮重置）
while (turn < effectiveMaxTurns && !cancelled):
  ├─ if (stop_reason != "max_tokens") turn++       ← 仅用户消息触发的 API 调用计数，续写不增加 turn
  ├─ compactIfNeeded(system, messages, tools, modelLimit) ← 估算实际总 token，超 modelLimit × 0.7 阈值则压缩 messages
  ├─ if (turn >= effectiveMaxTurns * 0.6):            ← 轮次预警。effectiveMaxTurns == Int.MAX_VALUE（不限轮次）时跳过预警
  │    附加系统提示"已执行 N 轮，评估剩余工作量"
  ├─ client.messages().createStreaming(params)    ← OkHttp, background
  │    .forEach { chunk → invokeLater { UI } }
  │
  ├─ for each toolUse:
  │    ├─ 审批检查（首次弹确认）
  │    │    CountDownLatch ← 等待用户操作（无超时，Agent Loop 在后台线程池而非 EDT，阻塞不影响 UI 响应；审批弹窗是模态的，用户必然处理）
  │    │
  │    ├─ 执行（18 个工具）:
  │    │    Read      → VFS (bg)
  │    │    Write     → invokeAndWait { WriteCommandAction }
  │    │    Edit      → invokeAndWait { WriteCommandAction }
  │    │    Bash      → ProcessHandler (bg, 流式)
  │    │    Glob     → VFS + FilenameIndex (bg)
  │    │    Grep → 文件遍历 + 正则 (bg)
  │    │    readLints     → IDE Inspection (bg)
  │    │    Skill    → SkillManager 注入 SKILL.md 内容
  │    │    Agent   → 新 AgentLoop (bg)
  │    │    WebSearch → HTTP API (bg)
  │    │    WebFetch  → HTTP API (bg)
  │    │    AskUserQuestion → MODAL Dialog (EDT)
  │    │    Symbol  → PSI + StubIndex (bg)
  │    │    createPlan / listPlans / removePlan / reorderPlans / markPlanDone
  │    │              → PlanExecutor (计划任务管理)
  │    │
  │    └─ 结果显示在 ToolCallCard
  │
  ├─ params.add(toolUses + toolResults)           ← 追加到消息
  │
  ├─ stop_reason 分叉:
  │    "end_turn"      → continueStreak = 0, 退出 while（等待用户下一条消息）
  │    "max_tokens"    → continueStreak++, 超过 maxAutoContinue(5) 则 emit Error + 退出
  │                      未超 → 追加临时 "继续" 消息（不持久化），循环回到顶部继续（续写不增加 turn）
  │    "stop_sequence" → continueStreak = 0, 退出 while。在 assistant 消息尾部追加系统标注 "[响应被 stop_sequence 终止]"
  │
stop: cancelled → 按[第五节清理顺序](#五项目关闭清理)逐步关闭
```

**关键分支说明：**

| 分支              | 触发条件                 | 行为                                   |
|-----------------|----------------------|--------------------------------------|
| `end_turn`      | LLM 自然完成回复           | 正常退出 while，等待用户下一条消息                 |
| `max_tokens`    | LLM 输出被 token 上限截断   | 自动发送"继续"消息，续写不增加 turn 计数，最多连续 5 次    |
| `stop_sequence` | LLM 遇到 stop sequence | 正常结束，消息尾部标注 `[响应被 stop_sequence 终止]` |
| `cancelled`     | 用户点击停止按钮             | 退出循环，按清理顺序关闭                         |

**工具执行顺序：** 同一轮中 LLM 可能返回多个 `tool_use`。按 LLM 返回的顺序串行执行，不并行。前一个工具的结果会立即追加到
`params.messages`，后续工具可以看到前面工具的执行结果。LLM 应通过返回顺序控制执行依赖（如先 `Read` 后
`Edit`）。

## 二、AgentSession 状态机

AgentSession 维护会话级别的状态机，控制消息的发送、工具执行和错误处理流程。

### 状态图

```
IDLE ──sendMessage()──→ PROCESSING ──LLM 返回输出──→ IDLE
  │                        │
  │                        ├── 429/网络瞬断 → PAUSED（计时后自动继续）→ PROCESSING
  │                        │
  │                        ├── toolUse → AWAITING_APPROVAL（如有审批）
  │                        │     ├─ 确认 → EXECUTING → PROCESSING
  │                        │     └─ 拒绝 → IDLE（[重审] 按钮可重试）
  │                        │
  │                        └── 无审批 → EXECUTING → PROCESSING
  │
  ├── 用户点 Stop → CANCELLED（清理 → IDLE）
  └── API error → ERROR（显示错误气泡 + [重试]）
```

### 状态说明

| 状态                  | 含义                                                                                     |
|---------------------|----------------------------------------------------------------------------------------|
| `IDLE`              | 可接受输入，无进行中的 Agent 操作                                                                   |
| `PROCESSING`        | LLM 流式输出中，或 tool 结果已提交等待 LLM 响应                                                        |
| `AWAITING_APPROVAL` | 弹出审批 dialog，等待用户操作（无超时，等待用户操作 → IDLE）                                                  |
| `EXECUTING`         | 工具正在执行（非阻塞，UI 显示进度）                                                                    |
| `CANCELLED`         | 用户中断，清理中                                                                               |
| `ERROR`             | API 调用失败或 tool 执行异常，等待用户操作                                                             |
| `PAUSED`            | Agent 因速率限制（429）或网络瞬断等待自动恢复，计时后自动继续。IDE 重启后计时器丢失，重置为 IDLE，见 [会话恢复规则](./session.md#一存储) |

### 瞬态持久化规则

| 状态                                                                   | 是否持久化  | 恢复行为            |
|----------------------------------------------------------------------|--------|-----------------|
| `IDLE` / `PROCESSING` / `AWAITING_APPROVAL` / `EXECUTING` / `PAUSED` | 持久化    | IDE 重启后可恢复      |
| `CANCELLED` / `ERROR`                                                | 不持久化   | 加载时自动重置为 `IDLE` |
| `PROCESSING`（HTTP 流已断，无法恢复）                                          | 加载时检测到 | 自动重置为 `IDLE`    |

- `CANCELLED` 和 `ERROR` 不写入 Session JSON
- `AWAITING_APPROVAL` / `EXECUTING` 持久化，IDE 重启后可恢复

## 三、API 错误恢复

### 错误类型与处理策略

| 错误类型  | HTTP 状态码              | 处理策略                                                                                                          |
|-------|-----------------------|---------------------------------------------------------------------------------------------------------------|
| 认证失败  | 401                   | 不重试，状态 → ERROR，toast "API Key 无效"                                                                             |
| 速率限制  | 429                   | 读取 `Retry-After` header（秒），暂停当前请求。如 header 缺失则默认等 30s。Agent 状态 → PAUSED（可恢复），显示倒计时。连续 3 次 429 → 建议用户降低并发或切换模型 |
| 服务器错误 | 5xx                   | 退避重试：1s → 3s → 9s，最多 3 次。3 次均失败 → ERROR                                                                       |
| 网络超时  | 无响应                   | 退避重试：2s → 5s → 10s，最多 3 次。3 次均超时 → ERROR，已渲染内容保留 + 标注 `[连接中断]`                                                |
| 请求体过大 | 400（context too long） | 不重试，强制触发 compact 后重发。compact 后仍过大 → 提示用户减少上下文                                                                 |

### PAUSED vs ERROR 的区别

| 特性         | PAUSED               | ERROR                   |
|------------|----------------------|-------------------------|
| 触发场景       | 429、网络瞬时中断（可自动恢复）    | 401、compact 后仍过大（需用户介入） |
| 恢复方式       | 计时后自动继续，不需要用户手动 [重试] | 需要用户手动 [重试]             |
| 是否阻塞 Agent | 是，等待恢复条件满足           | 是，等待用户操作                |

### 连续错误升级规则

| 条件                       | 动作                                          |
|--------------------------|---------------------------------------------|
| 同一 tool call 连续 3 次失败    | 提示"该操作连续失败 3 次，建议跳过或手动处理"，LLM 应放弃该操作        |
| 同一 Session 内累计 5 个 ERROR | 弹出提示"已出现多个错误，建议使用 /plan 拆分任务"               |
| API 4xx 错误（401/403 除外）   | 不自动重试，直接 ERROR                              |
| 网络错误（超时/DNS）             | 最多自动重试 3 次（退避：2s → 5s → 10s），3 次均失败 → ERROR |
| 连续 3 次 429               | 建议用户降低并发或切换模型，Agent → PAUSED                |

## 四、流式中断处理

### 停止按钮行为

用户点 Stop（⏹）时，三件事同时做：

- `cancelled=true`（退出 Agent while 循环）
- `client.close()` 取消当前 HTTP 请求
- 遍历当前 AgentSession 的 `runningProcesses` → `destroyForcibly()`（杀 Shell 进程）

作用域仅限当前 AgentSession，不杀其他 Agent 的进程、不杀 MCP Server 进程。

**中断后的数据保留规则：**

| 内容                                 | 处理                                                 |
|------------------------------------|----------------------------------------------------|
| 已流式渲染的文本（`MessageAccumulator` 已累积） | 保留在 UI + 持久化到 `session.messages`                   |
| 当前 streamingBuf 中未 flush 的部分       | 丢弃                                                 |
| 已解析但未执行的 tool call                 | 标记 CANCELLED，不持久化                                  |
| 正在执行的工具                            | `destroyForcibly()` 终止，结果丢失，tool call 标记 CANCELLED |
| Agent 状态                           | → IDLE，输入框立即可用                                     |

### 网络断连（被动中断）

- 已完成的流式内容：保留 + 持久化
- 未完成的流式内容：保留 + 持久化（存已收到的部分），尾部标注 `[连接中断]`
- 用户点击 [重试]：发送相同的 `params` 重新开始当前 turn，LLM 从中断处继续

### Escape 键行为

Escape 键仅关闭 Popup，不中断 Agent Loop、LLM 调用、流式生成或工具执行。所有正在进行的操作不受影响。

## 五、项目关闭清理

`ChatToolWindow.dispose()` 中的清理顺序（必须按序）：

```
1. 标记所有 AgentSession 为 cancelled（含子 Agent）
2. 等待子 Agent 完成（最多 3s）→ 等待 WriteCommandAction 完成（最多 5s）
3. AnthropicOkHttpClient.close() 取消所有 HTTP 请求
4. 遍历所有 AgentSession 的 runningProcesses → destroyForcibly() 杀 Shell 进程
5. 持久化所有未保存 Session（含暂停的 Plan）
6. McpManager.dispose()（遍历 server → shutdown 2s → destroyForcibly）
7. 释放所有 FileLock
```

## 六、AgentLoop 接口定义

```
AgentLoop
├── 构造: AgentLoop(session, toolRegistry, client, settings)
├── run(userMessage: String): Flow<AgentEvent>
│   AgentEvent = TextDelta | ToolCallStarted | ToolCallCompleted | TurnCompleted | Error | StateChanged
├── cancel()
├── isRunning: Boolean
├── currentState: AgentState            // 从 AgentSession 读取
├── compactIfNeeded(system: String, messages: List, tools: String, modelLimit): Boolean
│                         // 估算实际总 token = system + messages + tools。超过 compactThreshold × modelLimit 则压缩。
├── compactThreshold: Double = 0.7      // 触发压缩的上下文使用率阈值
├── modelContextLimit: Int = 1_000_000  // 模型上下文窗口大小（DeepSeek V4 的 1M tokens，写死）
├── maxAutoContinue: Int = 5            // max_tokens 自动续写最大次数（单轮内连续续写链长度）
├── autoContinueMessage: String = "继续" // max_tokens 时自动发送的续写消息
├── continueStreak: Int = 0              // 当前轮内续写链已执行次数，end_turn 后归零
├── turnWarningRatio: Double = 0.6      // 轮次预警触发比例
└── turnWarningMessage: String          // 轮次预警文本模板
```

**线程模型：**

- `run()` 在 `ApplicationManager.executeOnPooledThread()` 上执行（非 EDT）
- 流式 token 通过 `Flow<AgentEvent>.collect()` 接收，在 collector 处 `invokeLater` 切换到 EDT
- `cancel()` 可在任意线程调用
- Write 内部通过 `invokeAndWait` 切换到 EDT

**约束：**

- 单个 AgentSession 同时只能有一个 `run()` 在执行
- AgentEvent 的发射在 background thread，UI 订阅者负责线程切换

## 七、AgentSession 接口定义

```
AgentSession
├── id: String (UUID)
├── messages: List<Message>           // 只读视图，通过 addMessage() 追加
├── state: AgentState
├── plan: Plan?                       // 关联的计划（最多一个）
├── runningProcesses: Set<ProcessHandle>  // 当前会话的 Shell 进程
├── filesReadThisTurn: Set<String>        // 当前 turn 中已读取的文件路径
├── compactSummary: String?               // compact 后的对话摘要
├── compactCount: Int = 0                 // 已执行 compact 的次数
│
├── addMessage(msg: Message)
├── setState(newState: AgentState)
├── cancel()                          // 标记 cancelled=true，清理 runningProcesses
├── onStateChanged: (AgentState) -> Unit   // 状态变更回调（UI 注册）
├── onMessageAdded: (Message) -> Unit      // 消息追加回调
├── rollbackTo(messageId: String)         // 回退：标记 messageId 之后的消息 deleted=true
└── totalTokens: TokenUsage                // 输入/输出 token 累计

AgentState = IDLE | PROCESSING | AWAITING_APPROVAL | EXECUTING | CANCELLED | ERROR | PAUSED

Message:
├── id: String
├── role: USER | ASSISTANT | SYSTEM | TOOL_CALL | TOOL_RESULT | ERROR
├── content: String
├── timestamp: Instant
├── toolCalls: List<ToolCallRecord>?   // ASSISTANT 消息可能含工具调用
├── tokenUsage: TokenDelta?            // ASSISTANT 消息的 token 消耗
├── feedback: String?                  // 用户反馈 "positive" | "negative"
└── deleted: Boolean = false           // 回退标记，true 时持久化保留但 UI 不渲染

TokenUsage:
├── inputTokens: Long
├── outputTokens: Long
└── timestamp: Instant
```
