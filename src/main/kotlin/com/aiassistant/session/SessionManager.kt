package com.aiassistant.session

import com.aiassistant.AppSettingsService
import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.Role
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.MessageCreateParams
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDate

// ponytail: session CRUD + search wrapper over SessionStore

enum class TokenRange { DAY, MONTH, ALL }

class SessionManager(private val project: Project) {

    private val store = SessionStore(project)
    var currentSession: AgentSession? = null

    /** 标题异步生成后回调，用于 ChatViewModel 更新标题栏，对齐 docs/ui/pages.md §十二 */
    var onTitleGenerated: ((sessionId: String, title: String) -> Unit)? = null

    /** Anthropic SDK 客户端，用于标题生成。对齐 docs/agent.md 技术栈声明：Anthropic 兼容接口 */
    private var titleClient: AnthropicClient? = null
    private var titleClientApiKey: String? = null

    private fun getTitleClient(): AnthropicClient {
        val apiKey = AppSettingsService.getInstance().getApiKey()
            ?: throw IllegalStateException("API Key not configured")
        val existing = titleClient
        if (existing != null && titleClientApiKey == apiKey) return existing
        return AnthropicOkHttpClient.builder()
            .baseUrl("https://api.deepseek.com/anthropic")
            .apiKey(apiKey)
            .build()
            .also {
                titleClient = it
                titleClientApiKey = apiKey
            }
    }

    fun createSession(title: String = "新会话"): AgentSession {
        val session = AgentSession(title = title)
        session.finishTurn() // 确保状态为 IDLE，对齐 constructor 默认值
        currentSession = session
        return session
    }

    fun getSession(id: String): AgentSession? = store.load(id)

    fun getAllSessions(): List<SessionIndex> = store.listAll()

    /**
     * 软删除：标记 session 的 deleted=true，不删除物理文件。
     * 可通过 undoDeleteSession() 撤销，或 purgeSession() 物理删除。
     * 对齐 docs/agent/session.md §二 软删除操作。
     */
    fun deleteSession(id: String) {
        store.softDelete(id)
        if (currentSession?.id == id) currentSession = null
    }

    fun deleteSessions(ids: List<String>) {
        for (id in ids) {
            store.softDelete(id)
        }
        if (currentSession?.id in ids) currentSession = null
    }

    /**
     * 物理删除指定 session（文件 + index 条目），不可撤销。
     * 软删除后的 session 可通过此方法彻底清除。
     */
    fun purgeSession(id: String) {
        store.delete(id)
        if (currentSession?.id == id) currentSession = null
    }

    /**
     * 撤销软删除：将 session 的 deleted 标记恢复为 false。
     */
    fun undoDeleteSession(sessionId: String) {
        store.undoDelete(sessionId)
    }

    fun saveSession(session: AgentSession) = store.save(session)

    fun searchSessions(query: String): List<SessionIndex> =
        getAllSessions().filter { it.title.contains(query, ignoreCase = true) }

    /**
     * 聚合所有 session 的 token 用量，按 range 分组。
     * 对齐 docs/agent/session.md §六：getTotalTokenUsage(range, includeChildren)
     *
     * @param range DAY 按日聚合，MONTH 按月聚合，ALL 全部聚合到一个周期
     * @param includeChildren 是否包含子 session 的 parentTotalTokens
     */
    fun getTotalTokenUsage(range: TokenRange, includeChildren: Boolean = false): TokenAggregation {
        val allSessions = getAllSessions()
        val periods = mutableMapOf<LocalDate, TokenPeriod>()

        for (index in allSessions) {
            val session = store.load(index.id) ?: continue
            val dateKey = when (range) {
                TokenRange.DAY -> index.updatedAt.atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()

                TokenRange.MONTH -> index.updatedAt.atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().withDayOfMonth(1)

                TokenRange.ALL -> LocalDate.ofEpochDay(0)
            }

            val existing = periods[dateKey]
            // 从消息级别累加实际的 input/output token 用量
            val inputTokens = session.messages.sumOf { it.tokenUsage?.inputTokens ?: 0L }
            val outputTokens = session.messages.sumOf { it.tokenUsage?.outputTokens ?: 0L }
            val childTokens = if (includeChildren) index.parentTotalTokens ?: 0 else 0

            periods[dateKey] = if (existing == null) {
                TokenPeriod(
                    date = dateKey,
                    inputTokens = inputTokens + childTokens,
                    outputTokens = outputTokens
                )
            } else {
                existing.copy(
                    inputTokens = existing.inputTokens + inputTokens + childTokens,
                    outputTokens = existing.outputTokens + outputTokens
                )
            }
        }

        val periodList = periods.values
            .sortedBy { it.date }
            .filter { it.inputTokens > 0 || it.outputTokens > 0 }

        val grandTotal = periodList.sumOf { it.inputTokens + it.outputTokens }
        // 粗略费用估算：DeepSeek pricing ~ $0.27/1M input, $1.10/1M output
        val estimatedCost = BigDecimal(grandTotal).multiply(BigDecimal("0.00000027"))

        return TokenAggregation(
            periods = periodList,
            grandTotal = grandTotal,
            estimatedCost = estimatedCost
        )
    }

    /**
     * 递归聚合所有子 session 的 totalTokens，写入父 session 的 parentTotalTokens。
     * 对齐 docs/agent/session.md §六：aggregateChildTokens(sessionId)
     *
     * 遍历所有 session，找到 parentId == sessionId 的子 session，累加它们的 totalTokens，
     * 同时递归聚合子 session 的孙 session。
     *
     * @param sessionId 需要聚合子 token 的父 session ID
     * @param visited 已访问的 session ID 集合，防止循环引用导致栈溢出
     */
    fun aggregateChildTokens(sessionId: String, visited: MutableSet<String> = mutableSetOf()): Long {
        if (!visited.add(sessionId)) return 0L // 循环引用检测，已访问过则跳过
        val allSessions = getAllSessions()
        val childSessions = allSessions.filter { it.parentId == sessionId }
        if (childSessions.isEmpty()) return 0L

        var total = 0L
        for (child in childSessions) {
            total += child.totalTokens
            // 递归聚合孙 session，传递 visited 防止循环
            total += aggregateChildTokens(child.id, visited)
        }

        // 将聚合结果写回 index
        updateParentTotalTokens(sessionId, total)
        return total
    }

    /**
     * 更新 index.json 中指定 session 的 parentTotalTokens 字段。
     */
    private fun updateParentTotalTokens(sessionId: String, tokens: Long) {
        val sessionsDir = java.io.File(project.basePath!!, ".code-assistant/sessions")
        val indexFile = java.io.File(sessionsDir, "index.json")
        if (!indexFile.exists()) return

        try {
            val indexType =
                object : com.google.gson.reflect.TypeToken<MutableList<Map<String, Any?>>>() {}.type
            val indexList: MutableList<Map<String, Any?>> =
                com.google.gson.Gson().fromJson(indexFile.readText(), indexType) ?: mutableListOf()
            val idx = indexList.indexOfFirst { it["id"] == sessionId }
            if (idx >= 0) {
                indexList[idx] = indexList[idx].toMutableMap().apply {
                    put("parentTotalTokens", tokens)
                }
                val indexTmp = java.io.File(sessionsDir, "index.json.tmp")
                indexTmp.writeText(com.google.gson.Gson().toJson(indexList))
                java.nio.file.Files.move(
                    indexTmp.toPath(),
                    indexFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            // 静默失败，不影响主流程
        }
    }

    /**
     * 异步调用 LLM 生成会话标题（≤20字, max_tokens=64）。
     * 对齐 docs/agent/session.md §三：
     * - 独立 API 调用，不带 tools
     * - 不阻塞主流程，后台进行
     * - 失败保持默认值"新会话"
     * - 429 限流等待 1 分钟后重试，无限重试
     * - 用户完全不可见（无状态指示器，无错误提示）
     *
     * @param sessionId 需要生成标题的会话 ID
     */
    fun generateTitle(sessionId: String) {
        val session = store.load(sessionId) ?: return
        val firstUserMessage = session.messages.firstOrNull { it.role == Role.USER }?.content
        if (firstUserMessage.isNullOrBlank()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            generateTitleWithRetry(sessionId, firstUserMessage)
        }
    }

    /**
     * 标题生成重试循环，使用 Anthropic SDK（对齐 docs/agent.md 技术栈声明）。
     * 对齐 docs/agent/session.md §三：
     * - API 调用失败 → title 保持"新会话"
     * - 429 限流 → 等 1 分钟重试，无限重试
     */
    private fun generateTitleWithRetry(sessionId: String, firstUserMessage: String) {
        val model = AppSettingsService.getInstance().getModel()
        val prompt = """根据以下消息生成一个简短的会话标题（≤ 20 字）：
"${firstUserMessage}"
仅返回标题文本，不要加引号。"""

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(64)
            .addUserMessage(prompt)
            .build()

        var shouldRetry = true

        while (shouldRetry) {
            shouldRetry = false
            try {
                val client = getTitleClient()
                val result = client.beta().messages().create(params)
                val sb = StringBuilder()
                for (block in result.content()) {
                    block.text().ifPresent { textBlock ->
                        sb.append(textBlock.text())
                    }
                }
                val title = sb.toString().trim().trim('"', '\'').trim().take(20).ifBlank { null }
                if (title != null) {
                    applyTitle(sessionId, title)
                }
            } catch (e: com.anthropic.errors.RateLimitException) {
                // 429 限流：等 1 分钟后重试，无限重试
                Thread.sleep(60_000)
                if (store.load(sessionId) != null) {
                    shouldRetry = true
                }
            } catch (e: SocketTimeoutException) {
                // 超时：title 保持默认值"新会话"，退出
                return
            } catch (e: Exception) {
                // 其他错误：title 保持默认值"新会话"，退出
                return
            }
        }
    }


    /**
     * 将生成的标题持久化到 Session JSON 和 index.json，并更新内存中的 currentSession。
     * 对齐 docs/agent/session.md §三：持久化到 SessionIndex.title 和 Session JSON 的 title 字段。
     */
    private fun applyTitle(sessionId: String, title: String) {
        // 通过 SessionStore.updateTitle 更新底层文件（Session JSON + index.json）
        store.updateTitle(sessionId, title)

        // 如果当前 session 就是被生成标题的 session，更新内存中的引用
        currentSession?.let {
            if (it.id == sessionId) {
                it.title = title
            }
        }

        // 通知 UI 层标题已更新，对齐 docs/ui/pages.md §十二 ChatPage 标题行
        onTitleGenerated?.invoke(sessionId, title)
    }
}
