# 事件总线契约

> **原始来源：** `docs/tech-spec.md`（已拆分，内容归入 `docs/specs/` 各文件）

本文档定义 `MessageBus` 的所有事件 Topic、消息类型、字段、发布者和订阅者。事件通过 IntelliJ
`project.messageBus.syncPublisher` 同步发布，所有订阅者在 EDT 上接收。

---

## 事件表

| Topic                   | 消息类型              | 字段                                                                     | 发布者            | 订阅者                    |
|-------------------------|-------------------|------------------------------------------------------------------------|----------------|------------------------|
| `SessionChanged`        | `SessionEvent`    | `sessionId: String, type: CREATED\|UPDATED\|DELETED`                   | SessionManager | SessionsPage, ChatPage |
| `AgentStateChanged`     | `AgentStateEvent` | `sessionId: String, oldState: AgentState, newState: AgentState`        | AgentSession   | ChatPage, TabBar       |
| `TokenUsageUpdated`     | `TokenUsageEvent` | `sessionId: String, delta: TokenDelta`                                 | AgentSession   | TokenUsagePage         |
| `McpServerStateChanged` | `McpServerEvent`  | `serverId: String, oldState: McpServerState, newState: McpServerState` | McpManager     | McpPage                |
| `ApiKeyValidated`       | `ApiKeyEvent`     | `state: VALID\|INVALID\|UNKNOWN`                                       | WelcomePage    | ChatPage, SettingsPage |
| `PlanStateChanged`      | `PlanStateEvent`  | `sessionId: String, planStatus: PlanStatus, currentPlan: Int`          | PlanExecutor   | ChatPage, SessionsPage |
| `PageSwitched`          | `PageEvent`       | `from: PageId, to: PageId`                                             | ChatToolWindow | 所有 Page                |

## AgentState 枚举

```
AgentState = IDLE | PROCESSING | PAUSED | AWAITING_APPROVAL | EXECUTING | CANCELLED | ERROR
```

## McpServerState 枚举

```
McpServerState = CONFIGURED | INITIALIZING | RUNNING | CRASHED | STOPPED | DISCONNECTED | ERROR | INIT_ERROR
```

- `INIT_ERROR`：初始化握手超时（退避重试 3 分钟后仍失败），需用户手动 `[重连]`

## PlanStatus 枚举

```
PlanStatus = PAUSED | EXECUTING | COMPLETED | CANCELLED
```
