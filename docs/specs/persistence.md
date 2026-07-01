# 持久化 JSON Schema

> **原始来源：** `tech-spec.md`（已拆分，内容归入 `specs/` 各文件）

本文档定义 Code Assistant Agent Mode 的持久化 JSON Schema，包括会话（Session）、会话索引（Session Index）和
MCP 配置的数据结构。另附会话存储的写入流程与读取容错描述。

---

## 存储概述

- 目录：`<project>/.code-assistant/sessions/<uuid>.json`
- 写入流程：Jackson 序列化 → `.tmp` 临时文件 → `Files.move(ATOMIC_MOVE)` 原子替换 →
  `FileChannel.tryLock()` OS 级排他锁（跨进程写锁）
- 读取容错：`JsonParseException` → 跳过损坏文件；`FileNotFoundException` → 从 index.json 移除条目
- SessionStore 在 Background Thread 上执行保存
- **Session JSON 不限制体积，不自动拆分。** 超长对话会持续追加到单个 JSON 文件，无上限检查
- **Session 清理策略：不做自动清理。** 自动清理可能误删有价值的日志。Sessions 页面提供 `[全选]` +
  `[🗑 删除选中]` 按钮供用户手动批量删除

### 数据迁移

Session JSON Schema 版本升级时采用**向后兼容 + 懒迁移**策略：

- Session JSON 不携带版本号字段，通过字段存在性判断数据结构版本
- 新增可选字段：读取旧 Session 时缺失字段使用默认值填充（如 `deleted: false`），*
  *不重写旧文件**
- 新增必填字段：读取旧 Session 时缺失则用合理默认值，首次保存时自动补齐到新格式
- 移除字段：旧字段保留不删除，新写入不再产生；读取时忽略
- 字段类型变更：读取时做兼容转换（如 `Int → Long`），首次保存时写为新类型
- **不提供批量迁移工具**：Session 数量有限（通常 < 100），懒迁移在正常读写中自然完成

---

## 6.1 Session JSON

```json
{
  "id": "uuid",
  "parentId": null,
  "title": "重构 UserService",
  "createdAt": "2026-06-24T14:30:00Z",
  "updatedAt": "2026-06-24T14:45:00Z",
  "messages": [
    {
      "id": "msg-1",
      "role": "USER",
      "content": "帮我重构 UserService",
      "contentType": "TEXT",
      "timestamp": "2026-06-24T14:30:00Z",
      "deleted": false
    },
    {
      "id": "msg-2",
      "role": "ASSISTANT",
      "content": "好的，让我先读取文件...",
      "contentType": "TEXT",
      "timestamp": "2026-06-24T14:30:05Z",
      "deleted": false,
      "toolCalls": [
        {
          "id": "tooluse-1",
          "name": "Read",
          "parameters": {
            "filePath": "UserService.kt",
            "startLine": null,
            "endLine": null
          },
          "result": "...",
          "state": "DONE",
          "durationMs": 15
        }
      ],
      "tokenUsage": {
        "inputTokens": 1200,
        "outputTokens": 400,
        "timestamp": "2026-06-24T14:30:05Z"
      }
    }
  ],
  "plan": {
    "id": "plan-1",
    "status": "EXECUTING",
    "summary": "重构 UserService.findById 为 suspend 函数",
    "currentPlanIndex": 1,
    "plans": [
      {
        "id": "plan-1",
        "description": "读取 UserService.kt",
        "tool": "Read",
        "files": [
          "UserService.kt:40-60"
        ],
        "status": "COMPLETED",
        "result": "成功读取 156 行",
        "retryCount": 0
      },
      {
        "id": "plan-2",
        "description": "修改方法签名为 suspend fun",
        "tool": "Edit",
        "files": [
          "UserService.kt"
        ],
        "status": "PAUSED",
        "retryCount": 0
      }
    ],
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:35:00Z"
  },
  "totalTokens": {
    "inputTokens": 5200,
    "outputTokens": 3000
  },
  "compactSummary": "用户任务为重构 UserService.findById 为 suspend 函数。已完成：读取 UserService.kt 和 UserController.kt。当前正在修改方法签名...",
  "compactCount": 2,
  "approvedTools": [
    "Read",
    "Write",
    "Edit",
    "Bash"
  ],
  "approvedMcpServers": [
    "github",
    "filesystem"
  ]
}
```

### Message 字段说明

| 字段            | 类型                       | 说明                                                                                 |
|---------------|--------------------------|------------------------------------------------------------------------------------|
| `id`          | String                   | 消息唯一标识                                                                             |
| `role`        | String                   | 内部渲染角色。见 [AgentSession.Message](../agent/loop.md#七agentsession-接口定义)               |
| `content`     | String                   | 消息正文                                                                               |
| `contentType` | String                   | 内容类型：`TEXT` / `TOOL_USE` / `TOOL_RESULT`。`TOOL_USE` 和 `TOOL_RESULT` 是独立 Message 记录 |
| `timestamp`   | String (ISO 8601)        | 时间戳                                                                                |
| `toolCalls`   | Array\<ToolCallRecord\>? | ASSISTANT 消息可能含工具调用记录                                                              |
| `tokenUsage`  | TokenDelta?              | 单条 ASSISTANT 消息的 token 增量                                                          |
| `deleted`     | Boolean                  | 回退标记，`true` 时持久化保留但 UI 不渲染。撤销回退时恢复为 `false`                                        |

### Plan 字段说明

| 字段                 | 类型                | 说明                                     |
|--------------------|-------------------|----------------------------------------|
| `id`               | String            | 计划唯一标识                                 |
| `status`           | String            | 见 [Plan 状态](../agent/plan.md#四plan-状态) |
| `summary`          | String            | 一句话描述                                  |
| `currentPlanIndex` | Int               | 当前执行到第几项（0-based）                      |
| `plans`            | Array\<Plan\>     | 计划项列表                                  |
| `createdAt`        | String (ISO 8601) | 创建时间                                   |
| `updatedAt`        | String (ISO 8601) | 更新时间                                   |

### Plan 子项字段说明

| 字段            | 类型              | 说明                                     |
|---------------|-----------------|----------------------------------------|
| `id`          | String          | 计划项唯一标识                                |
| `description` | String          | 计划项描述                                  |
| `tool`        | String          | 建议工具名，LLM 应优先使用但不强制                    |
| `files`       | Array\<String\> | 涉及文件（含行号如 `"UserService.kt:40-60"`）    |
| `status`      | String          | 见 [Plan 状态](../agent/plan.md#四plan-状态) |
| `result`      | String?         | 执行结果                                   |
| `retryCount`  | Int             | 重试次数                                   |

---

## 6.2 Session Index

```json
[
  {
    "id": "uuid",
    "title": "重构 UserService",
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:45:00Z",
    "messageCount": 12,
    "totalTokens": 8200,
    "toolCallCount": 5,
    "hasActivePlan": true,
    "parentId": null,
    "parentTotalTokens": null
  }
]
```

### SessionIndex 字段说明

| 字段                  | 类型      | 说明                                                            |
|---------------------|---------|---------------------------------------------------------------|
| `id`                | String  | 会话唯一标识（UUID）                                                  |
| `title`             | String  | 会话标题，初始值为"新会话"，首条消息后异步 LLM 生成（≤ 20 字）                         |
| `createdAt`         | String  | 创建时间（ISO 8601）                                                |
| `updatedAt`         | String  | 更新时间（ISO 8601）                                                |
| `messageCount`      | Int     | 消息数量                                                          |
| `totalTokens`       | Long    | `inputTokens + outputTokens` 累加值，从 API usage 返回值获取            |
| `toolCallCount`     | Int     | 工具调用次数                                                        |
| `hasActivePlan`     | Boolean | 是否有活跃计划                                                       |
| `parentId`          | String? | 子会话关联父会话 ID，`null` 表示顶级会话                                     |
| `parentTotalTokens` | Long?   | 聚合所有子 session 的 totalTokens（父 session 专用），`null` 表示无子 session |

---

## 6.3 MCP Config

存储路径：`<project>/.code-assistant/mcp-config.json`

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
      "transport": "stdio",
      "enabled": true
    },
    {
      "id": "remote-api",
      "transport": "http",
      "url": "https://mcp.example.com/sse",
      "env": {
        "API_TOKEN": "sk-xxx"
      },
      "enabled": true
    }
  ]
}
```

### McpServerConfig 字段说明

| 字段          | 类型                    | 说明                                                 |
|-------------|-----------------------|----------------------------------------------------|
| `id`        | String                | Server 唯一标识                                        |
| `transport` | String                | 传输协议，`"stdio"`（默认）或 `"http"`。`"http"` 时需设置 `url`   |
| `command`   | String?               | 启动命令（如 `npx`、`python` 等），仅 `transport="stdio"` 时有效 |
| `url`       | String?               | HTTP JSON-RPC 端点 URL，仅 `transport="http"` 时有效      |
| `args`      | Array\<String\>       | 命令行参数，仅 `transport="stdio"` 时有效                    |
| `env`       | Map\<String, String\> | 环境变量，敏感值建议通过环境变量注入                                 |
| `enabled`   | Boolean               | 是否启用                                               |
