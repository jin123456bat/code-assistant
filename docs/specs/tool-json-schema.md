# 工具 JSON Schema 完整定义

> 本文档给出 Agent 模式 8 个内置工具在 API 调用时的完整 JSON Schema。Schema 由 Anthropic SDK 的
`@JsonClassDescription` + `@JsonPropertyDescription` 注解自动生成，`strict: true` +
`additionalProperties: false` + 全部属性 `required`。

## 一、Schema 生成机制

```
Kotlin data class (@JsonClassDescription + @JsonPropertyDescription)
  → Jackson 注解反射
  → Anthropic SDK ToolConverter.toolFromClass()
  → BetaTool.builder().inputSchema(jsonSchema).build()
  → MessageCreateParams.addTool()
```

所有工具共享以下约束：

- `additionalProperties: false`
- 所有字段 `required`（包括可选字段，值为 `null` 时传 `null`）
- `type: object`

## 二、Read — 读取文件

```json
{
  "name": "Read",
  "description": "读取项目内指定文件的内容",
  "input_schema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "项目内相对路径，如 src/main/kotlin/UserService.kt"
      },
      "startLine": {
        "type": "integer",
        "description": "起始行号，1-based，可选"
      },
      "endLine": {
        "type": "integer",
        "description": "结束行号，1-based，可选"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。读取小文件设 5-10s，大文件设 15-30s，0=不限"
      }
    },
    "required": [
      "filePath",
      "startLine",
      "endLine",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 返回值格式

```
[文件: {path} ({totalLines} 行 total, {returnedLines} 行已返回)]
{content}
[文件结束: {path}]
```

截断时（>500 行）：

```
[文件: {path} ({totalLines} 行 total, 已截断到 500 行)]
{truncatedContent}
... (共 {totalLines} 行，已截断到 500 行。如需查看剩余内容，请用 startLine 参数分页读取)
[文件结束: {path}]
```

## 三、Write — 覆盖写入文件

```json
{
  "name": "Write",
  "description": "覆盖写入整个文件。用于创建新文件或大范围修改",
  "input_schema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "项目内相对路径"
      },
      "content": {
        "type": "string",
        "description": "完整的新文件内容"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。小文件设 5-10s，大文件设 15-30s，0=不限"
      }
    },
    "required": [
      "filePath",
      "content",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 限制

- 内容上限：**3,000 行**。超出返回错误
- 需要**用户审批**（`ToolApprovalPolicy.requiresApproval("Write") = true`）

### 返回值格式

```
✅ 文件已写入: {path} ({lineCount} 行, {byteCount} 字节)
操作类型: {新建|覆盖}
```

## 四、Edit — 精确替换

```json
{
  "name": "Edit",
  "description": "精确替换文件中的部分内容。oldString 必须在文件中唯一且精确匹配",
  "input_schema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "项目内相对路径"
      },
      "oldString": {
        "type": "string",
        "description": "要被替换的旧内容片段，必须精确匹配文件中的唯一片段"
      },
      "newString": {
        "type": "string",
        "description": "替换后的新内容"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。简单替换设 5-10s，大文件设 15-30s，0=不限"
      }
    },
    "required": [
      "filePath",
      "oldString",
      "newString",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### modificationStamp 冲突检测

Edit 执行前校验文件 modificationStamp：

```
if (lastReadStamp != null && lastReadStamp != currentStamp)
  → 错误: "{path}" 已被外部修改
```

`lastReadStamp` 来源于最近一次 Read 操作记录的 `file.lastModified()`。

### 返回值格式

成功：

```
✅ 已修改: {path}
替换了 {replacedLines} 行 → {newLines} 行
```

失败（不唯一）：

```
错误: oldString 在 "{path}" 中匹配到 {count} 处，必须唯一。
请使用更长的 oldString 使其唯一。
```

失败（未找到）：

```
错误: 在 "{path}" 中未找到 oldString。
提示: 请使用 Read 确认文件内容。
文件共 {lines} 行。
```

## 五、Bash — 执行 Shell 命令

```json
{
  "name": "Bash",
  "description": "执行 Shell 命令。工作目录默认为项目根目录",
  "input_schema": {
    "type": "object",
    "properties": {
      "command": {
        "type": "string",
        "description": "要执行的 Shell 命令"
      },
      "workDir": {
        "type": "string",
        "description": "工作目录，可选，默认为项目根目录"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。快速命令设 10-30s，构建/测试设 120-300s，长时间任务设 600s，0=不限"
      }
    },
    "required": [
      "command",
      "workDir",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 执行细节

- Shell: `/bin/bash -c <command>`
- 工作目录: `workDir` 参数或 `project.basePath`
- 输出截断: **10,000 字符**（stdout + stderr 合并后截取）
- 返回内容截断: **4,000 字符**
- 超时后强制 `destroyForcibly()`

### 返回值格式

成功：

```
$ {command}
{output}  ← 最多 4000 字符
退出码: 0 | 耗时: {elapsed}s | {lines} 行输出
```

失败：

```
$ {command}
{output}  ← 最多 4000 字符
退出码: {exitCode} | 耗时: {elapsed}s
```

超时：

```
超时: 命令执行超过 {timeout}s，已强制终止
$ {command}
```

## 六、Glob — 列出目录结构

```json
{
  "name": "Glob",
  "description": "列出项目目录结构",
  "input_schema": {
    "type": "object",
    "properties": {
      "dirPath": {
        "type": "string",
        "description": "目录相对路径，可选，默认项目根目录"
      },
      "maxDepth": {
        "type": "integer",
        "description": "最大递归深度，默认 2 层"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。建议 5-10s，大项目设 15-30s，0=不限"
      }
    },
    "required": [
      "dirPath",
      "maxDepth",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 限制

- 每层最多 **200 个**子项
- 目录在前、文件在后，按名称排序
- 输出格式为树形缩进（`├──`）

## 七、Grep — 搜索文本

```json
{
  "name": "Grep",
  "description": "在项目中搜索文本内容。支持正则表达式，不区分大小写。非法正则自动回退为字面子串匹配",
  "input_schema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "搜索关键词"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。建议 5-15s，大项目设 20-30s，0=不限"
      }
    },
    "required": [
      "query",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 搜索范围

- 文件扩展名白名单: `kt, java, kts, xml, json, md, gradle, properties, yml, yaml`
- 排除目录: `build/`, `.git/`, `.idea/`
- 最多扫描 **800 个**文件
- 结果上限 **50 条**（超出标注截断）

### 正则回退

```kotlin
val regex = try {
    Regex(query, RegexOption.IGNORE_CASE)
} catch (_: Exception) {
    Regex(Regex.escape(query), RegexOption.IGNORE_CASE)  // 非法正则 → 字面子串
}
```

### 返回值格式

```
找到 {count} 条匹配:
{filePath}:{lineNumber}: {matchedLine}  ← 每行截断到 120 字符
```

截断时：

```
找到 50+ 条匹配，已截断到 50 条:
...
如有必要，请使用更精确的搜索词缩小范围。
```

## 八、readLints — IDE 诊断

```json
{
  "name": "readLints",
  "description": "读取指定文件的 IDE 诊断信息（错误和警告）",
  "input_schema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "项目内相对路径"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。建议 5-10s，大文件设 15-20s，0=不限"
      }
    },
    "required": [
      "filePath",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 当前状态

**占位实现**，始终返回空诊断：

```
文件: {path}
0 个错误, 0 个警告, 0 个提示
(IDE inspection 集成将在后续 Phase 实现)
```

## 九、Task — 派生子 Agent

```json
{
  "name": "Task",
  "description": "启动子代理处理子任务，子代理完成后返回结果摘要",
  "input_schema": {
    "type": "object",
    "properties": {
      "task": {
        "type": "string",
        "description": "子代理的任务描述"
      },
      "timeout": {
        "type": "integer",
        "description": "超时秒数，必填。简单任务设 30-60s，复杂任务设 120-300s，0=不限"
      }
    },
    "required": [
      "task",
      "timeout"
    ],
    "additionalProperties": false
  }
}
```

### 子 Agent 限制

- 并发上限: **3 个**（`Semaphore(3)`，超出返回错误）
- 子不可再 spawn 孙（嵌套上限 1 层）
- 子 Agent 执行期间父 Agent 阻塞等待（`CompletableFuture.get()` 无超时）

### 返回值格式

成功：

```
子任务完成: {result.take(500)}
轮次: {turns}
```

失败：

```
子任务失败: {errorMessage}
```

超出并发上限：

```
错误: Agent 并发数已达上限 (3)
```

## 十、审批策略

| 工具        | 需要审批 | 理由        |
|-----------|------|-----------|
| Read      | ❌    | 只读        |
| Glob      | ❌    | 只读        |
| Grep      | ❌    | 只读        |
| readLints | ❌    | 只读        |
| **Write** | ✅    | 写文件       |
| **Edit**  | ✅    | 写文件       |
| **Bash**  | ✅    | 执行命令      |
| **Task**  | ✅    | 派生子 Agent |

审批弹窗为模态 `Messages.showYesNoDialog`，在 EDT 上通过 `invokeAndWait` 执行。Agent Loop 在后台线程通过
`CountDownLatch.await()` 阻塞等待用户选择。

## 十一、工具参数提取工具类

所有工具通过 `ToolInput` 从 `toolUse._input()`（`JsonNode` 类型）提取参数：

```kotlin
object ToolInput {
    fun string(input: JsonNode, key: String): String?  // 提取字符串
    fun int(input: JsonNode, key: String): Int?         // 提取整数
    fun map(input: JsonNode): Map<String, Any?>          // 全量转 Map
}
```

> **注意：** `toolUse._input()` 返回的是 Anthropic SDK 的 `JsonNode` 类型。在 DeepSeek `strict`
> 模式下，未传的可选字段值可能为 `null` 而非不存在。
