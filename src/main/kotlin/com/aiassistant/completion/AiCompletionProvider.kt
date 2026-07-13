package com.aiassistant.completion

import com.aiassistant.AppLogger
import com.aiassistant.AppSettingsService
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 补全 Provider，实现 IntelliJ Inline Completion API。
 * 负责上下文采集、缓存、FIM 请求、后处理、统计数据收集。
 *
 * 注册方式：在 plugin.xml 中添加：
 * ```xml
 * <extensions defaultExtensionNs="com.intellij">
 *     <inline.completion.provider
 *         id="ai-assistant"
 *         implementation="com.aiassistant.completion.AiCompletionProvider"/>
 * </extensions>
 * ```
 */
class AiCompletionProvider : InlineCompletionProvider {

    private val settings = AppSettingsService.getInstance()
    private val fimClient: DeepSeekFimClient
        get() = ApplicationManager.getApplication().service<CompletionFimService>().client
    private val cache = CompletionCache()

    /** 缓存当前补全请求的语言标识，供 afterInsertion 回调使用 */
    @Volatile
    private var currentLanguage: String = "unknown"

    /**
     * 上一次返回给 IDE 的补全候选的 prefix/suffix 快照，用于检测用户拒绝。
     * IntelliJ InlineCompletionInsertHandler 只有 afterInsertion 回调（接受），
     * 没有原生 rejection 回调，因此通过对比两次 getSuggestion 之间是否发生
     * afterInsertion 来推断：如果上次返回了候选但本次 getSuggestion 时
     * afterInsertion 未被调用，说明用户拒绝了上次的补全。
     */
    @Volatile
    private var lastSuggestionPrefix: String? = null

    @Volatile
    private var lastSuggestionSuffix: String? = null

    @Volatile
    private var lastSuggestionAccepted: Boolean = false

    /** Provider 唯一标识（Kotlin inline class，JVM 层面 getter 名为 getId-S2YkoFA） */
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("ai-assistant")

    /** 只对文档变更事件（自动补全）和手动触发事件启用 */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return settings.isCompletionEnabled()
    }

    /**
     * 核心补全方法（suspend 函数）。IntelliJ 的 Inline Completion API 基于协程，
     * 通过返回 [InlineCompletionSuggestion]（内含 Flow）来提供补全元素。
     */
    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val project = editor.project

        if (project == null || !settings.isCompletionEnabled()) {
            return emptySuggestion()
        }
        project.service<CompletionStatsProjectService>()

        // === 补全拒绝检测 ===
        // 如果上一次返回了补全候选但 afterInsertion 未被调用，
        // 说明用户拒绝了上次的补全（继续输入而非按 Tab 接受）。
        // 此时触发缓存失效和统计记录。
        val prevPrefix = lastSuggestionPrefix
        val prevSuffix = lastSuggestionSuffix
        if (prevPrefix != null && prevSuffix != null && !lastSuggestionAccepted) {
            cache.remove(prevPrefix, prevSuffix)
            CompletionStats.recordRejected(currentLanguage)
        }

        // 上下文采集
        val collector = CompletionContextCollector()
        val context = collector.collect(editor, project)

        // 缓存当前语言，供 afterInsertion 回调传递正确的语言标识
        currentLanguage = context.language

        // 判断是否为手动触发（通过 DirectCall 事件类型）
        // IntelliJ 2023.3: InlineCompletionEvent.DirectCall 表示手动触发（如快捷键）
        val manualTrigger = request.event is InlineCompletionEvent.DirectCall

        // 检查缓存（手动触发时跳过缓存）
        val cachedCandidates: List<String>? = if (manualTrigger) {
            null
        } else {
            cache.get(context.prefix, context.suffix)
        }

        val candidates: List<String>
        val startTime = System.currentTimeMillis()

        if (cachedCandidates != null) {
            candidates = cachedCandidates
            CompletionStats.recordShown(context.language, 0)
        } else {
            // 构建 FIM prompt：文件路径 + 语言 + smartContext（PSI 增强） + prefix
            val prompt = buildString {
                append("// File: ${context.fileName}\n")
                append("// Language: ${context.language}\n")
                append("\n")
                if (!context.smartContext.isNullOrBlank()) {
                    append(context.smartContext)
                    append("\n")
                }
                append(context.prefix)
            }

            // 空 suffix 传 null，避免 API 收到空字符串
            val suffix: String? = context.suffix.ifBlank { null }

            val response = try {
                // 请求级超时：1s + 2 次重试（400ms）= 1.4s，超过则视为不可接受
                // Kotlin 协程中无法直接用 withTimeout（suspend 在 EDT 线程池），
                // 改用 OkHttp call.timeout() 在 FimClient 层收紧
                fimClient.complete(prompt, suffix)
            } catch (e: Exception) {
                AppLogger.requestFailed(0, "FIM API error: ${e.message}")
                return emptySuggestion()
            }

            if (response == null || response.choices.isNullOrEmpty()) {
                if (response == null) AppLogger.requestFailed(
                    0,
                    "FIM response null — check API Key"
                )
                return emptySuggestion()
            }

            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            val codeStyleSettings: CommonCodeStyleSettings.IndentOptions? = if (psiFile != null) {
                CodeStyleSettingsManager.getInstance(project).currentSettings.getIndentOptions(
                    psiFile.fileType
                )
            } else {
                null
            }
            candidates = CompletionPostProcessor.process(
                response.choices, context.prefix, context.suffix, codeStyleSettings
            )

            if (!manualTrigger && candidates.isNotEmpty()) {
                cache.put(context.prefix, context.suffix, candidates)
            }

            val latency = System.currentTimeMillis() - startTime
            if (candidates.isNotEmpty()) {
                CompletionStats.recordShown(context.language, latency)
            }
        }

        if (candidates.isEmpty()) {
            return emptySuggestion()
        }

        // 记录本次返回的候选位置，用于下次 getSuggestion 时检测用户是否拒绝
        lastSuggestionPrefix = context.prefix
        lastSuggestionSuffix = context.suffix
        lastSuggestionAccepted = false

        // 单候选不安装方向键动作，避免无意义地介入 IntelliJ 原生光标移动。
        if (candidates.size > 1) {
            withContext(Dispatchers.EDT) {
                FimCandidateShortcutBinding.ensureRegistered(editor)
            }
        }
        return buildInlineCompletionSuggestion(candidates)
    }

    /**
     * 插入处理器：补全被接受时记录统计。
     * IntelliJ 2023.3 中 val insertHandler 在接口中有默认实现，此处重写以添加统计逻辑。
     */
    override val insertHandler: InlineCompletionInsertHandler
        get() = object : InlineCompletionInsertHandler {
            override fun afterInsertion(
                environment: InlineCompletionInsertEnvironment,
                elements: List<InlineCompletionElement>
            ) {
                lastSuggestionAccepted = true
                CompletionStats.recordAccepted(currentLanguage)
            }
        }

    /** 取消进行中的 FIM 请求 */
    fun cancelPendingRequest() {
        fimClient.cancel()
    }

    private fun emptySuggestion(): InlineCompletionSuggestion {
        return InlineCompletionSuggestion.Empty
    }
}

/**
 * 每个候选必须对应一个独立 variant，方向键切换的是 variant，而不是同一 variant 中的元素。
 */
internal fun buildInlineCompletionSuggestion(candidates: List<String>): InlineCompletionSuggestion {
    val variants = candidates
        .take(InlineCompletionSuggestion.MAX_VARIANTS_NUMBER)
        .map { candidate ->
            InlineCompletionVariant.build {
                emit(InlineCompletionGrayTextElement(candidate))
            }
        }
    return object : InlineCompletionSuggestion {
        override suspend fun getVariants(): List<InlineCompletionVariant> = variants
    }
}
