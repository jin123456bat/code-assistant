# 多页面架构

> 关联文档：[[chat]], [[design-system]], [[../agent/session]]

## 一、页面路由

Code Assistant 采用顶部 TabBar 导航 + CardLayout 容器，共 7 个页面。**TabBar 根据 API Key 状态动态显隐：
**

- **无 API Key**：仅显示 Welcome tab，其他隐藏
- **有 API Key**：隐藏 Welcome tab，显示 Chat / Sessions / Usage / MCP / Skills / Settings

```
无 API Key:                         有 API Key:
┌────────────────────────┐          ┌──────────────────────────────────────────┐
│ [🏠]                   │          │ [💬] [📁] [📊] [🔌] [🎯] [⚙] │
├────────────────────────┤          ├──────────────────────────────────────────┤
│                        │          │                                          │
│  Welcome 页面           │          │  当前页面内容 (CardLayout)                  │
│                        │          │                                          │
└────────────────────────┘          └──────────────────────────────────────────┘
```

| 页面          | 路由          | 说明                                                                                                     |
|-------------|-------------|--------------------------------------------------------------------------------------------------------|
| Welcome     | `/welcome`  | 无 API Key 时的唯一页面。配置 Key 后自动隐藏                                                                          |
| Chat        | `/chat`     | 主聊天面板。Key 已配置时的默认页面                                                                                    |
| Sessions    | `/sessions` | 会话历史列表、搜索、点击恢复会话、删除                                                                                    |
| Token Usage | `/usage`    | Token 消耗统计（按会话/日/月聚合）                                                                                  |
| MCP         | `/mcp`      | MCP Server 管理（添加/启停/测试连接）                                                                              |
| Skills      | `/skills`   | Skills 列表、启用/禁用                                                                                        |
| Settings    | `/settings` | 关于信息、版本、快捷键参考。Agent 相关设置已迁移到 **IDE Settings > Tools > Code Assistant**（SettingsConfigurable），面板内不再重复配置 |

**TabBar 显隐规则：** 无 API Key 时，TabBar 仅显示 Welcome（🏠），其他 Tab 通过 `tab.hidden` CSS 隐藏。保存
Key 后，Welcome Tab 隐藏，其余 6 个 Tab 显示。Key 被清除时恢复初始状态。

**视觉层级（每页视线顺序 ①②③）：**

| 页面       | ①        | ②         | ③        |
|----------|----------|-----------|----------|
| Chat     | PlanCard | 最新消息      | 输入框      |
| Sessions | 搜索框      | 最近会话      | 操作按钮     |
| Usage    | 总消耗      | sparkline | 按会话列表    |
| MCP      | 列表       | 添加按钮      | 配置路径     |
| Skills   | 列表       | 添加按钮      | 配置路径     |
| Settings | 关于信息     | 快捷键参考     | IDE 设置入口 |

## 二、页面生命周期

- **懒加载：** 首屏只创建 Welcome + Chat，其他页面首次点击时懒加载
- **CardLayout** 不销毁隐藏页面（自动状态保持）
- **页面间状态同步**通过 `project.messageBus` 事件总线

**自动恢复：** 打开面板时自动恢复上次活跃会话（含暂停的计划）。

**会话标题生成：** 对齐 Claude Code——新会话的第一条用户消息发送后，异步调用 LLM（不带 tools，
`max_tokens=64`）生成简短标题（≤ 20 字）。标题生成不阻塞主流程。Prompt 模板：

```
根据以下消息生成一个简短的会话标题（≤ 20 字）：
"{用户第一条消息内容}"
仅返回标题文本，不要加引号。
```

生成后持久化到 `SessionIndex.title` 和 Session JSON 的 `title` 字段，通过 `SessionChanged` 事件通知
Sessions 页面更新。

## 三、Welcome 页面

### API Key 状态机

```
UNSET ──saveKey()──→ VALIDATING ──200──→ VALID
  │                      │
  │                      ├─ 401 → INVALID
  │                      └─ 超时 → UNKNOWN
  │                                │
  └────────────────────────────────┘ (用户可重新输入)
```

启动时不阻塞 UI，后台异步验证。保存 Key 后**乐观导航**立即跳转 Chat，验证在后台继续。VALID
静默通过，INVALID → toast "API Key 无效"，UNKNOWN → toast "网络不可用，Key 暂未验证"。不在 Chat
页面显示验证横幅。

### 页面布局

```
┌──────────────────────────────────────────────────┐
│ [🏠]                                             │ ← TabBar（仅 Welcome，其他隐藏）
├──────────────────────────────────────────────────┤
│                                                  │
│              🤖  Welcome to Code Assistant        │
│                                                  │
│     你的免费 AI 编程助手——代码补全 + Agent 对话    │
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │                                              ││
│  │  ① 获取 DeepSeek API Key                     ││
│  │     https://platform.deepseek.com/api_keys    ││
│  │     （点击链接在浏览器打开）                   ││
│  │                                              ││
│  │  ② 粘贴 API Key：                            ││
│  │     ┌──────────────────────────────────────┐  ││
│  │     │ sk-•••••••••••••••••••••••••••••••• │  ││ ← 输入框 + 👁 切换
│  │     └──────────────────────────────────────┘  ││
│  │                                              ││
│  │  模型: deepseek-v4-pro（固定）               ││
│  │                                              ││
│  │                [保存并开始使用 →]             ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  已有 Key？在 Settings 页面随时修改               │
└──────────────────────────────────────────────────┘
```

## 四、Sessions 页面

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁*] [📊] [🔌] [🎯] [⚙]│
├──────────────────────────────────────────────────┤
│  🔍 搜索会话...                         [🗑 清空]│
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │ 📝 重构 UserService — 添加 suspend 支持      ││ ← 会话卡片
│  │    └ ⏸ 计划暂停中 (3/5)                    ││   暂停计划标记
│  │    3 小时前  ·  tokens: 8.2K  ·  12 次工具   ││
│  └──────────────────────────────────────────────┘│
│  ┌──────────────────────────────────────────────┐│
│  │ 💬 关于 Kotlin 协程的讨论                    ││
│  │    昨天  ·  tokens: 1.5K  ·  纯聊天          ││
│  └──────────────────────────────────────────────┘│
│  ┌──────────────────────────────────────────────┐│
│  │ 📝 分析项目架构                              ││
│  │    6 月 22 日  ·  tokens: 12.3K  ·  8 次工具 ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  共 24 个会话，总消耗 156K tokens                 │
│  [全选] [删除选中]                                 │
└──────────────────────────────────────────────────┘
```

- 搜索：v1 为 session title `String.contains()` 文本过滤 + 前端 debounce 300ms。全文搜索消息内容标记为后续优化
- 会话卡片：显示标题、计划状态（⏸ 计划暂停中）、时间、token 消耗、工具调用次数
- 子会话（parentId 非空）在列表中缩进 + `└─` 前缀展示

## 五、Token Usage 页面

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊*] [🔌] [🎯] [⚙]        │
├──────────────────────────────────────────────────┤
│  时间范围: [本月 ▾]  [☐ 含子任务]   总消耗: 128.5K  ·  ¥0.87  │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │          📈 消耗趋势（30 天，按日）            ││
│  │                                              ││
│  │   ▁ ▂ ▃ ▅ ▃ ▄ ▆ █ ▇ ▅ ▄ ▃ ▂ ▁              ││ ← sparkline
│  │  1日                          30日           ││
│  │  hover 任意点 → 显示当日具体数值              ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  按会话：                                       │
│  ┌──────────────────────────────────┬─────┬─────┐│
│  │ 📝 重构 UserService        ⏸    │ 45K │¥0.32││ ← 点击跳转到该会话
│  │ 💬 Kotlin 协程讨论              │  3K │¥0.01││
│  │ 📝 分析项目架构                 │ 32K │¥0.21││
│  │ ...（分页，每页 20 条）          │     │     ││
│  └──────────────────────────────────┴─────┴─────┘│
│                                                  │
│  模型: deepseek-v4-pro（固定）                    │
└──────────────────────────────────────────────────┘
```

- sparkline：30 天按日折线图，X 轴 = 日期，Y 轴 = 当日 totalTokens（input+output），Custom JComponent
  `paintComponent` 手绘。仅显示趋势线，省略坐标轴标签（鼠标 hover 显示具体数值）
- 数据源：Session JSON 的 `totalTokens.output` + `totalTokens.input` 按 `updatedAt` 日期聚合
- **含子任务模式**：勾选 `[☐ 含子任务]` 后，父 session 的 token 统计自动包含所有子 session 的
  token（递归聚合 `parentTotalTokens`）。默认不勾选（仅显示自身消耗）

## 六、MCP 页面

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌*] [🎯] [⚙]          │
├──────────────────────────────────────────────────┤
│  MCP Servers                        [➕ 添加]    │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │ 🟢 mysql                              [断开] ││ ← 状态灯
│  │    command: npx -y @anthropic/mcp-server-...  ││
│  │    tools: 5 (query, list_tables, describe,   ││
│  │              insert, update)                  ││
│  │    [▶ 测试连接]  [✏ 编辑]  [🗑 删除]         ││
│  └──────────────────────────────────────────────┘│
│  ┌──────────────────────────────────────────────┐│
│  │ 🟡 init... filesystem                  [✕]   ││ ← 初始化中
│  │    command: npx -y @anthropic/mcp-server-...  ││
│  │    正在安装依赖 (npm install)...              ││
│  │    最多等待 3 分钟                            ││
│  └──────────────────────────────────────────────┘│
│  ┌──────────────────────────────────────────────┐│
│  │ 🔴 custom-server                      [重连] ││ ← 崩溃
│  │    command: python my_server.py              ││
│  │    错误: Process exited with code 1          ││
│  │    [▶ 测试连接]  [📋 查看日志]  [✏ 编辑]     ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  配置文件: .code-assistant/mcp-config.json        │
│  [📂 打开文件]                                    │
└──────────────────────────────────────────────────┘
```

- 状态指示灯：🟢 RUNNING / 🟡 INITIALIZING / 🔴 CRASHED / ERROR
- "测试连接"：发送 MCP `initialize` 握手请求，5s 超时。成功 → 绿色 toast "连接成功，发现 N 个工具"
  。失败 → 红色 toast + 错误信息

## 七、Skills 页面

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌] [🎯*] [⚙]        │
├──────────────────────────────────────────────────┤
│  Skill 目录: .code-assistant/skills/              │
│  [📂 打开目录]  [➕ 新建 Skill]                   │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │ [✅] code-review                      [详情] ││ ← 启用/禁用开关
│  │      审查代码质量，查找 bug 和安全问题        ││
│  │      触发词: review, 审查, 检查代码          ││
│  │      所需工具: Read, Bash            ││
│  │                                               ││
│  │ [✅] refactor                         [详情] ││
│  │      重构代码结构，改进可读性                 ││
│  │      触发词: 重构, refactor                  ││
│  │      所需工具: Read, Edit, Write  ││
│  │                                               ││
│  │ [❌] docker-helper              ⚠ 工具缺失   ││ ← 禁用+警告
│  │      Docker 容器管理和编排                   ││
│  │      ⚠ 声明了不存在的工具: docker-compose    ││
│  │                                               ││
│  │ [✅] test-generator                  [详情] ││
│  │      根据源代码自动生成单元测试               ││
│  │      触发词: 测试, test, generate test       ││
│  └──────────────────────────────────────────────┘│
```

- Skill 卡片：启用/禁用开关 + 名称 + 描述 + 触发词 + 所需工具
- 工具交叉验证：声明但未注册的工具 → ⚠️ 警告标记，`hasMissingTools=true` 的 Skill 不可调用

## 八、Settings 页面

Agent 相关设置统一在 **IDE Settings > Tools > Code Assistant** 中管理（`SettingsConfigurable`），包括
API Key、Model、代码补全开关、Commit 模板、Agent 最大轮次、并发上限。面板内 Settings 页面为关于页面 +
快捷键参考。

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌] [🎯] [⚙*]      │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌─ 关于 ───────────────────────────────────────┐│
│  │ Code Assistant v2.0.0                        ││
│  │ github.com/jin123456bat/code-assistant        ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  ┌─ 快捷键参考 ──────────────────────────────────┐│
│  │ 打开面板:    Ctrl+Shift+K / Cmd+Shift+K       ││
│  │ 发送消息:    Enter（换行: Shift+Enter）        ││
│  │ 关闭 Popup:  Escape                           ││
│  │ 新建会话:    Ctrl+Shift+N / Cmd+Shift+N       ││
│  └──────────────────────────────────────────────┘│
│                                                  │
│  ┌─ 设置 ───────────────────────────────────────┐│
│  │ 所有配置项（API Key、模型、Agent、快捷键）    ││
│  │ 请前往 IDE Settings > Tools > Code Assistant  ││
│  │                          [⚙ 打开 IDE 设置]    ││
│  └──────────────────────────────────────────────┘│
└──────────────────────────────────────────────────┘
```

## 九、IDE Settings

Agent 相关设置已迁移到 IDE SettingsConfigurable（Settings > Tools > Code Assistant），与其他功能统一管理：

| 配置项           | 默认值               | 说明                     |
|---------------|-------------------|------------------------|
| API Key       | —                 | 从 PasswordSafe 读写，显示掩码 |
| Model         | `deepseek-v4-pro` | 固定                     |
| 代码补全          | 启用                | 开关                     |
| 补全 max_tokens | 256               | 范围 1-1024              |
| Commit Prompt | 默认模板              | 自定义 commit message 模板  |
| Agent 最大轮次    | 20（0=不限）          | 达到上限后自动终止              |
| 多 Agent 并发上限  | 3                 | 父 + 子 Agent 总计并发数      |

> Shell 超时由 LLM 在每次 tool call 时传入（必填），0=不限，不在 Settings 中配置。

## 十、快捷键

| 快捷键（Windows/Linux） | 快捷键（macOS）    | 操作                                |
|--------------------|---------------|-----------------------------------|
| `Ctrl+Shift+K`     | `Cmd+Shift+K` | 打开/关闭聊天面板                         |
| `Enter`            | `Enter`       | 发送消息                              |
| `Shift+Enter`      | `Shift+Enter` | 输入框换行                             |
| `Escape`           | `Escape`      | 关闭 Popup（不中断 Agent、LLM、流式生成或工具执行） |
| `Ctrl+Shift+N`     | `Cmd+Shift+N` | 新建会话                              |
| `↑`（在空输入框）         | `↑`（在空输入框）    | 填充上一条消息                           |

## 十一、页面流转图

```
应用启动
  │
  ├─ 无 API Key ──→ Welcome 页面（唯一可见页面，TabBar 仅显示 🏠）
  │                   │
  │                   └─ 输入 Key → 乐观跳转 Chat
  │                       ├─ 🏠 隐藏，其余 6 个 Tab 显示
  │                       ├─ 验证成功 → ✓ 静默
  │                       └─ 验证失败 → Toast 警告
  │
  └─ 有 API Key ──→ Chat 页面（Welcome Tab 隐藏）
                     │
        ┌────────────┼────────────┬────────────┬────────────┐
        ▼            ▼            ▼            ▼            ▼
    Sessions     Token Usage      MCP        Skills      Settings
    页面          页面           页面         页面         页面
        │
        ├─ 点击会话 → Chat 页面 (恢复该会话)
        ├─ 搜索 → 过滤会话列表
        └─ 删除 → 确认 Dialog → 删除

Chat 页面内流转（统一模式，无需切换）:
  ┌─────────────────────────────────────────────────────┐
  │                                                      │
  │  Chat（默认，允许工具调用）                              │
  │     │                                                │
  │     ├─ /plan 命令/createPlan → PlanCard + 自动执行    │
  │     │               ├─ 逐步自动执行                     │
  │     │               ├─ LLM 通过 listPlans 查看进度      │
  │     │               └─ LLM 通过 removePlan/reorderPlans 调整 │
  │     │                                                │
  │     ├─ 工具调用（随对话自然触发）                        │
  │     │   ├─ ToolCallCard (PENDING)                    │
  │     │   ├─ 审批弹窗 (AWAITING_APPROVAL)               │
  │     │   ├─ ToolCallCard (EXECUTING)                  │
  │     │   ├─ ToolCallCard (DONE/ERROR)                 │
  │     │   └─ 循环直到 stop_reason=end_turn              │
  │     │                                                │
  │     ├─ @file 引用 → FileRefTags                       │
  │     ├─ 选中代码 → SelectionTag (自动)                  │
  │     ├─ Ctrl+V 粘贴图片 → ImageTags                    │
  │     ├─ 发送 → AgentLoop.run()                         │
  │     │         ├─ 流式 token → 气泡增量渲染              │
  │     │         └─ 完成 → 自动滚动到底部                  │
  │     └─ Stop → 取消 HTTP + kill 进程                   │
  │                                                      │
  └──────────────────────────────────────────────────────┘
```

### messageBus 事件总线

| Topic                   | 消息类型                                            | 发布者            | 订阅者                    |
|-------------------------|-------------------------------------------------|----------------|------------------------|
| `SessionChanged`        | sessionId + changeType(CREATED/UPDATED/DELETED) | SessionManager | SessionsPage, ChatPage |
| `AgentStateChanged`     | newState (IDLE/PROCESSING/...)                  | AgentSession   | ChatPage, TabBar       |
| `TokenUsageUpdated`     | sessionId + delta                               | AgentSession   | TokenUsagePage         |
| `McpServerStateChanged` | serverId + newState                             | McpManager     | McpPage                |
| `ApiKeyValidated`       | keyState (VALID/INVALID/UNKNOWN)                | WelcomePage    | ChatPage, SettingsPage |
| `PlanStateChanged`      | sessionId + planStatus + currentPlan            | PlanExecutor   | ChatPage, SessionsPage |
| `PageSwitched`          | from + to (PageId)                              | ChatToolWindow | 所有 Page                |

## 十二、组件接口

### ChatToolWindow（顶层容器）

```
ChatToolWindow
├── 构造: ChatToolWindow(project: Project)
├── tabBar: TabBar                       // 顶部导航
├── pageContainer: JPanel(CardLayout)    // 页面切换容器
├── pages: Map<PageId, Page>             // 懒加载页面缓存
│
├── navigateTo(pageId: PageId)           // 切换页面
├── getCurrentPage(): PageId
├── registerPage(pageId: PageId, factory: () -> Page)
│
├── onPageChanged: ((PageId) -> Unit)?   // 页面切换回调
└── dispose():                           // 清理顺序见 agent.md 第十三节

PageId: WELCOME | CHAT | SESSIONS | TOKEN_USAGE | MCP | SKILLS | SETTINGS

所有页面直接继承 JPanel，通过 CardLayout 管理切换。页面懒加载，首次点击对应 Tab 时创建。
```

### TabBar

```
TabBar
├── 构造: TabBar(pages: List<PageId>, onSelect: (PageId) -> Unit)
├── setSelected(pageId: PageId)
├── setBadge(pageId: PageId, text: String?)    // 页面标签上的角标（如计划执行中数）
├── setEnabled(pageId: PageId, enabled: Boolean)  // Welcome 页面控制导航禁用
├── getSelected(): PageId
└── 渲染: JPanel (FlowLayout, 等宽按钮)
```

### ChatPage（聊天主页面）

```
ChatPage extends JPanel
├── 构造: ChatPage(sessionManager, chatViewModel)
├── messageList: JScrollPane             // 消息列表
├── inputArea: ChatInputArea             // 输入区域
├── renderMessage(msg: ChatMessage)      // 渲染一条消息
├── scrollToBottom()                     // 自动滚动
├── onAutoScrollPaused: Boolean          // 用户上滚 > 50px → 暂停 → 显示 ↓ 按钮
│
└── 组件树:
    ChatPage (BorderLayout)
      ├── NORTH: 标题行（会话标题 + 新会话/清空按钮）
      ├── CENTER: JScrollPane
      │         └── messageContainer (BoxLayout.Y_AXIS)
      │               ├── ChatBubble (历史消息)
      │               ├── ToolCallCard (工具调用)
      │               ├── PlanCard (计划)
      │               └── StreamingBubble (流式渲染中)
      └── SOUTH: ChatInputArea
                 ├── TagsRow (FlowLayout: 文件+图片混合 tag, 输入框上方)
                 ├── JTextArea (文本区域, 无边框)
                 └── BottomBar: [+]按钮 + @提示 + [→]发送
```
