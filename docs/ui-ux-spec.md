# Code Assistant — UI/UX 设计规范

关联文档：[DESIGN.md](../DESIGN.md) | [tech-spec.md](tech-spec.md)

---

## 一、设计系统

### 1.1 色板

```
主色调
┌─────────────────────────────────────────────────────────────┐
│ Primary:       #3B82F6  ████████  主操作按钮、链接、选中态      │
│ Primary Hover: #2563EB  ████████  按钮 hover                  │
│ Primary Light: #EFF6FF  ████████  选中背景、tag 背景           │
├─────────────────────────────────────────────────────────────┤
│ 语义色                                                       │
│ Success:       #22C55E  ████████  DONE、成功 toast            │
│ Warning:       #F59E0B  ████████  警告横幅、AWAITING_APPROVAL │
│ Error:         #EF4444  ████████  错误气泡、ERROR 状态        │
│ Error Light:   #FEE2E2  ████████  错误背景                    │
├─────────────────────────────────────────────────────────────┤
│ 中性色                                                       │
│ Gray 900:      #111827  ████████  标题、正文                  │
│ Gray 700:      #374151  ████████  次要文字                    │
│ Gray 500:      #6B7280  ████████  辅助文字、placeholder       │
│ Gray 300:      #D1D5DB  ████████  边框                        │
│ Gray 200:      #E5E7EB  ████████  分割线                      │
│ Gray 100:      #F3F4F6  ████████  卡片背景                    │
│ Gray 50:       #F9FAFB  ████████  页面背景                    │
├─────────────────────────────────────────────────────────────┤
│ 代码块                                                       │
│ Code BG:       #F6F8FA  ████████  EditorTextField 背景       │
│ Code Border:   #E1E4E8  ████████  代码块边框                  │
├─────────────────────────────────────────────────────────────┤
│ 扩展色                                                       │
│ Gray 400:      #9CA3AF  ████████  禁用文字、时间戳、系统消息    │
│ Blue 50:       #E8F0FE  ████████  用户气泡背景                 │
│ Blue 700:      #1D4ED8  ████████  按钮 Pressed                │
│ Blue 200:      #BFDBFE  ████████  tag 边框                    │
│ Amber 50:      #FFFBEB  ████████  审批等待背景                 │
└─────────────────────────────────────────────────────────────┘
```

#### 暗色主题色板

```
┌─────────────────────────────────────────────────────────────┐
│ 主色调                                                       │
│ Primary:       #60A5FA  ████████  主操作按钮、链接、选中态      │
│ Primary Hover: #93BBFD  ████████  按钮 hover                  │
│ Primary Light: #1E3A5F  ████████  选中背景（15% 透明叠加）      │
├─────────────────────────────────────────────────────────────┤
│ 语义色                                                       │
│ Success:       #4ADE80  ████████  DONE、成功 toast            │
│ Warning:       #FBBF24  ████████  警告横幅、AWAITING_APPROVAL │
│ Error:         #F87171  ████████  错误气泡、ERROR 状态        │
│ Error Light:   #7F1D1D  ████████  错误背景（暗色）             │
├─────────────────────────────────────────────────────────────┤
│ 中性色                                                       │
│ Gray 900:      #E5E7EB  ████████  标题、正文（浅色文字）        │
│ Gray 700:      #D1D5DB  ████████  次要文字                    │
│ Gray 500:      #9CA3AF  ████████  辅助文字、placeholder       │
│ Gray 300:      #4B5563  ████████  边框（暗色）                 │
│ Gray 200:      #374151  ████████  分割线（暗色）               │
│ Gray 100:      #1F2937  ████████  卡片背景（暗色）             │
│ Gray 50:       #111827  ████████  页面背景（暗色）             │
├─────────────────────────────────────────────────────────────┤
│ 代码块                                                       │
│ Code BG:       #1E1E2E  ████████  EditorTextField 背景       │
│ Code Border:   #2D2D3F  ████████  代码块边框                  │
├─────────────────────────────────────────────────────────────┤
│ 扩展色                                                       │
│ Gray 400:      #6B7280  ████████  禁用文字、时间戳、系统消息    │
│ Blue 50:       #1E3A5F  ████████  用户气泡背景（暗色）          │
│ Blue 700:      #BFDBFE  ████████  按钮 Pressed（暗色）         │
│ Blue 200:      #1E40AF  ████████  tag 边框（暗色）             │
│ Amber 50:      #422006  ████████  审批等待背景（暗色）          │
└─────────────────────────────────────────────────────────────┘
```

**暗色主题适配规则（AppColors.kt 实现层）：**

- 通过 `JBColor(lightColor, darkColor)` 实现自动切换
- 背景色遵循 IntelliJ 暗色主题（Darcula/New Dark）的基准色温
- **色阶反转约定：** 暗色主题下色阶号与亮度成反比——Gray 900 为最亮（`#E5E7EB`），Gray 50 为最暗（
  `#111827`），与亮色主题相反。这是有意为之的设计策略，确保暗色主题下文字/背景/边框的视觉层级与亮色主题一致
- 代码块背景使用 `#1E1E2E`，与 IntelliJ Darcula 编辑器背景一致
- 文字对比度在暗色主题下仍需满足 WCAG AA（≥4.5:1）

### 1.2 字体层级

| 层级            | 字体                     | 大小   | 行高  | 用途                  |
|---------------|------------------------|------|-----|---------------------|
| H1            | SansSerif Bold         | 20px | 1.4 | 页面标题                |
| H2            | SansSerif Bold         | 16px | 1.4 | 卡片标题、Section 标题     |
| H3            | SansSerif Semibold     | 14px | 1.4 | 气泡内标题               |
| Body          | SansSerif Regular      | 14px | 1.6 | 正文、聊天内容             |
| Body Small    | SansSerif Regular      | 12px | 1.5 | 辅助信息、时间戳、token 统计   |
| Caption       | SansSerif Regular      | 11px | 1.4 | 标签、badge            |
| Code          | JetBrains Mono Regular | 13px | 1.5 | 代码块、行内代码            |
| Code Fallback | Monospaced Regular     | 13px | 1.5 | JetBrains Mono 不可用时 |

### 1.3 间距体系

```
基础单位: 4px

gap-xs:   4px    ── 紧密元素间距（icon+文字）
gap-sm:   8px    ── 气泡间、tag 间
gap-md:   12px   ── 气泡内 padding
gap-lg:   16px   ── Section 间距
gap-xl:   24px   ── 页面 margin

inset-xs: 4px 8px   ── tag、badge 内边距
inset-sm: 8px 12px  ── 小型卡片
inset-md: 12px 16px ── 标准卡片、气泡
inset-lg: 16px 24px ── 大型面板
```

### 1.4 圆角

| 元素        | 圆角   | 说明                 |
|-----------|------|--------------------|
| 气泡        | 12px | 用户+Agent 气泡        |
| 代码块       | 8px  | EditorTextField 外框 |
| 工具卡片      | 8px  | ToolCallCard       |
| 按钮        | 6px  | 操作按钮               |
| Tag       | 4px  | 文件引用/图片 tag        |
| 输入框       | 8px  | JTextArea 外框       |
| TabBar 按钮 | 0    | 直角，底边框高亮           |

### 1.5 阴影

```
阴影仅用于悬浮元素，不用于静态卡片:

tooltip:    0px 4px 12px rgba(0,0,0,0.10)    — 悬浮提示、Popup
dialog:     0px 8px 24px rgba(0,0,0,0.15)    — 弹窗（审批、确认）
无阴影:     气泡、卡片、输入框                  — 扁平设计
```

### 1.6 图标

使用 IntelliJ Platform 内置图标集（`com.intellij.icons.AllIcons`），不引入外部图标库。

| 用途                       | 图标引用                                    |
|--------------------------|-----------------------------------------|
| TabBar - Chat            | `AllIcons.Actions.Forum`                |
| TabBar - Sessions        | `AllIcons.Actions.ListFiles`            |
| TabBar - Token Usage     | `AllIcons.Actions.ShowAsPieChart`       |
| TabBar - MCP             | `AllIcons.Nodes.Plugin`                 |
| TabBar - Skills          | `AllIcons.Actions.Star`                 |
| TabBar - Settings        | `AllIcons.General.Gear`                 |
| TabBar - Welcome         | `AllIcons.Actions.Home`                 |
| ToolCallCard - DONE      | `AllIcons.RunConfigurations.TestPassed` |
| ToolCallCard - ERROR     | `AllIcons.RunConfigurations.TestFailed` |
| ToolCallCard - EXECUTING | `AllIcons.Process.Step_4`（旋转动画）         |
| ToolCallCard - PENDING   | `AllIcons.Process.Step_0`               |
| 发送按钮                     | `AllIcons.Actions.Commit`               |
| 停止按钮                     | `AllIcons.Actions.Suspend`              |
| 文件引用 tag                 | `AllIcons.FileTypes.Text`               |
| 图片 tag                   | `AllIcons.FileTypes.Image`              |

---

## 二、组件状态矩阵

> **标注约定：** 组件规格使用 CSS 风格简写（`bg`、`fg`、`border`、`cursor`、`padding` 等），
> 便于设计评审。实际 Swing 实现对应关系见 [`tech-spec.md`](tech-spec.md) 各组件 API。

### 2.1 按钮

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

### 2.2 输入框

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

### 2.3 TabBar 按钮

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
│ Hidden   │ display:none                    │
│          │ 无 API Key 时隐藏 Welcome 以外   │
│          │ 有 API Key 时隐藏 Welcome        │
├──────────┼─────────────────────────────────┤
│ Badge    │ 右上角角标                       │
│          │ bg=#EF4444, fg=#FFFFFF, 圆形 8px  │
│          │ 数字 > 99 → "99+"               │
│          │ v1 仅 Sessions 页面有角标（未读会话数）。后续可按需扩展到其他页面。               │
└──────────┴─────────────────────────────────┘
```

### 2.4 聊天气泡

```
用户气泡:
┌────────────────────────────────────────────┐
│ bg=#E8F0FE 圆角=12px                      │
│ 右对齐，maxWidth=容器宽度×70%（默认 300-500px）│
│ 响应式变体见第五节                            │
│ padding=12px                              │
│ 文字=Body (14px)                          │
│ 底部: 时间戳 Caption fg=#9CA3AF 右对齐     │
└────────────────────────────────────────────┘

Agent 气泡:
┌────────────────────────────────────────────┐
│ bg=#FFFFFF 圆角=12px                      │
│ 左对齐，maxWidth=容器宽度×85%（默认 300-500px）│
│ border-left=3px #3B82F6                   │
│ padding=12px                              │
│ 文字=Body (14px)                          │
│ 底部: 时间戳 + token 消耗 Caption          │
└────────────────────────────────────────────┘

流式气泡（生成中）:
┌────────────────────────────────────────────┐
│ 与 Agent 气泡样式相同                       │
│ 末尾闪烁光标 ▍ (#3B82F6, 500ms blink)      │
│ 30ms batch flush → 增量渲染最后一段         │
│ 未闭合代码块 → 缓存等待                     │
└────────────────────────────────────────────┘

错误气泡:
┌────────────────────────────────────────────┐
│ bg=#FEE2E2 圆角=12px                      │
│ border-left=3px #EF4444                   │
│ 图标: ❌ (AllIcons.RunConfigurations.TestFailed)│
│ [重试] 按钮                                │
│ [复制错误信息] 按钮                         │
└────────────────────────────────────────────┘

系统消息:
┌────────────────────────────────────────────┐
│ 居中，fg=#9CA3AF Caption (11px)           │
│ 无背景，仅文字                              │
│ 示例: "── 14:30 ──"                         │
└────────────────────────────────────────────┘
```

### 2.5 工具调用卡片

```
8 个状态:
┌───────────────┬──────────┬──────────────────┐
│ PENDING       │ ⏳ Gray 500 │ 等待执行，fg=#6B7280         │
│               │ icon=Step_0│                  │
├───────────────┼──────────┼──────────────────┤
│ AWAITING_APPR │ 🔒 黄色   │ 等待用户审批     │
│               │ icon=Warning│ [批准] [拒绝]  │
│               │ bg=#FFFBEB│ 无超时           │
├───────────────┼──────────┼──────────────────┤
│ EXECUTING     │ 🔄 旋转   │ 执行中           │
│               │ icon=Step_4│ 不可折叠        │
│               │ 进度条    │ [⏹ 终止]         │
├───────────────┼──────────┼──────────────────┤
│ DONE          │ ✅ 绿色   │ 执行成功         │
│               │ icon=TestPassed│ 默认折叠   │
├───────────────┼──────────┼──────────────────┤
│ ERROR         │ ❌ 红色   │ 执行失败         │
│               │ icon=TestFailed│ 默认展开   │
│               │           │ [重试] 按钮      │
├───────────────┼──────────┼──────────────────┤
│ TIMEOUT       │ ⏰ 橙色   │ 超时             │
│               │ icon=Warning│ 默认展开       │
├───────────────┼──────────┼──────────────────┤
│ REJECTED      │ 🚫 灰色   │ 用户拒绝         │
│               │ icon=Suspend│ [重审] 按钮    │
├───────────────┼──────────┼──────────────────┤
│ CANCELLED     │ ⛔ 灰色   │ 用户终止         │
│               │ icon=Suspend│ 默认折叠       │
└───────────────┴──────────┴──────────────────┘

卡片结构:
┌──────────────────────────────────────────┐
│ 🔧 Bash                        ⏳   │ ← 头部(图标+名称+状态), 可点击折叠
├──────────────────────────────────────────┤
│ 参数: command="./gradlew build"          │ ← 灰色 monospaced 12px
│       workDir="./"                       │    (折叠时隐藏，显示摘要)
├──────────────────────────────────────────┤
│ 结果:                                    │ ← 出现后自动展开
│ BUILD SUCCESSFUL in 12s                  │    bg=#F6F8FA monospaced 13px
│                                          │    max 15 行 + 滚动条
├──────────────────────────────────────────┤
│ 耗时: 12.3s       [复制]                │ ← 底部状态行 Caption
└──────────────────────────────────────────┘
```

### 2.6 Plan 卡片

```
┌──────────────────────────────────────────┐
│ 📋 执行计划                    [展开全部]│ ← 头部
│ 任务: {summary}                          │ ← Body Small
│ 涉及: {fileCount}个文件 {toolList}        │
├──────────────────────────────────────────┤
│ Step N:                                  │ ← 每步独立行
│ ⬜/🔄/✅/❌/🗑  {description}              │ ← 状态图标 + 描述
│    工具: {tool}  文件: {fileList}        │ ← Caption
│                           [✕]  ← PENDING │ ← 用户可删除待执行步
├──────────────────────────────────────────┤
│ 进度: 3/5 已完成                          │ ← 底部状态行
└──────────────────────────────────────────┘

Step 状态样式:
⬜ PENDING:   fg=#6B7280, bg=transparent, 行末 [✕] 可见
🔄 EXECUTING: fg=#3B82F6, bg=#EFF6FF, 左侧蓝色竖线 2px
✅ DONE:      fg=#22C55E, bg=transparent
❌ ERROR:     fg=#EF4444, bg=#FEE2E2, 行末 [✕] 可见
🗑 DELETED:   fg=#9CA3AF, bg=transparent, 删除线

// 所有步骤自动连续执行，无全局暂停/继续/取消按钮。
// LLM 通过 listTasks/deleteTask/reorderTasks 工具自主管理任务列表。
```

### 2.7 Tag（文件引用 / 图片缩略图）

```
文件引用 Tag:
┌────────────────────────────────┐
│ 📄 UserService.kt:40-60 [✕]   │ ← bg=#EFF6FF, border=#BFDBFE
│                                │   圆角 4px, inset-xs (4px 8px)
│    点击 ✕ → 移除引用          │   可多个排列在 FlowLayout 行中
└────────────────────────────────┘

图片缩略图 Tag:
┌────────────────────────────────┐
│ [🖼48×48] screenshot.png [✕]   │ ← 缩略图 bg=#F3F4F6, 右侧文件名
│           34KB                 │   圆角 4px, 可多个排列
│    点击缩略图 → 1:1 预览弹窗   │   hover 时显示原图预览 tooltip
│    点击 ✕ → 移除图片           │
└────────────────────────────────┘
```

### 2.8 Toast / 横幅

```
Toast（右下角弹出，3s 自动消失）:
┌────────────────────────────────┐
│ ✅ API Key 验证成功            │ ← bg=#22C55E, fg=#FFFFFF
│                                │   圆角 8px, inset-md
│ ❌ 网络不可用，Key 暂未验证     │ ← bg=#EF4444
│                                │   从底部滑入 (300ms ease-out)
│ ⚠ 项目正在建立索引，搜索结果   │ ← bg=#F59E0B, fg=#FFFFFF
│   可能不完整                   │
└────────────────────────────────┘

横幅（页面顶部，持续显示直到条件消除）:
┌──────────────────────────────────────────────────────────┐
│ 🔄 正在验证 API Key...                                   │ ← Chat 页顶部
│    bg=#EFF6FF, fg=#3B82F6, 无关闭按钮                    │
├──────────────────────────────────────────────────────────┤
│ ⚠ API Key 无效，请检查 Settings                          │ ← Chat 页顶部
│    bg=#FEE2E2, fg=#EF4444, [✕ 关闭] [⚙ Settings]       │
└──────────────────────────────────────────────────────────┘
```

### 2.9 思考过程（Reasoning Block）

```
┌─────────────────────────────────────────┐
│ ▶ 💭 思考过程                    1.2s   │ ← 头部（可点击折叠/展开）
│                                         │   字体: Caption 12px
│                                         │   fg=#B45309, bg=#FFF8F0 (亮色)
│                                         │   fg=#FBBF24, bg=#422006 (暗色)
│  展开后:                                │
│  斜体 Body Small 12px, fg=#92400E       │
│  line-height: 1.5                       │
│  max-height: 200px, overflow-y: auto    │
│  border-top: 1px solid #FDE8D0          │
├─────────────────────────────────────────┤
│ Default:  折叠（隐藏正文）               │
│ Expanded: 展开 + 箭头旋转 90°           │
│ 内容不持久化到 Session JSON             │
└─────────────────────────────────────────┘
```

### 2.10 选项组件（OptionsBlock）

```
┌─────────────────────────────────────────┐
│ 选择下一步操作：                         │ ← 标题 (Body, #374151)
│                                         │    bg=#F9FAFB (亮) / #111827 (暗)
├─────────────────────────────────────────┤
│ ● A  直接用 Edit 修改               │ ← 选项行（每行可点击）
│       修改 UserService.kt 和相关调用处    │    hover: bg=#EFF6FF (亮) / #1E3A5F (暗)
├─────────────────────────────────────────┤
│ ○ B  生成 Plan 确认后再执行              │ ← 未选中态
│       列出详细步骤，逐步执行              │    圆圈: 2px solid #D1D5DB
├─────────────────────────────────────────┤
│ ○ C  只改 UserService.kt                │
│       其他调用处不动，快速验证方案        │
├─────────────────────────────────────────┤
│ [确认选择]          已选: B             │ ← 操作栏
└─────────────────────────────────────────┘

选项状态:
Default:    fg=#374151, bg=transparent, 左边框透明
Hover:      bg=#F3F4F6 (亮) / #1F2937 (暗)
Selected:   bg=#EFF6FF (亮) / #1E3A5F (暗)
            border-left: 3px solid #3B82F6
            圆圈: bg=#3B82F6, border=#3B82F6, fg=#FFFFFF (实心蓝)
Disabled:   fg=#9CA3AF, cursor=default

操作栏:     bg=#F9FAFB, border-top=#E5E7EB
            已选文字: Caption 11px, #6B7280
```

### 2.11 搜索/过滤

```
Sessions 搜索框:
┌──────────────────────────────────────────┐
│ 🔍 搜索会话...                            │ ← border=#D1D5DB, 圆角 6px
│                                          │    placeholder 灰色
│ Focus: border=#3B82F6                    │    debounce 300ms 触发过滤
│ Empty: "没有匹配的会话"                   │    (不是 ERROR 状态)
└──────────────────────────────────────────┘

@file 搜索 Popup:
┌──────────────────────┐
│ UserService.kt       │ ← border=#E5E7EB, 圆角 4px
│ UserController.kt    │    每行 32px 高
│ UserRepository.kt    │    匹配部分加粗 bg=#EFF6FF
│ ...                  │    ↑↓ 键选择, Enter 确认, Esc 关闭
└──────────────────────┘
   最大 8 行可见 + 滚动条
```

### 2.12 空状态

每个页面在无数据时的空状态设计：

```
Sessions 空:
┌──────────────────────────────────────────┐
│                                          │
│           📁                             │
│        还没有会话记录                      │
│    开始一段对话，会话将自动保存             │
│                                          │
└──────────────────────────────────────────┘

Token Usage 空:
┌──────────────────────────────────────────┐
│                                          │
│           📊                             │
│        还没有 token 消耗                   │
│    使用 Agent 后这里会显示消耗统计          │
│                                          │
└──────────────────────────────────────────┘

MCP 空:
┌──────────────────────────────────────────┐
│                                          │
│           🔌                             │
│        还没有 MCP Server                   │
│    [➕ 添加 Server] 连接外部工具服务        │
│                                          │
└──────────────────────────────────────────┘

Skills 空:
┌──────────────────────────────────────────┐
│                                          │
│           🎯                             │
│        还没有安装 Skill                    │
│    在 .code-assistant/skills/ 下创建       │
│    SKILL.md 来自定义 Agent 能力            │
│                                          │
└──────────────────────────────────────────┘
```

---

## 三、页面流转图

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
  │     │               ├─ LLM 通过 listTasks 查看进度      │
  │     │               └─ LLM 通过 deleteTask/reorderTasks 调整 │
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

PlanCard 交互规则:
  
  所有步骤自动连续执行:
    └─ 创建后自动开始 → 逐步执行 → 全部完成后卡片消失
  
  用户干预:
    └─ 仅可删除 PENDING/ERROR 状态的单步（行末 [✕]），LLM 收到通知后跳过该步
  
  LLM 自主管理:
    ├─ listTasks — 查看所有步骤状态
    ├─ deleteTask — 删除不需要的步骤
    └─ reorderTasks — 调整执行顺序
```

---

## 四、动效规范

| 动效        | 时长    | 缓动              | 触发条件                                |
|-----------|-------|-----------------|-------------------------------------|
| Toast 滑入  | 300ms | ease-out        | Toast 触发                            |
| Toast 滑出  | 200ms | ease-in         | 3s 后自动                              |
| 审批弹窗出现    | 200ms | ease-out        | tool_use 到达 + requiresApproval=true |
| 审批弹窗消失    | 150ms | ease-in         | 用户操作（批准/拒绝）                         |
| 消息气泡出现    | 150ms | ease-out        | 消息添加到列表                             |
| 流式闪烁光标    | 500ms | blink           | 流式生成中                               |
| 工具卡片展开/折叠 | 200ms | ease-out        | 点击头部                                |
| 执行中旋转     | 1s/圈  | linear infinite | EXECUTING 状态                        |
| 页面切换      | 0ms   | 无               | CardLayout.show()（即时）               |
| hover 高亮  | 100ms | ease-out        | 鼠标进入按钮/卡片                           |

**prefers-reduced-motion 适配：** 如果系统设置了"减少动效"，所有动画时长降为 0ms（即时切换），spinner
动画除外。

---

## 五、响应式行为

聊天面板支持两种停靠位置，需适配不同宽度（与 [`DESIGN.md`](../DESIGN.md) §四对齐）：

| 面板宽度            | 布局调整                                               |
|-----------------|----------------------------------------------------|
| < 250px         | 气泡 maxWidth=95%。ToolCallCard 参数行换行                 |
| 250-350px       | 气泡 maxWidth=90%（用户）/95%（Agent）                     |
| 350-500px       | 默认行为。气泡 maxWidth=70%(用户)/85%(Agent)                |
| > 500px         | 气泡 maxWidth=60%(用户)/75%(Agent)。代码块可并排显示（如果有多个短代码块） |
| ToolWindow 浮动模式 | 同 < 250px 行为                                       |

> TabBar 全宽度下均为纯图标模式，无文字标签。

**面板宽度变化监听：** `ChatToolWindow.addComponentListener` → `componentResized()` → 更新所有气泡的
`setMaximumSize()`。

---

## 六、无障碍

| 需求    | 实现                                                                                                              |
|-------|-----------------------------------------------------------------------------------------------------------------|
| 键盘导航  | Tab 在输入框和发送按钮间切换。↑↓ 在 @file popup 中选择。Escape 关闭任何弹窗/popup                                                       |
| 焦点指示器 | Focus 时输入框边框加粗到 2px+变色。按钮 focus 时使用 `JButton.setFocusPainted(true)` + `UIManager.getIcon("Button.focus")` 绘制聚焦环 |
| 屏幕阅读器 | 所有按钮 `setAccessibleDescription()`。气泡 `setAccessibleDescription()` 包含消息摘要                                        |
| 色盲友好  | 状态不仅依赖颜色——每个状态有独立图标（✅/❌/⏳/⛔）+ 文字标签                                                                              |
| 对比度   | 正文 `#111827` 对 `#FFFFFF` → 对比度 16.9:1 ✅。辅助文字 `#6B7280` 对 `#F9FAFB` → 5.2:1 ✅                                    |

---

## 七、会话列表虚拟化

当 ChatPage 中消息超过 100 条时，使用虚拟化渲染避免 OOM：

```
实现策略:
┌─────────────────────────────────────────┐
│ JScrollPane viewport                     │
│   ├─ 可见区域: 渲染完整 Bubble 组件       │
│   ├─ 上方不可见: 占位 JPanel(height=N)    │
│   └─ 下方不可见: 占位 JPanel(height=M)    │
│                                          │
│ 不引入第三方虚拟列表库                      │
│ 自定义实现: JScrollPane +                  │
│   Adjustable + componentListener          │
│   → 监听 viewport 变化 → 计算可见行        │
│   → 只渲染可见 Bubble + 上下各 3 个 buffer  │
│                                          │
│ 触发条件: messages.size > 100            │
│ 100 条以内: 全量渲染（无性能问题）          │
└─────────────────────────────────────────┘
```