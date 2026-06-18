package com.aiassistant.completion

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 增强上下文收集：从已打开的编辑器标签页中选兄弟文件，用 Jaccard 相似度排序。
 * 只遍历已打开的文件（VFS 内存缓存命中），不读磁盘。
 */
object ContextEnhancer {

    /** 最多选取的兄弟文件数 */
    private const val MAX_SIBLING_FILES = 5
    /** Jaccard 计算的候选上限，避免打开太多标签页时计算量过大 */
    private const val MAX_CANDIDATES_FOR_SCORING = 10

    /**
     * 从已打开的编辑器标签页中查找同目录同扩展名的兄弟文件，
     * 按 Jaccard 相似度排序，返回文件路径列表。
     *
     * 所有文件内容通过 IntelliJ VFS 读取——已打开文件在 VFS 内存缓存中，不触发磁盘 IO。
     */
    fun findBestSiblingFiles(
        currentFile: VirtualFile,
        extension: String,
        currentImportLines: Set<String>,
        project: Project
    ): List<String> {
        val currentPath = currentFile.path
        val parentDir = currentFile.parent?.path ?: return emptyList()

        // 只从已打开的编辑器文件中选候选（VFS 缓存命中，无磁盘 IO）
        val openFiles = FileEditorManager.getInstance(project).openFiles
        val candidates = openFiles.filter {
            it.extension == extension
                && it.path != currentPath
                && it.parent?.path == parentDir
                && !it.isDirectory
        }

        if (candidates.isEmpty()) return emptyList()
        if (candidates.size == 1) return listOf(candidates.first().path)

        // Jaccard 相似度排序（候选上限 MAX_CANDIDATES_FOR_SCORING）
        val scored = candidates
            .take(MAX_CANDIDATES_FOR_SCORING)
            .map { file ->
                val importLines = extractImportLinesFromVfs(file, extension)
                file.path to jaccardSimilarity(currentImportLines, importLines)
            }
            .sortedByDescending { it.second }

        return scored.take(MAX_SIBLING_FILES).map { it.first }
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

    /**
     * 从 VFS 读取文件的 import 行。
     * 已打开的文件在 VFS 内存缓存中，不会触发磁盘 IO。
     */
    private fun extractImportLinesFromVfs(file: VirtualFile, extension: String): Set<String> {
        return try {
            // contentsToByteArray 对已打开文件走 VFS 内存缓存
            val text = String(file.contentsToByteArray())
            text.lines()
                .map { it.trim() }
                .filter { line ->
                    when (extension.lowercase()) {
                        "php" -> line.startsWith("use ") || line.startsWith("namespace ")
                        "js", "ts", "jsx", "tsx" -> line.startsWith("import ")
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
