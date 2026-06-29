# 线程模型

> **原始来源：** `tech-spec.md`（已拆分，内容归入 `specs/` 各文件）

本文档描述 Code Assistant Agent Mode 的线程模型总览，包括各线程职责、跨线程通信规则。

---

## 线程架构图

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
│  └─ ChatViewModel 静默合并 flush（token 停顿 ≥30ms 触发）   │
├─────────────────────────────────────────────────────────┤
│ ProcessHandler listener                                  │
│  └─ Bash onTextAvailable → batch buffer → Timer(100ms)│
│                                                    → EDT │
└─────────────────────────────────────────────────────────┘
```

## 跨线程通信规则

- Background → UI：`ApplicationManager.invokeLater { ... }` 或 `SwingUtilities.invokeLater { ... }`
- UI → Background：`ApplicationManager.executeOnPooledThread { ... }`
- UI 等待 Background：`invokeAndWait` 用于 Write/Edit
- Background 等待 UI：`CountDownLatch` 用于审批等待（无超时——Agent Loop 在后台线程，不阻塞
  EDT；审批以对话内 ToolCallCard 呈现，用户必响应；加超时反而引入竞态风险）
- 流式静默合并 flush：首 token 即时渲染，后续 token 写入 buffer + 重置 timer。Timer 为单次触发（
  `isRepeats=false`），仅在 token 停顿 ≥30ms 后才在 EDT 上批量 flush。连续到达的 token 会不断推迟
  timer，避免高频重绘

## AgentLoop 线程约束

- `run()` 在 `ApplicationManager.executeOnPooledThread()` 上执行（非 EDT）
- 流式 token 通过 `Flow<AgentEvent>.collect()` 接收，在 collector 处 `invokeLater` 切到 EDT
- `cancel()` 可在任意线程调用
- Write 内部通过 `invokeAndWait` 切到 EDT
- 单个 AgentSession 同时只能有一个 `run()` 在执行
- AgentEvent 的发射在 background thread，UI 订阅者负责线程切换

## ToolExecutor 线程分配

详见 [工具系统 §三](../agent/tools.md#三工具执行分发)。
