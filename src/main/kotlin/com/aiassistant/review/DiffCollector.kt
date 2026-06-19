package com.aiassistant.review

import java.io.File

enum class Severity { CRITICAL, WARNING, INFO }
enum class Category { BUG, SIMPLIFY, PERF, SECURITY }

data class Finding(
    val severity: Severity,
    val category: Category,
    val file: String,
    val line: Int,
    val title: String,
    val description: String,
    val suggestion: String = "",
    val confidence: Int = 5
)

data class FileChange(
    val path: String,
    val status: String,
    val hunks: List<Hunk>,
    val isBinary: Boolean = false
)

data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<String>
)

class DiffCollector(private val projectBasePath: String?) {

    fun collectBranchDiff(): String? {
        val base = projectBasePath ?: return null
        return runCatching {
            val baseBranch = detectBaseBranch()
            val process = ProcessBuilder("git", "-C", base, "diff", "$baseBranch...HEAD")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    fun collectFileDiff(filePath: String): String? {
        val base = projectBasePath ?: return null
        return runCatching {
            val process = ProcessBuilder("git", "-C", base, "diff", "HEAD", "--", filePath)
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    fun collectDiffStat(): String? {
        val base = projectBasePath ?: return null
        return runCatching {
            val process = ProcessBuilder("git", "-C", base, "diff", "--stat", "main...HEAD")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun detectBaseBranch(): String {
        val base = projectBasePath!!
        val hasMain = runCatching {
            ProcessBuilder("git", "-C", base, "rev-parse", "--verify", "origin/main")
                .start().waitFor() == 0
        }.getOrDefault(false)
        return if (hasMain) "origin/main" else "origin/master"
    }

    fun parse(diff: String): List<FileChange> {
        if (diff.isBlank()) return emptyList()
        val changes = mutableListOf<FileChange>()
        val lines = diff.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git ")) {
                val pathMatch = Regex("""b/(.+)""").find(line)
                val path = pathMatch?.groupValues?.get(1) ?: "unknown"
                i++
                var status = "modified"
                var isBinary = false
                while (i < lines.size && !lines[i].startsWith("@@")) {
                    if (lines[i].startsWith("new file")) status = "added"
                    else if (lines[i].startsWith("deleted file")) status = "deleted"
                    else if (lines[i].contains("Binary files")) isBinary = true
                    i++
                }
                val hunks = mutableListOf<Hunk>()
                while (i < lines.size && lines[i].startsWith("@@")) {
                    val hunkHeader = Regex("""@@ -(\d+),?(\d*) \+(\d+),?(\d*) @@""").find(lines[i])
                    if (hunkHeader != null) {
                        val oldStart = hunkHeader.groupValues[1].toInt()
                        val oldCount = hunkHeader.groupValues[2].ifEmpty { "1" }.toInt()
                        val newStart = hunkHeader.groupValues[3].toInt()
                        val newCount = hunkHeader.groupValues[4].ifEmpty { "1" }.toInt()
                        i++
                        val hunkLines = mutableListOf<String>()
                        while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git")) {
                            hunkLines.add(lines[i]); i++
                        }
                        hunks.add(Hunk(oldStart, oldCount, newStart, newCount, hunkLines.toList()))
                    } else { i++ }
                }
                changes.add(FileChange(path, status, hunks.toList(), isBinary))
            } else { i++ }
        }
        return changes.toList()
    }
}
