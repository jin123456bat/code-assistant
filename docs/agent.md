# Agent 智能对话

Agent 模式是 Code Assistant 的核心功能——一个对齐 Claude Code 的智能编程代理。支持聊天面板、自主工具调用、Plan
Mode、多 Agent、Skill 系统和 MCP 外部工具。

> **关联文档：** 技术契约见 [`tech-spec.md`](tech-spec.md)（组件接口、数据契约、线程模型、JSON
> Schema、System Prompt、Tool 返回值格式），UI/UX 设计规范见 [`ui-ux-spec.md`](ui-ux-spec.md)。

## 一、架构分层

```
┌──────────────────────────────────────────────────────┐
│  UI Layer (多页面架构)                                 │
│  顶部 TabBar: Welcome | Chat | Sessions | TokenUsage    │
│            MCP | Skills | Settings                      │
│  CardLayout 容器切换页面                                 │
├──────────────────────────────────────────────────────┤
│  Chat UI: JTextPane (Markdown→HTML) + EditorTextField │
│  (代码块 block-level 渲染) + ToolCallCard + PlanCard  │
├──────────────────────────────────────────────────────┤
│  Session Layer: SessionManager + SessionStore (JSON)  │
├──────────────────────────────────────────────────────┤
│  Agent Layer: AgentLoop (while 循环) + PlanExecutor  │
│  MultiAgentManager                                     │
├──────────────────────────────────────────────────────┤
│  Tool Layer: ToolRegistry (内置 Tools + MCP Tools)    │
│  ReadFile/WriteFile/EditFile/RunShell/ListFiles/      │
│  SearchContent/ReadLints/SpawnAgent                    │
├──────────────────────────────────────────────────────┤
│  Provider: AnthropicOkHttpClient → DeepSeek            │
│  Skill System: SkillManager → SKILL.md 扫描            │
│  MCP: McpManager (进程生命周期)                         │
└──────────────────────────────────────────────────────┘
```

### 核心数据流

```
用户输入 → AgentLoop.run(task)
  → AnthropicOkHttpClient.messages().createStreaming(params)
  → forEach chunk:
      ContentBlockDelta.text → 流式渲染到气泡
      ContentBlockStart.toolUse → executeTool() → 结果追加到 params
  → while 循环直到 stop_reason="end_turn"
  → SessionStore.save()
```

## 二、多页面架构

顶部 TabBar 导航 + CardLayout 容器，7 个页面。**TabBar 根据 API Key 状态动态显隐：**

- **无 API Key**：仅显示 Welcome tab
- **有 API Key**：隐藏 Welcome tab，显示 Chat / Sessions / Token Usage / MCP / Skills / Settings

| 页面          | 路由          | 说明                                  |
|-------------|-------------|-------------------------------------|
| Welcome     | `/welcome`  | 无 API Key 时的唯一页面，配置 Key 后自动隐藏       |
| Chat        | `/chat`     | 主聊天面板，Key 已配置时的默认页面                 |
| Sessions    | `/sessions` | 会话历史列表、搜索、恢复、删除                     |
| Token Usage | `/usage`    | Token 消耗统计（按会话/日/月聚合，sparkline 趋势图） |
| MCP         | `/mcp`      | MCP Server 管理（添加/启停/测试连接）           |
| Skills      | `/skills`   | Skills 列表、启用/禁用                     |
| Settings    | `/settings` | 关于信息、版本、快捷键参考                       |

**页面生命周期：** 首屏只创建 Welcome + Chat，其他页面首次点击时懒加载。CardLayout 不销毁隐藏页面，自动状态保持。页面间通过
`project.messageBus` 事件总线同步。

### IDE Settings（SettingsConfigurable）

Agent 相关设置统一在 **IDE Settings > Tools > Code Assistant** 中管理：

| 配置项           | 默认值               | 说明                      |
|---------------|-------------------|-------------------------|
| API Key       | —                 | PasswordSafe 安全存储，显示掩码  |
| Model         | `deepseek-v4-pro` | 下拉选择（V4 Flash / V4 Pro） |
| Agent 最大轮次    | 15（0=不限）          | 达到上限后自动终止               |
| 多 Agent 并发上限  | 3                 | 父 + 子 Agent 总计并发数       |
| 代码补全          | 启用                | 开关                      |
| Commit Prompt | 默认模板              | 自定义 commit message 模板   |

### 快捷键

| 快捷键（Win/Linux） | 快捷键（macOS）    | 操作          |
|----------------|---------------|-------------|
| `Ctrl+Shift+K` | `Cmd+Shift+K` | 打开/关闭聊天面板   |
| `Enter`        | `Enter`       | 发送消息        |
| `Shift+Enter`  | `Shift+Enter` | 输入框换行       |
| `Escape`       | `Escape`      | 停止生成 / 关闭弹窗 |
| `Ctrl+Shift+N` | `Cmd+Shift+N` | 新建会话        |
| `↑`（空输入框）      | `↑`（空输入框）     | 填充上一条消息     |

### API Key 状态机

```
UNSET ──saveKey()──→ VALIDATING ──200──→ VALID
  │                      │
  │                      ├─ 401 → INVALID
  │                      └─ 超时 → UNKNOWN
  │                                │
  └────────────────────────────────┘ (用户可重新输入)
```

保存 Key 后**乐观导航**立即跳转 Chat，验证在后台继续。VALID 静默通过，INVALID → toast "API Key 无效"
，UNKNOWN → toast "网络不可用"。

## 三、Agent Loop（核心逻辑）

```
while (turn < maxTurns && !cancelled):
  ├─ compactIfNeeded()                             ← 每次 API 请求前检查
  ├─ if (turn >= maxTurns * 0.6):                  ← 轮次预警（规划中）
  │    附加系统提示"已执行 N 轮，评估剩余工作量"
  ├─ client.messages().createStreaming(params)    ← OkHttp, background
  │    .forEach { chunk → invokeLater { UI } }
  │
  ├─ for each toolUse:
  │    ├─ 审批检查（首次弹确认）
  │    │    CountDownLatch ← 等待用户操作（无超时）
  │    │
  │    ├─ 执行（8 个工具）:
  │    │    readFile      → VFS (bg)
  │    │    writeFile     → invokeAndWait { WriteCommandAction }
  │    │    editFile      → invokeAndWait { WriteCommandAction }
  │    │    runShell      → ProcessHandler (bg, 流式)
  │    │    listFiles     → VFS + FilenameIndex (bg)
  │    │    searchContent → PsiSearchHelper (bg)
  │    │    readLints     → IDE Inspection (bg)
  │    │    spawnAgent    → 新 AgentLoop (bg)
  │    │
  │    └─ 结果显示在 ToolCallCard
  │
  ├─ params.add(toolUses + toolResults)           ← 追加到消息
  ├─ turn++
  │
stop: cancelled → client.close() + runningProcesses.forEach { destroyForcibly() }
```

### 8 个工具（对齐 Claude Code 能力集）

| 工具              | 对应 Claude | 实现                   | 说明                      |
|-----------------|-----------|----------------------|-------------------------|
| `readFile`      | Read      | PSI/VFS              | 读文件内容，支持行范围             |
| `writeFile`     | Write     | WriteCommandAction   | 覆盖写入整个文件                |
| `editFile`      | Edit      | WriteCommandAction   | 精确字符串替换（old→new）        |
| `runShell`      | Bash      | GeneralCommandLine   | Shell 命令，超时由 LLM 传入（必填） |
| `listFiles`     | Glob      | VFS + FilenameIndex  | 目录结构 + 文件名模式匹配          |
| `searchContent` | Grep      | PsiSearchHelper + 正则 | 项目内文本搜索                 |
| `readLints`     | —         | IDE Inspections      | 读取当前文件的 errors/warnings |
| `spawnAgent`    | Task      | AgentLoop 复用         | 启动子代理处理子任务              |

### 工具返回结果截断策略

| 工具                     | 最大返回行数              | 截断后行为                       |
|------------------------|---------------------|-----------------------------|
| `readFile`             | 500 行               | 尾部注 `...(共 N 行，已截断到 500 行)` |
| `searchContent`        | 50 条匹配              | 尾部注 `...(共 N 条，已截断到 50 条)`  |
| `listFiles`            | 200 条目              | 尾部注 `...(共 N 条目，已截断到 200)`  |
| `runShell`             | 200 行               | 传给 LLM 前截断，完整输出保留在 UI 卡片    |
| `readLints`            | 50 条诊断              | 按 severity 排序               |
| `editFile`/`writeFile` | 写入 ≤ 3000 行         | 超过拒绝并返回错误                   |
| `spawnAgent`           | 返回 ≤ 2000 tokens 摘要 | 完整结果保存到子 session            |

### Shell 安全

- 无硬性超时（LLM 自行判断并在 tool call 时传入）
- 实时流式输出 → batch 100ms → invokeLater 更新 UI（防 EDT 洪水）
- 工作目录限定项目根
- 危险模式二次确认：`rm -rf /`、`git push --force`、`sudo`、`chmod 777`（不可跳过）

### `stop_reason="max_tokens"` 自动续写

LLM 输出被 max_tokens 截断时，自动发送 "继续" 消息续写：

```
"max_tokens" → 自动追加 user message "继续" → continue while
  - "继续"不持久化（仅临时在 params.messages 中）
  - 不计入 turn 计数
  - 最多连续续写 5 次
```

### 流式输出中断处理

**主动停止（Escape）：**

- 已渲染文本：保留 + 持久化
- 当前未 flush 内容：丢弃
- 未执行 tool call：CANCELLED，不持久化
- 正在执行工具：`destroyForcibly()` 终止
- Agent → IDLE

**网络断连：**

- 已渲染内容：保留 + 持久化（尾部标注 `[连接中断]`）
- 用户点击 [重试]：发送相同 params 重新开始

## 四、AgentSession 状态机

```
IDLE ──sendMessage()──→ PROCESSING ──LLM 返回输出──→ IDLE
  │                        │
  │                        ├── toolUse → AWAITING_APPROVAL（如有审批）
  │                        │     ├─ 确认 → EXECUTING → PROCESSING
  │                        │     └─ 拒绝 → IDLE（[重审] 按钮可重试）
  │                        │
  │                        └── 无审批 → EXECUTING → PROCESSING
  │
  ├── 用户点 Stop → CANCELLED（清理 → IDLE）
  ├── Plan 暂停 → PAUSED
  └── API error → ERROR（显示错误气泡 + [重试]）
```

状态说明：

| 状态                | 含义                              |
|-------------------|---------------------------------|
| IDLE              | 可接受输入，无进行中的 Agent 操作            |
| PROCESSING        | LLM 流式输出中，或 tool 结果已提交等待 LLM 响应 |
| AWAITING_APPROVAL | 弹出审批 dialog，等待用户操作              |
| EXECUTING         | 工具正在执行（非阻塞，UI 显示进度）             |
| PAUSED            | 计划步骤完成后暂停，等待用户操作                |
| CANCELLED         | 用户中断，清理中                        |
| ERROR             | API 调用失败或 tool 执行异常             |

## 五、Plan Mode

### 计划卡片

计划卡片固定在 Chat 页面顶部，作为持久状态栏。计划完成/终止后卡片消失。

计划**不自动连续执行**。每步执行完后暂停，通过 PlanCard 内联按钮控制：

```
Step N 执行完 → 自动暂停
  → 用户查看结果 → PlanCard 内联按钮:
      ├─ [▶ 继续] → 执行下一步
      ├─ [↻ 重试] → 重新执行当前 step
      ├─ [⏭ 跳过] → 标记 SKIPPED
      └─ [✕ 取消计划] → 所有剩余步骤 CANCELLED
```

### Plan 生命周期

- **未开始时可删除**：[✕] 直接删除
- **暂停**：当前 step 完成后自动暂停
- **仅下一个待执行 Step 可点击 [▶]**：非连续 Step 的 [▶] 禁止
- **暂停时可自由聊天**：消息追加到同一 Session
- **计划持久化**：跨会话保留，关闭 IDE 再打开可恢复

### Plan 恢复边界处理

| 场景             | 行为                                      |
|----------------|-----------------------------------------|
| Step 引用文件已删除   | 标记 ERROR，等待用户决定                         |
| Step 引用文件被外部修改 | 检查 `modificationStamp` → 提示 diff → 用户决定 |
| 用户修改了后续步骤      | 持久化到 `plan.modifiedSteps`               |
| 多个会话各有暂停计划     | 允许，每个独立存储                               |

### Plan JSON Schema（存于 Session JSON 的 `plan` 字段）

```json
{
  "plan": {
    "status": "PAUSED",
    "summary": "将 UserService.findById 改为 suspend",
    "currentStep": 2,
    "steps": [
      {
        "id": "step-1",
        "description": "读取文件",
        "tool": "readFile",
        "files": [
          "UserService.kt:40-60"
        ],
        "status": "DONE",
        "result": "成功读取 156 行",
        "fileStamps": {
          "UserService.kt": 123456
        }
      }
    ],
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:45:00Z"
  }
}
```

### LLM 自动判断并创建计划（规划中）

当前 Plan Mode 需要用户手动输入 `/plan` 触发，存在两个问题：

1. **用户不确定该不该用**：不知道任务会不会很长，等发现时 Agent 已执行很多轮
2. **LLM 不能主动拆解**：即使 LLM 在执行中意识到任务复杂，也无法主动创建计划

以下三个方案成本递增，可逐步落地。

#### 方案一：System Prompt 自判指令（成本最低）

在 System Prompt 中加入复杂度预判规则，让 LLM 在开始执行前先评估任务范围，复杂任务**直接在回复中列出执行计划
**，不依赖 `/plan` 命令触发。

**判断条件：**

- 涉及 3 个以上文件的修改
- 可能需要多次编译/测试验证
- 用户描述中包含"重构""迁移""全部""整个项目""所有""统一"等大范围关键词
- 用户要求同时做多件事

**行为：** 满足任一条件时，LLM 先在回复开头以结构化列表输出计划概览（目标、步骤、涉及文件），再逐步执行。计划仅作为回复文本展示，不创建
PlanCard、不强制暂停——用户仍可看到 LLM 的完整思路，但不打断 Agent Loop 节奏。

**预期效果：** LLM 有清晰的内部规划后更不容易迷路，用户也能在回复中看到任务全貌。不需要改任何代码，只改
System Prompt。

#### 方案二：轮次预警（Agent Loop 层，成本极低）

在 Agent Loop 的 while 循环中加入一个简单判断：当 turn 数达到 `maxTurns` 的 **60%** 时，在下一轮 API
请求的消息末尾追加一条系统提示，提醒 LLM 评估剩余工作量。

**行为：**

- 默认 `maxTurns=15`，第 9 轮触发预警
- 提示内容大致为"已执行 N 轮，请评估任务是否能在剩余轮次内完成。如果任务范围较大，建议在回复中列出剩余步骤并建议用户使用
  /plan 拆分。"
- 用户可以据此决定是否停止当前执行、重新用 /plan 启动
- 预警不打断 Agent Loop，仅作为上下文提示

#### 方案三：`createPlan` 工具（LLM 主动创建计划，最彻底）

新增一个 `createPlan` 工具，让 LLM 可以在执行过程中**随时主动**创建正式执行计划，而非必须等用户输入
`/plan`。

**工具定义：**

| 字段   | 说明                                            |
|------|-----------------------------------------------|
| 工具名  | `createPlan`                                  |
| 参数   | `task`（任务描述）+ `steps[]`（步骤列表，每步含描述、预期工具、涉及文件） |
| 触发时机 | LLM 在执行任务过程中发现自己读了 5 个文件后还有 20 个要改            |
| 效果   | 创建 PlanCard 并持久化，后续执行按 Plan Mode 暂停/继续逻辑走     |

**与 `/plan` 的区别：**

|       | `/plan` 命令  | `createPlan` 工具  |
|-------|-------------|------------------|
| 触发方   | 用户手动输入      | LLM 根据实际情况主动创建   |
| 创建时机  | 任务开始前       | 执行中途，LLM 已充分了解项目 |
| 计划准确性 | LLM 凭用户描述猜测 | LLM 已读代码，步骤更精准   |
| 用户体验  | 需要用户知道何时用   | 全自动，用户无感         |

**工具描述提示词（供 LLM 理解何时使用）：**
> 当任务涉及 3 个以上文件或预计需要 5 轮以上完成时，在执行关键修改前先调用 createPlan
> 创建执行计划。创建后按计划步骤逐步执行，每步完成后标记状态。

**实现要点：**

- `createPlan` 被调用时 → 自动注入 PlanCard 到 Chat UI
- 创建后的计划行为与 `/plan` 完全一致（每步暂停、可恢复）
- LLM 可以在计划执行中调用 `createPlan` **更新**剩余步骤（当发现新情况时）

#### 推荐路径

| 优先级 | 方案                 | 改动量           | 效果                                 |
|-----|--------------------|---------------|------------------------------------|
| 1   | System Prompt 自判指令 | 只改 prompt     | LLM 先思考再动手，用户可见计划思路                |
| 2   | 轮次预警               | AgentLoop 加几行 | 防止不知不觉跑飞                           |
| 3   | `createPlan` 工具    | 新增 Tool + 注册  | LLM 主动创建正式 Plan，与现有 Plan Mode 无缝对接 |

方案一和方案二可并行实施，互不冲突。方案三是正确的大方向——对齐 Claude Code 中 TodoWrite 工具的设计理念：
**给 LLM 工具能力而非模式切换，让它自己判断什么时候需要结构化**。

## 六、多 Agent（spawnAgent + MultiAgentManager）

父 Agent 可启动子代理处理子任务，由 `MultiAgentManager` 统一调度。

### 关键约束

- **并发控制**：`Semaphore(3)` 限制全局最大并发（父 + 子总计）
- **文件写锁**：`ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享）
- **递归限制**：最多 1 层嵌套（子不可再 spawn 孙）
- **结果摘要**：≤ 2000 tokens 写入父的 toolResult
- **子 Session**：独立持久化（`session.parentId` 关联）

### 文件写入并发控制

写文件前校验 `modificationStamp` 与 Agent 上次读取时的 stamp 一致——不一致则拒绝写入，Agent 自动重读后重试。

## 七、会话持久化 & Token 统计

### 存储

- 路径：`<project>/.code-assistant/sessions/<uuid>.json`
- 写入流程：Jackson → `.tmp` → `Files.move(ATOMIC_MOVE)` → `FileChannel.tryLock()` OS 级排他锁
- 读取容错：`JsonParseException` → 跳过；`FileNotFoundException` → 从 index 移除

### Session Index

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
    "hasActivePlan": true
  }
]
```

### 会话标题生成

首条用户消息后异步调用 LLM（不带 tools，`max_tokens=64`）生标题（≤ 20 字）。不阻塞主流程。

### Token Usage 页面

从所有 session 文件的 `totalTokens` 聚合 → 按会话/日/月三种视图 → sparkline 趋势图（Custom JComponent
手绘，30 天按日折线）。

## 八、上下文自动压缩（Auto-Compact）

当消息历史 token 数超过模型上下文窗口的 **80%** 时（1M × 0.8 = 800K tokens），自动压缩旧消息。

### 超长任务的三层防线

| 层级    | 机制                            | 触发时机                   | 作用                       |
|-------|-------------------------------|------------------------|--------------------------|
| 1. 预防 | [LLM 自动规划](#llm-自动判断并创建计划规划中) | 任务开始前 / 执行中途           | 让 LLM 主动拆解任务，从源头避免失控     |
| 2. 预警 | 轮次预警                          | turn 达到 maxTurns 的 60% | 提醒 LLM 评估剩余工作量，建议拆分      |
| 3. 兜底 | Auto-Compact                  | 上下文超过 800K tokens      | 压缩旧消息为摘要，确保 LLM 不丢失关键上下文 |

三层环环相扣：**预防**最优（不产生问题），**预警**次之（问题刚出现就提醒），**兜底**保底（问题已发生但自动修复）。

### 压缩策略

1. 保留最近 N 条消息原文（`N = max(4, messages.size / 3)`，至少保留最后 2 轮）
2. 早期消息 → 独立 API 调用生成摘要（不带 tools，`max_tokens=1024`）
3. 摘要插入消息列表头部，替换被压缩消息
4. 多次压缩时，之前摘要参与新一轮压缩（幂等）
5. Plan 摘要注入压缩 prompt，确保计划上下文不丢失

### Token 估算（统一策略）

```kotlin
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val bytes = text.encodeToByteArray().size
    val asciiOnly = text.count { it.code <= 127 }
    val nonAscii = text.length - asciiOnly
    // 英文/代码 ~4 字节/token，中文 ~0.67 token/字符
    return max(bytes / 4, asciiOnly / 4 + (nonAscii * 3) / 2)
}
```

误差 ±20%。API 返回的 `usage` 优先使用，估算仅用于 compact 阈值判定、输入框预览。

## 九、Skill 系统

兼容 Claude Code / Codex SKILL.md 格式。

### 扫描目录

- `.code-assistant/skills/`（主目录）
- `.claude/skills/`（兼容 Claude Code）

### SKILL.md 格式

```yaml
---
name: code-review
description: 审查代码质量
command: review
tools:
  - readFile
  - runShell
---
# 正文（Markdown）
```

### 注册 & 验证

- `SkillManager` 启动时扫描目录，解析注册
- 工具声明与 `ToolRegistry` 交叉验证——不存在则 ⚠️ 标记
- 用户通过 `/command` 手动调用（如 `/review`），不做自动关键词匹配
- 已启用 Skill 的正文按需注入 system prompt

### 调用方式

用户输入 `/command`（如 `/review`）→ `AgentLoop` 解析 `/` 指令 → 匹配
`SkillManager.getByCommand(command)` → 注入 SKILL.md 正文到 system prompt → 后续消息不再自动注入（避免
context 膨胀）。

## 十、MCP 支持

```
┌──────────────┐     MCP (JSON-RPC via stdio)
│ Code         │ ←── stdio / HTTP+SSE ──→  MCP Servers
│ Assistant    │    JSON-RPC (手写)         ├─ mysql (stdio)
│ Plugin       │                           ├─ filesystem (stdio)
└──────────────┘                           └─ remote-api (HTTP+SSE)
```

### 配置文件

同时读取以下配置（后加载覆盖先加载）：

- `<project>/.code-assistant/mcp-config.json`（主配置）
- `<project>/.mcp.json`（兼容 Claude Code）
- `~/.claude/.mcp.json`（兼容 Claude Code 全局）

### Server 生命周期

```
CONFIGURED → INITIALIZING (握手 5s 超时) → RUNNING
                │                    │
                ├─ 超时 → ERROR      ├─ 进程退出 → CRASHED（自动重启 1 次）
                └─ 握手失败 → ERROR  ├─ 手动停止 → STOPPED
                                     └─ JSON-RPC 断开 → DISCONNECTED
```

### 关键行为

- CRASHED → 等 2s 自动重启 1 次。再次崩溃 → CRASHED（需手动 [重连]）
- DISCONNECTED → 每 5s 自动重连（最多 10 次）
- INITIALIZING → 退避重试：2s → 5s → 10s → 15s...，最多 3 分钟
- Server 工具通过 `tools/list` 获取，注册到 `ToolRegistry`
- 同名工具加前缀 `serverName/toolName`，内置工具优先级高于 MCP 工具
- v1 不支持 `resources/list` 和 `prompts/list`

## 十一、UI/UX 设计

### 聊天面板整体布局

```
┌──────────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌] [🎯] [⚙] │ ← TabBar
├──────────────────────────────────────────────────────┤
│  🤖 会话标题                                [🗑] [✕]│ ← 标题行
├──────────────────────────────────────────────────────┤
│  消息列表（JScrollPane）                              │
│  ├ 用户气泡 (右对齐, 蓝底 #E8F0FE)                    │
│  ├ Agent 文本 (左对齐, Markdown 渲染)                │
│  ├ 工具调用卡片 (折叠, 8 状态)                       │
│  ├ 错误气泡 (红底 #FEE2E2 + 重试)                   │
│  └ 系统消息 (居中, 灰色)                            │
├──────────────────────────────────────────────────────┤
│ 📄 UserService.kt:40-60 [✕]                          │ ← Tags 行
│ 输入你的问题...                                        │ ← 文本区域
│ [+]  @ 选择文件                              [→]      │ ← 底部栏
└──────────────────────────────────────────────────────┘
```

### 气泡渲染

- **用户气泡**：右对齐，蓝色背景 `#E8F0FE`，圆角 12px
- **Agent 文本**：左对齐，Markdown 流式渲染（30ms batch flush）
- **代码块**：`EditorTextField`（只读，语法高亮），背景 `#F6F8FA`，字体 `JetBrains Mono 13`
- **错误气泡**：红色背景 `#FEE2E2` + [重试] 按钮
- **系统消息**：居中，灰色 `#9CA3AF`

### 思考过程

DeepSeek V4 `reasoning_content` 以折叠块展示（默认收起），浅橙背景 `#FFF8F0`，不持久化。

### 工具调用卡片（8 状态）

状态：⏳ PENDING → 🔒 AWAITING_APPROVAL → 🔄 EXECUTING → ✅ DONE / ❌ ERROR / ⏰ TIMEOUT / 🚫 REJECTED / ⛔
CANCELLED。

`editFile` 执行成功后，ToolCallCard 内联展示可视化 Diff（`SimpleDiff` 生成，ADD 绿色/DEL 红色/CTX
灰色），替换纯文本的前后对比。详见 [方案正确性验证 > 方案三](#方案三同类代码自动参考agent-loop-层)。

### @file 引用

- `@file` 注入格式：`[File: UserService.kt (156 lines)]\n<content>\n[/File]`
- 选中代码注入：`[Selection from UserService.kt:40-60]\n<code>\n[/Selection]`
- 同时存在时去重：@file 完整文件保留，选中内容不重复注入但保留行号标记
- 手动 @file 可多个，选中引用仅一个（新选中替换旧）

### `/` 指令 & `@` 文件引用 Popup

输入 `/` 或 `@` 或点击 [+] 时从输入区域底部弹出选择列表，支持 ↑↓ 键盘导航和实时过滤。

### 剪贴板图片粘贴

支持格式：PNG/JPEG/GIF/WebP/BMP。单张 ≤ 5MB，单次 ≤ 5 张。缩放（长边 max 2048px）→ PNG 编码 → Base64 →
`data:image/png;base64,...` → image content block。

### 响应式

| 宽度        | 用户气泡 | Agent 气泡 |
|-----------|------|----------|
| >500px    | 60%  | 75%      |
| 350-500px | 70%  | 85%      |
| 250-350px | 90%  | 95%      |
| <250px    | 95%  | 95%      |

### 字体 & 颜色

| 属性        | 值                                            |
|-----------|----------------------------------------------|
| 正文字体      | `SansSerif, PLAIN, 14`                       |
| 代码字体      | `JetBrains Mono, PLAIN, 13`（降级 `Monospaced`） |
| TabBar 按钮 | 44×32 px，高亮 `Color(100, 140, 220)` 底边框 2px   |

## 十二、messageBus 事件总线

| Topic                   | 消息类型                                 | 发布者            | 订阅者                    |
|-------------------------|--------------------------------------|----------------|------------------------|
| `SessionChanged`        | sessionId + CREATED/UPDATED/DELETED  | SessionManager | SessionsPage, ChatPage |
| `AgentStateChanged`     | newState (IDLE/PROCESSING/...)       | AgentSession   | ChatPage, TabBar       |
| `TokenUsageUpdated`     | sessionId + delta                    | AgentSession   | TokenUsagePage         |
| `McpServerStateChanged` | serverId + newState                  | McpManager     | McpPage                |
| `ApiKeyValidated`       | VALID/INVALID/UNKNOWN                | WelcomePage    | ChatPage, SettingsPage |
| `PlanStateChanged`      | sessionId + planStatus + currentStep | PlanExecutor   | ChatPage, SessionsPage |
| `PageSwitched`          | from + to (PageId)                   | ChatToolWindow | 所有 Page                |

## 十三、项目关闭清理

`ChatToolWindow.dispose()` 中的清理顺序（必须按序）：

```
1. 标记所有 AgentSession 为 cancelled（含子 Agent）
2. 等待子 Agent 完成（最多 3s）→ 等待 WriteCommandAction 完成（最多 5s）
3. AnthropicOkHttpClient.close() 取消所有 HTTP 请求
4. 遍历所有 AgentSession 的 runningProcesses → destroyForcibly()
5. 持久化所有未保存 Session（含暂停的 Plan）
6. McpManager.dispose()（遍历 server → shutdown 2s → destroyForcibly）
7. 释放所有 FileLock
```

## 十四、配置项汇总

| 配置项           | 默认值               | 说明                |
|---------------|-------------------|-------------------|
| API Key       | —                 | PasswordSafe 安全存储 |
| Model         | `deepseek-v4-pro` | V4 Flash / V4 Pro |
| Agent 最大轮次    | 15（0=不限）          | 达到上限自动终止          |
| 多 Agent 并发上限  | 3                 | 父 + 子总计           |
| 代码补全          | 启用                | 独立开关              |
| Commit Prompt | 默认模板              | `{diff}` 占位符      |

## 十五、防止 LLM 幻觉

> **关联技术契约：** System Prompt 反幻觉规则见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)，工具前置校验（VFS 校验/readFile
> 前置/stamp 校验）见 [`tech-spec.md` 2.3](../docs/tech-spec.md#23-toolregistry)，工具结果自检反馈环见 [
`tech-spec.md` 2.1](../docs/tech-spec.md#21-agentloop)，Shell 输出强化标注见 [
`tech-spec.md` 九 > runShell](../docs/tech-spec.md#runshell)。

LLM 会产生几种典型幻觉：**凭空编造文件路径和 API**、**基于过时信息做决策**、**忽略工具返回的错误信息**、
**假设搜索结果完整**。以下按防御层次说明已有措施和加强方案。

### 15.1 已有防御措施

| 机制                      | 防御的幻觉类型   | 原理                                                           |
|-------------------------|-----------|--------------------------------------------------------------|
| `editFile` 精确匹配         | 瞎编代码内容    | `oldString` 必须在文件中**精确且唯一**匹配，否则拒绝写入并返回错误（含附近行内容引导 LLM 重试）   |
| `modificationStamp` 校验  | 基于过时信息修改  | 文件被外部修改后 stamp 不匹配 → 拒绝写入 → 强制 LLM 重新 `readFile`             |
| 工具返回截断 + 明确标记           | 遗漏关键信息    | 截断时尾部标注 `...(共 N 行/条)`，LLM 知道自己看到的不是全部                       |
| Shell 危险命令二次确认          | 幻觉出危险命令   | `rm -rf /`、`git push --force`、`sudo`、`chmod 777` 弹出二次确认，不可跳过 |
| System Prompt"先读后写"原则   | 凭空猜测文件内容  | 强制 LLM 先用 `readFile` 获取真实内容再 `editFile`/`writeFile`          |
| `searchContent` 无结果明确返回 | 搜不到假装搜到了  | 返回 `"未找到匹配"` + 建议（检查拼写、使用更简短搜索词）                             |
| 工具结果自检反馈环               | 忽略工具返回的异常 | 每次 tool result 回传前，根据结果类型附加检查提示（详见 System Prompt）            |

### 15.2 加强方案

#### 方案一：System Prompt 反幻觉指令（零成本，立刻生效）

在 System Prompt 中显式加入：

> ```
> ## 防止幻觉
>
> 1. 绝不编造不存在的 API、类名、方法名。引用任何 API 前必须通过 readFile 或 searchContent 确认其真实存在。
> 2. 不确定文件是否存在时，先用 listFiles 或 readFile 确认，不要假设路径。
> 3. 修改代码前必须用 readFile 读取目标区域的真实内容，不要凭记忆或猜测。
> 4. Shell 命令执行后，先检查退出码和 stderr，再根据实际结果（而非预期结果）决定下一步。
> 5. 如果信息不足，主动说明"我需要先读取 X 文件来确认"，而不是猜测。
> ```

#### 方案二：文件路径 VFS 校验

在 `ToolExecutor` 中对文件操作工具做前置校验：

| 工具          | 校验规则                                                        |
|-------------|-------------------------------------------------------------|
| `readFile`  | VFS 中文件不存在 → 返回错误，不执行                                       |
| `editFile`  | 非新建场景（`oldString` 非空）且文件不存在 → 返回错误；新建场景（`oldString` 为空）→ 放行 |
| `writeFile` | 始终放行（覆盖写入/新建均可）                                             |

#### 方案三：`readFile` 前置强制执行

在 `ToolExecutor` 中，`editFile` 和 `writeFile` **覆盖模式**执行前检查：

> 该文件在当前 turn 中是否被 `readFile` 读取过？
> → 否：拒绝执行，返回 `"请先用 readFile 读取 {filePath} 后再修改，确保你了解文件的真实内容"`

LLM 将无法在没看过文件的情况下瞎编。这是防御幻觉**性价比最高**的措施。

#### 方案四：Shell 输出强化标注

将 `runShell` 的返回值格式从平铺改为错误优先：

```
// 强化后格式：退出码和 stderr 前置，用显眼标记
⚠️ 命令执行失败 (退出码: 1)
STDERR:
  error: cannot find symbol UserService
STDOUT:
  ...（前 20 行）...
$ ./gradlew build
```

退出码非零或 stderr 非空时，用 `⚠️` 标记前置，LLM 更难忽略。

#### 方案五：工具结果自检反馈环

在 Agent Loop 中，每次 tool result 回传 LLM 前，根据结果类型附加检查提示：

| 工具              | 结果特征         | 自动附加提示                                     |
|-----------------|--------------|--------------------------------------------|
| `readFile`      | 文件不存在        | `提示：文件不存在，请确认路径是否正确。不要假设文件内容。`             |
| `searchContent` | 0 条匹配        | `提示：未找到匹配。不要假设代码存在，考虑用 listFiles 确认文件位置。`  |
| `searchContent` | 结果被截断        | `提示：搜索结果已截断，可能有遗漏。如需修改代码，建议先用更精确的关键词确认范围。` |
| `runShell`      | 退出码非 0       | `⚠️ 命令执行失败。请分析错误原因后决定下一步，不要忽略此错误继续执行。`     |
| `runShell`      | stderr 非空    | `⚠️ 命令有错误输出，请检查 stderr 内容。`                |
| `readLints`     | 有 ERROR 级别诊断 | `⚠️ 文件存在编译错误。请先解决这些错误再继续修改代码。`             |
| `listFiles`     | 结果被截断        | `提示：目录列表已截断。如果你在找特定文件，建议用更精确的路径缩小范围。`      |

### 15.3 防御层次总结

```
第 1 层：事前预防（阻止幻觉产生）
  ├─ System Prompt 反幻觉指令        ← 只改 prompt
  └─ System Prompt 复杂度判断规则     ← 同上

第 2 层：事前硬约束（阻止基于幻觉的操作）
  ├─ readFile 前置强制执行           ← 改 ToolExecutor
  ├─ 文件路径 VFS 校验              ← 改 ToolExecutor
  └─ editFile 精确匹配 + stamp 校验  ← 已有

第 3 层：事后纠偏（让 LLM 意识到自己错了）
  ├─ Shell 输出强化标注              ← 改 runShell 返回值格式
  ├─ 工具结果自检反馈环              ← 改 AgentLoop
  └─ 工具返回截断 + 明确标记         ← 已有
```

### 15.4 推荐实施路径

| 优先级 | 方案                  | 改动量              | 效果            |
|-----|---------------------|------------------|---------------|
| 1   | System Prompt 反幻觉指令 | 只改 prompt        | LLM 行为基线提升    |
| 2   | `readFile` 前置强制执行   | ToolExecutor 加检查 | 杜绝凭空编造代码      |
| 3   | 文件路径 VFS 校验         | ToolExecutor 加检查 | 杜绝幻觉文件路径      |
| 4   | Shell 输出强化标注        | 改返回值格式           | LLM 更准确理解执行结果 |
| 5   | 工具结果自检反馈环           | AgentLoop 加后处理   | 系统性减少所有工具误读   |

> 方案 1 零成本立即可做，方案 2+3 改动小防御面广，方案 4+5 是锦上添花的系统性优化。

## 十六、Agent 代码改动正确性验证

> **关联技术契约：** System Prompt 验证流程见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)，修改后自动 readLints +
> 回归测试提示 + 影响范围分析见 [`tech-spec.md` 2.3](../docs/tech-spec.md#23-toolregistry)，Diff
> 可视化见 [`tech-spec.md` 九 > editFile](../docs/tech-spec.md#editfile) 和 [
`tech-spec.md` 2.6](../docs/tech-spec.md#26-toolcallcard--plancard)。

Agent 改完代码后，核心问题是：**改对了吗？** 这个问题分两层——Agent 自己如何验证（自检），用户如何验证（审查）。

### 16.1 已有验证机制

| 机制                     | 验证层面     | 说明                                            |
|------------------------|----------|-----------------------------------------------|
| `editFile` 精确匹配        | 改对位置     | `oldString` 精确唯一匹配保证改到了目标代码                   |
| `modificationStamp` 校验 | 基于最新版本   | 确保 Agent 看到的是修改时的真实文件状态                       |
| `readLints` 工具         | 语法/类型正确性 | Agent 可以主动读取 IDE 诊断，发现编译错误                    |
| `runShell` 工具          | 编译/测试正确性 | Agent 可以运行 `./gradlew build`、`./gradlew test` |
| Plan Mode 逐步审查         | 用户审查     | 每步执行完暂停，用户确认 diff 后再继续                        |
| `editFile` 返回前后对比      | 改动可见性    | 返回替换前后的 3 行代码，Agent 和用户可判断合理性                 |

### 16.2 增强方案

#### 方案一：System Prompt 自检指令（零成本）

在 System Prompt 中加入代码修改后的自检流程：

> ```
> ## 代码修改后的验证流程
>
> 每次修改代码后，按以下顺序验证：
>
> 1. 如果修改的是 Kotlin/Java 等编译型语言文件，用 readLints 检查是否有新引入的错误。
> 2. 如果 lints 无错误，考虑运行编译或相关测试（如 ./gradlew build 或对应模块的 test）。
> 3. 如果测试失败，分析失败原因并修复，不要跳过失败的测试。
> 4. 如果项目没有现成测试或编译耗时过长，至少用 readFile 重新读取修改区域确认改动符合预期。
> ```

#### 方案二：`editFile`/`writeFile` 后自动 `readLints`（Agent Loop 层）

在 Agent Loop 中，每次 `editFile` 或 `writeFile` 成功执行后，自动对被修改文件运行一次 `readLints`
，将诊断结果追加到 tool result 中：

```
editFile 执行成功
  → 自动静默运行 readLints(filePath)
  → 如果有新 ERROR：toolResult 尾部追加 "⚠️ 该文件修改后存在 N 个编译错误，请检查并修复"
  → 如果有新 WARNING：追加 "该文件修改后有 N 个警告"
  → 无新问题：不追加额外内容
```

**关键设计决策：**

- 静默执行：不显示额外的 ToolCallCard，避免 UI 噪音
- 只读诊断：不阻塞 Agent Loop，结果作为 tool result 的附加信息
- 关注**新增**问题：对比修改前后的 lint 数量，只报告增量

#### 方案三：修改文件后自动展示 Diff（Chat UI 层）

当 `editFile` 成功后，在老字符串和新字符串之间生成**可视化 diff**，在 ToolCallCard 中以内联方式展示：

```
🔧 editFile: UserService.kt                    ✅ 完成
┌─────────────────────────────────────────────┐
│ --- a/UserService.kt                        │
│ +++ b/UserService.kt                        │
│ @@ -40,6 +40,6 @@                            │
│  -fun findById(id: Long): User? {           │ ← 红色背景
│  +suspend fun findById(id: Long): User? {   │ ← 绿色背景
│                                              │
│ 耗时: 0.3s                                   │
└─────────────────────────────────────────────┘
```

这个 diff 用户一眼就能判断改动是否符合预期，不需要在头脑中对比前后文本。用已有的 `SimpleDiff`（Myers
LCS 算法）生成，不引入新依赖。

#### 方案四：回归测试智能提示

在 `ToolExecutor` 中，根据被修改文件的位置，智能提示 Agent 需要运行哪些测试：

| 修改文件路径             | 自动提示                                                                                   |
|--------------------|----------------------------------------------------------------------------------------|
| `src/main/**/*.kt` | `提示：修改了 {fileName}，建议运行相关测试验证。如果项目用 Gradle，可运行 ./gradlew test --tests "*{ClassName}*"` |
| `src/test/**/*.kt` | `提示：修改了测试文件 {fileName}，建议运行该测试确认通过：./gradlew test --tests "{TestClassName}"`           |
| `build.gradle.kts` | `⚠️ 修改了构建配置，建议运行 ./gradlew build 验证构建未破坏`                                              |

#### 方案五：改动影响范围分析

用 `searchContent` 反向查找被修改符号的引用处，帮助 Agent 判断是否需要联动修改：

```
editFile 成功后
  → 提取修改涉及的方法名/类名（简单正则：fun/class/val/var 后的标识符）
  → 自动 searchContent 搜索该标识符在项目中的引用
  → 将搜索结果追加提示："{symbolName} 在 N 个文件中被引用：file1, file2... 请确认这些引用是否需要联动修改"
```

### 16.3 验证层次总结

```
第 1 层：Agent 自检（每次修改后自动触发）
  ├─ 修改后自动 readLints           ← 方案二
  ├─ System Prompt 自检指令         ← 方案一
  └─ editFile 精确匹配（已有）       ← 确保改对地方

第 2 层：Agent 主动验证（需要 Agent 判断触发）
  ├─ runShell 编译/测试             ← 已有
  ├─ 回归测试智能提示               ← 方案四
  └─ 改动影响范围分析               ← 方案五

第 3 层：用户审查（人眼确认）
  ├─ Plan Mode 逐步暂停（已有）      ← 最可靠的验证
  ├─ 修改后 Diff 可视化展示          ← 方案三
  └─ ToolCallCard 中的前后对比（已有）
```

### 16.4 推荐实施路径

| 优先级 | 方案                 | 改动量                          | 效果               |
|-----|--------------------|------------------------------|------------------|
| 1   | System Prompt 自检指令 | 只改 prompt                    | Agent 养成改完就验证的习惯 |
| 2   | 修改后自动 readLints    | ToolExecutor 加静默调用           | 编译错误第一时间发现       |
| 3   | 修改后 Diff 可视化       | Chat UI 层                    | 用户审查效率大幅提升       |
| 4   | 回归测试智能提示           | ToolExecutor 加提示             | 减少"忘记跑测试"导致的回归   |
| 5   | 改动影响范围分析           | ToolExecutor + searchContent | 减少联动修改遗漏         |

> 方案 1+2 保证 Agent 自己能发现并修复错误（**自愈**），方案 3 让用户能快速判断改动是否正确（**审查**
> ），方案 4+5 防止遗漏（**全面性**）。

## 十七、Agent 方案正确性验证

> **关联技术契约：** System Prompt 方案设计原则 + 自检清单见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)，关键操作确认 +
> 同类代码自动参考见 [`tech-spec.md` 2.3](../docs/tech-spec.md#23-toolregistry)，PlanCard `createPlan`
> 工具入口见 [`tech-spec.md` 2.6](../docs/tech-spec.md#26-toolcallcard--plancard) 和 [
`tech-spec.md` 2.7](../docs/tech-spec.md#27-planexecutor)。

上一节解决的是"代码改对了吗"（语法/编译/测试通过），本节解决更高层的问题：**"方案本身对吗"**
——架构决策是否合理、设计模式是否恰当、是否对齐项目约定。代码编译通过 ≠ 方案正确。

### 17.1 方案错误的典型表现

| 类型    | 表现                           | 根因             |
|-------|------------------------------|----------------|
| 过度工程  | 为简单需求引入抽象层、工厂模式、接口           | LLM 偏好"教科书式"设计 |
| 风格不一致 | 用 Java 风格写 Kotlin、忽略项目已有的工具类 | 不了解项目约定        |
| 破坏性修改 | 改了公共 API 但不知道有外部调用者          | 对影响范围认知不足      |
| 重复造轮子 | 自己实现了一个项目已有工具类已经提供的功能        | 不了解项目基础设施      |
| 选错模式  | 用继承代替组合、用可变状态代替不可变           | 缺乏上下文判断        |
| 过度修改  | 为了改一个方法签名把 20 个无关文件也重构了      | 没有控制变更范围       |

### 17.2 已有防御机制

| 机制                           | 防御层面   | 说明                                    |
|------------------------------|--------|---------------------------------------|
| Plan Mode                    | 方案审查   | 执行前用户看到完整计划，可在源头否决错误方案                |
| System Prompt 项目上下文          | 风格对齐   | 告知项目名、技术栈、当前文件，帮助 LLM 定位              |
| Skill 系统                     | 领域知识注入 | `SKILL.md` 可携带项目特定规范（如"本项目禁止使用 X 模式"） |
| `readFile` + `searchContent` | 了解现状   | Agent 可以在动手前搜索类似实现作为参考                |

但这些机制都**依赖 Agent 主动使用**或**依赖用户判断**，没有系统化的自动校验。

### 17.3 增强方案

#### 方案一：System Prompt"先理解再动手"（零成本）

在 System Prompt 中加入方案层面的行为准则：

> ```
> ## 方案设计原则
>
> 在动手修改代码前，先理解项目现状：
>
> 1. 如果任务是修改/扩展已有功能，先用 searchContent 搜索项目中类似实现（如"项目中其他 Service 类长什么样"），以现有模式为模板。
> 2. 如果任务是新增功能，先在项目中找一个最相似的文件通读，保持风格一致（命名、结构、错误处理方式）。
> 3. 优先复用项目已有的工具类、基类、扩展函数，不要自己从头写。
> 4. 选择方案时遵循项目已有的复杂度水平——如果项目里其他 Service 都是单文件 200 行，你就不该引入多层抽象。
> 5. 只改和任务直接相关的代码，不要顺便重构不相关的文件。
> ```

#### 方案二：方案自检清单（System Prompt 追加）

每次提出修改方案前（在回复中或 Plan 创建前），Agent 自问以下问题并在回复中简要说明：

> 1. **模式对齐**：项目里有没有类似实现可以参考？我是否遵循了？
> 2. **最简单方案**：有没有更简单的写法？我是否过度设计了？
> 3. **影响范围**：这个修改会影响多少调用者？有没有遗漏的联动修改？
> 4. **破坏性**：是不是 Breaking Change？如果是，用户知道吗？

自检结果直接写在回复中——用户可以一眼判断 Agent 有没有经过思考，还是拍脑袋出方案。

#### 方案三：同类代码自动参考（Agent Loop 层）

在 Agent Loop 中，当 `readFile` 打开一个新文件时，自动附加一段"风格参考"到 tool result：

```
readFile 返回 UserService.kt
  → 自动分析文件特征（缩进风格、命名模式、使用的框架/工具类）
  → tool result 底部追加:
     "📋 风格参考: 该文件使用 4 空格缩进，方法名驼峰式，依赖通过构造器注入。
      项目中类似的 Service 类有: AuthService.kt, OrderService.kt（可用 readFile 查看）"
```

这让 Agent 在修改前就有明确的风格参照，减少风格不一致。

#### 方案四：用户确认关口（关键操作需审批）

对以下**高影响操作**弹出确认对话框，Agent 必须等用户同意后才执行：

| 操作类型      | 判定条件                                      | 确认内容                        |
|-----------|-------------------------------------------|-----------------------------|
| 公共 API 变更 | 修改了 `public`/`open` 方法签名                  | "将修改公共方法 X，可能影响 N 个调用者。确认？" |
| 新增依赖      | `build.gradle.kts` 中新增 `implementation` 行 | "将新增依赖 Y。确认？"               |
| 删除文件      | `writeFile` 空内容（等价删除）或直接 `runShell rm`    | "将删除文件 Z。确认？"               |
| 大范围修改     | 一次改了 5 个以上文件                              | "将修改 M 个文件。建议用 /plan 拆分？"   |

这些确认和现有的 Shell 危险命令确认共用同一套审批 UI。

#### 方案五：AI 代码审查（改完后自动 Review）

每次任务完成（Agent `end_turn`）后，自动触发一次轻量代码审查：

> 用独立的 LLM 调用（不带 tools，`max_tokens=512`），带着当前 diff 问：
> "审查以下改动。找出：1) 风格不一致的地方 2) 过度设计 3) 可能破坏的调用者 4)
> 可以复用的已有工具。只报告有问题的地方，改动OK则回复'无问题'。"

结果以系统消息形式追加到对话中，Agent 下一轮可以修正。审查不阻塞流程，作为参考信息呈现。

这类似 Claude Code 中 `/review` 的思路——用一个独立视角检查另一个视角的工作。

### 17.4 正确性验证层次总结

```
第 1 层：方案合理性（执行前，防患于未然）
  ├─ Plan Mode 用户审查              ← 已有
  ├─ System Prompt 方案设计原则       ← 方案一
  ├─ 方案自检清单（回复中可见）        ← 方案二
  ├─ 同类代码自动参考                 ← 方案三
  └─ 关键操作用户确认                 ← 方案四

第 2 层：代码正确性（执行后，验证改对没）
  ├─ 修改后自动 readLints            ← 第十六节方案二
  ├─ 编译/测试验证                   ← 已有
  └─ 改动影响范围分析                 ← 第十六节方案五

第 3 层：审查兜底（任务完成后，独立检查）
  ├─ AI 代码审查                     ← 方案五
  └─ 用户最终审查（Diff 可视化）      ← 第十六节方案三
```

### 17.5 三个维度的正确性对比

|      | 代码正确性（第十六节）  | 方案正确性（本节）      | 防幻觉（第十五节）     |
|------|--------------|----------------|---------------|
| 问的问题 | 改对了吗？        | 方案对吗？          | 说的是真的吗？       |
| 关注点  | 编译通过、测试通过    | 架构合理、风格一致      | 不编造 API/路径    |
| 典型错误 | 类型错误、NPE     | 过度设计、破坏性修改     | 捏造不存在的类名      |
| 防御时机 | 修改后          | 修改前            | 全过程           |
| 关键手段 | 自动 readLints | Plan 审查 + 同类参考 | 精确匹配 + VFS 校验 |

### 17.6 推荐实施路径

| 优先级 | 方案                   | 改动量              | 效果                    |
|-----|----------------------|------------------|-----------------------|
| 1   | System Prompt 方案设计原则 | 只改 prompt        | Agent 基线行为提升，先看再改     |
| 2   | 方案自检清单               | 只改 prompt        | 用户可在回复中看到 Agent 的思考质量 |
| 3   | 关键操作确认               | ToolExecutor 加审批 | 防止不可逆操作               |
| 4   | 同类代码自动参考             | AgentLoop 工具结果增强 | 减少风格不一致               |
| 5   | AI 代码审查              | 独立 API 调用        | 独立视角兜底                |

> 方案 1+2 零成本立即可做，方案 3 防御面广性价比高，方案 4+5 是系统性优化。

## 十八、已知限制

- 仅支持 DeepSeek API（`deepseek-v4-pro`），不支持其他 LLM 提供商
- LLM 暂不能主动创建 Plan（需用户手动 `/plan`），[自动规划方案](#llm-自动判断并创建计划规划中)已在规划中
- 超长任务目前仅依赖 Auto-Compact 兜底，缺少预防和预警机制（[三层防线方案](#超长任务的三层防线)规划中）
- MCP `resources/list` 和 `prompts/list` v1 不支持
- 多 Agent 嵌套上限 1 层（子不可再 spawn 孙）
- Sessions 全文搜索暂未实现（v1 仅 title 过滤）
- `searchContent` 当前仅支持单词边界匹配
- `@file` glob 匹配上限 50 个文件
- `readLints` 仅支持单文件诊断
