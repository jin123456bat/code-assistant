package com.aiassistant.mcp

import com.aiassistant.shared.JsonUtils

/**
 * MCP 服务器配置数据类
 */
data class McpServerConfig(
    val name: String,
    val transport: String,        // "stdio" | "http"
    val command: String = "",     // stdio: npx / node / python
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",         // http: JSON-RPC endpoint（如 http://localhost:3000/message）
    val sseUrl: String = "",      // SSE 推送端点（如 http://localhost:3000/sse），为空时复用 url
    val enabled: Boolean = true
) {
    companion object {
        /**
         * 从 Claude 的 ~/.claude.json 读取 MCP 配置。
         * Claude Code 格式：{ "mcpServers": { "name": { "type": "stdio", "command": "...", "args": [...] } } }
         */
        fun fromClaudeConfig(): List<McpServerConfig> {
            val home = System.getProperty("user.home") ?: return emptyList()
            val configFile = java.io.File(home, ".claude.json")
            if (!configFile.exists()) return emptyList()
            return try {
                parseConfigs(configFile.readText())
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * 从项目 .mcp.json 读取 MCP 配置（Claude Code 项目级格式）。
         */
        fun fromProjectMcpJson(projectBasePath: String): List<McpServerConfig> {
            val configFile = java.io.File(projectBasePath, ".mcp.json")
            if (!configFile.exists()) return emptyList()
            return try {
                parseConfigs(configFile.readText())
            } catch (_: Exception) {
                emptyList()
            }
        }

        /**
         * 从 JSON 字符串解析多个 MCP 服务器配置（使用 Gson）
         */
        fun parseConfigs(json: String): List<McpServerConfig> {
            val configs = mutableListOf<McpServerConfig>()
            return try {
                val gson = com.google.gson.Gson()
                @Suppress("UNCHECKED_CAST")
                val root = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return configs
                @Suppress("UNCHECKED_CAST")
                val servers = root["mcpServers"] as? Map<String, Any> ?: return configs

                for ((name, serverObj) in servers) {
                    @Suppress("UNCHECKED_CAST")
                    val obj = serverObj as? Map<String, Any> ?: continue
                    val command = obj["command"]?.toString() ?: ""
                    val url = obj["url"]?.toString() ?: ""
                    val sseUrl = obj["sseUrl"]?.toString() ?: ""
                    val type = obj["type"]?.toString() ?: ""
                    val transport = when {
                        type.equals("http", ignoreCase = true) -> "http"
                        url.isNotBlank() -> "http"
                        else -> "stdio"
                    }
                    // args 数组
                    val args = (obj["args"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    // env 对象
                    @Suppress("UNCHECKED_CAST")
                    val envMap = obj["env"] as? Map<String, Any> ?: emptyMap()
                    val env = envMap.mapValues { it.value.toString() }

                    configs.add(McpServerConfig(name = name, transport = transport,
                        command = command, args = args, env = env, url = url, sseUrl = sseUrl))
                }
                configs
            } catch (_: Exception) {
                configs
            }
        }

        /**
         * 序列化为 JSON 字符串
         */
        fun toJson(configs: List<McpServerConfig>): String {
            val serversJson = configs.joinToString(",\n") { config ->
                buildString {
                    append("    \"${JsonUtils.escapeJson(config.name)}\": {\n")
                    append("      \"type\": \"${config.transport}\",\n")
                    if (config.transport == "http") {
                        append("      \"url\": \"${JsonUtils.escapeJson(config.url)}\"")
                        if (config.sseUrl.isNotBlank()) {
                            append(",\n      \"sseUrl\": \"${JsonUtils.escapeJson(config.sseUrl)}\"")
                        }
                        append("\n")
                    } else {
                        append("      \"command\": \"${JsonUtils.escapeJson(config.command)}\",\n")
                        if (config.args.isNotEmpty()) {
                            val argsStr = config.args.joinToString(", ") { "\"${JsonUtils.escapeJson(it)}\"" }
                            append("      \"args\": [$argsStr],\n")
                        }
                        if (config.env.isNotEmpty()) {
                            val envStr = config.env.entries.joinToString(", ") {
                                "\"${it.key}\": \"${JsonUtils.escapeJson(it.value)}\""
                            }
                            append("      \"env\": {$envStr}\n")
                        } else {
                            append("      \"env\": {}\n")
                        }
                    }
                    append("    }")
                }
            }
            return "{\n  \"mcpServers\": {\n$serversJson\n  }\n}"
        }

    }
}
