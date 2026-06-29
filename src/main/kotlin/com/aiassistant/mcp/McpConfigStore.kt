package com.aiassistant.mcp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * MCP 配置持久化存储。
 *
 * 支持 3 级配置文件加载，后加载覆盖先加载：
 * 1. `<project>/.code-assistant/mcp-config.json`（主配置文件）
 * 2. `<project>/.mcp.json`（兼容 Claude Code MCP 配置格式）
 * 3. `~/.claude/.mcp.json`（兼容 Claude Code 全局 MCP 配置）
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
     * 加载顺序：mcp-config.json → .mcp.json → ~/.claude/.mcp.json
     * 同级文件中同 id Server 靠后的覆盖靠前的；跨级时高优先级文件覆盖低优先级。
     */
    fun load(): McpConfig {
        val allServers = mutableMapOf<String, McpManager.McpServerConfig>()

        // 第 1 级：项目主配置 .code-assistant/mcp-config.json
        loadFromFile(configFile)?.forEach { config ->
            allServers[config.id] = config
        }

        // 第 2 级：项目兼容配置 .mcp.json（覆盖第 1 级同 id）
        loadFromFile(projectDotMcpPath.toFile())?.forEach { config ->
            allServers[config.id] = config
        }

        // 第 3 级：全局兼容配置 ~/.claude/.mcp.json（覆盖第 1、2 级同 id）
        loadFromFile(globalDotMcpPath.toFile())?.forEach { config ->
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
        configFile.writeText(gson.toJson(config.servers))
    }

    /**
     * 从指定 JSON 文件加载 Server 配置列表。
     * 文件不存在或解析失败返回 null。
     */
    private fun loadFromFile(file: File): List<McpManager.McpServerConfig>? {
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<List<McpManager.McpServerConfig>>() {}.type
            val configs: List<McpManager.McpServerConfig> = gson.fromJson(file.readText(), type)
            configs
        } catch (e: Exception) {
            LOG.warn("MCP 配置文件解析失败: ${file.absolutePath}", e)
            null
        }
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
