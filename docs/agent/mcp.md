# MCP 支持

> 关联文档：[[tools]]

Model Context Protocol (MCP) 支持——通过 JSON-RPC 协议与外部 MCP Server 通信，将外部工具注册到 Agent
的工具列表中。

---

## 一、架构

```
┌──────────────┐     MCP (JSON-RPC via stdio / HTTP)
│ Code         │ ←── stdio / HTTP ───────→  MCP Servers
│ Assistant    │    JSON-RPC (手写)         ├─ mysql (stdio)
│ Plugin       │                           ├─ filesystem (stdio)
└──────────────┘                           └─ remote-api (HTTP)
```

**传输协议：** 支持 stdio（子进程）和 HTTP JSON-RPC（远程服务）。`mcp-config.json` 中 `transport` 字段指定：

- `"transport": "stdio"` 时使用 `command` + `args` 启动子进程
- `"transport": "http"` 时使用 `url` 字段向远程 MCP Server 发送 JSON-RPC POST 请求；响应可为
  `application/json`，也可为有限的 `text/event-stream` `data:` 帧。当前实现不维护长连接 SSE 推送通道。

---

## 二、配置文件

兼容 Claude Code / Codex MCP 配置格式。同时读取以下配置文件（后加载覆盖先加载）：

| 优先级 | 文件路径                                        | 说明                        |
|-----|---------------------------------------------|---------------------------|
| 1   | `<project>/.code-assistant/mcp-config.json` | 主配置文件                     |
| 2   | `<project>/.mcp.json`                       | 兼容 Claude Code 项目级 MCP 配置 |
| 3   | `~/.claude/.mcp.json`                       | 兼容 Claude Code 用户级 MCP 配置 |
| 4   | `<project>/.codex/mcp.json`                 | 兼容 Codex 项目级 MCP 配置       |
| 5   | `~/.codex/mcp.json`                         | 兼容 Codex 用户级 MCP 配置       |

### mcp-config.json 示例

JSON Schema 和字段说明见 [persistence.md §6.3](../specs/persistence.md#63-mcp-config)。

---

## 三、Server 生命周期

```
CONFIGURED → INITIALIZING (握手 5s 超时) → RUNNING
                │                    │
                ├─ 超时 → ERROR      ├─ 进程退出 → CRASHED（自动重启 1 次）
                └─ 握手失败 → ERROR  ├─ 手动停止 → STOPPED
                                     └─ JSON-RPC 断开 → DISCONNECTED

INIT_ERROR：初始化握手超时（退避重试 3 分钟后仍失败），需用户手动 [重连]
```

**状态说明：**

| 状态             | 含义                      |
|----------------|-------------------------|
| `CONFIGURED`   | 已配置，未连接                 |
| `INITIALIZING` | 正在启动子进程并进行 MCP 握手       |
| `RUNNING`      | 正常运行，工具可用               |
| `CRASHED`      | 进程异常退出                  |
| `STOPPED`      | 用户手动停止                  |
| `DISCONNECTED` | JSON-RPC 连接断开（进程可能仍在运行） |
| `ERROR`        | 握手失败                    |
| `INIT_ERROR`   | 初始化超时（退避重试耗尽）           |

---

## 四、关键行为

### 工具审批

对齐 Claude Code：MCP 工具按 **Server 粒度**审批。首次调用某 Server 的任意工具时弹窗确认整个
Server，通过后该 Server 所有工具自动放行。详见 [mcp-permissions.md](../specs/mcp-permissions.md)。

### 心跳与存活检测

stdio 传输无独立心跳，依赖子进程存活 + JSON-RPC 超时判断：

- 子进程意外退出 → `CRASHED`，等 2s 自动重启 1 次
- JSON-RPC 请求超过 30s 无响应 → 标记 `DISCONNECTED`，每 5s 自动重连（最多 10 次）
- 对齐 Claude Code：不实现独立心跳协议，用进程生命周期 + 请求超时代替

### Server 输出的非 JSON-RPC 处理

MCP Server 的 stdout 可能混杂非 JSON-RPC 行（如 npm 安装日志、stderr 重定向）：

- 非 JSON 行：跳过，记录 WARN 日志
- stderr：记录 INFO 日志，不视为协议错误
- 连续 100 行非 JSON → 判定 Server 异常，强制断开
- 对齐 Claude Code：宽容解析，不因单行垃圾数据崩溃

### 崩溃恢复

- CRASHED → 等 2s 自动重启 1 次。再次崩溃 → CRASHED（不再自动），需用户手动 [重连]

### 断连恢复

- DISCONNECTED → 每 5s 自动重连（最多 10 次）

### 初始化重试

- INITIALIZING → 握手超时 5s。如果失败且 server 进程仍在运行（如 `npm install` 中），按退避策略重试：
    - 第 1 次等 2s → 第 2 次等 5s → 第 3 次等 10s → 之后每次等 15s
    - 总计最多 3 分钟。超过 3 分钟仍失败 → ERROR

### 工具注册

- Server 工具通过 `tools/list` 获取，注册到 `ToolRegistry`
- 同名工具加前缀 `serverName/toolName`，内置工具优先级高于 MCP 工具
- 不支持 `resources/list` 和 `prompts/list`
- **MCP Server 断连时，已注册的工具不从 `ToolRegistry` 注销**。LLM 下一轮调用时看到的是"Server 断连"
  错误，而非"工具不存在"

---

## 五、MCP 页面

> UI 布局及交互详见 [MCP 页面](../ui/pages.md#六mcp-页面)。

**添加 Server：** `McpManager.connect(serverId)` 启动子进程 + MCP 握手 → `tools/list` 获取工具 → 注册到
`ToolRegistry`。

**删除 Server：** `McpManager.disconnect(serverId)` shutdown 进程 → 从 `ToolRegistry` 移除工具 → 从
`mcp-config.json` 移除。

**测试连接：** 发送 MCP `initialize` 握手请求，5s 超时。成功返回工具数量和延迟，失败返回错误信息。

---

## 六、McpManager 接口

```
McpManager
├── loadConfig(): McpConfig            // 从 McpConfigStore 读取
├── connect(serverId: String)          // 启动子进程 + MCP 握手
├── disconnect(serverId: String)       // shutdown → destroy
├── getTools(serverId: String): List<AgentTool>
├── testConnection(serverId: String): ConnectionResult  // initialize 握手 + 5s 超时
├── dispose()                          // 遍历所有 server → shutdown → destroyForcibly
│
├── onServerStateChanged: ((serverId, McpServerState) -> Unit)?
│
└── ConnectionResult:
    ├── success: Boolean
    ├── toolCount: Int?
    ├── errorMessage: String?
    └── latencyMs: Long?

McpServerState:
    CONFIGURED | INITIALIZING | RUNNING | CRASHED | STOPPED | DISCONNECTED | ERROR | INIT_ERROR
```

---

## 七、McpConfigStore 接口

```
McpConfigStore
├── load(): McpConfig
├── save(config: McpConfig)
└── configPath: Path                     // <project>/.code-assistant/mcp-config.json

McpConfig:
└── servers: List<McpServerConfig>

McpServerConfig: 见 [persistence.md §6.3](../specs/persistence.md#63-mcp-config)
```

---

## 八、安全边界

### 工具 Schema 校验

`tools/list` 返回的每个工具在注册到 `ToolRegistry` 前校验：

- `name` 非空，仅允许 `[a-zA-Z0-9_-]+`，长度 ≤ 64
- `description` 非空字符串，长度 ≤ 1024
- `inputSchema` 为有效 JSON Schema（`type: "object"`、`properties` 非空 Map）

校验失败的工具**跳过不注册**，通过 `McpServerStateChanged` 事件通知 MCP 页面显示 ⚠️ 标记 + 失败原因。

### 工具执行错误传播

MCP 工具执行失败（JSON-RPC error / 超时 / Server 异常断开）时，统一返回：

```
MCP 工具 {serverName}/{toolName} 执行失败: {errorMessage}
```

作为 `ToolResult(success=false)` 写回 `session.messages`，LLM 看到失败信息后可自行决定重试或调整策略。单轮内同一
MCP 工具连续失败 3 次 → 自动跳过，返回 "已连续失败 3 次，请检查 MCP Server 状态"。

### 结果截断

MCP 工具返回值统一走 `ToolExecutor` 的截断通道：保留头部 200 行，超出截断并标注
`... (共 N 行，已截断到 200 行)`。截断结果写回 `session.messages`，参与 compact。

### compact 参与

MCP 工具结果作为 `session.messages` 的一部分参与 compact 压缩，与内置工具结果同等对待。compact 后不保留
MCP 工具历史结果原文——LLM 仅看到摘要中保留的关键信息。
