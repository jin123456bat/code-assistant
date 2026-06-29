# UI/UX 设计

Code Assistant 的界面设计——基于 IntelliJ Platform 的 Swing 多页面应用。

## 功能文档

| 文档                                         | 说明                                               |
|--------------------------------------------|--------------------------------------------------|
| [ui/pages.md](ui/pages.md)                 | 多页面架构：7 页面 + TabBar + CardLayout + 页面生命周期 + 流转图  |
| [ui/chat.md](ui/chat.md)                   | 聊天面板：气泡渲染 + 工具卡片 + Plan 卡片 + 输入区域 + Popup + 图片粘贴 |
| [ui/design-system.md](ui/design-system.md) | 设计系统：色板 + 字体 + 间距 + 圆角 + 动效 + 响应式 + 无障碍 + 虚拟化    |
| [ui/components.md](ui/components.md)       | UI 组件技术契约：Swing 布局规范 + 组件接口定义 + 状态矩阵             |

> 各组件在 IntelliJ IDEA 亮/暗双主题下自动适配。

## 架构概览

```
┌──────────────────────────────────────────────────────┐
│  ui/ 包                                               │
│  ├── ChatToolWindow.kt     顶层容器                   │
│  ├── TabBar.kt             顶部导航                   │
│  ├── AppColors.kt          颜色令牌                   │
│  ├── MessageBus.kt         事件总线                   │
│  ├── SelectionListener.kt  编辑器选中监听              │
│  ├── OpenChatAction.kt     快捷键 Action              │
│  ├── page/                 7 个 Page                  │
│  │   ├── WelcomePage.kt                            │
│  │   ├── ChatPage.kt                               │
│  │   ├── SessionsPage.kt                           │
│  │   ├── TokenUsagePage.kt                         │
│  │   ├── McpPage.kt                                │
│  │   ├── SkillsPage.kt                             │
│  │   └── SettingsPage.kt                           │
│  └── chat/                 聊天 UI 组件               │
│      ├── ChatBubbleRenderer.kt                     │
│      ├── ChatViewModel.kt                          │
│      ├── ChatInputArea.kt                          │
│      ├── ToolCallCard.kt                           │
│      ├── PlanCard.kt                               │
│      ├── RoundedBorder.kt                          │
│      └── SimpleDiff.kt                             │
└──────────────────────────────────────────────────────┘
```

## 核心技术栈

- **UI 框架：** IntelliJ Platform Swing（JPanel / JLabel / JTextArea / JScrollPane）
- **Markdown 渲染：** 手写字符串解析器，5 种 Block
  类型（Paragraph/CodeBlock/Header/ListItem/QuoteBlock），详见 [
  `docs/agent/markdown-rendering.md`](agent/markdown-rendering.md)
- **代码块渲染：** `JTextArea` 只读模式（JetBrains Mono 等宽字体），非 `EditorTextField`。详见 [
  `docs/agent/markdown-rendering.md`](agent/markdown-rendering.md)
- **流式渲染：** 首 token 即时渲染，后续 token 静默合并（token 停顿 ≥30ms 后批量 flush）
- **主题适配：** `JBColor` 亮/暗双主题自动切换
- **页面路由：** CardLayout 懒加载，首屏只创建 Welcome + Chat

## 页面间通信

通过 `project.messageBus` 事件总线。完整 Topic
契约（消息类型、字段、发布者、订阅者）详见 [specs/event-bus.md](specs/event-bus.md)。
