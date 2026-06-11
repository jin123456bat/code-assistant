# Code Assistant Design System

PhpStorm IDE 插件 — Swing UI 设计规范。Token 定义与 `ChatTheme.kt` 保持同步。

## Product Context

- **What this is**: PhpStorm 开源 AI Agent 插件，可自主搜索代码、读写文件、执行命令、Git 操作
- **Who it's for**: PHP 开发者（学生至企业级），需要免费开源的 AI 编程助手
- **Space/industry**: JetBrains IDE 插件生态
- **Reference**: GitHub Copilot、JetBrains AI Assistant、Cursor

## Aesthetic Direction

- **Direction**: Utilitarian — IDE 原生集成感，功能优先于装饰
- **Accent**: 单一强调蓝 `#3574F0`（用户气泡 + 工具左栏竖线），克制不喧闹
- **Decoration level**: minimal — 颜色仅用于语义区分（用户/AI/工具/错误）
- **Mood**: 可靠、专业、不突兀。开发者不需要"漂亮"的 UI，需要不打断心流的工具

## 决策日志

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-06-02 | v1.0 初始设计：蓝色用户气泡 + 灰色 AI 气泡 | IntelliJ 默认主题色调 |
| 2026-06-04 | v2.0 Agent 模式：新增紫/绿/黄工具卡片 | 语义色区分工具调用/结果/执行中 |
| 2026-06-05 | 对比度修复：#999→#767, #8C8C→#AAA | WCAG AA 4.5:1 合规 |
| 2026-06-05 | 欢迎页去 Slop：移除"欢迎使用"改为能力列表 | AI Slop 黑名单 #9 |
| 2026-06-05 | 创建 DESIGN.md | /plan-design-review 建议 |
| 2026-06-08 | v3.0 重构：统一蓝色强调 + 工具改为折叠行（左侧色条） | 去掉紫/绿/黄彩色气泡，改为克制折叠行 |
| 2026-06-08 | ChatBubble 改为实时自测量架构 | 根治构造期尺寸冻结导致的裁字/错位 |
| 2026-06-10 | conversationWrapper 替代 vertical glue | 实现 web-like 顶部对齐 + 底部留空 |
| 2026-06-10 | PlanBar 置顶计划条 | 折叠摘要 展开步骤，不随消息滚动 |
| 2026-06-10 | PermissionCard / SelectionCard 内联交互 | 替代系统弹窗，Cliuude Code 风格选项列表 |
| 2026-06-11 | ask_user 工具 + SelectionCard 多选 | M5-A/M5-B，单选/多选模式 |

## Color Tokens

所有 token 定义在 `ChatTheme.kt`，使用 `JBColor(lightHex, darkHex)` 跟随 IDE 主题切换。

### 气泡颜色（语义色）

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `userBg` | `#3574F0` | `#2F5E8F` | 用户消息背景（蓝色实心） |
| `userFg` | `#FFFFFF` | `#FFFFFF` | 用户消息文字（白色） |
| `aiBg` | `#F5F5F5` | `#37373C` | AI 回复背景（浅灰） |
| `aiBorder` | `#DCDCDC` | `#41414B` | AI 回复边框（灰） |

### 工具行颜色（统一蓝色强调）

工具行采用折叠+左侧色条的克制风格，取代旧的彩色气泡（紫/绿/黄）。

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `toolBar` | `#3574F0` | `#5C8FD6` | 工具行左侧 3px 竖线 + spinner 颜色 |
| `toolFg` | `#2F6FE0` | `#84ACDF` | 工具名称/标题文字颜色 |
| `toolBg` | `rgba(53,116,240,0.08)` | `rgba(92,143,214,0.13)` | 工具行/权限卡淡背景 |

### 代码 / 输入

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `codeBg` | `#F7F8FA` | `#1E1F22` | 代码块/工具结果背景 |
| `codeBorder` | `#EBECF0` | `#393B40` | 代码块边框 |
| `inputBg` | `#FFFFFF` | `#1E1F22` | 输入面板背景 |
| `inputBorder` | `#C9CCD6` | `#4E5157` | 输入面板边框（默认） |
| `inputFocus` | `#4A90D9` | `#4A90D9` | 输入面板边框（聚焦） |

### 文字颜色

| Token | Light | Dark | 用途 | 对比度 |
|-------|-------|------|------|--------|
| `textPrimary` | `#27282B` | `#DFE1E5` | 正文 | 14:1 / 14:1 |
| `textSecondary` | `#6C707E` | `#9DA0A8` | 标签/角色 | 5.3:1 / 5.0:1 |
| `textMuted` | `#989AA2` | `#7A7D85` | 辅助/折叠文字 | 3.5:1 / 3.8:1 |

### 基础色

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `winBg` | `#FFFFFF` | `#2B2D30` | 窗口/面板背景 |
| `divider` | `#EBECF0` | `#393B40` | 分割线 |

### 状态/语义色

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `diffDelFg` | `#C0392B` | `#D97D7D` | diff 删除行 |
| `diffAddFg` | `#2E7D32` | `#7BBD86` | diff 添加行 |
| `danger` | `#D98A3D` | `#D98A3D` | execute_command 危险边框 |
| `error` | `#B5503E` | `#E08A72` | 错误文字 |
| `errorCardBg` | `rgba(181,80,62,0.06)` | `rgba(224,138,114,0.10)` | 错误卡背景 |
| `doneCheck` | `#5AA86A` | `#5AA86A` | 已完成 ✓ 对勾 |

## Typography

- 系统默认字体：`Font.SANS_SERIF`（Swing 跨平台兼容）
- 代码字体：`Font.MONOSPACED`
- 字号层级（`ChatTheme` + IDE 编辑器字号自适应）：
  - `bodyFont`: `JBFont.regular()`（~13px，对话内容）
  - `metaFont`: `JBFont.small()`（~11px，标签、角色名、工具行）
  - `codeFont`: `Font.MONOSPACED` 跟随 body 字号
  - `ToolRowFactory` 中：工具行字号 = `editorFontSize - 1`，思考行 = `editorFontSize - 2`
- 行高：Swing 默认（~1.2-1.4x），HTML JTextPane 自然换行

## Spacing

定义在 `ChatTheme` 常量（逻辑 px，经 `JBUI.scale()` 缩放适配 HiDPI）：

| Token | 值 | 用途 |
|------|----|------|
| `GAP_BUBBLE` | 10 | 气泡之间垂直间距 |
| `GAP_ROLE` | 16 | 不同角色切换额外留白 |
| `PAD_BUBBLE_V` | 8 | 气泡内上下 padding |
| `PAD_BUBBLE_H` | 12 | 气泡内左右 padding |
| `RADIUS` | 14 | 气泡/卡片圆角 |
| `RADIUS_TIGHT` | 5 | 微信式小尖角 |

- 基础单位：4px（IntelliJ `JBUI` 工具类）
- 输入区最小高度：80px
- 对话区最小宽度：100px

## Border Radius

- 气泡：14px 圆角（`ChatBubble.paintComponent` + Graphics2D 抗锯齿渲染）
- 用户气泡右下角 5px（微信式指向发送方的小尖角）
- AI 气泡左下角 5px
- 代码块/工具结果：4px 轻微圆角
- 输入框：14px 圆角，2px 聚焦描边
- 选项行 hover：8px 圆角

## Bubble Sizing（自测量架构）

`ChatBubble` 不再在构造期冻结尺寸，改为实时自测量：

- `getPreferredSize()` — 实时按 viewport 宽度 + content 测量计算
- `getMaximumSize()` = `preferredSize` — hug content，不被拉伸
- `getMinimumSize()` = `preferredSize` — BoxLayout 不压缩
- `USER_FRACTION = 0.80` — 用户气泡最大宽度占 viewport 80%
- `AI_FRACTION = 1.0` — AI 气泡占满全宽
- `ABS_CAP = 560` — 用户气泡内容绝对宽度上限（逻辑 px）

## Layout Architecture

```
panel (BorderLayout)
├── NORTH  → errorBanner
├── CENTER → conversationPanel
│   ├── NORTH  → northStack（conversationHeader + planBar）
│   └── CENTER → conversationScrollPane
│       └── conversationWrapper (BoxLayout.Y_AXIS, 无 glue)
│           └── conversationContainer (BoxLayout.Y_AXIS)
│               ├── rowPanel [glue] [ChatBubble]  ← 用户靠右
│               ├── rowPanel [ChatBubble] [glue]  ← AI 靠左
│               ├── ToolRowFactory 折叠行
│               ├── PermissionCard / SelectionCard
│               └── 流式气泡
└── SOUTH  → inputPanel（引用芯片 + textarea + 发送按钮）
```

**关键机制：**
- `conversationWrapper` 无 vertical glue → 内容自然从顶部开始，等价 web `flex-direction: column`
- 气泡对齐：`rowPanel`（X_AXIS）+ `Box.createHorizontalGlue()`，等价 CSS `justify-content`
- 滚动到末尾：双重 `invokeLater` 确保 revalidate 完成后再读取 scrollBar.maximum

## Components

### PlanBar — 置顶计划条
- 固定于 conversationScrollPane 上方，不随消息滚动
- 折叠态：chevron + 标题 + 进度 "2/4" + 当前步骤 + 迷你进度条
- 展开态：步骤列表（done/doing/todo 状态 + checkbox 图标）
- 步骤过多时内部滚动（max-height 168px）

### PermissionCard — 权限确认卡
- 圆角卡片 + `toolBg` 淡背景 + `toolBar`/`danger` 色边框
- 头部：工具名（toolFg 粗体）+ args 预览（等宽 textMuted，单行截断 120 chars）
- 选项列表：❯ chevron 高亮 + 主文本 + 副文本
- 正常变体 3 选项：允许本次 / 允许且不再询问 / 拒绝并说明
- danger 变体（execute_command）2 选项 + ⚠ 标记 + orange 边框
- 点击后卡片切换为已确认静态状态，不可再交互
- `write_file` 特殊：头部下方展示 diff 预览（LCS 算法，超 40 行折叠）

### SelectionCard — ask_user 选择卡
- 单选模式：点击即提交
- 多选模式：复选框 + 确认按钮
- 选项 hover 高亮 + toolBg 背景

### ToolRowFactory — 工具/思考折叠行
- 统一左侧 3px 色条（`toolBar`）+ 淡 `toolBg` 背景
- `toolCallRow`: 列出每个 toolCall 的 name + args 预览，不可折叠
- `toolResultRow`: 默认折叠 "结果 · toolName" + "✓ N 行"，展开显示内容（超 2000 chars 截断）
- `thinkingRow`: 默认折叠，摘要取前 100 chars 双行截断；展开显示全文。流式展示时 `initiallyExpanded=true`
- `runningRow`: 盲文 spinner（⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏）+ "执行中 · toolName"
- `errorCardRow`: 失败时红色左栏 + "✕ toolName 失败" + errorCodeBg 详情

## Interaction States

| 状态 | 视觉表现 |
|------|---------|
| 默认 | 正常颜色 + 实线边框 |
| 错误 | 红色横幅 + 具体错误消息 + 修复建议 |
| 警告 | 黄色横幅 + 倒计时 |
| 流式生成中 | 发送按钮 "■" + 输入框禁用 + 思考/回复流式原地更新 |
| 工具执行中 | 盲文 spinner 动画 + "执行中 · toolName" |
| 工具完成 | 折叠行 "✓ N 行" |
| 工具失败 | 红色错误卡 |
| 权限确认 | PermissionCard 内联，阻塞等待 + CountDownLatch |
| ask_user | SelectionCard 内联，阻塞等待用户选择 |
| hover（选项行） | chevron "❯" 出现 + 文字变为 textPrimary + toolBg 背景 |
| 已确认 | 卡片替换为静态标签 "已允许 ✓" / "已拒绝 ✗" |

## Accessibility

- 所有文字对比度 ≥ 4.5:1（WCAG AA），textMuted 在浅色主题约为 3.5:1（辅助信息可接受）
- 交互元素通过 IntelliJ 主题自动适配暗色模式
- 键盘导航：Tab 序（输入框 → 停止 → 文件 → 引用 → 发送）
- 错误消息包含具体修复步骤（不是仅"失败了"）
- 盲文 spinner 通过 `addNotify/removeNotify` 管理 Timer 生命周期，防止泄漏

## Icons

- 使用 Unicode 符号替代图片图标：❯（chevron）、▸/▾（展开/收起箭头）、⚠（危险）、✕（失败）、✓（成功）
- 盲文 spinner 帧：⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏
- IntelliJ 内置图标：`AllIcons.Actions.Annotate`
