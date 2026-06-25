package com.aiassistant.actions

import com.aiassistant.AiAssistantBundle
import com.aiassistant.AppLogger
import com.aiassistant.AppSettingsService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.EditorTextField
import com.aiassistant.ui.DiffKind
import com.aiassistant.ui.DiffLine
import com.aiassistant.ui.SimpleDiff
import com.google.gson.Gson
import java.awt.Component
import java.awt.Container
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

class GenerateCommitAction : AnAction() {

    // @Volatile：update() 在 EDT 读，finally 在后台线程写，需要可见性保证
    @Volatile private var isGenerating = false
    @Volatile private var lastClickTime = 0L

    override fun update(e: AnActionEvent) {
        val project = e.project
        val changes = project?.let {
            ChangeListManager.getInstance(it).defaultChangeList.changes
        } ?: emptyList()
        val hasCommitControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null

        e.presentation.isVisible = changes.isNotEmpty()
        e.presentation.isEnabled = hasCommitControl && !isGenerating
        if (isGenerating) e.presentation.text = AiAssistantBundle.message("action.generate.progress")
        else e.presentation.text = AiAssistantBundle.message("action.generate.commit")
    }

    override fun actionPerformed(e: AnActionEvent) {
        // 防抖：1.5 秒内不重复触发
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
            Messages.showWarningDialog(project, "未检测到待提交的文件变更", "Code Assistant")
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
                    val diffText = buildDiff(project, selectedChanges)
                    if (diffText.isBlank()) {
                        showNotification(project, AiAssistantBundle.message("action.generate.nochanges"))
                        return
                    }
                    val (systemPrompt, userPrompt) = buildPrompt(diffText)
                    // 同步清空编辑器，确保在流式输出前完成
                    app.invokeAndWait {
                        app.runWriteAction { editor.document?.setText("") }
                    }
                    val message = callDeepSeek(apiKey, systemPrompt, userPrompt) { delta ->
                        app.invokeLater {
                            try {
                                app.runWriteAction {
                                    editor.document?.insertString(editor.document.textLength, delta)
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    if (message == null) {
                        showNotification(project, AiAssistantBundle.message("action.generate.failed"))
                        return
                    }
                    // ponytail: 流式完成后兜底覆盖，防止 invokeLater 异步插入丢失内容
                    app.invokeAndWait {
                        app.runWriteAction { editor.document?.setText(message) }
                    }
                } catch (ex: Exception) {
                    showNotification(project, ex.message ?: "Unknown error")
                } finally {
                    isGenerating = false
                }
            }
        })
    }

    /**
     * 只对用户在 IDEA 提交对话框中勾选的文件生成 diff。
     * 优先尝试 git diff（更准确的 unified diff），失败时降级到 ContentRevision 方案。
     */
    private fun buildDiff(project: Project, changes: List<Change>): String {
        if (changes.isEmpty()) return ""

        val basePath = project.basePath
        // 提取勾选文件的相对路径
        val relativePaths = changes.mapNotNull { change ->
            val vf = change.afterRevision?.file ?: change.beforeRevision?.file ?: return@mapNotNull null
            val abs = vf.path
            if (basePath != null && abs.startsWith(basePath)) abs.removePrefix(basePath).removePrefix("/") else abs
        }

        if (basePath != null && relativePaths.isNotEmpty()) {
            // 尝试 git diff --staged，只针对勾选文件
            val pathArgs = relativePaths.toTypedArray()
            val stagedDiff = runGitCommand(basePath, "diff", "--staged", "--", *pathArgs)
            val unstagedDiff = if (stagedDiff.isBlank()) {
                runGitCommand(basePath, "diff", "--", *pathArgs)
            } else ""

            val diffContent = when {
                stagedDiff.isNotBlank() -> stagedDiff
                unstagedDiff.isNotBlank() -> unstagedDiff
                else -> ""
            }

            if (diffContent.isNotBlank()) {
                val fileList = relativePaths.joinToString("\n") { "  - $it" }
                val stat = if (stagedDiff.isNotBlank()) {
                    runGitCommand(basePath, "diff", "--staged", "--stat", "--", *pathArgs)
                } else {
                    runGitCommand(basePath, "diff", "--stat", "--", *pathArgs)
                }
                val recentCommits = runGitCommand(basePath, "log", "--oneline", "-5")

                return buildString {
                    append("Changed files:\n$fileList\n\n")
                    if (stat.isNotBlank()) append("Changes summary:\n$stat\n\n")
                    append("Git diff:\n$diffContent")
                    if (recentCommits.isNotBlank()) append("\n\nRecent commits for style reference:\n$recentCommits")
                }.take(50000)
            }
        }

        // git diff 无结果（文件未 staged，或 ContentRevision 才能访问），降级到 IntelliJ API
        return buildSimpleDiffForChanges(changes)
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
     * 使用 [SimpleDiff]（Myers LCS 算法）+ IntelliJ ContentRevision 生成 unified diff。
     * 只处理传入的 [changes]，即用户在提交对话框中勾选的文件。
     */
    private fun buildSimpleDiffForChanges(changes: List<Change>): String {
        if (changes.isEmpty()) return ""

        return buildString {
            append("Changed files:\n")
            for (change in changes.take(50)) {
                val path = (change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path) ?: continue
                append("  - $path\n")
            }
            append("\n")

            for (change in changes.take(30)) {
                val beforeRev = change.beforeRevision
                val afterRev = change.afterRevision
                val path = (afterRev?.file?.path ?: beforeRev?.file?.path) ?: continue

                val before = readContent(beforeRev)
                val after = readContent(afterRev)

                when {
                    before == null && after != null -> {
                        // 新文件
                        val lines = after.lines()
                        append("--- /dev/null\n")
                        append("+++ b/$path\n")
                        append("@@ -0,0 +1,${lines.size} @@\n")
                        lines.take(100).forEach { append("+$it\n") }
                        if (lines.size > 100) {
                            append("... (${lines.size - 100} more lines omitted)\n")
                        }
                    }
                    after == null && before != null -> {
                        // 删除文件
                        val lines = before.lines()
                        append("--- a/$path\n")
                        append("+++ /dev/null\n")
                        append("@@ -1,${lines.size} +0,0 @@\n")
                        lines.take(100).forEach { append("-$it\n") }
                        if (lines.size > 100) {
                            append("... (${lines.size - 100} more lines omitted)\n")
                        }
                    }
                    before != null && after != null -> {
                        // 修改文件：平面 diff 输出（git CLI 覆盖绝大多数场景，此处为降级路径）
                        val diffLines = SimpleDiff.diff(before, after)
                        if (diffLines.isEmpty()) continue

                        append("--- a/$path\n")
                        append("+++ b/$path\n")
                        for (line in diffLines) {
                            when (line.kind) {
                                DiffKind.CTX -> append(" ${line.text}\n")
                                DiffKind.DEL -> append("-${line.text}\n")
                                DiffKind.ADD -> append("+${line.text}\n")
                            }
                        }
                    }
                }
                append("\n")
            }
        }.take(50000)
    }

    private fun readContent(revision: com.intellij.openapi.vcs.changes.ContentRevision?): String? {
        if (revision == null) return null
        return try {
            val content = revision.content
            if (content != null && content.contains(' ')) null else content as? String
        } catch (_: Exception) { null }
    }

    /**
     * 构建 prompt，返回 (systemPrompt, userPrompt)。
     * systemPrompt 包含角色指令和行为约束，userPrompt 仅包含 diff 数据，
     * 分别放入 API 的 system/user role，提高模型指令遵循度。
     */
    private fun buildPrompt(diffText: String): Pair<String, String> {
        val customPrompt = try {
            AppSettingsService.getInstance().getPrompt()
        } catch (_: Exception) { null }

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

        // 分离系统指令（{diff} 之前的内容）和用户数据（diff 本身）
        val systemPrompt = template.replace("{diff}", "").trim()
        // 防御：如果分离后 systemPrompt 为空（如用户仅输入 "{diff}"），回退到默认系统指令
        val effectiveSystemPrompt = systemPrompt.ifBlank {
            val isChinese = Locale.getDefault().language.startsWith("zh")
            if (isChinese) {
                AppSettingsService.DEFAULT_COMMIT_PROMPT_ZH.replace("{diff}", "").trim()
            } else {
                AppSettingsService.DEFAULT_COMMIT_PROMPT.replace("{diff}", "").trim()
            }
        }
        return Pair(effectiveSystemPrompt, diffText)
    }

    private fun callDeepSeek(apiKey: String, systemPrompt: String, userPrompt: String, onDelta: ((String) -> Unit)? = null): String? {
        val model = try { AppSettingsService.getInstance().getModel() } catch (_: Exception) { null } ?: "deepseek-chat"
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
        } catch (e: Exception) {
            AppLogger.requestFailed(0, e.message ?: "Connection failed")
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 通过反射从组件树中查找 CheckinProjectPanel 并拿取用户勾选的文件。
     * CheckinProjectPanel 在 vcs-impl 模块，不可编译期引用，只能运行时反射。
     * 若反射失败（新版 Commit UI 等），降级使用 defaultChangeList.changes。
     */
    private fun getCheckedChanges(controlComponent: Component, project: Project): List<Change> {
        var parent: Container? = controlComponent.parent
        while (parent != null) {
            if (parent.javaClass.name.contains("CheckinProjectPanel")) {
                try {
                    val method = parent.javaClass.getMethod("getSelectedChanges")
                    @Suppress("UNCHECKED_CAST")
                    val changes =
                        (method.invoke(parent) as? Collection<*>)?.filterIsInstance<Change>()
                            ?: emptyList()
                    if (changes.isNotEmpty()) return changes
                    // 反射成功但返回空列表，跳出循环走降级逻辑
                    break
                } catch (_: Exception) { /* reflection failed, try next parent */
                }
            }
            parent = parent.parent
        }
        // 降级：反射未找到 CheckinProjectPanel（新版 Commit UI），使用 default changelist
        AppLogger.info("getCheckedChanges: CheckinProjectPanel not found or returned empty, falling back to defaultChangeList")
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
