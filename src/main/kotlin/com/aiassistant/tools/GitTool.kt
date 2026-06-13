package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git 操作工具集合：diff, log, status
 */
class GitDiffTool : AgentTool {
    override val name = "git_diff"
    override val description = "查看 git 工作区的变更差异（unstaged + staged）"
    override val parameters = listOf(
        ToolParameter("staged", "boolean", "是否仅查看已暂存的变更，默认 false 查看所有变更")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val staged = params["staged"]?.toBoolean() ?: false
        val args = if (staged) listOf("diff", "--staged") else listOf("diff")
        return runGit(project, *args.toTypedArray())
    }
}

class GitLogTool : AgentTool {
    override val name = "git_log"
    override val description = "查看最近的 git 提交历史"
    override val parameters = listOf(
        ToolParameter("count", "integer", "显示的提交数量，默认 10")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val count = params["count"]?.toIntOrNull() ?: 10
        return runGit(project, "log", "--oneline", "-$count")
    }
}

class GitStatusTool : AgentTool {
    override val name = "git_status"
    override val description = "查看 git 工作区状态（修改、新增、删除的文件）"
    override val parameters = emptyList<ToolParameter>()

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        return runGit(project, "status", "--short")
    }
}

private fun runGit(project: Project, vararg args: String): ToolResult {
    val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
    return try {
        val process = ProcessBuilder("git", "-C", basePath, *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(10, TimeUnit.SECONDS); if (!finished) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
        ToolResult.ok(if (output.length > 10000) output.take(10000) + "\n… (已截断)" else output.ifBlank { "(无输出)" })
    } catch (e: Exception) {
        ToolResult.err("Git 命令失败: ${e.message}")
    }
}
