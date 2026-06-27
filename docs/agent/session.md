# 会话管理

> 关联文档：[[../specs/persistence]]

会话持久化将 Agent 对话历史以 JSON 文件格式保存，支持跨 IDE 重启恢复、Token 统计聚合和对话回退。

---

## 一、存储

### 文件路径

```
<project>/.code-assistant/sessions/<uuid>.json
```

### 写入流程

```
Jackson 序列化 → 写入 .tmp 临时文件 → Files.move(ATOMIC_MOVE) 原子重命名 → FileChannel.tryLock() OS 级排他锁
```

- **原子写入**：`ATOMIC_MOVE` 确保写入过程中崩溃不会导致数据损坏（要么全是旧文件，要么全是新文件）
- **跨进程锁**：`FileChannel.tryLock()` 获取 OS 级排他锁（跨 JVM 有效），获取失败等 100ms 重试最多 3
  次，应对多 IDE 窗口场景

### 读取容错

- `JsonParseException` → 标记文件损坏，跳过该会话
- `FileNotFoundException` → 从 index 自动移除过期条目

### 恢复规则

IDE 重启加载 Session 时，进行以下状态修复：

| 加载时状态                                            | 修复动作       |
|--------------------------------------------------|------------|
| Agent 状态为 PROCESSING、AWAITING_APPROVAL、EXECUTING | 重置为 IDLE   |
| Plan 状态为 EXECUTING                               | 重置为 PAUSED |

---

## 二、Session Index

`<project>/.code-assistant/sessions/index.json` 维护所有会话的元数据索引：

```json
[
  {
    "id": "uuid",
    "title": "重构 UserService",
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:45:00Z",
    "messageCount": 12,
    "totalTokens": 8200,
    "parentTotalTokens": 15200,
    "toolCallCount": 5,
    "hasActivePlan": true,
    "parentId": null
  }
]
```

| 字段                  | 类型              | 说明                                                          |
|---------------------|-----------------|-------------------------------------------------------------|
| `id`                | `String (UUID)` | 会话唯一标识                                                      |
| `title`             | `String`        | 会话标题，由 LLM 异步生成                                             |
| `createdAt`         | `Instant`       | 创建时间                                                        |
| `updatedAt`         | `Instant`       | 最后更新时间                                                      |
| `messageCount`      | `Int`           | 消息总数（含 tool call/results）                                   |
| `totalTokens`       | `Long`          | 累计 token 消耗（inputTokens + outputTokens），取 API usage 返回值累加   |
| `parentTotalTokens` | `Long?`         | 聚合所有子 session 的 totalTokens（父 session 专用），null 表示无子 session |
| `toolCallCount`     | `Int`           | 工具调用次数                                                      |
| `hasActivePlan`     | `Boolean`       | 是否有活跃计划                                                     |
| `parentId`          | `String?`       | 子会话关联父会话 ID，null 表示顶级会话                                     |

---

## 三、会话标题生成

新会话的第一条用户消息发送后，异步调用 LLM 生成简短标题。

- **调用方式**：独立 API 调用，不带 tools，`max_tokens=64`
- **不阻塞主流程**：标题生成在后台进行
- **长度限制**：≤ 20 字

**Prompt 模板：**

```
根据以下消息生成一个简短的会话标题（≤ 20 字）：
"{用户第一条消息内容}"
仅返回标题文本，不要加引号。
```

生成后持久化到 `SessionIndex.title` 和 Session JSON 的 `title` 字段，通过 `SessionChanged` 事件通知
Sessions 页面更新。

---

## 四、Token Usage 页面

从所有 session 文件的 `totalTokens` 聚合，提供三种视图：

- **按会话**：每个会话的 token 消耗明细
- **按日**：按 `updatedAt` 日期聚合
- **按月**：按月份聚合

### Sparkline 趋势图

30 天按日折线图，Custom JComponent `paintComponent` 手绘：

- X 轴 = 日期（1 日~30 日）
- Y 轴 = 当日 totalTokens（input+output）
- 仅显示趋势线，省略坐标轴标签
- 鼠标 hover 显示具体数值
- 数据源：Session JSON 的 `totalTokens.inputTokens + totalTokens.outputTokens` 按 `updatedAt` 日期聚合

**页面布局：**

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊*] [🔌] [🎯] [⚙]        │
├──────────────────────────────────────────────────┤
│  时间范围: [本月 ▾]    总消耗: 128.5K  ·  ¥0.87  │
├──────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────┐│
│  │          📈 消耗趋势（30 天，按日）            ││
│  │   ▁ ▂ ▃ ▅ ▃ ▄ ▆ █ ▇ ▅ ▄ ▃ ▂ ▁              ││ ← sparkline
│  │  1日                          30日           ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  按会话：                                       │
│  ┌──────────────────────────────────┬─────┬─────┐│
│  │ 📝 重构 UserService        ⏸    │ 45K │¥0.32││ ← 点击跳转到该会话
│  │ 💬 Kotlin 协程讨论              │  3K │¥0.01││
│  └──────────────────────────────────┴─────┴─────┘│
└──────────────────────────────────────────────────┘
```

---

## 五、SessionStore 接口

```
SessionStore
├── save(session: AgentSession): String    // 返回文件路径，原子写入（.tmp → ATOMIC_MOVE）
├── load(sessionId: String): AgentSession? // 读取 + 解析 + auto-repair
├── delete(sessionId: String)
├── deleteAll(sessionIds: List<String>)    // 批量删除（全选 + 删除选中）
├── listAll(): List<SessionIndex>          // 读 index.json
├── acquireLock(sessionId): FileLock?      // 跨进程写锁（FileChannel.tryLock）
└── releaseLock(sessionId)
```

### Session JSON 结构

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
      "timestamp": "2026-06-24T14:30:00Z"
    },
    {
      "id": "msg-2",
      "role": "ASSISTANT",
      "content": "好的，让我先读取文件...",
      "timestamp": "2026-06-24T14:30:05Z",
      "toolCalls": [
        {
          "id": "tooluse-1",
          "name": "Read",
          "parameters": { "filePath": "UserService.kt" },
          "result": "...",
          "state": "DONE",
          "durationMs": 15
        }
      ],
      "tokenUsage": { "inputTokens": 1200, "outputTokens": 400 }
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
        "files": ["UserService.kt:40-60"],
        "status": "COMPLETED",
        "result": "成功读取 156 行",
        "retryCount": 0
      }
    ],
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:35:00Z"
  },
  "totalTokens": { "inputTokens": 5200, "outputTokens": 3000 },
  "compactSummary": "用户任务为重构 UserService.findById 为 suspend 函数...",
  "compactCount": 2
}
```

---

## 六、SessionManager 接口

```
SessionManager
├── createSession(title: String): AgentSession  // title 初始为临时值（如"新会话"），第一条消息后异步生成
├── getSession(id: String): AgentSession?
├── getAllSessions(): List<SessionIndex>
├── deleteSession(id: String)
├── deleteSessions(ids: List<String>)          // 批量删除（对齐 UI [全选] + [删除选中]）
├── setCurrentSession(session: AgentSession)  // Chat 页面绑定
├── currentSession: AgentSession?
├── searchSessions(query: String): List<SessionIndex>  // title.contains()
├── getTotalTokenUsage(range: DAY | MONTH | ALL, includeChildren: Boolean = false): TokenAggregation
├── aggregateChildTokens(sessionId: String)    // 递归聚合所有子 session 的 totalTokens，写入 parentTotalTokens
└── generateTitle(sessionId: String)           // 异步 LLM 调用生成标题（≤20字, max_tokens=64）

SessionIndex:
├── id: String
├── title: String
├── createdAt: Instant
├── updatedAt: Instant
├── messageCount: Int
├── totalTokens: Long                         // inputTokens + outputTokens 累加值，从 API usage 返回值获取
├── parentTotalTokens: Long?                   // 聚合所有子 session 的 totalTokens（父 session 专用），null 表示无子 session
├── toolCallCount: Int
├── parentId: String?                          // 子会话关联父会话 ID
└── hasActivePlan: Boolean

TokenAggregation:
├── periods: List<TokenPeriod>
├── grandTotal: Long
└── estimatedCost: BigDecimal

TokenPeriod:
├── date: LocalDate
├── inputTokens: Long
└── outputTokens: Long
```

---

## 七、对话回退

用户右键某条消息 → "从此处重试"：

- 删除该消息**之后**的所有消息（含选中的那条的回复链）
- 保留选中消息之前的历史
- 从选中消息重新发送到 LLM
- 回退后的消息不立即删除（标记 `deleted: true`），用户可通过"撤销回退"恢复
- Plan Mode 中 LLM 通过 `removePlan` + `createPlan` 可自行管理任务重试，不适用此机制

回退相关接口：

```
ChatViewModel
├── rollbackToMessage(messageId: String)     // 回退：标记 messageId 之后的消息 deleted=true
├── undoRollback()                           // 撤销回退：恢复最近回退的消息 deleted=false
└── hasPendingRollback: Boolean              // 是否存在可撤销的回退
```

AgentSession 中的消息标记：

```
Message:
├── ...
└── deleted: Boolean = false   // 回退标记，true 时持久化保留但 UI 不渲染。撤销回退时恢复为 false
```

---

## 八、会话清理策略

- **不做自动清理**：自动清理可能误删有价值的日志，导致无法恢复/回溯问题。系统不替用户做删除决定。
- **手动批量删除**：Sessions 页面提供 `[全选]` + `[🗑 删除选中]` 按钮，对应
  `SessionManager.deleteSessions(ids)` / `SessionStore.deleteAll(sessionIds)`
- **`/clear` 复用 session.id**：清空消息不产生新文件，避免 session 文件堆积
