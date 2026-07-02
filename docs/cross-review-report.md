# 文档/原型 vs 代码实现 交叉审查报告

> 审查日期：2026-07-02
> 审查范围：全量文档（agent/、ui/、specs/）+ UI 原型 vs 全部 Kotlin 源代码

---

## 总览

> **对抗审查状态：** 已对全部 70 个问题进行独立对抗审查，每个问题派发独立 agent 验证代码行。

| 严重程度 | 原始数量 | CONFIRMED | PLAUSIBLE | REJECTED |
|---------|---------|-----------|-----------|----------|
| 🔴 BUG | 27 | 22 | 4 | 1 |
| 🟡 MISSING | 22 | 15 | 7 | 0 |
| 🟠 UNREASONABLE | 21 | 18 | 3 | 0 |
| **合计** | **70** | **55** | **14** | **1** |

> **REJECTED 的问题（不修复）：**
> - **#12** McpManager HTTP SSE — `readFirstSseData()` 正确读取完整 SSE 事件（循环内累加 data: 行直到空行），不是只读第一行
> - **#21** CompletionContextCollector charset — Kotlin `readText()` 无参调用默认使用 `Charsets.UTF_8`，不存在编码问题
> - **#26** CompletionStats 路径 — 注释省略 `$projectPath` 前缀是 Kotlin 文档常见写法，路径一致
> - **#27** ChatBubbleRenderer max-width — 用户气泡第 57 行确实有 `max-width:480px`，功能完整

---

## 🔴 BUG（需立即修复）

### 1. `AgentLoop.kt:40` — `maxAutoContinue = Int.MAX_VALUE`，文档规定最多 5 次

**文档要求：** max_tokens 自动续写最多连续 5 次，超过后停止续写并标注截断。

**代码现状：**
```kotlin
val maxAutoContinue: Int = Int.MAX_VALUE
```

**修复方案：** 
不修复，修改文档按照Int.MAX_VALUE来算

---

### 2. `AgentLoop.kt` / `AgentSession.kt` — `cancel()` 不杀掉 HTTP 连接和进程

**文档要求：** `cancel()` 应同时关闭 HTTP 连接（`client.close()`）并遍历 `runningProcesses` 调用 `destroyForcibly()`。

**代码现状：**
```kotlin
// AgentSession.kt:77
fun cancel() {
    cancelled = true
    state = State.CANCELLED
}
```

**修复方案：**

`AgentSession.kt` 增加进程销毁：
```kotlin
fun cancel() {
    cancelled = true
    state = State.CANCELLED
    // 销毁所有正在运行的 shell 进程
    runningProcesses.forEach { it.destroyForcibly() }
    runningProcesses.clear()
}
```

`AgentLoop.kt` 增加 HTTP 连接关闭（在 `run()` 的 catch/finally 中调用 `client.close()`）。

---

### 3. `AgentLoop.kt:301` — `stop_sequence` 直接 break，丢弃未处理的 tool_use

**文档要求：** `stop_sequence` 应结束当前轮但保留已渲染内容，标注结束原因。

**代码现状：**
```kotlin
stop_reason == "stop_sequence" -> {
    // ... 直接 break，丢弃未处理的 tool_use
    break
}
```

**修复方案：** 在 break 之前，遍历已解析但未执行的 `toolUseBlocks`，将其状态设置为 `CANCELLED` 并追加到消息列表。

```kotlin
stop_reason == "stop_sequence" -> {
    // 标注未执行的 tool_use 为 CANCELLED
    toolUseBlocks.forEach { block ->
        onEvent(ToolCallCancelled(block.id, "生成被 stop_sequence 终止"))
    }
    break
}
```

---

### 4. `AgentLoop.kt:735-889` — **compact 后消息重复**（builder 无法清空，新旧消息同时发送）

**文档要求：** compact 后旧消息替换为摘要 + 最近消息，下一轮仅发送摘要 + 最近消息。

**代码现状：** `MessageCreateParams.Builder` 在 loop 开始时构建一次，无法中途清空已添加的 messages。compact 更新了 `session.messages` 但 builder 仍持有旧消息，导致下一轮新旧消息同时发送。

**修复方案：** compact 后重建 builder。将 builder 构建从 loop 外移到 loop 内（每次迭代重建），或 compact 后设置标志位触发重建。

```kotlin
// 方案：compact 后设置标志，下一轮迭代时重建 builder
var needsRebuild = false

while (turn < effectiveMaxTurns && !session.cancelled) {
    if (needsRebuild || turn == 0) {
        builder = buildMessageParams(session.messages)
        needsRebuild = false
    }
    // ...
}

// compact 中：
fun doCompact() {
    // ... 生成摘要、更新 session.messages ...
    needsRebuild = true
}
```

---

### 5. `ToolExecutor.kt:484` — Bash 耗时测量 `start` 在 `waitFor()` 之后，始终≈0ms

**文档要求：** Bash 工具返回的耗时字段应反映实际 wall-clock 执行时间。

**代码现状：**
```kotlin
process.waitFor()
val start = System.currentTimeMillis()  // BUG: 在 waitFor() 之后
// ...
val elapsed = System.currentTimeMillis() - start  // ≈0ms
```

**修复方案：** 将 `start` 移到 `waitFor()` 之前。

```kotlin
val start = System.currentTimeMillis()  // 修复：在 waitFor 之前记录
process.waitFor()
// ...
val elapsed = System.currentTimeMillis() - start
```

---

### 6. `PlanExecutor.kt:187` — `resumeNextStep` 标记为 COMPLETED 但不执行（名实不符）

**文档要求：** 方法名暗示"恢复执行下一步"，但实际是"跳过当前步骤并标记完成"。

**代码现状：**
```kotlin
fun resumeNextStep() {
    // 标记当前步骤为 COMPLETED（未执行！）
    currentPlan?.let { plan ->
        plan.plans[plan.currentPlanIndex].status = PlanItem.ItemStatus.COMPLETED
        plan.currentPlanIndex++
    }
}
```

**修复方案：** 
代码未被调用，删除

---

### 7. `MultiAgentManager.kt:540` — `cleanupSubSession` 不释放文件锁，崩溃后锁泄漏

**文档要求：** 子 Agent 崩溃时应释放 semaphore、文件写锁、销毁 shell 进程。

**代码现状：**
```kotlin
fun cleanupSubSession(sessionId: String) {
    // 销毁进程 ✓
    // 释放 semaphore ✓
    // 释放文件锁 ✗ — 从未调用 fileLocks.remove()
}
```

**修复方案：** 在 `cleanupSubSession` 中遍历子 Agent 持有的文件锁并释放。

```kotlin
fun cleanupSubSession(sessionId: String) {
    // 释放该子 Agent 持有的所有文件锁
    subAgentFileLocks[sessionId]?.forEach { file ->
        fileLocks.remove(file)?.let { lock ->
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }
    subAgentFileLocks.remove(sessionId)
    // ... 其余清理逻辑
}
```

---

### 8. `SessionStore.kt` — AgentSession 状态**序列化但从不反序列化**（重启后全部 IDLE）

**文档要求：** AgentSession 状态应持久化并在恢复时正确加载（PROCESSING→IDLE, EXECUTING plan→PAUSED）。

**代码现状：**
```kotlin
// save(): 写入 dto.state = session.state.name  ✓
// load(): 从未读取 dto.state 设置 session.state  ✗
// 所有加载的 session 状态都是默认的 IDLE
```

**修复方案：** 在 `load()` 中增加状态恢复逻辑。

```kotlin
fun load(sessionId: String): AgentSession {
    val dto = readJson(sessionId)
    val session = AgentSession(dto.id)
    // 恢复状态（重启后重置运行中状态）
    val rawState = dto.state?.let {
        try { State.valueOf(it) } catch (e: Exception) { null }
    }
    session.state = when (rawState) {
        State.PROCESSING, State.AWAITING_APPROVAL, State.EXECUTING -> State.IDLE
        State.PAUSED -> State.PAUSED
        else -> State.IDLE
    }
    // ... 其余恢复逻辑
}
```

---

### 9. `SessionStore.kt:185` — `approvedTools` 错误合并到 `firstToolUseDone`

**文档要求：** `approvedTools` 和 `firstToolUseDone` 是两个独立概念：
- `approvedTools`：用户批准的工具白名单（跨会话持久化）
- `firstToolUseDone`：已跳过首次审批提示的工具

**代码现状：**
```kotlin
// 加载时将 approvedTools 全部加入 firstToolUseDone
session.firstToolUseDone.addAll(approvedTools)
```

**修复方案：** 删除合并逻辑，两者独立维护。

```kotlin
// 分别恢复
session.approvedTools.addAll(dto.approvedTools ?: emptyList())
// firstToolUseDone 从 dto.firstToolUseDone 恢复，不合并 approvedTools
session.firstToolUseDone.addAll(dto.firstToolUseDone ?: emptyList())
```

---

### 10. `SessionManager.kt:96` — `outputTokens` 硬编码为 0，成本估算严重失准

**文档要求：** `getTotalTokenUsage()` 应正确区分 input/output tokens 用于成本计算（input: $0.27/1M, output: $1.10/1M）。

**代码现状：**
```kotlin
val outputTokens = 0L  // BUG: 硬编码为 0
```

**修复方案：** 从 session 消息中累加实际的 outputTokens。

```kotlin
val outputTokens = sessions.flatMap { it.messages }
    .sumOf { it.tokenUsage?.outputTokens ?: 0L }
val inputTokens = sessions.flatMap { it.messages }
    .sumOf { it.tokenUsage?.inputTokens ?: 0L }
```

---

### 11. `McpManager.kt:77` — 30s 请求超时**完全未实现**（有字段无逻辑）

**文档要求：** MCP JSON-RPC 请求超过 30 秒无响应应标记 DISCONNECTED。

**代码现状：** `lastRequestTimeMs` 字段被声明、重置、设置，但没有看门狗线程检查超时。

**修复方案：** 添加 `ScheduledExecutorService` 周期性检查超时。

```kotlin
private val timeoutChecker = Executors.newSingleThreadScheduledExecutor()

init {
    timeoutChecker.scheduleAtFixedRate({
        val elapsed = System.currentTimeMillis() - lastRequestTimeMs
        if (state == McpServerState.RUNNING && elapsed > 30_000) {
            updateState(McpServerState.DISCONNECTED)
            reconnect()
        }
    }, 5, 5, TimeUnit.SECONDS)
}
```

---

### 12. `McpManager.kt:912-973` — HTTP SSE 只读第一个 `data:` 事件，后续事件丢弃

**文档要求：** MCP HTTP 传输应处理 SSE 流，持续接收服务器推送的消息。

**代码现状：**
```kotlin
// 只读取第一个 data: 行，后续 SSE 事件被忽略
val line = reader.readLine()
if (line.startsWith("data:")) {
    // 处理第一个事件后退出
}
```

**修复方案：** 使用循环持续读取 SSE 事件流，直到连接关闭。

```kotlin
while (reader.readLine().also { line = it } != null) {
    if (line.startsWith("data:")) {
        val data = line.removePrefix("data:").trim()
        handleJsonRpcMessage(data)
    }
}
```

---

### 13. `AppSettingsService.kt:42` — 默认 commitPrompt **缺少 `{diff}` 占位符**

**文档要求：** commitPrompt 默认模板应包含 `{diff}` 占位符，设置 UI 也标注了 `{diff}` 的作用。

**代码现状：**
```kotlin
val DEFAULT_COMMIT_PROMPT = "Based on the diff, generate a Conventional Commits message..."
// 没有 {diff} 占位符！
```

**修复方案：** 在默认模板中加入 `{diff}` 占位符。

```kotlin
val DEFAULT_COMMIT_PROMPT = """请基于以下 git diff 生成一条 Conventional Commits 规范的 commit message。

{diff}

要求：
- 使用中文描述
- 格式：<type>(<scope>): <description>
"""
```

---

### 14. `ChatBubbleRenderer.kt:25` — 代码块字体 12px，文档规定 13px

**文档要求：** Code = `JetBrains Mono Regular, 13px`（design-system.md）。

**代码现状：**
```kotlin
private val CODE_FONT = Font("JetBrains Mono", Font.PLAIN, 12)
```

**修复方案：**
```kotlin
private val CODE_FONT = Font("JetBrains Mono", Font.PLAIN, 13)
```

---

### 15. `ChatBubbleRenderer.kt:218` — Agent 气泡边框/填充顺序颠倒

**文档要求：** `border-left=3px #3B82F6, padding=12px` — 蓝色竖线在最左侧边缘，填充在内。

**代码现状：**
```kotlin
CompoundBorder(EmptyBorder(12, 12, 12, 12), MatteBorder(0, 3, 0, 0, accentColor))
// 结果：填充在外层，边框在填充内部 → 蓝色线距左边缘 12px
```

**修复方案：** 交换 CompoundBorder 的内外层顺序。

```kotlin
CompoundBorder(MatteBorder(0, 3, 0, 0, accentColor), EmptyBorder(12, 12, 12, 12))
// 结果：边框在外层（左边缘），填充在内
```

---

### 16. `ChatBubbleRenderer.kt:207` — 流式气泡每 token 完全重建（视觉闪烁）

**文档要求：** "每个 token 追加到缓冲，完整 Block 闭合后通过 parseMarkdown() 解析"，应增量更新。

**代码现状：** 每个 token 都 remove 旧组件、创建新组件、revalidate，导致闪烁。

**修复方案：** 使用增量更新 — 仅在段落闭合或代码块闭合时才重建对应组件。中间 token 只追加到 buffer 不触发重建。或者使用 `DebounceTimer(30ms)` 批量更新。

```kotlin
// 使用 Timer 实现 30ms 批量渲染
private var flushTimer: Timer? = null

fun onStreamingToken(token: String) {
    buffer.append(token)
    flushTimer?.cancel()
    flushTimer = Timer(30) {  // 30ms 静默后刷新
        SwingUtilities.invokeLater { renderBuffer() }
    }
}
```

---

### 17. `SelectionListener.kt:33` — 返回 `presentableName` 而非完整相对路径

**文档要求：** FileRef 应包含完整相对路径（如 `src/main/kotlin/service/UserService.kt`）。

**代码现状：**
```kotlin
val path = file.presentableName  // 仅返回文件名，如 "UserService.kt"
```

**修复方案：** 使用相对于项目根目录的路径。

```kotlin
val path = VfsUtil.getRelativePath(file, project.baseDir)
    ?: file.presentableName  // fallback
```

---

### 18. `ToolCallCard.kt:35` — 状态图标用 `Step_1/Step_2`，文档规定 `Plan_0/Plan_4`

**文档要求：**
- PENDING → `AllIcons.Process.Plan_0`
- EXECUTING → `AllIcons.Process.Plan_4`（带旋转动画）

**代码现状：**
```kotlin
PENDING -> AllIcons.Process.Step_1
EXECUTING -> AllIcons.Process.Step_2  // 无旋转动画
```

**修复方案：**
```kotlin
PENDING -> AllIcons.Process.Plan_0
EXECUTING -> AllIcons.Process.Plan_4  // 配合旋转动画
```

---

### 19. `ToolCallCard.kt:172-215` — `setState(DONE)` expand → `setResult()` collapse 导致闪烁

**文档要求：** DONE 状态应平滑过渡展示结果。

**代码现状：** `setState(DONE)` 展开卡片 → `setResult()` 立即折叠 → 视觉闪烁。

**修复方案：** `setResult()` 检查当前状态，若已是 DONE 则不折叠。

```kotlin
fun setResult(result: String) {
    this.result = result
    if (state != ToolCallState.DONE) {  // 已是 DONE → 保持展开
        if (canCollapse) isCollapsed = true
    }
    applyCollapseState()
}
```

---

### 20. `ToolCallCard.kt:73` — 结果区 max-height 180px，文档规定 240px

**修复方案：**
```kotlin
// 修改前
resultScrollPane.maximumSize = Dimension(Int.MAX_VALUE, 180)
// 修改后
resultScrollPane.maximumSize = Dimension(Int.MAX_VALUE, 240)
```

---

### 21. `CompletionContextCollector.kt:86` — 使用默认 charset 而非 UTF-8

**文档要求：** 文件内容读取应使用 UTF-8 编码。

**代码现状：**
```kotlin
String(file.contentsToByteArray())  // 平台默认 charset
```

**修复方案：**
```kotlin
String(file.contentsToByteArray(), Charsets.UTF_8)
```

---

### 22. `GenerateCommitAction.kt:129` — SSE 流式写入后 `setText` 覆盖用户编辑

**文档要求：** 流式输出应显示在 commit 面板中，但不应覆盖用户的并发编辑。

**代码现状：** SSE 逐个 delta 写入 → 最终 `setText` 全量覆盖，用户中途编辑丢失。

**修复方案：** 取消最终的全量 `setText`，依赖 SSE 增量写入即可。或保存用户编辑并在覆盖前恢复。

```kotlin
// 删除或注释最终的全量覆盖
// app.invokeAndWait { app.runWriteAction { editor.document?.setText(message) } }
// 增量写入已经完成，无需再覆盖
```

---

### 23. `FileReferenceResolver.kt:29` — 使用默认 charset 读文件

**修复方案：** 同 #21，使用 `Charsets.UTF_8`。

```kotlin
file.readText(Charsets.UTF_8)
```

---

### 24. `DeepSeekFimClient.kt:62` — `FimRequest` 缺少 `stop` 字段

**文档要求：** FIM 补全应发送 `stop` 参数控制生成结束位置。

**修复方案：**
```kotlin
data class FimRequest(
    val model: String,
    val prompt: String,
    val suffix: String,
    val max_tokens: Int,
    val temperature: Double = 0.0,
    val stop: List<String> = listOf("\n\n", "\r\n\r\n")  // 新增
)
```

---

### 25. `ChatViewModel.kt` — `sendMessage()` 签名与文档接口不匹配

**文档接口：** `sendMessage(text: String, attachments: List<FileRef>, images: List<ImageRef>)`

**代码现状：** `sendMessage(text: String)`

**修复方案：** 将签名对齐文档，或在 `sendMessage` 内部从 `inputState` 获取 attachments/images（当前方式本就可行，补充文档注释说明参数来源）。

```kotlin
/**
 * 发送用户消息。attachments 和 images 从 inputState 中获取。
 * @see InputState.manualRefs
 * @see InputState.images
 */
fun sendMessage(text: String)
```

---

### 26. `CompletionStats.kt:136` — 文件路径 `.code-assistant/` 但注释说 `.claude/`

**修复方案：** 统一注释与实际路径。

```kotlin
// 自动持久化统计数据到 .code-assistant/completion-stats.json
```

---

### 27. `ChatBubbleRenderer.kt` — 用户气泡缺少 `max-width` 包裹

**文档要求：** 用户气泡 `maxWidth=70%`，Agent 气泡 `maxWidth=85%`。Agent 气泡已有 `<body style='width:...'>` 包裹，用户气泡没有。

**修复方案：** 用户气泡增加宽度限制。

```kotlin
// 用户气泡 HTML 中增加
<body style='width:${maxWidth}px'>...</body>
```

---

## 🟡 MISSING（尚未实现）

### 28. `AgentLoop.kt` — 无 30ms 流式 token 批量合并
**方案：** 在 `onToken` 回调中增加 `Timer(30ms)` 批量刷新。首次 token 立即渲染，后续 30ms 内收到的 token 缓冲后一起渲染。

### 29. `ToolModels.kt` — WebFetch/Skill 描述缺少上限声明
**方案：** 在 `@JsonClassDescription` 中增加内容截断上限说明，如 "最大返回 8000 字符"。

### 30. `ToolExecutor.kt` — 无 8 种线程分发模式
**方案：** 文档描述的 8 种线程模式是针对不同工具类型的执行策略（如 Read 在 EDT、Bash 在 PooledThread）。当前是 plain sync `when`。按文档补充各工具的线程分发逻辑。

### 31. `MultiAgentManager.kt` — `EXPLORE_TOOLS` 定义但从未使用
**方案：** 在 `subAgentToolsFilter` 中根据 Agent 工具的 `subagent_type` 参数选择 `EXPLORE_TOOLS` 或 `GENERAL_PURPOSE_TOOLS`。

### 32. `MultiAgentManager.kt` — 无文件变更通知给父 Agent
**方案：** 子 Agent 完成后，通过 `MessageBus` 发布 `FileChangedEvent` 通知父 Agent 更新 `fileStamps`。

### 33. `SessionStore.kt` — `FileNotFoundException` 不从索引移除
**方案：** 在 `checkCorrupted()` 中检查文件存在性，不存在则从 `readIndex()` 的结果中移除对应条目。

### 34. `SessionManager.kt` — 无软删除/撤销支持
**方案：** `deleteSession()` 改为标记 `deleted = true` 而非物理删除。在 Sessions 页面增加 "撤销" 按钮。物理删除作为独立方法 `purgeSession()`。

### 35. `SessionManager.kt` — IDE 重启缺少 AgentSession 状态修复
**方案：** 在 `load()` 中已修复（见 BUG #8）。额外在 `ChatPage` 初始化时检查并重置 Plan 状态。

### 36. `SettingsConfigurable.kt` — commitPrompt 保存无 `{diff}` 验证
**方案：** 在 `apply()` 中保存前检查 `commitPrompt.contains("{diff}")`，无则弹 toast 警告。

### 37. `ChatPage.kt` — 缺少"暂停自动滚动时显示 ↓ 按钮"
**方案：** 当 `isAutoScrollPaused = true` 时，在右下角显示一个 `↓` 浮动按钮（JButton），点击恢复自动滚动并滚动到底部。

### 38. `ChatPage.kt` — 标题栏缺少"新建会话"按钮
**方案：** 在标题栏添加 `[+]` 按钮（`AllIcons.General.Add`），点击触发 `ChatViewModel.newSession()`。

### 39. `WelcomePage.kt` — 缺少"已有 Key？"提示文本
**方案：** 在 API Key 输入框下方添加 `I18n.get("welcome.existing_key_hint")` 标签。

### 40. `SettingsPage.kt` — About 卡片缺少 GitHub URL
**方案：** 在 About 区域添加可点击链接：`https://github.com/jin123456bat/code-assistant`。

### 41. `McpPage.kt` — 崩溃 Server 缺少"查看日志"按钮
**方案：** 在 CRASHED/ERROR 状态的 server 卡片上添加 `[查看日志]` 按钮，打开 IDE 日志面板或显示 server 的最后输出。

### 42. `McpPage.kt` — Schema 校验失败缺少 ⚠️ 标记
**方案：** 在 `McpServerState` 增加 `SCHEMA_WARNING` 状态，卡片渲染时显示 ⚠️ 和失败原因。

### 43. `SkillsPage.kt` — 缺少 command 冲突检测
**方案：** 在 `SkillManager.loadSkills()` 中检测 command 重复，冲突的两个 skill 均设置 `hasConflict = true`，UI 显示 ⚠️ 冲突提示。

### 44. `SkillsPage.kt` — 配置路径不可点击
**方案：** 将目录路径文本改为 `JButton`（`HyperlinkLabel` 样式），点击打开项目目录。

### 45. `ChatInputArea.kt` — 底部栏缺少 `[@]` 提示标签
**方案：** 在 `[+]` 按钮和发送按钮之间添加 `[@]` 标签按钮，点击触发文件选择弹窗。

### 46. `TabBar.kt` — 缺少 `getSelected()` 公共方法
**方案：** 添加 `fun getSelected(): PageId = selected` 公共方法。

### 47. `ToolCallCard.kt` — 缺少 `setChildTokenCost()` 方法
**方案：** 添加方法：
```kotlin
fun setChildTokenCost(tokens: Long, cost: BigDecimal) {
    // 在卡片底部显示子 Agent token 消耗
}
```

### 48. `PlanCard.kt` — `PlanState.COMPLETED` 枚举定义但从不赋值
**方案：** 在所有 `plans` 项都标记完成时，设置 `planState = COMPLETED`。或移除未使用的枚举值。

### 49. `PsiCompletionStrategy.kt` — 仅支持 PHP，其他语言返回 null
**方案：** 扩展 `collectContext()` 支持 Kotlin/Java/Python/JS/TS。至少覆盖项目使用的主要语言。

---

## 🟠 UNREASONABLE（不合理实现）

### 50. `ToolExecutor.kt:452` — 用 `Runtime.exec()` 而非 IntelliJ `ProcessHandler`
**方案：** 替换为 `OSProcessHandler`：
```kotlin
val handler = OSProcessHandler(
    GeneralCommandLine("/bin/bash", "-c", command)
        .withWorkDirectory(dir)
)
handler.startNotify()
```

### 51. `ToolExecutor.kt:480` — `waitFor()` 无超时安全网
**方案：** 即使 `timeoutSec == 0`，也添加一个硬上限（如 300 秒）防止永久挂起。
```kotlin
val effectiveTimeout = if (timeoutSec == 0) 300 else timeoutSec
process.waitFor(effectiveTimeout, TimeUnit.SECONDS)
```

### 52. `PlanExecutor.kt:237` — `removePlan` 标记 CANCELLED 而非真正删除
**方案：** 从 `plan.plans` 列表中真正移除该项，或重命名为 `cancelPlan` 并保留两种操作（删除 vs 取消）。

### 53. `MultiAgentManager.kt` — 文件锁访问是实例方法但操作静态 Map
**方案：** 将 `acquireFileLock` / `releaseFileLock` 移至 `companion object`，或提取为独立的 `FileLockManager` 单例。

### 54. `SessionStore.kt` — 索引 read-modify-write 在锁外读
**方案：** 将 `readIndex()` 调用移到 `writeIndexWithLock` 的锁内部。

### 55. `SessionStore.kt` — 索引 totalTokens 从消息重算而非用会话级总计
**方案：** 索引 `totalTokens` 应使用 `dto.totalTokens`（与 session JSON 中的值一致）。

### 56. `SessionManager.kt:266` — 通过反射设置 `val title`
**方案：** 将 `AgentSession.title` 从 `val` 改为 `var`，或使用 `AtomicReference<String>` 包装。

### 57. `SessionManager.kt:134` — `aggregateChildTokens` 无循环检测
**方案：** 添加 `visited` Set 防止循环递归。
```kotlin
fun aggregateChildTokens(sessionId: String, visited: MutableSet<String> = mutableSetOf()): Long {
    if (!visited.add(sessionId)) return 0L  // 循环检测
    // ...
}
```

### 58. `SessionDTO.kt:107` — `tokenUsage.timestamp` 在文档外，从未被业务逻辑使用
**方案：** 如无用途则删除字段，或者用于会话排序/时间线展示。

### 59. `SkillManager.kt:77` — `reloadSkills()` 是空操作
**方案：** 实现真正的重载逻辑（清理缓存 + 重新扫描），或在文档中注明当前实现每次都是直接读取。

### 60. `AppSettingsService.kt:69` — 模型下拉菜单锁定单一模型
**方案：** 移除 UI 中的 model 下拉菜单（既然不可更改），或开放配置允许选择其他模型。

### 61. `SettingsConfigurable.kt:51` — 补全复选框 bypass 确认/取消
**方案：** 移除 `ActionListener`，在 `apply()` 中统一保存所有设置。

### 62. `ChatPage.kt:506` — `isReducedMotionEnabled()` 与 `AppAnimations` 重复实现
**方案：** 删除 `ChatPage` 中的重复方法，统一使用 `AppAnimations.isReducedMotionEnabled()`。

### 63. `ChatPage.kt:546` — `AnimatedBubbleWrapper` 硬编码动画值
**方案：** 使用 `AppAnimations.MESSAGE_APPEAR_DURATION` 等常量替代硬编码值。

### 64. `SessionsPage.kt:177` — `e.x > 30` 像素判断脆弱
**方案：** 使用组件命中测试或布局计算替代硬编码像素值。
```kotlin
// 用 checkbox 组件的 bounds 判断
if (e.x < checkbox.width + padding) { /* checkbox 点击 */ }
```

### 65. `AppColors.kt:69` — `tagBorder` 暗色值不匹配规范
**方案：** `JBColor(0xBFDBFE, 0x1E40AF)` — 将暗色从 `0x2563EB` 改为 `0x1E40AF`。

### 66. `ChatInputArea.kt:139` — `adjustTextAreaRows()` 在首次布局前静默失败
**方案：** 添加 `HierarchyListener` 或使用 `SwingUtilities.invokeLater` 在布局完成后重新计算。

### 67. `MultiAgentBlock.kt:240` — 使用 `cardBg` 而非 `multiAgentBg`
**方案：** 使用 `AppColors.multiAgentBg`（`JBColor(0xF0F7FF, 0x0F1D2F)`）。

### 68. `ChatViewModel.kt:723` — `clearSession()` 的 approvedTools 保存-还原是空操作
**方案：** 删除冗余的 preserve-and-readd 逻辑。

### 69. `McpConfigStore.kt` — 6 个配置级别而非文档的 3 个
**方案：** 更新文档记录所有 6 个配置来源及其优先级顺序，或移除未记录的来源。

### 70. `ApiKeyValidator.kt` — 引入 Anthropic SDK 仅做简单 key 校验
**方案：** 用简单的 `HttpURLConnection` GET 请求到 `https://api.deepseek.com/v1/models` 替代 Anthropic SDK 调用。

---

## 修复优先级建议

### 第一批（关键 BUG，影响核心功能）— 1-12 项
Compact 消息重复、Session 状态丢失、cancel 不杀进程、MCP 超时未实现、approvedTools 数据损坏。

### 第二批（UI BUG，影响用户体验）— 14-20 项
字体、边框、闪烁、路径、图标、字符编码问题。

### 第三批（MISSING 功能）— 28-49 项
按实际需求逐步实现。

### 第四批（UNREASONABLE 改进）— 50-70 项
重构改进，非阻塞性问题。
