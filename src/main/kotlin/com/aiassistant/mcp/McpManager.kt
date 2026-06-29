package com.aiassistant.mcp

import com.aiassistant.agent.ToolRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpManager(private val project: Project) {

    data class McpServerConfig(
        val id: String, val command: String, val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(), val transport: String = "stdio",
        val url: String? = null, val enabled: Boolean = true
    )

    enum class State { CONFIGURED, INITIALIZING, RUNNING, CRASHED, STOPPED, DISCONNECTED, ERROR, INIT_ERROR }

    data class McpServer(
        val config: McpServerConfig, var state: State = State.CONFIGURED,
        var process: Process? = null
    ) {
        /** 已注册到 ToolRegistry 的工具名称列表（含 serverName/ 前缀），用于 disconnect 时不注销 */
        val registeredToolNames: MutableList<String> = mutableListOf()

        /** 最近的错误信息（用于 UI 展示崩溃/错误详情） */
        var lastErrorMessage: String? = null

        /** 崩溃恢复：已自动重启次数（最多 1 次） */
        internal var crashRestartCount: Int = 0

        /** 断连恢复：已自动重连次数（最多 10 次） */
        internal var disconnectReconnectCount: Int = 0

        /** 初始化重试：当前重试次数 */
        internal var initRetryCount: Int = 0

        /** 初始化开始时间戳（用于判断 3 分钟上限） */
        internal var initStartTimeMs: Long = 0

        /** stdout 后台读取线程 */
        internal var stdoutReaderThread: Thread? = null

        /** stderr 后台读取线程 */
        internal var stderrReaderThread: Thread? = null

        /** stdin 写入器（BufferedWriter，线程安全通过 synchronized 保护） */
        internal var stdinWriter: BufferedWriter? = null

        /** 连续非 JSON-RPC stdout 行计数器 */
        internal val nonJsonConsecutiveCount: AtomicInteger = AtomicInteger(0)

        /** 待处理的 JSON-RPC 响应队列 */
        internal val responseQueue: LinkedBlockingQueue<String> = LinkedBlockingQueue()
    }

    /**
     * MCP 工具描述，对应 tools/list 返回的单个工具。
     * 通过 getTools() 返回给外部使用。
     */
    data class AgentTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject? = null
    )

    data class ConnectionResult(
        val success: Boolean,
        val toolCount: Int? = null,
        val errorMessage: String? = null,
        val latencyMs: Long? = null
    )

    var onServerStateChanged: ((serverId: String, state: State) -> Unit)? = null

    private val gson = Gson()
    private val configFile: File get() = File(project.basePath, ".code-assistant/mcp-config.json")
    private val servers = mutableMapOf<String, McpServer>()

    /** 定时任务调度器 */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mcp-recovery").apply { isDaemon = true }
        }

    /** 每个 server 的待执行恢复任务 */
    private val pendingTasks = mutableMapOf<String, ScheduledFuture<*>>()

    init {
        registerInstance(project, this)
        loadServers().forEach { servers[it.config.id] = it }
    }

    fun loadServers(): List<McpServer> {
        if (!configFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<McpServerConfig>>() {}.type
            val configs: List<McpServerConfig> = gson.fromJson(configFile.readText(), type)
            configs.map { McpServer(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addServer(config: McpServerConfig) {
        servers[config.id] = McpServer(config)
        persist()
    }

    fun updateServer(id: String, update: (McpServerConfig) -> McpServerConfig): Boolean {
        val server = servers[id] ?: return false
        val updatedConfig = update(server.config)
        disconnect(id)
        servers.remove(id)
        servers[updatedConfig.id] = McpServer(updatedConfig)
        persist()
        return true
    }

    fun removeServer(id: String) {
        disconnect(id)
        // 删除 Server 时需要从 ToolRegistry 注销其工具
        val server = servers[id]
        server?.registeredToolNames?.forEach { toolName ->
            ToolRegistry.unregister(toolName)
        }
        servers.remove(id)
        persist()
    }

    /**
     * 启动子进程并执行 MCP 握手。
     * 握手超时 5s。失败时按退避策略重试：2s → 5s → 10s → 15s，总计最多 3 分钟。
     */
    fun connect(id: String): Boolean {
        val server = servers[id] ?: return false
        cancelPendingTask(id)
        server.crashRestartCount = 0
        server.disconnectReconnectCount = 0
        server.initRetryCount = 0
        server.initStartTimeMs = System.currentTimeMillis()
        return doConnect(server)
    }

    /**
     * 实际执行连接操作：启动进程 + 启动 stdout/stderr 后台读取线程 + 握手。
     */
    private fun doConnect(server: McpServer): Boolean {
        try {
            setServerState(server, State.INITIALIZING)

            // 清理上个进程残留的 reader/writer 线程
            stopStdioThreads(server)

            val pb = ProcessBuilder(server.config.command, *server.config.args.toTypedArray())
            pb.directory(File(project.basePath ?: "."))
            server.config.env.forEach { (k, v) -> pb.environment()[k] = v }
            server.process = pb.start()

            // 初始化 stdin 写入器
            server.stdinWriter = server.process!!.outputStream.bufferedWriter()

            // 重置计数器
            server.nonJsonConsecutiveCount.set(0)
            server.responseQueue.clear()

            // 启动 stdout 后台读取线程（持续解析 JSON-RPC 行，按 §四 规则处理非 JSON 行）
            startStdoutReader(server)

            // 启动 stderr 后台读取线程（记录 INFO 日志，不视为协议错误）
            startStderrReader(server)

            // 注册进程退出监听，检测 CRASHED
            server.process!!.onExit().thenAccept { process ->
                if (server.state == State.INITIALIZING) {
                    handleInitFailure(server)
                } else if (server.state == State.RUNNING) {
                    server.process = null
                    handleCrash(server)
                }
            }

            // JSON-RPC 初始化握手（5s 超时，从 responseQueue 中等待响应）
            val handshakeSuccess = performHandshake(server)

            if (handshakeSuccess) {
                server.initRetryCount = 0

                // 发送 initialized 通知
                sendJsonRpcNotification(server, "notifications/initialized", null)

                // 获取 tools/list
                val toolNames = fetchAndRegisterTools(server)
                setServerState(server, State.RUNNING)
                LOG.info("MCP Server [${server.config.id}] 连接成功，注册 ${toolNames.size} 个工具: $toolNames")
                return true
            } else {
                if (server.process?.isAlive == true) {
                    handleInitFailure(server)
                } else {
                    stopStdioThreads(server)
                    server.process = null
                    setServerState(server, State.ERROR)
                }
                return false
            }
        } catch (e: Exception) {
            stopStdioThreads(server)
            server.process = null
            server.lastErrorMessage = e.message ?: "启动失败"
            setServerState(server, State.ERROR)
            LOG.warn("MCP Server [${server.config.id}] 启动失败", e)
            return false
        }
    }

    /**
     * JSON-RPC 初始化握手：发送 initialize 请求，在 responseQueue 中等待 5 秒响应。
     */
    private fun performHandshake(server: McpServer): Boolean {
        val process = server.process ?: return false
        if (!process.isAlive) return false

        val initializeRequest = buildJsonRpcRequest(
            "initialize",
            mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to "code-assistant",
                    "version" to "1.0.0"
                )
            )
        )

        return try {
            sendJsonRpcMessage(server, initializeRequest)

            // 从 responseQueue 等待响应（5s 超时）
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
            while (System.currentTimeMillis() < deadline) {
                val response = server.responseQueue.poll(200, TimeUnit.MILLISECONDS)
                if (response != null) {
                    return try {
                        val json = JsonParser.parseString(response)
                        json.isJsonObject && json.asJsonObject.has("result")
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!process.isAlive) return false
            }
            LOG.warn("MCP Server [${server.config.id}] 初始化握手超时 (5s)")
            false
        } catch (e: Exception) {
            LOG.warn("MCP Server [${server.config.id}] 初始化握手异常", e)
            false
        }
    }

    /**
     * 发送 tools/list 请求，获取工具列表并注册到 ToolRegistry。
     * 工具名加前缀 `serverName/toolName`，同名时内置工具优先。
     * 校验工具 Schema（name/description/inputSchema）。
     * 返回注册成功的工具名称列表。
     */
    private fun fetchAndRegisterTools(server: McpServer): List<String> {
        val registered = mutableListOf<String>()
        val serverId = server.config.id
        try {
            val toolsListRequest = buildJsonRpcRequest("tools/list", null)
            sendJsonRpcMessage(server, toolsListRequest)

            // 从 responseQueue 等待响应（15s 超时）
            val response = waitForResponseFromQueue(server, TimeUnit.SECONDS.toMillis(15))
            if (response == null) {
                LOG.warn("MCP Server [$serverId] tools/list 超时")
                return registered
            }

            val json = try {
                JsonParser.parseString(response).asJsonObject
            } catch (_: Exception) {
                LOG.warn("MCP Server [$serverId] tools/list 响应 JSON 解析失败")
                return registered
            }

            val toolsArray = json
                .getAsJsonObject("result")
                ?.getAsJsonArray("tools") ?: return registered

            for (toolElement in toolsArray) {
                val toolObj = toolElement.asJsonObject ?: continue
                val rawName = toolObj.get("name")?.asString ?: continue
                val description = toolObj.get("description")?.asString ?: ""
                val inputSchema = toolObj.getAsJsonObject("inputSchema")

                // Schema 校验
                if (!isValidToolSchema(rawName, description, inputSchema)) {
                    LOG.warn("MCP Server [$serverId] 工具 [$rawName] Schema 校验失败，跳过注册")
                    continue
                }

                // 注册到 ToolRegistry，前缀 `serverName/toolName`
                val prefixedName = "$serverId/$rawName"
                if (ToolRegistry.get(prefixedName) != null) {
                    LOG.info("MCP 工具 [$prefixedName] 已在 ToolRegistry 中，跳过")
                    registered.add(prefixedName)
                    continue
                }

                ToolRegistry.register(
                    prefixedName,
                    McpToolStub::class.java,
                    ToolRegistry.ToolInfo(prefixedName, description, "")
                )
                server.registeredToolNames.add(prefixedName)
                registered.add(prefixedName)
            }
        } catch (e: Exception) {
            LOG.warn("MCP Server [$serverId] tools/list 处理失败", e)
        }
        return registered
    }

    /**
     * 从 responseQueue 中带超时等待响应。
     */
    private fun waitForResponseFromQueue(server: McpServer, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val response =
                server.responseQueue.poll(remaining.coerceAtMost(500), TimeUnit.MILLISECONDS)
            if (response != null) return response
        }
        return null
    }

    /**
     * 校验 tools/list 返回的工具 Schema。
     * - name: 非空，仅允许 [a-zA-Z0-9_-]+，长度 <= 64
     * - description: 非空字符串，长度 <= 1024
     * - inputSchema: 有效 JSON Schema（type: "object"、properties 非空 Map）
     */
    private fun isValidToolSchema(
        name: String,
        description: String,
        inputSchema: JsonObject?
    ): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) return false
        if (description.isEmpty() || description.length > 1024) return false
        if (inputSchema != null) {
            if (inputSchema.get("type")?.asString != "object") return false
            val properties = inputSchema.getAsJsonObject("properties")
            if (properties == null || properties.size() == 0) return false
        }
        return true
    }

    /**
     * 后台 stdout 读取线程。
     * 逐行解析，按 docs/mcp.md §四 "Server 输出的非 JSON-RPC 处理" 规则：
     * - 非 JSON-RPC 行（无法解析为 {"jsonrpc":"2.0",...}）：跳过，记录 WARN 日志
     * - 连续 100 行非 JSON-RPC → 判定 Server 异常，强制断开
     * - 有效 JSON-RPC 行：重置计数器，放入 responseQueue 等待消费
     */
    private fun startStdoutReader(server: McpServer) {
        val process = server.process ?: return
        val serverId = server.config.id
        server.stdoutReaderThread = Thread(Runnable {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    val trimmed = currentLine.trim()
                    if (trimmed.isEmpty()) continue

                    if (isValidJsonRpcLine(trimmed)) {
                        // 有效 JSON-RPC 行：重置计数器，放入响应队列
                        server.nonJsonConsecutiveCount.set(0)
                        server.responseQueue.offer(trimmed)
                    } else {
                        // 非 JSON-RPC 行：跳过，记录 WARN 日志，计数器+1
                        val count = server.nonJsonConsecutiveCount.incrementAndGet()
                        LOG.warn(
                            "MCP Server [$serverId] stdout 非 JSON-RPC 行 (连续第 $count 行): ${
                                trimmed.take(
                                    200
                                )
                            }"
                        )

                        if (count >= NON_JSON_CONSECUTIVE_MAX) {
                            LOG.error("MCP Server [$serverId] 连续 $count 行非 JSON-RPC，判定异常，强制断开")
                            handleDisconnection(serverId)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // stdout 读取线程因进程退出等原因终止，正常情况
                LOG.info("MCP Server [$serverId] stdout 读取线程退出: ${e.message}")
            }
        }, "mcp-stdout-$serverId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 后台 stderr 读取线程。
     * 记录 INFO 日志，不视为协议错误（对齐 mcp.md §四）。
     */
    private fun startStderrReader(server: McpServer) {
        val process = server.process ?: return
        val serverId = server.config.id
        server.stderrReaderThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    LOG.info("MCP Server [$serverId] stderr: ${currentLine.take(500)}")
                }
            } catch (e: Exception) {
                // stderr 读取线程退出，忽略
            }
        }, "mcp-stderr-$serverId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 检查一行文本是否为有效的 JSON-RPC 消息。
     * 条件：可解析为 JSON 对象，且包含 "jsonrpc":"2.0" 字段。
     */
    private fun isValidJsonRpcLine(line: String): Boolean {
        return try {
            val json = JsonParser.parseString(line)
            if (!json.isJsonObject) return false
            val obj = json.asJsonObject
            obj.has("jsonrpc") && obj.get("jsonrpc").asString == "2.0"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 发送 JSON-RPC 请求到 MCP Server 的 stdin。
     */
    fun sendRequest(serverId: String, requestJson: String): Boolean {
        val server = servers[serverId] ?: return false
        return try {
            sendJsonRpcMessage(server, JsonParser.parseString(requestJson).asJsonObject)
            true
        } catch (e: Exception) {
            LOG.warn("MCP Server [$serverId] 发送 JSON-RPC 请求失败", e)
            false
        }
    }

    /**
     * 从响应队列中获取响应（非阻塞），返回 null 表示暂无响应。
     */
    fun pollResponse(serverId: String): String? {
        val server = servers[serverId] ?: return null
        return server.responseQueue.poll()
    }

    /**
     * 处理初始化失败：使用退避策略重试。
     */
    private fun handleInitFailure(server: McpServer) {
        val elapsedMs = System.currentTimeMillis() - server.initStartTimeMs
        if (elapsedMs >= TimeUnit.MINUTES.toMillis(3)) {
            server.process?.let {
                it.destroy()
                try {
                    it.waitFor(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
                if (it.isAlive) it.destroyForcibly()
            }
            stopStdioThreads(server)
            server.process = null
            server.lastErrorMessage = "初始化超时（超过 3 分钟）"
            setServerState(server, State.INIT_ERROR)
            return
        }

        val delayMs = when (server.initRetryCount) {
            0 -> TimeUnit.SECONDS.toMillis(2)
            1 -> TimeUnit.SECONDS.toMillis(5)
            2 -> TimeUnit.SECONDS.toMillis(10)
            else -> TimeUnit.SECONDS.toMillis(15)
        }

        server.initRetryCount++

        server.process?.let {
            it.destroy()
            try {
                it.waitFor(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            if (it.isAlive) it.destroyForcibly()
        }
        stopStdioThreads(server)
        server.process = null

        scheduleTask(server.config.id, delayMs) {
            doConnect(server)
        }
    }

    /**
     * 处理进程崩溃：CRASHED 后等 2s 自动重启 1 次。
     */
    private fun handleCrash(server: McpServer) {
        stopStdioThreads(server)
        val exitCode = server.process?.exitValue()
        server.process = null
        server.lastErrorMessage =
            if (exitCode != null) "Process exited with code $exitCode" else "Process exited unexpectedly"
        setServerState(server, State.CRASHED)

        if (server.crashRestartCount < 1) {
            server.crashRestartCount++
            scheduleTask(server.config.id, TimeUnit.SECONDS.toMillis(2)) {
                server.disconnectReconnectCount = 0
                server.initRetryCount = 0
                doConnect(server)
            }
        }
    }

    /**
     * 处理 JSON-RPC 断连：每 5s 自动重连，最多 10 次。
     */
    fun handleDisconnection(id: String) {
        val server = servers[id] ?: return
        if (server.state != State.RUNNING && server.state != State.DISCONNECTED) return

        stopStdioThreads(server)
        setServerState(server, State.DISCONNECTED)

        if (server.disconnectReconnectCount < 10) {
            server.disconnectReconnectCount++
            cancelPendingTask(id)
            scheduleTask(id, TimeUnit.SECONDS.toMillis(5)) {
                server.crashRestartCount = 0
                server.initRetryCount = 0
                server.initStartTimeMs = System.currentTimeMillis()
                val oldProcess = server.process
                oldProcess?.let {
                    it.destroy()
                    try {
                        it.waitFor(2, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                    }
                    if (it.isAlive) it.destroyForcibly()
                }
                server.process = null
                doConnect(server)
            }
        }
    }

    /**
     * 断开 MCP Server 连接。
     * 注意：按文档要求，断连时不从 ToolRegistry 注销工具。
     * LLM 下一轮调用时看到的是 "Server 断连" 错误，而非 "工具不存在"。
     */
    fun disconnect(id: String) {
        val server = servers[id] ?: return
        cancelPendingTask(id)
        server.process?.let {
            it.destroy()
            try {
                it.waitFor(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            if (it.isAlive) it.destroyForcibly()
        }
        stopStdioThreads(server)
        server.process = null
        server.crashRestartCount = 0
        server.disconnectReconnectCount = 0
        server.initRetryCount = 0
        server.responseQueue.clear()
        server.nonJsonConsecutiveCount.set(0)
        setServerState(server, State.STOPPED)
    }

    /**
     * 获取指定 Server 已注册的工具列表。
     */
    fun getTools(serverId: String): List<AgentTool> {
        val server = servers[serverId] ?: return emptyList()
        return server.registeredToolNames.map { toolName ->
            val info = ToolRegistry.getToolInfo(toolName)
            AgentTool(
                name = toolName,
                description = info?.description ?: ""
            )
        }
    }

    fun testConnection(id: String): ConnectionResult {
        val server = servers[id]
        if (server == null) {
            return ConnectionResult(success = false, errorMessage = "Server 不存在")
        }
        val startTime = System.currentTimeMillis()
        return try {
            val writer = server.stdinWriter
            if (writer == null) {
                return ConnectionResult(success = false, errorMessage = "未建立连接通道")
            }
            val initializeRequest = buildJsonRpcRequest(
                "initialize",
                mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf<String, Any>(),
                    "clientInfo" to mapOf(
                        "name" to "code-assistant",
                        "version" to "1.0.0"
                    )
                )
            )
            sendJsonRpcMessage(server, initializeRequest)
            val response = waitForResponseFromQueue(server, TimeUnit.SECONDS.toMillis(5))
            if (response != null) {
                val json = JsonParser.parseString(response).asJsonObject
                if (json.has("result")) {
                    // 测试连接后获取 tools/list 得到工具数
                    val toolsListRequest = buildJsonRpcRequest("tools/list", null)
                    sendJsonRpcMessage(server, toolsListRequest)
                    val toolsResponse =
                        waitForResponseFromQueue(server, TimeUnit.SECONDS.toMillis(5))
                    val toolCount = toolsResponse?.let {
                        try {
                            JsonParser.parseString(it).asJsonObject
                                .getAsJsonObject("result")
                                ?.getAsJsonArray("tools")?.size()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    ConnectionResult(
                        success = true,
                        toolCount = toolCount,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                } else {
                    ConnectionResult(
                        success = false,
                        errorMessage = "握手无响应",
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                ConnectionResult(
                    success = false,
                    errorMessage = "握手无响应",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            ConnectionResult(
                success = false,
                errorMessage = e.message,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    fun getServer(id: String): McpServer? = servers[id]

    fun getAllServers(): List<McpServer> = servers.values.toList()

    fun dispose() {
        scheduler.shutdownNow()
        servers.keys.forEach { disconnect(it) }
        unregisterInstance(project)
    }

    // ── JSON-RPC 协议辅助方法 ──

    private fun buildJsonRpcRequest(method: String, params: Any?): JsonObject {
        val request = JsonObject()
        request.addProperty("jsonrpc", "2.0")
        request.addProperty("id", UUID.randomUUID().toString())
        request.addProperty("method", method)
        if (params != null) {
            request.add("params", gson.toJsonTree(params))
        }
        return request
    }

    private fun sendJsonRpcNotification(server: McpServer, method: String, params: Any?) {
        val notification = JsonObject()
        notification.addProperty("jsonrpc", "2.0")
        notification.addProperty("method", method)
        if (params != null) {
            notification.add("params", gson.toJsonTree(params))
        }
        sendJsonRpcMessage(server, notification)
    }

    private fun sendJsonRpcMessage(server: McpServer, message: JsonObject) {
        val writer = server.stdinWriter ?: return
        synchronized(writer) {
            writer.write(gson.toJson(message) + "\n")
            writer.flush()
        }
    }

    // ── 内部辅助方法 ──

    /** 更新状态并触发回调 */
    private fun setServerState(server: McpServer, newState: State) {
        if (server.state == newState) return
        server.state = newState
        onServerStateChanged?.invoke(server.config.id, newState)
    }

    /** 取消指定 server 的待执行恢复任务 */
    private fun cancelPendingTask(id: String) {
        pendingTasks.remove(id)?.cancel(false)
    }

    /** 延迟执行恢复任务 */
    private fun scheduleTask(id: String, delayMs: Long, action: () -> Unit) {
        cancelPendingTask(id)
        val future = scheduler.schedule(Runnable { action() }, delayMs, TimeUnit.MILLISECONDS)
        pendingTasks[id] = future
    }

    /**
     * 停止 stdout/stderr 后台读取线程并关闭 stdin writer。
     */
    private fun stopStdioThreads(server: McpServer) {
        try {
            server.stdinWriter?.close()
        } catch (_: Exception) {
        }
        server.stdinWriter = null

        server.stdoutReaderThread?.let {
            try {
                it.interrupt()
            } catch (_: Exception) {
            }
        }
        server.stdoutReaderThread = null

        server.stderrReaderThread?.let {
            try {
                it.interrupt()
            } catch (_: Exception) {
            }
        }
        server.stderrReaderThread = null
    }

    private fun persist() {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(servers.values.map { it.config }))
    }

    companion object {
        private val LOG = Logger.getInstance(McpManager::class.java)

        /** 连续非 JSON-RPC 行阈值，超过此值判定 Server 异常 */
        private const val NON_JSON_CONSECUTIVE_MAX = 100

        /** 按 Project 查找已创建的 McpManager 实例，供 ToolExecutor 等组件查询 Server 状态 */
        private val instances = mutableMapOf<Project, McpManager>()

        fun registerInstance(project: Project, manager: McpManager) {
            instances[project] = manager
        }

        fun unregisterInstance(project: Project) {
            instances.remove(project)
        }

        fun getInstance(project: Project): McpManager? = instances[project]

        /**
         * 从 MCP 工具名（格式 `serverName/toolName`）提取 serverName。
         * 如果工具名不包含 "/" 前缀则返回 null。
         */
        fun extractServerId(toolName: String): String? {
            val slashIndex = toolName.indexOf('/')
            if (slashIndex <= 0) return null
            val prefix = toolName.substring(0, slashIndex)
            // 排除内置工具前缀（如 Plan 工具名不含 /）
            return if (prefix.isNotEmpty() && prefix.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                prefix
            } else {
                null
            }
        }
    }
}

/**
 * 占位工具类，用于 MCP 工具在 ToolRegistry 中的注册。
 * MCP 工具的实际执行通过 JSON-RPC 调用 MCP Server 完成，不走 ToolExecutor 的类反射调用。
 */
internal class McpToolStub
