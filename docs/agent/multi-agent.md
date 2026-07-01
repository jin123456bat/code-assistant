# 多 Agent 协作

> 关联文档：[Agent Loop](./loop.md)、[工具系统](./tools.md)

## 一、架构

父 Agent 通过 `Agent` 工具启动子 Agent处理子任务，由 `MultiAgentManager` 统一调度。

**MultiAgentManager 职责：**

- 并发控制：`Semaphore(maxConcurrentAgents)` 限制全局 Agent 并发总数（父 + 子总计）。默认 3（1 父 + 最多
  2 子），可在 Settings 中修改 `maxConcurrentAgents`（0=不限）
- 子 Agent 创建：详见 [Agent 工具参数](../agent/tools.md#agent)
- 文件写锁：`ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享同一份锁表）
- crash 清理：子 Agent 异常退出时自动释放信号量、释放文件写锁、销毁 Shell 进程
- 文件变更通知：子 Agent 完成后，父 Agent 缓存的文件 `modificationStamp` 自动失效，下次 Edit 前强制重新
  Read。参见 [前置校验 § modificationStamp](../agent/tools.md#七前置校验)

## 二、关键约束

| 约束项        | 规则                                                                                                                                                                                                                             |
|------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 并发控制       | `Semaphore(maxConcurrentAgents)` 限制全局 Agent 并发总数（父 + 子总计）。默认 3（1 父 + 最多 2 子），0=不限，可在 Settings 中修改。排队策略：FIFO 公平排队，先请求先获得                                                                                                        |
| 文件写锁       | `ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享）                                                                                                                                                                   |
| 递归限制       | 最多 1 层嵌套（子不可再 spawn 孙）                                                                                                                                                                                                         |
| 结果摘要       | ≤ 2000 tokens 写入父的 toolResult。摘要 = 子 Agent 最后一轮 assistant 消息 + 所有 tool call 结果原文拼接，截断到 2000 tokens。不额外调用 LLM 生成摘要                                                                                                              |
| 子 Session  | 独立持久化（`session.parentId` 关联）                                                                                                                                                                                                   |
| 上下文构建      | 子 Agent 上下文独立构建，不继承父 Agent 的历史对话。仅包含：① System Prompt 基础部分（角色指令 + 工具描述 + 环境信息，不含父的对话历史摘要） + ② 父的 `prompt` 参数作为首条 user message。子 Agent 结束后，结果摘要通过回调写回父的 toolResult                                                               |
| 上下文压缩      | 子 Agent 复用父的 Auto-Compact 机制（同一阈值、策略），详见 [context.md §二](context.md#二auto-compact)                                                                                                                                             |
| 子 Agent 失败 | 子 Agent crash → `"Sub-agent failed: <error>"`；超时 → `"Sub-agent timeout: {timeout}s"`。父 LLM 自行决定重试或调整策略。crash/超时后自动清理：释放信号量、释放文件写锁、销毁 Shell 进程。**子 Agent 崩溃不会导致父 Agent 崩溃**。如果父同时 spawn 了多个子 Agent，其中 1 个 crash，另外的子 Agent 继续执行 |

## 三、子 Agent 审批与工具限制

### 审批策略

**子 Agent 不需要审批，所有工具调用一律自动放行。**

原因：

- 父 Agent 调用 `Agent` 工具 spawn 子 Agent 时已经过审批（首次使用时弹窗确认），信任已建立
- 子 Agent 的工具范围受白名单/黑名单限制，不会越权操作
- 对齐 Claude Code：子 Agent 继承父会话的权限模式，父 Agent 处于高权限模式时子 Agent 强制继承

### 实现机制

**子 Agent 在 `ToolApprovalPolicy.needsUserApproval()`
最顶部（危险命令检测之前）通过 `session.isSubAgent` 守卫直接返回 `false`，跳过所有审批检查。**

`AgentSession.isSubAgent` 为计算属性（`parentId != null`），无需额外状态维护。这意味着子 Agent
创建时就自动获得免审批权限——无论父 Agent 当前处于什么权限状态。

> 对齐 Claude Code：子 Agent 继承父会话的权限模式。当前实现为最简策略——只要是子 Agent 就全部放行，不做细粒度权限区分。

### 工具白名单

子 Agent 的工具范围由系统预配置，LLM 调用 `Agent` 时无需传类型参数。

| 系统预配置           | 可用工具                   | 说明            |
|-----------------|------------------------|---------------|
| Explore（只读搜索）   | `Read`, `Grep`, `Glob` | 纯只读，不允许修改文件   |
| General-purpose | 全部 11 个工具（见下方列表）       | 通用任务执行，默认全部可用 |

**General-purpose 默认可用工具（11 个）：** `Read`, `Write`, `Edit`, `Bash`, `Glob`, `Grep`,
`readLints`, `WebSearch`, `WebFetch`, `AskUserQuestion`, `Symbol`

> **不可用：** `Agent`（禁止嵌套）、`Skill`（Skill 注入由父 Agent 管理，子 Agent 不加载 Skill）。计划管理工具（
`createPlan` 等 5 个）在子 Agent 中不可用——子 Agent 的任务由父 Agent 通过 prompt 直接描述。

### 工具限制规则

| 限制方式   | 配置                              | 说明                     |
|--------|---------------------------------|------------------------|
| 白名单    | `tools: Read, Grep, Glob, Bash` | 子 Agent**仅获得**列出的工具    |
| 黑名单    | `disallowedTools: Write, Edit`  | 子 Agent获得**除列表外**的全部工具 |
| MCP 禁用 | `disallowedTools: mcp__*`       | 禁用全部 MCP 工具            |
| 特定 MCP | `disallowedTools: mcp__github`  | 禁用指定 MCP Server 的全部工具  |

**始终不可用的工具：**

| 工具      | 原因                                    |
|---------|---------------------------------------|
| `Agent` | 当前仅支持 1 层嵌套（子不可 spawn 孙）              |
| `Skill` | Skill 注入由父 Agent 管理，子 Agent 不加载 Skill |

> 对齐 Claude Code：`AskUserQuestion`、`EnterPlanMode` 等依赖父会话状态的工具在子 Agent
> 中不可用。当前项目因嵌套上限为
> 1 层，`Agent` 工具在子 Agent 中同样不可用。

### 与父 Agent 审批的区别

| 对比维度             | 父 Agent（详见 [审批机制](../agent/tools.md#六审批机制)） | 子 Agent                            |
|------------------|---------------------------------------------|------------------------------------|
| Agent 工具首次使用     | 弹窗确认                                        | 不可用（禁止嵌套）                          |
| 文件读写             | 首次需审批                                       | 一律放行                               |
| Shell 执行         | 首次需审批，危险命令二次确认                              | 一律放行（工具范围已限制）                      |
| MCP 工具           | 首次需审批                                       | 一律放行（可整体禁用）                        |
| 危险命令（`rm -rf` 等） | 强制二次确认                                      | 一律放行（父 Agent 已建立信任，子 Agent 工具范围受限） |

**实现要点：** 子 Agent 免审批通过 `ToolApprovalPolicy.needsUserApproval()` 顶部守卫实现，无需在子
Agent 的 `AgentLoop` 或 `ToolExecutor` 中做额外判断。审批跳过发生在策略层，对工具执行路径透明。

---

## 四、父子通信

```
父 Agent 调用 Agent(prompt="重构 UserService")
  → 子 Agent 在线程池中异步执行
  → 父 Agent 立即收到确认，继续执行后续任务
  → ...（父 Agent 同时处理其他工作）...
  → 子 Agent 完成 → MultiAgentManager 回调父 Agent
  → toolResult 写入子任务摘要

并行模式（任务完全独立时）:
父 Agent
  ├─ Agent(prompt="重构 UserService") → 子 Agent A（后台运行）
  ├─ Agent(prompt="写单元测试")       → 子 Agent B（后台运行）
  ├─ 父 Agent 继续执行，可响应新消息
  └─ 子 Agent A、B 完成后分别回调 → 父 Agent 汇总结果
```

**通信规则：**

- **规则**：子 Agent 之间**禁止直接通信**。所有协调通过父 Agent——父 Agent spawn 子 Agent →
  接收回调 → 决定是否 spawn 更多子 Agent。
- **同步/异步**：见 [Agent 工具参数](../agent/tools.md#agent)
- **并发上限**：见 [关键约束](#二关键约束) 中的并发控制
- **结果传递**：子 Agent 完成后，结果摘要（≤ 2000 tokens）通过回调写入父的 toolResult。子 Agent
  的完整执行过程作为一个独立
  Session 持久化（`session.parentId` 关联）。
- **文件修改通知**：子 Agent 完成时，`MultiAgentManager` 自动失效父 Agent 中被子 Agent 修改过的文件的
  `modificationStamp`，迫使父 Agent 下次 `Edit` 前必须重新 `Read`
  。参见 [前置校验](../agent/tools.md#七前置校验)
- **数据流路径**：子 Agent 的 `AgentLoop` 回调（`onToken`, `onToolCall`, `onToolCallStateChanged`）由
  `MultiAgentManager` 封装为 `SubAgentEvent` 密封类事件，通过回调链
  `MultiAgentManager → ToolExecutor → AgentLoop → ChatViewModel → ChatPage` 传递到
  `MultiAgentBlock` UI 组件。`ChatViewModel` 负责 EDT 线程调度（`SwingUtilities.invokeLater`）。
  子 Agent 的 `ToolCallCard` 在 `MultiAgentBlock` 卡片内部独立渲染，不混入父的 `messageContainer`。
- **Chat 页面展示**：仅显示 `🤖 Agent: 重构 UserService → 已完成 (sub-session #42)，摘要: ...`。子 Agent
  的 ToolCallCard 出现在自己的 Session 视图中，不嵌入父的 ChatPage。

## 五、子 Agent UI

> MultiAgentBlock 的 UI
> 布局及交互详见 [Chat 面板 §六](../ui/chat.md#六多-agent-调度卡片multiagentblock)。

## 六、SessionsPage 关联

- SessionIndex 添加 `parentId: String?` 字段
- 子 Session 在列表中缩进 + `└─` 前缀展示
- 点击子 Session 打开只读历史面板

## 七、文件写入并发控制

写文件前校验 `VirtualFile.modificationStamp` 与 Agent 上次读取时的 stamp 一致——不一致则拒绝写入并返回错误给
Agent，Agent 自动重读后重试。

`ConcurrentHashMap<VirtualFile, ReentrantLock>` 确保同一文件同时只有一个 Agent 在写。任务正交分派（如
Agent-A 重构、Agent-B 测试、Agent-C 审查）从源头减少冲突。
