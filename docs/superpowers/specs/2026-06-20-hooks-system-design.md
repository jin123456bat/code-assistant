# Hooks 事件系统 — 设计文档

> 日期: 2026-06-20 | 状态: 已确认 | 对齐: Claude Code Hooks 规范

## 概述

事件驱动的 hook 系统，允许用户在 Agent 生命周期的关键节点插入自定义逻辑（安全审计、日志记录、自定义校验、通知等）。完全对齐 Claude Code Hooks 规范。

## 配置模型

```yaml
# ~/.claude/settings.json 的 "hooks" 字段 (全局)
# <project>/.claude/hooks.yaml (项目级，与全局合并，工具名正则去重)

hooks:
  PreToolUse:
    - matcher: "execute_command|write_file"
      hooks:
        - type: command
          command: "~/.claude/hooks/audit.sh"
          timeout: 10

  SessionStart:
    - hooks:
        - type: http
          url: "https://hooks.example.com/start"
        - type: mcp
          tool: "log_session"

  PostToolUse:
    - matcher: ".*"
      hooks:
        - type: prompt
          prompt: "操作已完成 $TOOL_NAME"
```

### 数据结构

```kotlin
data class HookConfig(
    val hooks: Map<String, List<HookMatcherEntry>> = emptyMap()
)

data class HookMatcherEntry(
    val matcher: String? = null,       // 工具名正则，null/空 = 全部匹配
    val hooks: List<HookEntry> = emptyList()
)

data class HookEntry(
    val type: String,                   // command / http / mcp / prompt
    val command: String? = null,        // command 类型专用
    val url: String? = null,            // http 类型专用
    val method: String = "POST",        // http 方法
    val tool: String? = null,           // mcp 类型专用
    val prompt: String? = null,         // prompt 类型专用
    val timeout: Int = 60               // 超时秒数
)

data class HookEventContext(
    val event: String,                  // PreToolUse / PostToolUse / SessionStart / ...
    val tool_name: String? = null,      // 当前工具名（PreToolUse/PostToolUse）
    val tool_input: Map<String, Any>? = null,
    val session_id: String,
    val project_dir: String? = null,
    val transcript_path: String? = null
)

data class HookDecision(
    val permissionDecision: String? = null,  // "allow" / "deny" (仅 PreToolUse/PostToolUse)
    val hookSpecificOutput: Map<String, Any>? = null,
    val content: String? = null              // 注入到 Agent 上下文的文本
)
```

## 事件列表

| 类别 | 事件 | 触发时机 | 输入上下文 |
|------|------|---------|-----------|
| 生命周期 | `SessionStart` | ChatViewModel 初始化后 | session_id, project_dir |
| 生命周期 | `SessionEnd` | clearConversation / dispose | session_id, transcript_path |
| 生命周期 | `Stop` | Agent stopGeneration | session_id |
| 工具拦截 | `PreToolUse` | 工具执行前 | tool_name, tool_input |
| 工具拦截 | `PostToolUse` | 工具执行后 (含结果) | tool_name, tool_input, tool_result |
| 用户交互 | `UserPromptSubmit` | 用户发送消息时 | message_content |
| 用户交互 | `PermissionRequest` | 审批卡弹出时 | tool_name |
| 用户交互 | `Notification` | 系统通知 | message |
| 子代理 | `SubagentStart` | 子 Agent 启动前 | agent_type, description |
| 子代理 | `SubagentStop` | 子 Agent 完成时 | agent_type, result |
| 系统 | `PreCompact` | compact 压缩对话前 | token_count |
| 系统 | `PreCompact` | compact 压缩对话后 (备选) | token_count_new |
| 文件 | `FileChanged` | Write/Edit 后 | file_path |

## 4 种 Hook 类型

### command

```
HookExecutor 启动本地进程
  stdin: HookEventContext JSON
  stdout: HookDecision JSON (或纯文本)
  exitCode: 0=新配置，非0=可能deny
  timeout: destroyForcibly 后按超时处理
```

### http

```
HookExecutor 发 HTTP POST
  body: HookEventContext JSON
  response: HookDecision JSON (或纯文本)
  timeout: 连接+读取超时
```

### mcp

```
HookExecutor 调 MCP 工具
  params: HookEventContext JSON
  返回值: HookDecision
```

### prompt

```
HookExecutor 不做任何执行动作
  hook.prompt 直接注入 system prompt（$TOOL_NAME 等变量替换后）
```

## Hook 执行模型

```
HookEventBus.fire(event, context)
    ↓
HookMatcher.match(event, tool_name) → List<HookMatcherEntry>
    ↓
并行执行所有匹配的 HookEntry:
    ├── CommandHook.execute()
    ├── HttpHook.execute()
    ├── McpHook.execute()
    └── PromptHook.inject()    (立即返回，不阻塞)
    ↓
CountDownLatch 等所有完成 (除 prompt 外)
    ↓
HookDecision.merge(listOf(HookDecision)):
    ├── 有 deny → 返回 deny (阻止操作)
    ├── 全部 allow → 返回 allow + 收集 content
    └── 其他 → 返回 allow
    ↓
PreToolUse 返回 deny → 工具执行跳过，返回 ToolResult.err
PostToolUse 的 content → 注入下一轮 Agent 上下文
```

## 目录结构

```
hooks/ (新增包)
├── HookConfig.kt              — HookConfig/HookMatcherEntry/HookEntry 数据模型
├── HookEventContext.kt        — HookEventContext/HookDecision 事件模型
├── HookConfigLoader.kt        — JSON/YAML 加载 + 合并
├── HookMatcher.kt             — 正则匹配
├── HookEventBus.kt            — 事件总线(单例)
├── HookExecutor.kt             — 调度：构建 stdin → 分发 4 种 hook → 收集结果
├── HookDecisionMerger.kt      — 合并多 hook 结果
└── types/
    ├── CommandHookRunner.kt   — ProcessBuilder 执行
    ├── HttpHookRunner.kt      — HTTP POST
    ├── McpHookRunner.kt       — MCP 工具调用
    └── PromptHookRunner.kt    — 变量替换 + prompt 返回
```

## 集成点

| 位置 | 改动 |
|------|------|
| `AgentContext` | 新增 `hookEventBus: HookEventBus` |
| `AgentLoop` | PreToolUse/PostToolUse fire + deny 处理 |
| `ChatViewModel` | SessionStart/SessionEnd/Stop fire |
| `ChatToolWindow` | 初始化时加载配置 |
| `SubAgentRegistry` | SubagentStart/SubagentStop fire |
| `TaskTool/WorkflowTool` | 子代理 fire |
| `AppSettingsService` | hooks 配置读写 |

## 边界条件 & 故障处理

| 场景 | 处理 |
|------|------|
| hook 脚本超时 | destroyForcibly，记录日志；PreToolUse 视为 deny |
| hook 返回非 0 | PreToolUse 视为 deny；其他事件记录日志继续 |
| stdout 非合法 JSON | 按纯文本处理——注入 Agent 上下文 content |
| stdin JSON 太大 | tool_input 截断 10KB |
| 多 hook 并发 | CountDownLatch 等全部完成 |
| 配置语法错误 | 加载时记录日志，跳过该条目 |
| 多 hook 有 deny 和 allow | deny 优先（merge 逻辑） |
| matcher 为空 | 匹配所有 |
| HTTP/MCP 网络不可用 | catch 异常，记录日志，不阻断 |

## 测试

| 层级 | 对象 | 内容 |
|------|------|------|
| 单元 | HookConfigLoader | JSON/YAML 正确合并 + 语法错误跳过 |
| 单元 | HookMatcher | 正则匹配/不匹配/空 matcher |
| 单元 | HookDecisionMerger | deny/allow/content 合并逻辑 |
| 单元 | PromptHookRunner | $TOOL_NAME 变量替换 |
| 单元 | CommandHookRunner | stdin 构建 + stdout 解析 |
| 集成 | HookEventBus | fire → match → execute → merge 完整链路 |
