# 工具系统

> 关联文档：[Agent Loop](./loop.md)、[Plan Mode](./plan.md)

## 一、9 个内置工具

| 工具          | 参数                                              | 对应 Claude | 实现                  | 上限                    |
|-------------|-------------------------------------------------|-----------|---------------------|-----------------------|
| `Read`      | `filePath`, `startLine?`, `endLine?`, `timeout` | Read      | PSI/VFS             | 单次 ≤ 500 行，超出需分页      |
| `Write`     | `filePath`, `content`, `timeout`                | Write     | WriteCommandAction  | 内容 ≤ 3000 行           |
| `Edit`      | `filePath`, `oldString`, `newString`, `timeout` | Edit      | WriteCommandAction  | newString ≤ 3000 行    |
| `Bash`      | `command`, `workDir?`, `timeout`                | Bash      | GeneralCommandLine  | 输出 ≤ 200 行，timeout 必填 |
| `Glob`      | `dirPath`, `maxDepth?`, `offset?`, `timeout`    | Glob      | VFS + FilenameIndex | ≤ 50 条目，超出需翻页         |
| `Grep`      | `query`, `filePattern?`, `timeout`              | Grep      | 文件遍历 + 正则           | ≤ 50 条匹配              |
| `readLints` | `filePath`, `timeout`                           | —（扩展工具）   | IDE Inspections     | ≤ 50 条诊断              |
| `Task`      | `task`, `timeout`                               | Task      | AgentLoop 复用        | 结果摘要 ≤ 2000 tokens    |
| `Skill`     | `skill`, `args?`                                | Skill     | SkillManager        | LLM 自主判断触发时机          |

**关键工具行为补充：**

- **Read**：读文件内容，支持行范围。单次最多 500 行，超出用 `startLine` 分页。
- **Edit**：精确字符串替换（old→new），不重写整个文件。执行前校验 `modificationStamp`——如与 Agent
  上次读取时不一致，返回错误提示重新 Read。`oldString` 必须精确匹配且唯一。空 `oldString` +
  文件不存在 = 自动创建新文件（Write 也支持同样语义）。
- **Grep**：支持正则表达式匹配，不区分大小写。非法正则自动回退为字面子串匹配。跳过 build/、.git/、.idea/
  目录。
- **readLints**：必填参数 `filePath`（项目内相对路径），返回指定文件的 IDE 诊断。项目未索引完成 →
  返回带进度提示的部分结果。最多返回 50 条，按 severity 排序（ERROR > WARNING > INFO）。

## 二、5 个计划管理工具

| 工具             | 参数                      | 用途                                   |
|----------------|-------------------------|--------------------------------------|
| `createPlan`   | `task` + `plans[]`      | 创建/更新执行计划，自动开始执行，≤ 20 项              |
| `listPlans`    | 无                       | 查看当前计划的所有计划项及状态                      |
| `removePlan`   | `planId: String`        | 删除指定计划项（仅 PAUSED 状态可删）               |
| `reorderPlans` | `planIds: List<String>` | 重排剩余 PAUSED 计划项的执行顺序（传入新的 planId 序列） |
| `markPlanDone` | `planId: String`        | 将指定计划项标记为 COMPLETED                  |

## 三、工具执行分发

每个工具按类型分发到不同线程执行：

| 工具类型                                                              | 线程                                     | 说明                                     |
|-------------------------------------------------------------------|----------------------------------------|----------------------------------------|
| Read / Glob / Grep / ReadLints                                    | Background Thread                      | I/O 操作，不阻塞 EDT                         |
| Write / Edit                                                      | `invokeAndWait { WriteCommandAction }` | 文件写入必须在 EDT 上通过 WriteCommandAction     |
| Bash                                                              | Background Thread                      | 通过 `ProcessHandler` + 实时 `onOutput` 回调 |
| Task                                                              | `MultiAgentManager.spawn()`            | 子 Agent 在线程池中运行                        |
| createPlan / listPlans / removePlan / reorderPlans / markPlanDone | PlanExecutor（Agent 线程）                 | 计划任务管理                                 |

**超时机制：** 每个工具都包含必填的 `timeout` 参数（秒），由 LLM 在 tool call 时传入。0=不限。Bash 超时时强制
`destroyForcibly()` 终止进程。其他 I/O 工具的 timeout 由 ToolExecutor 统一读取，目前作为安全兜底。

## 四、工具返回截断策略

> **双重告知：** 每个工具的上限同时在工具描述中声明（事前，LLM 调用前通过 JSON Schema 的 description
> 字段知晓）和返回值截断标注中体现（事后）。两者不可互相替代——事前防止误判，事后确保发现遗漏。

| 工具             | 最大返回行数                | 截断后行为                                                                   |
|----------------|-----------------------|-------------------------------------------------------------------------|
| `Read`         | 500 行                 | 尾部注 `... (共 N 行，已截断到 500 行，如需查看剩余内容请用 startLine 参数分页读取)`                |
| `Grep`         | 50 条匹配                | 尾部注 `... (共 N 条，已截断到 50 条。如有必要，请使用更精确的搜索词缩小范围)`                         |
| `Glob`         | 50 条目                 | 尾部注 `... (共 N 条目，已截断到 50。用 dirPath/maxDepth 缩小范围，或用 offset={N} 翻页获取更多)` |
| `Bash`         | 200 行 (stdout+stderr) | 中段截断：保留头部 30 行 + 尾部 30 行，中间标注 `... (省略 N 行)`。Bash 不支持翻页（重新执行有副作用）       |
| `readLints`    | 50 条诊断                | 按 severity 排序（error > warning > info），截断后注 `还有 N 条未显示，优先展示 error 级别`    |
| `Edit`/`Write` | 写入 ≤ 3000 行           | 超过拒绝并返回错误 `内容过长 (N 行, 上限 3000 行)`                                       |
| `Task`         | 返回 ≤ 2000 tokens 摘要   | 完整结果保存到子 session，LLM 看到摘要 + `详情见 sub-session #N`                        |
| MCP 工具         | 200 行                 | 尾部注 `... (共 N 行，已截断到 200 行)`。MCP 工具不支持翻页（重新调用有副作用）                      |

## 五、Shell 安全

- **timeout 参数必填**（秒），由 LLM 根据命令类型自行判断（编译类 300s，简单命令 30s）。0=不限，但 System
  Prompt 要求 LLM 必须传非 0 值
- **实时流式输出**：`ProcessHandler` listener → batch 100ms → `invokeLater` 更新 UI（防 EDT 洪水）
- **工作目录限定项目根**
- **危险模式二次确认**：`rm -rf /`、`git push --force`、`sudo`、`chmod 777`（不可跳过）。正则检测定位为"
  安全提醒"非"安全防线"——真正边界是项目根目录限定 + 完全透明

## 六、审批机制

### 审批触发规则

| 场景         | 触发条件                                                | 审批类型           |
|------------|-----------------------------------------------------|----------------|
| 首次工具使用     | 每个会话每种工具首次调用                                        | 首次审批（可"允许此会话"） |
| Shell 危险命令 | `rm -rf /`, `git push --force`, `sudo`, `chmod 777` | 危险命令确认（不可跳过）   |
| 公共 API 变更  | Edit/Write 修改了方法签名且文件被 ≥3 个其他文件引用                   | 关键操作确认         |
| 大范围修改      | 同一 turn 修改 ≥5 个文件                                   | 关键操作确认         |
| 文件删除       | Bash 含 `rm ` 且目标在项目内                                | 关键操作确认         |

### 审批弹窗行为

- **类型**：MODAL（阻塞父窗口），可拖动
- **按钮**：首次审批场景有 [允许一次] / [允许此会话] / [拒绝]；危险命令无"允许此会话"按钮
- **超时**：无超时（`CountDownLatch` 永久等待）。Agent Loop 在后台线程池，阻塞不占 EDT；审批弹窗是模态
  Dialog，用户离开前必须处理
- **被拒绝后**：ToolCallCard 保留 [重审] 按钮，用户点击后从 IDLE 重新进入 AWAITING_APPROVAL，无需重新发送消息

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

| 工具             | description 中应包含的上限声明                                                                                                         |
|----------------|-------------------------------------------------------------------------------------------------------------------------------|
| `Read`         | `单次最多返回 500 行。如果文件行数超过此限制，返回内容会被截断，请使用 startLine 参数分页读取剩余内容。`                                                                 |
| `Grep`         | `最多返回 50 条匹配。如果匹配数超过此限制，结果会被截断，请使用更精确的搜索词缩小范围。`                                                                               |
| `Glob`         | `最多返回 50 个条目（文件+目录）。如果超出此限制，结果会被截断并在返回值中标注。请用 dirPath/maxDepth 缩小范围，或用 offset 参数翻页获取更多条目。`                                    |
| `Bash`         | `最多返回 200 行输出（stdout+stderr）。超出时中段截断：保留头部 30 行 + 尾部 30 行，中间标注省略行数。Bash 不支持翻页（重新执行有副作用）。timeout 参数由 LLM 根据命令类型自行判断传入（秒），0=不限。` |
| `readLints`    | `最多返回 50 条诊断，按 severity 排序（ERROR > WARNING > INFO）。如果超出此限制，低严重度诊断可能不显示。`                                                      |
| `Edit`         | `newString 最多 3000 行。超过此限制的操作会被拒绝。`                                                                                           |
| `Write`        | `内容最多 3000 行。超过此限制的操作会被拒绝。`                                                                                                   |
| `Skill`        | `执行指定 Skill。LLM 根据用户需求自主判断触发时机，将 SKILL.md 内容作为消息注入 conversation。`                                                             |
| `Task`         | `子 Agent 结果摘要最多 2000 tokens。完整执行过程保存为独立 Session。`                                                                             |
| `createPlan`   | `创建执行计划，最多 20 项。计划创建后自动开始执行，LLM 可用 listPlans/removePlan/reorderPlans 管理。`                                                     |
| `listPlans`    | `查看当前计划的所有计划项及状态，无参数。`                                                                                                        |
| `removePlan`   | `删除指定计划项（仅 PAUSED 状态可删）。`                                                                                                     |
| `reorderPlans` | `重排剩余 PAUSED 计划项的执行顺序，传入新的 planId 序列。`                                                                                        |
| `markPlanDone` | `将指定计划项标记为 COMPLETED。`                                                                                                        |

### 工具模型

工具数据类定义在 `ToolModels.kt`，使用 `@JsonClassDescription` 注解的 data class + `ToolInput.kt`
中的参数类，通过 Anthropic SDK 的 `toolFromClass()` 生成 JSON Schema。所有工具共享 `timeout` 公共字段。
