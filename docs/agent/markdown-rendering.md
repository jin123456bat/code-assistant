# Markdown 渲染实现

> **重要更正：** 文档曾声称使用 `org.intellij.plugins.markdown` bundled plugin 的 13 个 PSI 类型。*
*实际实现使用纯手写字符串解析器**，不依赖任何 IntelliJ Markdown PSI API。本文档描述实际实现。

## 一、解析器架构

```
LLM 返回 Markdown 文本
  │
  ▼
ChatBubbleRenderer.parseMarkdown(text)
  │
  ├── 按行遍历
  ├── 识别块级元素起始标记（```、#、>、-、数字列表）
  ├── 收集连续行为同一块
  └── 返回 List<MarkdownBlock>
  │
  ▼
ChatBubbleRenderer.renderAgentBubble(msg)
  │
  ├── 遍历 MarkdownBlock 列表
  ├── 每个 block 渲染为对应 Swing 组件
  └── 组装到 BoxLayout(Y_AXIS) 垂直面板
```

## 二、支持的 MarkdownBlock 类型（5 种）

| 类型           | 识别规则                          | 渲染方式                                                |
|--------------|-------------------------------|-----------------------------------------------------|
| `Paragraph`  | 非特殊起始的连续非空行                   | `JLabel` HTML 渲染，`<code>` 内联代码高亮                    |
| `CodeBlock`  | `` ``` `` 起始/结束               | `JTextArea`（只读，JetBrains Mono 等宽字体） + `JScrollPane` |
| `Header`     | `#` 起始                        | `JLabel` `<b>` 粗体                                   |
| `ListItem`   | `- ` / `* ` / 数字列表 `1. ` `2)` | `JLabel` 缩进 + `•` 前缀                                |
| `QuoteBlock` | `> ` 起始，连续行合并                 | `JTextArea` 斜体 + 左边框线                               |

## 三、逐行解析规则

```kotlin
fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        when {
            line.startsWith("```")      → CodeBlock（收集直到闭合```）
            line.startsWith("#")
                → Header
            line.startsWith("> ")       → QuoteBlock（合并连续 > 行）
            line.matches(数字列表正则)
                → ListItem
            line.startsWith("- ") / "* "→ ListItem
            line.startsWith("---")      → 跳过（水平线，不渲染）
            line.isBlank()
                → 跳过
            else                        → Paragraph（合并连续普通行直到遇到特殊标记）
        }
    }
}
```

### 数字列表正则

```
^\d+[.)]\s.*
```

匹配格式：

- `1. xxx`
- `2) xxx`
- `步骤 1: xxx`（不匹配——仅匹配 `数字.` 或 `数字)` 起始的行）

### 内联代码（Paragraph 内）

在 Paragraph 渲染时，正则替换 `` `code` `` 为带背景色的 `<code>` 标签：

```kotlin
text.replace(Regex("`([^`]+)`")) {
    "<code style='font-family:monospace;font-size:13px;background:${codeBgHex};padding:1px 6px;border-radius:3px;border:1px solid ${codeBorderHex}'>${it.groupValues[1]}</code>"
}
```

## 四、代码块渲染

使用 `JTextArea`（只读模式）而非 `EditorTextField`：

| 属性  | 值                                         |
|-----|-------------------------------------------|
| 字体  | JetBrains Mono 13pt → Monospaced fallback |
| 背景色 | `AppColors.codeBg`                        |
| 前景色 | `AppColors.textSecondary`                 |
| 边框  | 8px 圆角 + 8px 内边距                          |
| 滚动  | 水平滚动条随需显示，垂直永不过卷                          |

### 等宽字体回退策略

```kotlin
private val monoFont = run {
    val jetbrains = Font("JetBrains Mono", Font.PLAIN, 13)
    if ("JetBrains Mono" in availableFontFamilyNames) jetbrains
    else Font(Font.MONOSPACED, Font.PLAIN, 13)
}
```

## 五、不支持的元素

以下 Markdown 元素当前不解析，会被识别为 Paragraph：

| 元素          | 说明                                       |
|-------------|------------------------------------------|
| **表格**      | `                                        | col | col |` 格式不识别，渲染为普通段落 |
| **嵌套列表**    | 仅支持一级列表项                                 |
| **图片**      | `![alt](url)` 不识别                        |
| **链接**      | `[text](url)` 不渲染为可点击链接                  |
| **粗体/斜体**   | `**bold**` / `*italic*` 在 Paragraph 内不转换 |
| **任务列表**    | `- [ ]` / `- [x]` 不识别                    |
| **脚注**      | 不支持                                      |
| **HTML 标签** | 原始 HTML 会被 `escapeHtml()` 转义             |

## 六、HTML 转义

所有用户/LLM 文本渲染前经过 `escapeHtml()`：

```kotlin
private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
```

> **安全说明：** 这防止了 LLM 输出中的 HTML/JS 注入。所有内容都先转义再嵌入 `<html>` 标签。

## 七、设计决策

| 决策                                 | 理由                                                         |
|------------------------------------|------------------------------------------------------------|
| 手写解析器而非 PSI                        | PSI 需要 write action + 虚拟文件，EDT 上耗时不可控；手写解析器纯字符串操作，无 EDT 约束 |
| JTextArea 而非 EditorTextField 渲染代码块 | 避免依赖编辑器基础设施，更轻量                                            |
| JLabel HTML 渲染普通文本                 | Swing 内置 HTML 渲染足够用于段落/标题/列表，无需额外依赖                        |
| 不支持表格                              | 表格需要复杂的 HTML `<table>` 渲染，JLabel 的 HTML 3.2 子集对复杂表格支持差     |
