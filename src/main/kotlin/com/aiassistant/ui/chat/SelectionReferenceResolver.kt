package com.aiassistant.ui.chat

object SelectionReferenceResolver {

    fun expand(text: String, displayName: String?, content: String?): String {
        val selected = content?.takeIf { it.isNotBlank() } ?: return text
        val name = displayName?.takeIf { it.isNotBlank() } ?: "selection"
        val fileName = name.substringBefore(":").substringAfterLast("/")
        if (fileName.isNotBlank() && Regex("""\[File: [^\]]*\Q$fileName\E\b""").containsMatchIn(text)) {
            return text
        }
        return buildString {
            append(text)
            append("\n\n[Selection from ")
            append(name)
            appendLine("]")
            append(selected)
            if (!selected.endsWith("\n")) appendLine()
            append("[/Selection]")
        }
    }
}
