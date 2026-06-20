# Code Assistant - 技术方案文档

> 最后更新: 2026-06-20 | 基于实际代码交叉验证

## 1. 架构设计

### 1.1 分层架构

```
┌──────────────────────────────────────────────────────────────────┐
│                       UI 层 (Swing + IntelliJ)                    │
│  ChatToolWindow(1900行) · BubbleFactory · ToolRowFactory          │
│  ReviewResultPanel · PlanBar · MarkdownRenderer · PathUtils       │
├──────────────────────────────────────────────────────────────────┤
│                     ViewModel 层                                   │
│  ChatViewModel (Activity 状态机 · 回调管理 · memory/hooks 接入)    │
├──────────────────────────────────────────────────────────────────┤
│                      Agent 层                                      │
│  AgentLoop(1100行) · AgentContext · ToolRegistry                  │
│  SubAgentRegistry · SkillEngine · RulesEngine · DiagnosticFeedback │
├──────────────────────────────────────────────────────────────────┤
│                      功能模块层                                     │
│  memory/  review/  security/  hooks/  commands/  session/          │
├──────────────────────────────────────────────────────────────────┤
│                     SDK 适配层                                      │
│  AnthropicSdkClient (官方 Java SDK 封装, Anthropic Messages API)    │
├──────────────────────────────────────────────────────────────────┤
│                      工具层                                         │
│  21 内置工具 · Memory 工具(4) · MCP 工具 · 7 元工具                 │
│  McpManager/McpClient (stdio+HTTP JSON-RPC 2.0)                   │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 数据流

```
用户输入 / 斜杠命令 / 右键 Action
  │
  ▼
ChatToolWindow.sendMessage()
  │ 拼接引用内容 + 图片
  ▼
ChatViewModel.sendMessage(apiKey, content, images, refChips)
  │ 创建 AgentMessage → AgentLoop.run()
  ▼
AgentLoop.run() [Agent 后台线程]
  │
  ├─► 注入 pendingDiagnostics + 子Agent 结果 + Hook 内容
  │
  └─► while (loopCount < MAX_LOOPS && !cancelled)
       │
       ├─► buildSystemPrompt() (含 Memory/Skills/Rules/Hooks/Prompt)
       │
       ├─► callAnthropic() → AnthropicSdkClient.createStreaming()
       │     │ Anthropic Java SDK → DeepSeek Anthropic 兼容端点
       │     │ SSE 事件流: TextDelta / ThinkingDelta / ToolUse / MessageStop
       │     │ 回填: textContent + thinking + toolCalls + tokens
       │
       ├─► 无工具调用 → callback(textContent, thinking) → 结束
       │
       └─► 有工具调用 → 逐个执行:
             │
             ├─► PreToolUse Hook → 可 deny 阻止
             ├─► 安全检查 (SAFE_TOOLS / 白名单 / Skill allowed-tools)
             ├─► 非安全工具 → CountDownLatch 阻塞等用户确认
             ├─► ToolRegistry.executeTool() → 返回 ToolResult
             ├─► PostToolUse Hook → 通知
             ├─► DiagnosticFeedback (write/edit 后注入 IDE Inspections)
             ├─► 结果回填 history (synchronized with historyLock)
             └─► EDT 回调 → rebuildConversation() 增量渲染
```

## 2. 核心模块

### 2.1 AgentLoop (`agent/AgentLoop.kt`, ~1100 行)

**职责**: Agent 主循环，模型调用调度，工具分发与安全控制，Hooks 集成。

**核心流程**: MAX_LOOPS=Int.MAX_VALUE(不限制轮次), MAX_FAILURES=3, MAX_CONTINUATIONS=5。

**安全模型**:
- `SAFE_TOOLS`: search_code, read_file, list_directory, git_diff, git_log, git_status, web_search, web_fetch, task, ask_user, code_intelligence, memory_read, memory_list
- 用户白名单: `AppSettingsService.getToolWhitelist()`
- 非安全工具: CountDownLatch + onConfirmTool → UI 审批卡
- PreToolUse Hook: 可 deny 阻止工具执行

**元工具** (7 个, 由 buildSdkToolDefs 硬编码): Skill, EnterPlanMode, ExitPlanMode, TaskCreate, TaskUpdate, TaskList, TaskGet

**线程安全**: conversationHistory 用 synchronizedList + historyLock 双层保护，所有 history.add() 在 synchronized(ctx.historyLock) 内。

### 2.2 AgentContext (`agent/AgentContext.kt`)

**核心字段**: toolRegistry, memoryEngine, hookEventBus, conversationHistory, tasks, planMode, skillDefs, rules, goal, pendingDiagnostics, tokenStats。

**Task 系统**: Task(id, subject, description, status, result) + TaskStatus(PENDING/IN_PROGRESS/COMPLETED)。TaskCreate/Update/List/Get 元工具操作。

### 2.3 ToolRegistry (`agent/ToolRegistry.kt`)

统一管理内置 + MCP 工具，支持 registerBuiltIn/registerMcp/replaceMcp。内置可传 memoryEngine 参数决定是否注册 Memory 工具。

### 2.4 新增功能模块

| 模块 | 文件数 | 职责 |
|------|--------|------|
| `agent/memory/` | 4 | MemoryStore(文件读写+YAML), MemoryEngine, MemoryRelevance, MemoryAutoExtract |
| `review/` | 5 | ReviewEngine(LLM审查), DiffCollector, ReviewAnalyzer, FixApplier, CommentFormatter |
| `security/` | 5 | SecurityReviewEngine + InjectionScanner/SecretDetector/PermissionAnalyzer/DependencyChecker |
| `hooks/` | 11 | HookConfigLoader, HookEventBus, HookExecutor + 4种Runner + 配置模型 |
| `commands/` | 2 | ReviewCommands(5命令action), TestRunner |

### 2.5 DiagnosticFeedback (`agent/DiagnosticFeedback.kt`)

Write/Edit 工具执行后自动收集 IntelliJ Inspection 诊断结果（DaemonCodeAnalyzerImpl.getHighlights），注入到 ctx.pendingDiagnostics，下一轮 user 消息前追加到 `## 代码诊断` 段落。

## 3. 通信协议

### 3.1 AnthropicSdkClient (`AnthropicSdkClient.kt`)

**SDK**: `com.anthropic:anthropic-java:2.40.1`（官方 Java SDK）。

**端点**: `https://api.deepseek.com/anthropic`（DeepSeek Anthropic 兼容 Messages API）。

**关键处理**:
- `mergeConsecutiveSameRole`: 合并连续同 role 消息为单个 MessageParam（跳过 tool_result/tool_use）
- `buildSdkMessage`: AnthropicMessage → SDK MessageParam（含 thinking/text/tool_use block）
- 多 tool_use 不合并（toolUseId != null 的消息独立保留）
- JSON 解析失败时跳过该 tool_use（return null）而非发送空参数

### 3.2 AnthropicAdapter (`AnthropicAdapter.kt`)

仅保留 `AnthropicMessage` 数据类和 `ParsedEvent` sealed class（供历史参考和测试），HTTP/SSE 层已迁移到 SDK。

## 4. 工具系统

### 4.1 内置工具 (21 个)

| 工具 | 权限 | 说明 |
|------|------|------|
| search_code | 只读 | 代码搜索(grep/Java回退) |
| read_file | 只读 | 读文件(支持 resource:// MCP) |
| list_directory | 只读 | 目录列表 |
| git_diff/log/status | 只读 | Git 操作 |
| web_search/fetch | 只读 | 网络搜索/抓取 |
| task | 只读 | 创建子 Agent |
| ask_user | 只读 | 向用户提问 |
| code_intelligence | 只读 | PSI 代码智能(定义/引用/类型/符号/调用层级) |
| write_file | 需确认 | 写文件(原子写入+路径校验) |
| execute_command | 需确认 | Shell 命令(长度限制+危险检测) |
| notebook_edit | 需确认 | Jupyter Notebook 编辑 |
| edit_file | 需确认 | 精确文本替换 |
| workflow | 只读 | 并行/串行子 Agent 编排 |
| mcp_get_prompt | 只读 | MCP Prompt 模板 |

### 4.2 Memory 工具 (4 个)

memory_write(需确认), memory_read(只读), memory_list(只读), memory_delete(需确认)。

### 4.3 CodeIntelligence 扩展

新增 incoming_calls(ReferencesSearch) + outgoing_calls(PsiRecursiveElementVisitor)，无需额外 EP。

## 5. 斜杠命令 (13 个)

/new, /plan, /goal, /init, /review(+--fix+--comment), /test(+--file+--method), /diff(+--stat), /fix(+--retry), /security-review, /stop, /compact, /context, /resume, /export, /clear, /memory

- `/review` 增强: 自动收集 git diff → 四维度 LLM 分析 → 结构化报告。--fix 发 LLM edit_file 修复，--comment 输出 GitHub 评论格式。
- `/test`: 实际执行 ./gradlew test + 解析失败详情(仅报告, 不修复)。
- `/fix`: 读取 /test 缓存，调 LLM 定位根因并修复。

## 6. Hooks 事件系统

对齐 Claude Code Hooks 规范。13 个事件, 4 种 hook 类型。

| 事件 | 触发时机 |
|------|---------|
| SessionStart/End/Stop | 会话生命周期 |
| PreToolUse | 工具执行前(可 deny) |
| PostToolUse | 工具执行后(通知) |
| UserPromptSubmit | 用户发送消息 |
| Notification | 系统通知 |
| SubagentStart/Stop | 子 Agent |
| PreCompact | Compress 前 |

Hook 类型: command(本地脚本, stdin JSON → stdout), http(POST JSON), mcp(MCP 工具调用), prompt(注入 system prompt)。

配置: `~/.claude/settings.json` + `.claude/hooks.yaml` 合并。

## 7. Memory 记忆系统

对齐 Claude Code 文件格式(`~/.claude/memory/` + `<project>/.claude/memory/`)。

- **MemoryStore**: YAML frontmatter 解析/生成, MEMORY.md 索引维护, 原子写入
- **MemoryRelevance**: 中英文关键词提取 + 相关度评分排序
- **MemoryAutoExtract**: clearConversation 时调 LLM 提取关键信息(消息数≥6 才触发, AtomicBoolean 防并发)
- **自动注入**: buildSystemPrompt() 中相关记忆注入 `## 记忆` 段落

## 8. 代码审查系统

- **ReviewEngine**: DiffCollector(收集 git diff) → ReviewAnalyzer(LLM prompt构建+JSON解析) → 4维度分析(正确性/简化/效率/安全)
- **SecurityReviewEngine**: 本地 5 维度扫描(注入/密钥/权限/依赖/不安全API), 纯正则+PSI, 无需 LLM
- **IDE 集成**: ReviewResultPanel(结果面板), ReviewAnnotationGutter(编辑器行号标记), 右键菜单 7 个 Action(审查/安全/修复/解释/优化/注释/测试)

## 9. UI 架构

### 9.1 布局

```
panel (BorderLayout)
├── NORTH  → errorBanner
├── CENTER → conversationPanel (BorderLayout)
│   ├── NORTH  → northStack (header + planBar + goalBar)
│   └── CENTER → conversationCenterPanel (BorderLayout)
│       ├── NORTH  → reviewResultPanel (审查结果面板)
│       └── CENTER → conversationScrollPane
└── SOUTH  → inputPanel (chipPanel + textArea + 发送按钮)
```

### 9.2 增量渲染

AgentMessage.version 递增驱动增量更新——needFullRebuild 或版本号不匹配时原地替换组件，匹配时跳过。

### 9.3 右键菜单 (7 Action)

审查选中代码 / 安全审查此文件 / 修复此代码 / 解释此代码 / 优化此代码 / 生成注释 / 生成单元测试。通过 ReviewActionBridge per-project 桥接到 ChatToolWindow。

## 10. 配置

- **API Key**: IntelliJ PasswordSafe 加密存储
- **模型**: AppSettingsService.getModel()，默认 deepseek-v4-pro
- **MCP**: ~/.claude.json + .mcp.json，stdio/HTTP 双传输
- **Memory 开关**: AppSettingsService.isMemoryEnabled()，默认开
- **Hooks**: settings.json + .claude/hooks.yaml

## 11. 线程安全设计

- conversationHistory: synchronizedList + historyLock 双层保护
- toolRegistry.mcpTools: ConcurrentHashMap
- ctx.tasks: CopyOnWriteArrayList
- pendingApprovals: ConcurrentHashMap
- 所有 UI 回调: runOnEdt → invokeLater 切 EDT
- MemoryEngine.cachedIndex: @Volatile
- extractingMemory: AtomicBoolean 防并发提取
- HookExecutor: ConcurrentLinkedQueue + CountDownLatch + 超时 interrupt

## 12. 项目统计

| 维度 | 数值 |
|------|------|
| 源文件 | 101 个 Kotlin 文件 |
| 代码行数 | ~17,000 行 |
| 测试文件 | 19 个测试类 |
| 包数量 | 14 个包 |
| 内置工具 | 21 个 |
| 元工具 | 7 个 |
| Memory 工具 | 4 个 |
| 斜杠命令 | 13 个 |
| 右键 Action | 7 个 |
| Hook 事件 | 13 个 |
| TODO 覆盖 | 22/22 全部完成 |
