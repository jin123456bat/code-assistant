# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

PhpStorm 的开源 AI 编程 Agent 插件（IntelliJ Platform plugin，type `PS`）。基于 Kotlin + Swing，通过 DeepSeek 的 **Anthropic 兼容 Messages API**（`/anthropic/v1/messages`）驱动一个可自主调用工具的 Agent 循环。用户自带 DeepSeek API Key。

## 常用命令

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox PhpStorm（autoReloadPlugins=true：改代码后重新编译即热加载，无需重启）
./gradlew test             # 运行全部 JUnit 测试
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest"           # 单个测试类
./gradlew test --tests "com.aiassistant.AnthropicAdapterTest.方法名"     # 单个测试方法
```

环境：JVM 17、Kotlin 1.9.22、IntelliJ Platform 2023.3（PhpStorm）、Gradle IntelliJ Plugin 1.17.4。

## 架构（big picture）

请求从上到下的调用链：

```
ChatToolWindowFactory → ChatToolWindow (Swing UI, ~1272 行)
    → ChatViewModel  (UI 桥接，轻量 ViewModel)
        → AgentLoop (agent_v3，真正的 Agent 循环)
            → AnthropicAdapter (构建请求 / 解析 SSE 事件)
            → SseClient        (流式 HTTP)
            → ToolRegistryV3   (工具分发)
```

**Agent 循环**（`agent_v3/AgentLoop.kt`，核心）：在后台 `Thread` 上跑 `while` 循环（`MAX_LOOPS=100`，连续失败 `MAX_FAILURES=3` 即中止）。每轮调用模型 → 若返回 `tool_use` 则执行工具并把结果回填到 `history` 继续下一轮 → 若返回纯文本则结束。所有 UI 更新通过回调（`onMessage`/`onStreaming`/`onToolExecute`…）经 `invokeLater`/`invokeAndWait` 切回 EDT。

**两个 agent 包，注意区分：**
- `agent/`（旧）：仅保留**共享类型**——`AgentTool` 接口、`ToolParameter`、`ToolResult`、`ModelRouter`、`SystemPromptBuilder`。其中 `ToolRegistry` 和 `AgentTool.toFunctionJson()` 已 `@Deprecated`，仅供测试兼容。
- `agent_v3/`（当前生效）：`AgentLoop`、`AgentContext`（贯穿生命周期的共享上下文，含 `Plan`/`Step`）、`ToolRegistryV3`、`Planner`、`SkillEngine`。新代码走这里。

**适配器：`AnthropicAdapter` 是生效的适配器**（Anthropic Messages 格式 + `input_schema` 工具）。`DeepSeekAdapter.kt`（OpenAI function-calling 格式）是遗留死代码，除自身测试外无任何引用——不要在新功能里使用它。

**工具系统**：`tools/` 下每个工具实现 `agent.AgentTool` 接口（`name` / `description` / `parameters` / `execute()`）。8 个内置工具由 `ToolRegistryV3.registerBuiltIn()` 注册：`search_code`、`read_file`、`write_file`、`list_directory`、`execute_command`、`git_diff`、`git_log`、`git_status`。三类工具来源统一管理：内置 / MCP（`registerMcp`）/ Skill（`registerSkills`）。`buildToolsJson()` 生成 Anthropic `input_schema` 并**带缓存**，注册新工具时通过 `invalidateCache()` 失效。

**安全模型**：`AgentLoop.SAFE_TOOLS`（只读工具）+ 用户白名单 `AppSettingsService.getToolWhitelist()` 内的工具直接执行；其余工具（`write_file`、`execute_command` 等）触发内联确认——`onConfirmTool` 回调配合 `CountDownLatch`/`AtomicBoolean` 阻塞等待用户在 UI 上点确认。

**MCP**（`mcp/`）：`McpManager` 连接 `.code-assistant/mcp.json`（项目级）和全局配置里声明的 MCP server，`McpClient` 走 stdio JSON-RPC，把远程 tool 包装成 `AgentTool` 注入注册中心。

**Skills**（`agent_v3/SkillEngine.kt`）：扫描 `<project>/.claude/skills/**/SKILL.md` 和 `~/.claude/skills/**/SKILL.md`，把每个 skill 包装成一个特殊 `AgentTool`。

**Plan / Model 路由**：`Planner.shouldPlan()` 按关键词或 >200 字符判定复杂任务并生成步骤计划；`ModelRouter.selectModel()` 按关键词在 `deepseek-v4-flash`（默认/快速）与 `deepseek-v4-pro`（复杂推理）间路由。

## 关键约定与坑

- **DeepSeek V4 兼容**：`AnthropicAdapter.buildRequest()` 在每个 `tool_use` content block 前**预置一个空 `thinking` block**（`{"type":"thinking","thinking":""}`），这是 DeepSeek V4 API 的硬性要求，不要删。
- **SSE 解析手写**：`AnthropicAdapter.parseSseEvent()` 和 `extractJsonString()` 用字符串扫描而非 JSON 库解析事件；`SseClient` 已剥离 `data: ` 前缀，传入的是裸 JSON。修改事件格式时两边都要动。
- **工具参数解析用正则**：`AgentLoop.parseParams()` 用正则 `"(\w+)"\s*:\s*(...)` 从模型返回的 JSON 里抽参数，**不是完整 JSON 解析**——嵌套对象/数组参数会失效，新工具尽量用扁平的字符串/数字参数。
- **JSON 转义统一走 `shared/JsonUtils`**：`escapeJson` / `unescapeJson` 在适配器和工具 schema 里复用，不要再就地手写转义。
- **持久化目录**：对话存 `.code-assistant/conversations/current.json`（`ConversationStore`），粘贴图片存 `.code-assistant/images/`。这些目录不进 git。
- **文件写入边界**：`write_file` 限制在项目目录内，防止越界。
- **API Key**：存于 IntelliJ PasswordSafe（CredentialStore），通过 `AppSettingsService` 读写，不落明文。
- **i18n**：用户可见文案走 `messages/AiAssistantBundle*.properties`（含 `_zh`），通过 `AiAssistantBundle` 读取。

## 设计规范

UI 设计 token（配色 / 字号 / 间距 / 圆角 / 交互态）见 `DESIGN.md`。改动聊天面板视觉时遵循其中的语义色与 WCAG AA 对比度约束。
