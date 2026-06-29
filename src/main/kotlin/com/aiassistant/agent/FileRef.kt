package com.aiassistant.agent

/**
 * 文件引用，对齐文档 docs/ui/chat.md §十二 InputState 中的 FileRef 定义。
 * 分为两类：
 * - 手动 @file 引用（manualRefs，可多个）
 * - 编辑器选中代码引用（selectionRef，仅一个，带行号范围）
 */
data class FileRef(
    /** 文件相对路径（相对于项目根目录），如 "src/main/kotlin/UserService.kt" */
    val path: String,
    /** 选中行范围，仅在 selectionRef 时有值，如 "40-60" */
    val lines: String? = null,
    /** 选中内容，仅在 selectionRef 时有值 */
    val content: String? = null
) {
    /** TagsRow 展示标签，如 "📄 UserService.kt:40-60" 或 "📄 UserService.kt" */
    val displayName: String
        get() = if (lines != null) "📎 $path:$lines" else "📎 $path"
}
