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
        } catch (e: Exception) {
            // 写入失败（如进程已崩溃），标记为断开
            initialized = false
            throw RuntimeException("MCP 写入失败: ${config.name}", e)
        }
    }

    private fun readResponse(timeoutMs: Long = 10_000): String? {
        val proc = process ?: return null
        val expectedId = requestId  // 快照当前请求 ID
        return try {
            val future = readExecutor.submit<String> {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                reader.readLine()
            }
            val line = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            // 验证响应 ID 匹配发送的请求 ID（顺序调用时作为防护）
            if (line != null && expectedId > 0) {
                val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(line)
                val respId = idMatch?.groupValues?.get(1)?.toIntOrNull()
                if (respId != null && respId != expectedId) {
                    // 响应 ID 不匹配，可能是并发调用导致的错乱
                    return null
                }
            }
            line
        } catch (_: TimeoutException) {
            null
        } catch (e: Exception) {
            try {
                val errReader = BufferedReader(InputStreamReader(proc.errorStream))
                val errLine = errReader.readLine()
                if (errLine != null) errLine else null
            } catch (_: Exception) {
                null
            }
        }
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
            val first = content?.firstOrNull() as? Map<*, *>
            first?.get("text") as? String ?: response.take(1000)
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
}

/**
 * MCP 工具适配器 — 将 MCP 工具包装为 AgentTool 接口
 */
class McpToolAdapter(
    override val name: String,
    override val description: String,
    override val parameters: List<ToolParameter>,
    private val client: McpClient
) : AgentTool {

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        return client.callTool(name, params, project)
    }
}
