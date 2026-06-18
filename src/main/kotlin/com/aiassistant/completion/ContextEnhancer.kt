package com.aiassistant.completion

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * 增强上下文收集：邻近文件 Jaccard 相似度排序 + 打开标签页优先。
 */
object ContextEnhancer {

    private const val MAX_SIBLING_FILES = 5

    fun findBestSiblingFiles(
        currentFile: VirtualFile,
        extension: String,
        currentImportLines: Set<String>,
        project: com.intellij.openapi.project.Project
    ): List<String> {
        val parent = currentFile.parent ?: return emptyList()
        val currentName = currentFile.name

        val openFiles = FileEditorManager.getInstance(project).openFiles
            .map { it.path }
            .toSet()

        val siblings = parent.children.filter {
            it.extension == extension && it.name != currentName && !it.isDirectory
        }
        if (siblings.isEmpty()) return emptyList()

        val (openSiblings, closedSiblings) = siblings.partition { it.path in openFiles }

        fun scoreAndSort(files: List<VirtualFile>): List<VirtualFile> {
            return files.map { file ->
                val importLines = extractImportLines(file, extension)
                val score = jaccardSimilarity(currentImportLines, importLines)
                file to score
            }.sortedByDescending { it.second }.map { it.first }
        }

        val sorted = scoreAndSort(openSiblings) + scoreAndSort(closedSiblings)
        return sorted.take(MAX_SIBLING_FILES).map { it.path }
    }

    fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union
    }

    fun extractImportLinesFromText(text: String, language: String): Set<String> {
        return text.lines()
            .map { it.trim() }
            .filter { line ->
                when (language.lowercase()) {
                    "php" -> line.startsWith("use ") || line.startsWith("namespace ")
                    "javascript", "typescript" -> line.startsWith("import ")
                    "python" -> line.startsWith("import ") || line.startsWith("from ")
                    "java", "kotlin" -> line.startsWith("import ")
                    else -> false
                }
            }
            .toSet()
    }

    private fun extractImportLines(file: VirtualFile, extension: String): Set<String> {
        return try {
            val text = String(file.contentsToByteArray())
            text.lines()
                .map { it.trim() }
                .filter { line ->
                    when (extension.lowercase()) {
                        "php" -> line.startsWith("use ") || line.startsWith("require")
                        "js", "ts", "jsx", "tsx" -> line.startsWith("import ") || (line.startsWith("const ") && line.contains("require("))
                        "py" -> line.startsWith("import ") || line.startsWith("from ")
                        "java", "kt" -> line.startsWith("import ")
                        else -> line.contains("import") || line.contains("include")
                    }
                }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
