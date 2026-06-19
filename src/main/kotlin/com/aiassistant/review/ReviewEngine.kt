package com.aiassistant.review

import com.aiassistant.AnthropicMessage
import com.aiassistant.AnthropicSdkClient
import com.aiassistant.AnthropicToolDef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReviewEngine(private val projectBasePath: String?) {

    private val collector = DiffCollector(projectBasePath)
    private val analyzer = ReviewAnalyzer()
    val fixApplier = FixApplier()
    val commentFormatter = CommentFormatter()

    data class ReviewResult(val findings: List<Finding>, val score: Int, val totalFiles: Int)

    fun review(apiKey: String): ReviewResult? {
        val diff = collector.collectBranchDiff()?.takeIf { it.isNotBlank() } ?: return null
        return analyze(apiKey, diff)
    }

    fun reviewFile(apiKey: String, filePath: String): ReviewResult? {
        val diff = collector.collectFileDiff(filePath)?.takeIf { it.isNotBlank() } ?: return null
        return analyze(apiKey, diff)
    }

    fun analyze(apiKey: String, diff: String): ReviewResult {
        val fileChanges = collector.parse(diff).filter { !it.isBinary }
        val prompt = analyzer.buildPrompt(fileChanges)
        val findings = callLLM(apiKey, prompt)
        val score = calculateScore(findings)
        return ReviewResult(findings, score, fileChanges.size)
    }

    private fun callLLM(apiKey: String, prompt: String): List<Finding> {
        return try {
            val client = AnthropicSdkClient(apiKey)
            val latch = CountDownLatch(1)
            var resultText = ""
            client.createStreaming(
                model = com.aiassistant.AppSettingsService.getInstance().getModel() ?: "deepseek-chat",
                systemPrompt = "",
                messages = listOf(AnthropicMessage("user", prompt)),
                tools = emptyList(),
                thinkingEnabled = false,
                callback = object : AnthropicSdkClient.Callback {
                    override fun onTextDelta(fullText: String) {
                        resultText = fullText
                    }

                    override fun onStreamComplete(
                        textContent: String,
                        thinking: String,
                        thinkingSignature: String,
                        toolCalls: List<AnthropicSdkClient.StreamToolCall>,
                        inputTokens: Int,
                        outputTokens: Int,
                        stopReason: String
                    ) {
                        resultText = textContent
                        latch.countDown()
                    }

                    override fun onError(error: Throwable) {
                        latch.countDown()
                    }

                    override fun onThinkingDelta(fullThinking: String) {}
                    override fun onToolUseStart(id: String, name: String) {}
                    override fun onToolInputDelta(partial: String) {}
                }
            )
            latch.await(60, TimeUnit.SECONDS)
            analyzer.parseResult(resultText)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun calculateScore(findings: List<Finding>): Int {
        if (findings.isEmpty()) return 100
        val criticals = findings.count { it.severity == Severity.CRITICAL }
        val warnings = findings.count { it.severity == Severity.WARNING }
        return (100 - criticals * 15 - warnings * 5).coerceAtLeast(0)
    }

    fun getDiffStat(): String = collector.collectDiffStat() ?: "无法获取 diff"
}
