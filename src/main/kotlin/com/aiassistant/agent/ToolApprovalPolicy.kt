package com.aiassistant.agent

import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import java.io.File

object ToolApprovalPolicy {

    /** Shell 危险命令模式（对齐 docs/agent/tools.md §六 审批触发规则：Shell 危险命令） */
    private val dangerousCommandPatterns = listOf(
        Regex("""rm\s+-rf\s+/"""),
        Regex("""git\s+push\s+--force"""),
        Regex("""sudo\s+"""),
        Regex("""chmod\s+777""")
    )

    /** 文件删除命令模式（Bash 含 `rm ` 且目标在项目内）（对齐 docs/agent/tools.md §六 文件删除） */
    private val fileDeletionPattern = Regex("""rm\s+""")

    /** 大范围修改阈值（同一 turn ≥5 个文件）（对齐 docs/agent/tools.md §六 大范围修改） */
    private const val LARGE_SCALE_THRESHOLD = 5

    /** 公共 API 变更引用文件阈值（Edit/Write 修改了方法签名且 ≥3 个文件引用）（对齐 docs/agent/tools.md §六 公共 API 变更） */
    private const val API_CHANGE_REFERENCE_THRESHOLD = 3

    /**
     * 审批上下文：包含本次工具调用的所有信息，供审批策略判断。
     */
    data class ApprovalContext(
        val session: AgentSession,
        val toolName: String,
        val toolUse: BetaToolUseBlock,
        val project: Project
    )

    /**
     * 审批原因枚举，用于 describe 时提供针对性提示。
     */
    enum class ApprovalReason {
        /** 首次工具使用 */
        FIRST_USE,

        /** Shell 危险命令（rm -rf /, git push --force, sudo, chmod 777） */
        DANGEROUS_SHELL_COMMAND,

        /** Bash 工具 dangerous=true（由 LLM 判断） */
        DANGEROUS_FLAG,

        /** 公共 API 变更（Edit/Write 修改方法签名且 ≥3 文件引用） */
        PUBLIC_API_CHANGE,

        /** 大范围修改（同一 turn ≥5 个文件） */
        LARGE_SCALE_MODIFICATION,

        /** 文件删除（Bash 含 rm 且目标在项目内） */
        FILE_DELETION
    }

    /**
     * 判断工具是否需要用户审批确认。
     *
     * 审批优先级（按文档 tools.md §六 审批判定流程）：
     * 1. 危险命令检测（Shell 危险命令 + Bash dangerous=true） → 始终弹窗确认，无视白名单
     * 2. 首次工具使用（每个会话每种工具首次调用） → 首次审批
     * 3. 公共 API 变更（Edit/Write 修改方法签名且 ≥3 个文件引用） → 关键操作确认
     * 4. 大范围修改（同一 turn ≥5 个文件） → 关键操作确认
     * 5. 文件删除（Bash 含 rm 且目标在项目内） → 关键操作确认
     * 6. 审批白名单检查（approvedTools 中已有 → 跳过审批）
     *
     * @return Pair(Boolean, ApprovalReason?) — 是否需要审批 + 审批原因
     */
    fun needsUserApproval(ctx: ApprovalContext): Pair<Boolean, ApprovalReason?> {
        val session = ctx.session
        val toolName = ctx.toolName
        val input = ctx.toolUse._input()
        val mcpServerId = extractMcpServerId(toolName)
        val firstUseKey = mcpServerId?.let { "mcp:$it" } ?: toolName

        // ── 0. 子 Agent 无需审批（对齐 docs/agent/multi-agent.md §三） ──
        // 父 Agent 调用 Agent 工具时已建立信任，子 Agent 所有工具一律自动放行
        if (session.isSubAgent) {
            return false to null
        }

        // ── 1. 危险命令检测（始终确认，无视白名单） ──
        // Bash dangerous=true（由 LLM 判断）
        if (toolName == "Bash" && ToolInput.bool(input, "dangerous") == true) {
            return true to ApprovalReason.DANGEROUS_FLAG
        }

        // Shell 危险命令模式检测
        if (toolName == "Bash") {
            val command = ToolInput.string(input, "command") ?: ""
            if (dangerousCommandPatterns.any { it.containsMatchIn(command) }) {
                return true to ApprovalReason.DANGEROUS_SHELL_COMMAND
            }
        }

        if (mcpServerId != null && mcpServerId in session.approvedMcpServers) {
            return false to null
        }

        // ── 2. 首次工具使用 ──
        if (firstUseKey !in session.firstToolUseDone) {
            return true to ApprovalReason.FIRST_USE
        }

        // ── 3. 公共 API 变更检测 ──
        // Edit/Write 修改方法签名 + ≥3 个其他文件引用
        if (toolName == "Write" || toolName == "Edit") {
            val filePath = ToolInput.string(input, "filePath") ?: ""
            if (isPublicApiChange(ctx, filePath)) {
                return true to ApprovalReason.PUBLIC_API_CHANGE
            }
        }

        // ── 4. 大范围修改检测（同一 turn ≥5 个文件） ──
        if ((toolName == "Write" || toolName == "Edit") && session.filesModifiedThisTurn.size >= LARGE_SCALE_THRESHOLD) {
            return true to ApprovalReason.LARGE_SCALE_MODIFICATION
        }

        // ── 5. 文件删除检测（Bash 含 rm 且目标在项目内） ──
        if (toolName == "Bash") {
            val command = ToolInput.string(input, "command") ?: ""
            if (fileDeletionPattern.containsMatchIn(command) && isTargetingProjectFiles(
                    command,
                    ctx.project
                )
            ) {
                return true to ApprovalReason.FILE_DELETION
            }
        }

        // ── 6. 审批白名单检查 ──
        if (toolName in session.approvedTools) {
            return false to null
        }

        // 不需要审批
        return false to null
    }

    /**
     * 检查是否为公共 API 变更：Edit/Write 修改了方法签名且文件被 ≥3 个其他文件引用。
     */
    private fun isPublicApiChange(ctx: ApprovalContext, filePath: String): Boolean {
        if (filePath.isEmpty()) return false
        val project = ctx.project
        val basePath = project.basePath ?: return false
        val file = File(basePath, filePath)
        if (!file.exists()) return false

        return try {
            val virtualFile =
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file)
                    ?: return false
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return false

            // 获取文件中的所有 PsiMethod
            val methods = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                psiFile,
                com.intellij.psi.PsiMethod::class.java
            )
            if (methods.isEmpty()) return false

            // 检查是否有方法签名包含在本次修改中
            // 对于 Write（全量替换），检查待写入内容中是否仍然包含这些方法签名（表示修改了方法签名）
            // 由于我们无法精确判断旧内容 vs 新内容的差异，采用保守策略：只要文件中定义了方法，就检查引用数
            for (method in methods) {
                if (method.name.isNullOrEmpty()) continue
                val refs = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
                    .findAll()
                // 统计不同文件的引用数（排除自身所在的文件）
                val distinctRefFiles = refs.map { it.element.containingFile?.virtualFile?.path }
                    .filterNotNull()
                    .filter { it != virtualFile.path }
                    .distinct()
                if (distinctRefFiles.size >= API_CHANGE_REFERENCE_THRESHOLD) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            // PSI 操作失败时保守处理：不阻止操作
            false
        }
    }

    /**
     * 检查 Bash 命令的 `rm` 目标是否在项目目录内。
     */
    private fun isTargetingProjectFiles(command: String, project: Project): Boolean {
        val basePath = project.basePath ?: return false
        // 提取 rm 命令的参数（文件路径）
        val rmParts = command.split("""\s+""".toRegex())
        val rmIndex = rmParts.indexOfFirst { it == "rm" }
        if (rmIndex < 0) return false
        // 检查 rm 后面的参数
        for (i in (rmIndex + 1) until rmParts.size) {
            val part = rmParts[i]
            if (part.startsWith("-")) continue  // 跳过选项标志
            // 构造完整路径判断
            val targetPath = if (part.startsWith("/")) part else "$basePath/$part"
            val targetFile = File(targetPath)
            if (targetFile.exists() && targetFile.canonicalPath.startsWith(File(basePath).canonicalPath)) {
                return true
            }
        }
        return false
    }

    /**
     * 将工具名加入当前会话的审批白名单（对齐 docs/agent/tools.md §六 "允许此会话" 按钮行为）。
     */
    fun approveForSession(session: AgentSession, toolName: String) {
        val mcpServerId = extractMcpServerId(toolName)
        if (mcpServerId != null) {
            session.approvedMcpServers.add(mcpServerId)
        } else {
            session.approvedTools.add(toolName)
        }
    }

    /**
     * 标记该工具的首次使用已完成（对齐 docs/agent/tools.md §六 首次工具使用）。
     */
    fun markFirstToolUse(session: AgentSession, toolName: String) {
        session.firstToolUseDone.add(extractMcpServerId(toolName)?.let { "mcp:$it" } ?: toolName)
    }

    /**
     * 审批结果枚举（对齐 docs/agent/tools.md §六 审批判定流程）。
     */
    enum class ApprovalResult {
        /** 拒绝执行 */
        REJECTED,

        /** 仅本次放行，不加入白名单 */
        ALLOW_ONCE,

        /** 本次放行，加入会话白名单持久化 */
        ALLOW_SESSION
    }

    /**
     * 危险命令是否允许"允许此会话"按钮。
     * 危险命令（Shell 危险命令 + dangerous=true）不可加入白名单，只能"允许一次"或"拒绝"。
     * 对齐 docs/agent/tools.md §六 审批提示行为
     */
    fun isDangerousReason(reason: ApprovalReason?): Boolean {
        return reason == ApprovalReason.DANGEROUS_SHELL_COMMAND || reason == ApprovalReason.DANGEROUS_FLAG
    }

    fun extractMcpServerId(toolName: String): String? =
        toolName.substringBefore('/').takeIf { it.length < toolName.length && it.isNotBlank() }

    fun describe(toolName: String, input: Any?, reason: ApprovalReason? = null): String {
        val lines = mutableListOf<String>()
        lines.add("工具: $toolName")
        when (toolName) {
            "Bash" -> {
                ToolInput.string(input, "command")?.let { lines.add("命令: $it") }
                ToolInput.string(input, "workDir")?.let { lines.add("目录: $it") }
            }

            "Write", "Edit" -> {
                ToolInput.string(input, "filePath")?.let { lines.add("文件: $it") }
            }

            "Agent" -> {
                ToolInput.string(input, "prompt")?.let { lines.add("任务: $it") }
            }

            else -> {
                // 其他工具的首次使用审批
            }
        }

        // 根据审批原因添加针对性提示
        when (reason) {
            ApprovalReason.DANGEROUS_SHELL_COMMAND -> {
                lines.add("")
                lines.add("[危险命令] 此命令包含高危操作（rm -rf /、git push --force、sudo、chmod 777），")
                lines.add("可能造成不可逆的破坏，请仔细确认。")
                lines.add("危险命令不可加入会话白名单，每次均需确认。")
            }

            ApprovalReason.DANGEROUS_FLAG -> {
                lines.add("")
                lines.add("[危险命令] LLM 标记此命令为危险操作（dangerous=true），")
                lines.add("可能造成不可逆的破坏，请仔细确认。")
                lines.add("危险命令不可加入会话白名单，每次均需确认。")
            }

            ApprovalReason.FIRST_USE -> {
                lines.add("")
                lines.add("这是本会话中首次调用 ${toolName} 工具。")
            }

            ApprovalReason.PUBLIC_API_CHANGE -> {
                lines.add("")
                lines.add("[公共 API 变更] 此修改涉及公共方法签名，且被 ≥3 个文件引用。")
                lines.add("修改可能影响多个调用者，请确认变更合理性。")
            }

            ApprovalReason.LARGE_SCALE_MODIFICATION -> {
                lines.add("")
                lines.add("[大范围修改] 当前 turn 已修改 ≥5 个文件。")
                lines.add("大范围修改有较高风险，请确认是否需要继续。")
            }

            ApprovalReason.FILE_DELETION -> {
                lines.add("")
                lines.add("[文件删除] 此命令将删除项目内的文件。")
                lines.add("删除操作不可撤销，请仔细确认。")
            }

            null -> {
                // 无特殊原因
            }
        }

        lines.add("")
        lines.add("允许 Code Assistant 执行这个操作吗？")
        return lines.joinToString("\n")
    }
}
