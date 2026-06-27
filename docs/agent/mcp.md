# MCP 支持

> 关联文档：[[tools]]

Model Context Protocol (MCP) 支持——通过 JSON-RPC 协议与外部 MCP Server 通信，将外部工具注册到 Agent
的工具列表中。

---

## 一、架构

```
┌──────────────┐     MCP (JSON-RPC via stdio)
│ Code         │ ←── stdio / HTTP+SSE ──→  MCP Servers
│ Assistant    │    JSON-RPC (手写)         ├─ mysql (stdio)
│ Plugin       │                           ├─ filesystem (stdio)
└──────────────┘                           └─ remote-api (HTTP+SSE)
```

**传输协议：** 支持 stdio（子进程）和 HTTP+SSE（远程服务）。`mcp-config.json` 中 `transport` 字段指定：

- `"transport": "stdio"` 时使用 `command` + `args` 启动子进程
- `"transport": "http"` 时使用 `url` 字段连接远程 MCP Server

---

## 二、配置文件

兼容 Claude Code / Codex MCP 配置格式。同时读取以下配置文件（后加载覆盖先加载）：

| 优先级 | 文件路径                                        | 说明                       |
|-----|---------------------------------------------|--------------------------|
| 1   | `<project>/.code-assistant/mcp-config.json` | 主配置文件                    |
| 2   | `<project>/.mcp.json`                       | 兼容 Claude Code MCP 配置格式  |
| 3   | `~/.claude/.mcp.json`                       | 兼容 Claude Code 全局 MCP 配置 |

### mcp-config.json 示例

```json
{
  "servers": [
    {
      "id": "mysql",
      "command": "npx",
      "args": [
        "-y",
        "@anthropic/mcp-server-mysql",
        "--db",
        "mydb"
      ],
      "env": {
        "DB_HOST": "localhost"
      },
      "enabled": true
    }
  ]
}
```

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
- v1 不支持 `resources/list` 和 `prompts/list`

---

## 五、MCP 页面

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌*] [🎯] [⚙]          │
├──────────────────────────────────────────────────┤
│  MCP Servers                        [➕ 添加]    │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │ 🟢 mysql                              [断开] ││ ← 状态灯
│  │    command: npx -y @anthropic/mcp-server-...  ││
│  │    tools: 5 (query, list_tables, describe,   ││
│  │              insert, update)                  ││
│  │    [▶ 测试连接]  [✏ 编辑]  [🗑 删除]         ││
│  └──────────────────────────────────────────────┘│
│  ┌──────────────────────────────────────────────┐│
│  │ 🟡 init... filesystem                  [✕]   ││ ← 初始化中
│  │    command: npx -y @anthropic/mcp-server-...  ││
│  │    正在安装依赖 (npm install)...              ││
│  │    最多等待 3 分钟                            ││
│  └──────────────────────────────────────────────┘│
│  ┌──────────────────────────────────────────────┐│
│  │ 🔴 custom-server                      [重连] ││ ← 崩溃
│  │    command: python my_server.py              ││
│  │    错误: Process exited with code 1          ││
│  │    [▶ 测试连接]  [📋 查看日志]  [✏ 编辑]     ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  配置文件: .code-assistant/mcp-config.json        │
│  [📂 打开文件]                                    │
└──────────────────────────────────────────────────┘
```

**添加 Server 流程：** 点击"➕ 添加"→ 内联表单填写名称/命令/参数/环境变量 → "保存并连接" →
`McpManager.connect()` 启动子进程 + MCP 握手 → `tools/list` 获取工具 → 注册到 `ToolRegistry` → 卡片标记
RUNNING。

**删除 Server 流程：** 点击"🗑 删除"→ 确认 Dialog → `McpManager.disconnect(serverId)` shutdown 进程 →
从 `ToolRegistry` 移除工具 → 从 `mcp-config.json` 移除 → 卡片消失。

**测试连接：** 发送 MCP `initialize` 握手请求，5s 超时。成功 → 显示绿色 toast "连接成功，发现 N 个工具"
。失败 → 显示红色 toast + 错误信息。

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

McpServerConfig:
├── id: String
├── command: String
├── args: List<String>
├── env: Map<String, String>            // 敏感值建议环境变量注入
└── enabled: Boolean
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
