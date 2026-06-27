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
│  Read/Write/Edit/Bash/Glob/                           │
│  Grep/ReadLints/Task/Skill                            │
│  CreatePlan/ListTasks/DeleteTask/ReorderTasks/        │
│  MarkTaskDone                                         │
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

| 配置项          | 默认值               | 说明                            |
|--------------|-------------------|-------------------------------|
| API Key      | —                 | PasswordSafe 安全存储，显示掩码        |
| Model        | `deepseek-v4-pro` | 下拉选择（V4 Flash / V4 Pro）       |
| Agent 最大轮次   | 20（0=不限）          | 达到上限后自动终止。每轮 = 一次 API 调用（含续写） |
| 多 Agent 并发上限 | 3                 | 父 + 子 Agent 总计并发数             |
| 代码补全         | 启用                | 开关                            |
| Commit 消息模板  | 默认模板              | 自定义 commit message 模板         |

### 快捷键

| 快捷键（Win/Linux） | 快捷键（macOS）    | 操作                             |
|----------------|---------------|--------------------------------|
| `Ctrl+Shift+K` | `Cmd+Shift+K` | 打开/关闭聊天面板                      |
| `Enter`        | `Enter`       | 发送消息                           |
| `Shift+Enter`  | `Shift+Enter` | 输入框换行                          |
| `Escape`       | `Escape`      | 关闭 Popup（无法中断 Agent、LLM 或流式生成） |
| `Ctrl+Shift+N` | `Cmd+Shift+N` | 新建会话                           |
| `↑`（空输入框）      | `↑`（空输入框）     | 填充上一条消息                        |

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

### API 错误处理

| 错误类型  | HTTP 状态码              | 处理策略                                                                                                          |
|-------|-----------------------|---------------------------------------------------------------------------------------------------------------|
| 认证失败  | 401                   | 不重试，状态 → INVALID，toast "API Key 无效"                                                                           |
| 速率限制  | 429                   | 读取 `Retry-After` header（秒），暂停当前请求。如 header 缺失则默认等 30s。Agent 状态 → PAUSED（可恢复），显示倒计时。连续 3 次 429 → 建议用户降低并发或切换模型 |
| 服务器错误 | 5xx                   | 退避重试：1s → 3s → 9s，最多 3 次。3 次均失败 → ERROR                                                                       |
| 网络超时  | 无响应                   | 退避重试：2s → 5s → 10s，最多 3 次。3 次均超时 → UNKNOWN，已渲染内容保留 + 标注 `[连接中断]`                                              |
| 请求体过大 | 400（context too long） | 不重试，强制触发 compact 后重发。compact 后仍过大 → 提示用户减少上下文                                                                 |

**PAUSED vs ERROR 的区别：** 可自动恢复的场景（429、网络瞬时中断）用 PAUSED——Agent
等待后自动继续，不需要用户手动 [重试]。不可自动恢复的（401、compact 后仍过大）用 ERROR——需要用户介入。

**连续错误升级规则：**

| 条件                       | 动作                                          |
|--------------------------|---------------------------------------------|
| 同一 tool call 连续 3 次失败    | 提示"该操作连续失败 3 次，建议跳过或手动处理"，LLM 应放弃该操作        |
| 同一 Session 内累计 5 个 ERROR | 弹出提示"已出现多个错误，建议使用 /plan 拆分任务"               |
| API 4xx 错误（401/403 除外）   | 不自动重试，直接 ERROR                              |
| 网络错误（超时/DNS）             | 最多自动重试 3 次（退避：2s → 5s → 10s），3 次均失败 → ERROR |
| 连续 3 次 429               | 建议用户降低并发或切换模型，Agent → PAUSED                |

## 三、Agent Loop（核心逻辑）

```
val effectiveMaxTurns = if (maxTurns == 0) Int.MAX_VALUE else maxTurns  // 0=不限，内部转为最大值
var continueStreak = 0                           // 当前轮续写链长度（每轮重置）
while (turn < effectiveMaxTurns && !cancelled):
  ├─ if (stop_reason != "max_tokens") turn++       ← 仅用户消息触发的 API 调用计数，续写不增加 turn
  ├─ compactIfNeeded(system, messages, tools, modelLimit) ← 估算实际总 token，超 modelLimit × 0.7 阈值则压缩 messages（选 0.7 而非接近 1.0：token 估算有 ±20% 误差，最坏情况下 0.7/0.8=87.5% 仍在 1M 窗口内）
  ├─ if (turn >= effectiveMaxTurns * 0.6):            ← 轮次预警（⏳ 规划中）。effectiveMaxTurns == Int.MAX_VALUE（不限轮次）时跳过预警
  │    附加系统提示"已执行 N 轮，评估剩余工作量"
  ├─ client.messages().createStreaming(params)    ← OkHttp, background
  │    .forEach { chunk → invokeLater { UI } }
  │
  ├─ for each toolUse:
  │    ├─ 审批检查（首次弹确认）
  │    │    CountDownLatch ← 等待用户操作（无超时，设计如此：Agent Loop 在后台线程池而非 EDT，阻塞不影响 UI 响应；审批弹窗是模态的，用户必然处理）
  │    │
  │    ├─ 执行（9 个工具）:
  │    │    Read      → VFS (bg)
  │    │    Write     → invokeAndWait { WriteCommandAction }
  │    │    Edit      → invokeAndWait { WriteCommandAction }
  │    │    Bash      → ProcessHandler (bg, 流式)
  │    │    Glob     → VFS + FilenameIndex (bg)
  │    │    Grep → 文件遍历 + 正则 (bg)
  │    │    readLints     → IDE Inspection (bg)
  │    │    Task    → 新 AgentLoop (bg)
  │    │
  │    └─ 结果显示在 ToolCallCard
  │
  ├─ params.add(toolUses + toolResults)           ← 追加到消息
  │
  ├─ stop_reason 分叉:
  │    "end_turn"      → continueStreak = 0, 退出 while（等待用户下一条消息）
  │    "max_tokens"    → continueStreak++, 超过 maxAutoContinue(5) 则 emit Error + 退出
  │                      未超 → 追加临时 "继续" 消息（不持久化），循环回到顶部继续（续写不增加 turn）
  │    "stop_sequence" → continueStreak = 0, 退出 while。在 assistant 消息尾部追加系统标注 "[响应被 stop_sequence 终止]"。此为 API 层面的截断信号，等效于 end_turn，Agent 进入 IDLE 等待用户下一条消息。
  │
stop: cancelled → 按[第十三节清理顺序](#十三项目关闭清理)逐步关闭（先等待子 Agent 3s → 等 WriteCommandAction 5s → client.close() → destroyForcibly()）
```

### 9 个工具

| 工具          | 参数                                              | 对应 Claude | 实现                  | 上限                         |
|-------------|-------------------------------------------------|-----------|---------------------|----------------------------|
| `Read`      | `filePath`, `startLine?`, `endLine?`, `timeout` | Read      | PSI/VFS             | 单次 ≤ 500 行，超出需分页           |
| `Write`     | `filePath`, `content`, `timeout`                | Write     | WriteCommandAction  | 内容 ≤ 3000 行                |
| `Edit`      | `filePath`, `oldString`, `newString`, `timeout` | Edit      | WriteCommandAction  | newString ≤ 3000 行         |
| `Bash`      | `command`, `workDir?`, `timeout`                | Bash      | GeneralCommandLine  | 输出 ≤ 200 行，timeout 默认 120s |
| `Glob`      | `dirPath`, `maxDepth?`, `offset?`, `timeout`    | Glob      | VFS + FilenameIndex | ≤ 50 条目，超出需翻页              |
| `Grep`      | `query`, `filePattern?`, `timeout`              | Grep      | 文件遍历 + 正则           | ≤ 50 条匹配                   |
| `readLints` | `filePath`, `timeout`                           | —（扩展工具）   | IDE Inspections     | ≤ 50 条诊断                   |
| `Task`      | `task`, `timeout`                               | Task      | AgentLoop 复用        | 结果摘要 ≤ 2000 tokens         |
| `Skill`     | `skill`, `args?`                                | Skill     | SkillManager        | LLM 自主判断触发时机               |

### 5 个计划任务管理工具

| 工具             | 参数                      | 用途                                |
|----------------|-------------------------|-----------------------------------|
| `createPlan`   | `task` + `steps[]`      | 创建/更新执行计划，自动开始执行，steps ≤ 20 步     |
| `listSteps`    | 无                       | 查看当前计划的所有步骤及状态                    |
| `deleteStep`   | `stepId: String`        | 删除指定步骤（仅 PENDING 状态可删）            |
| `reorderSteps` | `stepIds: List<String>` | 重排剩余 PENDING 步骤的执行顺序              |
| `markStepDone` | `stepId: String`        | 将 PENDING/EXECUTING 状态的步骤标记为 DONE |

> **双重告知：** 每个工具的上限同时在工具描述中声明（事前，LLM 调用前通过 JSON Schema 的 description
> 字段知晓）和返回值截断标注中体现（事后，详见下方截断策略表）。两者不可互相替代——事前防止误判，事后确保发现遗漏。工具描述中的上限声明规范见 [
`tech-spec.md` 2.3](tech-spec.md#23-toolregistry)。

**工具执行顺序：** 同一轮中 LLM 可能返回多个 `tool_use`。**按 LLM 返回的顺序串行执行**
，不并行。前一个工具的结果会立即追加到 `params.messages`，后续工具可以看到前面工具的执行结果。LLM
应通过返回顺序控制执行依赖（如先 `Read` 后 `Edit`）。

### 工具返回结果截断策略

| 工具             | 最大返回行数              | 截断后行为                                                              |
|----------------|---------------------|--------------------------------------------------------------------|
| `Read`         | 500 行               | 尾部注 `...(共 N 行，已截断到 500 行)`                                        |
| `Grep`         | 50 条匹配              | 尾部注 `...(共 N 条，已截断到 50 条)`                                         |
| `Glob`         | 50 条目               | 尾部注 `...(共 N 条目，已截断到 50。用 dirPath/maxDepth 缩小范围，或用 offset={N} 翻页)` |
| `Bash`         | 200 行               | 传给 LLM 前截断，完整输出保留在 UI 卡片                                           |
| `readLints`    | 50 条诊断              | 按 severity 排序                                                      |
| `Edit`/`Write` | 写入 ≤ 3000 行         | 超过拒绝并返回错误                                                          |
| `Task`         | 返回 ≤ 2000 tokens 摘要 | 完整结果保存到子 session                                                   |

> 事前上限声明（工具 description 中的文字）见 [`tech-spec.md` 2.3](tech-spec.md#23-toolregistry)，Token
> 估算策略见 [`tech-spec.md` 第十节](tech-spec.md#十token-估算统一策略)。

### Shell 安全

- timeout 参数必填（秒），默认值 120s。LLM 可根据命令类型调整（编译类 300s，简单命令 30s）。0=不限，System
  Prompt 要求 LLM 必须传非 0 值
- 实时流式输出 → batch 100ms → invokeLater 更新 UI（防 EDT 洪水）
- 工作目录限定项目根
- 危险模式二次确认：`rm -rf /`、`git push --force`、`sudo`、`chmod 777`（不可跳过）

### `stop_reason="max_tokens"` 自动续写

LLM 输出被 max_tokens 截断时，自动发送 "继续" 消息续写。**续写不增加 turn 计数**——turn 只统计用户消息触发的
API 调用，续写是同一轮对话的延伸，不算新轮次。

```
"max_tokens" → continueStreak++
  ├─ 超过 maxAutoContinue(5) → 终止该轮，emit Error("连续续写已达上限（5次），建议精简输入或拆分任务")
  ├─ 未超 → 追加临时 user message "继续"（不持久化）→ 循环回到顶部继续（跳过 turn++）
  └─ "end_turn" 后 continueStreak 归零

关键规则：
  - 续写不增加 turn（turn++ 在 while 顶部，但续写时跳过）
  - "继续"不持久化（仅临时在 params.messages 中）
  - 最多连续续写 5 次（防死循环），end_turn 后计数器重置
  - 仅在当前会话生命周期内有效。重启 IDE 后不自动续写，被截断消息保持原样
```

### 流式输出中断处理

**主动停止（Escape）：** 仅关闭 Popup，无法中断 Agent Loop、LLM 调用或流式生成。

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

状态说明：

| 状态                | 含义                                  |
|-------------------|-------------------------------------|
| IDLE              | 可接受输入，无进行中的 Agent 操作                |
| PROCESSING        | LLM 流式输出中，或 tool 结果已提交等待 LLM 响应     |
| AWAITING_APPROVAL | 弹出审批 dialog，等待用户操作                  |
| EXECUTING         | 工具正在执行（非阻塞，UI 显示进度）                 |
| CANCELLED         | 用户中断，清理中                            |
| ERROR             | API 调用失败或 tool 执行异常                 |
| PAUSED            | Agent 因速率限制（429）或网络瞬断等待自动恢复，计时后自动继续 |

**瞬态持久化规则：** `CANCELLED` 和 `ERROR` 不写入 Session JSON。Session 加载时若发现最后持久化的状态为
`CANCELLED`、`ERROR`、`PROCESSING`（HTTP 流已断，无法恢复），自动重置为 IDLE。
`AWAITING_APPROVAL` / `EXECUTING` 持久化，IDE 重启后可恢复。

### 对话回退

用户右键某条消息 → "从此处重试"：

- 删除该消息**之后**的所有消息（含选中的那条的回复链）
- 保留选中消息之前的历史
- 从选中消息重新发送到 LLM
- 回退后的消息不立即删除（标记 `deleted: true`），用户可通过"撤销回退"恢复

Plan Mode 中 LLM 通过 `deleteStep` + `createPlan` 可自行管理任务重试，不适用此机制。

## 五、Plan Mode（计划任务）

Plan Mode 将复杂任务拆解为有序步骤列表。默认**自动连续执行**，用户可随时暂停干预。

### 职责边界

- **PlanCard**：纯 UI 组件，展示步骤列表及进度。每个 PENDING 步骤行末有 [✕]
  单步删除按钮。不提供独立的暂停/继续/删除计划按钮——暂停跟随全局发送/停止按钮。**头部可点击折叠/展开**
  ——默认折叠（仅显示标题+摘要+进度），用户点击展开查看完整步骤列表。EXECUTING 状态下自动展开且不可折叠。全部步骤
  DONE 或被删除后 PlanCard 消失。
- **PlanExecutor**：负责自动执行。创建计划后按步骤顺序串行执行，每步完成后**自动进入下一步**
  。用户暂停后停止自动推进，等待用户手动操作。全部步骤 DONE 或被删除后 PlanCard 消失。

### 交互模式

```
默认自动连续执行:

Step 1 DONE → 自动执行 Step 2 → Step 2 DONE → 自动执行 Step 3 → ...
  用户随时可点全局停止按钮(⏹) → PlanExecutor 完成当前 Step 后停止
  停止后用户可再次发送消息恢复自动执行
  用户也可通过 LLM 的 deleteStep 工具终止计划

Step 出错了？
  → LLM 自行判断：重试 / 调用 deleteStep 删除 / 调用 reorderSteps 调整顺序
  → 继续自动执行剩余步骤
```

用户干预手段：

- 自动执行中：点全局停止按钮(⏹) 停止自动推进，可点单步 [✕] 删除 PENDING 步骤
- 计划控制跟随全局发送/停止按钮——发送变为停止，停止即暂停

### 计划任务管理工具

LLM 通过 5 个工具自主管理任务列表：

| 工具             | 参数                      | 用途                                   |
|----------------|-------------------------|--------------------------------------|
| `createPlan`   | `task` + `steps[]`      | 创建/更新执行计划，自动开始执行，steps ≤ 20 步        |
| `listSteps`    | 无                       | 查看当前计划的所有步骤及状态                       |
| `deleteStep`   | `stepId: String`        | 删除指定步骤（仅 PENDING 状态可删）               |
| `reorderSteps` | `stepIds: List<String>` | 重排剩余 PENDING 步骤的执行顺序（传入新的 stepId 序列） |
| `markStepDone` | `stepId: String`        | 将指定步骤标记为 DONE                        |

LLM 在执行过程中可随时调用这些工具调整计划：

- 发现某个步骤不再需要 → `deleteStep`
- 执行中意识到顺序不合理 → `reorderSteps`
- 步骤已通过其他方式完成 → `markStepDone`
- 任务范围扩大需要重新规划 → `createPlan`
- 不确定当前进度 → `listSteps`

### Plan 生命周期

- **创建**：用户 `/plan` 或 LLM 调用 `createPlan` 工具，状态 PENDING
- **执行**：创建后自动开始，状态变为 EXECUTING，逐步执行直到全部完成
- **暂停**：用户点全局停止按钮(⏹) → PlanExecutor 完成当前 Step 后停止，Plan 保持 EXECUTING
- **单步删除**：用户可删除任意 PENDING 步，LLM 收到通知后跳过该步
- **完成**：全部步骤 DONE 或全部被删除 → Plan 状态变为 DONE 或 DELETED → PlanCard 消失
- **持久化**：跨会话保留，关闭 IDE 再打开可恢复

### Plan 状态

| 状态          | 含义                                                    |
|-------------|-------------------------------------------------------|
| `PENDING`   | 计划已创建，尚未开始执行                                          |
| `EXECUTING` | 正在执行中（含暂停状态，暂停时 PlanExecutor 停止推进但 Plan 保持 EXECUTING） |
| `DONE`      | 全部步骤执行完成                                              |
| `DELETED`   | 用户终止计划，所有剩余步骤标记 DELETED                               |

### Step 状态

| 状态          | 含义          |
|-------------|-------------|
| `PENDING`   | 等待执行        |
| `EXECUTING` | 正在执行        |
| `DONE`      | 执行完成        |
| `DELETED`   | 被用户或 LLM 删除 |

### Plan 边界处理

| 场景             | 行为                                                |
|----------------|---------------------------------------------------|
| Step 引用文件已删除   | LLM 通过工具结果获知，自行决定调用 deleteStep 删除或重新 Read         |
| Step 引用文件被外部修改 | `modificationStamp` 校验 → 拒绝写入 → LLM 重读后重试         |
| LLM 需要调整剩余步骤   | 调用 `reorderSteps` / `deleteStep` 自主管理             |
| 用户暂停后恢复        | 用户再次发送消息 → PlanExecutor 继续执行下一个 PENDING 步骤        |
| 用户终止计划         | 用户通过 LLM 调用 deleteStep 删除所有剩余步骤 → Plan 标记 DELETED |
| 多个会话各有计划       | 允许，每个独立存储                                         |

### Plan JSON Schema（存于 Session JSON 的 `plan` 字段）

```json
{
  "plan": {
    "id": "plan-1",
    "status": "EXECUTING",
    "summary": "将 UserService.findById 改为 suspend",
    "currentStepIndex": 1,
    "steps": [
      {
        "id": "step-1",
        "description": "读取文件",
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
        "description": "修改方法签名",
        "tool": "Edit",
        "files": [
          "UserService.kt"
        ],
        "status": "PENDING",
        "retryCount": 0
      }
    ],
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:45:00Z"
  }
}
```

### `createPlan` 工具（LLM 主动创建计划）

LLM 在执行过程中**随时主动**创建正式执行计划：

| 字段   | 说明                                            |
|------|-----------------------------------------------|
| 工具名  | `createPlan`                                  |
| 参数   | `task`（任务描述）+ `steps[]`（步骤列表，每步含描述、预期工具、涉及文件） |
| 触发时机 | 任务涉及 3 个以上文件，或 LLM 读了 5 个文件后发现还有更多要改          |
| 效果   | 创建 PlanCard 并持久化，自动开始执行                       |

**与 `/plan` 命令的关系：**

|       | `/plan` 命令  | `createPlan` 工具  |
|-------|-------------|------------------|
| 触发方   | 用户手动输入      | LLM 根据实际情况主动创建   |
| 创建时机  | 任务开始前       | 执行中途，LLM 已充分了解项目 |
| 计划准确性 | LLM 凭用户描述猜测 | LLM 已读代码，步骤更精准   |

### Plan Step 工具自由度

Step 的 `tool` 字段是**建议**而非**约束**。LLM 可以调用预期之外的工具，但应在 step result 中说明原因。

**工具描述提示词（供 LLM 理解何时使用）：**
> 当任务涉及 3 个以上文件或预计需要 5 轮以上完成时，在执行关键修改前先调用 createPlan
> 创建执行计划。执行中可随时用 listSteps/deleteStep/reorderSteps 管理步骤。

## 六、多 Agent（Task + MultiAgentManager）

父 Agent 可启动子代理处理子任务，由 `MultiAgentManager` 统一调度。

### 关键约束

- **并发控制**：`Semaphore(3)` 限制全局最大并发（父 + 子总计）
- **文件写锁**：`ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享）
- **递归限制**：最多 1 层嵌套（子不可再 spawn 孙）
- **结果摘要**：≤ 2000 tokens 写入父的 toolResult。摘要 = 子 Agent 最后一轮 assistant 消息 + 所有 tool
  call 结果原文拼接，截断到 2000 tokens。不额外调用 LLM 生成摘要。
- **子 Session**：独立持久化（`session.parentId` 关联）

### 子 Agent UI 呈现

子 Agent 在父面板中以**内联折叠块**形式展示，默认折叠，点击展开查看详情：

**MultiAgentBlock（多 Agent 调度卡片）：**

- 头部显示 "🤖 多 Agent 调度中" + 并发状态（如 "2/3 运行中"）
- 子 Agent 行：图标 + 名称/任务 + 状态（✅完成 / 🔄执行中 / ⏸排队）
- 点击子 Agent 行 → 展开/折叠该子 Agent 的详细执行过程

**展开后（子 Agent 详情面板）：**

- 子 Agent 流式文本实时追加（30ms batch flush）
- 子 ToolCallCard 嵌套展示（可折叠，与父 ToolCallCard 规则一致）
- 错误红色标注
- 底部显示耗时 + "详情: sub-session #{sessionId}"（点击跳转 SessionsPage）

**实时渲染：**

- 子 Agent 流式输出追加到父面板，不新建独立面板
- 父 Agent 暂停等待子 Agent 结果（子 Agent 完成前父 Agent 不再发起新请求）

**SessionsPage 父子关联：**

- SessionIndex 添加 `parentId: String?` 字段
- 子 Session 在列表中缩进 + `└─` 前缀展示
- 点击子 Session 打开只读历史面板

### Agent 间通信规则

```
父 Agent
  ├─ Task(taskA) → 子 Agent A
  │     └─ 返回 resultA → 父 Agent
  ├─ Task(taskB) → 子 Agent B
  │     └─ 返回 resultB → 父 Agent
  └─ 父 Agent 汇总 resultA + resultB → 继续执行
```

- **v1 规则**：子 Agent 之间**禁止直接通信**。所有协调通过父 Agent——父 Agent spawn 子 Agent →
  收集结果 → 决定是否 spawn 更多子 Agent。
- **并发建议**：默认串行（一次只 spawn 一个子 Agent，等结果回来后决定下一个）。LLM 在确定任务完全独立时才并行
  spawn（最多 3 个）。
- **文件修改通知**：子 Agent 修改文件后，父 Agent 从 toolResult 中获知文件变更。父 Agent 应主动
  `Read` 获取最新状态后再传递给其他子 Agent。

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
    // totalTokens = inputTokens + outputTokens，取 API usage 返回值累加
    "toolCallCount": 5,
    "parentId": null,
    "hasActivePlan": true
  }
]
```

### 会话标题生成

首条用户消息后异步调用 LLM（不带 tools，`max_tokens=64`）生标题（≤ 20 字）。不阻塞主流程。

### Token Usage 页面

从所有 session 文件的 `totalTokens` 聚合 → 按会话/日/月三种视图 → sparkline 趋势图（Custom JComponent
手绘，30 天按日折线）。

## 八、上下文管理

### Token 预算感知（轻量）

System Prompt 中动态追加一行 Token 使用情况，让 LLM 主动控制输出长度：

```
当前上下文: 已用 ~N% (约 X / Y tokens)。如接近上限，请精简输出、减少不必要的 tool call。
```

- 显示阈值：实际 token > 模型上限的 50% 时开始显示
- 超过 70% 时措辞升级为"⚠️ 上下文即将耗尽，请立即收尾"（compact 阈值为 70%，此警告在 compact 触发边缘提醒
  LLM 主动收尾）
- 不阻断 Agent Loop，仅作为上下文提示

### Auto-Compact（上下文自动压缩）

当**实际总 token**（System Prompt + messages + Tools）超过模型上下文窗口的 **70%** 时（1M × 0.7 = 700K
tokens），自动压缩 `session.messages` 中的旧消息。

> **为什么是 70% 而不是更接近 1.0？** Token 估算有 ±20% 误差。最坏情况下（低估 20%），当估算值达到 700K
> 时实际 token 已达 700K / 0.8 = 875K，仍在 1M 窗口内留有安全余量。如果设 80%，最坏情况实际已达
> 1M，存在溢出风险。

### 超长任务的三层防线

| 层级    | 机制                                           | 触发时机                                      | 作用                       |
|-------|----------------------------------------------|-------------------------------------------|--------------------------|
| 1. 预防 | [LLM 复杂度判断 + createPlan 工具](#五plan-mode计划任务) | 任务开始前 / 执行中途（复杂度自判 ⏳ 规划中，createPlan ✅ 已有） | 让 LLM 主动拆解任务，从源头避免失控     |
| 2. 预警 | 轮次预警                                         | turn 达到 maxTurns 的 60%（⏳ 规划中）             | 提醒 LLM 评估剩余工作量，建议拆分      |
| 3. 兜底 | Auto-Compact                                 | 上下文超过 700K tokens                         | 压缩旧消息为摘要，确保 LLM 不丢失关键上下文 |

三层环环相扣：**预防**最优（不产生问题），**预警**次之（问题刚出现就提醒），**兜底**保底（问题已发生但自动修复）。

### 压缩策略

1. 保留最近 N 条消息原文。`N = max(保留最近 2 轮的消息数, ceil(messages.size / 3))`。"保留最近 2 轮"
   是硬下限——即使 `messages.size` 很大，最近 2 轮（至少 4 条：user + assistant × 2）也绝不压缩
2. 早期消息 → 独立 API 调用生成摘要（不带 tools，`max_tokens=1024`）
3. 摘要插入消息列表头部，替换被压缩消息
4. 多次压缩时，之前摘要参与新一轮压缩（幂等）
5. Plan 摘要注入压缩 prompt，确保计划上下文不丢失

**compact 后上下文重建（对齐 Claude Code）：** compact 后从头重建上下文时：

| 内容                       | 行为                                                 |
|--------------------------|----------------------------------------------------|
| System Prompt            | 从 `SystemPromptBuilder` 重新构建（不变）                   |
| Tools 定义                 | 从 `ToolRegistry` 重新生成（不变）                          |
| Skill 正文（`/command` 触发时） | **从磁盘重新注入**（确保关键约束不因摘要质量丢失）                        |
| `@file` 文件内容             | **不重新注入**（LLM 应通过 `Read` 工具重新读取目标文件，不应依赖旧消息中的过期快照） |
| `session.messages`       | 旧消息被摘要替代，近期消息保留原文                                  |

### 压缩范围

`compactIfNeeded()` 基于**实际总 token** 判断是否触发压缩：

```
实际总 token = System Prompt + session.messages + Tools 定义
触发条件: 实际总 token > modelContextLimit × 0.7 (= 700K)
```

触发后**只压缩 `session.messages`**（将旧消息替换为摘要），System Prompt 和 Tools 定义保持不变。

| 组成部分               | 是否被 compact 压缩 | 是否每次重建                                          | 说明                                        |
|--------------------|----------------|-------------------------------------------------|-------------------------------------------|
| `session.messages` | ✅ 会（超阈值时）      | 否，持久化保留                                         | 唯一被压缩的部分。其中 `@file` 注入的文件内容按普通消息压缩，不单独保留  |
| System Prompt      | ❌ 不会           | 是，每次 `SystemPromptBuilder.build()`              | 含基础角色 + 工具使用原则 + 防幻觉规则 + 复杂度判断等           |
| Tools 定义           | ❌ 不会           | 是，每次从 `ToolRegistry.generateToolDescriptions()` | 每个工具的 JSON Schema + 上限声明                  |
| Skill 注入正文         | ✅ 会参与压缩        | **compact 后从磁盘重新注入**（对齐 Claude Code）            | 确保关键约束不因摘要质量丢失。正文 ≤ 2000 tokens 以减少重新注入开销 |

**Skill 注入的特殊情况：** 用户 `/command` 触发的 SKILL.md 正文以 system prompt 附加段落形式注入。compact
时与 messages 一起压缩为摘要。compact 后**从磁盘重新读取 SKILL.md 原文并重新注入**（对齐 Claude
Code），确保即使摘要丢失了 Skill 细节，LLM 仍能看到完整的 Skill 约束。建议 Skill 正文控制在 2000 tokens
以内以减少重新注入开销。

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

兼容 Claude Code / Codex SKILL.md 格式。Skill 持久化数据位于项目根目录，IDEA 插件启动时自动加载。

### 扫描目录

按 **Code-Assistant > Claude > Codex** 优先级扫描，同名 Skill 先扫到的覆盖后扫到的。同一平台内项目级优先于用户级：

- `.code-assistant/skills/` — 主目录，优先级最高。安装 Skill 统一写入此目录
- `.claude/skills/` — 兼容 Claude Code 项目级（只读）
- `~/.claude/skills/` — 兼容 Claude Code 用户级（只读）
- `.codex/skills/` — 兼容 Codex 项目级（只读）
- `~/.codex/skills/` — 兼容 Codex 用户级（只读）

### SKILL.md 格式

```yaml
---
name: code-review
description: 审查代码质量
command: review
tools:
  - Read
  - Bash
---
# 正文（Markdown）
```

### 注册 & 验证

- `SkillManager` 启动时扫描目录，解析注册。Skill 文件更新后需执行 `/reload-skill` 重新加载
- 工具声明与 `ToolRegistry` 交叉验证——不存在则 ⚠️ 标记（启动时一次性检查，结果体现在 Skill 列表中，
  `hasMissingTools=true` 的 Skill 不可调用）
- System Prompt 末尾注入 Skill 列表（名称 + 描述），不包含完整正文
- LLM 通过内置 `Skill` 工具触发 Skill（对齐 Claude Code），不做关键词匹配
- 用户也可手动 `/command` 调用

### 调用方式

**LLM 自动触发：** LLM 调用 `Skill` 工具（参数：skill 名称）→ `SkillManager` 加载 SKILL.md → 正文作为消息注入
conversation → LLM 下一轮按指令执行。后续不再重复注入（避免 context 膨胀），compact 时被调用过的 skill
重新注入。

**用户手动调用：** 输入 `/command`（如 `/review`）→ `AgentLoop` 解析 `/` 指令 →
`SkillManager.getByCommand(command)` → 正文作为消息注入 conversation。绕过 LLM 决策，直接加载。

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
- INITIALIZING → 退避重试：2s → 5s → 10s → 15s...，最多 3 分钟。超时 → INIT_ERROR，需用户手动 [重连]
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
- **Agent 文本**：左对齐，左边框 3px `#3B82F6`（蓝色强调线），Markdown 流式渲染（30ms batch flush）
- **代码块**：`EditorTextField`（只读，语法高亮），背景 `#F6F8FA`，字体 `JetBrains Mono 13`
- **错误气泡**：红色背景 `#FEE2E2` + [重试] 按钮
- **系统消息**：居中，灰色 `#9CA3AF`

### 思考过程

DeepSeek V4 `reasoning_content` 以折叠块展示（默认收起），浅橙背景 `#FFF8F0`，不持久化。

### 颜色主题

亮/暗双主题通过 `AppColors` 令牌统一管理。完整色板定义见 [`ui-ux-spec.md`](ui-ux-spec.md)。

### 工具调用卡片（8 状态）

状态：⏳ PENDING → 🔒 AWAITING_APPROVAL → 🔄 EXECUTING → ✅ DONE / ❌ ERROR / ⏰ TIMEOUT / 🚫 REJECTED / ⛔
CANCELLED。

**折叠/展开：** 所有状态默认折叠，用户点击头部展开查看详情（箭头 `▾`/`▶` 切换）。AWAITING_APPROVAL
始终展开不可折叠。EXECUTING
不可折叠（用户需看到进度条和终止按钮）。结果文本区域 max-height=240px，超出滚动显示。

`Edit` 执行成功后，ToolCallCard 内联展示可视化 Diff（`SimpleDiff` 生成，ADD 绿色/DEL 红色/CTX
灰色），替换纯文本的前后对比。详见 [方案正确性验证 > 方案三](#方案三修改文件后自动展示-diffchat-ui-层)。

`EXECUTING` 卡片提供 [⏹ 终止] 按钮，可单独终止当前工具执行。`Escape` 仅关闭 Popup，无法中断 Agent、LLM
或流式生成。

### @file 引用

- `@file` 注入格式：`[File: UserService.kt (156 lines)]\n<content>\n[/File]`
- 选中代码注入：`[Selection from UserService.kt:40-60]\n<code>\n[/Selection]`
- 同时存在时去重：@file 完整文件保留，选中内容不重复注入但保留行号标记
- 手动 @file 可多个，选中引用仅一个（新选中替换旧）
- 单次 @file glob 匹配上限 50 个文件，超出部分不注入。Glob 工具在返回值中告知 LLM 截断情况，LLM
  自行判断是否需要翻页获取更多文件

**生命周期（对齐 Claude Code）：** 文件内容注入当前轮上下文，持久化到 `session.messages`。compact
时按普通消息压缩为摘要，**不单独保留原始文件内容**。compact 后 LLM 如需再次查看文件内容，必须通过
`Read` 工具重新读取，不应依赖旧消息中的文件快照。这与 Claude Code 行为一致——文件引用是一次性快照，不是永久上下文注入。

### `/` 指令 & `@` 文件引用 Popup

**内置指令：**

| 指令              | 行为              |
|-----------------|-----------------|
| `/plan`         | 进入 Plan Mode    |
| `/clear`        | 清空上下文，开启新会话     |
| `/reload-skill` | 重新扫描并加载所有 Skill |

输入 `/` 或 `@` 或点击 [+] 时从输入区域底部弹出选择列表（内置指令 + 已启用 Skills），支持 ↑↓ 键盘导航和实时过滤。

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
| TabBar 按钮 | 44×32 px，高亮 `Color(59, 130, 246)` 底边框 2px    |

### 用户反馈

每条 assistant 消息右下角附加 👍/👎 按钮：

- 点击后记录到 Session JSON 对应消息的 `feedback: "positive" | "negative"` 字段
- 不影响当前对话流程，无额外 UI 弹窗
- v1 仅收集，不做自动分析。后续可用于评估 Agent 输出质量

## 十二、messageBus 事件总线

| Topic                   | 发布者            | 订阅者                    |
|-------------------------|----------------|------------------------|
| `SessionChanged`        | SessionManager | SessionsPage, ChatPage |
| `AgentStateChanged`     | AgentSession   | ChatPage, TabBar       |
| `TokenUsageUpdated`     | AgentSession   | TokenUsagePage         |
| `McpServerStateChanged` | McpManager     | McpPage                |
| `ApiKeyValidated`       | WelcomePage    | ChatPage, SettingsPage |
| `PlanStateChanged`      | PlanExecutor   | ChatPage, SessionsPage |
| `PageSwitched`          | ChatToolWindow | 所有 Page                |

> 完整字段定义和消息类型见 [`tech-spec.md` 第三节](tech-spec.md#三messagebus-事件契约)。

## 十三、项目关闭清理

`ChatToolWindow.dispose()` 中的清理顺序（必须按序）：

```
1. 标记所有 AgentSession 为 cancelled（含子 Agent）
2. 等待子 Agent 完成（最多 3s）→ 等待 WriteCommandAction 完成（最多 5s）
3. AnthropicOkHttpClient.close() 取消所有 HTTP 请求
4. 遍历所有 AgentSession 的 runningProcesses → destroyForcibly()
5. 持久化所有未保存 Session（含执行中的 Plan）
6. McpManager.dispose()（遍历 server → shutdown 2s → destroyForcibly）
7. 释放所有 FileLock
```

## 十四、配置项汇总

> 同 [第二节 IDE Settings](#二多页面架构)，此处仅做索引。详细配置说明见第二节配置表格。

## 十五、正确性验证体系

Agent 的正确性由三层防御保证。完整方案见 [`docs/correctness.md`](correctness.md)。

| 维度    | 核心问题    | 关键防御           | 详情                                                   |
|-------|---------|----------------|------------------------------------------------------|
| 防幻觉   | 说的是真的吗？ | 精确匹配 + VFS 校验  | [correctness.md §一](correctness.md#一防止-llm-幻觉)       |
| 代码正确性 | 改对了吗？   | 自动 readLints   | [correctness.md §二](correctness.md#二agent-代码改动正确性验证) |
| 方案正确性 | 方案对吗？   | Plan 审查 + 同类参考 | [correctness.md §三](correctness.md#三agent-方案正确性验证)   |

### 防御层次速览

```
第 1 层（执行前）→ 第 2 层（执行中）→ 第 3 层（执行后）
```

四维防线总图（防幻觉 × 代码正确性 × 方案正确性 × 超长任务）见 [
`correctness.md §3.5`](correctness.md#35-四维防线总图)。

> **关联技术契约：** System Prompt 反幻觉规则见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)，代码验证流程见 [
`tech-spec.md` 2.3](../docs/tech-spec.md#23-toolregistry)，方案设计原则见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)。

## 十八、已知限制

- 仅支持 DeepSeek API（`deepseek-v4-pro`），不支持其他 LLM 提供商
- LLM 可通过 `createPlan` 工具主动创建计划
- 超长任务通过 createPlan 预防 + 轮次预警 + Auto-Compact 兜底管理，复杂度自判 ⏳ 规划中
- MCP `resources/list` 和 `prompts/list` v1 不支持
- 多 Agent 嵌套上限 1 层（子不可再 spawn 孙）
- Sessions 全文搜索暂未实现（v1 仅 title 过滤）
- `Grep` 支持正则表达式匹配，不区分大小写。非法正则自动回退为字面子串匹配
- `@file` glob 匹配上限 50 个文件，超出部分由 Glob 工具告知 LLM 截断情况，LLM 自行决定是否翻页
- `readLints` 仅支持单文件诊断
