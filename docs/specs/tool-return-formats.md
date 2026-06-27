# 工具返回值格式契约

> **原始来源：** `docs/tech-spec.md`（已拆分，内容归入 `docs/specs/` 各文件）

本文档定义每个 Tool 的 `execute()` 方法中 `ToolResult.content` 的**精确格式契约**。LLM
收到的就是这些字符串。格式不一致会导致 LLM 行为不可预测。

---

## 通用约束

- 所有 `ToolResult` 的 `content` 字段不包含 Markdown 代码块包裹（LLM 自己加代码块）
- 文件内容原样返回，不添加额外转义
- `{variable}` 为实际值占位符
- `errorMessage` 字段仅在 `success=false` 时有值

---

## Read

```
成功:
[文件: {filePath} ({totalLines} 行 total, {returnedLines} 行已返回)]
{fileContent...}
[文件结束: {filePath}]

截断:
[文件: {filePath} ({totalLines} 行 total, 已截断到 {maxLines} 行)]
{fileContent...}
... (共 {totalLines} 行，已截断到 {maxLines} 行。如需查看剩余内容，请用 startLine 参数分页读取)
[文件结束: {filePath}]

文件不存在:
错误: 文件 "{filePath}" 不存在。请检查路径是否正确。
提示: 使用 Glob 工具查看目录结构。
```

上限：单次最多返回 500 行。

---

## Write

```
成功:
✅ 文件已写入: {filePath} ({lineCount} 行, {byteCount} 字节)
操作类型: {新建 | 覆盖}

内容过长:
错误: 内容过长 ({actualLines} 行，上限 {maxLines} 行)。请拆分为多次写入或减少内容。

写入失败:
错误: 写入 "{filePath}" 失败: {exceptionMessage}
```

上限：`newString` 最多 3000 行。

---

## Edit

```
成功:
✅ 已修改: {filePath}
替换了 {replacedLineCount} 行 → {newLineCount} 行
{修改前 3 行}
---
{修改后 3 行}

oldString 未找到:
错误: 在 "{filePath}" 中未找到 oldString。
提示: 请使用 Read 读取目标区域，确认 oldString 精确匹配文件内容（包括空白字符）。
附近内容({nearbyLineStart}-{nearbyLineEnd}行):
{nearbyContent}

oldString 匹配到 {count} 处:
错误: oldString 在 "{filePath}" 中匹配到 {count} 处，必须唯一。
请选择其中一处，使用更长的 oldString 使其唯一：
- 第 {line1} 行: "{context1}"
- 第 {line2} 行: "{context2}"
- ...（最多显示 5 处）

文件被外部修改:
错误: "{filePath}" 已被外部修改（上次读取 stamp={oldStamp}，当前 stamp={newStamp}）。
请使用 Read 重新读取文件后再试。

空 oldString + 文件不存在:
✅ 文件已创建: {filePath} ({lineCount} 行, {byteCount} 字节)

文件不存在（非新建模式，前置校验拒绝）:
错误: 文件 "{filePath}" 不存在，拒绝执行 Edit。
提示：使用 Glob 查看目录结构。

文件未 Read（前置校验拒绝）:
错误: 请先用 Read 读取 {filePath} 后再修改。
提示: 当前 turn 中该文件未被 Read，拒绝执行 Edit。先用 Read 读取文件内容和 modification stamp 后再试。

自动 readLints 结果（⏳ 规划中，Edit/Write 成功后自动追加）:
⚠️ 该文件修改后存在 2 个编译错误:
  45:12: Unresolved reference: findById [ERROR]
  78:5: Type mismatch: inferred type is String but Unit was expected [ERROR]
```

上限：`newString` 最多 3000 行。无新诊断时不追加 lint 结果。

**UI 展示（⏳ 规划中）：** `Edit` 成功后，ToolCallCard 内联展示 `SimpleDiff` 生成的可视化 diff（ADD 绿色/DEL
红色/CTX 灰色），替换当前的前后 3 行文本对比。

---

## Bash

**返回值格式（⏳ 强化版，错误优先）：**

```
成功（退出码 0 + stderr 空）:
$ {command}
{stdout}
退出码: 0 | 耗时: {duration}s | {outputLineCount} 行输出

成功但有 stderr（退出码 0 + stderr 非空，构建工具常见）:
$ {command}
{stdout}
⚠️ 命令成功但有以下输出（stderr）:
{stderr}
退出码: 0 | 耗时: {duration}s

超时（timeout 由 LLM 在 tool call 时传入，>0 时生效）:
$ {command}
超时: 命令执行超过 {timeout}s，已强制终止

输出截断:
$ {command}
{stdout}
... (共 {totalLines} 行输出，完整输出见 IDE 工具卡片)
退出码: {exitCode} | 耗时: {duration}s

命令失败（退出码非零，强化标注）:
⚠️ 命令执行失败 (退出码: {exitCode})
STDERR:
{stderr}
STDOUT:
{stdout}
耗时: {duration}s
提示: 检查命令参数是否正确，路径是否存在。
```

上限：最多返回 200 行输出（stdout+stderr）。timeout 由 LLM 自行判断传入（秒），0=不限。

### 格式说明

- ⚠️ 标记的前置确保 LLM 在流式解析时先看到错误信息
- 失败时 stderr 排在 stdout 前面（错误信息优先）
- 成功但 stderr 非空的情况单独处理（如 gradle 的 warning 输出在 stderr），不误报为失败

---

## Glob

```
成功:
{dirPath}/
├── {dir1}/
│   ├── {file1} ({size})
│   └── {file2} ({size})
├── {dir2}/
│   └── ...
└── {file3} ({size})

{totalFiles} 个文件, {totalDirs} 个目录

截断:
{dirPath}/
├── ...
... (共 {totalFiles} 个文件, {totalDirs} 个目录，已截断到 {maxEntries} 条目，当前第 {start}-{end} 个。用 dirPath/maxDepth 缩小范围，或用 offset={nextOffset} 翻页获取更多)

目录不存在:
错误: 目录 "{dirPath}" 不存在。请检查路径。
```

上限：最多返回 50 个条目（文件+目录）。

---

## Grep

```
找到 {matchCount} 条匹配:
{filePath1}:{line1}: {content1}
{filePath2}:{line2}: {content2}
...

截断:
找到 {totalMatches} 条匹配，已截断到 {maxMatches} 条:
{filePath}:{line}: {content}
...
如有必要，请使用更精确的搜索词缩小范围以获取完整结果。

无结果:
未找到匹配 "{query}" 的内容。请尝试:
- 检查搜索词拼写
- 使用更简短的搜索词
- 确认搜索的文件类型正确

索引未就绪:
项目正在建立索引 ({progress}%)，搜索结果可能不完整。当前结果:
{partialResults}
```

上限：最多返回 50 条匹配。

---

## readLints

```
文件: {filePath}
{errorCount} 个错误, {warningCount} 个警告, {infoCount} 个提示:

错误:
{line}:{column}: {message} [{code}]

警告:
{line}:{column}: {message} [{code}]

提示:
{line}:{column}: {message} [{code}]

截断:
按严重级别排序（错误 > 警告 > 提示），已截断到 {maxItems} 条:
{items}
还有 {remainingCount} 条未显示。

索引未就绪:
项目正在建立索引 ({progress}%)，诊断结果可能不完整。当前结果:
{partialDiagnostics}
```

上限：最多返回 50 条诊断，按 severity 排序（ERROR > WARNING > INFO）。

---

## Task

```
子任务: {taskSummary}
状态: {completed | failed | cancelled}
结果摘要:
{summary (≤ 2000 tokens)}
详情: sub-session #{sessionId}
```

上限：子 Agent 结果摘要最多 2000 tokens。完整执行过程保存为独立 Session。

---

## createPlan

```
✅ 已创建执行计划: {taskSummary}
步骤数: {stepCount}
步骤列表:
1. {step1.description} — 工具: {step1.tool}, 文件: {step1.files}
2. {step2.description} — 工具: {step2.tool}, 文件: {step2.files}
...
计划已持久化，自动开始执行。
```

上限：最多 20 步。

---

## listSteps

```
当前计划: {summary}
进度: {doneCount}/{totalCount} 已完成

步骤列表:
1. ✅ {step1.description} — DONE
2. 🔄 {step2.description} — EXECUTING
3. ⬜ {step3.description} — PENDING
4. 🗑 {step4.description} — DELETED
```

无参数工具。

---

## deleteStep

```
成功:
✅ 已删除步骤: {stepId} — "{description}"
剩余步骤: {remainingCount} 步，继续自动执行。

拒绝（步骤非 PENDING 状态）:
❌ 无法删除步骤 {stepId}：当前状态为 {status}，仅 PENDING 状态的步骤可删除。
```

仅 PENDING 状态可删。

---

## reorderSteps

```
成功:
✅ 已重排剩余步骤:
新顺序:
1. {step2.description} (原 step-2)
2. {step3.description} (原 step-3)
3. {step1.description} (原 step-1)
继续自动执行。

参数无效（stepId 不完整或不匹配）:
❌ 重排失败：提供的 stepId 列表与当前 PENDING 步骤不匹配。
当前 PENDING 步骤: step-1, step-3, step-5
提供的: step-1, step-5
```

---

## markStepDone

```
成功:
✅ 已将步骤 {stepId} — "{description}" 标记为 DONE。
剩余步骤: {remainingCount} 步，继续自动执行。

拒绝（步骤当前为 DONE/DELETED 状态）:
❌ 无法标记步骤 {stepId}：当前状态为 {status}。DONE/DELETED 状态的步骤已是终态，无法再标记为完成。
```

PENDING/EXECUTING 状态均可标记。
