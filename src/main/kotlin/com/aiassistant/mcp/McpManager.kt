package com.aiassistant.mcp

import com.aiassistant.agent.AgentTool
import com.intellij.openapi.project.Project

/**
 * MCP 服务器管理器 — 管理多个 MCP 服务器的生命周期。
 * 支持全局配置（设置页）和项目级配置（.code-assistant/mcp.json）。
 * 自动检测崩溃进程并尝试重启。
 */
class McpManager(private val project: Project) {

    private val clients = mutableMapOf<String, McpClient>()
    private val configs = mutableMapOf<String, McpServerConfig>()
    private var restartCount = mutableMapOf<String, Int>()

    companion object {
        private const val MAX_RESTART_COUNT = 3
    }

    /**
     * 加载并连接所有 MCP 服务器。
     * 优先从 Claude 的 ~/.claude.json 读取配置，fallback 到项目 .mcp.json。
     */
    fun loadAndConnect(): List<AgentTool> {
        val allTools = mutableListOf<AgentTool>()

        // 1. Claude 全局配置 ~/.claude.json
        val claudeConfigs = McpServerConfig.fromClaudeConfig()
        for (config in claudeConfigs) {
            if (!config.enabled) continue
            configs[config.name] = config
            val tools = connectAndDiscover(config)
            allTools.addAll(tools)
        }

        // 2. 项目级 .mcp.json（Claude Code 格式）
        val projectConfigs = project.basePath?.let { McpServerConfig.fromProjectMcpJson(it) } ?: emptyList()
        for (config in projectConfigs) {
            if (!config.enabled) continue
            if (clients.containsKey(config.name)) continue
            configs[config.name] = config
            val tools = connectAndDiscover(config)
            allTools.addAll(tools)
        }

        return allTools
    }

    private fun connectAndDiscover(config: McpServerConfig): List<AgentTool> {
        val client = McpClient(config)
        if (!client.connect()) {
            return emptyList()
        }
        clients[config.name] = client
        restartCount[config.name] = 0
        return client.discoverTools()
    }

    /**
     * 检查所有已连接服务器的健康状态，对崩溃的服务器尝试重启
     * @return 成功重启的服务器名称列表
     */
    fun healthCheck(): List<String> {
        val recovered = mutableListOf<String>()
        for ((name, client) in clients.toMap()) {
            if (!client.isConnected()) {
                val config = configs[name] ?: continue
                val retries = restartCount[name] ?: 0
                if (retries < MAX_RESTART_COUNT) {
                    // 清理旧进程
                    client.disconnect()
                    // 尝试重启
                    val newClient = McpClient(config)
                    if (newClient.connect()) {
                        clients[name] = newClient
                        restartCount[name] = retries + 1
                        recovered.add(name)
                    }
                }
            }
        }
        return recovered
    }

    /**
     * 断开所有 MCP 连接，清理僵尸进程
     */
    fun disconnectAll() {
        for ((_, client) in clients) {
            client.disconnect()
        }
        clients.clear()
        configs.clear()
        restartCount.clear()
    }

    fun getConnectedServers(): List<String> = clients.keys.toList()
}
