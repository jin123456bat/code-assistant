# AI 代码补全功能实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Code Assistant 插件新增 AI 驱动的行内幽灵文本代码补全功能，支持自动触发、手动触发、多候选切换、缓存、统计。

**Architecture:** 基于 IntelliJ Platform `InlineCompletionProvider` API，通过 DeepSeek FIM API（`/beta/completions`）获取补全建议。模块化设计：TokenBudgetManager（预算分配）→ CompletionContextCollector（上下文采集 + PSI 增强）→ DeepSeekFimClient（API 调用）→ 后处理 → InlineCompletionProvider 渲染。配套 CompletionDebounceManager（防抖）、CompletionCache（缓存）、CompletionStats（统计）。

**Tech Stack:** Kotlin, IntelliJ Platform 2023.3+, OkHttp（复用 Anthropic Java SDK 内置）, DeepSeek FIM API

**新增包:** `com.aiassistant.completion`（8 个文件）

---

### Task 1: 添加补全设置字段到 AppSettingsService

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/AppSettingsService.kt`

- [ ] **Step 1: 在 companion object 中添加常量**

```kotlin
// 在 companion object 中，THINKING_KEY 之后添加：
private const val COMPLETION_ENABLED_KEY = "$SERVICE_NAME.COMPLETION.ENABLED"
private const val COMPLETION_MAX_TOKENS_KEY = "$SERVICE_NAME.COMPLETION.MAX_TOKENS"
private const val COMPLETION_DEBOUNCE_MS_KEY = "$SERVICE_NAME.COMPLETION.DEBOUNCE_MS"
private const val COMPLETION_NUM_CANDIDATES_KEY = "$SERVICE_NAME.COMPLETION.NUM_CANDIDATES"
private const val COMPLETION_MANUAL_SHORTCUT_KEY = "$SERVICE_NAME.COMPLETION.MANUAL_SHORTCUT"
private const val COMPLETION_PREV_CANDIDATE_KEY = "$SERVICE_NAME.COMPLETION.PREV_CANDIDATE"
private const val COMPLETION_NEXT_CANDIDATE_KEY = "$SERVICE_NAME.COMPLETION.NEXT_CANDIDATE"
```

- [ ] **Step 2: 在类体末尾（`setCompactRatio` 之后）添加 getter/setter 方法**

```kotlin
// ---- 补全设置 ----

fun isCompletionEnabled(): Boolean {
    val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_ENABLED_KEY)
    return raw == null || raw.toBooleanStrictOrNull() != false
}

fun setCompletionEnabled(enabled: Boolean) {
    com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_ENABLED_KEY, enabled.toString())
}

fun getCompletionMaxTokens(): Int {
    val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_MAX_TOKENS_KEY)
    return raw?.toIntOrNull()?.coerceIn(1, 1024) ?: 1024
}

fun setCompletionMaxTokens(tokens: Int) {
    com.intellij.ide.util.PropertiesComponent.getInstance()
        .setValue(COMPLETION_MAX_TOKENS_KEY, tokens.coerceIn(1, 1024).toString())
}

fun getCompletionDebounceMs(): Int {
    val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_DEBOUNCE_MS_KEY)
    return raw?.toIntOrNull()?.coerceIn(100, 2000) ?: 300
}

fun setCompletionDebounceMs(ms: Int) {
    com.intellij.ide.util.PropertiesComponent.getInstance()
        .setValue(COMPLETION_DEBOUNCE_MS_KEY, ms.coerceIn(100, 2000).toString())
}

fun getCompletionNumCandidates(): Int {
    val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_NUM_CANDIDATES_KEY)
    return raw?.toIntOrNull()?.coerceIn(1, 10) ?: 10
}

fun setCompletionNumCandidates(n: Int) {
    com.intellij.ide.util.PropertiesComponent.getInstance()
        .setValue(COMPLETION_NUM_CANDIDATES_KEY, n.coerceIn(1, 10).toString())
}

fun getCompletionManualShortcut(): String {
    val default = if (System.getProperty("os.name").lowercase().contains("mac")) "meta P" else "alt P"
    return com.intellij.ide.util.PropertiesComponent.getInstance()
        .getValue(COMPLETION_MANUAL_SHORTCUT_KEY, default)
}

fun setCompletionManualShortcut(shortcut: String) {
    com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_MANUAL_SHORTCUT_KEY, shortcut)
}

fun getCompletionPrevCandidateKey(): String {
    return com.intellij.ide.util.PropertiesComponent.getInstance()
        .getValue(COMPLETION_PREV_CANDIDATE_KEY, "UP")
}

fun setCompletionPrevCandidateKey(key: String) {
    com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_PREV_CANDIDATE_KEY, key)
}

fun getCompletionNextCandidateKey(): String {
    return com.intellij.ide.util.PropertiesComponent.getInstance()
        .getValue(COMPLETION_NEXT_CANDIDATE_KEY, "DOWN")
}

fun setCompletionNextCandidateKey(key: String) {
    com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_NEXT_CANDIDATE_KEY, key)
}
```

- [ ] **Step 3: 构建并验证编译通过**

```bash
./gradlew compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/aiassistant/AppSettingsService.kt
git commit -m "feat(completion): 添加补全设置字段到 AppSettingsService"
```

---

### Task 2: 创建 TokenBudgetManager

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/TokenBudgetManager.kt`

- [ ] **Step 1: 创建 TokenBudgetManager**

```kotlin
package com.aiassistant.completion

import kotlin.math.min

/**
 * 根据用户设置的 maxTokens 动态分配 prefix/suffix/smartContext 的输入预算。
 * 总上下文窗口: 16K tokens，字符估算: 1 token ≈ 4 chars。
 */
class TokenBudgetManager(private val userMaxTokens: Int) {

    companion object {
        private const val TOTAL_WINDOW_TOKENS = 16_384   // 16K
        private const val CHARS_PER_TOKEN = 4

        /** 分配比例: prefix 50%, suffix 25%, smartContext 25% */
        private const val PREFIX_RATIO = 0.5
        private const val SUFFIX_RATIO = 0.25
        private const val SMART_CTX_RATIO = 0.25
    }

    /** 可用的输入 token 总数 */
    val availableInputTokens: Int = (TOTAL_WINDOW_TOKENS - userMaxTokens).coerceAtLeast(1)

    /** prefix 字符上限 */
    val maxPrefixChars: Int = (availableInputTokens * PREFIX_RATIO * CHARS_PER_TOKEN).toInt()

    /** suffix 字符上限 */
    val maxSuffixChars: Int = (availableInputTokens * SUFFIX_RATIO * CHARS_PER_TOKEN).toInt()

    /** smartContext 字符上限 */
    val maxSmartContextChars: Int = (availableInputTokens * SMART_CTX_RATIO * CHARS_PER_TOKEN).toInt()
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/TokenBudgetManager.kt
git commit -m "feat(completion): 添加 TokenBudgetManager 输入预算分配器"
```

---

### Task 3: 创建 PSI 补全策略

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/PsiCompletionStrategy.kt`

- [ ] **Step 1: 创建 PsiCompletionStrategy 接口及各语言实现**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * PSI 补全策略接口。各语言实现负责从当前编辑器提取增强上下文（函数边界、依赖文件签名等）。
 * 返回 null 表示该策略不适用（如语言不支持），调用方应降级到 FallbackStrategy。
 */
interface PsiCompletionStrategy {
    /**
     * 收集增强上下文。返回 null 表示无额外上下文可用。
     */
    fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String?
}

// ---- Fallback: 纯文本，不做 PSI 增强 ----

class FallbackStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? = null
}

// ---- PHP Strategy ----

class PhpPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val document = editor.document
        val offset = editor.caretModel.offset

        val sb = StringBuilder()

        // 1. 光标所在函数/类/方法边界 — 取声明头 + 参数
        val element = psiFile.findElementAt(offset) ?: return null
        val containingFunction = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element,
            com.jetbrains.php.lang.psi.elements.Function::class.java
        )
        if (containingFunction != null) {
            sb.appendLine("// ${containingFunction.text.take(500)}\n")
        } else {
            // 可能是顶级代码，取文件开头的 namespace + use 声明
            val text = document.charsSequence.toString()
            val headerEnd = getPhpHeaderEnd(text)
            if (headerEnd > 0) {
                sb.appendLine(text.substring(0, headerEnd.coerceAtMost(1500)))
            }
        }

        // 2. import (use) 解析 → resolve 到类文件 → 读 public 方法签名
        val useStatements = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            psiFile, com.jetbrains.php.lang.psi.elements.PhpUse::class.java
        )
        for (useStmt in useStatements.take(10)) {
            val fqn = useStmt.fqn ?: continue
            // 尝试在项目中查找对应文件
            val resolved = resolvePhpClass(project, fqn)
            if (resolved != null) {
                sb.appendLine("// use $fqn")
                sb.appendLine(resolved.take(500))
            }
        }

        // 3. 同级文件风格参考
        val parentDir = psiFile.virtualFile?.parent ?: return sb.takeIf { it.isNotBlank() }?.toString()
        val siblingFiles = parentDir.children.filter {
            it.extension == "php" && it.name != psiFile.virtualFile.name
        }.take(3)
        for (sibling in siblingFiles) {
            try {
                val snippet = String(sibling.contentsToByteArray()).take(200)
                sb.appendLine("// === ${sibling.name} ===")
                sb.appendLine(snippet)
            } catch (_: Exception) { /* skip unreadable files */ }
        }

        return sb.takeIf { it.isNotBlank() }?.toString()
    }

    private fun getPhpHeaderEnd(text: String): Int {
        var pos = text.indexOf("<?php")
        if (pos < 0) pos = 0
        // 跳过 namespace 和 use 声明块
        var lastNewline = pos
        for (line in text.substring(pos).lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("namespace ") || trimmed.startsWith("use ") || trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                lastNewline += line.length + 1
            } else {
                break
            }
        }
        return lastNewline
    }

    private fun resolvePhpClass(project: Project, fqn: String): String? {
        // 按 PSR-4 将 FQN 转文件路径后在项目源码中查找
        // TODO: 后续版本使用 PhpIndex 做精确 resolve
        return null
    }
}

// ---- JS Strategy ----

class JsPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val document = editor.document
        val offset = editor.caretModel.offset
        val sb = StringBuilder()

        // 1. 光标所在 function/class 边界
        val element = psiFile.findElementAt(offset) ?: return null
        try {
            val containingFunction = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element,
                com.intellij.lang.javascript.psi.JSFunction::class.java
            )
            if (containingFunction != null) {
                sb.appendLine("// ${containingFunction.text.take(500)}\n")
            }
            // 提取 import 行
            val text = document.charsSequence.toString()
            for (line in text.lines().take(50)) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") || trimmed.startsWith("const ") && trimmed.contains("require(")) {
                    sb.appendLine(trimmed)
                }
            }
        } catch (_: Exception) { /* JS PSI 不可用 */ }

        return sb.takeIf { it.isNotBlank() }?.toString()
    }
}

// ---- HTML Strategy ----

class HtmlPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        val parentTag = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element,
            com.intellij.psi.xml.XmlTag::class.java
        )
        if (parentTag != null) {
            return "<!-- parent: <${parentTag.name}> -->\n${parentTag.text.take(500)}"
        }
        return null
    }
}

// ---- CSS Strategy ----

class CssPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        // 查找所在的规则集或规则
        val parentBlock = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element,
            com.intellij.psi.css.CssRulesetList::class.java,
            com.intellij.psi.css.CssBlock::class.java
        )
        if (parentBlock != null) {
            return "/* parent block */ {\n${parentBlock.text.take(500)}\n}"
        }
        return null
    }
}
```

- [ ] **Step 2: 添加策略选择函数**

在同一个文件中添加：

```kotlin
/**
 * 根据语言标识选择合适的 PSI 策略。
 */
fun selectPsiStrategy(language: String): PsiCompletionStrategy = when (language.lowercase()) {
    "php" -> PhpPsiStrategy()
    "javascript", "typescript" -> JsPsiStrategy()
    "html", "xml" -> HtmlPsiStrategy()
    "css", "scss", "less" -> CssPsiStrategy()
    else -> FallbackStrategy()
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/PsiCompletionStrategy.kt
git commit -m "feat(completion): 添加 PSI 补全策略接口及多语言实现"
```

---

### Task 4: 创建 CompletionStats

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/CompletionStats.kt`

- [ ] **Step 1: 创建 CompletionStats**

```kotlin
package com.aiassistant.completion

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地统计数据收集，存内存，不上报。重启 IDE 清零。
 */
object CompletionStats {

    private val totalShown = AtomicInteger(0)
    private val totalAccepted = AtomicInteger(0)
    private val totalLatencyMs = AtomicLong(0)

    fun recordShown(latencyMs: Long) {
        totalShown.incrementAndGet()
        totalLatencyMs.addAndGet(latencyMs)
    }

    fun recordAccepted() {
        totalAccepted.incrementAndGet()
    }

    /** 不计入 accepted（用户 Esc 取消等） */
    fun recordCancelled() {
        // 仅标记，不改变计数器
    }

    fun getShownCount(): Int = totalShown.get()
    fun getAcceptedCount(): Int = totalAccepted.get()

    fun getAcceptRate(): Double {
        val shown = totalShown.get()
        if (shown == 0) return 0.0
        return totalAccepted.get().toDouble() / shown * 100.0
    }

    fun getAverageLatencyMs(): Long {
        val shown = totalShown.get()
        if (shown == 0) return 0L
        return totalLatencyMs.get() / shown
    }

    fun reset() {
        totalShown.set(0)
        totalAccepted.set(0)
        totalLatencyMs.set(0)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/CompletionStats.kt
git commit -m "feat(completion): 添加 CompletionStats 本地统计"
```

---

### Task 5: 创建 CompletionCache

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/CompletionCache.kt`

- [ ] **Step 1: 创建 CompletionCache**

```kotlin
package com.aiassistant.completion

import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * 补全结果缓存。宽松匹配（prefix/suffix 各取前后 200 字符 hash），TTL 60s，LRU 最大 20 条。
 */
class CompletionCache(
    private val ttlMs: Long = 60_000,
    private val maxSize: Int = 20
) {
    data class CacheEntry(
        val candidates: List<String>,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** LRU map (access-order)，线程安全 */
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(prefix: String, suffix: String): List<String>? {
        val key = makeKey(prefix, suffix)
        val entry = cache[key] ?: return null
        // 检查 TTL
        if (System.currentTimeMillis() - entry.createdAt > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.candidates
    }

    @Synchronized
    fun put(prefix: String, suffix: String, candidates: List<String>) {
        val key = makeKey(prefix, suffix)
        cache[key] = CacheEntry(candidates)
    }

    /** 清空所有缓存（文件变更时调用） */
    @Synchronized
    fun clearAll() {
        cache.clear()
    }

    private fun makeKey(prefix: String, suffix: String): String {
        val prefixPart = if (prefix.length > 200) prefix.substring(prefix.length - 200) else prefix
        val suffixPart = if (suffix.length > 200) suffix.substring(0, 200) else suffix
        val raw = "$prefixPart|$suffixPart"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/CompletionCache.kt
git commit -m "feat(completion): 添加 CompletionCache 补全结果缓存"
```

---

### Task 6: 创建 DeepSeekFimClient

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/DeepSeekFimClient.kt`

- [ ] **Step 1: 创建 DeepSeekFimClient**

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

/**
 * DeepSeek FIM API 客户端。封装 `/beta/completions` 调用，非流式，带超时和重试。
 */
class DeepSeekFimClient(
    private val settings: AppSettingsService = AppSettingsService.getInstance()
) {
    companion object {
        private const val FIM_ENDPOINT = "https://api.deepseek.com/beta/completions"
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val READ_TIMEOUT_MS = 3_000L
        private const val MAX_RETRIES = 1

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 可取消的 Call 引用
    @Volatile
    private var activeCall: Call? = null

    // ---- Request/Response data classes ----

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

    data class FimUsage(
        @SerializedName("prompt_tokens") val promptTokens: Int,
        @SerializedName("completion_tokens") val completionTokens: Int
    )

    data class FimResponse(
        val id: String?,
        val `object`: String?,
        val choices: List<FimChoice>?,
        val usage: FimUsage?
    )

    // ---- Public API ----

    /**
     * 发送 FIM 请求，返回原始响应。
     * @throws IOException 网络错误
     * @return null 表示 API Key 未设置
     */
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

    /** 取消进行中的请求 */
    fun cancel() {
        activeCall?.cancel()
    }

    // ---- Internal ----

    private fun executeWithRetry(request: FimRequest, apiKey: String): FimResponse? {
        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return execute(request, apiKey)
            } catch (e: IOException) {
                lastError = e
                activeCall = null
                // 4xx 错误不重试
                if (e is IOException && e.message?.contains("4") == true) break
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

        activeCall = client.newCall(httpRequest)
        val response = activeCall!!.execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        if (!response.isSuccessful) {
            throw IOException("FIM API error ${response.code}: $responseBody")
        }
        return gson.fromJson(responseBody, FimResponse::class.java)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/DeepSeekFimClient.kt
git commit -m "feat(completion): 添加 DeepSeekFimClient FIM API 客户端"
```

---

### Task 7: 创建 CompletionContextCollector

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/CompletionContextCollector.kt`

- [ ] **Step 1: 创建 CompletionContextCollector**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import kotlin.math.min

data class CompletionContext(
    val prefix: String,
    val suffix: String,
    val language: String,
    val fileName: String,
    val smartContext: String?
)

class CompletionContextCollector(
    private val budgetManager: TokenBudgetManager
) {
    fun collect(editor: Editor, project: Project): CompletionContext {
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = getLanguageFromExtension(virtualFile?.extension ?: "")

        // 核心层：纯文本 prefix + suffix
        val fullText = document.charsSequence.toString()
        val prefixRaw = fullText.substring(0, caretOffset.coerceAtMost(fullText.length))
        val suffixRaw = if (caretOffset < fullText.length) fullText.substring(caretOffset) else ""

        val prefix = prefixRaw.takeLast(budgetManager.maxPrefixChars)
        val suffix = suffixRaw.take(budgetManager.maxSuffixChars)

        // PSI 增强层
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val strategy = selectPsiStrategy(language)
        val smartContext = if (psiFile != null) {
            strategy.collectContext(editor, project, psiFile)?.take(budgetManager.maxSmartContextChars)
        } else null

        return CompletionContext(
            prefix = prefix,
            suffix = suffix,
            language = language,
            fileName = fileName,
            smartContext = smartContext
        )
    }

    private fun getLanguageFromExtension(ext: String): String = when (ext.lowercase()) {
        "php" -> "php"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "html", "htm" -> "html"
        "css" -> "css"
        "scss", "less" -> "css"
        "vue", "svelte" -> ext.lowercase()
        "py" -> "python"
        "go" -> "go"
        "rb" -> "ruby"
        "java" -> "java"
        "kt", "kts" -> "kotlin"
        else -> ext.lowercase().ifBlank { "text" }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/CompletionContextCollector.kt
git commit -m "feat(completion): 添加 CompletionContextCollector 上下文采集器"
```

---

### Task 8: 创建 CompletionDebounceManager

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/CompletionDebounceManager.kt`

- [ ] **Step 1: 创建 CompletionDebounceManager**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 防抖管理器。字符/回车/关键字/空格/小粘贴 → debounce 后触发；手动快捷键 → 立即触发。
 * 新输入 → 取消进行中请求并重新计时。
 */
class CompletionDebounceManager(
    private val debounceMs: Long,
    private val onTrigger: () -> Unit,
    private val onCancelPending: () -> Unit
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingFuture: ScheduledFuture<*>? = null
    private var lastInputTime: Long = 0

    @Synchronized
    fun onUserInput() {
        cancelPending()
        scheduleDebounce()
    }

    @Synchronized
    fun onManualTrigger() {
        cancelPending()
        onCancelPending()  // 取消进行中的 API 请求
        onTrigger()
    }

    private fun scheduleDebounce() {
        pendingFuture = scheduler.schedule({
            synchronized(this) {
                onTrigger()
            }
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun cancelPending() {
        pendingFuture?.cancel(false)
        pendingFuture = null
        onCancelPending()
    }

    fun dispose() {
        scheduler.shutdownNow()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/CompletionDebounceManager.kt
git commit -m "feat(completion): 添加 CompletionDebounceManager 防抖管理器"
```

---

### Task 9: 创建 CompletionProvider（核心）

**Files:**
- Create: `src/main/kotlin/com/aiassistant/completion/CompletionProvider.kt`

- [ ] **Step 1: 创建后处理函数**

先在文件中放置后处理逻辑：

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * 对模型返回的所有候选文本做清理和验证。
 * 返回有效候选列表（无重复）。
 */
object CompletionPostProcessor {

    fun process(choices: List<DeepSeekFimClient.FimChoice>, prefix: String, suffix: String): List<String> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()

        for (choice in choices) {
            if (choice.finishReason == "content_filter") continue

            var text = choice.text

            // 1. suffix 重叠裁剪
            text = trimSuffixOverlap(text, suffix)

            // 2. prefix 开头重叠裁剪
            text = trimPrefixOverlap(text, prefix)

            // 3. finish_reason == "length" → 截断到最后一个完整行
            if (choice.finishReason == "length") {
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline > 0) text = text.substring(0, lastNewline)
            }

            // 4. 有效性过滤
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length < 3) continue
            if (trimmed == suffix.take(trimmed.length).trim()) continue
            if (seen.contains(trimmed)) continue  // 候选间去重

            seen.add(trimmed)
            results.add(text)
        }

        return results
    }

    private fun trimSuffixOverlap(text: String, suffix: String): String {
        if (suffix.isEmpty()) return text
        val minLen = minOf(text.length, suffix.length)
        for (i in minLen downTo 1) {
            val tail = text.substring(text.length - i)
            if (suffix.startsWith(tail)) {
                return text.substring(0, text.length - i)
            }
        }
        return text
    }

    private fun trimPrefixOverlap(text: String, prefix: String): String {
        if (prefix.isEmpty()) return text
        val minLen = minOf(text.length, prefix.length)
        for (i in minLen downTo 1) {
            val head = text.substring(0, i)
            if (prefix.endsWith(head)) {
                return text.substring(i)
            }
        }
        return text
    }
}
```

- [ ] **Step 2: 创建 CompletionProvider（InlineCompletionProvider 实现）**

继续在同一个文件末尾添加：

```kotlin
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionResponse
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.aiassistant.AppSettingsService

class AiCompletionProvider : InlineCompletionProvider {

    private val settings = AppSettingsService.getInstance()
    private val fimClient = DeepSeekFimClient(settings)
    private val cache = CompletionCache()
    private var debounceManager: CompletionDebounceManager? = null
    private val lock = Any()

    override fun isEnabled(): Boolean = settings.isCompletionEnabled()

    override fun getInlineCompletion(request: InlineCompletionRequest): InlineCompletionResponse? {
        if (!isEnabled()) return null

        val editor = request.editor
        val project = editor.project ?: return null

        // Token 预算
        val budget = TokenBudgetManager(settings.getCompletionMaxTokens())

        // 上下文采集
        val collector = CompletionContextCollector(budget)
        val context = collector.collect(editor, project)

        // 检查缓存（手动触发时跳过）
        val cachedCandidates: List<String>? = if (request.isManual) null else cache.get(context.prefix, context.suffix)

        val candidates: List<String>
        val startTime = System.currentTimeMillis()
        if (cachedCandidates != null) {
            candidates = cachedCandidates
            CompletionStats.recordShown(0) // 缓存命中，延迟为 0
        } else {
            // 构建 prompt: smartContext + prefix
            val prompt = buildString {
                if (!context.smartContext.isNullOrBlank()) {
                    append(context.smartContext)
                    append("\n")
                }
                append(context.prefix)
            }

            val suffix: String? = context.suffix.ifBlank { null }

            val response = try {
                fimClient.complete(prompt, suffix)
            } catch (e: Exception) {
                return null // 网络错误，不显示任何补全
            }

            if (response == null || response.choices.isNullOrEmpty()) return null

            // 后处理
            candidates = CompletionPostProcessor.process(response.choices, context.prefix, context.suffix)

            // 存入缓存
            if (candidates.isNotEmpty()) {
                cache.put(context.prefix, context.suffix, candidates)
            }

            val latency = System.currentTimeMillis() - startTime
            if (candidates.isNotEmpty()) {
                CompletionStats.recordShown(latency)
            }
        }

        if (candidates.isEmpty()) return null

        // 转换为 InlineCompletionResponse
        val elements = candidates.map { candidate ->
            InlineCompletionTextElement(candidate)
        }

        return InlineCompletionResponse(elements)
    }

    /** 用户接受补全 */
    fun onAccepted() {
        CompletionStats.recordAccepted()
    }

    /** 用户取消补全 */
    fun onCancelled() {
        CompletionStats.recordCancelled()
    }

    /** 取消进行中的 FIM 请求（用户继续输入时调用） */
    fun cancelPendingRequest() {
        fimClient.cancel()
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/CompletionProvider.kt
git commit -m "feat(completion): 添加 CompletionProvider 核心补全实现"
```

---

### Task 10: 注册扩展点到 plugin.xml

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: 添加 inlineCompletionProvider 扩展点**

在 `<extensions>` 标签内，`</extensions>` 之前添加：

```xml
        <inlineCompletionProvider
            id="AiAssistantInlineCompletion"
            implementation="com.aiassistant.completion.AiCompletionProvider"/>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(completion): 注册 AiCompletionProvider 扩展点"
```

---

### Task 11: 添加设置 UI 到 SettingsConfigurable

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/SettingsConfigurable.kt`

- [ ] **Step 1: 添加补全设置 UI 组件声明**

在类体顶部现有声明后添加：

```kotlin
    // ---- 补全设置 ----
    private val completionEnabledCheckBox = JBCheckBox("启用 AI 代码补全").apply { isSelected = true }
    private val completionMaxTokensSpinner = JSpinner(SpinnerNumberModel(1024, 1, 1024, 1))
    private val completionDebounceSpinner = JSpinner(SpinnerNumberModel(300, 100, 2000, 100))
    private val completionNumCandidatesSpinner = JSpinner(SpinnerNumberModel(10, 1, 10, 1))
    private val completionStatsLabel = JBLabel().apply {
        foreground = JBColor(0x666666, 0x8C8C8C)
    }
```

- [ ] **Step 2: 在 createComponent() 中添加补全设置区域**

在现有 filler 之前（`gbc.gridy = 16` 之前）插入：

```kotlin
        // ---- 补全设置 Section ----
        gbc.gridy = 16; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(16, 8, 4, 8)
        contentPanel.add(
            JBLabel("<html><b>代码补全</b></html>"), gbc
        )

        gbc.gridy = 17; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionEnabledCheckBox, gbc)

        gbc.gridy = 18; gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        contentPanel.add(JLabel("最大补全长度 (tokens)"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionMaxTokensSpinner, gbc)

        gbc.gridy = 19; gbc.gridx = 0; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        contentPanel.add(JLabel("防抖延迟 (ms)"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionDebounceSpinner, gbc)

        gbc.gridy = 20; gbc.gridx = 0; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        contentPanel.add(JLabel("候选数量"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionNumCandidatesSpinner, gbc)

        // ---- 统计卡片 ----
        gbc.gridy = 21; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(12, 16, 4, 8)
        contentPanel.add(JLabel("<html><b>补全统计</b></html>"), gbc)

        gbc.gridy = 22; gbc.insets = JBUI.insets(2, 16, 4, 8)
        contentPanel.add(completionStatsLabel, gbc)

        gbc.gridy = 23; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        val resetStatsBtn = JButton("重置统计")
        resetStatsBtn.addActionListener {
            CompletionStats.reset()
            refreshCompletionStatsUI()
        }
        contentPanel.add(resetStatsBtn, gbc)

        // 把原来的 filler 的 gridy 推到 24
        gbc.gridy = 24; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        gbc.anchor = GridBagConstraints.NORTHWEST
        contentPanel.add(JPanel(), gbc)
```

- [ ] **Step 3: 更新 reset() 以读取补全设置**

在现有的 `reset()` 方法末尾（`refreshWhitelistUI()` 之前）添加：

```kotlin
        val service = AppSettingsService.getInstance()
        completionEnabledCheckBox.isSelected = service.isCompletionEnabled()
        completionMaxTokensSpinner.value = service.getCompletionMaxTokens()
        completionDebounceSpinner.value = service.getCompletionDebounceMs()
        completionNumCandidatesSpinner.value = service.getCompletionNumCandidates()
        refreshCompletionStatsUI()
```

- [ ] **Step 4: 更新 isModified() 以包含补全设置**

在 `isModified()` 方法的 `return false` 之前添加：

```kotlin
        val service = AppSettingsService.getInstance()
        if (service.isCompletionEnabled() != completionEnabledCheckBox.isSelected) return true
        if (service.getCompletionMaxTokens() != (completionMaxTokensSpinner.value as Int)) return true
        if (service.getCompletionDebounceMs() != (completionDebounceSpinner.value as Int)) return true
        if (service.getCompletionNumCandidates() != (completionNumCandidatesSpinner.value as Int)) return true
```

- [ ] **Step 5: 更新 apply() 以保存补全设置**

在 `apply()` 方法的 `ChatToolWindow.notifySettingsChanged()` 之前添加：

```kotlin
        val service = AppSettingsService.getInstance()
        service.setCompletionEnabled(completionEnabledCheckBox.isSelected)
        service.setCompletionMaxTokens(completionMaxTokensSpinner.value as Int)
        service.setCompletionDebounceMs(completionDebounceSpinner.value as Int)
        service.setCompletionNumCandidates(completionNumCandidatesSpinner.value as Int)
```

- [ ] **Step 6: 添加 refreshCompletionStatsUI() 辅助方法**

```kotlin
    private fun refreshCompletionStatsUI() {
        val stats = CompletionStats
        val acceptRate = "%.1f".format(stats.getAcceptRate())
        completionStatsLabel.text = """
            显示: ${stats.getShownCount()}   接受: ${stats.getAcceptedCount()}   接受率: ${acceptRate}%
            平均延迟: ${stats.getAverageLatencyMs()}ms
        """.trimIndent()
    }
```

- [ ] **Step 7: 构建并验证编译通过**

```bash
./gradlew compileKotlin
```

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/aiassistant/SettingsConfigurable.kt
git commit -m "feat(completion): 添加代码补全设置 UI 及统计卡片"
```

---

### Task 12: 集成测试与验证

**Files:**
- Create: `src/test/kotlin/com/aiassistant/completion/TokenBudgetManagerTest.kt`
- Create: `src/test/kotlin/com/aiassistant/completion/CompletionPostProcessorTest.kt`
- Create: `src/test/kotlin/com/aiassistant/completion/CompletionCacheTest.kt`

- [ ] **Step 1: 创建 TokenBudgetManagerTest**

```kotlin
package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class TokenBudgetManagerTest {
    @Test
    fun `should allocate budget inversely to maxTokens`() {
        val small = TokenBudgetManager(128)
        val large = TokenBudgetManager(1024)

        assertTrue(small.availableInputTokens > large.availableInputTokens)
        assertTrue(small.maxPrefixChars > large.maxPrefixChars)
        assertTrue(small.maxSuffixChars > large.maxSuffixChars)
    }

    @Test
    fun `should not produce negative budget`() {
        val max = TokenBudgetManager(16000)  // 接近上下文窗口上限
        assertTrue(max.availableInputTokens >= 1)
        assertTrue(max.maxPrefixChars >= 1)
    }

    @Test
    fun `should maintain 2:1 prefix to suffix ratio`() {
        val budget = TokenBudgetManager(512)
        val ratio = budget.maxPrefixChars.toDouble() / budget.maxSuffixChars.toDouble()
        assertEquals(2.0, ratio, 0.2)  // prefix 是 suffix 的 ~2 倍
    }
}
```

- [ ] **Step 2: 创建 CompletionPostProcessorTest**

```kotlin
package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class CompletionPostProcessorTest {
    @Test
    fun `should trim suffix overlap`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("function getUser() {\n    return null;\n}\n", 0, "stop")
        )
        val suffix = "function getUser() {\n    return null;\n}\n"  // 重复的后缀
        val processed = CompletionPostProcessor.process(choices, "", suffix)
        assertTrue(processed.isEmpty()) // 完全和 suffix 重复，丢弃
    }

    @Test
    fun `should filter short results`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("ab", 0, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertTrue(processed.isEmpty())
    }

    @Test
    fun `should filter blank results`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("   \n  \n ", 0, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertTrue(processed.isEmpty())
    }

    @Test
    fun `should keep valid completion`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("    return userRepository.findById(id);\n}", 0, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertEquals(1, processed.size)
        assertEquals("    return userRepository.findById(id);\n}", processed[0])
    }

    @Test
    fun `should deduplicate identical candidates`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("return x;", 0, "stop"),
            DeepSeekFimClient.FimChoice("return x;", 1, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertEquals(1, processed.size)
    }
}
```

- [ ] **Step 3: 创建 CompletionCacheTest**

```kotlin
package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class CompletionCacheTest {
    @Test
    fun `should return cached value`() {
        val cache = CompletionCache()
        val candidates = listOf("line1", "line2")
        cache.put("prefix", "suffix", candidates)
        val result = cache.get("prefix", "suffix")
        assertNotNull(result)
        assertEquals(candidates, result)
    }

    @Test
    fun `should return null on cache miss`() {
        val cache = CompletionCache()
        val result = cache.get("unknown", "unknown")
        assertNull(result)
    }

    @Test
    fun `should evict by LRU`() {
        val cache = CompletionCache(ttlMs = 60000, maxSize = 3)
        for (i in 1..5) {
            cache.put("prefix$i", "suffix", listOf("line$i"))
        }
        assertNull(cache.get("prefix1", "suffix")) // 被 LRU 淘汰
        assertNotNull(cache.get("prefix5", "suffix"))
    }
}
```

- [ ] **Step 4: 运行全部测试**

```bash
./gradlew test
```

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/aiassistant/completion/
git commit -m "test(completion): 添加补全模块单元测试"
```

---

### Task 13: 快捷键冲突检测与集成布线

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/completion/CompletionProvider.kt`
- Create: `src/main/kotlin/com/aiassistant/completion/ShortcutConflictDetector.kt`

- [ ] **Step 1: 创建 ShortcutConflictDetector**

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.keymap.KeymapManager
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 检测快捷键是否与 IDE 已有快捷键冲突。
 */
object ShortcutConflictDetector {

    data class ConflictResult(
        val isConflict: Boolean,
        val conflictActionName: String? = null
    )

    /**
     * 检测指定 KeyStroke 是否与已注册的快捷键冲突。
     * @return ConflictResult — isConflict=true 时 conflictActionName 为冲突的 Action 名称
     */
    fun checkConflict(keyStroke: KeyStroke): ConflictResult {
        val activeKeymap = KeymapManager.getInstance().activeKeymap
        val actionIds = activeKeymap.getActionIds(keyStroke)
        if (actionIds.isNullOrEmpty()) return ConflictResult(false)

        // 获取第一个冲突的 Action 名称
        val firstActionId = actionIds[0]
        val actionName = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction(firstActionId)?.templatePresentation?.text ?: firstActionId

        return ConflictResult(true, actionName)
    }

    /** 将设置中的快捷键字符串转为 KeyStroke */
    fun parseShortcutString(shortcut: String): KeyStroke? {
        return try {
            KeyStroke.getKeyStroke(shortcut)
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 2: 在 CompletionProvider 中集成快捷键冲突检测与手动触发 Action**

在 `completion` 包下创建手动触发 Action：

```kotlin
package com.aiassistant.completion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.aiassistant.AppSettingsService

class ManualCompletionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        InlineCompletion.getInstance(editor)?.show()
    }
}
```

- [ ] **Step 3: 在 plugin.xml 中注册手动补全 Action**

在 `<actions>` 标签中添加（指定默认快捷键）：

```xml
        <action
            id="AiAssistant.ManualCompletion"
            class="com.aiassistant.completion.ManualCompletionAction"
            text="手动触发 AI 补全"
            description="触发 AI 代码补全">
            <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta P"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta P"/>
            <keyboard-shortcut keymap="$default" first-keystroke="alt P"/>
        </action>
```

- [ ] **Step 4: 设置页面快捷键冲突提示**

在 SettingsConfigurable 中修改快捷键设置（如果后续加了自定义快捷键输入框），在保存前调用 `ShortcutConflictDetector.checkConflict()`。当前设计通过 Keymap 系统管理快捷键，冲突检测由 IDE Keymap 自动处理。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aiassistant/completion/ShortcutConflictDetector.kt \
        src/main/kotlin/com/aiassistant/completion/ManualCompletionAction.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(completion): 添加快捷键冲突检测和手动补全 Action"
```

---

### Task 14: 构建插件并验证

```bash
./gradlew buildPlugin
```

- [ ] **Step 2: 启动 sandbox IDE 手动验证**

```bash
./gradlew runIde
```

验证清单：
1. 打开任意 PHP/JS/HTML 文件，输入代码 → 等待防抖 → 观察幽灵文本是否出现
2. 按 Tab → 确认幽灵文本被接受
3. 按 ↑/↓ → 确认候选切换
4. 按 Esc → 确认补全取消
5. 手动快捷键（Mac: `Cmd+P` / Win: `Alt+P`）→ 确认立即触发
6. 关闭补全开关 → 确认 IDEA 原生补全恢复
7. 打开设置 → 确认统计卡片有数据
8. 关闭/重开 IDE → 确认统计清零

- [ ] **Step 3: Commit（如有调整）**

```bash
git add -A
git commit -m "chore(completion): 构建验证与微调"
```
