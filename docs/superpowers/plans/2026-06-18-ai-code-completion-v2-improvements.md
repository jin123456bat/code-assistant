# AI 代码补全 v2.0 改进实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 对比 Copilot 复盘后，5 个方向增强代码补全功能：上下文收集、候选导航、网络层、遥测、Prompt 结构化。

**Architecture:** 修改现有文件和新增少量文件，不改变整体架构。改动集中在 `completion/` 包。

**Tech Stack:** Kotlin, IntelliJ Platform 2023.3+, OkHttp（复用 Anthropic Java SDK 内置）, Gson

---

### Task 1: 增强上下文收集（Jaccard 相似度 + 标签页信号 + 路径元信息）

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/completion/CompletionContextCollector.kt`
- Create: `src/main/kotlin/com/aiassistant/completion/ContextEnhancer.kt`

- [ ] **Step 1: 创建 ContextEnhancer**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * 增强上下文收集：邻近文件 Jaccard 相似度排序 + 打开标签页优先。
 */
object ContextEnhancer {

    /** 最多选取的兄弟文件数 */
    private const val MAX_SIBLING_FILES = 5

    /**
     * 查找并排序兄弟文件。先按是否在打开标签页中分组（优先），组内按 Jaccard 相似度排序。
     * @param currentFile 当前编辑文件
     * @param extension 文件扩展名
     * @param currentImportLines 当前文件的 import/use 行集合（用于 Jaccard）
     * @param project 当前项目
     * @return 排序后的兄弟文件路径列表（最多 MAX_SIBLING_FILES 个）
     */
    fun findBestSiblingFiles(
        currentFile: VirtualFile,
        extension: String,
        currentImportLines: Set<String>,
        project: com.intellij.openapi.project.Project
    ): List<String> {
        val parent = currentFile.parent ?: return emptyList()
        val currentName = currentFile.name

        // 获取打开的文件集合
        val openFiles = FileEditorManager.getInstance(project).openFiles
            .map { it.path }
            .toSet()

        // 同扩展名的兄弟文件
        val siblings = parent.children.filter {
            it.extension == extension && it.name != currentName && !it.isDirectory
        }

        if (siblings.isEmpty()) return emptyList()

        // 分为两组：打开的文件优先
        val (openSiblings, closedSiblings) = siblings.partition { it.path in openFiles }

        // 组内按 Jaccard 相似度排序（相似度越高越靠前）
        fun scoreAndSort(files: List<VirtualFile>): List<VirtualFile> {
            return files.map { file ->
                val importLines = extractImportLines(file, extension)
                val score = jaccardSimilarity(currentImportLines, importLines)
                file to score
            }.sortedByDescending { it.second }.map { it.first }
        }

        val sorted = scoreAndSort(openSiblings) + scoreAndSort(closedSiblings)
        return sorted.take(MAX_SIBLING_FILES).map { it.path }
    }

    /**
     * 计算两个集合的 Jaccard 相似度：|A ∩ B| / |A ∪ B|
     */
    fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union
    }

    /**
     * 从文件中提取 import/use/include 行。
     */
    private fun extractImportLines(file: VirtualFile, extension: String): Set<String> {
        return try {
            val text = String(file.contentsToByteArray())
            text.lines()
                .map { it.trim() }
                .filter { line ->
                    when (extension.lowercase()) {
                        "php" -> line.startsWith("use ") || line.startsWith("require")
                        "js", "ts", "jsx", "tsx" -> line.startsWith("import ") || (line.startsWith("const ") && line.contains("require("))
                        "py" -> line.startsWith("import ") || line.startsWith("from ")
                        "java", "kt" -> line.startsWith("import ")
                        else -> line.contains("import") || line.contains("include")
                    }
                }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
```

- [ ] **Step 2: 修改 CompletionContextCollector 的 findSiblingFile 逻辑**

将 `collect()` 方法中的 `findSiblingFile()` 替换为使用 `ContextEnhancer.findBestSiblingFiles()`：

```kotlin
// 3. 如果不够 16K，加兄弟文件
var smartContext: String? = null
if (prefix.length + suffix.length < MAX_CHARS && virtualFile != null) {
    // 提取当前文件的 import 行用于 Jaccard 计算
    val currentImports = ContextEnhancer.extractImportLinesFromText(prefix, language)
    val siblingPaths = ContextEnhancer.findBestSiblingFiles(
        virtualFile,
        virtualFile.extension ?: "",
        currentImports,
        project
    )
    // 取第一个兄弟文件的内容
    if (siblingPaths.isNotEmpty()) {
        smartContext = try {
            java.io.File(siblingPaths.first()).readText().take(
                MAX_CHARS - prefix.length - suffix.length
            )
        } catch (_: Exception) { null }
    }
}
```

同时在 `ContextEnhancer` 中添加从文本提取 import 行的静态方法：

```kotlin
fun extractImportLinesFromText(text: String, language: String): Set<String> {
    return text.lines()
        .map { it.trim() }
        .filter { line ->
            when (language.lowercase()) {
                "php" -> line.startsWith("use ") || line.startsWith("namespace ")
                "javascript", "typescript" -> line.startsWith("import ")
                "python" -> line.startsWith("import ") || line.startsWith("from ")
                else -> false
            }
        }
        .toSet()
}
```

- [ ] **Step 3: Prompt 中嵌入路径元信息**

修改 `CompletionProvider.kt` 中构建 prompt 的部分：

```kotlin
val prompt = buildString {
    // 文件路径和语言元信息（对齐 Copilot 风格）
    append("// File: ${context.fileName}\n")
    append("// Language: ${context.language}\n")
    append("\n")
    if (!context.smartContext.isNullOrBlank()) {
        append(context.smartContext)
        append("\n")
    }
    append(context.prefix)
}
```

- [ ] **Step 4: 编译验证 + 测试**

```bash
./gradlew compileKotlin
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/
git commit -m "feat(completion): v2.0 增强上下文收集 (Jaccard相似度+标签页+路径元信息)"
```

---

### Task 2: 候选导航 Action 注册

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/NextCandidateAction.kt`
- Create: `src/main/kotlin/com/aiassistant/completion/PrevCandidateAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: 创建 NextCandidateAction**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.codeInsight.inline.completion.InlineCompletion

/**
 * 切换到下一个补全候选。默认快捷键：↓
 */
class NextCandidateAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        // 检查是否有活跃的 inline completion session
        val session = handler.session ?: return
        if (session.elements.size > 1) {
            handler.nextElement()
        } else {
            // 无多候选时，向下移动光标
            editor.caretModel.moveCaretRelatively(0, 1, false, false, false)
        }
    }
}
```

- [ ] **Step 2: 创建 PrevCandidateAction**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.codeInsight.inline.completion.InlineCompletion

/**
 * 切换到上一个补全候选。默认快捷键：↑
 */
class PrevCandidateAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        val session = handler.session ?: return
        if (session.elements.size > 1) {
            handler.previousElement()
        } else {
            editor.caretModel.moveCaretRelatively(0, -1, false, false, false)
        }
    }
}
```

**注意**：`handler.session`、`handler.nextElement()`、`handler.previousElement()` 需根据 IntelliJ 2023.3 实际 API 调整。如果这些 API 不存在，改用 在 `ChatToolWindow.popupKeyDispatcher` 或 `EditorFactoryListener` 中拦截键盘事件并调用 `InlineCompletion` 相关方法。

- [ ] **Step 3: 在 plugin.xml 中注册 Action**

```xml
        <action
            id="AiAssistant.NextCandidate"
            class="com.aiassistant.completion.NextCandidateAction"
            text="下一个补全候选"
            description="切换到下一个 AI 补全候选">
            <keyboard-shortcut keymap="$default" first-keystroke="DOWN"/>
        </action>

        <action
            id="AiAssistant.PrevCandidate"
            class="com.aiassistant.completion.PrevCandidateAction"
            text="上一个补全候选"
            description="切换到上一个 AI 补全候选">
            <keyboard-shortcut keymap="$default" first-keystroke="UP"/>
        </action>
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew compileKotlin
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/NextCandidateAction.kt \
        src/main/kotlin/com/aiassistant/completion/PrevCandidateAction.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(completion): v2.0 注册候选导航 Action (↑↓切换)"
```

---

### Task 3: 网络层优化（迁移到 OkHttp）

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/completion/DeepSeekFimClient.kt`

- [ ] **Step 1: 重写 DeepSeekFimClient 使用 OkHttp**

将 `HttpURLConnection` 迁移到 OkHttp，核心改动：

```kotlin
package com.aiassistant.completion

import com.aiassistant.AppSettingsService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeepSeekFimClient(
    private val settings: AppSettingsService = AppSettingsService.getInstance()
) {
    companion object {
        private const val FIM_ENDPOINT = "https://api.deepseek.com/beta/completions"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 10_000L
        private const val MAX_RETRIES = 2
        private val RETRY_BACKOFF_MS = longArrayOf(200, 400, 800)

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    /** OkHttp 客户端——连接池 + HTTP/2 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    /** 可取消的 Call */
    @Volatile
    private var activeCall: Call? = null

    // ---- Data classes (不变) ----

    data class FimRequest(
        val model: String,
        val prompt: String,
        val suffix: String?,
        @SerializedName("max_tokens") val maxTokens: Int,
        val n: Int,
        val temperature: Double = 0.0,
        val stop: List<String> = listOf("\n\n\n"),
        val stream: Boolean = false
    )

    data class FimChoice(
        val text: String,
        val index: Int,
        @SerializedName("finish_reason") val finishReason: String?
    )

    data class FimResponse(
        val id: String?,
        val `object`: String?,
        val choices: List<FimChoice>?,
        val usage: FimUsage?
    )

    data class FimUsage(
        @SerializedName("prompt_tokens") val promptTokens: Int,
        @SerializedName("completion_tokens") val completionTokens: Int
    )

    // ---- Public API ----

    fun complete(prompt: String, suffix: String?): FimResponse? {
        val apiKey = settings.getApiKey() ?: return null
        val request = FimRequest(
            model = settings.getModel(),
            prompt = prompt,
            suffix = suffix,
            maxTokens = settings.getCompletionMaxTokens(),
            n = settings.getCompletionNumCandidates()
        )
        return executeWithRetry(request, apiKey)
    }

    fun cancel() {
        activeCall?.cancel()
    }

    // ---- Internal ----

    private fun executeWithRetry(request: FimRequest, apiKey: String): FimResponse? {
        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return execute(request, apiKey)
            } catch (e: FimApiException) {
                // 4xx 不重试
                if (e.statusCode in 400..499) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            // 指数退避
            if (attempt < MAX_RETRIES) {
                Thread.sleep(RETRY_BACKOFF_MS[attempt])
            }
        }
        throw lastError ?: IOException("Unknown error")
    }

    private fun execute(request: FimRequest, apiKey: String): FimResponse {
        val jsonBody = gson.toJson(request)
        val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(FIM_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(httpRequest)
        activeCall = call
        try {
            val response = call.execute()
            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            if (!response.isSuccessful) {
                throw FimApiException(response.code, "FIM API error ${response.code}: $responseBody")
            }
            return gson.fromJson(responseBody, FimResponse::class.java)
        } finally {
            activeCall = null
        }
    }
}

/** 自定义异常：携带 HTTP 状态码 */
class FimApiException(val statusCode: Int, message: String) : IOException(message)
```

- [ ] **Step 2: 编译验证**（需确认项目 classpath 中有 OkHttp 依赖）

```bash
./gradlew compileKotlin
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/DeepSeekFimClient.kt
git commit -m "feat(completion): v2.0 网络层迁移到 OkHttp (连接池+指数退避+宽松超时)"
```

---

### Task 4: 遥测持久化与统计 Dashboard

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/completion/CompletionStats.kt`
- Modify: `src/main/kotlin/com/aiassistant/SettingsConfigurable.kt`

- [ ] **Step 1: 增强 CompletionStats 支持持久化和按语言统计**

```kotlin
package com.aiassistant.completion

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object CompletionStats {

    private val gson = Gson()

    // ---- 本次会话数据 ----
    private val totalShown = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalAccepted = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalLatencyMs = java.util.concurrent.atomic.AtomicLong(0)

    // ---- 按语言维度 ----
    private data class LanguageStats(
        val shown: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
        val accepted: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
        val latencyMs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0)
    )
    private val languageStats = ConcurrentHashMap<String, LanguageStats>()

    // ---- 持久化数据 ----
    private data class PersistentStats(
        val date: String,
        val totalShown: Int,
        val totalAccepted: Int,
        val averageLatencyMs: Long,
        val byLanguage: Map<String, LanguageSummary>
    )
    private data class LanguageSummary(
        val shown: Int,
        val accepted: Int,
        val avgLatencyMs: Long
    )

    fun recordShown(language: String, latencyMs: Long) {
        totalShown.incrementAndGet()
        totalLatencyMs.addAndGet(latencyMs)
        languageStats.computeIfAbsent(language) { LanguageStats() }.apply {
            shown.incrementAndGet()
            latencyMs.addAndGet(latencyMs)
        }
    }

    fun recordAccepted(language: String) {
        totalAccepted.incrementAndGet()
        languageStats[language]?.accepted?.incrementAndGet()
    }

    fun recordCancelled() {
        // no-op
    }

    // 保持兼容旧接口（不带 language 参数）
    fun recordShown(latencyMs: Long) = recordShown("unknown", latencyMs)
    fun recordAccepted() = recordAccepted("unknown")

    // ---- 查询 ----

    data class StatsSnapshot(
        val shown: Int,
        val accepted: Int,
        val totalLatencyMs: Long,
        val byLanguage: Map<String, LangSnapshot>
    )
    data class LangSnapshot(
        val shown: Int,
        val accepted: Int,
        val avgLatencyMs: Long
    )

    @Synchronized
    fun getSnapshot(): StatsSnapshot {
        val langSnapshots = languageStats.mapValues { (_, v) ->
            LangSnapshot(
                shown = v.shown.get(),
                accepted = v.accepted.get(),
                avgLatencyMs = if (v.shown.get() > 0) v.latencyMs.get() / v.shown.get() else 0L
            )
        }
        return StatsSnapshot(
            shown = totalShown.get(),
            accepted = totalAccepted.get(),
            totalLatencyMs = totalLatencyMs.get(),
            byLanguage = langSnapshots
        )
    }

    fun getShownCount() = totalShown.get()
    fun getAcceptedCount() = totalAccepted.get()

    fun getAcceptRate(): Double {
        val snap = getSnapshot()
        return if (snap.shown == 0) 0.0 else snap.accepted.toDouble() / snap.shown * 100.0
    }

    fun getAverageLatencyMs(): Long {
        val snap = getSnapshot()
        return if (snap.shown == 0) 0L else snap.totalLatencyMs / snap.shown
    }

    fun reset() {
        totalShown.set(0)
        totalAccepted.set(0)
        totalLatencyMs.set(0)
        languageStats.clear()
    }

    /** 保存到项目目录下的 JSON 文件 */
    fun persist(projectPath: String) {
        try {
            val snap = getSnapshot()
            val today = java.time.LocalDate.now().toString()
            val persistent = PersistentStats(
                date = today,
                totalShown = snap.shown,
                totalAccepted = snap.accepted,
                averageLatencyMs = getAverageLatencyMs(),
                byLanguage = snap.byLanguage.mapValues { (_, v) ->
                    LanguageSummary(v.shown, v.accepted, v.avgLatencyMs)
                }
            )
            val file = File("$projectPath/.claude/completion-stats.json")
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(persistent))
        } catch (_: Exception) {
            // 持久化失败不阻塞
        }
    }
}
```

- [ ] **Step 2: 更新 SettingsConfigurable 中的统计卡片**

修改 `refreshCompletionStatsUI()` 增加分语言展示：

```kotlin
private fun refreshCompletionStatsUI() {
    val stats = CompletionStats
    val snap = stats.getSnapshot()
    val acceptRate = "%.1f".format(stats.getAcceptRate())
    
    val sb = StringBuilder()
    sb.appendLine("显示: ${snap.shown}   接受: ${snap.accepted}   接受率: ${acceptRate}%")
    sb.appendLine("平均延迟: ${stats.getAverageLatencyMs()}ms")
    
    if (snap.byLanguage.isNotEmpty()) {
        sb.appendLine()
        for ((lang, ls) in snap.byLanguage.entries.sortedBy { it.key }) {
            val langRate = if (ls.shown > 0) "%.1f".format(ls.accepted.toDouble() / ls.shown * 100.0) else "0.0"
            sb.appendLine("$lang: ${ls.accepted}/${ls.shown} ($langRate%)  ${ls.avgLatencyMs}ms")
        }
    }
    
    completionStatsLabel.text = sb.toString()
}
```

**注意**：`CompletionProvider.kt` 中 `recordShown()` 调用需同步加上 language 参数（从 `context.language` 获取）。

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/CompletionStats.kt \
        src/main/kotlin/com/aiassistant/SettingsConfigurable.kt \
        src/main/kotlin/com/aiassistant/completion/CompletionProvider.kt
git commit -m "feat(completion): v2.0 遥测持久化+按语言统计+设置页Dashboard"
```

---

### Task 5: Prompt 结构化（已在 Task 1 Step 3 中完成）

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/completion/CompletionProvider.kt`

此改进已在 Task 1 的 Step 3 中实现（prompt 中嵌入 `// File:` + `// Language:` 元信息）。如果 Task 1 中未实现，则在 Task 5 中单独完成。

**验证**：检查 prompt 构建逻辑是否包含元信息注释。

---

### Task 6: 全量测试与构建验证

- [ ] **Step 1: 运行全部测试**

```bash
./gradlew test
```

- [ ] **Step 2: 构建插件**

```bash
./gradlew buildPlugin
```

- [ ] **Step 3: 手动验证**

```bash
./gradlew runIde
```

验证清单：
1. 打开 PHP 项目 → 输入代码 → 幽灵文本出现 → prompt 中应包含 `// File:` + `// Language:` 元信息
2. 同目录有多个 PHP 文件 → 兄弟文件按 Jaccard 相似度选择（检查日志）
3. 打开的文件在标签页中 → 应优先被选为上下文
4. ↑↓ 键 → 多候选之间切换
5. 设置页 → 统计 Dashboard 显示分语言数据
6. 关闭 IDE → `.claude/completion-stats.json` 生成

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(completion): v2.0 构建验证与微调"
```
