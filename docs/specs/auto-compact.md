# Auto-Compact 上下文压缩

> **当前状态：** Auto-Compact 逻辑在 AgentLoop 中**尚未实现**。本文档描述设计意图和 System Prompt
> 完整内容，供后续开发参考。

## 一、设计意图（来自设计文档）

当上下文 Token 数超过模型窗口的 70% 时触发压缩：

```
估算总 token（system + messages + tools） > modelContextLimit * 0.7
  → 独立 API 调用（不带 tools, max_tokens=1024）生成摘要
  → session.messages = [摘要消息, ...近期原文(≥2轮)]
  → 重建 params.messages
  → SessionStore.save()
```

### 关键参数

| 参数            | 值                 | 说明                     |
|---------------|-------------------|------------------------|
| 模型上下文窗口       | 1,000,000 tokens  | DeepSeek V4 上限，写死不动态检测 |
| 压缩触发阈值        | 70%（~700K tokens） | 阈值见 loop.md            |
| 摘要 max_tokens | 1024              | 摘要生成的 API 参数           |
| 保留近期原文        | ≥2 轮              | 压缩后保留的最近对话轮次           |

### 续写机制

当 API 返回 `stop_reason="max_tokens"` 时自动发送 `"继续"`：

- `maxAutoContinue = 5`（单轮最多续写 5 次，防死循环）
- 续写**不增加** turn 计数
- `"继续"` 消息**不持久化**
- `continueStreak` 在 `end_turn` 后归零

## 二、当前 System Prompt 完整内容

以下是 `AgentLoop.buildSystemPrompt()` 的实际输出模板（变量已标注）：

```
你是 Code Assistant，一个运行在 JetBrains IDE 中的智能编程助手。你可以：
- 阅读项目中的任何文件
- 修改文件内容（精确替换或完整覆盖）
- 执行 Shell 命令
- 列出目录结构
- 搜索代码内容
- 读取 IDE 诊断信息（错误和警告）
- 启动子 Agent处理子任务

当前项目：{projectName}
项目路径：{basePath}
当前文件：{currentFileName}

## 工具使用原则

1. 先用 Read 或 Glob 获取足够信息，再使用 Write/Edit 修改代码。
2. 修改代码前，先读取目标文件的完整内容或足够上下文。
3. Edit 的 oldString 必须在文件中唯一且精确匹配。如果不确定 oldString，先用 Read 读取目标区域。
4. Shell 命令的工作目录默认为项目根目录。长时间运行的命令（如 gradle build）是正常的，不需要手动终止。
5. 所有文件路径使用项目内相对路径。

## 回复风格

- 使用中文回复
- 代码块使用正确的语言标记（```kotlin、```java、```json 等）
- 修改文件前简要说明变更内容
- 执行 Shell 命令前说明命令用途

{toolSection}  ← ToolRegistry.buildSystemPromptTools() 动态生成

## 环境
- 日期: {dateStr}          ← LocalDate.now()
- 操作系统: {osName}        ← System.getProperty("os.name")
- 工作目录: {basePath}
- 项目名: {projectName}
- Git 分支: {branch}        ← 从 .git/HEAD 读取

{configRef}  ← 检测到 CLAUDE.md/CODEX.md/AGENTS.md/.cursorrules 时注入提示

{skillExt}   ← SkillManager.getSystemPromptExtension() 注入 Skill 列表
```

### 工具使用指南（toolSection 动态内容）

```kotlin
fun buildSystemPromptTools(): String {
    return """
## 工具使用指南
- **Read** — 先确认路径存在。大文件用 startLine/endLine 分页读取。
- **Write** — 仅用于创建新文件或需要大范围修改时。小修改请用 Edit。
- **Edit** — 小范围修改的首选，比 Write 更安全。oldString 必须唯一且精确匹配。
- **Bash** — 工作目录默认是项目根目录。构建、测试等长时间命令是正常的。
- **Glob** — 用 maxDepth 控制深度，默认 2 层。
- **Grep** — 结果上限 50 条。关键词太泛会被截断，用更精确的搜索词。
- **readLints** — 修改代码后检查是否有新错误/警告。
- **Task** — 子 Agent有独立上下文窗口，不会污染当前上下文。用于并行处理独立任务。
- **并行调用** — 多个独立的读取/搜索操作可以同时发起，无需等待。
""".trimIndent()
}
```

### 编码规范文件检测

启动时扫描项目根目录是否存在以下文件：

| 文件名            | 检测逻辑                                           |
|----------------|------------------------------------------------|
| `CLAUDE.md`    | `File(project.basePath, "CLAUDE.md").exists()` |
| `CODEX.md`     | 同上                                             |
| `AGENTS.md`    | 同上                                             |
| `.cursorrules` | 同上                                             |

如任一存在，注入：

```
遵守项目中 CLAUDE.md / CODEX.md 定义的编码规范
```

## 三、Compact 摘要 Prompt（待实现）

当需要压缩时，应使用以下独立 API 调用生成摘要（不含 tools）：

### System Prompt

```
你是一个对话摘要生成器。请将以下对话历史压缩为简洁的摘要。

要求：
1. 保留所有关键决策和结论
2. 保留所有文件修改操作（文件路径 + 改动简述）
3. 保留所有未解决的问题和待办事项
4. 保留用户明确表达的偏好和约束
5. 使用中文，不超过 500 字
```

### User Prompt

```
请摘要以下对话（从旧到新排列）：

{历史消息文本}
```

### API 参数

```json
{
  "model": "deepseek-v4-pro",
  "max_tokens": 1024,
  "messages": [
    {"role": "system", "content": "<摘要 system prompt>"},
    {"role": "user", "content": "<历史消息>"}
  ]
}
```

> **注意：** 摘要请求**不带 tools**，因为摘要本身不需要工具调用能力。

## 四、与 Claude Code 的差异

| 特性           | Claude Code | Code Assistant（当前） |
|--------------|-------------|--------------------|
| Auto-Compact | ✅ 自动触发      | ❌ 未实现              |
| 续写机制         | ✅           | ❌ 未实现              |
| 摘要保留工具结果     | ✅           | —                  |
| 阈值可配         | ❌ 内置        | —                  |
| 手动 /compact  | ✅           | ❌                  |
