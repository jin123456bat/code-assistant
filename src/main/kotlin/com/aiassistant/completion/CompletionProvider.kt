package com.aiassistant.completion

import com.aiassistant.AppLogger
import com.aiassistant.AppSettingsService
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 对模型返回的所有候选文本做清理和验证。返回有效候选列表（无重复）。
 */
object CompletionPostProcessor {

    /**
     * @param choices 模型返回的原始候选列表
     * @param prefix 光标前的文本，用于裁剪开头重叠
     * @param suffix 光标后的文本，用于裁剪结尾重叠
     * @return 去重后的有效补全候选文本列表
     */
    fun process(choices: List<DeepSeekFimClient.FimChoice>, prefix: String, suffix: String): List<String> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()

        for (choice in choices) {
            // 内容过滤的候选直接跳过
            if (choice.finishReason == "content_filter") continue

            var text = choice.text

            // suffix 重叠裁剪：去掉补全文本末尾与 suffix 开头重叠的部分
            text = trimSuffixOverlap(text, suffix)

            // prefix 开头重叠裁剪：去掉补全文本开头与 prefix 结尾重叠的部分
            text = trimPrefixOverlap(text, prefix)

            // finish_reason == "length" → 截断到最后一个完整行，避免半行代码
            if (choice.finishReason == "length") {
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline > 0) text = text.substring(0, lastNewline)
            }

            // 有效性过滤
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            // 如果补全内容和 suffix 开头完全相同则无意义
            if (trimmed == suffix.take(trimmed.length).trim()) continue
            if (seen.contains(trimmed)) continue

            seen.add(trimmed)
            results.add(trimmed)
        }

        return results
    }

    /**
     * 裁剪补全文本末尾与 [suffix] 开头的重叠部分。
     * 例如 text="hello world", suffix="world!" → 返回 "hello "
     */
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

    /**
     * 裁剪补全文本开头与 [prefix] 结尾的重叠部分。
     * 例如 text="world!", prefix="hello world" → 返回 "!"
     */
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
    private val fimClient = DeepSeekFimClient(settings)
    private val cache = CompletionCache()

    init {
        // 注册 project 关闭监听，自动持久化统计数据到 .claude/completion-stats.json
        @Suppress("DEPRECATION")
        ProjectManager.getInstance().addProjectManagerListener(object : ProjectManagerListener {
            override fun projectClosing(project: Project) {
                val projectPath = project.basePath ?: return
                CompletionStats.persist(projectPath)
            }
        })
    }

    /** 缓存当前补全请求的语言标识，供 afterInsertion 回调使用 */
    @Volatile
    private var currentLanguage: String = "unknown"

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

            candidates = CompletionPostProcessor.process(response.choices, context.prefix, context.suffix)

            if (candidates.isNotEmpty()) {
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

        // 构建补全 Flow：将每个候选文本包装为 InlineCompletionGrayTextElement
        val elementsFlow: Flow<InlineCompletionElement> = flow {
            for (candidate in candidates) {
                emit(InlineCompletionGrayTextElement(candidate))
            }
        }

        return buildSuggestion {
            elementsFlow.collect { element -> emit(element) }
        }
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
                CompletionStats.recordAccepted(currentLanguage)
            }
        }

    /** 取消进行中的 FIM 请求 */
    fun cancelPendingRequest() {
        fimClient.cancel()
    }

    /**
     * 跨 IntelliJ 版本兼容的 empty() 调用。
     * 2025.1+ 移除了 InlineCompletionSuggestion.Companion.empty()，回退到反射。
     */
    private fun emptySuggestion(): InlineCompletionSuggestion {
        try {
            return InlineCompletionSuggestion.Companion.empty()
        } catch (_: NoSuchMethodError) {
            try {
                val companion = InlineCompletionSuggestion::class.java
                    .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
                val method = companion.javaClass.getMethod("empty")
                @Suppress("UNCHECKED_CAST")
                return method.invoke(companion) as InlineCompletionSuggestion
            } catch (e: Exception) {
                return buildSuggestion { }
            }
        }
    }

    /**
     * 跨 IntelliJ 版本兼容的 withFlow() 调用。
     * 2025.1+ 移除了 withFlow()，改为 InlineCompletionSingleSuggestion.build()。
     */
    private fun buildSuggestion(block: suspend kotlinx.coroutines.flow.FlowCollector<InlineCompletionElement>.() -> Unit): InlineCompletionSuggestion {
        try {
            @Suppress("DEPRECATION")
            return InlineCompletionSuggestion.Companion.withFlow(block)
        } catch (_: NoSuchMethodError) {
            try {
                val cls =
                    Class.forName("com.intellij.codeInsight.inline.completion.InlineCompletionSingleSuggestion")
                val companionField = cls.getDeclaredField("Companion").apply { isAccessible = true }
                val companion = companionField.get(null)
                val buildMethod =
                    companion.javaClass.methods.first { it.name == "build" && it.parameterCount == 1 }
                @Suppress("UNCHECKED_CAST")
                return buildMethod.invoke(companion, block) as InlineCompletionSuggestion
            } catch (e: Exception) {
                throw RuntimeException("Cannot create InlineCompletionSuggestion", e)
            }
        }
    }
}
