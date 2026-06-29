# 事件总线契约

> **原始来源：** `tech-spec.md`（已拆分，内容归入 `specs/` 各文件）

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

见 [AgentSession 状态机](../agent/loop.md#二agentsession-状态机)。

## McpServerState 枚举

见 [MCP Server 生命周期](../agent/mcp.md#一server-生命周期)。

## PlanStatus 枚举

见 [Plan 状态定义](../agent/plan.md#四plan-状态)。
