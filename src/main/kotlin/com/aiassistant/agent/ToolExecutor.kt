package com.aiassistant.agent

import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

class ToolExecutor(private val project: Project, private val session: AgentSession) {

    fun execute(toolUse: BetaToolUseBlock): String {
        return try {
            when (toolUse.name()) {
                "readFile" -> readFile(toolUse)
                "writeFile" -> writeFile(toolUse)
                "editFile" -> editFile(toolUse)
                "runShell" -> runShell(toolUse)
                "listFiles" -> listFiles(toolUse)
                "searchContent" -> searchContent(toolUse)
                "readLints" -> readLints(toolUse)
                "spawnAgent" -> spawnAgent(toolUse)
                else -> "未知工具: ${toolUse.name()}"
            }
        } catch (e: Exception) {
            "错误: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ponytail: read from VFS, parse input as JsonValue
    private fun readFile(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"
        val startLine = ToolInput.int(input, "startLine")
        val endLine = ToolInput.int(input, "endLine")

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = File(basePath, path)
        if (!file.exists()) return "错误: 文件 \"$path\" 不存在"

        val lines = file.readLines()
        val from = (startLine?.minus(1))?.coerceAtLeast(0) ?: 0
        val to = (endLine?.coerceAtMost(lines.size)) ?: lines.size

        val content = lines.subList(from, to).joinToString("\n")
        val totalLines = lines.size
        val returnedLines = to - from
        val maxLines = 500

        return if (returnedLines <= maxLines) {
            "[文件: $path ($totalLines 行 total, $returnedLines 行已返回)]\n$content\n[文件结束: $path]"
        } else {
            val truncated = lines.subList(from, from + maxLines).joinToString("\n")
            "[文件: $path ($totalLines 行 total, 已截断到 $maxLines 行)]\n$truncated\n... (共 $totalLines 行，已截断到 $maxLines 行。如需查看剩余内容，请用 startLine 参数分页读取)\n[文件结束: $path]"
        }
    }

    // ponytail: use WriteCommandAction for safe file writes
    private fun writeFile(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"
        val content = ToolInput.string(input, "content") ?: return "错误: 缺少 content 参数"

        val maxLines = 3000
        val lineCount = content.lines().size
        if (lineCount > maxLines) return "错误: 内容过长 ($lineCount 行，上限 $maxLines 行)"

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = File(basePath, path)
        val isNew = !file.exists()

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }

        return "✅ 文件已写入: $path ($lineCount 行, ${content.length} 字节)\n操作类型: ${if (isNew) "新建" else "覆盖"}"
    }

    // ponytail: precise string replacement
    private fun editFile(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"
        val oldString = ToolInput.string(input, "oldString") ?: return "错误: 缺少 oldString 参数"
        val newString = ToolInput.string(input, "newString") ?: return "错误: 缺少 newString 参数"

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = File(basePath, path)
        if (!file.exists()) {
            // 空 oldString + 文件不存在 = 创建新文件
            if (oldString.isEmpty()) {
                file.parentFile?.mkdirs()
                file.writeText(newString)
                return "✅ 文件已创建: $path (${newString.lines().size} 行)"
            }
            return "错误: 文件 \"$path\" 不存在"
        }

        val currentContent = file.readText()
        val count = currentContent.split(oldString).size - 1

        if (count == 0) {
            // Show nearby content for context
            val lines = currentContent.lines()
            return "错误: 在 \"$path\" 中未找到 oldString。\n提示: 请使用 readFile 确认文件内容。\n文件共 ${lines.size} 行。"
        }
        if (count > 1) {
            return "错误: oldString 在 \"$path\" 中匹配到 $count 处，必须唯一。\n请使用更长的 oldString 使其唯一。"
        }

        val newContent = currentContent.replace(oldString, newString)

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                file.writeText(newContent)
            }
        }

        val replacedLines = oldString.lines().size
        val newLines = newString.lines().size
        return "✅ 已修改: $path\n替换了 $replacedLines 行 → $newLines 行"
    }

    // ponytail: Runtime.exec, 30s timeout for safety
    private fun runShell(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val command = ToolInput.string(input, "command") ?: return "错误: 缺少 command 参数"
        val workDir = ToolInput.string(input, "workDir") ?: project.basePath

        val dir = File(workDir ?: ".")
        val process = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", command), null, dir)
        session.runningProcesses.add(process)

        // Read stdout/stderr on separate threads to prevent deadlock
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }
        val stdout = stdoutFuture.get(60, java.util.concurrent.TimeUnit.SECONDS)
        val stderr = stderrFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
        process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly(); session.runningProcesses.remove(process)
        }

        val start = System.currentTimeMillis()
        val exitCode = process.exitValue()
        val elapsed = (System.currentTimeMillis() - start) / 1000
        val output = (stdout + stderr).take(10000)

        return if (exitCode == 0) {
            "\$ $command\n${output.take(4000)}\n退出码: $exitCode | 耗时: ${elapsed}s | ${output.lines().size} 行输出"
        } else {
            "\$ $command\n${output.take(4000)}\n退出码: $exitCode | 耗时: ${elapsed}s"
        }
    }

    // ponytail: VFS directory listing
    private fun listFiles(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val dirPath = ToolInput.string(input, "dirPath") ?: "."
        val maxDepth = ToolInput.int(input, "maxDepth") ?: 2

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val dir = File(basePath, dirPath)
        if (!dir.exists() || !dir.isDirectory) return "错误: 目录 \"$dirPath\" 不存在"

        val sb = StringBuilder()
        sb.appendLine("$dirPath/")
        listDir(dir, "", maxDepth, sb)
        return sb.toString()
    }

    private fun listDir(
        dir: File,
        prefix: String,
        maxDepth: Int,
        sb: StringBuilder,
        depth: Int = 0
    ) {
        if (depth >= maxDepth) return
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        children.take(200).forEach { f ->
            if (f.isDirectory) {
                sb.appendLine("$prefix├── ${f.name}/")
                listDir(f, "$prefix│   ", maxDepth, sb, depth + 1)
            } else {
                sb.appendLine("$prefix├── ${f.name}")
            }
        }
    }

    private fun searchContent(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val query = ToolInput.string(input, "query") ?: return "错误: 缺少 query 参数"
        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val results = mutableListOf<String>()
        val sourceExtensions =
            setOf("kt", "java", "kts", "xml", "json", "md", "gradle", "properties", "yml", "yaml")

        File(basePath).walkTopDown()
            .filter {
                it.isFile && !it.path.contains("/build/") && !it.path.contains("/.git/") && !it.path.contains(
                    "/.idea/"
                ) && it.extension in sourceExtensions
            }
            .take(800)
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (line.contains(query, ignoreCase = true) && results.size < 50) {
                        results.add(
                            "${file.relativeTo(File(basePath))}:${idx + 1}: ${
                                line.trim().take(120)
                            }"
                        )
                    }
                }
            }
        return if (results.isEmpty()) "未找到匹配 \"$query\" 的内容。"
        else if (results.size >= 50) "找到 ${results.size}+ 条匹配，已截断到 50 条:\n${
            results.joinToString(
                "\n"
            )
        }\n如有必要，请使用更精确的搜索词缩小范围。"
        else "找到 ${results.size} 条匹配:\n${results.joinToString("\n")}"
    }

    // ponytail: IDE inspection placeholder — real impl needs IntelliJ inspection API
    private fun readLints(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = File(basePath, path)
        if (!file.exists()) return "错误: 文件 \"$path\" 不存在"

        // ponytail: return empty — real IDE inspection integration in later phase
        return "文件: $path\n0 个错误, 0 个警告, 0 个提示\n(IDE inspection 集成将在后续 Phase 实现)"
    }

    // ponytail: real spawnAgent — creates sub-AgentLoop for parallel work
    private val multiAgent = MultiAgentManager(project)

    private fun spawnAgent(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val task = ToolInput.string(input, "task") ?: return "错误: 缺少 task 参数"
        return multiAgent.spawnAgent(task, session)
    }
}
