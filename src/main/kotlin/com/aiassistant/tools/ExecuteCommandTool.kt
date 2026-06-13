package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 执行终端命令的工具（带白名单沙箱防护）
 */
class ExecuteCommandTool : AgentTool {
    override val name = "execute_command"
    override val description = "在项目根目录执行终端命令。首次执行通过内联审批选择卡确认，确认后自动加入白名单。"
    override val parameters = listOf(
        ToolParameter("command", "string", "要执行的 shell 命令", required = true),
        ToolParameter("working_dir", "string", "工作目录，相对于项目根目录（可选）")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val command = params["command"] ?: return ToolResult.err("缺少 command 参数")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
        val workingDir = params["working_dir"]?.let { wd ->
            if (File(wd).isAbsolute) File(wd) else File(basePath, wd)
        } ?: File(basePath)

        if (!workingDir.exists()) return ToolResult.err("工作目录不存在: ${workingDir.path}")

        // 危险命令拦截
        val dangerousPatterns = listOf(
            "rm -rf /" to "删除根目录", "rm -rf ~" to "删除用户目录",
            "rm -rf ./" to "删除当前目录", "rm -rf ." to "删除当前目录",
            "dd if=" to "磁盘操作", "mkfs." to "格式化磁盘",
            ":(){ :|:& };:" to "fork 炸弹", "> /dev/sda" to "覆写磁盘",
            "chmod 777 /" to "危险权限修改",
            "git push -f" to "强制推送", "git push --force" to "强制推送",
            "> /dev/null; rm" to "管道后删除"
        )
        for ((pattern, desc) in dangerousPatterns) {
            if (command.contains(pattern, ignoreCase = true)) {
                return ToolResult.err("危险命令被拦截 [$desc]: $pattern")
            }
        }

        // 确认由 AgentLoop.onConfirmTool → 审批选择卡统一处理，工具层不再弹窗
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

            val output = process.inputStream.bufferedReader().readText()
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
