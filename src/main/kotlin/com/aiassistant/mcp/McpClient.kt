package com.aiassistant.mcp

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * MCP (Model Context Protocol) JSON-RPC 客户端。
 * 通过 stdio 与 MCP 服务器通信，发现并调用工具。
 */
class McpClient(private val config: McpServerConfig) {

    companion object {
        private val readExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "mcp-read-${System.currentTimeMillis()}").apply { isDaemon = true }
        }
    }

    private var process: Process? = null
    private var initialized: Boolean = false
    private val discoveredTools = mutableListOf<McpToolAdapter>()

    /**
     * 启动 MCP 服务器进程并完成初始化握手
     */
    fun connect(): Boolean {
        if (config.transport != "stdio" || config.command.isBlank()) return false

        return try {
            val env = mutableMapOf<String, String>()
            env.putAll(System.getenv())
            env.putAll(config.env)

            val pb = ProcessBuilder(listOf(config.command) + config.args)
                .redirectErrorStream(false)
            pb.environment().putAll(env)

            process = pb.start()

            // MCP 初始化握手
            val initRequest = buildJsonRpc("initialize", """
                {"protocolVersion":"0.1.0","capabilities":{},"clientInfo":{"name":"CodeAssistant","version":"1.0.0"}}
            """.trimIndent())

            sendRequest(initRequest)
            val initResponse = readResponse()
            if (initResponse == null || initResponse.contains("\"error\"")) {
                disconnect()
                return false
            }

            // 发送 initialized 通知
            val initializedNotif = buildJsonRpcNotification("initialized", "{}")
            sendRequest(initializedNotif)

            initialized = true
            true
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    /**
     * 发现 MCP 服务器提供的工具列表
     */
    fun discoverTools(): List<AgentTool> {
        if (!initialized) return emptyList()

        return try {
            val request = buildJsonRpc("tools/list", "{}")
            sendRequest(request)
            val response = readResponse()
            if (response == null || response.contains("\"error\"")) return emptyList()

            val tools = parseTools(response)
            discoveredTools.clear()
            discoveredTools.addAll(tools)
            tools
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 调用 MCP 工具
     */
    fun callTool(toolName: String, arguments: Map<String, String>, project: Project): ToolResult {
        if (!initialized) return ToolResult.err("MCP 服务器未连接: ${config.name}")

        return try {
            // 将参数转为 JSON 字符串
            val argsJson = arguments.entries.joinToString(",") { (k, v) ->
                "\"$k\":\"${v.replace("\"", "\\\"")}\""
            }.let { "{$it}" }

            val request = buildJsonRpc("tools/call", """
                {"name":"$toolName","arguments":$argsJson}
            """.trimIndent())

            sendRequest(request)
            val response = readResponse()
            if (response == null) {
                ToolResult.err("MCP 调用无响应: $toolName")
            } else if (response.contains("\"error\"")) {
                ToolResult.err("MCP 错误: ${extractErrorMessage(response)}")
            } else {
                val content = extractResultContent(response)
                ToolResult.ok(content)
            }
        } catch (e: Exception) {
            ToolResult.err("MCP 调用失败: ${e.message}")
        }
    }

    fun disconnect() {
        initialized = false
        try {
            process?.destroyForcibly()?.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        process = null
    }

    fun isConnected(): Boolean = initialized && process?.isAlive == true

    private var requestId = 0

    private fun buildJsonRpc(method: String, params: String): String {
        requestId++
        return """{"jsonrpc":"2.0","id":$requestId,"method":"$method","params":$params}"""
    }

    private fun buildJsonRpcNotification(method: String, params: String): String {
        return """{"jsonrpc":"2.0","method":"$method","params":$params}"""
    }

    private fun sendRequest(json: String) {
        try {
            process?.outputStream?.write("${json}\n".toByteArray())
            process?.outputStream?.flush()
        } catch (_: Exception) {}
    }

    private fun readResponse(timeoutMs: Long = 10_000): String? {
        val proc = process ?: return null
        return try {
            val future = readExecutor.submit<String> {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                reader.readLine()
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (e: Exception) {
            // 检查 stderr 是否有错误
            try {
                val errReader = BufferedReader(InputStreamReader(proc.errorStream))
                val errLine = errReader.readLine()
                if (errLine != null) errLine else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseTools(response: String): List<McpToolAdapter> {
        val tools = mutableListOf<McpToolAdapter>()
        // 解析 result.tools[] 中的每个工具
        val toolPattern = Regex(""""name":"([^"]+)"[^}]*"description":"([^"]+)"[^}]*"inputSchema":\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""")
        for (match in toolPattern.findAll(response)) {
            val name = match.groupValues[1]
            val description = unescape(match.groupValues[2])
            tools.add(McpToolAdapter(name, description, this))
        }
        return tools
    }

    private fun extractResultContent(response: String): String {
        // 提取 result.content[0].text
        val textPattern = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val match = textPattern.find(response)
        return match?.groupValues?.get(1)?.let { unescape(it) } ?: response.take(1000)
    }

    private fun extractErrorMessage(response: String): String {
        val msgPattern = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return msgPattern.find(response)?.groupValues?.get(1)?.let { unescape(it) } ?: "Unknown error"
    }

    private fun unescape(s: String): String {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

/**
 * MCP 工具适配器 — 将 MCP 工具包装为 AgentTool 接口
 */
class McpToolAdapter(
    override val name: String,
    override val description: String,
    private val client: McpClient
) : AgentTool {
    override val parameters: List<ToolParameter> = emptyList() // MCP 工具参数由服务器定义

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        return client.callTool(name, params, project)
    }
}
