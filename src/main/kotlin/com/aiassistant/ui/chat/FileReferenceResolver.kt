package com.aiassistant.ui.chat

import java.io.File

object FileReferenceResolver {

    private val referenceRegex = Regex("""(?:^|\s)@([^\s]+)""")
    private val ignoredDirs = setOf(".git", ".idea", "build", ".gradle", ".code-assistant")
    private val sourceExtensions = setOf(
        "kt", "java", "kts", "xml", "json", "md", "gradle",
        "properties", "yml", "yaml", "txt"
    )

    fun expand(text: String, basePath: String?): String {
        val root = basePath?.let { File(it).canonicalFile } ?: return text
        if (!root.isDirectory) return text

        val refs = referenceRegex.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
            .mapNotNull { resolve(root, it) }
            .distinctBy { it.canonicalPath }
            .toList()

        if (refs.isEmpty()) return text

        val context = refs.joinToString("\n\n") { file ->
            val relativePath = file.relativeTo(root).path
            val lines = file.readLines(Charsets.UTF_8)
            val returned = lines.take(500)
            buildString {
                appendLine("[File: $relativePath (${lines.size} lines)]")
                append(returned.joinToString("\n"))
                if (lines.size > returned.size) {
                    appendLine()
                    append("... (${lines.size - returned.size} more lines omitted)")
                }
                appendLine()
                append("[/File]")
            }
        }

        return "$text\n\n$context"
    }

    private fun resolve(root: File, reference: String): File? {
        val exact = File(root, reference).canonicalFile
        if (isAllowedFile(root, exact)) return exact

        val matches = root.walkTopDown()
            .onEnter { it.name !in ignoredDirs }
            .filter { file -> isAllowedFile(root, file) && file.name == reference }
            .take(2)
            .toList()

        return matches.singleOrNull()
    }

    private fun isAllowedFile(root: File, file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical.isFile &&
                canonical.path.startsWith(root.path + File.separator) &&
                canonical.extension in sourceExtensions &&
                canonical.length() <= 512_000
    }
}
