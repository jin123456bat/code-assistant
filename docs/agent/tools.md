# 工具系统

> 关联文档：[Agent Loop](./loop.md)、[Plan Mode](./plan.md)

## 一、13 个内置工具

| 工具                | 参数                                                                                           | 对应 Claude | 实现                  | 上限                                       |
|-------------------|----------------------------------------------------------------------------------------------|-----------|---------------------|------------------------------------------|
| `Read`            | `filePath`, `startLine?`, `endLine?`, `timeout`                                              | Read      | PSI/VFS             | 单次 ≤ 500 行，超出需分页                         |
| `Write`           | `filePath`, `content`, `timeout`                                                             | Write     | WriteCommandAction  | 内容 ≤ 3000 行                              |
| `Edit`            | `filePath`, `oldString`, `newString`, `timeout`                                              | Edit      | WriteCommandAction  | newString ≤ 3000 行                       |
| `Bash`            | `command`, `workDir?`, `timeout`, `dangerous`                                                | Bash      | GeneralCommandLine  | 输出 ≤ 200 行，timeout 必填                    |
| `Glob`            | `dirPath`, `maxDepth?`, `offset?`, `timeout`                                                 | Glob      | VFS + FilenameIndex | ≤ 50 条目，超出需翻页                            |
| `Grep`            | `query`, `filePattern?`, `timeout`                                                           | Grep      | 文件遍历 + 正则           | ≤ 50 条匹配                                 |
| `readLints`       | `filePath`, `timeout`                                                                        | —（扩展工具）   | IDE Inspections     | ≤ 50 条诊断                                 |
| `Agent`           | `prompt`（必填，任务描述）, `timeout`（必填，int，子 Agent 超时秒数，0=不限）, `run_in_background`（必填，bool，true=异步） | Agent     | AgentLoop 复用        | 结果摘要 ≤ 2000 tokens                       |
| `Skill`           | `skill`, `args?`                                                                             | Skill     | SkillManager        | LLM 自主判断触发时机                             |
| `WebSearch`       | `query`, `allowedDomains?`, `blockedDomains?`, `offset?`                                     | —（扩展工具）   | HTTP API            | 搜索网页，返回标题+URL 列表，支持 offset 翻页            |
| `WebFetch`        | `url`, `prompt`                                                                              | —（扩展工具）   | HTTP API            | 抓取 URL 内容并提取信息                           |
| `AskUserQuestion` | `questions[]`（1-4 个，含 options/multiSelect）                                                   | —（扩展工具）   | IDE Dialog          | 向用户发起多选/单选问题以澄清需求                        |
| `Symbol`          | `operation`, `filePath`, `line`, `character`, `query?`                                       | —（扩展工具）   | PSI + StubIndex     | 代码导航（8 种操作）：跳转定义/实现、查引用、类型提示、文件/全局符号、调用链 |

**关键工具行为补充：**

- **Read**：读文件内容，支持行范围。单次最多 500 行，超出用 `startLine` 分页。
- **Write**：覆盖写入整个文件。父目录不存在时自动 `Files.createDirectories()` 创建。用于创建新文件或需要大范围修改时。
- **Edit**：精确字符串替换（old→new），不重写整个文件。执行前校验 `modificationStamp`——如与 Agent
  上次读取时不一致，返回错误提示重新 Read。`oldString` 必须精确匹配且唯一。空 `oldString` +
  文件不存在 = 自动创建新文件（Write 也支持同样语义）。
- **Grep**：支持正则表达式匹配，不区分大小写。非法正则自动回退为字面子串匹配。跳过
  build/、.git/、.idea/、node_modules/
  目录。
- **readLints**：必填参数 `filePath`（项目内相对路径），返回指定文件的 IDE 诊断。
  返回带进度提示的部分结果。最多返回 50 条，按 severity 排序（ERROR > WARNING > INFO）。

### WebSearch

搜索网页，返回匹配结果的标题和 URL 列表。当前仅支持美国区域搜索。

| 特性       | 说明                                                                                       |
|----------|------------------------------------------------------------------------------------------|
| **参数**   | `query`（必填，≥2 字符）、`allowedDomains?`（限定域名）、`blockedDomains?`（排除域名）、`offset?`（翻页起始位置，默认 0） |
| **返回**   | 结果块列表，每个含 `title` + `url`。无结果时返回空列表                                                      |
| **上限**   | ≤ 10 条/页，超出时用 `offset` 翻页获取更多                                                            |
| **翻页**   | 返回值尾部标注 `... (共 N 条，已返回 10 条。用 offset=10 翻页获取更多)`                                        |
| **工作目录** | 不适用（纯网络操作）                                                                               |
| **适用场景** | 查找最新技术文档、API 参考、开源库用法；不适合查询实时数据或需要登录的页面                                                  |

### WebFetch

抓取指定 URL 的网页内容，转为 Markdown 后按 prompt 提取信息。

| 特性       | 说明                                                |
|----------|---------------------------------------------------|
| **参数**   | `url`（必填）、`prompt`（必填，描述要提取的内容）                   |
| **返回**   | 基于 prompt 从页面内容中提取的信息摘要                           |
| **重定向**  | 跨主机重定向返回给 LLM 而非自动跟随，由 LLM 决定是否用新 URL 重新调用        |
| **限制**   | HTTP 自动升级为 HTTPS；不支持需认证/登录的页面；不支持 PDF/二进制文件；不支持缓存 |
| **适用场景** | 阅读官方文档页面、查看 GitHub README、获取技术博客内容                |

### AskUserQuestion

在任务执行过程中向用户发起多选或单选问题，澄清需求或确认方案。

| 特性        | 说明                                                                                                          |
|-----------|-------------------------------------------------------------------------------------------------------------|
| **参数**    | `questions[]`（必填，1-4 个问题），每个问题含 `question`（标题）、`header`（标签 ≤12 字符）、`options[]`（2-4 个选项）、`multiSelect`（是否多选） |
| **选项结构**  | 每选项含 `label`（展示文本 1-5 词）、`description`（说明）                                                                  |
| **返回**    | 用户选择的答案 + 可选备注                                                                                              |
| **UI 行为** | MODAL dialog，阻塞 Agent Loop 等待用户操作                                                                           |
| **适用场景**  | 多个可行方案需要用户选择、需求不明确需要澄清、确认高风险操作前征求同意                                                                         |
| **禁止场景**  | 不要用来问"是否继续"（纯确认）——此类问题 LLM 应自行判断；不要用来逃避决策——技术细节 LLM 应主动选择                                                   |

### Agent

启动子 Agent执行子任务。通过 `run_in_background` 控制同步/异步模式。

| 特性       | 说明                                                                 |
|----------|--------------------------------------------------------------------|
| **参数**   | `prompt`（必填）、`timeout`（必填，int，秒，0=不限）、`run_in_background`（必填，bool） |
| **同步模式** | `run_in_background=false`，父 Agent 阻塞等待子完成，拿到结果后继续                  |
| **异步模式** | `run_in_background=true`，父 Agent 立即返回，子后台执行，完成后回调通知                |
| **返回**   | 同步：直接返回结果摘要；异步：先返回确认，完成后回调写入结果                                     |
| **超时**   | 超过 `timeout` 秒 → 强制终止 → toolResult 返回 `"Sub-agent timeout"`        |
| **并发**   | 见 [多 Agent 协作 §二](../agent/multi-agent.md#二关键约束)                   |
| **嵌套**   | 当前仅 1 层（子不可 spawn 孙）                                               |
| **适用场景** | 同步：需立即用子结果；异步：独立子任务可并行执行                                           |

### Symbol

基于 IntelliJ PSI（Program Structure Interface）提供代码语义导航。无需外部 LSP Server——直接读取 IDE
内存中的语法树和索引。

| 特性       | 说明                                                                                                                                                         |
|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **参数**   | `operation`（必选，见下表）、`filePath`（必填）、`line`（必填，1-based）、`character`（必填，1-based）。`workspaceSymbol` 额外需要 `query`（必填，符号名或部分名），不依赖 `filePath`/`line`/`character` |
| **返回**   | 取决于 operation，见下表                                                                                                                                          |
| **上限**   | 引用/调用数 ≤ 50、符号数 ≤ 100、workspaceSymbol 结果 ≤ 20                                                                                                              |
| **工作目录** | 不适用（纯 PSI 内存操作）                                                                                                                                            |

**八种操作：**

| operation            | PSI 实现                                                                     | 返回内容                                              | Agent 何时用                 |
|----------------------|----------------------------------------------------------------------------|---------------------------------------------------|---------------------------|
| `goToDefinition`     | `PsiElement.navigationElement` → 目标文件+行号                                   | 定义位置：文件路径、行号、所在函数/类名、定义源码片段（前后 3 行）               | "这个方法在哪定义的？"              |
| `goToImplementation` | `ClassInheritorsSearch.search()` / `OverridingMethodsSearch.search()`      | 实现类/方法列表：每项含文件路径、行号、实现源码片段                        | "这个接口/抽象方法有哪些实现？"         |
| `findReferences`     | `ReferencesSearch.search(element, scope)`                                  | 引用列表：每项含文件路径、行号、引用行源码、引用上下文（所在函数/类名）              | "改了这里会影响多少调用者？"           |
| `hover`              | `PsiElement.getReference()?.resolve()` → 类型推断                              | 符号类型、文档注释（KDoc/JavaDoc）、所在函数签名                    | "这个变量是什么类型？"              |
| `documentSymbol`     | `PsiTreeUtil.getChildrenOfType(file, CLASS, FUNCTION, VARIABLE)`           | 文件符号树：类→方法→字段的层级列表，每项含名称、类型、行号                    | "这个文件有哪些函数/类？"            |
| `workspaceSymbol`    | `PsiShortNamesCache.getClassesByName()` + `FilenameIndex.getFilesByName()` | 匹配的符号列表：每项含名称、类型（class/interface/fun/val）、文件路径、行号 | "项目中哪里定义了 `UserService`？" |
| `incomingCalls`      | `CallHierarchyProvider.getCallHierarchyItems()` → 入向                       | 调用者列表：谁调用了这个函数，每项含文件路径、行号、调用上下文                   | "这个函数被哪些地方调用？调用链是什么？"     |
| `outgoingCalls`      | `CallHierarchyProvider.getCallHierarchyItems()` → 出向                       | 被调用者列表：这个函数调用了谁，每项含文件路径、行号、调用上下文                  | "这个函数内部调用了哪些方法？"          |

**operation 参数差异：**

| operation                                                                                                | 需要 filePath/line/character | 需要额外参数                |
|----------------------------------------------------------------------------------------------------------|----------------------------|-----------------------|
| `goToDefinition` / `goToImplementation` / `findReferences` / `hover` / `incomingCalls` / `outgoingCalls` | ✅ 定位光标处的符号                 | 无                     |
| `documentSymbol`                                                                                         | ✅ 只需 `filePath`            | `line`/`character` 忽略 |
| `workspaceSymbol`                                                                                        | ❌ 不需要                      | `query`（必填，符号名）       |

**与 readLints 的分工：**

|      | readLints            | Symbol                                     |
|------|----------------------|--------------------------------------------|
| 做什么  | 代码哪里**报错**了          | 代码**是什么**——定义在哪、谁在用、类型是什么                  |
| 改代码前 | ❌ 不适用（还没改）           | ✅ 先用 `findReferences` 评估影响范围               |
| 改代码后 | ✅ `readLints` 验证编译通过 | ❌ 语义验证不如直接编译                               |
| 探索项目 | ❌ 只看到错误              | ✅ `documentSymbol` + `goToDefinition` 建立理解 |

**典型使用场景：**

1. **改函数签名前**：`findReferences` 看所有调用者 → `incomingCalls` 确认调用链 → 评估联动修改范围
2. **排查编译错误**：`readLints` 发现 `Unresolved reference` → `workspaceSymbol` 搜索符号是否存在 →
   `goToDefinition` 确认定义
3. **新接手项目**：`documentSymbol` 了解文件结构 → `goToDefinition` 深入关键函数 → `outgoingCalls`
   理解调用关系
4. **代码审查**：`hover` 查看 KDoc/JavaDoc → `goToImplementation` 检查实现是否符合接口约定
5. **重构前评估**：`findReferences` 查引用 + `incomingCalls` 查调用链 + `goToImplementation`
   查所有实现 → 完整影响面

## 二、5 个计划管理工具

| 工具             | 参数                      | 用途                                                                                                        |
|----------------|-------------------------|-----------------------------------------------------------------------------------------------------------|
| `createPlan`   | `task` + `plans[]`      | 创建/更新执行计划。`plans[]` 子字段（description/tool/files）详见 [plan.md §九 PlanItem](plan.md#九-planexecutor-接口)，≤ 20 项 |
| `listPlans`    | 无                       | 查看当前计划的所有计划项及状态                                                                                           |
| `removePlan`   | `planId: String`        | 删除指定计划项（仅 PAUSED 状态可删）                                                                                    |
| `reorderPlans` | `planIds: List<String>` | 重排剩余 PAUSED 计划项的执行顺序（传入新的 planId 序列）                                                                      |
| `markPlanDone` | `planId: String`        | 将指定计划项标记为 COMPLETED                                                                                       |

## 三、工具执行分发

每个工具按类型分发到不同线程执行：

| 工具类型                                                              | 线程                                     | 说明                                     |
|-------------------------------------------------------------------|----------------------------------------|----------------------------------------|
| Read / Glob / Grep / ReadLints                                    | Background Thread                      | I/O 操作，不阻塞 EDT                         |
| Write / Edit                                                      | `invokeAndWait { WriteCommandAction }` | 文件写入必须在 EDT 上通过 WriteCommandAction     |
| Bash                                                              | Background Thread                      | 通过 `ProcessHandler` + 实时 `onOutput` 回调 |
| Agent                                                             | `MultiAgentManager.spawn()`            | 子 Agent 在线程池中异步运行，父 Agent 不阻塞          |
| Skill                                                             | Background Thread                      | SKILL.md 正文注入，不涉及文件 I/O                |
| WebSearch / WebFetch                                              | Background Thread                      | HTTP API 调用，不阻塞 EDT                    |
| AskUserQuestion                                                   | `invokeAndWait { Dialog }`             | MODAL dialog 必须在 EDT 上弹出               |
| Symbol                                                            | Background Thread                      | PSI 只读操作，基于 StubIndex 索引               |
| createPlan / listPlans / removePlan / reorderPlans / markPlanDone | PlanExecutor（Agent 线程）                 | 计划管理工具                                 |

**超时机制：** 每个工具都包含必填的 `timeout` 参数（秒），由 LLM 在 tool call 时传入。0=不限。Bash 超时时强制
`destroyForcibly()` 终止进程。其他 I/O 工具的 timeout 由 ToolExecutor 统一读取，目前作为安全兜底。

## 四、工具返回截断策略

> **双重告知：** 每个工具的上限同时在工具描述中声明（事前，LLM 调用前通过 JSON Schema 的 description
> 字段知晓）和返回值截断标注中体现（事后）。两者不可互相替代——事前防止误判，事后确保发现遗漏。

| 工具                | 最大返回行数                           | 截断后行为                                                                   |
|-------------------|----------------------------------|-------------------------------------------------------------------------|
| `Read`            | 500 行                            | 尾部注 `... (共 N 行，已截断到 500 行，如需查看剩余内容请用 startLine 参数分页读取)`                |
| `Grep`            | 50 条匹配                           | 尾部注 `... (共 N 条，已截断到 50 条。如有必要，请使用更精确的搜索词缩小范围)`                         |
| `Glob`            | 50 条目                            | 尾部注 `... (共 N 条目，已截断到 50。用 dirPath/maxDepth 缩小范围，或用 offset={N} 翻页获取更多)` |
| `Bash`            | 200 行 / 4,000 字符 (stdout+stderr) | 中段截断：保留头部 30 行 + 尾部 30 行，中间标注 `... (省略 N 行)`。Bash 不支持翻页（重新执行有副作用）       |
| `readLints`       | 50 条诊断                           | 按 severity 排序（error > warning > info），截断后注 `还有 N 条未显示，优先展示 error 级别`    |
| `Edit`/`Write`    | 写入 ≤ 3000 行                      | 超过拒绝并返回错误 `内容过长 (N 行, 上限 3000 行)`                                       |
| `Agent`           | 返回 ≤ 2000 tokens 摘要              | 完整结果保存到子 session，LLM 看到摘要 + `详情见 sub-session #N`                        |
| `WebSearch`       | ≤ 10 条/页                         | 尾部注 `... (共 N 条，已返回 10 条。用 offset=N 翻页获取更多)`                            |
| `WebFetch`        | 无硬性上限（prompt 提取）                 | 大页面自动截断，不额外标注                                                           |
| `AskUserQuestion` | 无上限（用户输入）                        | 不适用                                                                     |
| `Symbol`          | 引用/调用 ≤ 50，符号 ≤ 100，全局搜索 ≤ 20    | 尾部注 `还有 N 处未显示`。缩小范围请用更精确的查询参数                                          |
| MCP 工具（动态注册）      | 200 行                            | 尾部注 `... (共 N 行，已截断到 200 行)`。MCP 工具由 MCP Server 动态注册，不支持翻页（重新调用有副作用）    |

## 五、Shell 安全

- **timeout 参数必填**（秒），由 LLM 根据命令类型自行判断（编译类 300s，简单命令 30s）。0=不限，但 System
  Prompt 要求 LLM 必须传非 0 值
- **实时流式输出**：`ProcessHandler` listener → batch 100ms → `invokeLater` 更新 UI（防 EDT 洪水）
- **工作目录限定项目根**
- **`dangerous` 参数**：bool 类型，必填。由 LLM 判断当前命令是否为危险命令（如 `rm -rf /`、
  `git push --force`、`sudo`、`chmod 777`）。`dangerous=true`
  时始终弹窗二次确认，无视白名单。详见 [§六 审批机制](#六审批机制)

## 六、审批机制

### 审批触发规则

| 场景                        | 触发条件                                                | 审批类型              |
|---------------------------|-----------------------------------------------------|-------------------|
| 首次工具使用                    | 每个会话每种工具首次调用                                        | 首次审批（可"允许此会话"）    |
| Shell 危险命令                | `rm -rf /`, `git push --force`, `sudo`, `chmod 777` | 危险命令确认（不可跳过）      |
| Bash 危险命令（dangerous=true） | Bash 工具 `dangerous=true`（由 LLM 判断）                  | 始终弹窗确认，不可跳过，无视白名单 |
| 公共 API 变更                 | Edit/Write 修改了方法签名且文件被 ≥3 个其他文件引用                   | 关键操作确认            |
| 大范围修改                     | 同一 turn 修改 ≥5 个文件                                   | 关键操作确认            |
| 文件删除                      | Bash 含 `rm ` 且目标在项目内                                | 关键操作确认            |

**Agent 工具特殊规则：** Agent 工具本身首次使用时需审批（与普通工具一致），但 **Agent
spawn 的子 Agent内部所有工具调用一律放行，无需审批**。子
Agent的工具范围受白名单/黑名单限制，详见 [多 Agent
协作 §三](./multi-agent.md#三子-agent-审批与工具限制)。

### 审批提示行为

审批以**对话内 ToolCallCard 形式**呈现，不弹出独立 Dialog。ToolCallCard 状态变为 `AWAITING_APPROVAL`
时，卡片在消息流中自动展开并显示审批按钮，用户点击后卡片更新为对应状态（DONE/REJECTED）。

- **交互方式**：对话内嵌按钮——[允许一次] / [允许此会话] / [拒绝]，危险命令无"允许此会话"按钮
- **阻塞机制**：ToolCallCard 处于 `AWAITING_APPROVAL` 时不可折叠，Agent Loop 等待用户操作。
  Agent Loop 在后台线程池，不占 EDT；用户操作按钮在 EDT 上，`CountDownLatch` 跨线程同步
- **被拒绝后**：ToolCallCard → REJECTED，发送拒绝 tool_result 给 LLM，由 LLM 判断更换方式重试还是中断
- **无关闭弹窗概念**：审批内嵌在对话中，用户只能选择批准或拒绝，不存在"关闭窗口绕过审批"的路径

### 审批白名单

用户点击 **[允许此会话]** 后，工具名被加入当前 Session 的持久化白名单。后续同一工具调用跳过审批。

**数据结构：**

```
AgentSession:
└── approvedTools: Set<String> = emptySet()   // 已批准的工具名集合，持久化到 Session JSON 的 approvedTools 字段
```

**生命周期：**

| 事件              | 白名单行为                                                     |
|-----------------|-----------------------------------------------------------|
| 用户点击 [允许此会话]    | `approvedTools.add(toolName)`，持久化到 Session JSON           |
| 后续同工具调用         | 检查 `approvedTools.contains(toolName)` → 跳过审批              |
| 用户点击 [允许一次]     | 仅本次放行，不加入白名单                                              |
| `/clear` `/new` | **不受影响**——白名单是会话级配置，`/clear`（`/new` 是其别名）仅清空对话上下文，不重置审批信任 |
| IDE 重启          | 从 Session JSON 恢复白名单，信任关系保留                               |
| 危险命令            | **无视白名单**，始终需二次确认                                         |

**持久化位置：** Session JSON 的 `approvedTools` 字段（`List<String>`），与其他会话数据一同通过
`SessionStore.save()` 原子写入。

**审批判定流程：**

```
工具调用到达 ToolExecutor
  ├─ 危险命令检测（rm -rf、git push --force、sudo、chmod 777）
  │     → 始终提示确认，不检查白名单
  │
  ├─ approvedTools.contains(toolName)?
  │     → 是：跳过审批，直接执行
  │
  └─ 否：ToolCallCard → AWAITING_APPROVAL（对话内嵌按钮）
        ├─ [允许一次]   → DONE，执行，不修改白名单
        ├─ [允许此会话] → DONE，approvedTools.add(toolName) + 持久化，执行
        └─ [拒绝]       → REJECTED，发送拒绝 tool_result 给 LLM
```

**持久化理由：** 审批信任是用户对"当前会话中该工具"的授权。同一次会话跨越 IDE 重启后，信任关系应保留——
用户已在对话中明确授权，重启不应撤销。对齐 Claude Code：`/clear` 重置权限，但关闭窗口再打开权限保留。

## 七、前置校验

`ToolExecutor` 在执行文件操作工具前做前置校验，防止 LLM 幻觉导致非法操作：

| 校验项               | 适用工具                 | 规则                              |
|-------------------|----------------------|---------------------------------|
| 文件存在性             | `Read`、`Edit`（非新建）   | VFS 中不存在 → 拒绝执行，返回错误            |
| Read 前置           | `Edit`、`Write`（覆盖模式） | 当前 turn 中该文件未被 `Read` 过 → 拒绝执行  |
| modificationStamp | `Edit`、`Write`（覆盖模式） | 文件 stamp 与上次 `Read` 时不一致 → 拒绝执行 |

**校验失败恢复规则：**

| 校验失败类型    | 错误消息指引                          | LLM 的恢复动作                        |
|-----------|---------------------------------|----------------------------------|
| 文件不存在     | `"提示：使用 Glob 查看目录结构"`           | 用 `Glob` 确认路径后重新调用               |
| Read 前置   | `"请先用 Read 读取 {filePath} 后再修改"` | 调用 `Read` 读取文件后重新 `Edit`/`Write` |
| stamp 不匹配 | `"请使用 Read 重新读取文件后再试"`          | 调用 `Read` 获取最新内容和 stamp 后重新修改    |

## 八、ToolRegistry 接口

### 类接口

```
ToolRegistry
├── register(name: String, toolClass: Class<*>, info: ToolInfo)  // 注册工具
├── get(name: String): Class<*>?       // 按名查找 tool class
├── listAll(): List<Class<*>>          // 所有已注册工具 class
├── listBuiltin(): List<String>        // 内置工具名称列表
└── toToolDefinitions(): List<String>  // 转为工具名称列表
```

### ToolInfo

```
ToolInfo (ToolRegistry 内部类):
├── name: String                       // Read, Write, ...
├── description: String                // LLM 看到的工具描述，必须包含上限声明
└── usage: String                      // 使用示例
```

### 工具描述中的上限声明

每个工具的 `description` 字段必须包含返回值上限，让 LLM **事前知道**限制：

| 工具                | description 中应包含的上限声明                                                                                                                                                                                     |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Read`            | `单次最多返回 500 行。如果文件行数超过此限制，返回内容会被截断，请使用 startLine 参数分页读取剩余内容。`                                                                                                                                             |
| `Grep`            | `最多返回 50 条匹配。如果匹配数超过此限制，结果会被截断，请使用更精确的搜索词缩小范围。`                                                                                                                                                           |
| `Glob`            | `最多返回 50 个条目（文件+目录）。如果超出此限制，结果会被截断并在返回值中标注。请用 dirPath/maxDepth 缩小范围，或用 offset 参数翻页获取更多条目。`                                                                                                                |
| `Bash`            | `最多返回 200 行输出（stdout+stderr）。超出时中段截断：保留头部 30 行 + 尾部 30 行，中间标注省略行数。Bash 不支持翻页（重新执行有副作用）。timeout 参数由 LLM 根据命令类型自行判断传入（秒），0=不限。`                                                                             |
| `readLints`       | `最多返回 50 条诊断，按 severity 排序（ERROR > WARNING > INFO）。如果超出此限制，低严重度诊断可能不显示。`                                                                                                                                  |
| `Edit`            | `newString 最多 3000 行。超过此限制的操作会被拒绝。`                                                                                                                                                                       |
| `Write`           | `内容最多 3000 行。超过此限制的操作会被拒绝。`                                                                                                                                                                               |
| `Skill`           | `执行指定 Skill。LLM 根据用户需求自主判断触发时机，将 SKILL.md 内容作为消息注入 conversation。`                                                                                                                                         |
| `Agent`           | `启动子 Agent执行子任务。prompt 描述任务，timeout 子 Agent 超时秒数（必填，0=不限），run_in_background 是否异步（必填）。结果摘要最多 2000 tokens。完整执行过程保存为独立 Session。`                                                                             |
| `WebSearch`       | `搜索网页，返回标题和 URL 列表。≤ 10 条/页，超出时用 offset 参数翻页。不支持缓存。`                                                                                                                                                      |
| `WebFetch`        | `抓取 URL 内容并按提示提取信息。HTTP 自动升级为 HTTPS。不支持需认证的页面。不支持缓存。`                                                                                                                                                     |
| `AskUserQuestion` | `向用户发起问题以澄清需求。一次 1-4 个问题，每题 2-4 个选项。支持多选。`                                                                                                                                                                |
| `Symbol`          | `基于 IDE PSI 的语义导航（8 种操作）。goToDefinition 跳转定义、goToImplementation 查实现、findReferences 查引用（≤50）、hover 类型提示、documentSymbol 文件结构（≤100）、workspaceSymbol 全局搜索（≤20，需 query）、incomingCalls/outgoingCalls 调用链（≤50）。` |
| `createPlan`      | `创建执行计划，最多 20 项。计划项初始 PAUSED，随后自动按序执行（PAUSED→EXECUTING→COMPLETED），详见 plan.md。LLM 可用 listPlans/removePlan/reorderPlans/markPlanDone 管理。`                                                                   |
| `listPlans`       | `查看当前计划的所有计划项及状态，无参数。`                                                                                                                                                                                    |
| `removePlan`      | `删除指定计划项（仅 PAUSED 状态可删）。`                                                                                                                                                                                 |
| `reorderPlans`    | `重排剩余 PAUSED 计划项的执行顺序，传入新的 planId 序列。`                                                                                                                                                                    |
| `markPlanDone`    | `将指定计划项标记为 COMPLETED。`                                                                                                                                                                                    |
| MCP 工具            | `由 MCP Server 动态注册，工具名和参数由 Server 声明。输出 ≤ 200 行，不支持翻页。详见 [mcp.md](mcp.md)。`                                                                                                                               |

### 工具模型

工具数据类定义在 `ToolModels.kt`，使用 `@JsonClassDescription` 注解的 data class + `ToolInput.kt`
中的参数类，通过 Anthropic SDK 的 `toolFromClass()` 生成 JSON Schema。所有工具共享 `timeout` 公共字段。

## 九、ToolResult 数据结构

每次工具执行后返回统一的 `ToolResult`，由 `ToolExecutor` 构造，写入 `params.messages` 回传 LLM。

### 接口定义

```
ToolResult:
├── toolUseId: String            // 对应的 tool_use id
├── toolName: String             // 工具名（Read, Write, Edit, ...）
├── content: String              // 返回给 LLM 的内容，截断策略见 §四
├── isError: Boolean = false     // true 时 content 为错误信息
├── success: Boolean             // 工具执行是否成功
├── durationMs: Long             // 执行耗时
└── state: ToolResultState       // DONE | CANCELLED | ERROR

ToolResultState = DONE | CANCELLED | ERROR
```

### 字段说明

| 字段           | 类型      | 说明                                                                                      |
|--------------|---------|-----------------------------------------------------------------------------------------|
| `toolUseId`  | String  | 对应 LLM 请求中的 `tool_use.id`，用于匹配请求和响应                                                     |
| `toolName`   | String  | 工具名称，与 ToolRegistry 注册名一致                                                               |
| `content`    | String  | 返回内容，经过截断处理（各行/字数限制见 §四）。格式遵循 [tool-return-formats.md](../specs/tool-return-formats.md) |
| `isError`    | Boolean | 是否应作为错误呈现给 LLM。`true` 时 content 包含错误描述和恢复提示                                             |
| `success`    | Boolean | 执行是否成功。被拒绝/取消时也为 `false`                                                                |
| `durationMs` | Long    | 执行耗时（毫秒），用于 ToolCallCard 展示                                                             |

### 各工具的结果特征

| 工具           | content 特征                   | isError=true 的触发条件          |
|--------------|------------------------------|-----------------------------|
| Read         | 文件内容 + 头部/尾部标注               | 文件不存在、读取失败                  |
| Write        | 写入成功确认 + 行数/字节数              | 内容过长、写入异常                   |
| Edit         | 修改前后 3 行对比 + 自动 readLints 结果 | oldString 未找到、匹配多处、stamp 冲突 |
| Bash         | 退出码 + stdout/stderr，错误优先格式   | 退出码非零、超时                    |
| Glob         | 目录树 + 条目统计                   | 目录不存在                       |
| Grep         | 匹配行列表 + 文件:行号                | 无（0 条匹配不算错误）                |
| readLints    | 诊断列表（按 severity 排序）          | 索引未就绪                       |
| Agent        | 子任务摘要 + sub-session ID       | 子 Agent 崩溃、超时               |
| Skill        | SKILL.md 正文                  | Skill 不存在                   |
| MCP 工具（动态注册） | MCP Server 返回的原始内容           | MCP 调用失败、Server 断连          |

### 在 Agent Loop 中的流转

```
ToolExecutor.execute(toolUse)
  → 构造 ToolResult(content = ..., isError = ..., success = ...)
  → AgentLoop 将 ToolResult 追加到 params.messages 作为 tool_result 角色
  → LLM 在下一轮 API 调用中看到 tool_result，根据 content/isError 决定下一步
```
