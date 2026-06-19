package com.aiassistant.mcp

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 服务器通知回调接口（对齐 Claude Code SSE 推送）
 */
interface McpNotificationHandler {
    fun onToolsChanged() {}
    fun onPromptsChanged() {}
    fun onResourcesChanged() {}
}

/**
 * MCP Sampling 回调（对齐 Claude Code：服务器可请求 LLM 生成文本）
 */
fun interface McpSamplingHandler {
    /** 处理 sampling/createMessage 请求，返回生成的文本 */
    fun handleCreateMessage(paramsJson: String): String?
}

/**
 * MCP (Model Context Protocol) JSON-RPC 客户端。
 * 支持 stdio 和 HTTP+SSE 两种传输方式，对齐 Claude Code。
 * 支持服务器推送通知（tools/prompts/resources/list_changed）。
 */
class McpClient(private val config: McpServerConfig) {

    companion object {
        private val readExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "mcp-read-${System.currentTimeMillis()}").apply { isDaemon = true }
        }
        private val httpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    var notificationHandler: McpNotificationHandler? = null
    var samplingHandler: McpSamplingHandler? = null

    /** 服务器能力集合（从 initialize 响应解析），对齐 Claude Code */
    private val serverCapabilities = mutableSetOf<String>()

    private var process: Process? = null
    @Volatile private var initialized: Boolean = false
    @Volatile private var disconnecting: Boolean = false  // 防止 disconnect 期间 reader 线程写入孤条目
    private val discoveredTools = mutableListOf<McpToolAdapter>()
    private var isHttpTransport: Boolean = false

    private val responseLock = Object()
    private val writeLock = Object()  // 保护 stdio 写入，防止多线程同时写入管道导致消息交错
    /** 按响应 ID 精确匹配，消除单字段覆盖导致的响应丢失（对齐 Claude Code 请求-响应关联） */
    private val pendingResponses = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var sseRunning = false

    /**
     * 启动 MCP 服务器并完成初始化握手。根据 config.transport 路由到 stdio 或 HTTP。
     */
    fun connect(): Boolean {
        disconnecting = false  // 重置断开标志，允许重新连接
        if (config.transport == "http" || config.url.isNotBlank()) {
            isHttpTransport = true
            return connectHttp()
        }
        if (config.transport == "stdio" && config.command.isNotBlank()) {
            isHttpTransport = false
            return connectStdio()
        }
        return false
    }

    /** stdio 传输：启动子进程 + 后台持续读取线程（捕捉服务器推送通知） */
    private fun connectStdio(): Boolean {
        return try {
            val env = mutableMapOf<String, String>()
            env.putAll(System.getenv())
            env.putAll(config.env)

            val pb = ProcessBuilder(listOf(config.command) + config.args)
                .redirectErrorStream(true)  // stderr 合并到 stdout，防止管道缓冲区填满阻塞进程
            pb.environment().putAll(env)

            process = pb.start()

            // 启动后台读取线程：持续监听 stdout，处理响应和通知
            val proc = process!!
            readExecutor.submit {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    while (proc.isAlive) {
                        val line = reader.readLine() ?: break
                        try {
                            processMessage(line)
                        } catch (_: Exception) {
                            // 单条消息解析失败不终止整个读取循环
                        }
                    }
                } catch (_: Exception) {}
            }

            val (initId, initRequest) = buildJsonRpc("initialize", """
                {"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true},"sampling":{}},"clientInfo":{"name":"CodeAssistant","version":"1.0.0"}}
            """.trimIndent())

            sendStdioRequest(initRequest)
            val initResponse = readStdioResponse(initId)
            if (initResponse == null || initResponse.contains("\"error\"")) {
                disconnect()
                return false
            }

            // 提前标记已初始化，确保 reader 线程不会因 !initialized 丢弃消息
            // MCP 协议要求 initialized 通知发送后才能发送其他请求，本实现在 discoverTools 前已发送
            initialized = true
            sendStdioRequest(buildJsonRpcNotification("initialized", "{}"))
            parseServerCapabilities(initResponse)
            true
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    /** HTTP 传输：POST JSON-RPC + 可选 SSE 推送监听 */
    private fun connectHttp(): Boolean {
        return try {
            val (_, initRequest) = buildJsonRpc("initialize", """
                {"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true},"sampling":{}},"clientInfo":{"name":"CodeAssistant","version":"1.0.0"}}
            """.trimIndent())

            val initResponse = sendHttpRequest(initRequest)
            if (initResponse == null || initResponse.contains("\"error\"")) {
                disconnect()
                return false
            }

            sendHttpRequest(buildJsonRpcNotification("initialized", "{}"))
            parseServerCapabilities(initResponse)
            startSseListener()
            initialized = true
            true
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    /** 启动 HTTP SSE 监听器（后台线程消费 sseQueue + 定时 POST 拉取） */
    private fun startSseListener() {
        if (sseRunning) return
        sseRunning = true
        readExecutor.submit {
            var retryCount = 0
            val maxRetries = 10
            val sseEndpoint = config.sseUrl.ifBlank { config.url }
            while (sseRunning && initialized) {
                try {
                    // 尝试 GET SSE 流
                    val sseRequest = HttpRequest.newBuilder()
                        .uri(URI.create(sseEndpoint))
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build()
                    val sseResponse = httpClient.send(sseRequest, HttpResponse.BodyHandlers.ofLines())
                    sseResponse.body().forEach { line ->
                        if (!sseRunning) return@forEach
                        if (line.startsWith("data: ")) {
                            val json = line.removePrefix("data: ").trim()
                            if (json.isNotBlank()) {
                                processMessage(json)
                            }
                        }
                    }
                    retryCount = 0  // 成功后重置重试计数
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount >= maxRetries) {
                        com.aiassistant.AppLogger.warn("SSE 重连失败 $maxRetries 次，停止重连: $sseEndpoint")
                        break
                    }
                    val delay = (1000L * retryCount).coerceAtMost(30000L)  // 指数退避 1s→30s
                    if (sseRunning) Thread.sleep(delay)
                }
            }
        }
    }

    /** 检查服务器是否支持指定能力（能力列表为空时放行，兼容旧服务器不声明能力的情况） */
    private fun supports(capability: String): Boolean = serverCapabilities.isEmpty() || serverCapabilities.contains(capability)

    /** 设置服务器日志级别（对齐 Claude Code logging 协议） */
    fun setLogLevel(level: String) {
        if (!initialized) return
        try {
            val json = buildJsonRpcNotification("logging/setLevel", """{"level":"$level"}""")
            if (isHttpTransport) sendHttpRequest(json) else sendStdioRequest(json)
        } catch (_: Exception) {}
    }

    /** 从 JSON-RPC 消息中提取字段值（用 Gson 解析，不再手写正则） */
    private fun parseJsonField(json: String, key: String): Any? {
        return try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            map?.get(key)
        } catch (_: Exception) { null }
    }

    /** 处理收到的消息 */
    private fun processMessage(json: String) {
        val id = parseJsonField(json, "id")
        val method = parseJsonField(json, "method")?.toString()
        if (id != null && method != null) {
            handleServerRequest(method, json)
        } else if (id != null) {
            // HTTP+SSE 模式下，异步工具结果可能通过 SSE 推送到达
            val responseId = (id as? Number)?.toInt() ?: return
            if (disconnecting) return  // disconnect 正在进行，跳过写入防止孤条目
            pendingResponses[responseId] = json
            synchronized(responseLock) {
                responseLock.notifyAll()
            }
        } else {
            dispatchNotification(json)
        }
    }

    /** 处理服务器主动发起的请求（对齐 Claude Code：ping、sampling 协议） */
    private fun handleServerRequest(method: String, json: String) {
        val id = (parseJsonField(json, "id") as? Number)?.toInt() ?: return
        when (method) {
            "ping" -> {
                val response = """{"jsonrpc":"2.0","id":$id,"result":{}}"""
                if (isHttpTransport) sendHttpRequest(response) else sendStdioRequest(response)
            }
            "sampling/createMessage" -> {
                val handler = samplingHandler
                if (handler != null) {
                    val params = parseJsonField(json, "params")
                    val paramsJson = if (params != null) com.google.gson.Gson().toJson(params) else "{}"
                    val result = handler.handleCreateMessage(paramsJson)
                    val response = if (result != null) {
                        // model 和 stopReason 字段补齐 MCP CreateMessageResult 规范，默认值对齐 Claude Code 行为
                        """{"jsonrpc":"2.0","id":$id,"result":{"model":"claude-fable-5","role":"assistant","content":{"type":"text","text":${com.google.gson.Gson().toJson(result)}},"stopReason":"endTurn"}}"""
                    } else {
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-1,"message":"Sampling not available"}}"""
                    }
                    if (isHttpTransport) sendHttpRequest(response) else sendStdioRequest(response)
                }
            }
        }
    }

    /** 分发服务器推送通知（对齐 Claude Code：含 logging 通知） */
    private fun dispatchNotification(json: String) {
        val method = parseJsonField(json, "method")?.toString() ?: return
        when (method) {
            "notifications/tools/list_changed" -> notificationHandler?.onToolsChanged()
            "notifications/prompts/list_changed" -> notificationHandler?.onPromptsChanged()
            "notifications/resources/list_changed" -> notificationHandler?.onResourcesChanged()
            "notifications/logging/message" -> {
                try {
                    val gson = com.google.gson.Gson()
                    @Suppress("UNCHECKED_CAST")
                    val params = (gson.fromJson(json, Map::class.java) as? Map<String, Any>)?.get("params") as? Map<String, Any>
                    val level = params?.get("level")?.toString() ?: "info"
                    val msg = params?.get("message")?.toString() ?: ""
                    com.aiassistant.AppLogger.info("MCP[${config.name}] $level: $msg")
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 发现 MCP 服务器提供的工具列表
     */
    fun discoverTools(): List<AgentTool> {
        if (!initialized || !supports("tools")) return emptyList()
        return try {
            val (id, json) = buildJsonRpc("tools/list", "{}")
            val response = if (isHttpTransport) sendHttpRequest(json) else { sendStdioRequest(json); readStdioResponse(id) }
            if (response == null || response.contains("\"error\"")) return emptyList()
            val tools = parseTools(response)
            synchronized(discoveredTools) {
                discoveredTools.clear()
                discoveredTools.addAll(tools)
            }
            tools
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 调用 MCP 工具（使用原始 JSON 参数，保留 number/boolean/array/object 类型）
     */
    fun callToolRaw(toolName: String, rawArgsJson: String, project: Project): ToolResult {
        if (!initialized) return ToolResult.err("MCP 服务器未连接: ${config.name}")
        return try {
            val paramsObj = com.google.gson.JsonObject().apply {
                addProperty("name", toolName)
                add("arguments", com.google.gson.JsonParser.parseString(rawArgsJson.ifEmpty { "{}" }))
            }
            val (id, json) = buildJsonRpc("tools/call", com.google.gson.Gson().toJson(paramsObj))
            val response = if (isHttpTransport) sendHttpRequest(json) else { sendStdioRequest(json); readStdioResponse(id) }
            if (response == null) ToolResult.err("MCP 调用无响应: $toolName")
            else if (response.contains("\"error\"")) ToolResult.err("MCP 错误: ${extractErrorMessage(response)}")
            else ToolResult.ok(extractResultContent(response))
        } catch (e: Exception) {
            ToolResult.err("MCP 调用失败: ${e.message}")
        }
    }

    // ---- Prompts ----

    /** 发现 MCP 服务器提供的 prompt 模板列表 */
    fun listPrompts(): List<McpPromptDef> {
        if (!initialized || !supports("prompts")) return emptyList()
        return try {
            val (id, json) = buildJsonRpc("prompts/list", "{}")
            val response = if (isHttpTransport) sendHttpRequest(json) else { sendStdioRequest(json); readStdioResponse(id) }
            if (response == null || response.contains("\"error\"")) return emptyList()
            parsePrompts(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 获取渲染后的 prompt 文本 */
    fun getPrompt(name: String, arguments: Map<String, String>): String {
        if (!initialized) return ""
        return try {
            val argsObj = com.google.gson.JsonObject().apply {
                arguments.forEach { (k, v) -> addProperty(k, v) }
            }
            val paramsObj = com.google.gson.JsonObject().apply {
                addProperty("name", name)
                add("arguments", argsObj)
            }
            val (id, json) = buildJsonRpc("prompts/get", com.google.gson.Gson().toJson(paramsObj))
            val response = if (isHttpTransport) sendHttpRequest(json) else { sendStdioRequest(json); readStdioResponse(id) }
            extractPromptContent(response ?: return "")
        } catch (e: Exception) {
            ""
        }
    }

    // ---- Resources ----

    /** 发现 MCP 服务器提供的资源列表 */
    fun listResources(): List<McpResourceDef> {
        if (!initialized || !supports("resources")) return emptyList()
        return try {
            val (id, json) = buildJsonRpc("resources/list", "{}")
            val response = if (isHttpTransport) sendHttpRequest(json) else { sendStdioRequest(json); readStdioResponse(id) }
            if (response == null || response.contains("\"error\"")) return emptyList()
            parseResources(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 读取 MCP 资源内容 */
    fun readResource(uri: String): String {
        if (!initialized) return ""
        return try {
            val paramsObj = com.google.gson.JsonObject().apply {
                addProperty("uri", uri)
            }
            val (id, json) = buildJsonRpc("resources/read", com.google.gson.Gson().toJson(paramsObj))
            val response = if (isHttpTransport) sendHttpRequest(json) else { sendStdioRequest(json); readStdioResponse(id) }
            extractResourceContent(response ?: return "")
        } catch (e: Exception) {
            ""
        }
    }

    /** 发送取消通知给服务器（对齐 Claude Code：用户中断时释放服务器资源） */
    fun cancelPending() {
        if (!initialized) return
        try {
            val json = buildJsonRpcNotification("notifications/cancelled",
                """{"reason":"User cancelled"}""")
            if (isHttpTransport) sendHttpRequest(json) else sendStdioRequest(json)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        disconnecting = true  // 最先设置，防止 reader 线程在清理期间写入孤条目
        initialized = false
        sseRunning = false
        try { process?.destroyForcibly()?.waitFor(10, TimeUnit.SECONDS) } catch (_: Exception) {}
        process = null
        pendingResponses.clear()
        synchronized(responseLock) { responseLock.notifyAll() }
    }

    fun isConnected(): Boolean = initialized && (isHttpTransport || process?.isAlive == true)

    private val requestId = AtomicInteger(0)

    /** 构建 JSON-RPC 请求，返回 (id, json) 对，消除 ID 竞态 */
    private fun buildJsonRpc(method: String, params: String): Pair<Int, String> {
        val id = requestId.incrementAndGet()
        return id to """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
    }

    private fun buildJsonRpcNotification(method: String, params: String): String {
        return """{"jsonrpc":"2.0","method":"$method","params":$params}"""
    }

    // ---- stdio 传输 ----

    private fun sendStdioRequest(json: String) {
        synchronized(writeLock) {
            try {
                process?.outputStream?.write("${json}\n".toByteArray())
                process?.outputStream?.flush()
            } catch (e: Exception) {
                initialized = false
                throw RuntimeException("MCP stdio 写入失败: ${config.name}", e)
            }
        }
    }

    private fun readStdioResponse(expectedId: Int, timeoutMs: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(responseLock) {
            try {
                while (!pendingResponses.containsKey(expectedId)) {
                    // 进程已死时提前退出，避免等到超时。
                    // 使用 != true 而非 == false：process 在 disconnect() 中被置 null，
                    // null == false 为 false，导致检查失效；null != true 则正确触发 break。
                    if (process?.isAlive != true) break
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    responseLock.wait(remaining)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // remove 返回 null 表示超时；若超时后 reader 线程迟到写入，此刻一并清理
            return pendingResponses.remove(expectedId)
        }
    }

    // ---- HTTP 传输 ----

    private fun sendHttpRequest(json: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(15))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    /** 从 initialize 响应解析服务器能力（对齐 Claude Code） */
    private fun parseServerCapabilities(response: String) {
        try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *> ?: return
            val result = root["result"] as? Map<*, *> ?: return
            val caps = result["capabilities"] as? Map<*, *> ?: return
            caps.keys.forEach { key -> serverCapabilities.add(key.toString()) }
        } catch (_: Exception) {}
    }

    /** 使用 Gson 解析 MCP tools/list 响应，提取工具名、描述和参数列表 */
    private fun parseTools(response: String): List<McpToolAdapter> {
        return try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *> ?: return emptyList()
            val result = root["result"] as? Map<*, *> ?: return emptyList()
            val toolList = result["tools"] as? List<*> ?: return emptyList()
            toolList.mapNotNull { tool ->
                val t = tool as? Map<*, *> ?: return@mapNotNull null
                val name = t["name"] as? String ?: return@mapNotNull null
                val desc = t["description"] as? String ?: ""
                val params = parseInputSchema(t["inputSchema"] as? Map<*, *>)
                McpToolAdapter(name, desc, params, this)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 从 inputSchema 的 properties 中提取 ToolParameter 列表 */
    private fun parseInputSchema(schema: Map<*, *>?): List<ToolParameter> {
        if (schema == null) return emptyList()
        val props = schema["properties"] as? Map<*, *> ?: return emptyList()
        val required = (schema["required"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
        return props.mapNotNull { (k, v) ->
            val paramName = k as? String ?: return@mapNotNull null
            val details = v as? Map<*, *>
            val paramType = details?.get("type") as? String ?: "string"
            val paramDesc = details?.get("description") as? String ?: ""
            ToolParameter(paramName, paramType, paramDesc, required = paramName in required)
        }
    }

    private fun extractResultContent(response: String): String {
        return try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            val root = gson.fromJson(response, Map::class.java) as Map<*, *>
            val result = root["result"] as? Map<*, *>
            val content = result?.get("content") as? List<*>
            // 提取所有 text 类型的 content block（MCP 规范允许 content 数组包含多个块）
            val textBlocks = content?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String } ?: emptyList()
            if (textBlocks.isNotEmpty()) textBlocks.joinToString("\n") else response.take(1000)
        } catch (_: Exception) {
            response.take(1000)
        }
    }

    private fun extractErrorMessage(response: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *>
            val error = root?.get("error") as? Map<*, *>
            error?.get("message") as? String ?: "Unknown error"
        } catch (_: Exception) {
            "Unknown error"
        }
    }

    // ---- Prompt 解析 ----

    private fun parsePrompts(response: String): List<McpPromptDef> {
        return try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *> ?: return emptyList()
            val result = root["result"] as? Map<*, *> ?: return emptyList()
            val list = result["prompts"] as? List<*> ?: return emptyList()
            list.mapNotNull { p ->
                val m = p as? Map<*, *> ?: return@mapNotNull null
                val name = m["name"] as? String ?: return@mapNotNull null
                val desc = m["description"] as? String ?: ""
                val args = parsePromptArgs(m["arguments"] as? List<*>)
                McpPromptDef(name, desc, args)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parsePromptArgs(args: List<*>?): List<ToolParameter> {
        if (args == null) return emptyList()
        return args.mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val desc = m["description"] as? String ?: ""
            val type = m["type"] as? String ?: "string"
            val required = m["required"] as? Boolean ?: false
            ToolParameter(name, type, desc, required)
        }
    }

    private fun extractPromptContent(response: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *>
            val result = root?.get("result") as? Map<*, *>
            val messages = result?.get("messages") as? List<*>
            messages?.joinToString("\n") { msg ->
                val m = msg as? Map<*, *>
                val role = m?.get("role") as? String ?: ""
                val content = (m?.get("content") as? Map<*, *>)?.get("text") as? String ?: ""
                if (role == "user") content else "[$role] $content"
            } ?: response.take(1000)
        } catch (_: Exception) { response.take(1000) }
    }

    // ---- Resource 解析 ----

    private fun parseResources(response: String): List<McpResourceDef> {
        return try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *> ?: return emptyList()
            val result = root["result"] as? Map<*, *> ?: return emptyList()
            val list = result["resources"] as? List<*> ?: return emptyList()
            list.mapNotNull { r ->
                val m = r as? Map<*, *> ?: return@mapNotNull null
                val uri = m["uri"] as? String ?: return@mapNotNull null
                val name = m["name"] as? String ?: uri
                val desc = m["description"] as? String ?: ""
                val mimeType = m["mimeType"] as? String ?: ""
                McpResourceDef(uri, name, desc, mimeType)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun extractResourceContent(response: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(response, Map::class.java) as? Map<*, *>
            val result = root?.get("result") as? Map<*, *>
            val contents = result?.get("contents") as? List<*>
            contents?.joinToString("\n") { c ->
                val m = c as? Map<*, *>
                m?.get("text") as? String ?: m?.get("uri") as? String ?: ""
            } ?: response.take(1000)
        } catch (_: Exception) { response.take(1000) }
    }
}

/** MCP Prompt 定义 */
data class McpPromptDef(
    val name: String,
    val description: String,
    val arguments: List<ToolParameter>
)

/** MCP Resource 定义 */
data class McpResourceDef(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String
)

/**
 * MCP 工具适配器 — 将 MCP 工具包装为 AgentTool 接口。
 * 支持原始 JSON 参数注入，保留 number/boolean/array/object 类型。
 */
class McpToolAdapter(
    override val name: String,
    override val description: String,
    override val parameters: List<ToolParameter>,
    private val client: McpClient
) : AgentTool {

    /** 原始 JSON 参数（由 AgentLoop 在执行前注入），保留原始类型 */
    @Volatile var rawArgsJson: String? = null

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val raw = rawArgsJson
        return if (raw != null) {
            client.callToolRaw(name, raw, project)
        } else {
            // 降级：使用 Map 重建 JSON（仅 string 类型可用）
            client.callToolRaw(name, buildArgsFromMap(params), project)
        }
    }

    /** 从 Map<String,String> 重建 JSON 参数，用 Gson 区分字符串值 vs JSON 值 */
    private fun buildArgsFromMap(params: Map<String, String>): String {
        if (params.isEmpty()) return "{}"
        val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
        val obj = com.google.gson.JsonObject()
        for ((k, v) in params) {
            obj.add(k, try {
                // 尝试解析为 JSON 元素（对象/数组/数字/布尔/null），否则当作字符串
                com.google.gson.JsonParser.parseString(v)
            } catch (_: Exception) {
                com.google.gson.JsonPrimitive(v)
            })
        }
        return gson.toJson(obj)
    }
}
