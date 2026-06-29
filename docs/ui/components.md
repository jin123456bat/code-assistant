# UI 组件技术契约

> 关联文档：[[chat]], [[design-system]]

## 一、布局规范

以下为关键 UI 组件的 LayoutManager 选择。**必须使用指定的 LayoutManager，不可替换。**

| 组件             | LayoutManager                             | 说明                                                                                         |
|----------------|-------------------------------------------|--------------------------------------------------------------------------------------------|
| ChatToolWindow | `BorderLayout`                            | NORTH=TabBar, CENTER=pageContainer(CardLayout)                                             |
| TabBar         | `JPanel(FlowLayout.LEFT, hgap=0, vgap=0)` | 纯图标按钮，`setPreferredSize(Dimension(44, 32))`。7 个 Tab × 44px = 308px                         |
| ChatPage       | `BorderLayout`                            | NORTH=标题行, CENTER=JScrollPane, SOUTH=ChatInputArea                                         |
| 消息容器           | `JPanel` → `BoxLayout.Y_AXIS`             | 消息气泡垂直排列，用 `Box.createVerticalStrut(8)` 间隔                                                 |
| 用户气泡           | `JPanel(BorderLayout)` → 右对齐              | `setMaximumSize(Dimension(maxWidth, ...))` 限制宽度为面板的 70%                                    |
| Agent 气泡       | `BubblePanel(BoxLayout.Y_AXIS)`           | 文本段+代码块+文本段垂直排列，用 `Box.createVerticalStrut(4)` 间隔                                          |
| ToolCallCard   | `JPanel(BorderLayout)`                    | NORTH=头部(图标+名称+状态), CENTER=折叠面板(JPanel.BoxLayout_Y_AXIS)                                   |
| PlanCard       | `JPanel(BorderLayout)`                    | NORTH=摘要行, CENTER=计划项列表(BoxLayout.Y_AXIS)                                                  |
| ChatInputArea  | `JPanel(BorderLayout)`                    | NORTH=TagsRow(FlowLayout: 文件+图片), CENTER=JTextArea, SOUTH=底部栏(FlowLayout: [+]按钮+@提示+[→]发送) |
| WelcomePage    | `JPanel(GridBagLayout)`                   | 居中单列，GridBagConstraints.fill=HORIZONTAL, insets=Insets(8,20,8,20)                          |
| SessionsPage   | `JPanel(BorderLayout)`                    | NORTH=搜索栏, CENTER=JScrollPane→JPanel(BoxLayout.Y_AXIS) 会话卡片列表                              |
| TokenUsagePage | `JPanel(BorderLayout)`                    | NORTH=时间选择行(FlowLayout), CENTER=JPanel→上部sparkline(JComponent)+下部JTable                    |
| McpPage        | `JPanel(BorderLayout)`                    | NORTH=标题+添加按钮, CENTER=JScrollPane→JPanel(BoxLayout.Y_AXIS) server卡片列表                      |
| SkillsPage     | `JPanel(BorderLayout)`                    | NORTH=标题+操作按钮, CENTER=JScrollPane→JPanel(BoxLayout.Y_AXIS) skill卡片列表                       |
| SettingsPage   | `JPanel(BoxLayout.Y_AXIS)`                | 关于卡片 + 快捷键参考卡片 + IDE 设置入口卡片，垂直排列                                                           |

## 二、组件样式

| 属性          | 值                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------|
| 用户气泡背景色     | `Color(232, 240, 254)` (#E8F0FE)                                                                                        |
| Agent 气泡背景色 | `Color(255, 255, 255)` (#FFFFFF) + 左边框 `Color(59, 130, 246)` (#3B82F6) 3px                                              |
| 错误气泡背景色     | `Color(254, 226, 226)` (#FEE2E2)                                                                                        |
| 系统消息颜色      | `Color(156, 163, 175)` (#9CA3AF)                                                                                        |
| 代码块背景色      | `Color(246, 248, 250)` (#F6F8FA)                                                                                        |
| 字体          | 中文/英文统一: `Font("SansSerif", PLAIN, 14)`。代码块: `Font("JetBrains Mono", PLAIN, 13)`，不可用时降级 `Font("Monospaced", PLAIN, 13)` |
| 圆角          | 用户气泡: 12px, Agent 气泡: 12px, 代码块: 8px, 工具卡片: 8px                                                                         |
| 间距          | 气泡间: 8px, 气泡内部 padding: 12px, 代码块 margin: 8px 0                                                                         |
| 输入框最小行数     | 2 行, 最大 10 行, 自动扩展                                                                                                      |
| TabBar 按钮大小 | 44×32 px（纯图标）, 当前页面高亮 `Color(59, 130, 246)` 底边框 2px                                                                     |

## 三、组件状态矩阵

> 以下使用 CSS 风格简写（`bg`、`fg`、`border`、`cursor`、`padding` 等），便于设计评审。实际 Swing
> 实现对应关系见各组件接口。

### 3.1 按钮

```
Primary Button（发送、保存）:
┌──────────┬─────────────┬──────────────────┐
│ Default  │ bg=#3B82F6  │ 蓝底白字          │
│          │ fg=#FFFFFF  │ 圆角 6px          │
├──────────┼─────────────┼──────────────────┤
│ Hover    │ bg=#2563EB  │ 深蓝              │
├──────────┼─────────────┼──────────────────┤
│ Pressed  │ bg=#1D4ED8  │ 更深蓝            │
├──────────┼─────────────┼──────────────────┤
│ Disabled │ bg=#D1D5DB  │ 灰色不可点击       │
│          │ fg=#9CA3AF  │ cursor=default    │
├──────────┼─────────────┼──────────────────┤
│ Loading  │ bg=#3B82F6  │ 文字位置显示 spinner│
│          │ text=""     │ 不可点击           │
└──────────┴─────────────┴──────────────────┘

Secondary Button（取消、跳过、清空）:
┌──────────┬─────────────┬──────────────────┐
│ Default  │ bg=transparent │ 透明底          │
│          │ fg=#374151  │ 边框 #D1D5DB     │
│          │ border=1px  │                  │
├──────────┼─────────────┼──────────────────┤
│ Hover    │ bg=#F3F4F6  │ 浅灰底            │
├──────────┼─────────────┼──────────────────┤
│ Disabled │ fg=#9CA3AF  │ 灰色              │
│          │ border=#E5E7EB│                  │
└──────────┴─────────────┴──────────────────┘

Danger Button（终止计划、删除会话）:
┌──────────┬─────────────┬──────────────────┐
│ Default  │ fg=#EF4444  │ 红色文字          │
│          │ bg=transparent│ 透明底           │
├──────────┼─────────────┼──────────────────┤
│ Hover    │ bg=#FEE2E2  │ 红底              │
└──────────┴─────────────┴──────────────────┘
```

### 3.2 输入框

```
┌──────────┬─────────────────────────────────┐
│ Default  │ border=#D1D5DB, bg=#FFFFFF      │
│          │ placeholder="输入你的问题..."    │
│          │ cursor=text                     │
├──────────┼─────────────────────────────────┤
│ Focus    │ border=#3B82F6, 边框加粗到 2px   │
│          │ 底部 token 计数变色              │
├──────────┼─────────────────────────────────┤
│ Disabled │ bg=#F3F4F6, cursor=default      │
│          │ (Agent 执行中，不可输入)         │
├──────────┼─────────────────────────────────┤
│ Error    │ border=#EF4444                  │
│          │ (消息发送失败时短暂标红 500ms)    │
├──────────┼─────────────────────────────────┤
│ Overflow │ 高度自适应 2-10 行               │
│          │ 超过 10 行出现垂直滚动条          │
│          │ JScrollPane border 跟随 input    │
└──────────┴─────────────────────────────────┘
```

### 3.3 TabBar 按钮

纯图标模式，无文字标签。按钮根据 API Key 状态动态显隐：

- **无 API Key**：仅显示 Welcome（🏠），其余 6 个 Tab 隐藏
- **有 API Key**：隐藏 Welcome，显示 Chat / Sessions / Token Usage / MCP / Skills / Settings

```
┌──────────┬─────────────────────────────────┐
│ Default  │ fg=#6B7280, bg=transparent      │
│          │ border-bottom=2px transparent   │
│          │ cursor=hand                     │
├──────────┼─────────────────────────────────┤
│ Hover    │ bg=#F3F4F6                      │
├──────────┼─────────────────────────────────┤
│ Selected │ fg=#3B82F6                      │
│          │ border-bottom=2px #3B82F6       │
│          │ font-weight=Semibold            │
├──────────┼─────────────────────────────────┤
│ Hidden   │ Swing setVisible(false)          │
│          │ 无 API Key 时隐藏 Welcome 以外   │
│          │ 有 API Key 时隐藏 Welcome        │
├──────────┼─────────────────────────────────┤
│ Badge    │ 右上角角标                       │
│          │ bg=#EF4444, fg=#FFFFFF, 圆形 8px  │
│          │ 数字 > 99 → "99+"               │
└──────────┴─────────────────────────────────┘
```

## 四、ChatViewModel 接口

```
ChatViewModel
├── messages: ObservableList<ChatMessage>  // UI 绑定列表（只读）
├── streamingToken: ObservableString       // 当前流式 token（UI 绑定）
├── session: AgentSession                  // 当前绑定的会话
├── inputState: InputState                 // 输入区域状态
│
├── sendMessage(text: String, attachments: List<FileRef>, images: List<ImageRef>)
│   → 调用 AgentLoop.run() → collect AgentEvent → 更新 messages/streamingToken
├── cancelGeneration()
├── addFileRef(ref: FileRef)
├── removeFileRef(ref: FileRef)
├── updateSelectionRef(file: String, lines: IntRange?, content: String)
├── clearSelectionRef()
├── addImage(image: ImageRef)
├── removeImage(imageId: String)
│
├── clearSession()
│   → session.messages.clear()
│   → session.compactSummary = null, session.compactCount = 0
│   → session.plan = null
│   → session.totalTokens 归零
│   → session.approvedTools 保留（不清除审批信任）
│   → 复用当前 session.id，不新建文件
│
├── rollbackToMessage(messageId: String)
├── undoRollback()
└── hasPendingRollback: Boolean
│
└── InputState:
    ├── manualRefs: List<FileRef>
    ├── selectionRef: FileRef?
    ├── images: List<ImageRef>
    └── tokenCount: Int

ChatMessage → 见 [chat.md](chat.md) ChatMessage 接口定义
ToolCallUIData → 见 [chat.md](chat.md) ToolCallUIData 接口定义

ImageRef:
├── id: String (UUID)
├── fileName: String
├── mimeType: String
├── sizeBytes: Long
├── base64Data: String
└── thumbnail: BufferedImage

FileRef:
├── filePath: String
├── displayName: String
├── lineStart: Int?
├── lineEnd: Int?
├── content: String
└── source: MANUAL | SELECTION
```

## 五、ToolCallCard & PlanCard 接口

```
ToolCallCard
├── 构造: ToolCallCard(toolName: String, params: String, initialState: ToolCallState)
├── setState(state: ToolCallState, result: String?, durationMs: Long?)
├── setRejected()
├── setCancelled()
├── isExpanded: Boolean = false
├── toggleExpanded()
├── renderDiff(oldText: String, newText: String)  //
├── setChildTokenCost(tokens: Long, cost: BigDecimal)  // Agent 工具调用时显示子任务 Token 消耗
└── 渲染: JPanel (带箭头 + 状态图标 + 参数区 + 结果滚动区(max-height=240px) + 可视化 Diff + 子任务 Token 行 + 底部耗时)

ToolCallState: PENDING | AWAITING_APPROVAL | EXECUTING | DONE | ERROR | TIMEOUT | REJECTED | CANCELLED

PlanCard
├── 构造: PlanCard(plan: Plan)
├── setPlanState(planId: String, state: PlanStatus)
├── setCurrentPlanIndex(index: Int)
├── isExpanded: Boolean = false
├── toggleExpanded()
├── onPlanDeleted: ((planId: String) -> Unit)?
└── 渲染: JPanel (计划摘要 + 计划项列表，行末 [✕] 仅 PAUSED 状态可见)
```

## 六、其他组件接口

### ChatToolWindow

```
ChatToolWindow
├── 构造: ChatToolWindow(project: Project)
├── tabBar: TabBar
├── pageContainer: JPanel(CardLayout)
├── pages: Map<PageId, Page>
├── navigateTo(pageId: PageId)
├── getCurrentPage(): PageId
├── registerPage(pageId: PageId, factory: () -> Page)
├── onPageChanged: ((PageId) -> Unit)?
└── dispose()
```

### TabBar

```
TabBar
├── 构造: TabBar(pages: List<PageId>, onSelect: (PageId) -> Unit)
├── setSelected(pageId: PageId)
├── setBadge(pageId: PageId, text: String?)
├── setEnabled(pageId: PageId, enabled: Boolean)
├── getSelected(): PageId
└── 渲染: JPanel (FlowLayout, 等宽按钮)
```

### ChatPage

```
ChatPage extends JPanel
├── 构造: ChatPage(sessionManager, chatViewModel)
├── messageList: JScrollPane
├── inputArea: ChatInputArea
├── renderMessage(msg: ChatMessage)
├── scrollToBottom()
├── onAutoScrollPaused: Boolean
│
└── 组件树:
    ChatPage (BorderLayout)
      ├── NORTH: 标题行
      ├── CENTER: JScrollPane
      │         └── messageContainer (BoxLayout.Y_AXIS)
      └── SOUTH: ChatInputArea
```

### ChatInputArea

```
ChatInputArea
├── 构造: ChatInputArea(viewModel: ChatViewModel)
├── textArea: JTextArea
├── tagsRow: JPanel
├── addFileButton: JButton
├── sendButton: JButton
├── hintLabel: JLabel
├── onSlashTrigger(): Unit
├── onAtTrigger(): Unit
├── handlePopupKeyDown(event: KeyEvent): Boolean
├── onPasteImage(image: BufferedImage)
└── 剪贴板监听
```

### 审批内嵌卡片（ToolCallCard AWAITING_APPROVAL）

审批不弹独立 Dialog，而是复用 ToolCallCard 的 `AWAITING_APPROVAL` 状态，在消息流中**内嵌**呈现审批按钮：

| 属性        | 值                                          |
|-----------|--------------------------------------------|
| **交互位置**  | 嵌入 ChatPage 消息流，ToolCallCard 自动展开          |
| **按钮**    | `[允许一次]` `[允许此会话]` `[拒绝]`（危险命令无"允许此会话"）    |
| **阻塞**    | 卡片不可折叠 + CountDownLatch，Agent Loop 在后台线程等待 |
| **无关闭概念** | 用户只能选择批准/拒绝，不存在关闭窗口绕过审批的路径                 |

审批触发规则详见 [工具系统 §六](../agent/tools.md#六审批机制)。
