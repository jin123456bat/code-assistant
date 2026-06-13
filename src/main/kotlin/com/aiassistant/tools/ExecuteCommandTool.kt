package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 执行终端命令的工具。
 * 安全策略：所有命令执行前均触发审批卡，由用户确认。不做黑名单拦截。
 */
class ExecuteCommandTool : AgentTool {
    override val name = "execute_command"
    override val description = "在项目根目录执行终端命令。每次执行前需要用户确认。"
    override val parameters = listOf(
        ToolParameter("command", "string", "要执行的 shell 命令", required = true),
        ToolParameter("working_dir", "string", "工作目录，相对于项目根目录（可选）")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val command = params["command"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("command 不能为空")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
        val workingDir = params["working_dir"]?.let { wd ->
            if (File(wd).isAbsolute) File(wd) else File(basePath, wd)
        } ?: File(basePath)

        if (!workingDir.exists()) return ToolResult.err("工作目录不存在: ${workingDir.path}")

        return try {
            val shell = if (System.getProperty("os.name").lowercase().contains("win")) {
                arrayOf("cmd.exe", "/c", command)
            } else {
                arrayOf("/bin/bash", "-c", command)
            }

            val process = ProcessBuilder(*shell)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS) // 等待强制终止完成
            }
            val exitCode = if (finished) process.exitValue() else -1

            // 工具结果截断（防止超大规模输出耗尽 token）
            val maxChars = 10000
            val truncated = output.take(maxChars)
            val summary = buildString {
                append("命令: $command\n")
                append("工作目录: ${workingDir.path}\n")
                append("退出码: $exitCode\n")
                if (!finished) append("(命令执行超时，已强制终止)\n")
                append("\n$truncated")
                if (output.length > maxChars) append("\n... (输出已截断，共 ${output.length} 字符)")
            }
            ToolResult.ok(summary)
        } catch (e: Exception) {
            ToolResult.err("命令执行失败: ${e.message}")
        }
    }
}
