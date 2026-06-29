# 设计系统

> 关联文档：[[pages]], [[chat]], [[components]]

## 一、色板

### 亮色主题

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
│ Code BG:       #F6F8FA  ████████  JTextArea 背景       │
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

### 暗色主题

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
│ Code BG:       #1E1E2E  ████████  JTextArea 背景       │
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
  `#111827`），与亮色主题相反
- 代码块背景使用 `#1E1E2E`，与 IntelliJ Darcula 编辑器背景一致
- 文字对比度在暗色主题下仍需满足 WCAG AA（≥4.5:1）

## 二、字体层级

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

## 三、间距体系

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

## 四、圆角

| 元素        | 圆角   | 说明           |
|-----------|------|--------------|
| 气泡        | 12px | 用户+Agent 气泡  |
| 代码块       | 8px  | JTextArea 外框 |
| 工具卡片      | 8px  | ToolCallCard |
| 按钮        | 6px  | 操作按钮         |
| Tag       | 4px  | 文件引用/图片 tag  |
| 输入框       | 8px  | JTextArea 外框 |
| TabBar 按钮 | 0    | 直角，底边框高亮     |

## 五、阴影

```
阴影仅用于悬浮元素，不用于静态卡片:

tooltip:    0px 4px 12px rgba(0,0,0,0.10)    — 悬浮提示、Popup
dialog:     0px 8px 24px rgba(0,0,0,0.15)    — 弹窗（审批、确认）
无阴影:     气泡、卡片、输入框                  — 扁平设计
```

## 六、图标

使用 IntelliJ Platform 内置图标集（`com.intellij.icons.AllIcons`），不引入外部图标库。

| 用途                       | 图标引用                                      |
|--------------------------|-------------------------------------------|
| TabBar - Chat            | `AllIcons.Toolwindows.ToolWindowMessages` |
| TabBar - Sessions        | `AllIcons.Actions.ListFiles`              |
| TabBar - Token Usage     | `AllIcons.Actions.Show`                   |
| TabBar - MCP             | `AllIcons.Nodes.Plugin`                   |
| TabBar - Skills          | `AllIcons.Nodes.Favorite`                 |
| TabBar - Settings        | `AllIcons.General.Gear`                   |
| TabBar - Welcome         | `AllIcons.Nodes.HomeFolder`               |
| ToolCallCard - DONE      | `AllIcons.RunConfigurations.TestPassed`   |
| ToolCallCard - ERROR     | `AllIcons.RunConfigurations.TestFailed`   |
| ToolCallCard - EXECUTING | `AllIcons.Process.Plan_4`（旋转动画）           |
| ToolCallCard - PENDING   | `AllIcons.Process.Plan_0`                 |
| 发送按钮                     | 文字 "→" / "发送"（中文环境）                       |
| 停止按钮                     | 输入框右侧 spinner 动画（无独立按钮）                   |
| 文件引用 tag                 | `AllIcons.FileTypes.Text`                 |
| 图片 tag                   | `AllIcons.FileTypes.Image`                |

## 七、动效规范

| 动效        | 时长                                 | 缓动              | 触发条件                                |
|-----------|------------------------------------|-----------------|-------------------------------------|
| Toast 滑入  | 300ms                              | ease-out        | Toast 触发                            |
| Toast 滑出  | 200ms                              | ease-in         | 3s 后自动                              |
| 审批弹窗出现    | 200ms                              | ease-out        | tool_use 到达 + requiresApproval=true |
| 审批弹窗消失    | 150ms                              | ease-in         | 用户操作（批准/拒绝）                         |
| 消息气泡出现    | 150ms                              | ease-out        | 消息添加到列表                             |
| 流式闪烁光标    | 500ms 可见 / 500ms 隐藏交替（完整周期 1000ms） | blink           | 流式生成中                               |
| 工具卡片展开/折叠 | 200ms                              | ease-out        | 点击头部                                |
| 执行中旋转     | 1s/圈                               | linear infinite | EXECUTING 状态                        |
| 页面切换      | 0ms                                | 无               | CardLayout.show()（即时）               |
| hover 高亮  | 100ms                              | ease-out        | 鼠标进入按钮/卡片                           |

### prefers-reduced-motion 适配

如果系统设置了"减少动效"，所有动画时长降为 0ms（即时切换），spinner 动画除外。

## 八、响应式行为

聊天面板支持两种停靠位置，需适配不同宽度：

| 面板宽度            | 用户气泡 maxWidth | Agent 气泡 maxWidth | 其他调整                |
|-----------------|---------------|-------------------|---------------------|
| > 500px         | 60%           | 75%               | 代码块可并排显示（如果有多个短代码块） |
| 350-500px       | 70%           | 85%               | 默认行为                |
| 250-350px       | 90%           | 95%               |                     |
| < 250px         | 95%           | 95%               | ToolCallCard 参数行换行  |
| ToolWindow 浮动模式 | 同 < 250px     | 同 < 250px         |                     |

> TabBar 全宽度下均为纯图标模式，无文字标签。

**面板宽度变化监听：** `ChatToolWindow.addComponentListener` → `componentResized()` → 更新所有气泡的
`setMaximumSize()`。

## 九、无障碍

| 需求    | 实现                                                                                                              |
|-------|-----------------------------------------------------------------------------------------------------------------|
| 键盘导航  | Tab 在输入框和发送按钮间切换。↑↓ 在 @file popup 中选择。Escape 关闭任何弹窗/popup                                                       |
| 焦点指示器 | Focus 时输入框边框加粗到 2px+变色。按钮 focus 时使用 `JButton.setFocusPainted(true)` + `UIManager.getIcon("Button.focus")` 绘制聚焦环 |
| 屏幕阅读器 | 所有按钮 `setAccessibleDescription()`。气泡 `setAccessibleDescription()` 包含消息摘要                                        |
| 色盲友好  | 状态不仅依赖颜色——每个状态有独立图标（✅/❌/⏳/⛔）+ 文字标签                                                                              |
| 对比度   | 正文 `#111827` 对 `#FFFFFF` → 对比度 16.9:1 ✅。辅助文字 `#6B7280` 对 `#F9FAFB` → 5.2:1 ✅                                    |

