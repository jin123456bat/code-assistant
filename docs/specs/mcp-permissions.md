# MCP 权限模型

> 本文档描述 MCP（Model Context Protocol）Server 的权限边界和安全模型。

## 一、当前状态

MCP 权限模型已实现基础的 **Server 粒度审批**：首次调用某个 MCP Server 的工具时，UI 在
ToolCallCard 中提供 `[允许一次]` / `[允许此 Server]` / `[拒绝]`。其中 `[允许此 Server]` 会将
Server ID 写入 Session JSON 的 `approvedMcpServers`；资源访问控制、网络限制和沙箱隔离仍未实现。

## 二、Server 生命周期

```
CONFIGURED → INITIALIZING → RUNNING → STOPPED
                 ↓              ↓
               ERROR ←──────── CRASHED
```

> 状态枚举详见 [MCP Server 生命周期](../agent/mcp.md#三server-生命周期)。配置数据结构详见
> [persistence.md §6.3 MCP Config](persistence.md#63-mcp-config)。

### 配置文件位置

```
{project}/.code-assistant/mcp-config.json
```

### 配置格式

MCP 配置文件结构见 [persistence.md §6.3 MCP Config](persistence.md#63-mcp-config)。格式为
`{ "servers": [...] }`，每个 server 条目包含 `id`、`transport`、`command`、`args`、`env`、`url`、`enabled`
字段，字段说明见 persistence.md §6.3 的 McpServerConfig 字段说明表。

## 四、当前权限边界

| 边界           | 当前状态               | 说明                                                                |
|--------------|--------------------|-------------------------------------------------------------------|
| Server 启用/禁用 | `enabled: boolean` | 唯一权限开关，`false` 时不启动                                               |
| 工具审批         | ✅ Server 粒度审批      | 首个 MCP 工具调用时弹窗"允许此 Server 的所有工具"，通过后该 Server 所有工具自动放行，与内置工具审批流程独立 |
| 资源访问控制       | ❌ 未实现              | MCP Server 可访问其进程能访问的一切资源                                         |
| 网络限制         | ❌ 未实现              | HTTP 模式的 MCP Server 无网络限制                                         |
| 环境变量隔离       | ❌ 未实现              | MCP Server 继承 IDE 进程环境变量                                          |

## 五、工具审批策略

对齐 Claude Code：MCP 工具按 **Server 粒度**审批，非逐个工具审批。

```
首次调用某 MCP Server 的任意工具
  → ToolCallCard 弹窗："允许此 Server 的所有工具？"（serverName + 工具列表预览）
  → [允许一次] → 仅本次 tool_use 放行，并记录该 server 已完成首次确认；不写入 approvedMcpServers
  → [允许此 Server] → 该 Server 所有工具加入 approvedMcpServers，持久化到 Session JSON
  → [拒绝] → 发送拒绝 tool_result，LLM 可选择不使用该 Server 或更换方式
  → /clear 或 /new → 不清除 approvedMcpServers（与 approvedTools 同等对待）
```

### 与内置工具审批的关系

| 维度       | 内置工具                              | MCP 工具                                             |
|----------|-----------------------------------|----------------------------------------------------|
| 审批粒度     | 按工具名（Read/Write/Edit/Bash/Agent）  | 按 Server ID                                        |
| 首次弹窗     | "允许此会话使用 Read？"                   | "允许此会话使用 Server mysql 的所有工具？"                      |
| 持久化      | `approvedTools: ["Read", "Edit"]` | `approvedMcpServers: ["mysql", "filesystem"]`      |
| 允许一次     | 仅本次工具调用放行，不加入 `approvedTools`     | 仅本次 tool_use 放行；不加入 `approvedMcpServers`，但不再重复首用确认 |
| 危险命令二次确认 | ✅（Bash dangerous=true）            | ❌（MCP 工具不参与危险命令检测）                                 |

## 六、工具白名单与资源访问（待实现）

用户可配置每个 MCP Server 允许的工具列表和资源访问范围：

```json
{
  "id": "mysql-server",
  "command": "npx",
  "args": ["-y", "@anthropic/mcp-server-mysql"],
  "allowedTools": ["query", "list_tables"],
  "deniedTools": ["execute", "drop_table"]
}
```

### 6.1 资源访问声明

MCP Server 应声明其需要的资源访问权限：

```json
{
  "id": "filesystem-server",
  "command": "npx",
  "args": ["-y", "@anthropic/mcp-server-filesystem"],
  "allowedPaths": ["/Users/john/projects/"],
  "deniedPaths": ["/etc/", "/System/"]
}
```

## 七、崩溃恢复

- 崩溃时状态变为 `CRASHED`
- 自动重启 **1 次**（对齐 Claude Code）
- 重启仍失败 → 需用户手动处理

> 崩溃恢复实现详见 [MCP Server 生命周期](../agent/mcp.md#三server-生命周期)。

## 八、进程清理

- 优雅关闭：`destroy()` + 等待 2 秒

> 进程清理实现详见 [McpManager 接口](../agent/mcp.md#六mcpmanager-接口)。
- 强制关闭：`destroyForcibly()`
- 项目关闭时 `dispose()` 清理所有进程

## 九、当前不支持的功能

| 功能               | 状态      |
|------------------|---------|
| `resources/list` | ❌ 不支持   |
| `prompts/list`   | ❌ 不支持   |
| MCP Server 热加载   | ❌ 需手动重启 |
| MCP 工具权限隔离       | ❌ 待实现   |
| MCP Server 沙箱    | ❌ 待评估   |
