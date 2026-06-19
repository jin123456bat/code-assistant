# Memory 记忆系统 — 设计文档

> 日期: 2025-06-19 | 状态: 已确认

## 概述

跨会话自动记忆系统，对齐 Claude Code Memory 三层架构和文件格式。LLM 可主动读写记忆，系统在会话结束时自动提取关键信息，对话开始时自动注入相关记忆到 System Prompt。

## 存储架构

```
~/.claude/memory/MEMORY.md       ← 全局记忆索引（用户级，跨项目共享）
~/.claude/memory/<name>.md       ← 全局单条记忆
<project>/.claude/memory/MEMORY.md  ← 项目记忆索引
<project>/.claude/memory/<name>.md  ← 项目单条记忆
```

### 记忆文件格式

```markdown
---
name: <kebab-case-slug>
description: <一句话描述，用于相关性和索引>
metadata:
  type: user | feedback | project | reference
---

<记忆正文>
**Why:** <为什么重要>
**How to apply:** <如何应用>
```

### MEMORY.md 索引格式

```markdown
- [Title](file.md) — one-line description
```

## 工具设计

### 1. memory_write

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 文件名（kebab-case） |
| `description` | string | ✅ | 一句话描述 |
| `content` | string | ✅ | 记忆正文（支持 `**Why:**`/`**How to apply:**`） |
| `type` | enum | ✅ | `user` / `feedback` / `project` / `reference` |
| `scope` | enum | ❌ | `user`（全局）/ `project`（项目），默认 `project` |

行为：
- 存在同名文件 → 覆盖旧文件 + 更新 MEMORY.md 索引行
- MEMORY.md 不存在 → 自动创建
- 写入前检查：description 是否有意义空值、name 是否符合 kebab-case

### 2. memory_read

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | ❌ | 搜索关键词，匹配 description 和正文 |
| `name` | string | ❌ | 按精确 name 读取 |
| `type` | string | ❌ | 按 type 过滤 |
| `scope` | enum | ❌ | `user`/`project`/`both`，默认 `both` |

行为：
- `name` 已指定 → 读取单个 `.md` 文件，返回正文
- `query` 已指定 → 遍历 MEMORY.md 索引，按关键词匹配排序，返回 top 5
- 两者都为空 → 返回 MEMORY.md 完整索引

### 3. memory_list

无参数。返回 MEMORY.md 索引内容（用户级 + 项目级合并）。

### 4. memory_delete

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 要删除的记忆文件名 |

行为：删除 `.md` 文件 → 从 MEMORY.md 移除对应索引行

## 自动注入

**时机**: `AgentLoop.buildSystemPrompt()` 中，每次 API 调用前

**流程**:
1. 取当前轮对话最后 3 条消息（user/assistant）
2. `MemoryRelevance.match()` 关键词提取 + 与所有记忆 description/正文匹配
3. 按相关度排序，取 top 5、总字数 ≤ 2000 字符
4. 注入到 System Prompt 末尾的 `## 记忆` 段落

## 自动提取

**时机**: `ChatViewModel.clearConversation()` 中（新会话时）

**流程**:
1. 取当前对话历史
2. 构造 extract prompt → 单次 API 调用 → 要求 LLM 输出 JSON 数组
3. JSON schema: `[{name, description, content, type, scope}]`
4. 解析成功 → 逐条 `memory_write` 写入
5. 解析失败 → 记录日志，跳过本次提取

**提取 prompt 要点**: 指示 LLM 提取用户偏好、代码约定、项目决策、值得复用的信息。每个记忆文件内容中必须包含 `**Why:**` 和 `**How to apply:**`。

## /memory 斜杠命令

用户手动管理记忆的命令，支持：
- `/memory` — 列出所有记忆
- `/memory <name>` — 查看某条记忆
- `/memory delete <name>` — 删除记忆

## 架构图

```
                   AgentLoop
                       │
    ┌──────────────────┼──────────────────┐
    │                  │                  │
    ▼                  ▼                  ▼
buildSystemPrompt  MemoryTools       clearConversation
    │              (4个工具)              │
    │                  │                  │
    ▼                  ▼                  ▼
MemoryRelevance    MemoryStore      MemoryAutoExtract
    │              (读写.md文件)        (调LLM提取)
    │                  │                  │
    └──────────────────┼──────────────────┘
                       │
                       ▼
              ~/.claude/memory/
              <project>/.claude/memory/
```

## 新增文件

| 文件 | 职责 |
|------|------|
| `agent/memory/MemoryEngine.kt` | 入口，整合 Store + Relevance + AutoExtract |
| `agent/memory/MemoryStore.kt` | 文件读写、YAML frontmatter 解析、MEMORY.md 索引维护 |
| `agent/memory/MemoryRelevance.kt` | 关键词提取 + 相关度匹配 + 排序截断 |
| `agent/memory/MemoryAutoExtract.kt` | 会话结束自动提取记忆 |
| `tools/MemoryWriteTool.kt` | memory_write 工具 |
| `tools/MemoryReadTool.kt` | memory_read 工具 |
| `tools/MemoryListTool.kt` | memory_list 工具 |
| `tools/MemoryDeleteTool.kt` | memory_delete 工具 |

## 集成点

| 位置 | 改动 |
|------|------|
| `AgentLoop.buildSdkToolDefs()` | 注册 4 个 memory 工具（只读工具加入 SAFE_TOOLS） |
| `AgentLoop.buildSystemPrompt()` | 追加 `## 记忆` 段落（相关记忆注入） |
| `AgentContext` | 新增 `memoryEngine: MemoryEngine` 引用 |
| `ChatViewModel.clearConversation()` | 触发 `MemoryAutoExtract.extract()` |
| `ChatToolWindow` | 注册 `/memory` 斜杠命令 |
| `ToolRegistryV3.registerBuiltIn()` | 注册 4 个 Memory Tool |
| `AppSettingsService` | 新增 `memoryEnabled` 开关（默认开） |

## 边界条件 & 故障处理

| 场景 | 处理 |
|------|------|
| 同名记忆文件已存在 | 覆盖旧文件，更新 MEMORY.md 的 description 行 |
| MEMORY.md 不存在 | 首次写入时自动创建 |
| 索引引用丢失文件 | 读取时跳过；下次写入时自动清理无效行 |
| 并发写同 name | 原子写入（tmp + ATOMIC_MOVE，复用 SessionStore 模式） |
| 自动提取 LLM 返回格式错误 | JSON schema 约束；解析失败则跳过并记录日志 |
| 目录无权限 | ToolResult.err() 返回具体错误信息 |
| YAML 解析失败 | try-catch 跳过该文件并记录日志 |
| token 预算（注入太多） | top 5 条 + 总字数 ≤ 2000 字符硬限制 |

## 测试

| 层级 | 测试对象 | 测什么 |
|------|---------|--------|
| 单元 | MemoryStore | 读写删、索引解析、YAML frontmatter |
| 单元 | MemoryRelevance | 关键词匹配排序、空输入、超长输入、中英文 |
| 单元 | MemoryAutoExtract | 结构化 JSON 解析（有效/无效/空） |
| 集成 | MemoryStore | tmp+ATOMIC_MOVE 原子写入、并发写不同 name |
| 集成 | MemoryTools | 完整写入→读取→列出→删除流程 |
