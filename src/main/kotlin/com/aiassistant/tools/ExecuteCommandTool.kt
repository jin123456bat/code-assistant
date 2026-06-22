package com.aiassistant.tools
import com.aiassistant.AppLogger

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 执行终端命令的工具。
 *
 * ## 安全策略
 * 所有命令执行前均触发审批卡，由用户确认。**不做黑名单拦截。**
 * 工具不在 SAFE_TOOLS 中，每次弹卡确认——用户是最终的安全决策者。
 *
 * ## 设计决策：使用 `/bin/bash -c` 而非命令过滤
 * shell 会解释元字符（`;`, `|`, `&&`, `$()` 等），存在命令注入理论风险。
 * **不修复原因**：
 * 1. 审批机制已提供有效防护——不在 SAFE_TOOLS 中，每次弹卡由用户审查确认
 * 2. 对齐 Claude Code 行为——Claude Code 同样依赖用户审批而非命令过滤
 * 3. 命令过滤（黑名单）容易产生误报（合法脚本含 `&&`）和漏报（新攻击模式）
 * 4. ProcessBuilder 不使用 shell 的方式无法支持管道、重定向等常用 shell 特性
 * **风险场景**：若用户将 execute_command 加入白名单或盲批审批卡，LLM 可能执行恶意命令。
 * 白名单机制的设计意图是用户明确信任此工具，风险由用户承担。
 */
class ExecuteCommandTool : AgentTool {
    override val name = "execute_command"
    override val description = "在项目根目录执行终端命令。每次执行前需要用户确认。"
    override val parameters = listOf(
        ToolParameter("command", "string", "要执行的 shell 命令", required = true),
        ToolParameter("working_dir", "string", "工作目录，相对于项目根目录（可选）")
    )

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val command = params["command"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("command 不能为空")

        // 命令长度限制：防止异常长命令（如 base64 编码的恶意脚本）
        if (command.length > 50000) {
            return ToolResult.err("安全限制：命令长度不能超过 50000 字符（当前 ${command.length} 字符）")
        }

        val basePath = params["_worktree"] ?: project.basePath ?: return ToolResult.err("项目路径不可用")
        val workingDir = params["working_dir"]?.let { wd ->
            if (File(wd).isAbsolute) File(wd) else File(basePath, wd)
        } ?: File(basePath)

        // 路径穿越防护：防止 LLM 传入 ../../etc 绕过项目目录限制
        if (!com.aiassistant.shared.PathUtils.isInsideProject(workingDir.path, basePath)) {
            return ToolResult.err("安全限制：不能操作项目目录之外的工作目录")
        }
        if (!workingDir.exists()) return ToolResult.err("工作目录不存在: ${workingDir.path}")

        // 危险模式检测：匹配时不阻止执行（审批卡是主要防线），但在结果中附加安全警告
        val dangerousPatterns = listOf(
            "curl" to "| sh", "curl" to "| bash", "wget" to "| sh", "wget" to "| bash",
            "rm -rf /" to "", "rm -rf ~" to "", "rm -fr /" to "",
            "> /dev/sda" to "", "dd if=" to "",
            "fork bomb" to ":(){ :|:& };:"
        )
        val safetyWarnings = dangerousPatterns.mapNotNull { (prefix, suffix) ->
            val cmdLower = command.lowercase()
            if (suffix.isEmpty() && cmdLower.contains(prefix.lowercase())) prefix
            else if (suffix.isNotEmpty() && cmdLower.contains(prefix) && cmdLower.contains(suffix)) "$prefix ... $suffix"
            else null
        }

        val shell = if (System.getProperty("os.name").lowercase().contains("win")) {
            arrayOf("cmd.exe", "/c", command)
        } else {
            arrayOf("/bin/bash", "-c", command)
        }

        val process = ProcessBuilder(*shell)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

        return try {
            // 逐行读取输出，实时推送到 UI
            val outputBuffer = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val readThread = Thread {
                try {
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            outputBuffer.appendLine(line)
                            onProgress?.invoke(outputBuffer.toString())
                        }
                    }
                } catch (e: Exception) { AppLogger.warn("ExecuteCommand: 读取进程输出失败: ${e.message}") }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            readThread.join(3000)
            val output = outputBuffer.toString()
            val exitCode = if (finished) process.exitValue() else -1

            // 工具结果截断（防止超大规模输出耗尽 token）
            val maxChars = 10000
            val truncated = output.take(maxChars)
            val summary = buildString {
                append("命令: $command\n")
                append("工作目录: ${workingDir.path}\n")
                append("退出码: $exitCode\n")
                if (!finished) append("(命令执行超时，已强制终止)\n")
                if (safetyWarnings.isNotEmpty()) {
                    append("⚠️ 安全警告：检测到潜在危险模式 — ${safetyWarnings.joinToString("; ")}\n")
                }
                append("\n$truncated")
                if (output.length > maxChars) append("\n... (输出已截断，共 ${output.length} 字符)")
            }
            ToolResult.ok(summary)
        } catch (e: Exception) {
            ToolResult.err("命令执行失败: ${e.message}")
        } finally {
            try { process.outputStream.close() } catch (_: Exception) {}
            try { process.inputStream.close() } catch (_: Exception) {}
        }
    }
}
