# 文件写锁（modificationStamp）

> 本文档描述 Write/Edit 操作的文件同步冲突检测机制。

## 一、概述

Code Assistant 使用**乐观锁（optimistic stamp）**策略防止 Agent 写入被外部修改的文件，避免静默覆盖用户的并行编辑。

不阻止写入本身——只在写入前检测文件是否被外部修改，如果被修改则报错让 LLM 重新读取后重试。

## 二、核心流程

```
LLM 调用 Read(filePath)
  │
  ├── 读取文件内容
  └── 记录 stamp: session.fileStamps[path] = file.lastModified()

...（LLM 思考、执行其他工具）...

LLM 调用 Edit(filePath, oldString, newString)
  │
  ├── 读取当前文件: file.lastModified() → currentStamp
  ├── 查询上次 Read 记录的 stamp: session.fileStamps[path] → lastReadStamp
  │
  ├── lastReadStamp == currentStamp？
  │     ├── ✅ 一致 → 文件未被外部修改 → 执行替换 → 写入
  │     │     └── 更新 stamp: session.fileStamps[path] = file.lastModified()
  │     │
  │     └── ❌ 不一致 → 文件已被外部修改
  │           └── 返回错误: ""{path}" 已被外部修改（上次读取 stamp=X，当前 stamp=Y）。请使用 Read 重新读取文件后再试。"
  │
  └── lastReadStamp == null？
        └── 未记录 stamp（从未 Read 过此文件）→ 直接执行，不检测冲突
```

## 三、Stamp 记录时机

| 操作         | 行为                                                    |
|------------|-------------------------------------------------------|
| `Read`     | 记录 `file.lastModified()` 到 `session.fileStamps[path]` |
| `Write`    | 写入成功后更新 `session.fileStamps[path]`                    |
| `Edit`     | 写入成功后更新 `session.fileStamps[path]`                    |
| `/clear`   | 清空 `session.fileStamps`（新会话无历史 stamp）                 |
| Session 恢复 | `fileStamps` 不持久化（重启 IDE 后无历史 stamp）                  |

## 四、Stamp 生命周期

```
session.fileStamps: MutableMap<String, Long>
```

- **Key**: 文件相对路径（如 `src/main/kotlin/UserService.kt`）
- **Value**: `File.lastModified()` 返回值（Unix 毫秒时间戳）
- **生命周期**: 跟随 `AgentSession`——会话结束即丢弃，不持久化
- **线程安全**: 单 AgentSession 同时只有一个 AgentLoop 在运行，无并发写入

## 五、冲突检测的覆盖范围

| 场景                                    | 是否检测    | 说明                             |
|---------------------------------------|---------|--------------------------------|
| Agent Read 后用户手动编辑了文件                 | ✅ 检测    | stamp 不匹配 → 报错                 |
| Agent Read 后另一个 Agent（同项目多窗口）编辑了文件    | ✅ 检测    | stamp 不匹配 → 报错                 |
| Agent 从未 Read 直接 Edit                 | ❌ 不检测   | `lastReadStamp == null` → 跳过检测 |
| Agent Read 后外部工具（git checkout 等）修改了文件 | ✅ 检测    | stamp 不匹配 → 报错                 |
| Agent Write 后立即 Edit 同一文件             | ✅ 通过    | Write 已更新 stamp                |
| 文件系统时间精度不足（FAT32 2s 粒度）               | ⚠️ 可能漏检 | 同一秒内多次修改 stamp 相同              |

## 六、与文件系统锁的区别

当前使用乐观锁而非文件系统锁（`FileLock`）：

| 维度     | 乐观锁（当前）      | 文件系统锁    |
|--------|--------------|----------|
| 阻止并发写入 | ❌ 不阻止，仅检测后报错 | ✅ 阻止     |
| 跨进程    | ✅            | ✅        |
| 需要释放   | 不需要          | 需要（否则死锁） |
| 崩溃安全   | ✅ 无残留锁       | ❌ 可能残留   |
| 实现复杂度  | 低            | 高        |

## 七、设计决策

| 决策                            | 理由                                            |
|-------------------------------|-----------------------------------------------|
| stamp 不持久化                    | 重启 IDE 后文件状态已不可知，持久化的 stamp 是过时信息             |
| 仅在 Edit 时检测，Write 不检测         | Write 是全覆盖操作，用户意图是替换整个文件内容                    |
| 检测失败不自动重试                     | 让 LLM 自行决定：重新 Read → Edit，还是直接覆盖（用 Write）     |
| 使用 `lastModified()` 而非内容 hash | `lastModified()` 是文件系统原生操作（纳秒/毫秒级），hash 需全量读取 |
| Null stamp 跳过检测               | LLM 首次 Edit 无历史记录时不设障碍                        |
