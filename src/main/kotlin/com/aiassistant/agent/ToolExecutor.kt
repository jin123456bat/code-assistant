package com.aiassistant.agent

import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler

data class ToolApprovalRequest(
    val toolUseId: String,
    val toolName: String,
    val message: String,
    val dangerous: Boolean,
    val complete: (ToolApprovalPolicy.ApprovalResult) -> Unit
)

class ToolExecutor(private val project: Project, private val session: AgentSession) {

    /** 工具状态变更回调，供 AgentLoop 驱动 ToolCallCard 实时刷新 */
    var onToolStateChanged: ((toolUseId: String, state: ToolCallState, result: String?, durationMs: Long?) -> Unit)? =
        null

    /** 审批请求回调，供 UI 在 ToolCallCard 内嵌按钮中完成审批。 */
    var onApprovalRequested: ((ToolApprovalRequest) -> Unit)? = null

    /** 子 Agent 事件回调，透传给 AgentLoop → ChatViewModel → MultiAgentBlock */
    var onSubAgentEvent: ((MultiAgentManager.SubAgentEvent) -> Unit)? = null

    /** WebSearch/WebFetch 复用 OkHttpClient */
    private val webHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 执行单个工具调用。
     *
     * 同一轮中多个 tool_use 按 LLM 返回的顺序串行执行，不并行（对齐 docs/agent/loop.md §一）。
     * 每个工具执行完毕后，结果由调用方 AgentLoop.run() 立即追加到 params.messages，
     * 确保后续工具在下一轮 LLM 推理时能看到前面工具的执行结果。
     *
     * 当前轮次内工具间通过 session 共享状态（如 fileStamps、filesReadThisTurn、calledSkills），
     * 实现 Write 的 Read 前置校验和 Edit 的 modificationStamp 冲突检测等跨工具协作。
     */
    fun execute(toolUse: BetaToolUseBlock): String {
        val toolUseId = toolUse.id()
        val toolName = toolUse.name()
        return try {
            val input = toolUse._input()
            val timeoutSec = ToolInput.int(input, "timeout") ?: 0
            val approvalCtx =
                ToolApprovalPolicy.ApprovalContext(session, toolName, toolUse, project)
            val (needsApproval, reason) = ToolApprovalPolicy.needsUserApproval(approvalCtx)
            if (needsApproval) {
                when (val approvalResult = requestApproval(toolUse, reason)) {
                    ToolApprovalPolicy.ApprovalResult.REJECTED -> {
                        val result = "用户拒绝执行工具: $toolName"
                        onToolStateChanged?.invoke(toolUseId, ToolCallState.REJECTED, result, null)
                        return result
                    }

                    ToolApprovalPolicy.ApprovalResult.ALLOW_SESSION -> {
                        if (!ToolApprovalPolicy.isDangerousReason(reason)) {
                            ToolApprovalPolicy.approveForSession(session, toolName)
                        }
                    }

                    ToolApprovalPolicy.ApprovalResult.ALLOW_ONCE -> {
                        // 仅本次放行，不加入白名单
                    }
                }
            }
            // 标记首次工具使用已完成（对齐 docs/agent/tools.md §六 首次工具使用）
            ToolApprovalPolicy.markFirstToolUse(session, toolName)
            onToolStateChanged?.invoke(toolUseId, ToolCallState.EXECUTING, null, null)
            val start = System.currentTimeMillis()
            val result = when (toolName) {
                "Read" -> read(toolUse)
                "Write" -> write(toolUse)
                "Edit" -> edit(toolUse)
                "Bash" -> runShell(toolUse, timeoutSec)
                "Glob" -> glob(toolUse)
                "Grep" -> grep(toolUse)
                "readLints" -> readLints(toolUse)
                "Skill" -> skill(toolUse)
                "Agent" -> task(toolUse)
                "WebSearch" -> webSearch(toolUse)
                "WebFetch" -> webFetch(toolUse)
                "AskUserQuestion" -> askUserQuestion(toolUse)
                "Symbol" -> symbol(toolUse)
                "CreatePlan", "createPlan" -> createPlan(toolUse)
                "ListPlans", "listPlans" -> listPlans()
                "RemovePlan", "removePlan" -> removePlan(toolUse)
                "ReorderPlans", "reorderPlans" -> reorderPlans(toolUse)
                "MarkPlanDone", "markPlanDone" -> markPlanDone(toolUse)
                else -> {
                    // MCP 工具处理：工具名格式为 `serverName/toolName`
                    val toolClass = ToolRegistry.get(toolName)
                    if (toolClass == com.aiassistant.mcp.McpToolStub::class.java) {
                        val serverId = com.aiassistant.mcp.McpManager.extractServerId(toolName)
                        if (serverId != null) {
                            val mcpManager = com.aiassistant.mcp.McpManager.getInstance(project)
                            val server = mcpManager?.getServer(serverId)
                            if (server == null || server.state != com.aiassistant.mcp.McpManager.State.RUNNING) {
                                "MCP Server [$serverId] 断连，无法执行工具: $toolName"
                            } else {
                                // Server 运行中，转发 JSON-RPC 调用到 MCP Server
                                executeMcpTool(toolName, toolUse, serverId)
                            }
                        } else {
                            "未知工具: $toolName"
                        }
                    } else {
                        "未知工具: $toolName"
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - start
            val isError =
                result.startsWith("错误") || result.startsWith("超时") || result.startsWith("未知工具") || result.startsWith(
                    "MCP Server"
                )
            onToolStateChanged?.invoke(
                toolUseId,
                if (isError) ToolCallState.ERROR else ToolCallState.DONE,
                result,
                elapsed
            )
            result
        } catch (e: Exception) {
            onToolStateChanged?.invoke(
                toolUseId,
                ToolCallState.ERROR,
                "错误: ${e.javaClass.simpleName}: ${e.message}",
                null
            )
            "错误: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 首次审批/危险命令审批对话框。
     * 危险命令仅显示 [允许一次] / [拒绝]（无允许此会话按钮）。
     * 对齐 docs/agent/tools.md §六 审批判定流程
     */
    private fun requestApproval(
        toolUse: BetaToolUseBlock,
        reason: ToolApprovalPolicy.ApprovalReason?
    ): ToolApprovalPolicy.ApprovalResult {
        val toolUseId = toolUse.id()
        val toolName = toolUse.name()
        session.requireApproval()
        onToolStateChanged?.invoke(toolUseId, ToolCallState.AWAITING_APPROVAL, null, null)
        val input = toolUse._input()
        val isDangerous = ToolApprovalPolicy.isDangerousReason(reason)
        val message = ToolApprovalPolicy.describe(toolName, input, reason)
        val approvalCallback = onApprovalRequested
        if (approvalCallback != null) {
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference(ToolApprovalPolicy.ApprovalResult.REJECTED)
            approvalCallback(
                ToolApprovalRequest(
                    toolUseId = toolUseId,
                    toolName = toolName,
                    message = message,
                    dangerous = isDangerous,
                    complete = { result ->
                        resultRef.set(result)
                        latch.countDown()
                    }
                )
            )
            latch.await()
            return finishApproval(resultRef.get())
        }

        val app = ApplicationManager.getApplication()

        val resultRef = AtomicReference(ToolApprovalPolicy.ApprovalResult.REJECTED)
        if (app.isDispatchThread) {
            resultRef.set(showApprovalDialog(message, isDangerous))
        } else {
            app.invokeAndWait { resultRef.set(showApprovalDialog(message, isDangerous)) }
        }
        return finishApproval(resultRef.get())
    }

    private fun finishApproval(result: ToolApprovalPolicy.ApprovalResult): ToolApprovalPolicy.ApprovalResult {
        when (result) {
            ToolApprovalPolicy.ApprovalResult.REJECTED -> session.approvalRejected()
            else -> session.approvalGranted()
        }
        return result
    }

    /**
     * 审批对话框。
     * 危险命令仅显示 [允许一次] / [拒绝]（无"允许此会话"按钮）。
     * 对齐 docs/agent/tools.md §六 审批提示行为
     */
    private fun showApprovalDialog(
        message: String,
        isDangerous: Boolean
    ): ToolApprovalPolicy.ApprovalResult {
        val options = if (isDangerous) {
            arrayOf("允许一次", "拒绝")
        } else {
            arrayOf("允许一次", "允许此会话", "拒绝")
        }
        val choice = Messages.showDialog(
            project,
            message,
            "确认工具执行",
            options,
            0,  // 默认选中"允许一次"
            Messages.getWarningIcon()
        )
        return when (choice) {
            0 -> ToolApprovalPolicy.ApprovalResult.ALLOW_ONCE
            1 -> if (isDangerous) ToolApprovalPolicy.ApprovalResult.REJECTED else ToolApprovalPolicy.ApprovalResult.ALLOW_SESSION
            else -> ToolApprovalPolicy.ApprovalResult.REJECTED
        }
    }

    // ── Read ──
    private fun read(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"
        val startLine = ToolInput.int(input, "startLine")
        val endLine = ToolInput.int(input, "endLine")

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = resolveProjectFile(basePath, path) ?: return pathEscapedError(path)
        if (!file.exists()) return "错误: 文件 \"$path\" 不存在"

        // 图片文件：返回 base64 编码（对齐 docs/agent/images.md §三 Read 工具图片支持）
        val imageMime = imageMimeType(file)
        if (imageMime != null) {
            val bytes = file.readBytes()
            val maxBytes = 5 * 1024 * 1024
            if (bytes.size > maxBytes) return "错误: 图片 \"$path\" 过大 (${bytes.size} 字节，上限 $maxBytes 字节)"
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            session.fileStamps[path] = file.lastModified()
            session.filesReadThisTurn.add(path)
            return "${IMAGE_RESULT_PREFIX}$imageMime\n[图片: $path (${bytes.size} 字节, ${imageMime})]\n$base64\n[图片结束: $path]"
        }

        val lines = file.readLines()
        val from = (startLine?.minus(1))?.coerceAtLeast(0) ?: 0
        val to = (endLine?.coerceAtMost(lines.size)) ?: lines.size

        // 记录 modificationStamp 供 Edit 冲突检测
        session.fileStamps[path] = file.lastModified()
        // 记录当前 turn 已 Read，供 Edit/Write 前置 Read 校验（对齐 docs/agent/tools.md §七）
        session.filesReadThisTurn.add(path)

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

    // ── Write ──
    private fun write(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"
        val content = ToolInput.string(input, "content") ?: return "错误: 缺少 content 参数"

        val maxLines = 3000
        val lineCount = content.lines().size
        if (lineCount > maxLines) return "错误: 内容过长 ($lineCount 行，上限 $maxLines 行)"

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = resolveProjectFile(basePath, path) ?: return pathEscapedError(path)
        var isNew = !file.exists()

        withFileWriteLock(file, path) {
            isNew = !file.exists()
            if (!isNew && !session.filesReadThisTurn.contains(path)) {
                return@withFileWriteLock "错误: 当前 turn 中文件 \"$path\" 未被 Read 过。\n请先用 Read 读取 $path 后再修改"
            }
            validateFileStamp(file, path)?.let { return@withFileWriteLock it }
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
            }
            null
        }?.let { return it }

        // 更新 stamp
        session.fileStamps[path] = file.lastModified()
        session.filesModifiedThisTurn.add(path)  // 大范围修改审批检测（对齐 docs/agent/tools.md §六）

        return "✅ 文件已写入: $path ($lineCount 行, ${content.length} 字节)\n操作类型: ${if (isNew) "新建" else "覆盖"}"
    }

    // ── Edit (含 modificationStamp 校验) ──
    private fun edit(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"
        val oldString = ToolInput.string(input, "oldString") ?: return "错误: 缺少 oldString 参数"
        val newString = ToolInput.string(input, "newString") ?: return "错误: 缺少 newString 参数"

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = resolveProjectFile(basePath, path) ?: return pathEscapedError(path)
        if (!file.exists()) {
            if (oldString.isEmpty()) {
                withFileWriteLock(file, path) {
                    if (file.exists()) {
                        return@withFileWriteLock "错误: 文件 \"$path\" 已存在。\n请先用 Read 读取 $path 后再修改"
                    }
                    file.parentFile?.mkdirs()
                    file.writeText(newString)
                    null
                }?.let { return it }
                session.fileStamps[path] = file.lastModified()
                session.filesModifiedThisTurn.add(path)  // 大范围修改审批检测（对齐 docs/agent/tools.md §六）
                return "✅ 文件已创建: $path (${newString.lines().size} 行)"
            }
            return "错误: 文件 \"$path\" 不存在"
        }

        // Read 前置校验（对齐 docs/agent/tools.md §七）
        if (!session.filesReadThisTurn.contains(path)) {
            return "错误: 当前 turn 中文件 \"$path\" 未被 Read 过。\n请先用 Read 读取 $path 后再修改"
        }

        var replacedLines = oldString.lines().size
        var newLines = newString.lines().size
        withFileWriteLock(file, path) {
            if (!file.exists()) return@withFileWriteLock "错误: 文件 \"$path\" 不存在"
            validateFileStamp(file, path)?.let { return@withFileWriteLock it }
            val currentContent = file.readText()
            val count = currentContent.split(oldString).size - 1

            if (count == 0) {
                val lines = currentContent.lines()
                return@withFileWriteLock "错误: 在 \"$path\" 中未找到 oldString。\n提示: 请使用 Read 确认文件内容。\n文件共 ${lines.size} 行。"
            }
            if (count > 1) {
                return@withFileWriteLock "错误: oldString 在 \"$path\" 中匹配到 $count 处，必须唯一。\n请使用更长的 oldString 使其唯一。"
            }

            val newContent = currentContent.replace(oldString, newString)
            replacedLines = oldString.lines().size
            newLines = newString.lines().size
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    file.writeText(newContent)
                }
            }
            null
        }?.let { return it }

        // 更新 stamp
        session.fileStamps[path] = file.lastModified()
        session.filesModifiedThisTurn.add(path)  // 大范围修改审批检测（对齐 docs/agent/tools.md §六）

        return "✅ 已修改: $path\n替换了 $replacedLines 行 → $newLines 行"
    }

    private fun resolveProjectFile(basePath: String, path: String): File? {
        val base = File(basePath).canonicalFile
        val file = File(base, path).canonicalFile
        val inside = file == base || file.path.startsWith(base.path + File.separator)
        return file.takeIf { inside }
    }

    private fun pathEscapedError(path: String): String =
        "错误: 文件路径 \"$path\" 超出项目根目录，已拒绝访问"

    /** 判断文件是否为支持的图片格式，返回 MIME 类型。非图片返回 null。 */
    private fun imageMimeType(file: File): String? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }
    }

    private fun validateFileStamp(file: File, path: String): String? {
        val lastReadStamp = session.fileStamps[path]
        val currentStamp = file.lastModified()
        return if (lastReadStamp != null && lastReadStamp != currentStamp) {
            "错误: \"$path\" 已被外部修改（上次读取 stamp=$lastReadStamp，当前 stamp=$currentStamp）。\n请使用 Read 重新读取文件后再试。"
        } else {
            null
        }
    }

    private fun withFileWriteLock(
        file: File,
        displayPath: String,
        writeAction: () -> String?
    ): String? {
        val lock = multiAgent.acquireFileLock(file.canonicalPath)
        if (!lock.tryLock()) {
            return "错误: 文件 \"$displayPath\" 正在被其他 Agent 修改，请稍后重试。"
        }
        return try {
            writeAction()
        } finally {
            lock.unlock()
        }
    }

    // ── Bash ──
    private fun runShell(toolUse: BetaToolUseBlock, timeoutSec: Int): String {
        val input = toolUse._input()
        val command = ToolInput.string(input, "command") ?: return "错误: 缺少 command 参数"
        val workDir = ToolInput.string(input, "workDir") ?: project.basePath

        // 安全检查：工作目录限定为项目根（对齐 docs/agent/tools.md §五）
        val dir = resolveProjectFile(project.basePath ?: ".", workDir ?: ".")?.let { f ->
            if (f.isDirectory) f else f.parentFile
        } ?: return "错误: 工作目录 \"$workDir\" 超出项目根，已拒绝访问"

        val cmdLine = GeneralCommandLine("/bin/bash", "-c", command)
            .withWorkDirectory(dir)
            .withCharset(Charsets.UTF_8)
        val handler = OSProcessHandler(cmdLine)
        handler.startNotify()
        val process = handler.process
        session.runningProcesses.add(process)

        val start = System.currentTimeMillis()

        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        val (stdout, stderr) = if (timeoutSec > 0) {
            try {
                val out =
                    stdoutFuture.get(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                val err = stderrFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
                process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                if (process.isAlive) {
                    process.destroyForcibly(); session.runningProcesses.remove(process)
                    return "超时: 命令执行超过 ${timeoutSec}s，已强制终止\n\$ $command"
                }
                Pair(out, err)
            } catch (e: java.util.concurrent.TimeoutException) {
                process.destroyForcibly(); session.runningProcesses.remove(process)
                return "超时: 命令执行超过 ${timeoutSec}s，已强制终止\n\$ $command"
            }
        } else {
            val out = stdoutFuture.get()
            val err = stderrFuture.get()
            val effectiveTimeout = if (timeoutSec == 0) 300 else timeoutSec
            process.waitFor(effectiveTimeout, TimeUnit.SECONDS)
            Pair(out, err)
        }

        val exitCode = process.exitValue()
        val elapsed = (System.currentTimeMillis() - start) / 1000
        val rawOutput = stdout + stderr

        // 截断策略：上限 200 行 / 4000 字符，中段截断：保留头部 30 行 + 尾部 30 行，中间标注省略行数
        val truncatedOutput = truncateBashOutput(rawOutput)

        return if (exitCode == 0) {
            "\$ $command\n${truncatedOutput}\n退出码: $exitCode | 耗时: ${elapsed}s | ${rawOutput.lines().size} 行输出"
        } else {
            "\$ $command\n${truncatedOutput}\n退出码: $exitCode | 耗时: ${elapsed}s"
        }
    }

    /**
     * Bash 输出截断策略：上限 200 行 / 4000 字符（取先到达者），
     * 中段截断：保留头部 30 行 + 尾部 30 行，中间标注省略行数。
     */
    private fun truncateBashOutput(output: String): String {
        val maxLines = 200
        val maxChars = 4000
        val headLines = 30
        val tailLines = 30

        // 按字符上限先截断
        val charTruncated = if (output.length > maxChars) {
            output.take(maxChars)
        } else {
            output
        }

        val charTruncatedLines = charTruncated.lines()

        // 行数也在上限内，直接返回
        if (charTruncatedLines.size <= maxLines) {
            return charTruncated
        }

        // 需要中段截断
        val head = charTruncatedLines.take(headLines)
        val tail = charTruncatedLines.takeLast(tailLines)
        val omitted = charTruncatedLines.size - headLines - tailLines

        if (omitted <= 0) {
            return charTruncated
        }

        return buildString {
            appendLine(head.joinToString("\n"))
            appendLine("... (省略 $omitted 行)")
            append(tail.joinToString("\n"))
        }
    }

    // ── Glob ──
    private fun glob(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val dirPath = ToolInput.string(input, "dirPath") ?: "."
        val maxDepth = ToolInput.int(input, "maxDepth") ?: 2
        val offset = ToolInput.int(input, "offset") ?: 0

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val dir = File(basePath, dirPath)
        if (!dir.exists() || !dir.isDirectory) return "错误: 目录 “$dirPath” 不存在"

        // 先收集所有条目（展平为列表），然后应用 offset 和 50 上限
        val allEntries = mutableListOf<String>()
        allEntries.add("$dirPath/")
        collectEntries(dir, "", maxDepth, allEntries)

        val totalEntries = allEntries.size
        val maxEntries = 50
        val paged = if (offset >= totalEntries) {
            emptyList()
        } else {
            allEntries.drop(offset).take(maxEntries)
        }

        val sb = StringBuilder()
        paged.forEach { sb.appendLine(it) }

        if (totalEntries > maxEntries + offset) {
            val nextOffset = offset + maxEntries
            sb.appendLine("... (共 $totalEntries 条目，已截断到 $maxEntries。用 dirPath/maxDepth 缩小范围，或用 offset=$nextOffset 翻页获取更多)")
        } else if (totalEntries > maxEntries) {
            sb.appendLine("... (共 $totalEntries 条目，已返回 ${paged.size} 条)")
        }

        return sb.toString()
    }

    /**
     * 递归收集目录条目到列表中，用于计数和分页。
     */
    private fun collectEntries(
        dir: File,
        prefix: String,
        maxDepth: Int,
        entries: MutableList<String>,
        depth: Int = 0
    ) {
        if (depth >= maxDepth) return
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (f in children) {
            if (f.isDirectory) {
                entries.add("$prefix├── ${f.name}/")
                collectEntries(f, "$prefix│   ", maxDepth, entries, depth + 1)
            } else {
                entries.add("$prefix├── ${f.name}")
            }
        }
    }

    // ── Grep ──
    private fun grep(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val query = ToolInput.string(input, "query") ?: return "错误: 缺少 query 参数"
        val filePattern = ToolInput.string(input, "filePattern")
        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val results = mutableListOf<String>()
        val sourceExtensions =
            setOf("kt", "java", "kts", "xml", "json", "md", "gradle", "properties", "yml", "yaml")

        // ponytail: 正则优先，非法正则回退到字面子串匹配
        val regex = try {
            Regex(query, setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
            Regex(Regex.escape(query), setOf(RegexOption.IGNORE_CASE))
        }

        File(basePath).walkTopDown()
            .filter {
                it.isFile && !it.path.contains("/build/") && !it.path.contains("/.git/") && !it.path.contains(
                    "/.idea/"
                ) && !it.path.contains("/node_modules/") && it.extension in sourceExtensions && (filePattern == null || it.name.contains(
                    java.io.File(filePattern).name
                ))
            }
            .take(800)
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (regex.containsMatchIn(line) && results.size < 50) {
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
            results.joinToString("\n")
        }\n如有必要，请使用更精确的搜索词缩小范围。"
        else "找到 ${results.size} 条匹配:\n${results.joinToString("\n")}"
    }

    // ── readLints ──
    private fun readLints(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val path = ToolInput.string(input, "filePath") ?: return "错误: 缺少 filePath 参数"

        val basePath = project.basePath ?: return "错误: 项目路径不可用"
        val file = File(basePath, path)
        if (!file.exists()) return "错误: 文件 \"$path\" 不存在"

        // 通过 PsiManager 获取文件的 PSI 文件
        val virtualFile =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file)
                ?: return "文件: $path\n0 个错误, 0 个警告, 0 个提示\n(文件未在 VFS 索引中)"

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) {
                result = "文件: $path\n0 个错误, 0 个警告, 0 个提示\n(PSI 文件不可用)"
                return@invokeAndWait
            }

            // 使用 IntelliJ 的 inspection 高亮信息获取诊断
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            if (document == null) {
                result = "文件: $path\n0 个错误, 0 个警告, 0 个提示\n(文档不可用)"
                return@invokeAndWait
            }

            try {
                val highlightInfos =
                    DaemonCodeAnalyzerImpl.getHighlights(
                        document,
                        HighlightSeverity.INFORMATION,
                        project
                    )
                val diagnostics = highlightInfos
                    .filter { it.severity >= HighlightSeverity.WARNING }
                    .sortedByDescending { it.severity.myVal }
                    .take(50)

                if (diagnostics.isEmpty()) {
                    result = "文件: $path\n0 个错误, 0 个警告, 0 个提示"
                    return@invokeAndWait
                }

                val errors = diagnostics.count { it.severity == HighlightSeverity.ERROR }
                val warnings = diagnostics.count { it.severity == HighlightSeverity.WARNING }
                val infos =
                    diagnostics.count { it.severity == HighlightSeverity.WEAK_WARNING || it.severity == HighlightSeverity.INFORMATION }

                val maxItems = 50
                val totalCount = highlightInfos.count { it.severity >= HighlightSeverity.WARNING }
                val truncated = diagnostics.take(maxItems)

                val sb = StringBuilder()
                sb.appendLine("文件: $path")
                sb.appendLine("$errors 个错误, $warnings 个警告, $infos 个提示:")

                if (errors > 0) {
                    sb.appendLine()
                    sb.appendLine("错误:")
                    for (d in truncated.filter { it.severity == HighlightSeverity.ERROR }) {
                        val line = document.getLineNumber(d.startOffset) + 1
                        val col = d.startOffset - document.getLineStartOffset(line - 1) + 1
                        sb.appendLine("$line:$col: ${d.description} [${d.inspectionToolId ?: "ERROR"}]")
                    }
                }
                if (warnings > 0) {
                    sb.appendLine()
                    sb.appendLine("警告:")
                    for (d in truncated.filter { it.severity == HighlightSeverity.WARNING }) {
                        val line = document.getLineNumber(d.startOffset) + 1
                        val col = d.startOffset - document.getLineStartOffset(line - 1) + 1
                        sb.appendLine("$line:$col: ${d.description} [${d.inspectionToolId ?: "WARNING"}]")
                    }
                }
                if (infos > 0) {
                    sb.appendLine()
                    sb.appendLine("提示:")
                    for (d in truncated.filter { it.severity == HighlightSeverity.WEAK_WARNING || it.severity == HighlightSeverity.INFORMATION }) {
                        val line = document.getLineNumber(d.startOffset) + 1
                        val col = d.startOffset - document.getLineStartOffset(line - 1) + 1
                        sb.appendLine("$line:$col: ${d.description} [${d.inspectionToolId ?: "INFO"}]")
                    }
                }

                if (totalCount > maxItems) {
                    val remaining = totalCount - maxItems
                    sb.appendLine()
                    sb.append("还有 $remaining 条未显示")
                }
                result = sb.toString()
            } catch (e: Exception) {
                result =
                    "文件: $path\n0 个错误, 0 个警告, 0 个提示\n(IDE inspection 读取失败: ${e.message})"
            }
        }

        return result
    }

    // ── Skill ──
    private val skillManager by lazy { com.aiassistant.skills.SkillManager(project) }

    private fun skill(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val skillName = ToolInput.string(input, "skill") ?: return "错误: 缺少 skill 参数"
        val skills = skillManager.loadSkills()
        val skill = skills.find { it.name == skillName || it.command == skillName }
            ?: return "错误: Skill \"$skillName\" 不存在"
        if (!skill.enabled) return "错误: Skill \"$skillName\" 已被禁用"
        if (skill.hasMissingTools) {
            return "错误: Skill \"$skillName\" 工具缺失: ${skill.missingTools.joinToString(", ")}"
        }
        // 记录已调用 skill，对齐 docs/agent/skills.md §五：compact 时被调用过的 skill 重新注入
        session.calledSkills.add(skill.name)
        return "## Skill: ${skill.name}\n${skill.description}\n\n${skill.content}"
    }

    // ── Agent (子代理) ──
    private val multiAgent = MultiAgentManager(project)

    private fun task(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val prompt = ToolInput.string(input, "prompt") ?: return "错误: 缺少 prompt 参数"
        val timeoutSec = ToolInput.int(input, "timeout") ?: 0
        val runInBackground = ToolInput.bool(input, "run_in_background") ?: false
        // 接线子 Agent 事件回调，使 UI 可以实时展示子 Agent 进度
        multiAgent.onSubAgentEvent = onSubAgentEvent
        return multiAgent.spawnAgent(prompt, session, timeoutSec, runInBackground)
    }

    // ── WebSearch ──
    private fun webSearch(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val query = ToolInput.string(input, "query") ?: return "错误: 缺少 query 参数"
        if (query.length < 2) return "错误: 搜索关键词至少 2 个字符"

        val allowedDomains = ToolInput.stringList(input, "allowedDomains")
        val blockedDomains = ToolInput.stringList(input, "blockedDomains")
        val offset = ToolInput.int(input, "offset") ?: 0
        val maxResults = 10

        return try {
            // 使用 DuckDuckGo Lite 搜索（无 API Key，返回 HTML）
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lite.duckduckgo.com/lite/?q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CodeAssistant/1.0")
                .build()

            val response = webHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return "未找到与 \"$query\" 相关的结果。"

            // 解析 DuckDuckGo Lite 结果页（HTML table 格式）
            val results = parseDuckDuckGoLiteResults(html)

            if (results.isEmpty()) {
                return "未找到与 \"$query\" 相关的结果。请尝试:\n- 使用更通用的搜索词\n- 检查搜索词拼写\n- 尝试不同的表述方式"
            }

            // 应用域名过滤
            val filtered = results.filter { result ->
                val domainOk = allowedDomains?.let { domains ->
                    domains.any { result.url.contains(it, ignoreCase = true) }
                } ?: true
                val blockedOk = blockedDomains?.let { domains ->
                    domains.none { result.url.contains(it, ignoreCase = true) }
                } ?: true
                domainOk && blockedOk
            }

            val totalCount = filtered.size
            val paged = filtered.drop(offset).take(maxResults)

            if (paged.isEmpty() && offset >= totalCount) {
                return "未找到与 \"$query\" 相关的结果（offset=$offset 超出范围，共 $totalCount 条）。"
            }

            val sb = StringBuilder()
            if (totalCount > maxResults + offset) {
                sb.appendLine("找到 $totalCount 条搜索结果，已返回 $maxResults 条（第 ${offset + 1}-${offset + maxResults} 条）:")
            } else {
                sb.appendLine("找到 $totalCount 条搜索结果:")
            }
            paged.forEachIndexed { idx, r ->
                sb.appendLine("${offset + idx + 1}. ${r.title}")
                sb.appendLine("   ${r.url}")
            }
            if (totalCount > maxResults + offset) {
                sb.appendLine("... (共 $totalCount 条，已返回 $maxResults 条。用 offset=${offset + maxResults} 翻页获取更多)")
            }

            sb.toString()
        } catch (e: Exception) {
            "WebSearch 执行失败: ${e.message}"
        }
    }

    /** 解析 DuckDuckGo Lite 搜索结果页面 */
    private fun parseDuckDuckGoLiteResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // DuckDuckGo Lite 结果格式: <a rel="nofollow" href="URL">Title</a><br><span class="link-text">URL</span><br><span>Description</span>
        val linkRegex = Regex(
            """<a[^>]*href="([^"]*)"[^>]*>([^<]*)</a>\s*<br>\s*<span[^>]*class="link-text"[^>]*>([^<]*)</span>""",
            RegexOption.IGNORE_CASE
        )
        linkRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty() && !title.contains(">") && url.startsWith("http")) {
                results.add(SearchResult(title, url))
            }
        }
        return results
    }

    /** 搜索结果数据类 */
    private data class SearchResult(val title: String, val url: String)

    // ── WebFetch ──
    private fun webFetch(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        var url = ToolInput.string(input, "url") ?: return "错误: 缺少 url 参数"
        val prompt = ToolInput.string(input, "prompt") ?: return "错误: 缺少 prompt 参数"

        // HTTP 自动升级为 HTTPS
        if (url.startsWith("http://")) {
            url = url.replace("http://", "https://")
        }

        if (!url.startsWith("https://")) {
            return "错误: 不支持的 URL 协议: $url"
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CodeAssistant/1.0")
                .header("Accept", "text/html,text/plain,*/*")
                .build()

            val response = webHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return "错误: 无法获取 \"$url\" (HTTP ${response.code})。页面可能不存在或需要认证。"
            }

            // 检查重定向
            val finalUrl = response.request.url.toString()
            if (finalUrl != url && !finalUrl.startsWith(url.substringBefore("/", "https://"))) {
                return "重定向: $url → $finalUrl\n请使用重定向后的 URL 重新调用 WebFetch。"
            }

            val contentType = response.header("Content-Type") ?: ""
            if (contentType.contains("application/pdf") || contentType.contains("application/octet-stream")) {
                return "错误: 无法从 \"$url\" 提取内容。页面可能为 PDF、二进制文件或需要 JavaScript 渲染。"
            }

            val rawHtml = response.body?.string() ?: return "错误: 无法获取 \"$url\" 的响应内容。"

            // 简单 HTML 转纯文本：移除 script/style 标签，提取 body 内容，去除 HTML 标签
            val textContent = stripHtml(rawHtml)

            if (textContent.isBlank()) {
                return "错误: 无法从 \"$url\" 提取内容。页面可能为 PDF、二进制文件或需要 JavaScript 渲染。"
            }

            // 截断到合理大小（WebFetch 返回页面内容 + prompt 提取结果）
            val maxChars = 8000
            val truncated = if (textContent.length > maxChars) {
                textContent.take(maxChars) + "\n... (内容已截断到 $maxChars 字符)"
            } else {
                textContent
            }

            "页面内容（基于 prompt: $prompt）:\n$truncated"
        } catch (e: Exception) {
            "WebFetch 执行失败: ${e.message}"
        }
    }

    /** 简单 HTML 标签去除，提取纯文本内容 */
    private fun stripHtml(html: String): String {
        var result = html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&apos;"), "'")
            .replace(Regex("\\s+"), " ")
            .trim()
        return result
    }

    // ── AskUserQuestion ──
    private fun askUserQuestion(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()

        @Suppress("UNCHECKED_CAST")
        val questions = (input as? Map<*, *>)?.get("questions") as? List<Map<String, Any>>
            ?: return "错误: 缺少 questions 参数或格式不正确"
        if (questions.size !in 1..4) return "错误: questions 数量必须在 1-4 之间，当前 ${questions.size} 个"

        val answerRef = AtomicReference<String>()
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val answers = mutableListOf<String>()
                for ((qIdx, q) in questions.withIndex()) {
                    val question = q["question"] as? String ?: "第 ${qIdx + 1} 个问题"
                    val header = q["header"] as? String ?: "Q${qIdx + 1}"

                    @Suppress("UNCHECKED_CAST")
                    val options = q["options"] as? List<Map<String, String>> ?: emptyList()
                    val multiSelect = q["multiSelect"] as? Boolean ?: false

                    if (options.isEmpty()) {
                        // 无选项时降级为文本输入
                        val text = Messages.showMultilineInputDialog(
                            project, "$header — $question", "请回答", "",
                            Messages.getQuestionIcon(), null
                        )
                        answers.add("$header: ${text?.trim() ?: "(未回答)"}")
                    } else if (multiSelect) {
                        // 多选模式：使用 MultiSelectQuestionDialog
                        val dialog = MultiSelectQuestionDialog(
                            project,
                            header,
                            question,
                            options.map { OptionData(it["label"] ?: "", it["description"] ?: "") }
                        )
                        val selected = dialog.showAndWait()
                        answers.add("$header: ${selected.joinToString(", ")}")
                    } else {
                        // 单选模式：使用 Messages.showDialog
                        val optionLabels = options.map { it["label"] ?: "" }
                        val optionDescs = options.map { it["description"] ?: "" }
                        val choice = Messages.showDialog(
                            project,
                            question,
                            header,
                            optionLabels.toTypedArray(),
                            0,
                            Messages.getQuestionIcon()
                        )
                        val selected =
                            if (choice in optionLabels.indices) optionLabels[choice] else "(未选择)"
                        answers.add("$header: $selected")
                    }
                }
                answerRef.set("用户回答:\n${answers.joinToString("\n")}")
            } catch (e: Exception) {
                answerRef.set("AskUserQuestion 执行失败: ${e.message}")
            }
        }

        return answerRef.get()
    }

    // ── Symbol ──
    private fun symbol(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val operation = ToolInput.string(input, "operation") ?: return "错误: 缺少 operation 参数"
        val validOps = setOf(
            "goToDefinition",
            "goToImplementation",
            "findReferences",
            "hover",
            "documentSymbol",
            "workspaceSymbol",
            "incomingCalls",
            "outgoingCalls"
        )
        if (operation !in validOps) return "错误: 不支持的 operation: $operation（支持: ${
            validOps.joinToString(
                ", "
            )
        }）"

        val filePath = ToolInput.string(input, "filePath") ?: ""
        val line = ToolInput.int(input, "line") ?: 0
        val character = ToolInput.int(input, "character") ?: 0
        val query = ToolInput.string(input, "query")

        // workspaceSymbol 不需要 filePath/line/character
        if (operation != "workspaceSymbol" && (filePath.isEmpty() || line == 0 || character == 0)) {
            return "错误: operation=$operation 需要 filePath、line、character 参数"
        }

        // workspaceSymbol 需要 query 参数
        if (operation == "workspaceSymbol" && query.isNullOrBlank()) {
            return "错误: workspaceSymbol 需要 query 参数"
        }

        val resultRef = AtomicReference("")
        ApplicationManager.getApplication().invokeAndWait {
            resultRef.set(executeSymbolOperation(operation, filePath, line, character, query))
        }
        return resultRef.get()
    }

    /** 执行 Symbol 语义导航操作 */
    private fun executeSymbolOperation(
        operation: String,
        filePath: String,
        line: Int,
        character: Int,
        query: String?
    ): String {
        try {
            val basePath = project.basePath ?: return "错误: 项目路径不可用"

            when (operation) {
                "workspaceSymbol" -> {
                    return executeWorkspaceSymbol(query!!)
                }

                "documentSymbol" -> {
                    val virtualFile = resolveVirtualFile(basePath, filePath)
                        ?: return "错误: 文件 \"$filePath\" 未在 VFS 索引中"
                    return executeDocumentSymbol(virtualFile)
                }

                else -> {
                    // 需要定位到特定位置的符号
                    val virtualFile = resolveVirtualFile(basePath, filePath)
                        ?: return "错误: 文件 \"$filePath\" 未在 VFS 索引中"
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                        ?: return "错误: 无法解析 PSI 文件 \"$filePath\""
                    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                        ?: return "错误: 无法获取文档 \"$filePath\""
                    val offset = document.getLineStartOffset(line - 1) + (character - 1)
                    val element = psiFile.findElementAt(offset)
                        ?: return "未找到位于 $filePath:$line:$character 的符号。"

                    // 找到符号的实际定义元素
                    val targetElement = findTargetElement(element)
                        ?: return "未找到位于 $filePath:$line:$character 的符号。"

                    return when (operation) {
                        "goToDefinition" -> executeGoToDefinition(targetElement)
                        "goToImplementation" -> executeGoToImplementation(targetElement)
                        "findReferences" -> executeFindReferences(targetElement)
                        "hover" -> executeHover(targetElement, filePath)
                        "incomingCalls" -> executeIncomingCalls(targetElement)
                        "outgoingCalls" -> executeOutgoingCalls(targetElement)
                        else -> "错误: 不支持的 operation: $operation"
                    }
                }
            }
        } catch (e: Exception) {
            return "Symbol $operation 执行失败: ${e.message}"
        }
    }

    /** 解析 VFS 文件 */
    private fun resolveVirtualFile(
        basePath: String,
        filePath: String
    ): com.intellij.openapi.vfs.VirtualFile? {
        val file = File(basePath, filePath)
        if (!file.exists()) return null
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file)
    }

    /** 找到实际的目标元素（跳过引用/字面量，定位到定义） */
    private fun findTargetElement(element: PsiElement): PsiElement? {
        var current = element
        // 尝试通过 reference resolve
        val ref = current.reference
        if (ref != null) {
            val resolved = ref.resolve()
            if (resolved != null) return resolved
        }
        // 如果当前元素是标识符，向上找父元素
        while (current is PsiWhiteSpace || current is PsiComment || current.parent != null && current.textLength <= 1) {
            current = current.parent
        }
        return if (current is PsiNameIdentifierOwner) {
            current.nameIdentifier ?: current
        } else current
    }

    /** goToDefinition */
    private fun executeGoToDefinition(element: PsiElement): String {
        val symbolName = getSymbolName(element)
        val navElement = element.navigationElement ?: element
        val containingFile = navElement.containingFile?.virtualFile
            ?: navElement.containingFile?.originalFile?.virtualFile
        if (containingFile == null) return "未找到 \"$symbolName\" 的定义。该符号可能为外部依赖或动态引用。"

        val document = FileDocumentManager.getInstance().getDocument(containingFile)
        val defLine = if (document != null) document.getLineNumber(navElement.textOffset) + 1 else 0

        // 获取定义所在的方法/类上下文
        val parentClass = PsiTreeUtil.getParentOfType(navElement, PsiClass::class.java)
        val parentMethod = PsiTreeUtil.getParentOfType(navElement, PsiMethod::class.java)
        val context = buildString {
            if (parentClass != null) append(parentClass.name ?: "?")
            if (parentMethod != null) append(".${parentMethod.name}()")
        }

        val snippet = getCodeSnippet(navElement.containingFile, navElement.textOffset)
        val projectPath = containingFile.path.removePrefix(project.basePath ?: "")

        return buildString {
            appendLine("📍 $symbolName 定义于 $projectPath:$defLine")
            appendLine("```kotlin")
            appendLine(snippet)
            appendLine("```")
            if (context.isNotEmpty()) appendLine("所在: $context")
        }
    }

    /** goToImplementation */
    private fun executeGoToImplementation(element: PsiElement): String {
        val symbolName = getSymbolName(element)
        val psiClass = if (element is PsiClass) element else PsiTreeUtil.getParentOfType(
            element,
            PsiClass::class.java
        )
        val psiMethod = if (element is PsiMethod) element else PsiTreeUtil.getParentOfType(
            element,
            PsiMethod::class.java
        )

        val implementations = mutableListOf<PsiElement>()
        if (psiMethod != null) {
            // 找重写方法
            if (psiMethod is PsiNamedElement) {
                val searchScope = GlobalSearchScope.projectScope(project)
                // 使用 OverridingMethodsSearch
                try {
                    val overriders =
                        com.intellij.psi.search.searches.OverridingMethodsSearch.search(
                            psiMethod,
                            searchScope,
                            true
                        )
                    overriders.findAll().forEach { implementations.add(it) }
                } catch (_: Exception) {
                    // OverridingMethodsSearch 可能不支持当前语言
                }
            }
        }
        if (psiClass != null && implementations.isEmpty()) {
            val searchScope = GlobalSearchScope.projectScope(project)
            try {
                val inheritors = com.intellij.psi.search.searches.ClassInheritorsSearch.search(
                    psiClass,
                    searchScope,
                    true
                )
                inheritors.findAll().forEach { implementations.add(it) }
            } catch (_: Exception) {
                // ClassInheritorsSearch 可能不支持当前语言
            }
        }

        if (implementations.isEmpty()) {
            return "未找到 \"$symbolName\" 的实现。该符号可能为具体类或非虚方法。"
        }

        val maxResults = 50
        val truncated = implementations.take(maxResults)
        return buildString {
            appendLine("🔍 $symbolName 有 ${implementations.size} 个实现:")
            truncated.forEachIndexed { idx, impl ->
                val implFile = impl.containingFile?.virtualFile
                val implPath = implFile?.path?.removePrefix(project.basePath ?: "") ?: "?"
                val implDocument = if (implFile != null) FileDocumentManager.getInstance()
                    .getDocument(implFile) else null
                val implLine =
                    if (implDocument != null) implDocument.getLineNumber(impl.textOffset) + 1 else 0
                val implName = if (impl is PsiNamedElement) impl.name else "?"
                appendLine("${idx + 1}. $implName 位于 $implPath:$implLine")
            }
            if (implementations.size > maxResults) {
                appendLine("还有 ${implementations.size - maxResults} 处未显示。")
            }
        }
    }

    /** findReferences */
    private fun executeFindReferences(element: PsiElement): String {
        val symbolName = getSymbolName(element)
        val searchElement = element.navigationElement ?: element
        val searchScope = GlobalSearchScope.projectScope(project)
        val refs = ReferencesSearch.search(searchElement, searchScope).findAll()

        if (refs.isEmpty()) {
            return "未找到 \"$symbolName\" 的引用。该符号可能未被使用或为私有引用。"
        }

        val maxResults = 50
        val truncated = refs.take(maxResults)
        return buildString {
            if (refs.size > maxResults) {
                appendLine("🔍 $symbolName 在 ${refs.size} 处被引用，已截断到 $maxResults 条:")
            } else {
                appendLine("🔍 $symbolName 在 ${refs.size} 处被引用:")
            }
            truncated.forEachIndexed { idx, ref ->
                val refFile = ref.element.containingFile?.virtualFile
                val refPath = refFile?.path?.removePrefix(project.basePath ?: "") ?: "?"
                val refDocument = if (refFile != null) FileDocumentManager.getInstance()
                    .getDocument(refFile) else null
                val refLine =
                    if (refDocument != null) refDocument.getLineNumber(ref.element.textOffset) + 1 else 0
                val sourceLine = if (refDocument != null) {
                    val lineStart = refDocument.getLineStartOffset(refLine - 1)
                    val lineEnd = refDocument.getLineEndOffset(refLine - 1)
                    refDocument.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
                        .trim()
                } else ""
                // 获取上下文（所在函数/类）
                val parent = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java)
                val context = parent?.name ?: ""
                appendLine("${idx + 1}. $refPath:$refLine: $sourceLine // $context")
            }
            if (refs.size > maxResults) {
                appendLine("还有 ${refs.size - maxResults} 处未显示。请缩小搜索范围或指定文件。")
            }
        }
    }

    /** hover */
    private fun executeHover(element: PsiElement, filePath: String): String {
        val symbolName = getSymbolName(element)
        val typeInfo = element.reference?.resolve() ?: element
        val typeName = when {
            typeInfo is PsiVariable -> typeInfo.type?.presentableText ?: "?"
            typeInfo is PsiMethod -> typeInfo.returnType?.presentableText ?: "Unit"
            typeInfo is PsiClass -> "class/interface"
            else -> "unknown"
        }

        val navElement = element.navigationElement ?: element
        val containingFile = navElement.containingFile?.virtualFile
        val document = if (containingFile != null) FileDocumentManager.getInstance()
            .getDocument(containingFile) else null
        val defLine = if (document != null) document.getLineNumber(navElement.textOffset) + 1 else 0
        val projectPath = containingFile?.path?.removePrefix(project.basePath ?: "") ?: filePath

        // 尝试获取文档注释
        val docComment = when (navElement) {
            is PsiDocCommentOwner -> navElement.docComment?.text?.trim()?.lines()?.firstOrNull()
                ?.removePrefix("/**")?.removeSuffix("*/")?.trim()

            else -> null
        }

        return buildString {
            appendLine("ℹ️ $symbolName: $typeName")
            if (docComment != null) appendLine(docComment)
            if (navElement is PsiMethod) appendLine(getMethodSignature(navElement))
            appendLine("位置: $projectPath:$defLine")
        }
    }

    /** documentSymbol */
    private fun executeDocumentSymbol(virtualFile: com.intellij.openapi.vfs.VirtualFile): String {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return "错误: 无法解析 PSI 文件"

        val fileName = virtualFile.name
        val classes = PsiTreeUtil.getChildrenOfTypeAsList(psiFile, PsiClass::class.java)
        val functions = PsiTreeUtil.getChildrenOfTypeAsList(psiFile, PsiMethod::class.java)
            .filter { it.containingClass == null } // 只取顶层函数

        val maxResults = 100
        var symbolCount = 0
        val sb = StringBuilder()
        sb.appendLine("📄 $fileName:")

        for (cls in classes) {
            if (symbolCount >= maxResults) break
            sb.appendLine("├── class ${cls.name ?: "?"} (${getPsiLineNum(cls, virtualFile)})")
            symbolCount++
            val methods = cls.methods
            for (m in methods) {
                if (symbolCount >= maxResults) break
                sb.appendLine("│ ├── fun ${m.name}() (${getPsiLineNum(m, virtualFile)})")
                symbolCount++
            }
            val fields = cls.fields
            for (f in fields) {
                if (symbolCount >= maxResults) break
                sb.appendLine(
                    "│ ├── val ${f.name}: ${f.type.presentableText} (${
                        getPsiLineNum(
                            f,
                            virtualFile
                        )
                    })"
                )
                symbolCount++
            }
        }
        for (f in functions) {
            if (symbolCount >= maxResults) break
            sb.appendLine("├── fun ${f.name}() (${getPsiLineNum(f, virtualFile)})")
            symbolCount++
        }

        if (symbolCount == 0) {
            return "📄 $fileName: 无符号（空文件或仅含注释）"
        }

        val totalSymbols =
            classes.size + classes.sumOf { it.methods.size + it.fields.size } + functions.size
        if (sb.toString() != "📄 $fileName:") {
            sb.insert(sb.indexOf("\n") + 1, " ($symbolCount/$totalSymbols 个符号):")
        }

        return sb.toString()
    }

    /** workspaceSymbol */
    private fun executeWorkspaceSymbol(query: String): String {
        val scope = GlobalSearchScope.projectScope(project)
        val maxResults = 20
        val results = mutableListOf<Pair<String, String>>()

        // 按类名搜索
        val classNameResults = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
            .getClassesByName(query, scope)
        for (cls in classNameResults.take(maxResults)) {
            val filePath =
                cls.containingFile?.virtualFile?.path?.removePrefix(project.basePath ?: "") ?: "?"
            val line = getPsiLineNum(cls)
            results.add("class ${cls.name ?: "?"}" to "$filePath:$line")
        }

        // 按文件名搜索
        val fileResults = FilenameIndex.getFilesByName(project, query, scope)
        for (f in fileResults.take(maxResults - results.size)) {
            // VirtualFile 没有直接的 path 属性，只用文件名展示
            results.add("file ${f.name}" to f.name)
        }

        if (results.isEmpty()) {
            return "未找到匹配 \"$query\" 的符号。请检查名称拼写。"
        }

        val total = results.size
        val truncated = results.take(maxResults)
        return buildString {
            if (total > maxResults) {
                appendLine("🔍 搜索 \"$query\" 找到 $total 个匹配，已截断到 $maxResults:")
            } else {
                appendLine("🔍 搜索 \"$query\" 找到 $total 个匹配:")
            }
            truncated.forEachIndexed { idx, (name, location) ->
                appendLine("${idx + 1}. $name — $location")
            }
            if (total > maxResults) {
                appendLine("请使用更精确的符号名缩小范围。")
            }
        }
    }

    /** incomingCalls */
    private fun executeIncomingCalls(element: PsiElement): String {
        val symbolName = getSymbolName(element)
        val searchElement = element.navigationElement ?: element
        val scope = GlobalSearchScope.projectScope(project)
        val refs = ReferencesSearch.search(searchElement, scope).findAll()

        if (refs.isEmpty()) {
            return "📞 $symbolName 为入口函数或未被直接调用"
        }

        val maxResults = 50
        val truncated = refs.take(maxResults)
        return buildString {
            if (refs.size > maxResults) {
                appendLine("📞 $symbolName 被 ${refs.size} 处调用，已截断到 $maxResults:")
            } else {
                appendLine("📞 $symbolName 被 ${refs.size} 处调用:")
            }
            truncated.forEachIndexed { idx, ref ->
                val caller = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java)
                val callerName = caller?.name ?: "?"
                val refFile = ref.element.containingFile?.virtualFile
                val refPath = refFile?.path?.removePrefix(project.basePath ?: "") ?: "?"
                val refDocument = if (refFile != null) FileDocumentManager.getInstance()
                    .getDocument(refFile) else null
                val refLine =
                    if (refDocument != null) refDocument.getLineNumber(ref.element.textOffset) + 1 else 0
                appendLine("${idx + 1}. ${callerName}() — $refPath:$refLine")
            }
            if (refs.size > maxResults) {
                appendLine("还有 ${refs.size - maxResults} 处未显示。")
            }
        }
    }

    /** outgoingCalls */
    private fun executeOutgoingCalls(element: PsiElement): String {
        val symbolName = getSymbolName(element)
        val methodElement = if (element is PsiMethod) element else PsiTreeUtil.getParentOfType(
            element,
            PsiMethod::class.java
        )
            ?: return "错误: 当前符号不是函数"

        val body =
            methodElement.body ?: return "📞 $symbolName 调用了 0 个方法（函数体为空或仅含简单表达式）"

        // 收集 body 中所有引用
        val refElements =
            PsiTreeUtil.collectElementsOfType(body, PsiJavaCodeReferenceElement::class.java)

        val calledMethods = refElements
            .mapNotNull { it.resolve() }
            .filterIsInstance<PsiMethod>()
            .distinct()
            .take(50)

        if (calledMethods.isEmpty()) {
            return "📞 $symbolName 调用了 0 个方法（函数体为空或仅含简单表达式）"
        }

        return buildString {
            appendLine("📞 $symbolName 调用了 ${calledMethods.size} 个方法:")
            calledMethods.forEachIndexed { idx, callee ->
                val calleeFile = callee.containingFile?.virtualFile
                val calleePath = calleeFile?.path?.removePrefix(project.basePath ?: "") ?: "?"
                val calleeLine = getPsiLineNum(callee)
                appendLine("${idx + 1}. ${callee.name}() — $calleePath:$calleeLine")
            }
        }
    }

    /** 获取符号名称 */
    private fun getSymbolName(element: PsiElement): String {
        return when (element) {
            is PsiNamedElement -> element.name ?: "?"
            else -> element.text.take(50)
        }
    }

    /** 获取方法签名 */
    private fun getMethodSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.presentableText} ${p.name}"
        }
        val ret = method.returnType?.presentableText ?: "Unit"
        return "fun ${method.name}($params): $ret"
    }

    /** 获取元素的代码片段（前后各 3 行） */
    private fun getCodeSnippet(psiFile: PsiFile?, offset: Int): String {
        if (psiFile == null) return ""
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return ""
        val line = document.getLineNumber(offset)
        val startLine = maxOf(0, line - 3)
        val endLine = minOf(document.lineCount - 1, line + 3)
        val sb = StringBuilder()
        for (i in startLine..endLine) {
            val lineStart = document.getLineStartOffset(i)
            val lineEnd = document.getLineEndOffset(i)
            sb.appendLine(document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)))
        }
        return sb.toString()
    }

    /** 获取元素在文件中的行号 */
    private fun getPsiLineNum(
        element: PsiElement,
        virtualFile: com.intellij.openapi.vfs.VirtualFile? = null
    ): Int {
        val vf = virtualFile ?: element.containingFile?.virtualFile ?: return 0
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return 0
        return doc.getLineNumber(element.textOffset) + 1
    }

    // ── Plan 管理工具 ──
    private val planExecutor by lazy { PlanExecutor(session) }

    private fun createPlan(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val task = ToolInput.string(input, "task") ?: return "错误: 缺少 task 参数"

        val plansRaw = ToolInput.mapList(input, "plans")
            ?: return "错误: 缺少 plans 参数或格式不正确"
        if (plansRaw.size > 20) return "错误: 计划项不能超过 20 项，当前 ${plansRaw.size} 项"
        val plan = planExecutor.createPlanFromTool(task, plansRaw)
        val sb = StringBuilder()
        sb.appendLine("计划已创建: ${plan.id}")
        sb.appendLine("摘要: ${plan.summary}")
        sb.appendLine("计划项 (${plan.plans.size}):")
        plan.plans.forEachIndexed { idx, step ->
            sb.appendLine("  ${idx + 1}. [${step.id}] ${step.description} (工具: ${step.tool})")
        }
        return sb.toString()
    }

    private fun listPlans(): String {
        val steps = planExecutor.listPlans()
        if (steps.isEmpty()) return "当前没有活跃计划"
        val sb = StringBuilder()
        sb.appendLine("当前计划项 (${steps.size}):")
        steps.forEachIndexed { idx, step ->
            sb.appendLine("  ${idx + 1}. [${step.id}] ${step.description} — ${step.status}")
        }
        return sb.toString()
    }

    private fun removePlan(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val planId = ToolInput.string(input, "planId") ?: return "错误: 缺少 planId 参数"
        return planExecutor.removePlan(planId)
    }

    private fun reorderPlans(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()

        val planIds = ToolInput.stringList(input, "planIds")
            ?: return "错误: 缺少 planIds 参数或格式不正确"
        return planExecutor.reorderPlans(planIds)
    }

    private fun markPlanDone(toolUse: BetaToolUseBlock): String {
        val input = toolUse._input()
        val planId = ToolInput.string(input, "planId") ?: return "错误: 缺少 planId 参数"
        return planExecutor.markPlanDone(planId)
    }

    // ── MCP 工具执行 ──

    /**
     * 将 MCP 工具调用通过 JSON-RPC 转发到对应 MCP Server 执行。
     * 先构建 tools/call 请求，通过 sendRequest 发送到 Server stdin，然后从 responseQueue 等待响应。
     */
    private fun executeMcpTool(
        toolName: String,
        toolUse: BetaToolUseBlock,
        serverId: String
    ): String {
        val mcpManager = com.aiassistant.mcp.McpManager.getInstance(project)
            ?: return "错误: McpManager 不可用"
        val input = toolUse._input()
        val rawName = toolName.substringAfter("$serverId/")

        // 构建 tools/call JSON-RPC 请求
        val gson = com.google.gson.Gson()
        val paramsJson = com.google.gson.JsonObject()
        paramsJson.addProperty("name", rawName)
        if (input != null) {
            paramsJson.add("arguments", gson.toJsonTree(input))
        }

        val requestJson = com.google.gson.JsonObject()
        requestJson.addProperty("jsonrpc", "2.0")
        requestJson.addProperty("id", toolUse.id())
        requestJson.addProperty("method", "tools/call")
        requestJson.add("params", paramsJson)

        val sent = mcpManager.sendRequest(serverId, gson.toJson(requestJson))
        if (!sent) {
            return "MCP 工具 $toolName 执行失败: 无法发送请求到 Server [$serverId]"
        }

        // 等待响应（30s 超时，对齐 mcp.md §四 断连检测超时）
        val deadline =
            System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            val response = mcpManager.pollResponse(serverId)
            if (response != null) {
                return try {
                    val json = com.google.gson.JsonParser.parseString(response).asJsonObject
                    if (json.has("error")) {
                        val errorObj = json.getAsJsonObject("error")
                        val errorMsg = errorObj?.get("message")?.asString ?: "未知错误"
                        "MCP 工具 $toolName 执行失败: $errorMsg"
                    } else if (json.has("result")) {
                        val result = json.get("result")
                        // MCP tools/call 返回 content 数组，提取文本内容
                        val content = result?.asJsonObject?.getAsJsonArray("content")
                        if (content != null) {
                            val texts = mutableListOf<String>()
                            for (item in content) {
                                val obj = item.asJsonObject ?: continue
                                val type = obj.get("type")?.asString ?: continue
                                if (type == "text") {
                                    obj.get("text")?.asString?.let { texts.add(it) }
                                }
                            }
                            truncateMcpResult(texts.joinToString("\n"))
                        } else {
                            "[MCP 工具 $toolName 返回空结果]"
                        }
                    } else {
                        "MCP 工具 $toolName 返回无效响应"
                    }
                } catch (e: Exception) {
                    "MCP 工具 $toolName 响应解析失败: ${e.message}"
                }
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }

        return "MCP 工具 $toolName 执行超时: Server [$serverId] 30s 无响应"
    }

    /**
     * MCP 工具结果最多返回 200 行。MCP 调用通常有副作用，不支持分页重放。
     */
    private fun truncateMcpResult(result: String): String {
        val maxLines = 200
        val lines = result.lines()
        if (lines.size <= maxLines) return result

        return lines.take(maxLines).joinToString("\n") +
                "\n... (共 ${lines.size} 行，已截断到 $maxLines 行)"
    }

    companion object {
        /** 图片结果前缀，用于 Read 工具返回图片时标记 content block（对齐 docs/agent/images.md §三） */
        const val IMAGE_RESULT_PREFIX = "__IMAGE_RESULT__:"
    }
}
