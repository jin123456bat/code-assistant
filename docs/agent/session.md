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

> 存储写入流程详见 [persistence.md §存储概述](../specs/persistence.md#存储概述)。

### 读取容错

- `JsonParseException` → 标记文件损坏，跳过该会话
- `FileNotFoundException` → 从 index 自动移除过期条目

### 损坏文件用户感知

Session JSON 解析失败时，Sessions 页面显示 ⚠️ 标记：

```
┌──────────────────────────────────────────────────┐
│ 🔧 项目配置                   [全选] [🗑 删除选中] │
├──────────────────────────────────────────────────┤
│ ⚠️ 会话文件损坏 (2026-06-28_abc123.json)         │ ← 不可点击，灰显
│ 💬 重构 UserService              2026-06-27      │ ← 正常会话，可点击
│ ⚠️ 会话文件损坏 (2026-06-26_def456.json)         │
└──────────────────────────────────────────────────┘
```

- 损坏的会话灰显、不可点击，仅显示"会话文件损坏" + 文件名
- 不做自动删除——保留损坏文件供用户手动排查
- 用户可到 `<project>/.code-assistant/sessions/` 目录手动删除 `.json` 文件，重启 IDE 后⚠️ 消失
- 损坏文件不清除对应的 `approvedTools` 白名单（白名单在 Session JSON 内部，JSON 损坏意味着白名单也不可读）

### 恢复规则

IDE 重启加载 Session 时，进行以下状态修复：

| 加载时状态                                                   | 修复动作       |
|---------------------------------------------------------|------------|
| Agent 状态为 PROCESSING、AWAITING_APPROVAL、EXECUTING、PAUSED | 重置为 IDLE   |
| Plan 状态为 EXECUTING                                      | 重置为 PAUSED |

---

## 二、Session Index

`<project>/.code-assistant/sessions/index.json` 维护所有会话的元数据索引。完整 JSON Schema
和字段说明见 [persistence.md §6.2](../specs/persistence.md#62-session-index)。

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

### 失败处理

- **API 调用失败**：title 保持默认值"新会话"，不覆盖为错误信息
- **429 限流**：等待 1 分钟后重试，无限重试，除非用户手动关闭会话
- **用户可见性**：标题生成过程用户完全不可见——不显示状态指示器，不弹出错误提示

---

## 四、Token Usage 页面

从所有 session 文件的 `totalTokens` 聚合，提供三种视图（按会话/按日/按月）。Token 使用量使用
K（千）、M（百万）为单位，不显示具体价格。

> UI 布局详见 [Token Usage 页面](../ui/pages.md#五token-usage-页面)。

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

完整的 JSON Schema 定义见 [persistence.md §6.1](../specs/persistence.md#61-session-json)
。以下仅列出核心字段关系。

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

---

## 八、会话恢复行为矩阵

从 Session JSON 恢复会话时，不同数据的保留/丢失情况：

| 数据                      | 是否持久化  | 恢复后行为                                                         |
|-------------------------|--------|---------------------------------------------------------------|
| 消息文本（user/assistant）    | ✅ 持久化  | 完整恢复，Markdown 重新渲染                                            |
| 工具调用卡片（ToolCallCard）    | ✅ 持久化  | 恢复后的卡片状态统一显示为 DONE/ERROR（历史终态），不可重新展开审批按钮                     |
| Plan 数据                 | ✅ 持久化  | PlanCard 重新渲染。PAUSED/EXECUTING 的未完成计划项恢复后**不会自动继续执行**，用户需手动触发 |
| 图片（粘贴）                  | ❌ 不持久化 | 恢复后仅保留 `[Image: screenshot.png]` 占位文本，图片数据丢失                  |
| 流式 token（未完成的生成）        | ❌ 不持久化 | IDE 关闭时正在生成的流式内容丢失，下次打开只看到上一条完整的 assistant 消息                 |
| 审批白名单（approvedTools）    | ✅ 持久化  | 从 Session JSON 恢复，信任关系保留。`/clear` 不清除白名单                      |
| modificationStamp       | ❌ 仅内存  | 跟随 AgentSession 生命周期，会话恢复后 stamp 缓存为空                         |
| 思考过程（reasoning_content） | ❌ 不持久化 | 恢复后不可见，仅有正式回复文本                                               |
| Token 统计                | ✅ 持久化  | `totalTokens` 从 API usage 累积，准确恢复                             |
| compact 摘要              | ✅ 持久化  | `compactSummary` 恢复，后续 compact 在此基础上继续追加                      |

> **设计原则：** 持久化的是"对话事实"（谁说了什么、做了什么），不持久化的是"临时状态"（审批信任、文件锁、
> 正在生成的内容、图片数据）。这样平衡存储开销和恢复可用性。断连/崩溃后的未完成流式消息通过尾部标注
> `[连接中断]` 提示用户。

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

## 九、会话清理策略

- **不做自动清理**：自动清理可能误删有价值的日志，导致无法恢复/回溯问题。系统不替用户做删除决定。
- **手动批量删除**：Sessions 页面提供 `[全选]` + `[🗑 删除选中]` 按钮，对应
  `SessionManager.deleteSessions(ids)` / `SessionStore.deleteAll(sessionIds)`
- **`/clear` 创建新 session**：旧 session 保留在 Sessions 列表中，新 session 干净启动
