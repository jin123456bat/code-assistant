# 模块交互总图

> 本文档描述 Code Assistant 各模块之间的依赖关系、关键接口契约和调用时序。阅读顺序：先看图，再看接口表，最后看时序。

---

## 一、模块依赖全景图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        UI Layer (ui/)                                │
│                                                                      │
│  ChatToolWindow ──owns──→ TabBar + CardLayout(7 Pages)               │
│       │                                                            │
│       └── ChatPage ──binds──→ ChatViewModel                        │
│              │                    │                                 │
│              │               renderMessage()                        │
│              │               cancelGeneration()                     │
│              ▼                    │                                 │
│       ChatBubbleRenderer          │  sendMessage()                   │
│       ToolCallCard                │  clearSession()                  │
│       PlanCard                    │  rollbackToMessage()             │
│       MultiAgentBlock             │                                  │
│       ChatInputArea                │                                  │
│       审批内嵌卡片 (ToolCallCard)     │                                  │
└───────────────────────────────────┼──────────────────────────────────┘
                                    │
                                    │ 持有引用
                                    ▼
┌───────────────────────────────────────────────────────────────────────┐
│                      Agent Layer (agent/)                              │
│                                                                        │
│  AgentLoop ──owns──→ AgentSession (状态机)                            │
│      │                    │                                           │
│      │              messages[]                                        │
│      │              plan: Plan?                                       │
│      │              approvedTools: Set<String>                        │
│      │              modificationStamps: Map<FilePath, Long>           │
│      │                                                                │
│      ├── stream ──→ AnthropicOkHttpClient (provider/)                 │
│      │                                                                │
│      ├── executeTool() ──→ ToolExecutor ──→ ToolRegistry              │
│      │                         │                │                     │
│      │                    when(toolName)   内置 Tools + MCP Tools     │
│      │                         │                                      │
│      │                    Read/Write/Edit/Bash/Glob/Grep/             │
│      │                    readLints/Agent/Skill/WebSearch/            │
│      │                    WebFetch/AskUserQuestion/Symbol             │
│      │                                                                │
│      ├── /plan ──→ PlanExecutor                                       │
│      │                                                                │
│      └── Agent tool ──→ MultiAgentManager                             │
│                            │                                          │
│                       Semaphore(并发控制)                              │
│                       ConcurrentHashMap<VirtualFile, Lock>(文件写锁)   │
│                       spawn → AgentLoop (子)                          │
│                                                                        │
│  AgentSession ──persisted by──→ SessionStore                          │
└───────────────────────────────────┬───────────────────────────────────┘
                                    │
                                    ▼
┌───────────────────────────────────────────────────────────────────────┐
│                    Session Layer (session/)                            │
│                                                                        │
│  SessionManager ──uses──→ SessionStore                                │
│       │                       │                                       │
│  CRUD + 标题生成          Jackson → .tmp → ATOMIC_MOVE + FileLock     │
│  + Token 聚合             sessions/<uuid>.json                        │
│                           sessions/index.json                         │
└───────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│                   Skills & MCP (skills/ + mcp/)                        │
│                                                                        │
│  SkillManager ──scans──→ .code-assistant/skills/*.md                  │
│       │                                                                │
│       └── injects SKILL.md body into System Prompt                    │
│                                                                        │
│  McpManager ──manages──→ MCP Server processes                         │
│       │                    (stdio subprocess / HTTP+SSE)               │
│       │                                                                │
│       └── registers MCP tools → ToolRegistry                          │
│                                                                        │
│  McpConfigStore ──reads/writes──→ .code-assistant/mcp-config.json     │
└───────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│                Completion Layer (completion/)                          │
│                                                                        │
│  AiCompletionProvider (InlineCompletionProvider)                       │
│       │                                                                │
│       ├── CompletionContextCollector (prefix/suffix/smartContext)      │
│       ├── CompletionCache (LRU, TTL 60s)                               │
│       ├── DeepSeekFimClient → POST /beta/completions                   │
│       └── CompletionPostProcessor (去重/裁剪/截断)                      │
│                                                                        │
│  (独立于 Agent Layer，不共享状态，只共享 API Key)                       │
└───────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│                 Git Message (actions/)                                  │
│                                                                        │
│  GenerateCommitAction (AnAction)                                       │
│       │                                                                │
│       ├── git diff --staged / SimpleDiff fallback                      │
│       └── POST /v1/chat/completions → 流式写入 commit 编辑框            │
│                                                                        │
│  (独立于 Agent Layer，不共享 Session，只共享 API Key)                    │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 二、模块间关键接口

### 2.1 UI → Agent

| 调用方                               | 被调用方            | 方法                           | 线程           | 说明                                  |
|-----------------------------------|-----------------|------------------------------|--------------|-------------------------------------|
| `ChatViewModel`                   | `AgentLoop`     | `run(task)`                  | PooledThread | 发起一次 Agent 对话，返回 `Flow<AgentEvent>` |
| `ChatViewModel`                   | `AgentLoop`     | `cancel()`                   | 任意线程         | 停止当前生成（取消 HTTP + kill 进程）           |
| `ChatViewModel`                   | `AgentSession`  | `messages`                   | EDT（读）       | 绑定到 UI 列表                           |
| `ChatPage`                        | `ChatViewModel` | `sendMessage()`              | EDT          | 用户点击发送                              |
| `ToolCallCard`（AWAITING_APPROVAL） | `ToolExecutor`  | `CountDownLatch.countDown()` | EDT          | 用户在对话内点击审批按钮，结果通过 latch 传回后台线程      |

### 2.2 Agent → Tool

| 调用方            | 被调用方                | 方法                   | 线程           | 说明                   |
|----------------|---------------------|----------------------|--------------|----------------------|
| `AgentLoop`    | `ToolExecutor`      | `execute(toolUse)`   | PooledThread | 执行工具，返回 `ToolResult` |
| `ToolExecutor` | `ToolRegistry`      | `get(name)`          | PooledThread | 查找工具定义               |
| `ToolExecutor` | `MultiAgentManager` | `spawn(prompt)`      | PooledThread | Agent 工具触发子 Agent    |
| `ToolExecutor` | `SkillManager`      | `getSkillBody(name)` | PooledThread | Skill 工具获取正文         |
| `PlanExecutor` | `AgentLoop`         | `run(task)`          | PooledThread | 执行单个计划项              |

### 2.3 Agent → Session

| 调用方              | 被调用方           | 方法              | 线程           | 说明      |
|------------------|----------------|-----------------|--------------|---------|
| `AgentLoop`      | `SessionStore` | `save(session)` | PooledThread | 每轮结束后保存 |
| `SessionManager` | `SessionStore` | `load(id)`      | PooledThread | 恢复会话    |
| `SessionManager` | `SessionStore` | `listAll()`     | PooledThread | 列出所有会话  |

### 2.4 Agent → Provider

| 调用方              | 被调用方                    | 方法                                   | 线程           | 说明        |
|------------------|-------------------------|--------------------------------------|--------------|-----------|
| `AgentLoop`      | `AnthropicOkHttpClient` | `messages().createStreaming(params)` | PooledThread | 流式 API 调用 |
| `SessionManager` | `AnthropicOkHttpClient` | `messages().create(params)`          | PooledThread | 标题生成（非流式） |

### 2.5 Skills → Agent

| 调用方         | 被调用方           | 方法                    | 线程           | 说明                               |
|-------------|----------------|-----------------------|--------------|----------------------------------|
| `AgentLoop` | `SkillManager` | `buildSkillSection()` | PooledThread | 组装 System Prompt 时注入已启用 Skill 列表 |
| `AgentLoop` | `SkillManager` | `getSkillBody(name)`  | PooledThread | Skill 工具被调用时注入正文                 |

### 2.6 MCP → Tool

| 调用方          | 被调用方           | 方法                            | 线程   | 说明             |
|--------------|----------------|-------------------------------|------|----------------|
| `McpManager` | `ToolRegistry` | `register(name, class, info)` | 任意线程 | 动态注册 MCP 工具    |
| `McpManager` | `ToolRegistry` | `unregister(name)`            | 任意线程 | Server 断开时注销工具 |

### 2.7 共享资源

| 资源                     | 持有者                 | 访问者                              | 线程安全                                            |
|------------------------|---------------------|----------------------------------|-------------------------------------------------|
| `AppSettingsService`   | 应用级 Service         | 所有模块                             | `PropertiesComponent` 自身线程安全                    |
| API Key                | `PasswordSafe`      | Agent / Completion / Git Message | IDE 内置安全存储                                      |
| `modificationStamp` 缓存 | `AgentSession`      | `ToolExecutor`                   | 单 AgentLoop 串行访问                                |
| 文件写锁表                  | `MultiAgentManager` | 所有 Agent                         | `ConcurrentHashMap<VirtualFile, ReentrantLock>` |
| `messageBus`           | `Project`           | UI 页面间                           | IntelliJ 内置同步发布                                 |

---

## 三、核心调用时序

### 3.1 用户发送消息（主流程）

```
用户按 Enter
  │
  ▼
ChatInputArea (EDT)
  │
  ▼
ChatViewModel.sendMessage(text, attachments, images) (EDT → PooledThread)
  │
  ├── buildContext(): List<ContentBlock>    // 组装 @file + 选中代码 + 图片
  │
  └── AgentLoop.run(task) (PooledThread)
        │
        ├── buildParams(system + messages + tools)   // System Prompt + 历史消息 + 工具列表
        │
        ├── while (turn < maxTurns):                 // turn 上限 20
        │     │
        │     ├── compact()?                          // 700K tokens 阈值检测
        │     │     └── 是: 独立 API 生成摘要 → compactSummary 追加
        │     │
        │     ├── AnthropicOkHttpClient.messages().createStreaming(params)
        │     │     │
        │     │     ├── ContentBlockDelta.text → invokeLater → ChatBubbleRenderer 流式渲染
        │     │     ├── ContentBlockDelta.reasoning_content → 思考过程块
        │     │     └── ContentBlockStart.toolUse → executeTool()
        │     │           │
        │     │           ├── 审批判定（检查白名单 / 危险命令）
        │     │           ├── ToolCallCard 显示 AWAITING_APPROVAL + 内嵌按钮 (如需要)
        │     │           ├── ToolExecutor.execute(toolUse)
        │     │           │     ├── 前置校验（VFS 存在 / Read 前置 / stamp 一致）
        │     │           │     ├── 执行工具（按类型分发线程）
        │     │           │     └── 返回 ToolResult
        │     │           └── toolResult 追加到 params.messages
        │     │
        │     ├── stop_reason = "end_turn" → break
        │     ├── stop_reason = "max_tokens" → continueStreak++ → 自动续写（≤5 次）
        │     └── stop_reason ≠ "end_turn" → turn++ → 继续循环
        │
        └── SessionStore.save(session)               // 每轮结束持久化
```

### 3.2 Plan 执行流程

```
用户输入 /plan 或 LLM 调用 createPlan
  │
  ▼
PlanExecutor.generatePlan(task) (PooledThread)
  │
  ├── AgentLoop.run("plan-request:" + task)
  │     └── LLM 返回计划文本 → parsePlan() 4 层解析
  │           ├── 1. JSON 代码块 → Gson → Plan
  │           ├── 2. 裸 JSON → Gson → Plan
  │           ├── 3. 正则 /Plan N:/ → 文本拆分 → Plan
  │           └── 4. 原始文本 → 兜底 Plan(single item)
  │
  ├── PlanCard 渲染（invokeLater → EDT）
  │
  └── PlanExecutor.executeNext() 自动循环:
        │
        ├── 获取 nextPlan (PAUSED → EXECUTING)
        ├── AgentLoop.run(plan.summary)
        ├── 更新 plan.status → COMPLETED
        ├── currentPlanIndex++
        ├── 下一项 → 重复
        └── 全部 COMPLETED → PlanCard 消失
```

### 3.3 子 Agent 执行流程

```
父 Agent 调用 Agent(prompt, timeout, run_in_background)
  │
  ▼
ToolExecutor → MultiAgentManager.spawn(prompt, parentSession) (PooledThread)
  │
  ├── Semaphore.acquire()                           // 并发控制
  ├── 创建子 AgentSession(parentId = parentSession.id)
  ├── 子 AgentLoop.run(prompt)
  │     ├── 工具调用一律放行（不弹审批窗）
  │     ├── 工具白名单过滤（Agent/Skill 不可用）
  │     └── 文件写前获取 ReentrantLock
  │
  ├── 完成/超时/crash
  │     ├── 子 Session 持久化
  │     ├── 生成结果摘要（≤ 2000 tokens）
  │     ├── 父 modificationStamp 缓存失效
  │     └── 回调父 Agent → toolResult 写入摘要
  │
  └── Semaphore.release()
```

### 3.4 代码补全流程（独立于 Agent）

```
用户输入代码 (DocumentChange 事件)
  │
  ▼
AiCompletionProvider.isEnabled()? (EDT)
  │ 否 → 跳过
  │
  ▼
getSuggestion() (suspend 协程)
  │
  ├── CompletionContextCollector.collect() (ReadAction)
  │     ├── prefix = 光标前文本
  │     ├── suffix = 光标后文本
  │     ├── ContextEnhancer (兄弟文件 Top-5, Jaccard 相似度)
  │     └── CharBudgetManager (16384 字符预算)
  │
  ├── CompletionCache 命中? → 直接返回 (跳过 API 调用)
  │
  ├── DeepSeekFimClient.complete(prompt, suffix)
  │     └── POST /beta/completions (非流式, 重试 2 次)
  │
  ├── CompletionPostProcessor.process()
  │     └── 去重 / 裁剪重叠 / 截断 incomplete / 过滤空白
  │
  └── InlineCompletionSuggestion → IDE 显示
```

---

## 四、关键设计约束

### 4.1 线程边界

| 边界                      | 规则                                                         |
|-------------------------|------------------------------------------------------------|
| EDT → Background        | `ApplicationManager.executeOnPooledThread()`               |
| Background → EDT        | `invokeLater` (不等待) / `invokeAndWait` (等待，Write/Edit/审批卡片) |
| Background → Background | 直接调用（同线程池）                                                 |
| UI 等待 Background        | `CountDownLatch`（审批卡片按钮等待），无超时                             |

### 4.2 数据所有权

| 数据                   | 所有者                                | 访问模式                          |
|----------------------|------------------------------------|-------------------------------|
| `AgentSession`       | `AgentLoop`（写）/ `ChatViewModel`（读） | 单写多读                          |
| `messages[]`         | `AgentSession`                     | AgentLoop 追加，ChatViewModel 绑定 |
| `approvedTools`      | `AgentSession`                     | 仅 ToolExecutor 读，session 生命周期 |
| `modificationStamps` | `AgentSession`                     | ToolExecutor 读/写，turn 生命周期    |
| 文件写锁表                | `MultiAgentManager`                | 所有 Agent 竞争获取                 |

### 4.3 错误传播

```
工具执行失败
  │
  ├── ToolResult.isError = true
  ├── content = 错误描述 + 恢复提示
  │
  └── AgentLoop 将 ToolResult 追加到 params.messages
        └── LLM 在下一轮看到错误，自行决定修复/重试/放弃

API 调用失败
  │
  ├── 429 (Rate Limit) → 解析 Retry-After → 自动重试 2 次
  ├── 流中断 (IOException) → 保留已接收内容，标注 [连接中断]
  └── 其他错误 → 直接报错给 LLM

Agent Loop 异常
  │
  └── catch → SessionStore.save() 保存已生成内容 → UI 显示错误气泡 + [重试] 按钮
```

---

## 五、文档交叉引用

| 主题               | 文档                                             |
|------------------|------------------------------------------------|
| Agent Loop 详细流程  | [loop.md](../agent/loop.md)                    |
| 工具系统完整规范         | [tools.md](../agent/tools.md)                  |
| Plan Mode        | [plan.md](../agent/plan.md)                    |
| 多 Agent 协作       | [multi-agent.md](../agent/multi-agent.md)      |
| 上下文管理            | [context.md](../agent/context.md)              |
| 线程模型             | [thread-model.md](thread-model.md)             |
| 事件总线             | [event-bus.md](event-bus.md)                   |
| 数据流时序            | [data-flow.md](data-flow.md)                   |
| System Prompt 组装 | [system-prompt.md](system-prompt.md)           |
| 持久化 JSON Schema  | [persistence.md](persistence.md)               |
| 设置项汇总            | [settings.md](settings.md)                     |
| API 错误处理         | [api-error-handling.md](api-error-handling.md) |
| Bash 安全模型        | [bash-security.md](bash-security.md)           |
| MCP 权限模型         | [mcp-permissions.md](mcp-permissions.md)       |
