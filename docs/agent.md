# Agent 智能对话

Agent 模式是 Code Assistant 的核心功能。对齐 Claude Code，支持自主工具调用、Plan Mode、多 Agent 协作、Skill
系统和 MCP 外部工具。

## 功能文档

| 文档                                           | 说明                                                                      |
|----------------------------------------------|-------------------------------------------------------------------------|
| [agent/loop.md](agent/loop.md)               | Agent Loop 主循环 + AgentSession 状态机 + API 错误恢复 + 流式中断 + 项目关闭清理            |
| [agent/tools.md](agent/tools.md)             | 工具系统：18 个工具（13 内置 + 5 计划管理）+ 执行分发 + 审批 + Shell 安全 + 前置校验                |
| [agent/plan.md](agent/plan.md)               | Plan Mode：自动连续执行 + LLM 自主管理 + PlanCard/PlanExecutor + 边界处理              |
| [agent/multi-agent.md](agent/multi-agent.md) | 多 Agent 协作：Agent 工具 + MultiAgentManager + 并发控制 + 子代理审批策略 + 工具白名单 + 文件写锁 |
| [agent/context.md](agent/context.md)         | 上下文管理：三层防线 + Auto-Compact + Token 感知 + max_tokens 自动续写 + /clear         |
| [agent/correctness.md](agent/correctness.md) | 正确性验证体系：防幻觉 + 代码改动验证 + 方案正确性验证                                          |
| [agent/skills.md](agent/skills.md)           | Skill 系统：兼容 Claude Code/Codex + SKILL.md + 交叉验证                         |
| [agent/mcp.md](agent/mcp.md)                 | MCP 支持：JSON-RPC via stdio/HTTP+SSE + Server 生命周期 + 崩溃恢复                 |
| [agent/session.md](agent/session.md)         | 会话持久化 & Token 统计：JSON 原子写入 + Session Index + 标题生成 + Token 聚合            |
| [agent/images.md](agent/images.md)           | 图片支持：粘贴编码 + ContentBlock 组装 + Read 读图片 + 生命周期                           |

## 跨模块规范

| 文档                                                           | 说明                                               |
|--------------------------------------------------------------|--------------------------------------------------|
| [specs/thread-model.md](specs/thread-model.md)               | 线程模型：EDT / PooledThread / Timer / ProcessHandler |
| [specs/event-bus.md](specs/event-bus.md)                     | 事件总线契约：7 个 Topic                                 |
| [specs/data-flow.md](specs/data-flow.md)                     | 数据流时序：用户发送消息 + Plan 执行                           |
| [specs/system-prompt.md](specs/system-prompt.md)             | System Prompt 完整内容 + 组装逻辑                        |
| [specs/tool-return-formats.md](specs/tool-return-formats.md) | 工具返回值格式契约（LLM 看到的内容）                             |
| [specs/token-estimation.md](specs/token-estimation.md)       | Token 估算统一策略                                     |
| [specs/persistence.md](specs/persistence.md)                 | 持久化 JSON Schema（Session + Index + MCP Config）    |
| [specs/settings.md](specs/settings.md)                       | Settings 持久化（AppSettingsService）                 |

## 架构分层

```
┌──────────────────────────────────────────────────────┐
│  UI Layer (多页面架构)                                 │
│  顶部 TabBar: Welcome | Chat | Sessions | TokenUsage    │
│            MCP | Skills | Settings                      │
│  CardLayout 容器切换页面                                 │
├──────────────────────────────────────────────────────┤
│  Chat UI: JTextPane (Markdown→HTML) + EditorTextField │
│  (代码块 block-level 渲染) + ToolCallCard + PlanCard  │
├──────────────────────────────────────────────────────┤
│  Session Layer: SessionManager + SessionStore (JSON)  │
├──────────────────────────────────────────────────────┤
│  Agent Layer: AgentLoop (while 循环) + PlanExecutor  │
│  MultiAgentManager                                     │
├──────────────────────────────────────────────────────┤
│  Tool Layer: ToolRegistry (内置 Tools + MCP Tools)    │
│  Read/Write/Edit/Bash/Glob/Grep/readLints/Agent/Skill/WebSearch/WebFetch/AskUserQuestion/Symbol    │
│  createPlan/listPlans/removePlan/reorderPlans/          │
│  markPlanDone                                           │
├──────────────────────────────────────────────────────┤
│  Provider: AnthropicOkHttpClient → DeepSeek            │
│  Skill System: SkillManager → SKILL.md 扫描            │
│  MCP: McpManager (进程生命周期)                         │
└──────────────────────────────────────────────────────┘
```

### 核心数据流

简化的主流程概览。完整的 turn 计数、compact、轮次预警、审批检查、stop_reason
分叉等细节见 [Agent Loop 主循环](agent/loop.md#一主循环)。

```
用户输入 → AgentLoop.run(task)
  → 构建 params（system + messages + tools）
  → AnthropicOkHttpClient.messages().createStreaming(params)
  → forEach chunk:
      ContentBlockDelta.text → 流式渲染到气泡
      ContentBlockStart.toolUse → executeTool() → 结果追加到 params
  → while 循环直到 stop_reason="end_turn"
  → SessionStore.save()
```

## 核心工具

共 18 个工具：13 个内置 + 5
个计划管理。完整参数、上限、执行线程、审批规则、返回格式详见 [agent/tools.md](agent/tools.md)。

## AgentSession 状态机

详见 [Agent Loop §二](agent/loop.md#二agentsession-状态机)。

## 已知限制

- 仅支持 DeepSeek API（`deepseek-v4-pro`），不支持其他 LLM 提供商
- LLM 可通过 `createPlan` 工具主动创建计划
- 超长任务通过 createPlan 预防 + 轮次预警 + Auto-Compact 兜底管理，复杂度自判
- MCP `resources/list` 和 `prompts/list` v1 不支持
- 多 Agent 嵌套上限 1 层（子不可再 spawn 孙）
- Sessions 全文搜索暂未实现（v1 仅 title 过滤）
- `Grep` 支持正则表达式匹配，不区分大小写。非法正则自动回退为字面子串匹配
- `@file` glob 匹配上限 50 个文件，超出部分由 Glob 工具告知 LLM 截断情况，LLM 自行决定是否翻页
- `readLints` 仅支持单文件诊断

## 配置项

详见 [specs/settings.md](specs/settings.md)。

## 设计决策记录

以下是 Agent 模式设计过程中的关键决策（来源：DESIGN.md）。各决策的详细实现见对应子文档：Loop
相关 → [loop.md](agent/loop.md)、工具/审批 → [tools.md](agent/tools.md)
、Plan → [plan.md](agent/plan.md)、多 Agent → [multi-agent.md](agent/multi-agent.md)
、上下文 → [context.md](agent/context.md)、Skills → [skills.md](agent/skills.md)
、MCP → [mcp.md](agent/mcp.md)、会话 → [session.md](agent/session.md)
、Token → [token-estimation.md](specs/token-estimation.md)
、持久化 → [persistence.md](specs/persistence.md)、System
Prompt → [system-prompt.md](specs/system-prompt.md)、线程 → [thread-model.md](specs/thread-model.md)
、事件总线 → [event-bus.md](specs/event-bus.md)。

| 决策            | 决议                                                     | 理由                                                              |
|---------------|--------------------------------------------------------|-----------------------------------------------------------------|
| LLM 交互        | Anthropic Java SDK + 手写 Agent Loop                     | SDK 封装 HTTP/SSE/重试/Tool Schema，循环控制权在手                          |
| Agent Loop 风格 | 手写 while 循环                                            | IDE 特定逻辑（EDT/审批/中断）框架反而限制可控性                                    |
| Markdown 渲染   | v1 用 bundled plugin，block-level 代码块                    | 已验证：13 个核心 PSI 类型可用，含表格                                         |
| Shell 超时      | LLM 在 tool call 时传入（必填），0=不限                           | LLM 根据命令类型自行判断合理超时                                              |
| 多 Agent 并发    | 3 个上限 + 文件写锁 + modificationStamp                       | 单 Agent 先跑通                                                     |
| Plan          | 自动连续执行，用户可随时暂停。tool 字段为建议非约束                           | 暂停跟随全局停止按钮，PlanCard 仅展示计划项+单项删除                                 |
| 工具审批          | 首次授权后同会话信任，危险命令始终二次确认                                  | 对齐 Claude Code                                                  |
| 页面架构          | 顶部 TabBar + CardLayout，首屏懒加载                           | 7 页全部预创建内存压力大                                                   |
| Skill 系统      | `.code-assistant/skills/` + SKILL.md，正文按需注入            | 避免 context window 浪费                                            |
| MCP           | JSON-RPC 手写 + JSON 配置，崩溃自动重启 1 次                       | 进程不稳定是最大风险                                                      |
| @file 引用      | `@文件名` + FilenameIndex + debounce 200ms                | < 50ms 索引查询，性能可控                                                |
| 会话持久化         | Jackson → .tmp → ATOMIC_MOVE + FileLock                | 防写入中断数据丢失 + 多实例竞态                                               |
| 上下文超限处理       | Auto-Compact：摘要 + 保留近期原文                               | 对齐 Claude Code，避免粗暴截断丢失关键上下文                                    |
| 上下文窗口大小       | 写死 1M tokens（DeepSeek V4 上限）                           | 不需要动态检测，compact 阈值见 [loop.md §六](agent/loop.md#六agentloop-接口定义) |
| max_tokens 续写 | 自动发送"继续"，不持久化，≤5 次                                     | 对齐 Claude Code，高频场景自动处理                                         |
| /clear & /new | 清空当前会话（messages + plan + compactSummary），复用 session.id | 对齐 Claude Code，避免 session 文件堆积                                  |
| 会话标题          | LLM 异步生成（≤20 字，max_tokens=64）                          | 对齐 Claude Code，Sessions 列表可读性                                   |
| 会话清理          | 仅手动：Sessions 页面 `[全选]` + `[删除选中]`，不做自动清理               | 自动清理可能误删日志，无法恢复/回溯                                              |
| Token 估算      | 统一启发式：英文 字节/4，中文 字符×3/2，取 max                          | 多处依赖，统一策略避免偏差                                                   |
| 流式中断处理        | 主动停止保留已渲染内容，被动断连尾部标注 [连接中断]                            | 对齐 Claude Code，数据不丢失                                            |
| 分发            | `./gradlew buildPlugin` → Marketplace 手动上传             | 无 CI/CD 依赖                                                      |

## 项目文件清单

（来源：DESIGN.md）

| 包          | 核心文件                      | 职责                                               |
|------------|---------------------------|--------------------------------------------------|
| `agent/`   | `AgentLoop.kt`            | while 循环：stream → parse → executeTool → feedback |
| `agent/`   | `AgentSession.kt`         | 状态机、消息列表、事件发射                                    |
| `agent/`   | `ToolRegistry.kt`         | 内置 Tools + MCP Tools 统一管理                        |
| `agent/`   | `ToolExecutor.kt`         | 工具执行分发器（when 路由）                                 |
| `agent/`   | `ToolModels.kt`           | 18 个 Tool 数据类 + @JsonClassDescription 注解         |
| `agent/`   | `PlanExecutor.kt`         | /plan 指令执行器                                      |
| `agent/`   | `MultiAgentManager.kt`    | 多 Agent 调度                                       |
| `skills/`  | `SkillManager.kt`         | SKILL.md 扫描/解析/注册                                |
| `mcp/`     | `McpManager.kt`           | MCP Server 生命周期                                  |
| `mcp/`     | `McpConfigStore.kt`       | mcp-config.json 读写                               |
| `session/` | `SessionStore.kt`         | JSON 文件读写                                        |
| `session/` | `SessionManager.kt`       | 会话 CRUD                                          |
| `ui/`      | `AppColors.kt`            | 统一颜色令牌（亮/暗主题）                                    |
| `ui/`      | `ChatToolWindow.kt`       | 主容器：TabBar + CardLayout 路由                       |
| `ui/`      | `TabBar.kt`               | 顶部 TabBar 导航组件                                   |
| `ui/`      | `MessageBus.kt`           | messageBus 事件总线                                  |
| `ui/`      | `SelectionListener.kt`    | 编辑器选中代码实时监听                                      |
| `ui/`      | `OpenChatAction.kt`       | Ctrl+Shift+K 快捷键 Action                          |
| `ui/page/` | `WelcomePage.kt`          | API Key 配置引导                                     |
| `ui/page/` | `ChatPage.kt`             | 消息列表 + 输入框                                       |
| `ui/page/` | `SessionsPage.kt`         | 会话历史列表                                           |
| `ui/page/` | `TokenUsagePage.kt`       | Token 消耗统计                                       |
| `ui/page/` | `McpPage.kt`              | MCP Server 管理                                    |
| `ui/page/` | `SkillsPage.kt`           | Skills 列表                                        |
| `ui/page/` | `SettingsPage.kt`         | 关于页面 + 快捷键参考                                     |
| `ui/chat/` | `ChatBubbleRenderer.kt`   | Markdown → 组件序列                                  |
| `ui/chat/` | `ChatViewModel.kt`        | 消息列表状态 + 流式 buffer                               |
| `ui/chat/` | `ChatInputArea.kt`        | 输入框：Tags 行 + 文本区域 + 底部栏                          |
| `ui/chat/` | `ToolCallCard.kt`         | 工具调用卡片组件（8 状态）                                   |
| `ui/chat/` | `PlanCard.kt`             | 计划卡片组件                                           |
| `ui/chat/` | `RoundedBorder.kt`        | 圆角边框绘制工具                                         |
| `ui/chat/` | `SimpleDiff.kt`           | LCS diff 算法                                      |
| `actions/` | `GenerateCommitAction.kt` | VCS diff → LLM → commit dialog 填充                |

## 技术栈

- Kotlin 2.0.21、JVM 21、IntelliJ Platform 2024.3（IC）
- LLM：Anthropic Java SDK → DeepSeek Anthropic 兼容接口 `https://api.deepseek.com/anthropic`
- 构建：Gradle IntelliJ Platform Plugin 2.2.1
- Markdown 解析：`org.intellij.plugins.markdown` bundled plugin
- 用户自带 DeepSeek API Key
