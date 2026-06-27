# 多 Agent 协作

> 关联文档：[Agent Loop](./loop.md)、[工具系统](./tools.md)

## 一、架构

父 Agent 通过 `Task` 工具启动子代理处理子任务，由 `MultiAgentManager` 统一调度。

**MultiAgentManager 职责：**

- 并发控制：`Semaphore(3)` 限制全局最大并发 Agent 数（父 + 子总计）
- 子 Agent 创建：`Task(task, parentSession)` → 新建 `AgentLoop` 实例 → 提交到线程池
- 结果收集：`CompletableFuture<ToolResult>` 回调，子完成 → 父的 toolResult 写入子任务摘要
- 文件写锁：`ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享同一份锁表）

## 二、关键约束

| 约束项        | 规则                                                                                                                |
|------------|-------------------------------------------------------------------------------------------------------------------|
| 并发控制       | `Semaphore(3)` 限制全局最大并发 Agent 数（父 + 子总计）                                                                          |
| 文件写锁       | `ConcurrentHashMap<VirtualFile, ReentrantLock>`（所有 Agent 共享）                                                      |
| 递归限制       | 最多 1 层嵌套（子不可再 spawn 孙）。Phase 5 后可评估是否放宽                                                                           |
| 结果摘要       | ≤ 2000 tokens 写入父的 toolResult。摘要 = 子 Agent 最后一轮 assistant 消息 + 所有 tool call 结果原文拼接，截断到 2000 tokens。不额外调用 LLM 生成摘要 |
| 子 Session  | 独立持久化（`session.parentId` 关联）                                                                                      |
| 子 Agent 失败 | 子 Agent crash 或超时 → toolResult 返回 `"Sub-agent failed: <error>"`，父 LLM 自行决定重试或调整策略                                 |

## 三、父子通信

```
父 Agent
  ├─ Task(taskA) → 子 Agent A
  │     └─ 返回 resultA → 父 Agent
  ├─ Task(taskB) → 子 Agent B
  │     └─ 返回 resultB → 父 Agent
  └─ 父 Agent 汇总 resultA + resultB → 继续执行
```

**通信规则：**

- **v1 规则**：子 Agent 之间**禁止直接通信**。所有协调通过父 Agent——父 Agent spawn 子 Agent →
  收集结果 → 决定是否 spawn 更多子 Agent。
- **并发建议**：默认串行（一次只 spawn 一个子 Agent，等结果回来后决定下一个）。LLM 在确定任务完全独立时才并行
  spawn（最多 3 个）。
- **结果传递**：子 Agent 完成后，结果摘要（≤ 2000 tokens）写入父的 toolResult。子 Agent 的完整执行过程作为一个独立
  Session 持久化（`session.parentId` 关联）。
- **文件修改通知**：子 Agent 修改文件后，父 Agent 从 toolResult 中获知文件变更。父 Agent 应主动 `Read`
  获取最新状态后再传递给其他子 Agent。
- **Chat 页面展示**：仅显示 `🔧 Task: 重构 UserService → 已完成 (sub-session #42)，摘要: ...`。子 Agent
  的 ToolCallCard 出现在自己的 Session 视图中，不嵌入父的 ChatPage。

## 四、子 Agent UI

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

## 五、SessionsPage 关联

- SessionIndex 添加 `parentId: String?` 字段
- 子 Session 在列表中缩进 + `└─` 前缀展示
- 点击子 Session 打开只读历史面板

## 六、文件写入并发控制

写文件前校验 `VirtualFile.modificationStamp` 与 Agent 上次读取时的 stamp 一致——不一致则拒绝写入并返回错误给
Agent，Agent 自动重读后重试。

`ConcurrentHashMap<VirtualFile, ReentrantLock>` 确保同一文件同时只有一个 Agent 在写。任务正交分派（如
Agent-A 重构、Agent-B 测试、Agent-C 审查）从源头减少冲突。
