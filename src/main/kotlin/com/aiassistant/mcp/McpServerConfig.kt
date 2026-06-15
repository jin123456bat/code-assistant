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
    val url: String = "",         // http: http://localhost:3000
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
         * 从 JSON 字符串解析多个 MCP 服务器配置
         */
        fun parseConfigs(json: String): List<McpServerConfig> {
            val configs = mutableListOf<McpServerConfig>()

            // 查找 mcpServers 对象中的每个条目
            val serversPattern = Regex(""""mcpServers"\s*:\s*\{""")
            val serversStart = serversPattern.find(json)?.range?.last ?: return configs

            // 简化解析：提取每个 "name": { ... } 对象
            val entryPattern = Regex(""""([\w-]+)"\s*:\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""")
            for (match in entryPattern.findAll(json.substring(serversStart))) {
                val name = match.groupValues[1]
                val body = match.groupValues[2]
                val config = parseServerBody(name, body)
                configs.add(config)
            }
            return configs
        }

        private fun parseServerBody(name: String, body: String): McpServerConfig {
            val command = extractJsonValue(body, "command")
            val url = extractJsonValue(body, "url")
            val type = extractJsonValue(body, "type")  // Claude 格式: "stdio" | "http"
            val transport = when {
                type.equals("http", ignoreCase = true) -> "http"
                url.isNotBlank() -> "http"  // fallback: 有 url 就是 http
                else -> "stdio"
            }

            // 解析 args 数组
            val args = mutableListOf<String>()
            val argsPattern = Regex(""""args"\s*:\s*\[([^\]]*)\]""")
            val argsMatch = argsPattern.find(body)
            if (argsMatch != null) {
                val argsStr = argsMatch.groupValues[1]
                val argPattern = Regex(""""((?:[^"\\]|\\.)*)"""")
                argPattern.findAll(argsStr).forEach { args.add(it.groupValues[1]) }
            }

            // 解析 env 对象
            val env = mutableMapOf<String, String>()
            val envPattern = Regex(""""env"\s*:\s*\{([^}]*)\}""")
            val envMatch = envPattern.find(body)
            if (envMatch != null) {
                val envStr = envMatch.groupValues[1]
                val kvPattern = Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                kvPattern.findAll(envStr).forEach { env[it.groupValues[1]] = it.groupValues[2] }
            }

            return McpServerConfig(name = name, transport = transport, command = command, args = args, env = env, url = url)
        }

        private fun extractJsonValue(json: String, key: String): String {
            val pattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            return pattern.find(json)?.groupValues?.getOrNull(1) ?: ""
        }

        /**
         * 序列化为 JSON 字符串
         */
        fun toJson(configs: List<McpServerConfig>): String {
            val serversJson = configs.joinToString(",\n") { config ->
                buildString {
                    append("    \"${config.name}\": {\n")
                    if (config.transport == "http") {
                        append("      \"url\": \"${JsonUtils.escapeJson(config.url)}\"\n")
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
