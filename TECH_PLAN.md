# Code Assistant - 技术方案文档

## 1. 架构设计

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                      UI 层 (Swing)                          │
│  ChatToolWindow · BubbleFactory · ToolRowFactory            │
│  SelectionCard · PlanBar · MarkdownRenderer · PathUtils      │
├─────────────────────────────────────────────────────────────┤
│                    ViewModel 层                              │
│  ChatViewModel (Activity 状态机 · 回调管理)                   │
├─────────────────────────────────────────────────────────────┤
│                     Agent 层                                 │
│  AgentLoop · AgentContext · ToolRegistryV3                  │
│  SkillEngine · ToolRegistryV3                               │
├─────────────────────────────────────────────────────────────┤
│                    适配器层                                   │
│  AnthropicAdapter (请求构建 · SSE 事件解析)                   │
│  SseClient (流式 HTTP · SSE 协议处理)                        │
├─────────────────────────────────────────────────────────────┤
│                    工具层                                     │
│  内置工具(13) · MCP 工具 · Skill 工具                        │
│  McpManager/McpClient (stdio JSON-RPC)                      │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 数据流

```
用户输入
  │
  ▼
ChatToolWindow.sendMessage()
  │ 拼接引用内容 + 图片
  ▼
ChatViewModel.sendMessage(apiKey, content, images)
  │ 创建用户消息 → AgentLoop.run()
  ▼
AgentLoop.run() [背景线程]
  │
  ├─► 构建 history (AnthropicMessage)
  │
  └─► while (loopCount < 100 && !cancelled)
       │
       ├─► callAnthropic() → AnthropicAdapter.buildRequest()
       │     │
       │     ├─► SseClient.connect()
       │     │     │  SSE 事件流
       │     │     ▼
       │     │   AnthropicAdapter.parseSseEvent()
       │     │     │ TextDelta / ThinkingDelta / ToolUseStart / ...
       │     │     ▼
       │     │   callAnthropic 回调: textBuffer ← text
       │     │                       thinkingBuffer ← thinking
       │     │                       toolCalls ← tool_use 块
       │     │
       │     └─► 返回 (textContent, thinking, toolCalls)
       │
       ├─► 无工具调用 → callback(textContent, thinking) → 结束
       │
       └─► 有工具调用 → 逐个执行:
             │
             ├─► 安全检查 (SAFE_TOOLS / 白名单 / onConfirmTool)
             │     │ 非安全工具 → CountDownLatch 阻塞等待用户确认
             │     ▼
             ├─► ToolRegistryV3.executeTool()
             │     │ 内置 → 直接执行
             │     │ MCP  → McpClient.callTool() [stdio JSON-RPC]
             │     │ Skill → 返回提示词注入文本
             │     ▼
             ├─► 结果回填 history → 下一个循环
             │
             └─► EDT 回调: onToolExecute → onToolResult → onStateChange → ...
```

## 2. 核心模块设计

### 2.1 ChatViewModel (`ChatViewModel.kt`, 225 行)

**职责**：UI 层与 Agent 层的轻量桥接，不包含业务逻辑。

**Activity 状态机**：

```kotlin
sealed class Activity {
    object Idle : Activity()                    // 空闲
    object Thinking : Activity()                // 模型推理中
    data class RunningTool(val toolName: String) : Activity()  // 工具执行中
}
```

**关键设计决策**：
- 所有 `AgentLoop` 回调在 EDT 上执行（`runOnEdt`），但 `runOnEdt` 内部检查当前线程，避免 `invokeLater` 嵌套排队
- 相邻的 `thinking` 消息自动合并：`addThinkingMessage()` 检查上一条消息角色，若同为 `thinking` 则拼接而非新增
- 工具执行结束后 activity 切换到 `Thinking` 而非 `Idle`，避免指示器"消失-重现"的闪烁
- 流式结束时的 `completion` 回调中，`thinking` 和 `assistant` 消息在同一个 EDT 块中原子性落地，同时清空 `streamingThinking/streamingContent`，避免两份渲染

### 2.2 AgentLoop (`agent/AgentLoop.kt`, 429 行)

**职责**：Agent 主循环，模型调用调度，工具分发与安全控制。

**核心流程**：
```
入口 run(userMessage, apiKey, images, callback)
  └─► 新 Thread
       └─► 构建 history
            └─► while (loopCount < MAX_LOOPS=100 && !cancelled)
                 ├─► callAnthropic(apiKey, history) → (text, thinking, toolCalls)
                 ├─► 无工具调用 → callback(text, thinking) → break
                 └─► 有工具调用 → 逐个执行 → 回填 history → loopCount++
```

**安全模型实现**：
- `SAFE_TOOLS` 静态集合：`search_code`、`read_file`、`list_directory`、`git_diff`、`git_log`、`git_status`、`web_search`、`web_fetch`、`task`、`ask_user`
- 用户白名单：`AppSettingsService.getToolWhitelist()`
- Skill 工具跳过审批：`ctx.toolRegistry.isSkill(tc.name)`
- 非安全工具的审批流程：
  ```kotlin
  val latch = CountDownLatch(1)
  val userChoice = AtomicBoolean(false)
  pendingConfirmLatch = latch  // 供 stop() 解除
  onConfirmTool?.invoke(name, args, latch, userChoice)
  latch.await(10, TimeUnit.MINUTES)  // 最多等 10 分钟
  if (cancelled) break
  userChoice.get()
  ```
- `stop()` 方法同时设置 `cancelled=true` + `sseClient.cancel()` + `pendingConfirmLatch?.countDown()`，三重保障避免线程挂起

**元工具处理**：
- `create_plan`：解析 JSON → `AgentContext.Plan` → 注入 PlanBar。plan 独立处理，history 中不保留此 tool_use
- `update_plan_step`：解析 index/status/result → 更新 `ctx.currentPlan` 的步骤状态

**DeepSeek V4 特殊处理**（`callAnthropic` 方法）：
- 简单回复时 DeepSeek V4 可能把全部内容写进 thinking 而非 text，此时将 thinking 降级为 text 返回
- 通过 `synchronized(done) { done.wait(120_000) }` 等待 SSE 事件流结束，最长等 120 秒

### 2.3 AgentContext (`agent/AgentContext.kt`, 56 行)

**职责**：贯穿 Agent 生命周期的共享上下文。

**核心数据结构**：
- `Plan`: 执行计划（`title` + `steps` 列表），提供 `progress()` / `isComplete()` / `nextPending()` / `updateStep()`
- `Step`: 单个步骤（`index` / `description` / `status` / `result`），状态：`PENDING` / `IN_PROGRESS` / `DONE` / `FAILED`

### 2.4 ToolRegistryV3 (`agent/ToolRegistryV3.kt`, 64 行)

**职责**：统一管理三类工具来源（内置/MCP/Skill），生成 Anthropic `input_schema` 格式的工具列表。

**三类工具分离存储**：
```kotlin
private val tools = mutableMapOf<String, AgentTool>()       // 内置
private val mcpTools = mutableMapOf<String, AgentTool>()    // MCP
private val skillTools = mutableMapOf<String, AgentTool>()  // Skill
```

**缓存策略**：
- `buildToolsJson()` 带 `@Volatile` 缓存：首次调用生成 JSON，后续直接返回缓存值
- 注册新工具时通过 `invalidateCache()` 失效缓存
- JSON 格式：`[{"name":"...","description":"...","input_schema":{...}}]`

**13 个内置工具**：
`ReadFileTool`、`WriteFileTool`、`SearchCodeTool`、`ListDirectoryTool`、`ExecuteCommandTool`、`GitDiffTool`、`GitLogTool`、`GitStatusTool`、`AskUserTool`、`WebSearchTool`、`WebFetchTool`、`NotebookEditTool`、`TaskTool`

### 2.5 SkillEngine (`agent/SkillEngine.kt`, 87 行)

**职责**：从文件系统加载 Skill 定义，包装为 `SkillTool`。

**加载路径**：
1. `<project>/.claude/skills/**/SKILL.md`
2. `~/.claude/skills/**/SKILL.md`

**SKILL.md 解析**：
- 目录名作为默认名称，解析前 2000 字符提取 `name:` 和 `description:`
- 文件大小限制 200KB
- `walkTopDown().maxDepth(2)` 扫描

**SkillTool**：实现 `AgentTool` 接口，参数为 `input`（string）。执行时返回提示词注入文本，让 LLM 按照 skill 指引完成任务，不执行实际文件操作。

### 2.6 CLAUDE.md 自动加载

`AgentLoop.loadClaudeMdFiles()` 按 Claude Code 层级自动加载并注入 system prompt：
1. `~/.claude/CLAUDE.md` — 用户全局
2. 项目根 `CLAUDE.md`
3. `.claude/CLAUDE.md`
4. `CLAUDE.local.md` — 个人覆盖（gitignored）

所有文件内容拼接后用 `## CLAUDE.md` 标题注入 system prompt。

## 3. 通信协议

### 3.1 AnthropicAdapter (`AnthropicAdapter.kt`, 176 行)

**端点**：`https://api.deepseek.com/anthropic/v1/messages`（DeepSeek Anthropic 兼容 API）

**请求格式**：
```json
{
  "model": "deepseek-v4-flash",
  "max_tokens": 4096,
  "system": "<systemPrompt>",
  "messages": [{ "role": "user/assistant", "content": [...] }],
  "tools": [{ "name": "...", "description": "...", "input_schema": {...} }],
  "tool_choice": { "type": "auto" },
  "stream": true
}
```

**消息构造关键逻辑**：
- `tool_result`（role=user + toolCallId）：`[{"type":"tool_result","tool_use_id":"...","content":"..."}]`
- `tool_use`（role=assistant + toolUseId）：**前面强制加一个空 thinking block** `{"type":"thinking","thinking":""}`，这是 DeepSeek V4 的硬性要求
- 用户消息含图片时生成 Claude 原生 image 块：`{"type":"image","source":{"type":"base64","media_type":"image/png","data":"..."}}`
- 所有字符串值通过 `JsonUtils.escapeJson()` 转义

**SSE 事件解析**（`parseSseEvent`）：
- 使用 `extractJsonString()` 手写字符串扫描（非 JSON 库），按 `"type":"xxx"` 分发
- 支持事件类型：
  | 事件 | 对应 SSE type | 说明 |
  |------|-------------|------|
  | `MessageStart` | `message_start` | 流开始 |
  | `TextDelta` | `content_block_delta` + `text_delta` | 文本增量 |
  | `InputJsonDelta` | `content_block_delta` + `input_json_delta` | 工具参数增量 |
  | `ThinkingDelta` | `content_block_delta` + `thinking_delta` | 思考过程增量 |
  | `SignatureDelta` | `content_block_delta` + `signature_delta` | 签名增量（忽略） |
  | `ToolUseStart` | `content_block_start` + `tool_use` | 工具调用开始 |
  | `TextStart` | `content_block_start` + `text` | 文本块开始（忽略） |
  | `ThinkingStart` | `content_block_start` + `thinking` | 思考块开始（标记） |
  | `ContentBlockStop` | `content_block_stop` | 内容块结束（触发 tool_use 收包） |
  | `MessageDelta` | `message_delta` | 消息元信息 |
  | `MessageStop` | `message_stop` | 流结束 |

### 3.2 SseClient (`SseClient.kt`, 114 行)

**实现**：基于 `HttpURLConnection` 的标准 SSE 协议客户端。

**关键参数**：
- 连接超时：10 秒
- 读取超时：120 秒（DeepSeek V4 Pro 思考模式可能较长）
- 请求头：`application/json`、`x-api-key`、`anthropic-version: 2023-06-01`、`text/event-stream`

**协议处理**：
- 按行读取：`data:` 前缀行 → 积累到 `currentData`；空行 → 触发 `callback.onData(currentData)`；`[DONE]` → 流结束
- `cancelled` 标志位 + `close()` 方法提供取消能力

### 3.3 McpClient JSON-RPC (`McpClient.kt`, 214 行)

**协议**：JSON-RPC 2.0 over stdio

**初始化握手**：
1. `initialize` 请求：发送 `{"jsonrpc":"2.0","method":"initialize","params":{...}}`
2. 等待响应，检查无 error
3. `initialized` 通知：`{"jsonrpc":"2.0","method":"initialized","params":{}}`

**工具发现**：
- `tools/list` 请求 → 解析返回的 `result.tools[]`
- 每个工具包装为 `McpToolAdapter`（实现 `AgentTool`）

**工具调用**：
- `tools/call` 请求 → 解析返回的 `result.content[0].text`

## 4. 安全模型

### 4.1 工具分类

| 分类 | 工具 | 审批方式 |
|------|------|---------|
| 只读安全 | search_code, read_file, list_directory, git_diff, git_log, git_status, web_search, web_fetch, task, ask_user | 直接执行 |
| 需审批 | write_file, execute_command, notebook_edit, MCP 工具 | 内联确认 |
| Skill | 所有 SkillTool | 直接执行（仅提示词注入） |
| 用户白名单 | 由用户通过审批选择卡添加 | 直接执行 |

### 4.2 审批流程

```
AgentLoop.onToolExecute
  │
  ├─► isSkill → 直接执行
  ├─► in SAFE_TOOLS → 直接执行
  ├─► in ToolWhitelist → 直接执行
  └─► 否则 → onConfirmTool(name, args, latch, userChoice)
       │
       ▼
ChatViewModel.onConfirmTool
  │ 创建 ApprovalState(latch, userChoice)
  │ 标记消息 approvalPending=true
  │
  ▼
ChatToolWindow
  │ rebuildConversation() → createToolResultBubble()
  │ 检测 approvalPending → 创建 ApprovalActions
  │
  ▼
ToolRowFactory.toolResultRow()
  │ 在 tool 行下方附加 approvalCard
  │ 圆角卡片 + 三个选项
  │
  ▼
用户点击 → onAllowOnce/onAlwaysAllow/onReject
  │ userChoice.set(true/false)
  │ latch.countDown()
  │ 卡片标记为已选 ✓
  │
  ▼
AgentLoop 解除阻塞 → approved 判断 → 执行工具 / 返回拒绝消息
```

### 4.3 文件越界防护

- `WriteFileTool` 限制写入路径必须在项目目录内
- `computeWriteFileDiff()` 读取旧文件时同样做路径穿越防护，防止 diff 预览泄露项目外文件

## 5. UI 架构

### 5.1 布局方案

```
panel (BorderLayout)
├── NORTH  → errorBanner（错误/警告横幅，红/黄背景）
├── CENTER → conversationPanel (BorderLayout)
│   ├── NORTH  → northStack (BoxLayout.Y_AXIS)
│   │   ├── conversationHeader（"对话"标题 + 新会话按钮 "+"）
│   │   └── planBar（置顶执行计划，不随消息滚动）
│   └── CENTER → conversationScrollPane
│       └── conversationWrapper (BoxLayout.Y_AXIS，无 vertical glue)
│           └── conversationContainer (BoxLayout.Y_AXIS)
│               ├── 用户气泡 row: [glue] [ChatBubble] → 靠右
│               ├── AI 气泡 row: [ChatBubble] [glue] → 靠左
│               ├── 工具行 / 思考行（ToolRowFactory 折叠行）
│               ├── 审批选项 (ToolRowFactory) / 选择卡 (SelectionCard)
│               └── 空态提示 / 流式气泡
└── SOUTH  → inputPanel
    ├── NORTH  → topRow (plusButton + chipPanel)
    ├── CENTER → scrollInput (inputArea)
    └── SOUTH  → bottomRow (lingmaSubmitBtn)
```

**关键布局机制**：
- `conversationWrapper` 无 vertical glue：内容天然从顶部开始，多余空间留白在底部（等价 web `flex-direction: column`）
- 气泡水平对齐：`rowPanel`（X_AXIS BoxLayout）+ `Box.createHorizontalGlue()`。用户气泡 `[glue][bubble]` → 靠右；AI 气泡 `[bubble][glue]` → 靠左（等价 CSS `justify-content: flex-end / flex-start`）
- 气泡 height hug：`getMaximumSize() = preferredSize`，确保气泡不被拉伸，glue 才能把它推到对侧

### 5.2 ChatBubble (`ChatBubble.kt`, 168 行) - 自测量架构

**设计决策**：从"构造期冻结尺寸"改为"实时自测量"。

**旧方案的缺陷**：在构造时测量一次内容尺寸然后冻结，但 viewport 宽度、JBFont、HiDPI 缩放在构造时都未最终确定，导致文字裁切和气泡对齐错误。

**新方案**：
- `getPreferredSize()` — 实时按当前 viewport 宽度 + content 测量计算
- `getMaximumSize()` = `preferredSize` — hug content，不被拉伸
- `getMinimumSize()` = `preferredSize` — BoxLayout 不压缩
- `USER_FRACTION = 0.80` — 用户气泡最大宽度占 viewport 80%
- `AI_FRACTION = 1.0` — AI 气泡占满全宽
- `ABS_CAP = 560` — 用户气泡内容绝对宽度上限（逻辑 px）

**测量算法**：
- `JTextPane`：先量自然宽 hug content（封顶 budget），再用确定宽度量诚实高度（getPreferredSpan 而非 getMinimumSpan）
- `JTextArea`：lineWrap=false 量自然宽，超限则启用 lineWrap=true 按上限重量高度
- `JPanel`：含非文本子节点（代码块）→ 满宽；纯文本 → 递归到内部 JTextPane

### 5.3 组件层次

| 组件 | 文件 | 行数 | 职责 |
|------|------|------|------|
| ChatTheme | ChatTheme.kt | 66 | 设计 token（JBColor 明暗双值、间距、圆角、字体、宽度约束） |
| ChatBubble | ChatBubble.kt | 168 | 自测量气泡（实时 viewport 感知尺寸） |
| BubbleFactory | BubbleFactory.kt | 99 | 构建用户/AI 气泡行（HTML JTextPane / markdown 容器 + 左右对齐） |
| ToolRowFactory | ToolRowFactory.kt | 668 | 工具/思考折叠行（左栏竖线 + 折叠展开 + 审批卡片 + 错误卡 + 执行中 spinner） |
| 审批选项 | ToolRowFactory.kt | ~100 | 内联审批选项（允许本次/始终允许/拒绝） |
| SelectionCard | SelectionCard.kt | 452 | ask_user 选择卡（单选/多选模式，cheveron 高亮 + hover） |
| PlanBar | PlanBar.kt | 284 | 置顶计划条（折叠摘要 + 展开步骤列表 + 迷你进度条） |
| MarkdownRenderer | MarkdownRenderer.kt | 406 | Markdown → Swing JPanel（分段渲染：文本段 JTextPane + 代码段 CodeBlockWrapper） |
| SimpleDiff | SimpleDiff.kt | 140 | 行级 diff（Myers LCS 算法 + DEL 优先排序） |
| AskUserBridge | AskUserBridge.kt | 89 | 工具线程 ↔ EDT 用户交互桥接（CountDownLatch + 5 分钟超时） |

### 5.4 设计 Token（ChatTheme）

所有 UI 组件统一使用 `ChatTheme` 中的 token，使用 `JBColor(lightHex, darkHex)` 跟随 IDE 主题自动切换。

**颜色体系**：
- 基础色：`winBg`、`divider`、`textPrimary`、`textSecondary`、`textMuted`
- 气泡色：`userBg`（蓝色实心）、`userFg`（白色）、`aiBg`（浅灰）、`aiBorder`
- 工具强调色：`toolBar`（蓝色 3px 竖线）、`toolFg`、`toolBg`（半透明淡蓝）
- 代码区：`codeBg`、`codeBorder`
- 输入区：`inputBg`、`inputBorder`、`inputFocus`
- 状态色：`diffDelFg`（红）、`diffAddFg`（绿）、`danger`（橙）、`error`（红）、`doneCheck`（绿）

**间距/圆角/宽度约束**：`GAP_BUBBLE=10`, `PAD_BUBBLE_V=8`, `PAD_BUBBLE_H=12`, `RADIUS=14`, `USER_FRACTION=0.80`, `AI_FRACTION=1.0`, `ABS_CAP=560`

## 6. 流式处理

### 6.1 流式渲染架构

流式处理分为两个并行通道：

1. **思考流**（`streamingThinking`）：展示模型的思考过程，默认展开，实时更新
2. **回复流**（`streamingContent`）：展示模型的正式回复，显示在 AI 气泡中

### 6.2 原地更新机制

为每个 token 增量触发 `revalidate()` 太昂贵，可能导致布局震荡。因此流式渲染采用**原地更新**策略：

**思考行原地更新**（`updateStreamingThinking`）：
- 首次：创建 `ToolRowFactory.thinkingRow(initiallyExpanded=true, streaming=true)`
- 后续：通过 `findDeepestTextArea(row)` 找到 JTextArea，直接 `area.text = content`
- 流式结束后：思考内容作为 `thinking` 消息固化到 messages 列表

**回复气泡原地更新**（`updateStreamingBubble`）：
- 首次：创建包含 `ChatBubble` 的 assistant 气泡
- 后续：通过 `markdownRenderer.updateInPlace(contentPane, streamingContent)` 原地更新 JTextPane 内容
- 高度变化时触发 `streamingBubble.revalidate()`，让自测量气泡按新内容重测
- 流式结束后：回复作为 `assistant` 消息固化到 messages 列表

**防护机制**：
- `updateStreamingBubble/updateStreamingThinking` 开头检查 `isStreaming` 和 content 非空，防止 completion 之后的延迟 invokeLater 创建空白气泡
- 检查组件 `parent == null`，若已被之前的 rebuild 移除则重置引用

### 6.3 滚动跟随

- `scrollToBottom()`：无条件立即滚动到底部（rebuildConversation、首次创建流式组件、showSelectionCard）
- `autoScrollIfAtBottom()`：仅在用户已在底部 80px 内时才滚动（流式更新每 token）
- 禁止通过 `caretPosition` 驱动滚动：会触发 `scrollRectToVisible` 强制父级 JScrollPane 滚动

### 6.4 SSE 事件处理流程

```
SseClient 后台线程读取流
  │ 每遇到一个事件行 → callback.onData(line)
  ▼
AgentLoop.callAnthropic 回调
  │ parseSseEvent(line)
  ├─► TextDelta → textBuffer.append → edt: onStreaming(textBuffer)
  ├─► ThinkingDelta → thinkingBuffer.append → edt: onThinkingDelta(thinkingBuffer)
  ├─► ToolUseStart → 记录 id/name → inToolUse=true
  ├─► InputJsonDelta → currentToolInput.append(partial)
  └─► ContentBlockStop → toolCalls.add(...) → inToolUse=false
  │
  └─► MessageStop → synchronized(done) { done.notifyAll() }
       │
       ▼
  主线程解除 wait(120_000)
  │ 返回 (finalText, thinking, toolCalls)
  ▼
AgentLoop.run() 继续下一步处理
```

## 7. 性能优化

### 7.1 缓存策略

| 缓存项 | 位置 | 失效条件 | 说明 |
|--------|------|---------|------|
| toolsJson | ToolRegistryV3.cachedJson | registerBuiltIn/registerMcp/registerSkills | 工具列表转 JSON 开销恒定，注册后不变 |
| projectFilesCache | ChatToolWindow | 仅在首次 showFileRefPopup 时填充 | 文件列表快照，不走实时 IO |

### 7.2 惰性加载

- **MCP 工具**：`ApplicationManager.getApplication().invokeLater` 延迟到 `COMPONENTS_LOADED` 之后加载
- **编辑器选区监听**：延迟注册到消息总线就绪后，避免 IDE 启动早期阶段错误
- **System Prompt**：仅在 `initialize()` 时构建一次，含 Skills 注入

### 7.3 流式渲染优化

- **原地更新**非重建：流式回复/思考使用 `setText()` 原地更新而非 `removeAll()+add()`，避免布局震荡
- **增量 diff**：`updateInPlace` 仅在高度变化超过 10px 时返回 true 触发 `revalidate`
- **流式结束后原子性固化**：thinking + assistant 消息在同一个 EDT 块中落地，避免分两次 rebuild 导致重复渲染

### 7.4 Diff 计算保护

- `computeWriteFileDiff()` 检查 `oldLines * newLines > 4,000,000` 时跳过 LCS，避免大文件 O(N*M) DP 导致 ED T冻结或 OOM
- `SimpleDiff` 仅用于几百行以内的文件编辑确认场景

### 7.5 Timer 生命周期管理

- `BrailleSpinnerLabel` 的 `Timer` 通过 `addNotify()/removeNotify()` 管理启动/停止，防止在组件从层级移除后继续运行造成泄漏
- `CodeBlockWrapper` 的复制反馈使用单次 `javax.swing.Timer`（repeats=false），在 EDT 上安全销毁

## 8. 输入增强系统

### 8.1 斜杠命令（`/`）

- 触发：输入框以 `/` 开头时弹出命令菜单
- 弹出位置：紧贴 `inputPanel` 上方，宽度一致
- 键盘交互：Up/Down 移动选择，Enter 确认，Esc 关闭
- 实时筛选：按命令名和描述过滤，最多 10 项
- 内置命令：`/new`、`/plan`、`/init`、`/review`、`/test`、`/stop`、`/clear`
- Skill 集成：`getSkillNames()` 的技能名自动出现在菜单中

### 8.2 文件引用（`@`）

- 触发：在行首或空白后输入 `@` 时弹出文件/目录选择菜单
- 文件和目录混合排列，目录以 `/` 后缀标识
- 最多显示 50 项，实时筛选
- 选择文件：添加 `RefChip` 引用芯片，包含完整文件内容
- 选择目录：生成一级目录列表，不读取文件内容
- RefChip：灰色圆角矩形，显示文件名 + 行号 + 删除按钮

### 8.3 编辑器选区自动引用

- 在编辑器中选中代码 → 自动添加为 `RefChip` 引用芯片
- 3 秒内相同 hash 跳过去重
- 仅在 Code Assistant 工具窗口可见时生效

### 8.4 图片粘贴

- 从剪贴板粘贴图片：`TransferHandler` 拦截 `DataFlavor.imageFlavor`
- 转换：`BufferedImage` → PNG 字节 → Base64
- 存储格式：`ImageData("image/png", base64)`，发送时生成 Claude 原生 image 块
- 显示：图片芯片与 RefChip 同款样式，显示"图片 N" + 删除按钮

### 8.5 加号按钮（`+`）

- 位于输入区左上角，点击复用 `@` 文件引用弹窗逻辑
- 用于快速添加文件引用

## 9. 配置

### 9.1 API Key 存储

- 使用 IntelliJ PasswordSafe（`CredentialAttributes` + `PasswordSafe.instance`）
- Key：`$SERVICE_NAME.API_KEY`
- 不落明文，不在日志中输出

### 9.2 模型选择

- 存储：`PropertiesComponent`，key `AI_Coding_Assistant.MODEL`
- 可用模型：`deepseek-v4-flash`（快速/工具调用）、`deepseek-v4-pro`（复杂编码/深度推理）
- 默认值：`deepseek-v4-pro`

### 9.3 MCP 配置

- 全局：读取 `~/.claude.json` 的 `mcpServers` 字段（Claude Code 格式）
- 项目级：`.mcp.json`（项目根，Claude Code 格式），项目级优先于全局
- 支持 `stdio`（command + args + env）和 `http`（url）两种传输类型

## 10. 错误处理

### 10.1 错误横幅

- 错误：红色背景 `#FFEBEE` / `#462828`，含具体错误消息
- 警告：黄色背景 `#FFF3CD` / `#3C3214`
- 速率限制倒计时：warn 级别，显示剩余秒数

### 10.2 Agent 错误处理

- API 调用失败：`onError("API 调用失败: $detail")` → 停止循环
- 连续工具失败：`consecutiveFailures >= MAX_FAILURES(3)` → 中止
- 空响应处理：`!hasResponse` → `onError("无响应 — 请检查 API Key 和网络连接")`
- DeepSeek V4 仅 thinking 无 text 降级：将 thinking 作为正式回复返回

### 10.3 工具错误展示

- ToolRowFactory 检测结果以 `错误:` / `错误：` / `Error:` 开头 → 渲染 `errorCardRow`
- 红色左栏 + "✕ <toolName> 失败" 标题 + 错误详情（等宽软换行，codeBg 面板）
