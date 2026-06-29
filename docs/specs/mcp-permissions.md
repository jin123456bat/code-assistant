# MCP 权限模型

> 本文档描述 MCP（Model Context Protocol）Server 的权限边界和安全模型。

## 一、当前状态

MCP 权限模型**尚未实现**。当前的 `McpManager` 仅负责 Server 生命周期管理（启动/停止/崩溃恢复），不涉及工具级别的权限控制。

## 二、Server 生命周期

```
CONFIGURED → INITIALIZING → RUNNING → STOPPED
                 ↓              ↓
               ERROR ←──────── CRASHED
```

### 状态枚举

```kotlin
enum class State {
    CONFIGURED,     // 已配置，未连接
    INITIALIZING,   // 正在连接/握手
    RUNNING,        // 正常运行
    CRASHED,        // 崩溃（自动重启 1 次）
    STOPPED,        // 用户手动停止
    DISCONNECTED,   // 连接断开
    ERROR           // 错误状态
}
```

## 三、Server 配置

```kotlin
data class McpServerConfig(
    val id: String,                    // 唯一标识
    val command: String,               // 启动命令
    val args: List<String> = emptyList(),  // 命令参数
    val env: Map<String, String> = emptyMap(),  // 环境变量
    val transport: String = "stdio",   // 传输协议：stdio / http+sse
    val url: String? = null,           // HTTP+SSE 模式的 URL
    val enabled: Boolean = true        // 是否启用
)
```

### 配置文件位置

```
{project}/.code-assistant/mcp-config.json
```

### 配置格式

```json
[
  {
    "id": "mysql-server",
    "command": "npx",
    "args": ["-y", "@anthropic/mcp-server-mysql"],
    "env": {"DB_HOST": "localhost", "DB_PORT": "3306"},
    "transport": "stdio",
    "enabled": true
  }
]
```

## 四、当前权限边界

| 边界           | 当前状态               | 说明                            |
|--------------|--------------------|-------------------------------|
| Server 启用/禁用 | `enabled: boolean` | 唯一权限开关，`false` 时不启动           |
| 工具审批         | ❌ 未实现              | MCP 工具走与内置工具相同的审批流程           |
| 资源访问控制       | ❌ 未实现              | MCP Server 可访问其进程能访问的一切资源     |
| 网络限制         | ❌ 未实现              | HTTP+SSE 模式的 MCP Server 无网络限制 |
| 环境变量隔离       | ❌ 未实现              | MCP Server 继承 IDE 进程环境变量      |

## 五、待实现的权限模型

### 5.1 工具级审批

MCP 工具应与内置工具走相同的审批策略。建议：

```
McpManager.listTools(serverId)
  → ToolRegistry.register("mcp/{serverId}/{toolName}", toolClass)
  → ToolApprovalPolicy 统一判断
```

### 5.2 工具白名单

用户可配置每个 MCP Server 允许的工具列表：

```json
{
  "id": "mysql-server",
  "command": "npx",
  "args": ["-y", "@anthropic/mcp-server-mysql"],
  "allowedTools": ["query", "list_tables"],
  "deniedTools": ["execute", "drop_table"]
}
```

### 5.3 资源访问声明

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

## 六、崩溃恢复

```kotlin
fun connect(id: String): Boolean {
    val server = servers[id] ?: return false
    try {
        server.state = State.INITIALIZING
        val pb = ProcessBuilder(server.config.command, *server.config.args.toTypedArray())
        pb.directory(File(project.basePath ?: "."))
        server.config.env.forEach { (k, v) -> pb.environment()[k] = v }
        server.process = pb.start()
        server.state = State.RUNNING
        return true
    } catch (e: Exception) {
        server.state = State.ERROR
        return false
    }
}
```

- 崩溃时状态变为 `CRASHED`
- 自动重启 **1 次**（对齐 Claude Code）
- 重启仍失败 → `ERROR`，需用户手动处理

## 七、进程清理

```kotlin
fun disconnect(id: String) {
    val server = servers[id] ?: return
    server.process?.let {
        it.destroy()
        try { it.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        if (it.isAlive) it.destroyForcibly()
    }
    server.state = State.STOPPED
    server.process = null
}

fun dispose() {
    servers.keys.forEach { disconnect(it) }  // 项目关闭时清理所有
}
```

- 优雅关闭：`destroy()` + 等待 2 秒
- 强制关闭：`destroyForcibly()`
- 项目关闭时 `dispose()` 清理所有进程

## 八、当前不支持的功能

| 功能               | 状态       |
|------------------|----------|
| `resources/list` | ❌ v1 不支持 |
| `prompts/list`   | ❌ v1 不支持 |
| MCP Server 热加载   | ❌ 需手动重启  |
| MCP 工具权限隔离       | ❌ 待实现    |
| MCP Server 沙箱    | ❌ 待评估    |
