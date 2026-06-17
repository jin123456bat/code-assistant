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
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.EditorTextField
import com.google.gson.Gson
import java.awt.Component
import java.awt.Container
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

class GenerateCommitAction : AnAction() {

    private var isGenerating = false
    private var lastClickTime = 0L

    override fun update(e: AnActionEvent) {
        val project = e.project
        val changes = project?.let {
            ChangeListManager.getInstance(it).defaultChangeList.changes
        } ?: emptyList()
        val hasCommitControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null

        // 有变更即可见；有 commit 控件时可用
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

        isGenerating = true

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, AiAssistantBundle.message("action.generate.progress"), false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val diffText = buildDiff(project)
                    if (diffText.isBlank()) {
                        showNotification(project, AiAssistantBundle.message("action.generate.nochanges"))
                        return
                    }
                    val prompt = buildPrompt(diffText)
                    val message = callDeepSeek(apiKey, prompt)
                    if (message == null) {
                        showNotification(project, AiAssistantBundle.message("action.generate.failed"))
                        return
                    }
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            val editor = findEditorField(commitMessageControl as? Component ?: return@invokeLater)
                            ApplicationManager.getApplication().runWriteAction {
                                editor?.document?.setText(message.trim())
                            }
                        } catch (ex: Exception) {
                            showNotification(project, AiAssistantBundle.message("action.generate.setfailed"))
                        }
                    }
                } catch (ex: Exception) {
                    showNotification(project, ex.message ?: "Unknown error")
                } finally {
                    isGenerating = false
                }
            }
        })
    }

    private fun buildDiff(project: Project): String {
        val basePath = project.basePath
        if (basePath != null) {
            val stagedDiff = runGitCommand(basePath, "diff", "--staged")
            val unstagedDiff = if (stagedDiff.isBlank()) runGitCommand(basePath, "diff") else ""

            val diffContent = when {
                stagedDiff.isNotBlank() -> stagedDiff
                unstagedDiff.isNotBlank() -> unstagedDiff
                else -> buildSimpleDiff(project)
            }
            if (diffContent.isBlank()) return ""

            val fileList = runGitCommand(basePath, "diff", "--staged", "--name-only")
            val stat = runGitCommand(basePath, "diff", "--staged", "--stat")
            val recentCommits = runGitCommand(basePath, "log", "--oneline", "-5")

            return buildString {
                if (fileList.isNotBlank()) {
                    append("Changed files:\n")
                    fileList.lines().forEach { append("  - $it\n") }
                    append("\n")
                }
                if (stat.isNotBlank()) {
                    append("Changes summary:\n$stat\n\n")
                }
                append("Git diff:\n$diffContent")
                if (recentCommits.isNotBlank()) {
                    append("\n\nRecent commits for style reference:\n$recentCommits")
                }
            }.take(15000)
        }

        return buildSimpleDiff(project)
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
            output.take(5000)
        } catch (_: Exception) {
            ""
        } finally {
            try { process.destroyForcibly() } catch (_: Exception) {}
        }
    }

    private fun buildSimpleDiff(project: Project): String {
        val changes = ChangeListManager.getInstance(project).defaultChangeList.changes
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

                append("--- a/$path\n")
                append("+++ b/$path\n")

                val before = readContent(beforeRev)
                val after = readContent(afterRev)

                when {
                    before == null && after != null -> {
                        append("@@ -0,0 +1,${after.lines().size} @@\n")
                        after.lines().take(50).forEach { append("+$it\n") }
                    }
                    after == null && before != null -> {
                        append("@@ -1,${before.lines().size} +0,0 @@\n")
                        before.lines().take(30).forEach { append("-$it\n") }
                    }
                    before != null && after != null -> {
                        // 生成简化版逐行 diff，让 LLM 看到具体改动
                        val beforeLines = before.lines()
                        val afterLines = after.lines()
                        val maxShow = 80
                        if (beforeLines.size + afterLines.size < maxShow) {
                            for (line in beforeLines) append("-$line\n")
                            for (line in afterLines) append("+$line\n")
                        } else {
                            append("@@ ... @@\n")
                            // 只显示开头和结尾的变化，标注中间省略
                            beforeLines.take(20).forEach { append("-$it\n") }
                            if (beforeLines.size > 40) append("... (${beforeLines.size - 40} lines omitted)\n")
                            beforeLines.takeLast(20).forEach { append("-$it\n") }
                            afterLines.take(20).forEach { append("+$it\n") }
                            if (afterLines.size > 40) append("... (${afterLines.size - 40} lines omitted)\n")
                            afterLines.takeLast(20).forEach { append("+$it\n") }
                        }
                    }
                }
                append("\n")
            }
        }.take(15000)
    }

    private fun readContent(revision: com.intellij.openapi.vcs.changes.ContentRevision?): String? {
        if (revision == null) return null
        return try {
            val content = revision.content
            if (content != null && content.contains(' ')) null else content as? String
        } catch (_: Exception) { null }
    }

    private fun buildPrompt(diffText: String): String {
        val customPrompt = try {
            AppSettingsService.getInstance().getPrompt()
        } catch (_: Exception) { null }

        if (!customPrompt.isNullOrBlank()) {
            return customPrompt.replace("{diff}", diffText)
        }

        val isChinese = Locale.getDefault().language.startsWith("zh")
        val defaultPrompt = if (isChinese) {
            AppSettingsService.DEFAULT_COMMIT_PROMPT_ZH
        } else {
            AppSettingsService.DEFAULT_COMMIT_PROMPT
        }
        return defaultPrompt.replace("{diff}", diffText)
    }

    private fun callDeepSeek(apiKey: String, prompt: String): String? {
        val model = try { AppSettingsService.getInstance().getModel() } catch (_: Exception) { null } ?: "deepseek-chat"
        val requestBody = Gson().toJson(mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            "stream" to false
        ))

        val uri = URI.create("https://api.deepseek.com/v1/chat/completions")
        val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use { it.write(requestBody.toByteArray()) }
            val statusCode = conn.responseCode

            if (statusCode != HttpURLConnection.HTTP_OK) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                AppLogger.requestFailed(statusCode, errorBody)
                return null
            }

            val response = conn.inputStream.bufferedReader().readText()
            return parseResponse(response)
        } catch (e: Exception) {
            AppLogger.requestFailed(0, e.message ?: "Connection failed")
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): String? {
        return try {
            val responseObj = Gson().fromJson(json, Map::class.java) as? Map<*, *> ?: return null
            val choices = responseObj["choices"] as? List<*> ?: return null
            val choice = choices.firstOrNull() as? Map<*, *> ?: return null
            val message = choice["message"] as? Map<*, *> ?: return null
            message["content"] as? String
        } catch (_: Exception) { null }
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
