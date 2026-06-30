package com.aiassistant.actions

import com.aiassistant.AiAssistantBundle
import com.aiassistant.AppLogger
import com.aiassistant.AppSettingsService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.EditorTextField
import com.aiassistant.ui.chat.DiffKind
import com.aiassistant.ui.chat.DiffLine
import com.aiassistant.ui.chat.SimpleDiff
import com.google.gson.Gson
import java.awt.Component
import java.awt.Container
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

class GenerateCommitAction : AnAction() {

    // @Volatile：update() 在 EDT 读，后台线程写（正常路径最后一步 + catch 分支），需要可见性保证
    @Volatile private var isGenerating = false
    @Volatile private var lastClickTime = 0L

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasCommitControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null

        // 可见：只要有 commit 对话框就显示；可用的前提是用户已勾选文件
        e.presentation.isVisible = hasCommitControl

        val hasSelectedChanges = if (hasCommitControl && project != null) {
            val controlComponent = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? Component
            controlComponent != null && getCheckedChanges(controlComponent, project).isNotEmpty()
        } else {
            false
        }
        e.presentation.isEnabled = hasSelectedChanges && !isGenerating
        if (isGenerating) e.presentation.text = AiAssistantBundle.message("action.generate.progress")
        else e.presentation.text = AiAssistantBundle.message("action.generate.commit")
    }

    override fun actionPerformed(e: AnActionEvent) {
        // 文档 §五：生成中重复点击由 1.5s 防抖 + isGenerating 禁用按钮双重保护
        // isGenerating 通过 update() 禁用按钮，防抖在 actionPerformed 中拦截，二者协同确保不重复触发
        if (isGenerating) return
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 1500) return
        lastClickTime = now

        val project = e.project ?: return
        val commitMessageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val apiKey = try {
            AppSettingsService.getInstance().getApiKey()
        } catch (ex: Exception) { null }

        if (apiKey.isNullOrBlank()) {
            Messages.showWarningDialog(project, AiAssistantBundle.message("action.generate.nokey"), "Code Assistant")
            return
        }

        // EDT 上取 editor，避免后台线程操作 Swing 组件
        val controlComponent = commitMessageControl as? Component ?: run {
            Messages.showWarningDialog(project, AiAssistantBundle.message("action.generate.setfailed"), "Code Assistant")
            return
        }
        // 通过反射从 CheckinProjectPanel 拿用户勾选的文件（该类在 vcs-impl，不可编译期引用）
        val selectedChanges = getCheckedChanges(controlComponent, project)
        if (selectedChanges.isEmpty()) {
            Messages.showWarningDialog(
                project,
                AiAssistantBundle.message("action.generate.nochanges"),
                "Code Assistant"
            )
            return
        }
        val editor = findEditorField(controlComponent) ?: run {
            Messages.showWarningDialog(project, AiAssistantBundle.message("action.generate.setfailed"), "Code Assistant")
            return
        }

        isGenerating = true

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, AiAssistantBundle.message("action.generate.progress"), false) {
            override fun run(indicator: ProgressIndicator) {
                val app = ApplicationManager.getApplication()
                try {
                    val isMerge = isMergeCommit(project)
                    // 文档 §九 Merge Commit：diff 内容仍然正常采集（包含冲突解决后的变更）
                    val diffText = buildDiff(project, selectedChanges)
                    // 文档 §九 空 diff（无变更）：buildDiff() 可能因为所有文件被二进制过滤等原因返回空字符串，
                    // 此时不发起 API 调用
                    if (diffText.isBlank()) {
                        showNotification(project, AiAssistantBundle.message("action.generate.nochanges"))
                        isGenerating = false
                        return
                    }
                    // 文档 §九 Merge Commit：合并提交不强行要求 Conventional Commits 格式，
                    // 始终使用专用 merge prompt 模板（不受用户自定义 prompt 影响）
                    val mergeSystemPrompt = if (isMerge) {
                        val isChinese = Locale.getDefault().language.startsWith("zh")
                        if (isChinese) {
                            AppSettingsService.DEFAULT_MERGE_COMMIT_PROMPT_ZH
                        } else {
                            AppSettingsService.DEFAULT_MERGE_COMMIT_PROMPT
                        }
                    } else null
                    val (systemPrompt, userPrompt) = if (isMerge) {
                        // 文档 §九 Merge Commit：使用专门的 merge prompt 模板，diff 内容正常采集
                        Pair(mergeSystemPrompt!!, diffText)
                    } else {
                        buildPrompt(diffText)
                    }
                    // 文档 §一 流程图：清空编辑器前先检查取消状态，避免清空后用户取消导致数据丢失
                    indicator.checkCanceled()
                    app.invokeAndWait {
                        app.runWriteAction { editor.document?.setText("") }
                    }
                    val message =
                        callDeepSeek(apiKey, systemPrompt, userPrompt, indicator) { delta ->
                        app.invokeLater {
                            app.runWriteAction {
                                try {
                                    editor.document?.insertString(editor.document.textLength, delta)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    if (message == null) {
                        showNotification(project, AiAssistantBundle.message("action.generate.failed"))
                        isGenerating = false
                        return
                    }
                    // ponytail: 流式完成后兜底覆盖，防止 invokeLater 异步插入丢失内容
                    app.invokeAndWait {
                        app.runWriteAction { editor.document?.setText(message) }
                    }
                    // 文档 §一 流程图：isGenerating = false 放在 Task.Backgroundable 的最后一步
                    isGenerating = false
                } catch (ex: ProcessCanceledException) {
                    // 任务被取消（如 IDE 关闭），必须在 catch 中重置标志
                    isGenerating = false
                    throw ex
                } catch (ex: Exception) {
                    AppLogger.requestFailed(0, ex.message ?: "Connection failed")
                    showNotification(project, ex.message ?: "Unknown error")
                    isGenerating = false
                }
            }
        })
    }

    /**
     * 只对用户在 IDEA 提交对话框中勾选的文件生成 diff。
     * 优先尝试 git diff（更准确的 unified diff），失败时降级到 ContentRevision 方案。
     *
     * 文档 §一 三个降级分支:
     * 1. 首选: git diff --staged -- <勾选文件>
     * 2. 降级: git diff -- <勾选文件>（staged 无内容时降级）
     * 3. 最终降级: SimpleDiff（Myers LCS 算法）+ ContentRevision
     */
    private fun buildDiff(project: Project, changes: List<Change>): String {
        if (changes.isEmpty()) return ""

        val basePath = project.basePath
        // 提取勾选文件的相对路径，同时过滤二进制文件（文档 §三限制表：检测 null 字符后跳过）
        val nonBinaryChanges = changes.filter { change ->
            val beforeContent = readContent(change.beforeRevision)
            val afterContent = readContent(change.afterRevision)
            // readContent 对 null 字符返回 null，只要不是两边都失败就保留
            beforeContent != null || afterContent != null
        }
        val relativePaths = nonBinaryChanges.mapNotNull { change ->
            val vf = change.afterRevision?.file ?: change.beforeRevision?.file ?: return@mapNotNull null
            val abs = vf.path
            if (basePath != null && abs.startsWith(basePath)) abs.removePrefix(basePath).removePrefix("/") else abs
        }

        // 文档 §三：先检查 git 命令是否可用（git --version），不可用则直接降级到 SimpleDiff
        val gitAvailable = basePath != null && runGitCommand(basePath, "--version").isNotBlank()

        if (!gitAvailable) {
            // 文档 §三：git 命令不可用，降级到 SimpleDiff（Myers LCS 算法）+ ContentRevision
            return buildSimpleDiffForChanges(changes)
        }

        if (basePath != null && relativePaths.isNotEmpty()) {
            // 文档 §三限制表：路径列表最多 50 个文件，防止 git 命令行参数溢出
            val pathArgs = relativePaths.take(50).toTypedArray()

            // 文档 §一 降级分支1（首选）: git diff --staged -- <勾选文件>
            val stagedDiff = runGitCommand(basePath, "diff", "--staged", "--", *pathArgs)
            if (stagedDiff.isNotBlank()) {
                val fileList = relativePaths.joinToString("\n") { "  - $it" }
                val stat = runGitCommand(basePath, "diff", "--staged", "--stat", "--", *pathArgs)
                val recentCommits = runGitCommand(basePath, "log", "--oneline", "-5")
                return buildGitDiffResult(fileList, stat, recentCommits, stagedDiff)
            }

            // 文档 §一 降级分支2: git diff -- <勾选文件>（staged 无内容时降级）
            val unstagedDiff = runGitCommand(basePath, "diff", "--", *pathArgs)
            if (unstagedDiff.isNotBlank()) {
                val fileList = relativePaths.joinToString("\n") { "  - $it" }
                val stat = runGitCommand(basePath, "diff", "--stat", "--", *pathArgs)
                val recentCommits = runGitCommand(basePath, "log", "--oneline", "-5")
                return buildGitDiffResult(fileList, stat, recentCommits, unstagedDiff)
            }
        }

        // 文档 §一 降级分支3（最终降级）: SimpleDiff（Myers LCS 算法）+ ContentRevision
        // 文档 §七：SimpleDiff 仅做行级比较，不包含 --stat 摘要和 git log 风格参考
        return buildSimpleDiffForChanges(changes)
    }

    /**
     * 基于 git diff 输出构建完整的 diff 文本，包含文件列表、--stat 摘要和 git log 风格参考。
     * 同时应用文档 §九 超大 Diff 五级截断策略。
     */
    private fun buildGitDiffResult(
        fileList: String,
        stat: String,
        recentCommits: String,
        diffContent: String
    ): String {
        // 文档 §九 超大 Diff 策略：先检查完整文本是否在 50000 字符上限内
        val prefix = buildString {
            append("Changed files:\n$fileList\n\n")
            if (stat.isNotBlank()) append("Changes summary:\n$stat\n\n")
        }
        val suffix =
            if (recentCommits.isNotBlank()) "\n\nRecent commits for style reference:\n$recentCommits" else ""
        val fullText = prefix + "Git diff:\n" + diffContent + suffix

        if (fullText.length <= 50000) return fullText

        // 超出 50000 字符：优先保留 --stat，按变更行数降序取前30文件，
        // 每文件截断到前500行，尾部标注
        val truncatedDiff = truncateGitDiff(diffContent, stat)
        val truncatedText = prefix + "Git diff:\n" + truncatedDiff + suffix

        if (truncatedText.length <= 50000) return truncatedText

        // 截断后仍超 50000 字符 → 仅发送 --stat 摘要
        return buildString {
            append("Changed files:\n$fileList\n\n")
            if (stat.isNotBlank()) append("Changes summary:\n$stat\n\n")
            append("(diff 内容过大（截断后 ${truncatedText.length} 字符仍超 50,000 上限），此条提交信息生成仅基于变更摘要)")
            if (recentCommits.isNotBlank()) append("\n\nRecent commits for style reference:\n$recentCommits")
        }
    }

    private fun runGitCommand(basePath: String, vararg args: String): String {
        val process = try {
            ProcessBuilder("git", "-C", basePath, *args)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) { return "" }
        return try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(10, TimeUnit.SECONDS)
            output
        } catch (_: Exception) {
            ""
        } finally {
            try { process.destroyForcibly() } catch (_: Exception) {}
        }
    }

    /**
     * 对 git diff 原始输出按文件拆分并应用五级截断策略（文档 §九）：
     * 1. 将 diff 按文件拆分（以 diff --git 行作为分隔）
     * 2. 每文件统计变更行数（+/-开头），按变更行数降序排序
     * 3. 取前 30 个文件的 diff
     * 4. 每文件截断到前 500 行
     * 5. 如果总文件数超过 30，添加尾部标注
     */
    private fun truncateGitDiff(diffContent: String, stat: String): String {
        // 按 git diff 的文件头分隔（diff --git a/... b/...）
        val fileDiffs = diffContent.split(Regex("(?=diff --git )")).filter { it.isNotBlank() }

        if (fileDiffs.isEmpty()) return ""

        // 解析每个文件的 diff 块，统计变更行数
        data class GitFileDiff(val block: String, val changedLineCount: Int)

        val entries = fileDiffs.map { block ->
            val lines = block.lines()
            val changedCount = lines.count { it.startsWith("+") && !it.startsWith("+++") }
                .plus(lines.count { it.startsWith("-") && !it.startsWith("---") })

            // 截断每文件到前 500 行（文档 §九 第三级）
            val displayBlock = if (lines.size > 500) {
                lines.take(500)
                    .joinToString("\n") + "\n... (${lines.size - 500} more lines omitted)"
            } else {
                block
            }

            GitFileDiff(displayBlock, changedCount)
        }

        // 按变更行数降序排序，取前 30 个文件（文档 §九 第二级）
        val topEntries = entries.sortedByDescending { it.changedLineCount }.take(30)

        val truncatedBody = topEntries.joinToString("\n") { "${it.block}\n" }

        // 第四级：尾部标注——使用 entries.size（实际 diff 文件数），而非外部传入的总文件数
        // 因为 git diff 命令行用了 take(50) 限制路径数，且二进制文件会被 git diff 跳过
        if (entries.size > 30) {
            return truncatedBody + "\n... (共 ${entries.size} 个文件变更，仅展示前 30 个文件的 diff)\n"
        }
        return truncatedBody
    }

    /**
     * 使用 [SimpleDiff]（Myers LCS 算法）+ IntelliJ ContentRevision 生成 unified diff。
     * 只处理传入的 [changes]，即用户在提交对话框中勾选的文件。
     * 五级截断策略（文档 §九）：按变更行数降序取前30文件，每文件截断到前500行，尾部标注，超50000字符则仅发送文件列表。
     * 文档 §七：SimpleDiff 仅做行级比较，不包含 --stat 摘要和 git log 风格参考。
     */
    private fun buildSimpleDiffForChanges(changes: List<Change>): String {
        if (changes.isEmpty()) return ""

        // 收集每个文件的 diff 片段及其变更行数（ADD + DEL 行数），用于后续排序
        data class FileDiffEntry(val path: String, val diffBlock: String, val changedLineCount: Int)

        val allEntries = mutableListOf<FileDiffEntry>()

        for (change in changes) {
            val beforeRev = change.beforeRevision
            val afterRev = change.afterRevision
            val path = (afterRev?.file?.path ?: beforeRev?.file?.path) ?: continue

            val before = readContent(beforeRev)
            val after = readContent(afterRev)

            val block = buildString {
                when {
                    before == null && after != null -> {
                        // 新文件：截断到前 500 行（文档 §九 要求）
                        val lines = after.lines()
                        append("--- /dev/null\n")
                        append("+++ b/$path\n")
                        append("@@ -0,0 +1,${lines.size} @@\n")
                        val displayLines = lines.take(500)
                        displayLines.forEach { append("+$it\n") }
                        if (lines.size > 500) {
                            append("... (${lines.size - 500} more lines omitted)\n")
                        }
                    }
                    after == null && before != null -> {
                        // 删除文件：截断到前 500 行
                        val lines = before.lines()
                        append("--- a/$path\n")
                        append("+++ /dev/null\n")
                        append("@@ -1,${lines.size} +0,0 @@\n")
                        val displayLines = lines.take(500)
                        displayLines.forEach { append("-$it\n") }
                        if (lines.size > 500) {
                            append("... (${lines.size - 500} more lines omitted)\n")
                        }
                    }
                    before != null && after != null -> {
                        // 修改文件：截断到前 500 行
                        val diffLines = SimpleDiff.diff(before.lines(), after.lines())
                        if (diffLines.isEmpty()) return@buildString

                        append("--- a/$path\n")
                        append("+++ b/$path\n")
                        val displayLines = diffLines.take(500)
                        for (line in displayLines) {
                            when (line.kind) {
                                DiffKind.CTX -> append(" ${line.content}\n")
                                DiffKind.DEL -> append("-${line.content}\n")
                                DiffKind.ADD -> append("+${line.content}\n")
                            }
                        }
                        if (diffLines.size > 500) {
                            append("... (${diffLines.size - 500} more diff lines omitted)\n")
                        }
                    }
                }
            }

            if (block.isNotBlank()) {
                // 统计变更行数（ADD + DEL）
                val changedCount = block.lines().count { it.startsWith("+") || it.startsWith("-") }
                allEntries.add(FileDiffEntry(path, block, changedCount))
            }
        }

        // 第二级：按变更行数降序排序，取前 30 个文件
        val topEntries = allEntries.sortedByDescending { it.changedLineCount }.take(30)

        val totalFiles = allEntries.size
        val fileList = allEntries.joinToString("\n") { "  - ${it.path}" }

        val diffBody = topEntries.joinToString("\n") { "${it.diffBlock}\n" }

        // 第四级：尾部标注
        val annotation = if (allEntries.size > 30) {
            "\n... (共 ${totalFiles} 个文件变更，仅展示前 30 个文件的 diff)\n"
        } else ""

        val fullText = buildString {
            append("Changed files:\n")
            append(fileList)
            append("\n\nGit diff:\n")
            append(diffBody)
            append(annotation)
        }

        // 第五级：截断后仍超 50000 字符，仅发送 --stat 风格摘要，不发送逐文件 diff
        // 文档 §九 第 5 级：仅发送 --stat 摘要，不发送逐文件 diff
        // SimpleDiff 降级路径虽无 git --stat，但用已统计的变更数据构建等价的统计摘要
        if (fullText.length <= 50000) return fullText

        return buildString {
            append("Changed files:\n")
            for (entry in allEntries.sortedByDescending { it.changedLineCount }) {
                append("  - ${entry.path} (+${entry.changedLineCount} lines)\n")
            }
            append("\n(diff 内容过大（总字符数 ${fullText.length}），此条提交信息生成仅基于变更摘要)")
        }
    }

    private fun readContent(revision: com.intellij.openapi.vcs.changes.ContentRevision?): String? {
        if (revision == null) return null
        return try {
            val content = revision.content
            if (content != null && content.contains(' ')) null else content as? String
        } catch (_: Exception) { null }
    }

    /**
     * 构建 prompt（不含 merge 逻辑，merge 场景由调用方处理），返回 (systemPrompt, userPrompt)。
     * systemPrompt 包含角色指令和行为约束，userPrompt 仅包含 diff 数据，
     * 分别放入 API 的 system/user role，提高模型指令遵循度。
     *
     * 流程（文档 §一）：
     * 1. 读取用户自定义 prompt（{diff} 占位符）
     * 2. 未自定义 → 根据系统语言选择中/英文默认 prompt
     * 3. 拆分: systemPrompt（指令）+ userPrompt（diff 数据）
     *
     * @param diffText diff 文本内容
     */
    private fun buildPrompt(diffText: String): Pair<String, String> {
        val customPrompt = try {
            AppSettingsService.getInstance().getPrompt()
        } catch (_: Exception) { null }

        // 文档 §一：读取用户自定义 prompt，未自定义 → 根据系统语言选择中/英文默认 prompt
        val template = if (!customPrompt.isNullOrBlank()) {
            customPrompt
        } else {
            val isChinese = Locale.getDefault().language.startsWith("zh")
            if (isChinese) {
                AppSettingsService.DEFAULT_COMMIT_PROMPT_ZH
            } else {
                AppSettingsService.DEFAULT_COMMIT_PROMPT
            }
        }

        // 分离系统指令和用户数据（diff 内容本身）
        // 文档 §四 消息角色分离：system = "{diff} 前后的指令文本"，user = "diff 内容本身"
        // {diff} 之前的文本和之后的文本都是指令部分，拼接后作为 system prompt
        val beforeDiff = template.substringBefore("{diff}")
        val afterDiff = template.substringAfter("{diff}")
        val systemPrompt = (beforeDiff + afterDiff).trim()
        // 防御：如果分离后 systemPrompt 为空（如用户仅输入 "{diff}" 或 {diff} 在开头），回退到默认系统指令
        val effectiveSystemPrompt = systemPrompt.ifBlank {
            val isChinese = Locale.getDefault().language.startsWith("zh")
            if (isChinese) {
                AppSettingsService.DEFAULT_COMMIT_PROMPT_ZH
            } else {
                AppSettingsService.DEFAULT_COMMIT_PROMPT
            }
        }
        return Pair(effectiveSystemPrompt, diffText)
    }

    /**
     * 检测当前是否为 merge commit 场景（文档 §九：Merge Commit）。
     * 检测条件：
     * 1. .git/MERGE_HEAD 文件存在 — 表示正在执行 git merge
     * 2. 或 git diff --staged 包含 merge 特征 — 冲突标记或 combine diff 格式
     */
    private fun isMergeCommit(project: Project): Boolean {
        val basePath = project.basePath ?: return false

        // 条件1：检查 .git/MERGE_HEAD 是否存在（git merge 进行中）
        val mergeHeadFile = java.io.File(basePath, ".git/MERGE_HEAD")
        if (mergeHeadFile.exists()) return true

        // 条件2：检查 staged diff 是否包含 merge 特征
        try {
            val stagedDiff = runGitCommand(basePath, "diff", "--staged")
            if (stagedDiff.isNotBlank()) {
                // 冲突解决标记：<<<<<<< / ======= / >>>>>>>
                if (stagedDiff.contains("<<<<<<<") ||
                    stagedDiff.contains("=======") ||
                    stagedDiff.contains(">>>>>>>")
                ) {
                    return true
                }
                // combine diff 格式（多父 commit diff）：git diff --cc 或 "index .." 多父模式
                if (stagedDiff.contains("diff --cc ") || stagedDiff.contains("index ..")) {
                    return true
                }
            }
        } catch (_: Exception) {
            // 检查失败，保守假设非 merge
        }
        return false
    }

    private fun callDeepSeek(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        indicator: ProgressIndicator? = null,
        onDelta: ((String) -> Unit)? = null
    ): String? {
        val model = try {
            AppSettingsService.getInstance().getModel()
        } catch (_: Exception) {
            null
        } ?: "deepseek-v4-pro"
        val requestBody = Gson().toJson(mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "stream" to true
        ))

        val uri = URI.create("https://api.deepseek.com/v1/chat/completions")
        val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val result = StringBuilder()
        try {
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val statusCode = conn.responseCode

            if (statusCode != HttpURLConnection.HTTP_OK) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                AppLogger.requestFailed(statusCode, errorBody)
                return null
            }

            conn.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // 定期检查取消状态，确保 isGenerating 能及时被 finally 重置
                    indicator?.checkCanceled()
                    val l = line ?: continue
                    if (!l.startsWith("data: ")) continue
                    val data = l.removePrefix("data: ")
                    if (data == "[DONE]") break
                    try {
                        val chunk = Gson().fromJson(data, Map::class.java) as? Map<*, *> ?: continue
                        val choices = chunk["choices"] as? List<*> ?: continue
                        val choice = choices.firstOrNull() as? Map<*, *> ?: continue
                        val delta = choice["delta"] as? Map<*, *> ?: continue
                        val content = delta["content"] as? String ?: continue
                        result.append(content)
                        onDelta?.invoke(content)
                    } catch (_: Exception) { /* skip malformed SSE line */ }
                }
            }
            if (result.isEmpty()) {
                AppLogger.requestFailed(200, "stream result empty — no content deltas received")
                return null
            }
            return result.toString()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 通过反射从组件树中查找 CheckinProjectPanel 并拿取用户勾选的文件。
     * CheckinProjectPanel 在 vcs-impl 模块，不可编译期引用，只能运行时反射。
     * 反射成功但 getSelectedChanges() 返回空（用户未勾选文件）→ 返回空列表（文档 §九）。
     * 若反射失败（新版 Commit UI 等），降级使用 defaultChangeList.changes。
     */
    private fun getCheckedChanges(controlComponent: Component, project: Project): List<Change> {
        var parent: Container? = controlComponent.parent
        while (parent != null) {
            // 旧版 Commit UI：CheckinProjectPanel.getSelectedChanges()
            if (parent.javaClass.name.contains("CheckinProjectPanel")) {
                try {
                    val method = parent.javaClass.getMethod("getSelectedChanges")
                    @Suppress("UNCHECKED_CAST")
                    val changes =
                        (method.invoke(parent) as? Collection<*>)?.filterIsInstance<Change>()
                            ?: emptyList()
                    if (changes.isNotEmpty()) return changes
                    // 文档 §九：反射成功但用户未勾选任何文件（无 Staged Changes 且无 Unstaged Changes），
                    // getCheckedChanges() 应返回空列表，不应降级到 defaultChangeList.changes，
                    // 否则会导致按钮误显示并获取所有未提交变更而非用户勾选的变更
                    return emptyList()
                } catch (_: Exception) { /* reflection failed, try next parent */
                }
            }
            // 新版 Commit UI：ChangesBrowser / CommitChangeListPanel
            if (parent.javaClass.name.contains("ChangesBrowser") ||
                parent.javaClass.name.contains("CommitChangeListPanel")
            ) {
                try {
                    for (methodName in listOf(
                        "getSelectedChanges",
                        "getIncludedChanges",
                        "getDisplayedChanges"
                    )) {
                        try {
                            val method = parent.javaClass.getMethod(methodName)

                            @Suppress("UNCHECKED_CAST")
                            val changes =
                                (method.invoke(parent) as? Collection<*>)?.filterIsInstance<Change>()
                            if (!changes.isNullOrEmpty()) return changes
                        } catch (_: NoSuchMethodException) {
                            continue
                        }
                    }
                } catch (_: Exception) { /* reflection failed, try next parent */
                }
            }
            parent = parent.parent
        }
        // 降级：所有反射路径失败（未知 Commit UI），使用 default changelist
        AppLogger.info("getCheckedChanges: no known commit panel found, falling back to defaultChangeList")
        return ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
    }

    private fun findEditorField(component: Component): EditorTextField? {
        if (component is EditorTextField) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findEditorField(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun showNotification(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "Code Assistant")
        }
    }
}
