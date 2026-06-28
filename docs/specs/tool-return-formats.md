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

自动 readLints 结果（Edit/Write 成功后自动追加）:
⚠️ 该文件修改后存在 2 个编译错误:
  45:12: Unresolved reference: findById [ERROR]
  78:5: Type mismatch: inferred type is String but Unit was expected [ERROR]
```

上限：`newString` 最多 3000 行。无新诊断时不追加 lint 结果。

**UI 展示：** `Edit` 成功后，ToolCallCard 内联展示 `SimpleDiff` 生成的可视化 diff（ADD 绿色/DEL
红色/CTX 灰色），替换当前的前后 3 行文本对比。

---

## Bash

**返回值格式（错误优先）：**

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

输出截断（中段截断，对齐 Claude Code）:
$ {command}
{stdoutHead 前 30 行}
... (省略 {omittedLines} 行) ...
{stdoutTail 后 30 行}
退出码: {exitCode} | 耗时: {duration}s | 共 {totalLines} 行

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

## Agent

```
启动成功:
✅ 已启动子 Agent: {prompt}
结果将通过 sub-session #{sessionId} 返回。

完成后回调:
🔧 Agent 完成: {prompt}
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
计划项数: {planCount}
计划项列表:
1. {plan1.description} — 工具: {plan1.tool}, 文件: {plan1.files}
2. {plan2.description} — 工具: {plan2.tool}, 文件: {plan2.files}
...
计划已持久化，自动开始执行。
```

上限：最多 20 项。

---

## listPlans

```
当前计划: {summary}
进度: {doneCount}/{totalCount} 已完成

计划项列表:
1. ✅ {plan1.description} — COMPLETED
2. 🔄 {plan2.description} — EXECUTING
3. ⬜ {plan3.description} — PAUSED
4. 🗑 {plan4.description} — CANCELLED
```

无参数工具。

---

## removePlan

```
成功:
✅ 已删除计划项: {planId} — "{description}"
剩余计划项: {remainingCount} 项，继续自动执行。

拒绝（非 PAUSED 状态）:
❌ 无法删除计划项 {planId}：当前状态为 {status}，仅 PAUSED 状态可删。
```

仅 PAUSED 状态可删。

---

## reorderPlans

```
成功:
✅ 已重排剩余计划项:
新顺序:
1. {plan2.description} (原 plan-2)
2. {plan3.description} (原 plan-3)
3. {plan1.description} (原 plan-1)
继续自动执行。

参数无效（planId 不完整或不匹配）:
❌ 重排失败：提供的 planId 列表与当前 PAUSED 计划项不匹配。
当前 PAUSED 计划项: plan-1, plan-3, plan-5
提供的: plan-1, plan-5
```

---

## markPlanDone

```
成功:
✅ 已将计划项 {planId} — "{description}" 标记为 COMPLETED。
剩余计划项: {remainingCount} 项，继续自动执行。

拒绝（已经是终态）:
❌ 无法标记计划项 {planId}：当前状态为 {status}。COMPLETED/CANCELLED 状态已是终态，无法再标记。
```

PAUSED/EXECUTING 状态均可标记。

---

## WebSearch

```
找到 {matchCount} 条搜索结果:
1. {title1}
   {url1}
2. {title2}
   {url2}
...

无结果:
未找到与 "{query}" 相关的结果。请尝试:
- 使用更通用的搜索词
- 检查搜索词拼写
- 尝试不同的表述方式
```

上限：无硬性上限，服务器端控制返回数量。15 分钟内相同查询返回缓存结果。

---

## WebFetch

```
成功:
{基于 prompt 从页面提取的信息}

HTTP 错误:
错误: 无法获取 "{url}" (HTTP {statusCode})。页面可能不存在或需要认证。

重定向:
重定向: {url} → {redirectUrl}
请使用重定向后的 URL 重新调用 WebFetch。

内容无法提取:
错误: 无法从 "{url}" 提取内容。页面可能为 PDF、二进制文件或需要 JavaScript 渲染。
```

限制：HTTP 自动升级为 HTTPS。跨主机重定向返回给 LLM 而非自动跟随。15 分钟内相同 URL 返回缓存结果。

---

## AskUserQuestion

无独立返回值格式——用户的选择结果直接追加到 `params.messages` 中，作为 tool_result 的 content 字段：

```
用户回答:
1. {question1.header}: {selectedOption1.label} — {selectedOption1.description}
   {userNotes1?}
2. {question2.header}: {selectedOption2.label} — {selectedOption2.description}
...
```

用户拒绝回答或关闭 dialog 时：

```
用户未回答: 对话框已关闭
```

上限：一次最多 4 个问题。每个问题 2-4 个选项。多选时返回数组。

---

## Symbol

基于 IntelliJ PSI，无需外部 LSP Server。所有 operation 共用参数 `filePath` + `line` + `character`
（1-based）。

```
goToDefinition 成功:
📍 {symbolName} 定义于 {filePath}:{line}
```kotlin
{definitionSnippet}  // 定义行 + 前后各 3 行
```

所在: {className}.{methodName}

goToDefinition 无结果:
未找到 "{symbolName}" 的定义。该符号可能为外部依赖或动态引用。

---

findReferences 成功:
🔍 {symbolName} 在 {totalCount} 处被引用:

1. {filePath1}:{line1}: {sourceLine1} // {context1}
2. {filePath2}:{line2}: {sourceLine2} // {context2}
   ...

findReferences 截断（> 50 条）:
🔍 {symbolName} 在 {totalCount} 处被引用，已截断到 50 条:
{results}
还有 {remaining} 处未显示。请缩小搜索范围或指定文件。

findReferences 无结果:
未找到 "{symbolName}" 的引用。该符号可能未被使用或为私有引用。

---

hover 成功:
ℹ️ {symbolName}: {typeName}
{KDoc/JavaDoc 第一行}
{完整签名}
位置: {filePath}:{line}

hover 无文档:
ℹ️ {symbolName}: {typeName}
位置: {filePath}:{line}

hover 无结果:
未找到位于 {filePath}:{line}:{character} 的符号。

---

documentSymbol 成功:
📄 {fileName} ({symbolCount} 个符号):
├── class {ClassName1} ({line1})
│ ├── fun {methodName1}() ({line2})
│ └── val {fieldName1}: {Type} ({line3})
├── fun {topLevelFun}() ({line4})
└── interface {InterfaceName} ({line5})

documentSymbol 截断（> 100）:
📄 {fileName} ({symbolCount} 个符号，已截断到 100):
{results}

documentSymbol 空文件:
📄 {fileName}: 无符号（空文件或仅含注释）

---

上限：引用/调用数 ≤ 50，符号数 ≤ 100，workspaceSymbol ≤ 20。operation 参数无效时返回错误提示。

---

### goToImplementation

```
goToImplementation 成功:
🔍 {symbolName} 有 {count} 个实现:
1. {className1} 位于 {filePath1}:{line1}
   {implementationSnippet1}
2. {className2} 位于 {filePath2}:{line2}
   {implementationSnippet2}

goToImplementation 无结果:
未找到 "{symbolName}" 的实现。该符号可能为具体类或非虚方法。
```

---

### workspaceSymbol

```
workspaceSymbol 成功:
🔍 搜索 "{query}" 找到 {count} 个匹配:
1. class {ClassName1} — {filePath1}:{line1}
2. interface {InterfaceName} — {filePath2}:{line2}
3. fun {functionName}() — {filePath3}:{line3}

workspaceSymbol 截断（> 20）:
🔍 搜索 "{query}" 找到 {count} 个匹配，已截断到 20:
{results}
请使用更精确的符号名缩小范围。

workspaceSymbol 无结果:
未找到匹配 "{query}" 的符号。请检查名称拼写。
```

---

### incomingCalls / outgoingCalls

```
incomingCalls 成功:
📞 {symbolName} 被 {count} 处调用:
1. {callerFunction1}() — {filePath1}:{line1}
2. {callerFunction2}() — {filePath2}:{line2}

incomingCalls 无结果:
{符号为入口函数或未被直接调用}

outgoingCalls 成功:
📞 {symbolName} 调用了 {count} 个方法:
1. {callee1}() — {filePath1}:{line1}
2. {callee2}() — {filePath2}:{line2}

outgoingCalls 无结果:
{函数体为空或仅含简单表达式}

截断（> 50）:
📞 {symbolName} 被调用/调用了 {totalCount} 处，已截断到 50:
{results}
还有 {remaining} 处未显示。
```
