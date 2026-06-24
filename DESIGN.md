# Code Assistant Design System

IntelliJ IDEA 插件 — UI 设计规范。仅覆盖 master 分支实际存在的 UI 组件。

## Product Context

- **What this is**: IntelliJ IDEA 及所有 JetBrains IDE 的免费 AI 编程助手插件
- **Who it's for**: PHP/Laravel 开发者，对价格敏感
- **Core features**: 代码补全 + Git Commit Message 生成
- **Reference**: GitHub Copilot、JetBrains AI Assistant

## Aesthetic Direction

- **Direction**: Utilitarian — IDE 原生集成感，功能优先于装饰
- **Decoration level**: minimal — 无自定义气泡 UI（补全用 IntelliJ 原生灰显渲染）

## Color Tokens

所有 UI 使用 IntelliJ 原生主题色（`JBColor`/`UIUtil`），不自定义颜色系统。补全文本通过 IntelliJ `InlineCompletionGrayTextElement` 渲染，颜色由 IDE 主题自动管理。

## Typography

- 系统默认字体：跟随 IDE 编辑器设置
- Settings 面板：IntelliJ 标准 Form 布局，无需自定义字号

## Spacing

- Settings 面板：IntelliJ 标准 Form 间距，无需自定义 token

## Components（master 实际组件）

### SettingsConfigurable

IntelliJ Settings 面板，包含：
- API Key 输入框（PasswordSafe 存储）
- 模型选择下拉（deepseek-v4-pro / deepseek-v4-flash）
- 补全开关 + 最大 tokens 配置

### InlineCompletion（灰显补全）

通过 IntelliJ `InlineCompletionProvider` API 渲染，使用原生 `InlineCompletionGrayTextElement`。颜色/字体由 IDE 主题控制，插件不自定义。

### GenerateCommitAction

标准 IntelliJ `AnAction`，注册在 VCS Commit 对话框。点击触发后台 LLM 请求，结果填充到 commit message 文本框。
