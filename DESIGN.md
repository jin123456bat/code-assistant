# Code Assistant Design System

IntelliJ IDEA 插件 — Swing UI 设计规范。Token 定义与 `ChatTheme.kt` 保持同步。

## Product Context

- **What this is**: IntelliJ IDEA 开源 AI Agent 插件，可自主搜索代码、读写文件、执行命令、Git 操作
- **Who it's for**: PHP 开发者（学生至企业级），需要免费开源的 AI 编程助手
- **Space/industry**: JetBrains IDE 插件生态
- **Reference**: GitHub Copilot、JetBrains AI Assistant、Cursor

## Aesthetic Direction

- **Direction**: Utilitarian — IDE 原生集成感，功能优先于装饰
- **Accent**: 单一强调蓝 `#3574F0`（用户气泡 + 工具左栏竖线），克制不喧闹
- **Decoration level**: minimal — 颜色仅用于语义区分（用户/AI/工具/错误）
- **Mood**: 可靠、专业、不突兀。开发者不需要"漂亮"的 UI，需要不打断心流的工具

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
| `agentBar` | `#7C3AED` | `#A78BFA` | 子 Agent 左栏竖线（紫罗兰，区分普通工具蓝） |
| `agentBg` | `rgba(124,58,237,0.08)` | `rgba(167,139,250,0.13)` | 子 Agent 淡紫背景 |

### 代码 / 输入

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `codeBg` | `#F7F8FA` | `#1E1F22` | 代码块/工具结果背景 |
| `codeBorder` | `#EBECF0` | `#393B40` | 代码块边框 |
| `codeHeaderBg` | `#F0F0F0` | `#32323A` | 代码块头部栏（语言标签+复制按钮） |
| `codeLangFg` | `#888888` | `#999999` | 代码块语言标签色 |
| `codeEditorBg` | `#FAFAFA` | `#2B2B2B` | 代码块编辑器域背景 |
| `inputBg` | `#FFFFFF` | `#1E1F22` | 输入文本区背景（ChatTheme token） |
| `inputBorder` | `#D0D0D0` | `#505050` | 输入面板边框（默认） |
| `inputFocus` | `#4A90D9` | `#5A9FD4` | 输入面板边框（聚焦） |
| `chipBg` | `#E3E8EE` | `#3A3E48` | 引用/图片芯片背景（chipPanel、气泡底部 RefChip） |
| `chipBorder` | `#C0C8D0` | `#505560` | 引用/图片芯片边框 |
| `chipFg` | `#333333` | `#CCCCCC` | 引用/图片芯片文字色 |
| `chipHoverBg` | `#D0D8E8` | `#4A4E58` | 引用芯片 hover 背景（气泡底部 RefChip） |
| `submitBtnFg` | `#888888` | `#AAAAAA` | 发送/删除按钮前景（lingmaSubmitBtn、芯片 × 按钮） |

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
| `accentHover` | `#2674B4` | `#5A9FD4` | 按钮 hover 强调色（newSessionBtn、plusButton、lingmaSubmitBtn、RefChip） |
| `headerTitleFg` | `#666666` | `#AAAAAA` | 标题/描述文字色（conversationHeader、welcomePanel descLabel） |
| `inputPanelBg` | `#FAFBFC` | `#2B2D30` | 输入面板整体背景（inputPanel、welcomePanel） |
| `welcomeMutedFg` | `#888888` | `#888888` | 欢迎面板弱文字（poweredLabel） |
| `emptyHintFg` | `#767676` | `#AAAAAA` | 空态提示文字（对话区无消息时 hint） |
| `menuSelectedBg` | `#E0E8F0` | `#3A4048` | 弹窗菜单选中项背景（斜杠命令菜单、文件引用菜单） |

### 状态/语义色

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `diffDelFg` | `#C0392B` | `#D97D7D` | diff 删除行 |
| `diffAddFg` | `#2E7D32` | `#7BBD86` | diff 添加行 |
| `danger` | `#D98A3D` | `#D98A3D` | execute_command 危险边框 |
| `error` | `#B5503E` | `#E08A72` | 错误文字 |
| `errorCardBg` | `rgba(181,80,62,0.06)` | `rgba(224,138,114,0.10)` | 错误卡背景 |
| `doneCheck` | `#5AA86A` | `#5AA86A` | 已完成 ✓ 对勾（PlanBar DONE 步骤、SelectionCard 已选、审批选项 允许/始终允许） |
| `rejectedFg` | `#C0392B` | `#E08A72` | 审批拒绝后的文字色（light 暖浅红，dark 与 error 边框一致） |
| `errorBannerBg` | `#FFEBEE` | `#462828` | 错误横幅背景 |
| `errorBannerFg` | `#B00020` | `#FFB4B4` | 错误横幅文字 |
| `warningBannerBg` | `#FFF3CD` | `#3C3214` | 警告横幅背景 |
| `warningBannerFg` | `#856404` | `#FFE696` | 警告横幅文字 |

### Markdown 渲染色

| Token | 值 | 用途 |
|-------|----|------|
| `inlineCodeBgLight` | `#F0F0F0` | 内联代码背景（亮色主题） |
| `inlineCodeBgDark` | `#3C3C3C` | 内联代码背景（暗色主题） |
| `markdownLinkFg` | `#2674B4` | Markdown 链接色 |

### 审批选择卡（ToolRowFactory.approvalCard）

ask_user SelectionCard 同款样式，tool 行下方显示三个选项。

| 元素 | 样式 | 说明 |
|------|------|------|
| 卡片背景 | `toolBg` 圆角 `RADIUS`(14px) | `paintComponent` 手绘 |
| 卡片边框 | `toolBar` 色 1px 圆角 | `RoundedBorder` |
| 头部 | "审批 · toolName" `toolFontBold` + `toolFg` | 左对齐 |
| 选项-默认 | "❯ 允许本次" / "❯ 始终允许" / "❯ 拒绝" | 首项 `textPrimary`，其余 `textSecondary` |
| 选项-悬停 | `toolBg` 圆角高亮 + 文字 `textPrimary` | 8px 圆角填充 |
| 选项-已选 | "✓ ❯ 允许本次" + `doneCheck` 绿 | 所有行禁用，不可二次点击 |
| 行内边距 | `JBUI.Borders.empty(4, 4, 4, 8)` | 上下 4px，左右 4/8px |
| 列表容器 | `BoxLayout.Y_AXIS` | 靠左排列 |

## Typography

- 系统默认字体：`Font.SANS_SERIF`（Swing 跨平台兼容）
- 代码字体：`Font.MONOSPACED`
- 字号层级（`ChatTheme` + IDE 编辑器字号自适应）：
  - `bodyFont`: `JBFont.regular()`（~13px，对话内容）
  - `metaFont`: `JBFont.small()`（~11px，标签、角色名、工具行）
  - `largeFont`: `JBFont.regular().deriveFont(BOLD, size+6)`（标题/大按钮）
  - `headerFont`: `JBFont.regular().deriveFont(BOLD)`（对话头标题）
  - `codeFont`: `Font.MONOSPACED` 跟随 body 字号
  - `ToolRowFactory` 中：工具行字号 = `editorFontSize - TOOL_FONT_OFFSET`（含思考行、代码块、粗体变体），`TOOL_FONT_OFFSET=1`
  - PlanBar 描述文字：`metaFont.size - META_FONT_OFFSET`，`META_FONT_OFFSET=2`
  - Markdown 标题偏移：`HEADING_FONT_OFFSET_H1=3`、`H2=2`、`H3=1`（相对于 bodyFont 的 pt 增量）
  - 代码块语言标签：`CODE_LANG_FONT_SIZE=10`
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
| `RADIUS_INNER` | 8 | 内部元素圆角（tool 行/审批选项/SelectionCard 选项 hover 高亮） |
| `RADIUS_PROGRESS` | 4 | PlanBar 进度条圆角 |

- 基础单位：4px（IntelliJ `JBUI` 工具类）
- 输入区最小高度：80px
- 对话区最小宽度：100px

### 图标/控件尺寸

| Token | 值 | 用途 |
|------|----|------|
| `ARROW_WIDTH` | 14 | 箭头/chevron 图标最小宽度（ToolRowFactory、SelectionCard） |
| `CHECK_WIDTH` | 16 | 选择卡复选框宽度（SelectionCard 多选模式） |
| `SPINNER_MIN_W` | 14 | 盲文 spinner 标签最小宽度（防抖动） |

### PlanBar 尺寸约束

| Token | 值 | 用途 |
|------|----|------|
| `PLAN_STEP_MAX_H` | 168 | 弹出步骤列表最大高度 |
| `PLAN_STEP_ROW_H` | 24 | 单步最大高度 |
| `PLAN_PROGRESS_W` | 60 | 迷你进度条宽度 |
| `PLAN_PROGRESS_H` | 5 | 迷你进度条高度 |

### 气泡/工具行宽度约束

| Token | 值 | 用途 |
|------|----|------|
| `BUBBLE_WIDTH_DEDUCT` | 20 | BubbleFactory 气泡宽度扣除量 |
| `TOOL_PREVIEW_DEDUCT` | 24 | ToolRowFactory args 预览扣除量 |

### 文本截断限制

| Token | 值 | 用途 |
|------|----|------|
| `ARGS_PREVIEW_MAX_CHARS` | 120 | 工具 args 参数预览最大字符数 |
| `RESULT_MAX_CHARS` | 2000 | 工具 result 内容展示最大字符数 |
| `THINKING_PREVIEW_MAX_CHARS` | 100 | 思考行折叠摘要最大字符数（双行截断） |

## Border Radius

- 气泡：14px 四角统一圆角（`ChatBubble.paintComponent` + Graphics2D 抗锯齿渲染）
- 代码块/工具结果：4px 轻微圆角
- 输入框：14px 圆角，2px 聚焦描边
- 选项行 hover：8px 圆角
- 审批选择卡：14px 圆角

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
│       └── conversationContainer (BoxLayout.Y_AXIS, JBScrollPane 强制视图宽=视口宽)
│           ├── rowPanel [glue] [ChatBubble]  ← 用户靠右
│           ├── rowPanel [ChatBubble] [glue]  ← AI 靠左
│           ├── ToolRowFactory 折叠行
│           ├── SelectionCard（ask_user 选择卡）
│           └── 流式气泡
└── SOUTH  → inputPanel（引用芯片 + 图片芯片 + textarea + 发送按钮）
```

**关键机制：**
- `conversationContainer`：直接作为 `JBScrollPane` 视图。`HORIZONTAL_SCROLLBAR_NEVER` 强制视图宽度 = 视口宽，确保 rowPanel 的 X_AXIS glue 有足够空间把气泡推到正确对侧
- `BoxLayout.Y_AXIS` 无 vertical glue → 内容从顶部开始，多余空间留白在底部，等价 web `flex-direction: column`
- 气泡对齐：`rowPanel`（X_AXIS）+ `Box.createHorizontalGlue()`，等价 CSS `justify-content`
- **滚动策略**：两种方法分工明确：
  - `scrollToBottom()`：无条件立即滚动到底部，用于 rebuildConversation、首次创建流式组件、showSelectionCard 等需要确保可见性的场景
  - `autoScrollIfAtBottom()`：流式更新专用，仅当用户已在底部 80px 内时才滚动到底部，确保流式输出跟随流畅同时不打断用户浏览历史
  - **禁止通过 `caretPosition` 驱动滚动**：`JTextComponent.caretPosition` 会触发 `scrollRectToVisible` 强制父级 JScrollPane 滚动到光标位置，无视用户滚动意图。流式更新中不设置 caretPosition，统一由上述两个方法管理
- **代码块内层 JScrollPane**：代码块由外层 JScrollPane 包裹（`HORIZONTAL_SCROLLBAR_AS_NEEDED`），用于长行横向滚动。通过 `setWheelScrollingEnabled(false)` 禁用内层滚轮事件消费，确保鼠标在代码块上时滚轮事件冒泡到 `conversationScrollPane`，对话可正常上下翻阅

## Components

### 工具执行行 — streamingToolRow

所有工具执行时统一显示可折叠运行行，**默认折叠**：
- **折叠态**：`▸ ⠋ 执行中 · toolName`（spinner + 工具名），task 用紫色左栏，其他用蓝色
- **展开态**：`▾ ⠋ 执行中 · toolName` + 结构化内容区：
  - 纯文本：`JTextArea`
  - 子代理工具调用：**组件化行**（`subAgentToolRunningRow` / `subAgentToolResultRow`），紫色左栏（`agentBar`），默认折叠可展开，与主 Agent 蓝色工具行区分
- **点击标题行**切换折叠/展开
- **执行完成**：清理运行行，`rebuildConversation` 渲染最终 `toolResultRow`

### 子代理工具行 — subAgentToolRow

子代理内部工具调用以组件化行渲染在流式工具行的内容区中：
- **运行中**：`▸ ⠋ toolName`（`agentBar` 色 spinner，默认折叠）
- **结果**：`▸ 结果 · toolName`（`agentBar` 左栏，默认折叠，可展开查看结果）
- 与主 Agent 工具行（蓝色 `toolBar`）通过紫色 `agentBar` 区分

### SubAgentCard — 子代理结果卡片

子 Agent 结果复用 `toolResultRow` 组件：
- **task 工具**：紫色左栏（`agentBar`），与普通工具蓝色（`toolBar`）区分
- **折叠态**：`▸ 结果 · task` + 内容预览
- **展开态**：完整输出含工具调用日志

### PlanBar — 置顶计划条

LLM 通过 `create_plan` 元工具自主创建执行计划，`update_plan_step` 更新步骤状态。

**Plan 数据结构：**
- `Plan(title, steps)` — 每个 `Step` 含 `index`（全局唯一递增）、`subject`（必填短名称）、`description`（可选详细描述）、`status`、`result`
- 重复调用 `create_plan` 时**追加**而非覆盖——新步骤自动合并到已有计划末尾，`index` 从上次最大 +1 继续
- 所有 `steps` 访问受 `synchronized` 保护，EDT 侧通过 `stepsSnapshot()` 获取只读快照

**显示规则：**
- 折叠态：chevron + 标题 + 进度 "2/4" + 当前步骤 `subject` + 迷你进度条
- 展开态：步骤列表通过 `JLayeredPane.POPUP_LAYER` 悬浮展示，**不推挤下方对话区**
- 每行显示 `subject`（主名称）+ `description`（灰色小字副描述，可选）+ 状态图标
- 步骤过多时内部滚动（max-height 168px），点击对话区自动收起
- **增量更新**：`update_plan_step` 后同引用 Plan 不重建，只更新变化的步骤行（marker + 颜色 + 字体）+ 进度文字/进度条
- 全部完成自动隐藏

**LLM 上下文注入：**
- 每次 API 调用前通过 `buildPlanPrompt()` 动态生成计划状态提示，注入到 system prompt
- 包含完整步骤列表、各步骤状态标记（✅🔄⏳❌）、进度百分比
- 强制指令：`ask_user` 或其他工具调用后必须继续执行下一步，禁止提前结束对话

### SelectionCard — ask_user 选择卡
- 单选模式：点击即提交，首项默认高亮，点击后卡片切换为"已选择"静态状态
- 多选模式：每行 ☐/☑ 复选框 + 底部"确认 (N)"按钮（初始禁用，至少选一项后启用）
- 选项 hover 高亮 + toolBg 背景（8px 圆角填充）

### ToolRowFactory — 工具/思考折叠行
- 统一左侧 3px 色条（`toolBar`）+ 淡 `toolBg` 背景
- `toolCallRow`: 列出每个 toolCall 的 name + args 预览，不可折叠
- `toolResultRow`: 默认折叠 "结果 · toolName" + "✓ N 行"，展开显示内容（超 2000 chars 截断）
- `thinkingRow`: 默认折叠，摘要取前 100 chars 双行截断；展开显示全文。流式展示时 `initiallyExpanded=true`，通过 `updateStreamingThinking()` 原地更新 JTextArea 文本（`area.text = content`），避免 remove/add 布局震荡。**不设置 caretPosition**，滚动由 `autoScrollIfAtBottom()` 统一管理
- `runningRow`: 盲文 spinner（⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏）+ "执行中 · toolName"
- `errorCardRow`: 失败时红色左栏 + "✕ toolName 失败" + errorCodeBg 详情
- **审批选项**（`approvalActions != null` 时启用）：
  - 审批选项直接内联到 tool 结果 bubble 内，紧接头部行下方，不创建独立卡片
  - 三个纵向排列的 chevron 选项行：`❯ 允许本次` / `❯ 始终允许` / `❯ 拒绝`
  - 点击选项 → 标记为已选（✓ 绿）→ 所有行禁用 → 回调执行
  - 交互模式：单选，点击即提交，不可撤销

## Input Commands（输入命令）

输入区支持 `/` 和 `@` 两个触发前缀，弹出筛选菜单供选择。

### `/` — 斜杠命令

**触发**：文本框以 `/` 开头时弹出命令菜单。

**弹出样式**：
- 宽度与 `inputPanel` 一致
- 高度 = `min(itemCount * 28, 280)` px
- 紧贴输入框上方显示（`popup.show(inputPanel, 0, -height)`）

**键盘交互**：Up/Down 移动选择，Enter 确认，Esc 关闭。通过 `KeyboardFocusManager.addKeyEventDispatcher()` 在 `JTextArea` 的 Keymap 之前全局拦截方向键，确保弹窗可见时箭头键用于列表导航。

**实时筛选**：输入变化时按命令名和描述过滤，最多显示 10 项。筛选时先 `popup.isVisible = false` 隐去再 `rebuildItems()` + `popup.show()` 重显，确保弹窗高度随结果数量自适应且始终紧贴输入框上方。

**内置命令**：

| 命令 | 描述 | 行为 |
|------|------|------|
| `/new` | 新会话 | `clearConversation()` + `rebuildConversation()` |
| `/plan` | 创建执行计划 | 发送 "请为当前任务创建执行计划。先分析需求，然后调用 create_plan 工具。" |
| `/init` | 初始化项目文档 | 发送 "请分析当前项目结构，创建 CLAUDE.md 文档..." |
| `/review` | 审查当前改动 | 发送 "请审查当前分支的代码改动..." |
| `/test` | 运行测试 | 发送 "请运行 ./gradlew test，分析测试结果并修复失败的测试。" |
| `/stop` | 停止生成 | `viewModel.stopGeneration()` |
| `/clear` | 清空输入 | 清空 inputArea 文本 |

**Skill 集成**：`getSkillNames()` 的技能名自动出现在菜单中，选择后发送 `"请使用 {skillName} skill 执行任务"`。

### `@` — 文件/目录引用

**触发**：在行首或空白后输入 `@` 时弹出选择菜单。

**弹出样式**：与 `/` 命令一致（宽度、高度、位置、键盘交互）

**筛选**：输入 `@partialname` 实时过滤项目文件和目录，最多 50 项。目录以 `/` 后缀标识

**选择文件**：读取文件内容，添加 `RefChip` 引用芯片

**选择目录**：生成一级目录列表（`dir.listFiles()`），每个条目 `"  filename"` 或 `"/dirname"` 区分文件和子目录，不读取文件内容

### RefChip — 文件引用芯片

**输入区芯片**（可删除）：
- 显示在 `chipPanel` 输入区上方，灰色圆角矩形背景 + 边框，文件名标签 + `×` 删除按钮（hover 变蓝）
- 去重：相同文件 + 行号范围的芯片不会重复添加
- 使用 `WrapLayout`（自定义 `FlowLayout` 变体）：多芯片自动换行，`preferredSize` 基于容器宽度模拟换行计算真实高度，面板随芯片数量向上拉伸，确保所有芯片可见
- `inputPanel.preferredSize` 动态计算（`super.getPreferredSize()`），不再硬编码固定高度

**气泡下方芯片**（只读）：
- 引用文件 chips 嵌入用户气泡底部，与消息文本同属一个气泡组件（上半部分消息文本 + 下半部分 chips 面板）
- 无引用时不显示 chips 区域，气泡退化为纯文本气泡
- 格式 `📄 文件名:行号`，`WrapLayout(RIGHT)` 靠右排列，多芯片自动换行，宽度受气泡约束，高度动态计算防止裁切
- 点击芯片 → 在编辑器中打开文件，有行号则自动跳转并居中；hover 变蓝高亮
- 引用内容不存入消息文本——气泡仅显示用户输入，引用全文通过 `refContent` 参数单独发送给 LLM

**编辑器选区自动引用**：
- 通过 `EditorFactoryListener` + `SelectionListener` 监听编辑器内文本选中事件
- 选中后自动添加芯片（发送文件全文 + 标记行号 `#L5-L10`，500KB 上限）
- **选区引用最多一个**：变更选区时直接更新已有芯片（替换文件/行号/内容），而非新增多个
- 100ms 防抖 + 3 秒相同 hash 去重，仅工具窗口可见时生效

### 新会话按钮（`newSessionBtn`）

- 位于 `conversationHeader` 右侧，显示 "+"，字号 18
- 默认色 `JBColor(0x888888, 0x999999)`，hover 变蓝 `JBColor(0x2674B4, 0x5A9FD4)`
- 点击：清空对话 + 重建视图

### 加号按钮（`plusButton`）

- 位于输入区左上角，显示 "+"，字号 20 粗体
- 颜色和 hover 效果与新会话按钮一致
- 点击在输入框光标处插入 `@`，复用 `@` 文件引用弹窗及实时筛选机制

### 图片粘贴

- 支持从剪贴板粘贴图片（`TransferHandler`）
- 粘贴后显示为图片芯片（与 RefChip 同款样式，"图片 N" + × 删除）
- 发送时使用 **Claude 原生 image 块格式**（base64 内存直传，不落盘）：
  ```json
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64>"}}
  ```
- 图片块与文本块并列于 `content` 数组中，非 Markdown data URL

## Interaction States

| 状态 | 视觉表现 |
|------|---------|
| 默认 | 正常颜色 + 实线边框 |
| 错误 | 红色横幅 `JBColor(0xFFEBEE, 0x462828)` + 具体错误消息 + 修复建议 |
| 警告 | 黄色横幅 `JBColor(0xFFF3CD, 0x3C3214)` + 倒计时 |
| 流式生成中 | 发送按钮 "■" + 输入框禁用 + 思考/回复流式原地更新 |
| 工具执行中 | 盲文 spinner 动画 + "执行中 · toolName" |
| 工具完成 | 折叠行 "✓ N 行" |
| 工具失败 | 红色错误卡 |
| 权限确认 | 审批选项内联，阻塞等待 + CountDownLatch |
| ask_user | SelectionCard 内联，阻塞等待用户选择 |
| hover（选项行） | chevron "❯" 出现 + 文字变为 textPrimary + toolBg 背景 |
| 已确认 | 卡片替换为静态标签 "已允许 ✓" / "已拒绝 ✗" |
| 审批选择卡-选项 | chevron ❯ + 文字（首项 textPrimary，其余 textSecondary） |
| 审批选择卡-悬停 | 选项行 toolBg 圆角高亮 + 文字变 textPrimary |
| 审批选择卡-点击 | 标记已选 ✓（doneCheck 绿）+ 所有行禁用 + 执行回调 |

## Accessibility

- 所有文字对比度 ≥ 4.5:1（WCAG AA），textMuted 在浅色主题约为 3.5:1（辅助信息可接受）
- 交互元素通过 IntelliJ 主题自动适配暗色模式
- 键盘导航：Tab 序（输入框 → 停止 → 文件 → 引用 → 发送）
- 错误消息包含具体修复步骤（不是仅"失败了"）
- 盲文 spinner 通过 `addNotify/removeNotify` 管理 Timer 生命周期，防止泄漏

## CodeIntelligenceTool — PSI 代码智能

通过 IntelliJ PSI（Program Structure Interface）API 提供结构化代码导航，替代 `grep` 文本搜索。单一工具多操作模式。

### 支持的操作

| 操作 | IntelliJ API | 说明 |
|------|-------------|------|
| `go_to_definition` | `PsiElement.reference?.resolve()` | 跳转到符号定义位置 |
| `find_references` | `ReferencesSearch.search(element)` | 查找所有引用点 |
| `hover` | 类型推导 + `PsiDocCommentOwner` | 返回符号类型和文档 |
| `document_symbols` | `PsiFile` 子元素遍历 | 文件的所有顶层符号 |
| `workspace_symbol` | `PsiShortNamesCache` + `FilenameIndex` | 按名称全局搜索符号 |
| `find_implementations` | `ClassInheritorsSearch.search(psiClass)` | 查找接口/抽象类实现（需 `com.intellij.java` 可选依赖） |

### 依赖

Java PSI API（`PsiClass`、`ClassInheritorsSearch`）通过 `com.intellij.java` **可选依赖**提供。在 Java/Kotlin IDE 上自动可用，WebStorm/PyCharm 等无 Java 模块的 IDE 上 `find_implementations` 会返回友好错误提示。

### 输入参数

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `operation` | string(enum) | 是 | 操作类型 |
| `file_path` | string | 除 workspace_symbol 外 | 文件相对路径 |
| `line` | integer | 除 workspace_symbol/document_symbols 外 | 1-based 行号 |
| `character` | integer | 除 workspace_symbol/document_symbols 外 | 1-based 字符偏移 |
| `query` | string | workspace_symbol 时必需 | 符号搜索词 |
| `max_results` | integer | 否，默认 20 | 最大结果数 |

### 输出格式

统一结构化输出，方便 LLM 解析：

```
### 操作: find_references
符号: FooClass (src/main/.../Foo.kt:10:6)
找到 3 个引用:
1. src/.../Bar.kt:15:8  |  val foo = FooClass()
2. src/.../Baz.kt:23:4  |  foo.doSomething()
3. src/.../Foo.kt:10:6  |  class FooClass {  ← 定义
```

### 实现要点

- 元素定位：`file_path` + `line` + `character` → `VirtualFile` → `PsiFile.findElementAt(offset)`
- 全体 `execute()` 包在 `ApplicationManager.getApplication().runReadAction<ToolResult> { ... }` 中——Agent 后台线程无 PSI 读权限，必须通过 read action 访问 Index/PSI
- 只读操作，加入 `SAFE_TOOLS` 白名单，无需权限确认
- `find_implementations` 通过 `com.intellij.java` 可选依赖提供，无 Java 模块的 IDE 上友好降级
- `call_hierarchy` 留到 v2（`CallHierarchyProvider` 是 per-language EP）

### 相关配置

`build.gradle.kts` 中添加 `com.intellij.java` 编译期依赖，`plugin.xml` 中声明 `optional="true"`：

## Icons

- 插件图标使用自定义 SVG：`src/main/resources/icons/icon.svg`
- UI 内使用 Unicode 符号：❯（chevron）、▸/▾（展开/收起箭头）、⚠（危险）、✕（失败/拒绝）、✓（成功）
- 盲文 spinner 帧：⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏

## 颜色/尺寸管理

所有颜色、间距、圆角、字号、宽度约束统一在 `ChatTheme.kt` 中管理。禁止在 UI 代码中硬编码 Hex 颜色值或 magic number。

## 文件引用芯片（RefChip）

芯片仅存储文件路径和行号（`fullPath` + `startLine`/`endLine`），不预载文件内容。
发送时 `buildRefContent()` 告知模型引用的文件路径和行号信息，模型按需调用 `read_file` 读取具体内容。

## 路径安全

`read_file` 工具通过 `PathUtils.isInsideProject()` 检测 canonical path 前缀。
- 项目目录内：免审批直接读取
- 项目目录外：触发审批卡，用户确认后执行；拒绝则返回"文件不存在"

## 审批系统

- `ApprovalActions` 定义审批回调（允许本次 / 始终允许 / 拒绝），由 `ToolRowFactory.buildApprovalOptions()` 渲染
- 审批选项内联到工具结果行 bubble 中，不再创建独立卡片
- `PermissionCard` 已废弃删除
- `messageRefChips` 改用消息 ID（`AgentMessage.id`）索引，不再依赖消息数组位置
- `execute_command` 不做黑名单拦截，所有命令执行前统一由审批卡确认
