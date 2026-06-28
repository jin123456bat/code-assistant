# 多 Agent 协作

> 关联文档：[Agent Loop](./loop.md)、[工具系统](./tools.md)

## 一、架构

父 Agent 通过 `Agent` 工具启动子代理处理子任务，由 `MultiAgentManager` 统一调度。

**MultiAgentManager 职责：**

- 并发控制：`Semaphore(maxConcurrentAgents)` 限制全局 Agent 并发总数（父 + 子总计）。默认 3（1 父 + 最多
  2 子），可在 Settings 中修改 `maxConcurrentAgents`（0=不限）
- 子 Agent 创建：详见 [Agent 工具参数](../agent/tools.md#agent)
- 文件写锁：`ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享同一份锁表）
- crash 清理：子 Agent 异常退出时自动释放信号量、释放文件写锁、销毁 Shell 进程
- 文件变更通知：子 Agent 完成后，父 Agent 缓存的文件 `modificationStamp` 自动失效，下次 Edit 前强制重新
  Read。参见 [前置校验 § modificationStamp](../agent/tools.md#七前置校验)

## 二、关键约束

| 约束项        | 规则                                                                                                                                            |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 并发控制       | `Semaphore(maxConcurrentAgents)` 限制全局 Agent 并发总数（父 + 子总计）。默认 3（1 父 + 最多 2 子），0=不限，可在 Settings 中修改                                             |
| 文件写锁       | `ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享）                                                                                  |
| 递归限制       | 最多 1 层嵌套（子不可再 spawn 孙）。Phase 5 后可评估是否放宽                                                                                                       |
| 结果摘要       | ≤ 2000 tokens 写入父的 toolResult。摘要 = 子 Agent 最后一轮 assistant 消息 + 所有 tool call 结果原文拼接，截断到 2000 tokens。不额外调用 LLM 生成摘要                             |
| 子 Session  | 独立持久化（`session.parentId` 关联）                                                                                                                  |
| 子 Agent 失败 | 子 Agent crash → `"Sub-agent failed: <error>"`；超时 → `"Sub-agent timeout: {timeout}s"`。父 LLM 自行决定重试或调整策略。crash/超时后自动清理：释放信号量、释放文件写锁、销毁 Shell 进程 |

## 三、子代理审批与工具限制

### 审批策略

**子代理不需要审批，所有工具调用一律自动放行。**

原因：

- 父 Agent 调用 `Agent` 工具 spawn 子代理时已经过审批（首次使用时弹窗确认），信任已建立
- 子代理的工具范围受白名单/黑名单限制，不会越权操作
- 对齐 Claude Code：子代理继承父会话的权限模式，父 Agent 处于高权限模式时子代理强制继承

### 权限继承

父 Agent 在审批过程中可进入两种高权限模式：

| 权限模式                | 触发条件              | 行为                                    |
|---------------------|-------------------|---------------------------------------|
| `bypassPermissions` | 用户在审批弹窗中选择"允许此会话" | 当前会话内所有后续工具调用跳过审批，全部自动放行              |
| `acceptEdits`       | 用户授予编辑权限          | 当前会话内文件编辑类操作（Write/Edit）自动批准，其余工具仍需审批 |

父 Agent 的权限状态决定子代理行为：

| 父权限状态              | 子代理行为              |
|--------------------|--------------------|
| 首次使用已确认            | 子代理内所有工具自动放行，不弹审批窗 |
| 父处于 bypass 模式      | 子代理强制继承，全部操作跳过审批   |
| 父处于 acceptEdits 模式 | 子代理强制继承，编辑类操作自动批准  |

> 对齐 Claude Code：父 Agent 处于高权限模式（`bypassPermissions`、`acceptEdits`）时，子代理强制继承该模式且
**不可被子代理覆盖**。子代理可能有不同的 system prompt 和行为约束，因此继承高权限模式意味着它们获得完全的自主执行权限。

### 工具白名单

子代理的工具范围由系统预配置，LLM 调用 `Agent` 时无需传类型参数。

| 系统预配置           | 可用工具                   | 说明            |
|-----------------|------------------------|---------------|
| Explore（只读搜索）   | `Read`, `Grep`, `Glob` | 纯只读，不允许修改文件   |
| General-purpose | 全部工具（可配置排除项）           | 通用任务执行，默认全部可用 |

### 工具限制规则

| 限制方式   | 配置                              | 说明                    |
|--------|---------------------------------|-----------------------|
| 白名单    | `tools: Read, Grep, Glob, Bash` | 子代理**仅获得**列出的工具       |
| 黑名单    | `disallowedTools: Write, Edit`  | 子代理获得**除列表外**的全部工具    |
| MCP 禁用 | `disallowedTools: mcp__*`       | 禁用全部 MCP 工具           |
| 特定 MCP | `disallowedTools: mcp__github`  | 禁用指定 MCP Server 的全部工具 |

**始终不可用的工具：**

| 工具      | 原因                               |
|---------|----------------------------------|
| `Agent` | 当前仅支持 1 层嵌套（子不可 spawn 孙）         |
| `Skill` | Skill 注入由父 Agent 管理，子代理不加载 Skill |

> 对齐 Claude Code：`AskUserQuestion`、`EnterPlanMode` 等依赖父会话状态的工具在子代理中不可用。当前项目因嵌套上限为
> 1 层，`Agent` 工具在子代理中同样不可用。

### 与父 Agent 审批的区别

| 对比维度             | 父 Agent（详见 [审批机制](../agent/tools.md#六审批机制)） | 子 Agent                            |
|------------------|---------------------------------------------|------------------------------------|
| Agent 工具首次使用     | 弹窗确认                                        | 不可用（禁止嵌套）                          |
| 文件读写             | 首次需审批                                       | 一律放行                               |
| Shell 执行         | 首次需审批，危险命令二次确认                              | 一律放行（工具范围已限制）                      |
| MCP 工具           | 首次需审批                                       | 一律放行（可整体禁用）                        |
| 危险命令（`rm -rf` 等） | 强制二次确认                                      | 一律放行（父 Agent 已建立信任，子 Agent 工具范围受限） |

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

- **v1 规则**：子 Agent 之间**禁止直接通信**。所有协调通过父 Agent——父 Agent spawn 子 Agent →
  接收回调 → 决定是否 spawn 更多子 Agent。
- **同步/异步**：见 [Agent 工具参数](../agent/tools.md#agent)
- **并发上限**：见 [关键约束](#二关键约束) 中的并发控制
- **结果传递**：子 Agent 完成后，结果摘要（≤ 2000 tokens）通过回调写入父的 toolResult。子 Agent
  的完整执行过程作为一个独立
  Session 持久化（`session.parentId` 关联）。
- **文件修改通知**：子 Agent 完成时，`MultiAgentManager` 自动失效父 Agent 中被子 Agent 修改过的文件的
  `modificationStamp`，迫使父 Agent 下次 `Edit` 前必须重新 `Read`
  。参见 [前置校验](../agent/tools.md#七前置校验)
- **Chat 页面展示**：仅显示 `🤖 Agent: 重构 UserService → 已完成 (sub-session #42)，摘要: ...`。子 Agent
  的 ToolCallCard 出现在自己的 Session 视图中，不嵌入父的 ChatPage。

## 五、子 Agent UI

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
- 子 Agent 异步执行中，父 Agent 可继续处理其他任务。子 Agent 完成后通过回调通知父 Agent

## 六、SessionsPage 关联

- SessionIndex 添加 `parentId: String?` 字段
- 子 Session 在列表中缩进 + `└─` 前缀展示
- 点击子 Session 打开只读历史面板

## 七、文件写入并发控制

写文件前校验 `VirtualFile.modificationStamp` 与 Agent 上次读取时的 stamp 一致——不一致则拒绝写入并返回错误给
Agent，Agent 自动重读后重试。

`ConcurrentHashMap<VirtualFile, ReentrantLock>` 确保同一文件同时只有一个 Agent 在写。任务正交分派（如
Agent-A 重构、Agent-B 测试、Agent-C 审查）从源头减少冲突。
