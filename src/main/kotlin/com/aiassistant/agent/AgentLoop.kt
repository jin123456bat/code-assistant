package com.aiassistant.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.*
import com.aiassistant.AppSettingsService
import com.aiassistant.i18n.I18n
import com.intellij.openapi.project.Project
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.UnauthorizedException
import java.io.File
import java.net.SocketTimeoutException

class AgentLoop(
    private val project: Project,
    private val session: AgentSession,
    private val apiKeyProvider: () -> String? = { AppSettingsService.getInstance().getApiKey() },
    private val modelProvider: () -> String = { AppSettingsService.getInstance().getModel() }
) {
    /** 模型上下文窗口大小（DeepSeek V4 的 1M tokens 上限），写死不动态检测 */
    val modelContextLimit: Int = 1_000_000

    /** 触发压缩的上下文使用率阈值 */
    val compactThreshold: Double = 0.7

    /**
     * 最大轮次，默认 20。每轮 = 一次用户消息触发的 API 调用。
     * 0 时内部转为 Int.MAX_VALUE（不限轮次）。
     * 对齐 docs/agent/loop.md §一、§六、docs/specs/settings.md §一
     */
    var maxTurns: Int = 20

    /**
     * max_tokens 自动续写上限，不限次数，直到 LLM 返回 end_turn。
     * end_turn 后 continueStreak 计数器重置。
     * 对齐 docs/agent/loop.md §六。
     */
    val maxAutoContinue: Int = Int.MAX_VALUE

    /** max_tokens 时自动发送的续写消息。对齐 docs/agent/loop.md §六 */
    val autoContinueMessage: String = "继续"

    /** 当前轮内续写链已执行次数。仅 max_tokens 累加，其余退出路径归零。对齐 docs/agent/loop.md §一 */
    private var continueStreak: Int = 0

    /** 轮次预警触发比例。对齐 docs/agent/loop.md §一、§六 */
    val turnWarningRatio: Double = 0.6

    /**
     * 轮次预警文本模板。%1$d = 当前轮次，%2$d = 最大轮次上限。
     * 对齐 docs/agent/loop.md §一
     */
    val turnWarningMessage: String =
        "已执行 %1\$d 轮（共 %2\$d 轮上限），请评估剩余工作量，若任务复杂建议使用 /plan 拆分"

    private var client: AnthropicClient? = null
    private var clientApiKey: String? = null

    private fun getClient(): AnthropicClient {
        val apiKey = apiKeyProvider() ?: throw IllegalStateException("API Key not configured")
        val existing = client
        if (existing != null && clientApiKey == apiKey) return existing
        return AnthropicOkHttpClient.builder()
            .baseUrl("https://api.deepseek.com/anthropic")
            .apiKey(apiKey)
            .build()
            .also {
                client = it
                clientApiKey = apiKey
            }
    }

    private val model: String get() = modelProvider()
    private val toolExecutor = ToolExecutor(project, session)

    /**
     * 子 Agent 工具过滤器。
     * 非 null 时 run() 仅注入此列表中的工具（替代 ToolRegistry.listAll()）。
     * 由 MultiAgentManager 在 spawn 子 Agent 时设置。
     * 对齐 docs/agent/multi-agent.md §三 工具白名单。
     */
    var toolsFilter: List<Class<*>>? = null

    // ── 回调 ──
    var onToken: ((String) -> Unit)? = null
    var onReasoningContent: ((String) -> Unit)? = null
    var onToolCall: ((toolUseId: String, toolName: String, params: Map<String, Any?>) -> Unit)? =
        null
    var onToolCallStateChanged: ((toolUseId: String, state: ToolCallState, result: String?, durationMs: Long?) -> Unit)? =
        null
    var onApprovalRequested: ((ToolApprovalRequest) -> Unit)? = null
    var onTurnCompleted: (() -> Unit)? = null
    var onSubAgentEvent: ((MultiAgentManager.SubAgentEvent) -> Unit)? = null

    /**
     * 同一 tool call 连续失败跟踪，用于连续错误升级规则。
     * 对齐 docs/agent/loop.md §三：同一 tool call 连续 3 次失败 → 提示跳过。
     */
    private var lastFailedToolUseId: String? = null
    private var consecutiveToolFailureCount: Int = 0

    /**
     * 标记错误并触发连续错误升级检测。
     * 对齐 docs/agent/loop.md §三：
     * - 同一 Session 内累计 5 个 ERROR → 提示使用 /plan 拆分任务
     * - session.errorCount 在 markError 中自增
     *
     * @return 增强后的错误消息（如果触发升级则包含建议）
     */
    private fun markAndCheckErrorUpgrade(message: String): String {
        session.markError(message)
        return if (session.errorCount >= 5) {
            "$message\n\n已出现多个错误（累计 ${session.errorCount} 个），建议使用 /plan 拆分任务"
        } else {
            message
        }
    }

    /**
     * 构建 Result.Error 并触发错误升级检测。
     * 对齐 docs/agent/loop.md §三：累计5个以上 ERROR 则附加建议。
     */
    private fun buildErrorResult(logMessage: String, returnMessage: String): Result.Error {
        val enhancedLog = markAndCheckErrorUpgrade(logMessage)
        val finalMessage = if (session.errorCount >= 5) {
            "$returnMessage\n\n建议使用 /plan 拆分任务"
        } else {
            returnMessage
        }
        return Result.Error(finalMessage)
    }

    init {
        toolExecutor.onToolStateChanged = { toolUseId, state, result, durationMs ->
            onToolCallStateChanged?.invoke(toolUseId, state, result, durationMs)
        }
        toolExecutor.onApprovalRequested = { request ->
            onApprovalRequested?.invoke(request)
        }
        toolExecutor.onSubAgentEvent = { event ->
            onSubAgentEvent?.invoke(event)
        }
    }

    fun close() {
        session.cancel()
    }

    enum class AgentMode { CHAT, AGENT, PLAN }

    /**
     * 执行 Agent 主循环。
     * 对齐 docs/agent/images.md §二：图片作为独立 image content block 与 text block 并列。
     * 对齐 docs/specs/system-prompt.md §8.2：/command Skill 正文注入 buildSystemPrompt。
     *
     * @param userMessage 用户文本消息
     * @param images 图片列表（按粘贴顺序），组装为 text block 在前、image blocks 在后
     * @param slashCommand 用户输入的 /command（如 "/review"），用于注入对应 SKILL.md 正文；null 表示非 /command
     * @param mode Agent 运行模式
     */
    fun run(
        userMessage: String,
        images: List<ImageRef> = emptyList(),
        slashCommand: String? = null,
        mode: AgentMode = AgentMode.AGENT
    ): Result {
        if (session.state in setOf(
                AgentSession.State.PROCESSING,
                AgentSession.State.EXECUTING,
                AgentSession.State.AWAITING_APPROVAL
            )
        ) {
            return Result.Error("Agent is already running")
        }
        val client = try {
            getClient()
        } catch (e: IllegalStateException) {
            return Result.Error("请先配置 DeepSeek API Key")
        }

        val builder = buildRequestBuilder(userMessage, images, slashCommand, mode)

        session.startProcessing()
        val output = StringBuilder()
        val reasoningOutput = StringBuilder()
        var turn = 0
        // 对齐 docs/agent/loop.md §一：0 代表不限轮次，内部转为 Int.MAX_VALUE
        val effectiveMaxTurns = if (maxTurns == 0) Int.MAX_VALUE else maxTurns
        var lastStopReason = ""
        var isMaxTokensContinue = false  // 标记当前循环是否为 max_tokens 续写
        var retryCount = 0
        var messageAlreadyPersisted =
            false  // stop_sequence 在 AgentLoop 内持久化后标记，避免 ChatViewModel 重复写入

        try {
            while (
                !session.cancelled &&
                turn < effectiveMaxTurns &&
                continueStreak <= maxAutoContinue
            ) {
                // 每个 turn 开始时清空 filesReadThisTurn 和 filesModifiedThisTurn
                // filesReadThisTurn：确保 Edit/Write 前置 Read 校验按 turn 粒度生效（对齐 docs/agent/tools.md §七）
                // filesModifiedThisTurn：大范围修改审批检测（同一 turn ≥5 个文件）（对齐 docs/agent/tools.md §六）
                session.filesReadThisTurn.clear()
                session.filesModifiedThisTurn.clear()

                // 轮次预警：当 turn >= effectiveMaxTurns * turnWarningRatio 时附加系统提示
                // effectiveMaxTurns == Int.MAX_VALUE（不限轮次）时跳过预警
                // 对齐 docs/agent/loop.md §一
                if (effectiveMaxTurns != Int.MAX_VALUE && turn >= effectiveMaxTurns * turnWarningRatio) {
                    builder.addUserMessage(
                        String.format(turnWarningMessage, turn, effectiveMaxTurns)
                    )
                }

                // 在每次 while 循环开始时检查是否需要 compact，超阈值则压缩 messages
                // 对齐 docs/agent/loop.md §一：估算实际总 token，超 modelLimit × 0.7 阈值则压缩 messages
                compactIfNeeded(builder, mode)

                val turnOutput = StringBuilder()
                val accumulator = BetaMessageAccumulator.create()

                try {
                    client.beta().messages().createStreaming(builder.build()).use { stream ->
                        stream.stream().forEach { event ->
                            accumulator.accumulate(event)

                            // reasoning_content（思考过程）
                            if (event.isContentBlockDelta()) {
                                val delta = event.asContentBlockDelta()
                                val contentDelta = delta.delta()
                                // 优先处理 reasoning_content
                                contentDelta.thinking().ifPresent { thinking ->
                                    reasoningOutput.append(thinking.thinking())
                                    onReasoningContent?.invoke(thinking.thinking())
                                }
                                // 再处理普通文本
                                contentDelta.text().ifPresent { text ->
                                    turnOutput.append(text.text())
                                    onToken?.invoke(text.text())
                                }
                            } else if (event.isContentBlockStart()) {
                                val start = event.asContentBlockStart()
                                start.contentBlock().toolUse().ifPresent { toolUse ->
                                    onToolCall?.invoke(
                                        toolUse.id(),
                                        toolUse.name(),
                                        ToolInput.map(toolUse._input())
                                    )
                                }
                            } else if (event.isMessageDelta()) {
                                event.asMessageDelta().delta().stopReason().ifPresent {
                                    lastStopReason = it.toString()
                                }
                            }
                        }
                    }

                    output.append(turnOutput)
                    val assistantMessage = accumulator.message()
                    // 从 API usage 返回值中提取并累加 token 数
                    // 对齐 docs/agent/loop.md §七 totalTokens: TokenUsage
                    assistantMessage.usage().let { usage ->
                        session.totalTokens = TokenUsage(
                            inputTokens = session.totalTokens.inputTokens + usage.inputTokens(),
                            outputTokens = session.totalTokens.outputTokens + usage.outputTokens(),
                            timestamp = java.time.Instant.now()
                        )
                    }
                    val toolUses =
                        assistantMessage.content().mapNotNull { it.toolUse().orElse(null) }
                    lastStopReason =
                        assistantMessage.stopReason().map { it.toString() }.orElse(lastStopReason)

                    // ── stop_reason 分叉 ──
                    // 对齐 docs/agent/loop.md §一：
                    //   "end_turn"      → continueStreak=0, 退出 while（等待用户下一条消息）
                    //   "max_tokens"    → continueStreak++, 追加临时"继续"消息（不持久化），循环继续（续写不增加 turn）
                    //   "stop_sequence" → continueStreak=0, 退出 while，在 assistant 消息尾部追加标注
                    //   无 tool_use      → 退出循环
                    when {
                        lastStopReason.contains("end_turn") -> {
                            continueStreak = 0
                            if (toolUses.isEmpty()) break
                        }

                        lastStopReason.contains("max_tokens") -> {
                            continueStreak++
                            if (continueStreak > maxAutoContinue) break
                            // 追加临时"继续"消息（不持久化到 session.messages），循环继续（续写不增加 turn）
                            builder.addUserMessage(autoContinueMessage)
                            isMaxTokensContinue = true
                            continue
                        }

                        lastStopReason.contains("stop_sequence") -> {
                            continueStreak = 0
                            // 在 assistant 消息尾部追加系统标注
                            val annotatedText =
                                turnOutput.toString() + "\n[响应被 stop_sequence 终止]"
                            output.append("\n[响应被 stop_sequence 终止]")
                            // 持久化到 session.messages，不依赖调用方通过 Result.Success.text 间接写入
                            // 对齐 docs/agent/loop.md §一
                            session.addMessage(
                                com.aiassistant.agent.Message(
                                    role = com.aiassistant.agent.Role.ASSISTANT,
                                    content = annotatedText
                                )
                            )
                            messageAlreadyPersisted = true
                            break
                        }

                        toolUses.isEmpty() -> {
                            continueStreak = 0
                            break
                        }
                    }

                    // 工具执行：同一轮中按 LLM 返回的顺序串行执行，不并行。
                    // 前一个工具的结果会立即追加到 params.messages，后续工具可以看到前面工具的执行结果。
                    // 对齐 docs/agent/loop.md §一
                    builder.addMessage(assistantMessage)
                    session.startExecuting()

                    for (toolUse in toolUses) {
                        val rawResult = toolExecutor.execute(toolUse)

                        // Skill 工具正文注入到 conversation 的消息列表中
                        // 对齐 docs/agent/skills.md §五：Skill 工具执行后正文应作为消息注入 conversation
                        if (toolUse.name() == "Skill" && !rawResult.startsWith("错误:")) {
                            session.addMessage(
                                com.aiassistant.agent.Message(
                                    role = com.aiassistant.agent.Role.SYSTEM,
                                    content = rawResult
                                )
                            )
                        }

                        // ── 连续错误升级规则：同一 tool call 连续失败跟踪
                        // 对齐 docs/agent/loop.md §三：同一 tool call 连续 3 次失败 → 提示跳过
                        val isToolError = rawResult.startsWith("错误:")
                        if (isToolError) {
                            if (toolUse.id() == lastFailedToolUseId) {
                                consecutiveToolFailureCount++
                            } else {
                                lastFailedToolUseId = toolUse.id()
                                consecutiveToolFailureCount = 1
                            }
                        } else {
                            // 成功执行，重置跟踪
                            if (toolUse.id() == lastFailedToolUseId) {
                                lastFailedToolUseId = null
                                consecutiveToolFailureCount = 0
                            }
                        }

                        // 同一 tool call 连续 3 次失败：提示跳过
                        val finalResult =
                            if (consecutiveToolFailureCount >= 3 && toolUse.id() == lastFailedToolUseId) {
                                lastFailedToolUseId = null
                                consecutiveToolFailureCount = 0
                                "$rawResult\n\n该操作连续失败 3 次，建议跳过或手动处理"
                            } else {
                                rawResult
                            }

                        // 检测图片结果标记 __IMAGE_RESULT__: 前缀
                        // 对齐 docs/agent/images.md §三：Read 工具读图片时返回 image content block
                        if (finalResult.startsWith(ToolExecutor.IMAGE_RESULT_PREFIX)) {
                            val imageData =
                                finalResult.removePrefix(ToolExecutor.IMAGE_RESULT_PREFIX)
                            val colonIdx = imageData.indexOf(':')
                            if (colonIdx > 0) {
                                val mimeType = imageData.substring(0, colonIdx)
                                val base64Data = imageData.substring(colonIdx + 1)
                                val mediaType = when (mimeType) {
                                    "image/jpeg" -> BetaBase64ImageSource.MediaType.IMAGE_JPEG
                                    "image/gif" -> BetaBase64ImageSource.MediaType.IMAGE_GIF
                                    "image/webp" -> BetaBase64ImageSource.MediaType.IMAGE_WEBP
                                    else -> BetaBase64ImageSource.MediaType.IMAGE_PNG
                                }
                                val imageSource = BetaBase64ImageSource.builder()
                                    .data(base64Data)
                                    .mediaType(mediaType)
                                    .build()
                                val imageBlock = BetaImageBlockParam.builder()
                                    .source(BetaImageBlockParam.Source.ofBase64(imageSource))
                                    .build()
                                val imageContentBlock =
                                    BetaToolResultBlockParam.Content.Block.ofImage(imageBlock)
                                val imageContent = BetaToolResultBlockParam.Content.ofBlocks(
                                    listOf(imageContentBlock)
                                )
                                // 立即追加当前工具结果到 params.messages，后续工具可看到前面的结果
                                // 对齐 docs/agent/loop.md §一
                                builder.addUserMessageOfBetaContentBlockParams(
                                    listOf(
                                        BetaContentBlockParam.ofToolResult(
                                            BetaToolResultBlockParam.builder()
                                                .toolUseId(toolUse.id())
                                                .content(imageContent)
                                                .build()
                                        )
                                    )
                                )
                                continue
                            }
                        }

                        // 立即追加当前工具结果到 params.messages，后续工具可看到前面的结果
                        // 对齐 docs/agent/loop.md §一
                        builder.addUserMessageOfBetaContentBlockParams(
                            listOf(
                                BetaContentBlockParam.ofToolResult(
                                    BetaToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .contentAsJson(finalResult)
                                        .build()
                                )
                            )
                        )
                    }

                    session.doneExecuting()

                    // 仅用户消息触发的 API 调用计数，续写（max_tokens）不增加 turn
                    // 对齐 docs/agent/loop.md §一
                    if (!isMaxTokensContinue) {
                        turn++
                    }
                    isMaxTokensContinue = false  // 重置标记
                    retryCount = 0 // 成功一轮，重置重试计数
                } catch (e: Exception) {
                    // 保留已接收文本
                    output.append(turnOutput)

                    when {
                        // 429 Rate Limit — 连续 3 次后建议降低并发或切换模型
                        // 对齐 docs/agent/loop.md §三：连续 3 次 429 → Agent → PAUSED + 建议降低并发
                        e is com.anthropic.errors.RateLimitException -> {
                            if (retryCount < 3) {
                                retryCount++
                                val waitSec = try {
                                    e.message?.let { msg ->
                                        Regex("(\\d+)\\s*(?:seconds|秒)").find(msg)?.groupValues?.get(
                                            1
                                        )
                                            ?.toLongOrNull()
                                    } ?: 10L
                                } catch (_: Exception) {
                                    10L
                                }
                                session.pause()
                                Thread.sleep(waitSec * 1000)
                                session.resume()
                                continue
                            } else {
                                session.pause()
                                return buildErrorResult(
                                    logMessage = "429 Rate Limit — 已连续 ${retryCount} 次触发速率限制，建议降低并发或切换模型",
                                    returnMessage = "429 速率限制 — 建议降低并发或切换模型"
                                )
                            }
                        }
                        // 400 context-too-long — 不重试，强制触发 compact 后重发
                        // 对齐 docs/agent/loop.md §三：请求体过大 → 强制触发 compact
                        e is com.anthropic.errors.BadRequestException -> {
                            val isContextTooLong =
                                e.message?.contains("context", ignoreCase = true) == true ||
                                        e.message?.contains(
                                            "too long",
                                            ignoreCase = true
                                        ) == true ||
                                        e.message?.contains("token", ignoreCase = true) == true
                            if (isContextTooLong) {
                                val compacted = forceCompact(builder, mode)
                                if (compacted) {
                                    continue
                                } else {
                                    return buildErrorResult(
                                        logMessage = "上下文过大且 compact 失败，无法继续",
                                        returnMessage = "上下文过大，compact 失败。建议使用 /plan 拆分任务或减少消息量"
                                    )
                                }
                            }
                            return buildErrorResult(
                                logMessage = e.message ?: "Bad Request",
                                returnMessage = e.message ?: "Bad Request"
                            )
                        }
                        // 服务器错误 5xx — 退避重试：1s → 3s → 9s，最多 3 次
                        // 对齐 docs/agent/loop.md §三：5xx 退避重试 3 次均失败 → ERROR
                        e is InternalServerException -> {
                            val maxServerRetries = 3
                            val backoffDelays = longArrayOf(1_000L, 3_000L, 9_000L)
                            if (retryCount < maxServerRetries) {
                                val delayMs = backoffDelays[retryCount]
                                retryCount++
                                Thread.sleep(delayMs)
                                continue
                            } else {
                                return buildErrorResult(
                                    logMessage = "服务器错误 (${e.statusCode()}) — 已重试 $maxServerRetries 次仍失败: ${e.message}",
                                    returnMessage = "服务器错误 (${e.statusCode()})，请稍后重试"
                                )
                            }
                        }
                        // 网络超时 — 退避重试：2s → 5s → 10s，最多 3 次
                        // 对齐 docs/agent/loop.md §三：网络错误最多自动重试 3 次，3 次均失败 → ERROR
                        e is SocketTimeoutException -> {
                            val maxNetworkRetries = 3
                            val backoffDelays = longArrayOf(2_000L, 5_000L, 10_000L)
                            if (retryCount < maxNetworkRetries) {
                                val delayMs = backoffDelays[retryCount]
                                retryCount++
                                Thread.sleep(delayMs)
                                continue
                            } else {
                                output.append("\n[连接中断]")
                                return buildErrorResult(
                                    logMessage = "连接中断 — 已重试 $maxNetworkRetries 次仍失败: ${e.message}",
                                    returnMessage = "网络连接中断，请点击重试恢复\n已接收文本: ${
                                        output.take(
                                            500
                                        )
                                    }"
                                )
                            }
                        }
                        // 通用网络错误（DNS/IO）— 退避重试：2s → 5s → 10s，最多 3 次
                        // 对齐 docs/agent/loop.md §三：网络错误最多自动重试 3 次
                        e is java.io.IOException -> {
                            val maxNetworkRetries = 3
                            val backoffDelays = longArrayOf(2_000L, 5_000L, 10_000L)
                            if (retryCount < maxNetworkRetries) {
                                val delayMs = backoffDelays[retryCount]
                                retryCount++
                                Thread.sleep(delayMs)
                                continue
                            } else {
                                output.append("\n[连接中断]")
                                return buildErrorResult(
                                    logMessage = "网络错误 — 已重试 $maxNetworkRetries 次仍失败: ${e.message}",
                                    returnMessage = "网络连接中断，请点击重试恢复\n已接收文本: ${
                                        output.take(
                                            500
                                        )
                                    }"
                                )
                            }
                        }
                        // 401 认证失败 — 不重试，状态 → ERROR，toast "API Key 无效"
                        // 对齐 docs/agent/loop.md §三：认证失败不重试
                        e is UnauthorizedException -> {
                            return buildErrorResult(
                                logMessage = "API Key 无效 (401)",
                                returnMessage = "API Key 无效"
                            )
                        }
                        // 其他异常
                        else -> {
                            return buildErrorResult(
                                logMessage = e.message ?: "Unknown error",
                                returnMessage = e.message ?: "Unknown error"
                            )
                        }
                    }
                }
            }

            session.finishTurn()
            val fullReasoning = reasoningOutput.toString()
            val fullText = output.toString()
            return Result.Success(
                text = fullText,
                reasoning = fullReasoning.ifEmpty { null },
                turns = turn,
                stopReason = lastStopReason,
                alreadyPersisted = messageAlreadyPersisted
            )

        } catch (e: Exception) {
            return buildErrorResult(
                logMessage = e.message ?: "Unknown error",
                returnMessage = e.message ?: "Unknown error"
            )
        }
    }

    internal fun buildRequestParamsForTest(
        userMessage: String,
        images: List<ImageRef> = emptyList(),
        slashCommand: String? = null,
        mode: AgentMode = AgentMode.AGENT
    ): MessageCreateParams =
        buildRequestBuilder(userMessage, images, slashCommand, mode).build()

    private fun buildRequestBuilder(
        userMessage: String,
        images: List<ImageRef>,
        slashCommand: String?,
        mode: AgentMode
    ): MessageCreateParams.Builder {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .addSystemMessage(buildSystemPrompt(slashCommand))
            .apply {
                if (mode != AgentMode.CHAT) {
                    addRegisteredTools(this)
                }
            }
        appendConversationHistory(builder, userMessage, images)
        return builder
    }

    private fun appendConversationHistory(
        builder: MessageCreateParams.Builder,
        userMessage: String,
        images: List<ImageRef>
    ) {
        val history = session.messages.filter { !it.deleted && it.content.isNotBlank() }
        val currentAlreadyStored =
            history.lastOrNull()?.let { it.role == Role.USER && it.content == userMessage } == true

        history.forEachIndexed { index, message ->
            val isStoredCurrent = currentAlreadyStored && index == history.lastIndex
            when (message.role) {
                Role.USER -> {
                    if (isStoredCurrent && images.isNotEmpty()) {
                        builder.addUserMessageOfBetaContentBlockParams(
                            buildUserContentBlocks(userMessage, images)
                        )
                    } else {
                        builder.addUserMessage(message.content)
                    }
                }

                Role.ASSISTANT -> builder.addAssistantMessage(message.content)
                Role.SYSTEM -> builder.addUserMessage("[System]\n${message.content}")
                Role.ERROR -> Unit
            }
        }

        if (!currentAlreadyStored) {
            if (images.isEmpty()) {
                builder.addUserMessage(userMessage)
            } else {
                builder.addUserMessageOfBetaContentBlockParams(
                    buildUserContentBlocks(userMessage, images)
                )
            }
        }
    }

    /**
     * 检查是否需要 compact，如果需要则执行压缩并重建 builder。
     * 对齐 docs/agent/context.md §二：当总 token 超过 700K 时触发压缩。
     *
     * @param builder 当前的 MessageCreateParams builder，compact 后会就地重建
     * @param mode 当前的 AgentMode
     * @return 是否执行了 compact
     */
    private fun compactIfNeeded(builder: MessageCreateParams.Builder, mode: AgentMode): Boolean {
        val systemPrompt = buildSystemPrompt(null)
        val systemTokens = TokenEstimator.estimateTokens(systemPrompt)

        // 对齐 docs/specs/token-estimation.md §五：使用统一 TokenEstimator.estimateTokens() 逐项估算后求和，
        // 避免 joinToString 拼接引入额外噪声（分隔符/n等）
        val toolsTokens = if (mode != AgentMode.CHAT) {
            val filteredTools = toolsFilter
            if (filteredTools != null) {
                filteredTools.sumOf { clazz ->
                    val desc = clazz.annotations
                        .filterIsInstance<com.fasterxml.jackson.annotation.JsonClassDescription>()
                        .firstOrNull()?.value ?: clazz.simpleName
                    TokenEstimator.estimateTokens(desc)
                }
            } else {
                ToolRegistry.listRegistered().sumOf { tool ->
                    TokenEstimator.estimateTokens("${tool.info.name}: ${tool.info.description} ${tool.info.usage}")
                }
            }
        } else 0

        val messagesTokens = session.messages
            .filter { !it.deleted }
            .sumOf { msg ->
                TokenEstimator.estimateTokens("${msg.role}: ${msg.content}")
            }

        val totalTokens = systemTokens + toolsTokens + messagesTokens

        if (totalTokens <= (modelContextLimit * compactThreshold).toInt()) return false

        // 触发 compact：委托给共享的 doCompact 方法
        return doCompact(builder, mode)
    }

    /**
     * 错误恢复路径：强制 compact（不检查阈值），用于 400 context-too-long 等场景。
     * 对齐 docs/agent/loop.md 三：请求体过大不重试，强制触发 compact 后重发。
     *
     * 与 compactIfNeeded 的区别：
     * - compactIfNeeded：预防性，仅在 token > threshold 时压缩
     * - forceCompact：错误恢复路径，总是执行压缩
     *
     * @param builder 当前的 MessageCreateParams builder，compact 后会就地重建
     * @param mode 当前的 AgentMode
     * @return 是否执行了 compact（false 表示摘要生成失败，无法压缩）
     */
    private fun forceCompact(builder: MessageCreateParams.Builder, mode: AgentMode): Boolean {
        // 错误恢复路径：不检查阈值，直接委托给共享的 doCompact 方法
        return doCompact(builder, mode)
    }

    /**
     * 执行 compact 的共享逻辑，供 compactIfNeeded 和 forceCompact 共同调用。
     * 包括：生成摘要 → 更新 session.messages → 更新 compact 元数据 → 重建 builder。
     * 对齐 docs/agent/context.md §二。
     *
     * @param builder 当前的 MessageCreateParams builder，compact 后会就地重建
     * @param mode 当前的 AgentMode
     * @return 是否执行了 compact（false 表示摘要生成失败，无法压缩）
     */
    private fun doCompact(builder: MessageCreateParams.Builder, mode: AgentMode): Boolean {
        val summary = generateCompactSummary()
        if (summary == null) return false

        // 保留最近消息：至少保留最近 2 轮（跳过已回退的 deleted 消息）
        val recentCount = computeRecentKeepCount()
        val activeMessages = session.messages.filter { !it.deleted }.toList()
        val recentMessages = if (recentCount < activeMessages.size) {
            activeMessages.takeLast(recentCount)
        } else {
            activeMessages.toList()
        }

        // 更新 session：旧消息被摘要替代，近期消息保留原文，deleted 消息保留
        session.messages.clear()
        session.messages.add(
            Message(
                role = Role.SYSTEM,
                content = "以下是与本次对话相关的历史摘要：\n\n$summary"
            )
        )
        session.messages.addAll(recentMessages)

        // 更新 compact 元数据：多次压缩时，之前的摘要参与新一轮压缩
        session.compactSummary = summary
        session.compactCount++

        // 重建 builder：System Prompt 重新构建、Tools 重新生成、
        // Skill 正文从磁盘重新注入、@file 不重新注入
        rebuildBuilderAfterCompact(builder, mode)

        return true
    }

    /**
     * 计算 compact 后保留的近期消息数量。
     * 对齐 docs/agent/context.md §二：N = min(保留最近 3 轮的消息数, ceil(messages.size / 3))
     * "1 轮"定义：一条 user message 到下一个 user message 之前的所有消息。
     */
    private fun computeRecentKeepCount(): Int {
        val messages = session.messages
        if (messages.isEmpty()) return 0
        // 从后往前找 3 个 user 消息的轮次边界（跳过已回退的 deleted 消息）
        var userCount = 0
        var idx = messages.lastIndex
        var skipped = 0
        while (idx >= 0 && userCount < 3) {
            val msg = messages[idx]
            if (msg.deleted) {
                idx--
                skipped++
                continue
            }
            // 检查是否是 user message 或 tool_result（对应 agent 层的 user content block）
            if (msg.role == Role.SYSTEM) {
                // compactSummary 消息当作 user 轮次边界
                userCount++
            }
            idx--
        }
        val effectiveSize = messages.size - skipped
        val recent3Rounds = effectiveSize - idx - 1
        val ceilThird = (effectiveSize + 2) / 3 // ceil division
        return minOf(recent3Rounds, ceilThird).coerceAtLeast(2)
    }

    /**
     * 通过独立的 API 调用生成对话摘要。
     * 对齐 docs/specs/auto-compact.md §三：不带 tools，max_tokens=1024。
     *
     * @return 生成的摘要文本，失败返回 null
     */
    private fun generateCompactSummary(): String? {
        val client = try {
            getClient()
        } catch (_: Exception) {
            return null
        }

        val historyText = session.messages.filter { !it.deleted }.joinToString("\n\n") { msg ->
            "[${msg.role}] ${msg.content.take(2000)}"
        }

        // 对齐 docs/agent/context.md §二：摘要生成 Prompt 模板
        val summarySystemPrompt = """
请将以下对话压缩为简洁摘要。保留：
- 用户的核心任务和目标
- 已完成的计划项和关键决策
- 当前进行中的工作和上下文
- 重要的文件路径、错误信息、技术约束

省略：思考过程、工具调用详情（具体参数/返回值）、中间探索性操作。

对话内容：
$historyText
        """.trimIndent()

        return try {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024)
                .addSystemMessage(summarySystemPrompt)
                .build()

            val result = client.beta().messages().create(params)
            val sb = StringBuilder()
            for (block in result.content()) {
                block.text().ifPresent { textBlock ->
                    sb.append(textBlock.text())
                }
            }
            val summary = sb.toString()
            summary.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * compact 后从头重建 builder 上下文。
     * 对齐 docs/agent/context.md §二 compact 后上下文重建表格：
     * - System Prompt：从 buildSystemPrompt() 重新构建
     * - Tools 定义：从 ToolRegistry 重新生成
     * - Skill 正文：从磁盘重新注入到 System Prompt 层面（确保关键约束不因摘要质量丢失）
     * - @file 文件内容：不重新注入（LLM 应通过 Read 工具重新读取）
     * - session.messages：旧消息已被摘要替代，builder 中仅注入近期原文 + 新 user 消息
     */
    private fun rebuildBuilderAfterCompact(builder: MessageCreateParams.Builder, mode: AgentMode) {
        // 清空 builder 中的消息（保留 model/maxTokens/systemMessage/tools）
        // builder 没有 clearMessages 方法，我们通过重新设置 system message 和 tools 来重建
        // 注意：builder.messages() 在 SDK 中可能无法直接清除，这里通过重建 system message 更新

        // 1. 重新生成 System Prompt（compact 时没有 /command，传 null）
        val systemPrompt = buildSystemPrompt(null)
        // 注意：SDK 的 builder 没有 replaceSystemMessage，我们通过移除旧 system messages 再新增的方式
        // 实际上 Anthropic SDK 的 MessageCreateParams.Builder 没有直接修改已添加消息的 API
        // 需要先在 ChatViewModel 层面重建 builder

        // 2. 重新注入被调用过的 Skill 正文到 System Prompt 层面（从磁盘重新注入）
        // 对齐 docs/agent/context.md §二：Skill 正文应在 System Prompt 层面注入，而非 user message
        if (session.calledSkills.isNotEmpty()) {
            val skillManager = com.aiassistant.skills.SkillManager(project)
            val skills = skillManager.loadSkills()
            for (skillName in session.calledSkills) {
                val skill = skills.find { it.name == skillName || it.command == skillName }
                if (skill != null && skill.enabled && skill.missingTools.isEmpty()) {
                    // 对齐 context.md §二：Skill 正文从磁盘重新注入到 System Prompt 层面
                    builder.addSystemMessage("## Skill（compact 重新注入）: ${skill.name}\n${skill.content}")
                }
            }
        }

        // 3. @file 文件内容：不重新注入（LLM 应通过 Read 工具重新读取目标文件）
        // 4. session.messages：旧消息已被摘要替代，近期消息保留原文（已在 compactIfNeeded 中处理）
    }

    /**
     * rebuildBuilderAfterCompact 的完整重建版本——从外部（ChatViewModel）调用，
     * 用于 compact 发生后需要完全重建 builder 的场景。
     * 返回一个新的 builder，包含重建后的 system prompt、tools 和重新注入的 skill 正文。
     *
     * @param userMessage 当前用户消息
     * @param mode 当前的 AgentMode
     * @return 重建后的 builder
     */
    fun rebuildBuilderAfterCompactForExternal(
        userMessage: String,
        mode: AgentMode = AgentMode.AGENT
    ): MessageCreateParams.Builder {
        val newBuilder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .addSystemMessage(buildSystemPrompt(null))
            .addUserMessage(userMessage)
            .apply { if (mode != AgentMode.CHAT) addRegisteredTools(this) }

        // 重新注入被调用过的 Skill 正文到 System Prompt 层面（从磁盘重新注入）
        // 对齐 docs/agent/context.md §二：compact 后 Skill 正文应在 System Prompt 层面注入
        if (session.calledSkills.isNotEmpty()) {
            val skillManager = com.aiassistant.skills.SkillManager(project)
            val skills = skillManager.loadSkills()
            for (skillName in session.calledSkills) {
                val skill = skills.find { it.name == skillName || it.command == skillName }
                if (skill != null && skill.enabled && skill.missingTools.isEmpty()) {
                    // 对齐 context.md §二：Skill 正文从磁盘重新注入到 System Prompt 层面
                    newBuilder.addSystemMessage(
                        "## Skill（compact 重新注入）: ${skill.name}\n${skill.content}"
                    )
                }
            }
        }

        // 不注入 @file 内容：LLM 应通过 Read 工具重新读取（避免依赖过期快照）

        return newBuilder
    }

    private fun addRegisteredTools(builder: MessageCreateParams.Builder) {
        val filteredTools = toolsFilter
        if (filteredTools != null) {
            filteredTools.forEach { builder.addTool(it) }
            return
        }

        ToolRegistry.listRegistered().forEach { tool ->
            val betaTool = tool.info.betaTool
            if (betaTool != null) {
                builder.addTool(betaTool)
            } else {
                builder.addTool(tool.toolClass)
            }
        }
    }

    /**
     * 构建用户消息的 ContentBlock 列表，对齐 docs/agent/images.md §二。
     * 文本 block 在前，图片 blocks 在后（按粘贴顺序）。
     *
     * 文本 block 中自动附加 [Image: fileName] 前缀，帮助 LLM 在纯文本流中感知图片。
     * API 请求格式为独立 image type content block，不嵌入文本。
     */
    private fun buildUserContentBlocks(
        text: String,
        images: List<ImageRef>
    ): List<BetaContentBlockParam> {
        val blocks = mutableListOf<BetaContentBlockParam>()

        // 文本 block：附加图片文件名前缀，帮助 LLM 感知图片存在
        val imagePrefix = if (images.isNotEmpty()) {
            images.joinToString(" ") { "[Image: ${it.fileName}]" } + "\n"
        } else ""
        blocks.add(
            BetaContentBlockParam.ofText(
                BetaTextBlockParam.builder()
                    .text(imagePrefix + text)
                    .build()
            )
        )

        // 图片 blocks：每个 ImageRef 转为独立的 image content block
        for (img in images) {
            val mediaType = when (img.mimeType) {
                "image/jpeg" -> BetaBase64ImageSource.MediaType.IMAGE_JPEG
                "image/gif" -> BetaBase64ImageSource.MediaType.IMAGE_GIF
                "image/webp" -> BetaBase64ImageSource.MediaType.IMAGE_WEBP
                else -> BetaBase64ImageSource.MediaType.IMAGE_PNG
            }
            val source = BetaBase64ImageSource.builder()
                .mediaType(mediaType)
                .data(img.base64Data)
                .build()
            val imageBlock = BetaImageBlockParam.builder()
                .source(source)
                .build()
            blocks.add(BetaContentBlockParam.ofImage(imageBlock))
        }

        return blocks
    }

    /**
     * 构建 System Prompt，对齐 docs/specs/system-prompt.md §8.2 和 docs/specs/i18n.md §三 模板加载。
     * 顺序：语言模板(变量替换) → 工具描述(ToolRegistry) → Skill列表(name+截断描述) → /command Skill正文。
     *
     * @param slashCommand 用户输入的 /command 命令名（如 "/review"），用于注入对应 SKILL.md 正文；null 表示非 /command
     */
    private fun buildSystemPrompt(slashCommand: String? = null): String {
        val skillManager = com.aiassistant.skills.SkillManager(project)
        val toolSection = ToolRegistry.buildSystemPromptTools()
        val projectName = project.name
        val basePath = project.basePath ?: "unknown"
        val currentFile = try {
            val editor =
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
            editor?.virtualFile?.name ?: I18n.get("agent.prompt.no_file")
        } catch (_: Exception) {
            I18n.get("agent.prompt.no_file")
        }

        val dateStr = java.time.LocalDate.now().toString()
        val osName = System.getProperty("os.name")
        val branch = try {
            val bp = project.basePath
            if (bp != null) {
                val gitDir = File(bp, ".git")
                if (gitDir.exists()) {
                    val headRef = File(gitDir, "HEAD").readText().trim()
                    headRef.removePrefix("ref: refs/heads/")
                } else "unknown"
            } else "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        // 检测编码规范文件
        val configFiles = listOf("CLAUDE.md", "CODEX.md", "AGENTS.md", ".cursorrules")
            .filter { project.basePath?.let { bp -> File(bp, it).exists() } ?: false }
        val configRef = if (configFiles.isNotEmpty()) {
            if (I18n.isChinese()) {
                "遵守项目中 ${configFiles.joinToString(" / ")} 定义的编码规范"
            } else {
                "Follow the coding standards defined in ${configFiles.joinToString(" / ")}"
            }
        } else ""

        // ── 1. 加载语言模板（按 IDE 语言选择，回退简体中文） ──
        val suffix = I18n.languageSuffix()
        val template = javaClass.getResourceAsStream("/i18n/system-prompt_$suffix.md")
            ?.bufferedReader()?.readText()
            ?: javaClass.getResourceAsStream("/i18n/system-prompt_zh_CN.md")
                ?.bufferedReader()?.readText()
            ?: error("System prompt template not found for locale: $suffix")

        val basePrompt = template
            .replace("{projectName}", projectName)
            .replace("{basePath}", basePath)
            .replace("{currentFile}", currentFile)
            .replace("{dateStr}", dateStr)
            .replace("{osName}", osName)
            .replace("{branch}", branch)
            .replace("{configRef}", configRef)
            .replace("{toolSection}", toolSection)
            .trimEnd()

        // ── 2. Skill 列表（名称 + 截断描述，不包含完整正文） ──
        // 对齐 docs/agent/skills.md §四 和 docs/specs/system-prompt.md §8.2：
        // 注入 "## 可用 Skills"，仅包含名称和截断描述，不包含 SKILL.md 正文
        val skillList = skillManager.getSystemPromptExtension()

        // ── 3. /command Skill 正文（仅当用户输入 /command 时注入对应 SKILL.md 全文） ──
        // 对齐 docs/specs/system-prompt.md §8.2：
        // 格式: "\n## Skill: {name}\n{content}"
        val skillBody = if (slashCommand != null) {
            val commandName = slashCommand.removePrefix("/").trim()
            val skills = skillManager.loadSkills()
            val matched =
                skills.find { it.command == commandName && it.enabled && !it.hasMissingTools }
            if (matched != null) {
                "\n\n## Skill: ${matched.name}\n${matched.content}"
            } else ""
        } else ""

        return basePrompt + skillList + skillBody
    }

    sealed class Result {
        data class Success(
            val text: String,
            val reasoning: String? = null,
            val turns: Int,
            val stopReason: String,
            /** 标记 assistant 消息是否已在 AgentLoop 内持久化到 session.messages */
            val alreadyPersisted: Boolean = false
        ) : Result()

        data class Error(val message: String) : Result()
    }
}
