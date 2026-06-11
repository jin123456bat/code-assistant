# Code Assistant Design System

PhpStorm IDE 插件 — Swing UI 设计规范。

## Product Context

- **What this is**: PhpStorm 开源 AI Agent 插件，可自主搜索代码、读写文件、执行命令、Git 操作
- **Who it's for**: PHP 开发者（学生至企业级），需要免费开源的 AI 编程助手
- **Space/industry**: JetBrains IDE 插件生态
- **Reference**: GitHub Copilot、JetBrains AI Assistant、Cursor

## Aesthetic Direction

- **Direction**: Utilitarian — IDE 原生集成感，功能优先于装饰
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

## Color Tokens

### 气泡颜色（语义色）

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `--bubble-user-bg` | `#E8F5FF` | `#28323C` | 用户消息背景 |
| `--bubble-user-border` | `#B4D7FF` | `#3C4664` | 用户消息边框 |
| `--bubble-ai-bg` | `#F5F5F5` | `#37373C` | AI 回复背景 |
| `--bubble-ai-border` | `#DCDCDC` | `#41414B` | AI 回复边框 |
| `--bubble-toolcall-bg` | `#F3E5F5` | `#2D2435` | 工具调用背景 |
| `--bubble-toolcall-border` | `#CE93D8` | `#563D5C` | 工具调用边框 |
| `--bubble-toolresult-bg` | `#E8F5E9` | `#1B3A1C` | 工具结果背景 |
| `--bubble-toolresult-border` | `#A5D6A7` | `#3C5A3C` | 工具结果边框 |
| `--bubble-running-bg` | `#FFF8E1` | `#3C3214` | 执行中背景 |
| `--bubble-running-border` | `#FFE082` | `#5C4A1C` | 执行中边框 |
| `--chip-bg` | `#E3E8EE` | `#3A3E48` | 引用芯片背景 |
| `--chip-border` | `#C0C8D0` | `#505560` | 引用芯片边框 |
| `--chip-close` | `#888888` | `#AAAAAA` | 芯片关闭按钮 |
| `--input-bg` | `#FAFBFC` | `#2B2D30` | 输入面板背景 |
| `--input-border` | `#D0D0D0` | `#505050` | 输入面板边框（默认） |
| `--input-border-focus` | `#4A90D9` | `#5A9FD4` | 输入面板边框（聚焦） |

### 文字颜色

| Token | Light | Dark | 用途 | 对比度 |
|-------|-------|------|------|--------|
| `--text-primary` | `#333333` | `#CCCCCC` | 正文 | 12.6:1 / 10.5:1 |
| `--text-secondary` | `#666666` | `#AAAAAA` | 标签/角色 | 5.9:1 / 4.6:1 |
| `--text-muted` | `#767676` | `#AAAAAA` | 辅助文字 | 4.5:1 / 4.6:1 |
| `--text-toolcall` | `#7B1FA2` | `#CE93D8` | 工具调用标题 | — |
| `--text-toolresult` | `#2E7D32` | `#81C784` | 工具结果标题 | — |
| `--text-running` | `#E65100` | `#FFB74D` | 执行中标题 | — |

### 错误/警告

| Token | Light | Dark | 用途 |
|-------|-------|------|------|
| `--error-bg` | `#FFEBEE` | `#462828` | 错误横幅背景 |
| `--error-fg` | `#B00020` | `#FFB4B4` | 错误横幅文字 |
| `--warning-bg` | `#FFF3CD` | `#3C3214` | 警告横幅背景 |
| `--warning-fg` | `#856404` | `#FFE696` | 警告横幅文字 |

## Typography

- 系统默认字体：`Font.SANS_SERIF`（Swing 跨平台兼容）
- 代码字体：`Font.MONOSPACED`（工具结果输出）
- 字号层级：
  - `body`: 13px-14px（对话内容）
  - `small`: 11px-12px（标签、角色名）
  - `caption`: 10px（代码块语言标识）
- 行高：Swing 默认（~1.2-1.4x），对话区自然换行

## Spacing

- 基础单位：4px（IntelliJ `JBUI` 工具类）
- 气泡内边距：8px 12px（上下 8px，左右 12px）
- 输入区内边距：8px 10px
- 按钮间距：4px
- 对话区最小宽度：100px
- 输入区最小高度：80px

## Border Radius

- 气泡：12px 圆角（roundedBorder + Graphics2D 抗锯齿渲染）
- 代码块：4px（轻微圆角区分内容）
- 输入框：12px 圆角，聚焦态 2px 蓝色描边
- 引用芯片：12px 圆角

## Interaction States

| 状态 | 视觉表现 |
|------|---------|
| 默认 | 正常颜色 + 实线边框 |
| 错误 | 红色横幅 + 具体错误消息 + 修复建议 |
| 警告 | 黄色横幅 + 倒计时 |
| 流式生成中 | 发送按钮禁用 + 停止按钮可见 |
| 工具执行中 | 黄色气泡 + 工具名称 |
| 确认弹窗 | 系统原生对话框 + 操作预览 |

## Accessibility

- 所有文字对比度 ≥ 4.5:1（WCAG AA）
- 交互元素通过 IntelliJ 主题自动适配暗色模式
- 键盘导航：Tab 序（输入框 → 停止 → 文件 → 引用 → 发送）
- 错误消息包含具体修复步骤（不是仅"失败了"）

## Icons

- 使用 IntelliJ 内置图标（`AllIcons.Actions.Annotate`）
- 工具卡片使用 Unicode 符号：🔧（工具调用）、📋（工具结果）、⏳（执行中）
