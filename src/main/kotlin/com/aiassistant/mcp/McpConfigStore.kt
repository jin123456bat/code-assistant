package com.aiassistant.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * MCP 配置持久化存储。
 *
 * 支持 3 级配置文件加载，高优先级覆盖低优先级：
 * 1. `<project>/.code-assistant/mcp-config.json`（主配置文件，最高优先级）
 * 2. `<project>/.mcp.json`（兼容 Claude Code MCP 配置格式）
 * 3. `~/.claude/.mcp.json`（兼容 Claude Code 全局 MCP 配置，最低优先级）
 *
 * 配置合并规则：同 id 的 Server 后加载覆盖先加载。
 */
class McpConfigStore(private val project: Project) {

    private val gson = Gson()

    /** 主配置文件路径：<project>/.code-assistant/mcp-config.json */
    val configPath: Path
        get() = Paths.get(
            project.basePath ?: ".",
            ".code-assistant/mcp-config.json"
        )

    /** 项目级兼容配置：<project>/.mcp.json */
    val projectDotMcpPath: Path get() = Paths.get(project.basePath ?: ".", ".mcp.json")

    /** 全局兼容配置：~/.claude/.mcp.json */
    val globalDotMcpPath: Path
        get() = Paths.get(System.getProperty("user.home"), ".claude", ".mcp.json")

    private val configFile: File get() = configPath.toFile()

    /**
     * 从 3 级配置文件加载 MCP Server 配置列表。
     *
     * 加载顺序：~/.claude/.mcp.json → .mcp.json → mcp-config.json
     * 同 id Server 后加载覆盖先加载，确保项目主配置拥有最高优先级。
     */
    fun load(): McpConfig {
        val allServers = mutableMapOf<String, McpManager.McpServerConfig>()

        // 第 3 级：全局兼容配置 ~/.claude/.mcp.json（最低优先级）
        loadFromFile(globalDotMcpPath.toFile())?.forEach { config ->
            allServers[config.id] = config
        }

        // 第 2 级：项目兼容配置 .mcp.json（覆盖全局同 id）
        loadFromFile(projectDotMcpPath.toFile())?.forEach { config ->
            allServers[config.id] = config
        }

        // 第 1 级：项目主配置 .code-assistant/mcp-config.json（最高优先级）
        loadFromFile(configFile)?.forEach { config ->
            allServers[config.id] = config
        }

        LOG.info(
            "MCP 配置加载完成: 共 ${allServers.size} 个 Server, " +
                    "mcp-config.json=${configFile.exists()}, " +
                    ".mcp.json=${projectDotMcpPath.toFile().exists()}, " +
                    "~/.claude/.mcp.json=${globalDotMcpPath.toFile().exists()}"
        )

        return McpConfig(servers = allServers.values.toList())
    }

    /**
     * 将 MCP Server 配置列表持久化到主配置文件 (.code-assistant/mcp-config.json)。
     * 自动创建父目录。
     */
    fun save(config: McpConfig) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(config))
    }

    /**
     * 从指定 JSON 文件加载 Server 配置列表。
     * 文件不存在或解析失败返回 null。
     */
    private fun loadFromFile(file: File): List<McpManager.McpServerConfig>? {
        if (!file.exists()) return null
        return try {
            val json = JsonParser.parseString(file.readText())
            when {
                json.isJsonArray -> {
                    val type = object : TypeToken<List<McpManager.McpServerConfig>>() {}.type
                    gson.fromJson(json, type)
                }

                json.isJsonObject -> loadFromObject(json.asJsonObject)
                else -> emptyList()
            }
        } catch (e: Exception) {
            LOG.warn("MCP 配置文件解析失败: ${file.absolutePath}", e)
            null
        }
    }

    private fun loadFromObject(root: JsonObject): List<McpManager.McpServerConfig> {
        if (root.has("servers") && root.get("servers").isJsonArray) {
            val type = object : TypeToken<List<McpManager.McpServerConfig>>() {}.type
            return gson.fromJson(root.getAsJsonArray("servers"), type)
        }

        if (!root.has("mcpServers") || !root.get("mcpServers").isJsonObject) return emptyList()
        return root.getAsJsonObject("mcpServers").entrySet().mapNotNull { (id, value) ->
            val config = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val transport = config.stringOrNull("transport")
                ?: config.stringOrNull("type")
                ?: if (config.has("url")) "http" else "stdio"
            McpManager.McpServerConfig(
                id = id,
                command = config.stringOrNull("command") ?: "",
                args = config.stringList("args"),
                env = config.stringMap("env"),
                transport = transport,
                url = config.stringOrNull("url"),
                enabled = config.boolOrNull("enabled") ?: true
            )
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.stringList(key: String): List<String> {
        val value = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return value.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
    }

    private fun JsonObject.stringMap(key: String): Map<String, String> {
        val value = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return value.entrySet().mapNotNull { (name, item) ->
            val stringValue =
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            stringValue?.let { name to it }
        }.toMap()
    }

    /**
     * MCP 配置容器。
     */
    data class McpConfig(
        val servers: List<McpManager.McpServerConfig> = emptyList()
    )

    private companion object {
        private val LOG = Logger.getInstance(McpConfigStore::class.java)
    }
}
