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
│  Grep/ReadLints/Task                                  │
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
  ├─ turn++                                       ← 所有 API 调用统一计数（含续写）
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
  │    ├─ 执行（8 个工具）:
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
  │                      未超 → 追加临时 "继续" 消息（不持久化），循环回到顶部 turn++ 后继续
  │    "stop_sequence" → continueStreak = 0, 退出 while。在 assistant 消息尾部追加系统标注 "[响应被 stop_sequence 终止]"。此为 API 层面的截断信号，等效于 end_turn，Agent 进入 IDLE 等待用户下一条消息。
  │
stop: cancelled → 按[第十三节清理顺序](#十三项目关闭清理)逐步关闭（先等待子 Agent 3s → 等 WriteCommandAction 5s → client.close() → destroyForcibly()）
```

### 8 个工具

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

### 5 个计划任务管理工具

| 工具             | 参数                      | 用途                                      |
|----------------|-------------------------|-----------------------------------------|
| `createPlan`   | `task` + `steps[]`      | 创建/更新执行计划，自动开始执行，steps ≤ 20 步           |
| `listTasks`    | 无                       | 查看当前计划的所有步骤及状态                          |
| `deleteTask`   | `stepId: String`        | 删除指定步骤（仅 PENDING/ERROR 状态可删）            |
| `reorderTasks` | `stepIds: List<String>` | 重排剩余 PENDING 步骤的执行顺序                    |
| `markTaskDone` | `stepId: String`        | 将 PENDING/EXECUTING/ERROR 状态的步骤标记为 DONE |

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

LLM 输出被 max_tokens 截断时，自动发送 "继续" 消息续写。**续写计入 turn 计数**（与普通 API 调用一致，
`turn++` 在循环顶部统一执行）。

```
"max_tokens" → continueStreak++
  ├─ 超过 maxAutoContinue(5) → 终止该轮，emit Error("连续续写已达上限（5次），建议精简输入或拆分任务")
  ├─ 未超 → 追加临时 user message "继续"（不持久化）→ 回到循环顶部 → turn++ → 继续
  └─ "end_turn" 后 continueStreak 归零

关键规则：
  - 续写计入 turn（turn++ 在 while 顶部，对所有请求一视同仁）
  - "继续"不持久化（仅临时在 params.messages 中）
  - 最多连续续写 5 次（防死循环），end_turn 后计数器重置
  - 仅在当前会话生命周期内有效。重启 IDE 后不自动续写，被截断消息保持原样
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
CANCELLED 或 ERROR（崩溃场景），自动重置为 IDLE。`PROCESSING` / `AWAITING_APPROVAL` / `EXECUTING`
持久化，IDE 重启后可恢复。

### 对话回退

用户右键某条消息 → "从此处重试"：

- 删除该消息**之后**的所有消息（含选中的那条的回复链）
- 保留选中消息之前的历史
- 从选中消息重新发送到 LLM
- 回退后的消息不立即删除（标记 `deleted: true`），用户可通过"撤销回退"恢复

Plan Mode 中 LLM 通过 `deleteTask` + `createPlan` 可自行管理任务重试，不适用此机制。

## 五、Plan Mode（计划任务）

Plan Mode 将复杂任务拆解为有序步骤列表。所有步骤**自动连续执行**，用户无需手动推进。LLM 通过管理工具自主掌控任务列表。

### 职责边界

- **PlanCard**：纯 UI 组件，展示任务列表 + 进度。单任务行有 [✕] 删除按钮（PENDING 和 ERROR 状态可见，对齐
  deleteTask 工具的可删除范围）。无继续/重试/跳过/取消等全局控制按钮。
- **PlanExecutor**：负责自动执行。创建计划后按步骤顺序串行执行，每步完成后**自动进入下一步**
  ，不暂停等待用户。全部步骤执行完后 PlanCard 消失。

### 计划任务列表

PlanCard 在消息列表顶部渲染（可随消息滚动），全部步骤执行完后卡片消失。

```
Step 1 执行完 → 自动执行 Step 2 → Step 2 出错了？
  → LLM 自行判断：重试 / 调用 deleteTask 删除 / 调用 reorderTasks 调整顺序
  → 继续自动执行剩余步骤 → ... → 全部完成 → PlanCard 消失
```

用户唯一干预手段：对 PENDING 或 ERROR 状态的单步点击 [✕] 删除。

### 计划任务管理工具

LLM 通过 5 个新工具自主管理任务列表，无需用户介入：

| 工具             | 参数                      | 用途                                   |
|----------------|-------------------------|--------------------------------------|
| `createPlan`   | `task` + `steps[]`      | 创建/更新执行计划，自动开始执行                     |
| `listTasks`    | 无                       | 查看当前计划的所有步骤及状态                       |
| `deleteTask`   | `stepId: String`        | 删除指定步骤（仅 PENDING/ERROR 状态可删）         |
| `reorderTasks` | `stepIds: List<String>` | 重排剩余 PENDING 步骤的执行顺序（传入新的 stepId 序列） |
| `markTaskDone` | `stepId: String`        | 将指定步骤标记为 DONE                        |

LLM 在执行过程中可随时调用这些工具调整计划：

- 发现某个步骤不再需要 → `deleteTask`
- 执行中意识到顺序不合理 → `reorderTasks`
- 步骤已通过其他方式完成 → `markTaskDone`
- 任务范围扩大需要重新规划 → `createPlan`
- 不确定当前进度 → `listTasks`

### Plan 生命周期

- **创建**：用户 `/plan` 或 LLM 调用 `createPlan` 工具
- **执行**：创建后自动开始，逐步执行直到全部完成
- **单步删除**：用户可删除任意 PENDING 步，LLM 收到通知后跳过该步
- **完成**：全部步骤 DONE 或全部被删除 → PlanCard 消失
- **持久化**：跨会话保留，关闭 IDE 再打开可恢复

### Plan 边界处理

| 场景             | 行为                                        |
|----------------|-------------------------------------------|
| Step 引用文件已删除   | LLM 通过工具结果获知，自行决定调用 deleteTask 删除或重新 Read |
| Step 引用文件被外部修改 | `modificationStamp` 校验 → 拒绝写入 → LLM 重读后重试 |
| LLM 需要调整剩余步骤   | 调用 `reorderTasks` / `deleteTask` 自主管理     |
| 多个会话各有计划       | 允许，每个独立存储                                 |

### Plan JSON Schema（存于 Session JSON 的 `plan` 字段）

```json
{
  "plan": {
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
        "retryCount": 0,
        "deletedBy": null
      },
      {
        "id": "step-2",
        "description": "修改方法签名",
        "tool": "Edit",
        "files": [
          "UserService.kt"
        ],
        "status": "PENDING",
        "retryCount": 0,
        "deletedBy": null
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
> 创建执行计划。执行中可随时用 listTasks/deleteTask/reorderTasks 管理步骤。

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

子 Agent 在父面板中以**内联折叠块**形式展示：

1. Task ToolCallCard：
  - 头部显示 "🤖 子 Agent: {taskSummary}" + 状态（PENDING → EXECUTING → DONE）
  - 折叠面板内：子 Agent 流式文本实时追加（30ms batch flush）、子 ToolCallCard 嵌套展示（可折叠）、错误红色标注
  - 底部显示耗时 + "详情: sub-session #{sessionId}"（点击跳转 SessionsPage）

2. 实时渲染：

- 子 Agent 流式输出追加到父面板的 TaskToolCallCard 中，不新建独立面板
  - 父 Agent 暂停等待子 Agent 结果（子 Agent 完成前父 Agent 不再发起新请求）

3. SessionsPage 父子关联：
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

### 会话导出（v1）

Sessions 页面提供"导出"按钮，将 Session JSON 转换为 Markdown 对话记录：

- 用户消息 → `### 用户` + 原文
- Agent 文本 → 原文（含 Markdown 格式）
- Tool call → `<details><summary>🔧 {toolName}</summary>\n\n```\n{result}\n```\n</details>`
- 错误 → `> ⚠️ 错误: {content}`
- 导出文件命名：`{session.title}-{date}.md`

会话合并/拆分标记为 v2。

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

`Edit` 执行成功后，ToolCallCard 内联展示可视化 Diff（`SimpleDiff` 生成，ADD 绿色/DEL 红色/CTX
灰色），替换纯文本的前后对比。详见 [方案正确性验证 > 方案三](#方案三修改文件后自动展示-diffchat-ui-层)。

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

## 十五、防止 LLM 幻觉

> **关联技术契约：** System Prompt 反幻觉规则见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)，工具前置校验（VFS 校验/Read
> 前置/stamp 校验）见 [`tech-spec.md` 2.3](../docs/tech-spec.md#23-toolregistry)，工具结果自检反馈环见 [
`tech-spec.md` 2.1](../docs/tech-spec.md#21-agentloop)，Shell 输出强化标注见 [
`tech-spec.md` 九 > Bash](../docs/tech-spec.md#runshell)。

LLM 会产生几种典型幻觉：**凭空编造文件路径和 API**、**基于过时信息做决策**、**忽略工具返回的错误信息**、
**假设搜索结果完整**。以下按防御层次说明已有措施和加强方案。

### 15.1 已有防御措施

| 机制                     | 防御的幻觉类型   | 原理                                                           |
|------------------------|-----------|--------------------------------------------------------------|
| `Edit` 精确匹配            | 瞎编代码内容    | `oldString` 必须在文件中**精确且唯一**匹配，否则拒绝写入并返回错误（含附近行内容引导 LLM 重试）   |
| `modificationStamp` 校验 | 基于过时信息修改  | 文件被外部修改后 stamp 不匹配 → 拒绝写入 → 强制 LLM 重新 `Read`                 |
| 工具返回截断 + 明确标记          | 遗漏关键信息    | 截断时尾部标注 `...(共 N 行/条)`，LLM 知道自己看到的不是全部                       |
| Shell 危险命令二次确认         | 幻觉出危险命令   | `rm -rf /`、`git push --force`、`sudo`、`chmod 777` 弹出二次确认，不可跳过 |
| System Prompt"先读后写"原则  | 凭空猜测文件内容  | 强制 LLM 先用 `Read` 获取真实内容再 `Edit`/`Write`                      |
| `Grep` 无结果明确返回         | 搜不到假装搜到了  | 返回 `"未找到匹配"` + 建议（检查拼写、使用更简短搜索词）                             |
| 工具结果自检反馈环              | 忽略工具返回的异常 | 每次 tool result 回传前，根据结果类型附加检查提示（详见 System Prompt）            |

### 15.2 加强方案

#### 方案一：System Prompt 反幻觉指令（零成本 ✅ 已实施）

> 已实施于 [`tech-spec.md` 8.1](tech-spec.md#81-agent-基础-system-prompt) System Prompt 的「防止幻觉」段落。

#### 方案二：文件路径 VFS 校验

在 `ToolExecutor` 中对文件操作工具做前置校验：

| 工具      | 校验规则                                                        |
|---------|-------------------------------------------------------------|
| `Read`  | VFS 中文件不存在 → 返回错误，不执行                                       |
| `Edit`  | 非新建场景（`oldString` 非空）且文件不存在 → 返回错误；新建场景（`oldString` 为空）→ 放行 |
| `Write` | 始终放行（覆盖写入/新建均可）                                             |

#### 方案三：`Read` 前置强制执行

在 `ToolExecutor` 中，`Edit` 和 `Write` **覆盖模式**执行前检查：

> 该文件在当前 turn 中是否被 `Read` 读取过？
> → 否：拒绝执行，返回 `"请先用 Read 读取 {filePath} 后再修改，确保你了解文件的真实内容"`

LLM 将无法在没看过文件的情况下瞎编。这是防御幻觉**性价比最高**的措施。

#### 方案四：Shell 输出强化标注

将 `Bash` 的返回值格式从平铺改为错误优先：

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

| 工具          | 结果特征         | 自动附加提示                                                       |
|-------------|--------------|--------------------------------------------------------------|
| `Read`      | 文件不存在        | `提示：文件不存在，请确认路径是否正确。不要假设文件内容。`                               |
| `Grep`      | 0 条匹配        | `提示：未找到匹配。不要假设代码存在，考虑用 Glob 确认文件位置。`                         |
| `Grep`      | 结果被截断        | `提示：搜索结果已截断，可能有遗漏。如需修改代码，建议先用更精确的关键词确认范围。`                   |
| `Bash`      | 退出码非 0       | `⚠️ 命令执行失败。请分析错误原因后决定下一步，不要忽略此错误继续执行。`                       |
| `Bash`      | stderr 非空    | `⚠️ 命令有错误输出，请检查 stderr 内容。`                                  |
| `readLints` | 有 ERROR 级别诊断 | `⚠️ 文件存在编译错误。请先解决这些错误再继续修改代码。`                               |
| `Glob`      | 结果被截断        | `提示：目录列表已截断。可用 dirPath/maxDepth 缩小范围，或使用 offset 参数翻页获取更多条目。` |

### 15.3 防御层次总结

```
第 1 层：事前预防（阻止幻觉产生）
  ├─ System Prompt 反幻觉指令        ← 只改 prompt
  └─ System Prompt 复杂度判断规则     ← 同上

第 2 层：事前硬约束（阻止基于幻觉的操作）
  ├─ Read 前置强制执行           ← 改 ToolExecutor
  ├─ 文件路径 VFS 校验              ← 改 ToolExecutor
  └─ Edit 精确匹配 + stamp 校验  ← 已有

第 3 层：事后纠偏（让 LLM 意识到自己错了）
  ├─ Shell 输出强化标注              ← 改 Bash 返回值格式
  ├─ 工具结果自检反馈环              ← 改 AgentLoop
  └─ 工具返回截断 + 明确标记         ← 已有
```

### 15.4 推荐实施路径

| 优先级 | 方案                  | 改动量              | 效果            |
|-----|---------------------|------------------|---------------|
| 1   | System Prompt 反幻觉指令 | 只改 prompt        | LLM 行为基线提升    |
| 2   | `Read` 前置强制执行       | ToolExecutor 加检查 | 杜绝凭空编造代码      |
| 3   | 文件路径 VFS 校验         | ToolExecutor 加检查 | 杜绝幻觉文件路径      |
| 4   | Shell 输出强化标注        | 改返回值格式           | LLM 更准确理解执行结果 |
| 5   | 工具结果自检反馈环           | AgentLoop 加后处理   | 系统性减少所有工具误读   |

> 方案 1 零成本立即可做，方案 2+3 改动小防御面广，方案 4+5 是锦上添花的系统性优化。

## 十六、Agent 代码改动正确性验证

> **关联技术契约：** System Prompt 验证流程见 [
`tech-spec.md` 8.1](../docs/tech-spec.md#81-agent-基础-system-prompt)，修改后自动 readLints +
> 回归测试提示 + 影响范围分析见 [`tech-spec.md` 2.3](../docs/tech-spec.md#23-toolregistry)，Diff
> 可视化见 [`tech-spec.md` 九 > Edit](../docs/tech-spec.md#editfile) 和 [
`tech-spec.md` 2.6](../docs/tech-spec.md#26-toolcallcard--plancard)。

Agent 改完代码后，核心问题是：**改对了吗？** 这个问题分两层——Agent 自己如何验证（自检），用户如何验证（审查）。

### 16.1 已有验证机制

| 机制                     | 验证层面     | 说明                                            |
|------------------------|----------|-----------------------------------------------|
| `Edit` 精确匹配            | 改对位置     | `oldString` 精确唯一匹配保证改到了目标代码                   |
| `modificationStamp` 校验 | 基于最新版本   | 确保 Agent 看到的是修改时的真实文件状态                       |
| `readLints` 工具         | 语法/类型正确性 | Agent 可以主动读取 IDE 诊断，发现编译错误                    |
| `Bash` 工具              | 编译/测试正确性 | Agent 可以运行 `./gradlew build`、`./gradlew test` |
| PlanCard 进度展示          | 用户审查     | 用户可随时查看计划执行进度，删除不需要的待执行步骤                     |
| `Edit` 返回前后对比          | 改动可见性    | 返回替换前后的 3 行代码，Agent 和用户可判断合理性                 |

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
> 4. 如果项目没有现成测试或编译耗时过长，至少用 Read 重新读取修改区域确认改动符合预期。
> ```

#### 方案二：`Edit`/`Write` 后自动 `readLints`（Agent Loop 层）

在 Agent Loop 中，每次 `Edit` 或 `Write` 成功执行后，自动对被修改文件运行一次 `readLints`
，将诊断结果追加到 tool result 中：

```
Edit 执行成功
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

当 `Edit` 成功后，在老字符串和新字符串之间生成**可视化 diff**，在 ToolCallCard 中以内联方式展示：

```
🔧 Edit: UserService.kt                    ✅ 完成
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

用 `Grep` 反向查找被修改符号的引用处，帮助 Agent 判断是否需要联动修改：

```
Edit 成功后
  → 提取修改涉及的方法名/类名（简单正则：fun/class/val/var 后的标识符）
  → 自动 Grep 搜索该标识符在项目中的引用
  → 将搜索结果追加提示："{symbolName} 在 N 个文件中被引用：file1, file2... 请确认这些引用是否需要联动修改"
```

### 16.3 验证层次总结

```
第 1 层：Agent 自检（每次修改后自动触发）
  ├─ 修改后自动 readLints           ← 方案二
  ├─ System Prompt 自检指令         ← 方案一
  └─ Edit 精确匹配（已有）       ← 确保改对地方

第 2 层：Agent 主动验证（需要 Agent 判断触发）
  ├─ Bash 编译/测试             ← 已有
  ├─ 回归测试智能提示               ← 方案四
  └─ 改动影响范围分析               ← 方案五

第 3 层：用户审查（人眼确认）
  ├─ PlanCard 进度展示（已有）      ← 最可靠的验证
  ├─ 修改后 Diff 可视化展示          ← 方案三
  └─ ToolCallCard 中的前后对比（已有）
```

### 16.4 推荐实施路径

| 优先级 | 方案                 | 改动量                 | 效果               |
|-----|--------------------|---------------------|------------------|
| 1   | System Prompt 自检指令 | 只改 prompt           | Agent 养成改完就验证的习惯 |
| 2   | 修改后自动 readLints    | ToolExecutor 加静默调用  | 编译错误第一时间发现       |
| 3   | 修改后 Diff 可视化       | Chat UI 层           | 用户审查效率大幅提升       |
| 4   | 回归测试智能提示           | ToolExecutor 加提示    | 减少"忘记跑测试"导致的回归   |
| 5   | 改动影响范围分析           | ToolExecutor + Grep | 减少联动修改遗漏         |

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

| 机制                  | 防御层面   | 说明                                    |
|---------------------|--------|---------------------------------------|
| Plan Mode           | 方案审查   | 执行前用户看到完整计划，可在源头否决错误方案                |
| System Prompt 项目上下文 | 风格对齐   | 告知项目名、技术栈、当前文件，帮助 LLM 定位              |
| Skill 系统            | 领域知识注入 | `SKILL.md` 可携带项目特定规范（如"本项目禁止使用 X 模式"） |
| `Read` + `Grep`     | 了解现状   | Agent 可以在动手前搜索类似实现作为参考                |

但这些机制都**依赖 Agent 主动使用**或**依赖用户判断**，没有系统化的自动校验。

### 17.3 增强方案

#### 方案一：System Prompt"先理解再动手"（零成本）

在 System Prompt 中加入方案层面的行为准则：

> ```
> ## 方案设计原则
>
> 在动手修改代码前，先理解项目现状：
>
> 1. 如果任务是修改/扩展已有功能，先用 Grep 搜索项目中类似实现（如"项目中其他 Service 类长什么样"），以现有模式为模板。
> 2. 如果任务是新增功能，先在项目中找一个最相似的文件通读，保持风格一致（命名、结构、错误处理方式）。
> 3. 优先复用项目已有的工具类、基类、扩展函数，不要自己从头写。
> 4. 选择方案时遵循项目已有的复杂度水平——如果项目里其他 Service 都是单文件 200 行，你就不该引入多层抽象。
> 5. 只改和任务直接相关的代码，不要顺便重构不相关的文件。
> ```

#### 方案二：方案自检清单（System Prompt 追加）

每次提出修改方案前，满足以下**任一条件**时 Agent 在回复中简要自检（一行即可）：

**触发条件：** 涉及 3 个以上文件 或 新增/删除方法签名（`fun`/`def`/`function`）或 创建新文件 或 Plan
Mode 执行中。单文件微调（如修改变量名、加注释）不触发。

Agent 自问以下问题：

> 1. **模式对齐**：项目里有没有类似实现可以参考？我是否遵循了？
> 2. **最简单方案**：有没有更简单的写法？我是否过度设计了？
> 3. **影响范围**：这个修改会影响多少调用者？有没有遗漏的联动修改？
> 4. **破坏性**：是不是 Breaking Change？如果是，用户知道吗？

自检结果直接写在回复中——用户可以一眼判断 Agent 有没有经过思考，还是拍脑袋出方案。

#### 方案三：同类代码自动参考（Agent Loop 层）

在 Agent Loop 中，当 `Read` 打开一个新文件时，自动附加一段"风格参考"到 tool result：

```
Read 返回 UserService.kt
  → 自动分析文件特征（缩进风格、命名模式、使用的框架/工具类）
  → tool result 底部追加:
     "📋 风格参考: 该文件使用 4 空格缩进，方法名驼峰式，依赖通过构造器注入。
      项目中类似的 Service 类有: AuthService.kt, OrderService.kt（可用 Read 查看）"
```

这让 Agent 在修改前就有明确的风格参照，减少风格不一致。

#### 方案四：用户确认关口（关键操作需审批）

对以下**高影响操作**弹出确认对话框，Agent 必须等用户同意后才执行：

| 操作类型      | 判定条件                                      | 确认内容                        |
|-----------|-------------------------------------------|-----------------------------|
| 公共 API 变更 | 修改了 `public`/`open` 方法签名                  | "将修改公共方法 X，可能影响 N 个调用者。确认？" |
| 新增依赖      | `build.gradle.kts` 中新增 `implementation` 行 | "将新增依赖 Y。确认？"               |
| 删除文件      | `Write` 空内容（等价删除）或直接 `Bash rm`            | "将删除文件 Z。确认？"               |
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

### 17.5 四维防线总图

以下是全部四套"三层"体系的统一视图。按时间轴对齐：**执行前 → 执行中 → 执行后**。

| 防线层次      | 时间点 | 防幻觉（第十五节）                                         | 代码正确性（第十六节）                                           | 方案正确性（第十七节）                        | 超长任务（第八节）                                    |
|-----------|-----|---------------------------------------------------|-------------------------------------------------------|------------------------------------|----------------------------------------------|
| **第 1 层** | 执行前 | **事前预防**：System Prompt 反幻觉指令 + 复杂度判断              | **Agent 自检**：System Prompt 自检指令                       | **方案合理性**：Plan 审查 + 方案设计原则 + 自检清单  | **预防**：LLM 自动规划 / System Prompt 复杂度自判        |
| **第 2 层** | 执行中 | **事前硬约束**：Read 前置 + VFS 校验 + Edit 精确匹配 + stamp 校验 | **Agent 主动验证**：修改后自动 readLints + 编译/测试 + 影响范围分析       | —（方案正确性只有事前和事后）                    | **预警**：⏳ 规划中 轮次预警（turn ≥ maxTurns × 0.6 时提示） |
| **第 3 层** | 执行后 | **事后纠偏**：Shell 输出强化标注 + 工具结果自检反馈环 + 截断标注          | **用户审查**：Diff 可视化 + PlanCard 进度展示 + ToolCallCard 前后对比 | **审查兜底**：AI 代码审查 + 用户最终审查 + 关键操作确认 | **兜底**：Auto-Compact（700K tokens 阈值压缩）        |

```
时间轴：  执行前                 执行中                 执行后
         ├──────────────────────┼──────────────────────┤
防幻觉    │ 事前预防              │ 事前硬约束             │ 事后纠偏
         │ (Prompt 指令)        │ (VFS/精确匹配/stamp)  │ (输出标注/反馈环)
         ├──────────────────────┼──────────────────────┤
代码正确性 │ Agent 自检           │ Agent 主动验证         │ 用户审查
         │ (Prompt 自检指令)     │ (自动 lint/编译/测试)   │ (Diff 可视化/Plan)
         ├──────────────────────┼──────────────────────┤
方案正确性 │ 方案合理性            │ (无 — 方案在执行前      │ 审查兜底
         │ (Plan 审查/同类参考)   │  就决定了)            │ (AI Review/用户审查)
         ├──────────────────────┼──────────────────────┤
超长任务   │ 预防                 │ 预警                  │ 兜底
         │ (LLM 自动规划)        │ (轮次预警)             │ (Auto-Compact)
         └──────────────────────┴──────────────────────┘
```

**各维度的核心问题：**

| 维度    | 问的问题    | 典型错误       | 关键防御           |
|-------|---------|------------|----------------|
| 防幻觉   | 说的是真的吗？ | 捏造 API/路径  | 精确匹配 + VFS 校验  |
| 代码正确性 | 改对了吗？   | 类型错误、NPE   | 自动 readLints   |
| 方案正确性 | 方案对吗？   | 过度设计、破坏性修改 | Plan 审查 + 同类参考 |
| 超长任务  | 会跑飞吗？   | 无限循环、上下文溢出 | Auto-Compact   |

### 17.6 推荐实施路径

| 优先级 | 方案                   | 改动量              | 效果                    |
|-----|----------------------|------------------|-----------------------|
| 1   | System Prompt 方案设计原则 | 只改 prompt        | Agent 基线行为提升，先看再改     |
| 2   | 方案自检清单               | 只改 prompt        | 用户可在回复中看到 Agent 的思考质量 |
| 3   | 关键操作确认               | ToolExecutor 加审批 | 防止不可逆操作               |
| 4   | 同类代码自动参考             | AgentLoop 工具结果增强 | 减少风格不一致               |
| 5   | AI 代码审查              | 独立 API 调用        | 独立视角兜底                |

> 方案 1+2 零成本立即可做，方案 3 防御面广性价比高，方案 4+5 是系统性优化。方案 5（AI
> 代码审查）虽排最后但独特价值在于"独立视角"——Agent 自己发现不了的问题，另一个不带 tools 的小模型可能会发现。建议在方案
> 2（自动 readLints）落地后优先实施。

### 独立评估（AI Code Review）

每次 Agent 任务完成（`end_turn`）后，自动用独立 API 调用做轻量代码审查：

- 独立 API 调用：不带 tools，`max_tokens=512`，system prompt = "你是代码审查专家"
- 输入：当前 Session 中所有 `Edit`/`Write` 的 diff 聚合
- 关注：风格不一致、过度设计、可能破坏的调用者、可复用的已有工具
- 输出：仅报告有问题的地方。无问题则输出"无问题"
- 结果以系统消息追加到对话，Agent 可以在下一轮修正
- 不阻塞流程（审查结果作为参考信息呈现）

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
