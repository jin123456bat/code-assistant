package com.aiassistant.mcp

import com.aiassistant.agent.AgentTool
import com.intellij.openapi.project.Project
import java.util.concurrent.Executors

/**
 * MCP 变更监听器 — 服务器推送变更时通知上层更新 AgentContext 和 ToolRegistry
 */
interface McpChangeListener {
    fun onToolsChanged(serverName: String, newTools: List<AgentTool>)
    fun onPromptsChanged(newPrompts: List<McpPromptDef>)
    fun onResourcesChanged(newResources: List<McpResourceDef>)
}

/**
 * MCP 服务器管理器 — 管理多个 MCP 服务器的生命周期。
 * 支持全局配置（设置页）和项目级配置（.code-assistant/mcp.json）。
 * 自动检测崩溃进程并尝试重启。
 */
class McpManager(private val project: Project) {

    private val clients = java.util.concurrent.ConcurrentHashMap<String, McpClient>()
    private val configs = java.util.concurrent.ConcurrentHashMap<String, McpServerConfig>()
    private val restartCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** MCP 变更监听器（由 ChatViewModel 注册，用于更新 AgentContext 和 ToolRegistry） */
    @Volatile var changeListener: McpChangeListener? = null

    companion object {
        private const val MAX_RESTART_COUNT = 3
        /** 全局实例映射（按 project basePath 索引），供 ReadFileTool 等工具查找 MCP 客户端 */
        @Volatile private var instances = java.util.concurrent.ConcurrentHashMap<String, McpManager>()
        fun getInstance(projectBasePath: String?): McpManager? = projectBasePath?.let { instances[it] }
        /** 后台线程池，用于通知回调等短任务，复用线程避免频繁创建 */
        private val bgExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "mcp-mgr-${System.currentTimeMillis()}").apply { isDaemon = true }
        }
    }

    init {
        instances[project.basePath ?: ""] = this
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
        setupClientCallbacks(client, config.name)
        return client.discoverTools()
    }

    /** 处理 tools/list_changed 推送：重新发现所有服务器的工具并通过 listener 更新注册中心 */
    private fun handleToolsChanged(serverName: String) {
        // collectAllTools() 内部会调用所有客户端的 discoverTools()，无需单独刷新
        val allTools = collectAllTools()
        changeListener?.onToolsChanged(serverName, allTools)
        com.aiassistant.AppLogger.info("MCP 工具变更: $serverName, 全量 ${allTools.size} 个")
    }

    /** 处理 prompts/list_changed 推送：重新采集并通过 listener 更新 AgentContext */
    private fun handlePromptsChanged() {
        val prompts = collectPrompts()
        changeListener?.onPromptsChanged(prompts)
        com.aiassistant.AppLogger.info("MCP prompts 变更: ${prompts.size} 个")
    }

    /** 汇总所有已连接服务器的全部工具（对齐 prompts/resources 全量采集模式） */
    private fun collectAllTools(): List<AgentTool> {
        return clients.values.flatMap { it.discoverTools() }
    }

    /** 处理 resources/list_changed 推送：重新采集并通过 listener 更新 AgentContext */
    private fun handleResourcesChanged() {
        val resources = collectResources()
        changeListener?.onResourcesChanged(resources)
        com.aiassistant.AppLogger.info("MCP resources 变更: ${resources.size} 个")
    }

    /**
     * 检查所有 MCP 服务器的健康状态，对崩溃或从未成功连接的服务器尝试重启。
     * 对齐 Claude Code：定期探测 + 自动恢复。
     * @return 成功恢复的服务器名称列表
     */
    @Volatile private var healthChecking = false  // 防重入

    fun healthCheck(): List<String> {
        if (healthChecking) return emptyList()
        healthChecking = true
        try {
        val recovered = mutableListOf<String>()

        // 1. 检查已知客户端（崩溃恢复）
        for ((name, client) in clients.toMap()) {
            if (!client.isConnected()) {
                val config = configs[name] ?: continue
                recoverServer(name, config, client, recovered)
            }
        }

        // 2. 检查从未成功连接的配置（初始连接失败重试）
        for ((name, config) in configs) {
            if (!clients.containsKey(name)) {
                val retries = restartCount[name] ?: 0
                if (retries < MAX_RESTART_COUNT) {
                    restartCount[name] = retries + 1
                    val newClient = McpClient(config)
                    if (newClient.connect()) {
                        setupClientCallbacks(newClient, name)
                        clients[name] = newClient
                        // 重新发现全量工具并通过 listener 更新注册中心（collectAllTools 内部已调用 discoverTools）
                        val allTools = collectAllTools()
                        if (allTools.isNotEmpty()) {
                            changeListener?.onToolsChanged(name, allTools)
                        }
                        recovered.add(name)
                    }
                }
            }
        }

        // 3. 汇总 prompts/resources 变更
        if (recovered.isNotEmpty()) {
            val allPrompts = collectPrompts()
            val allResources = collectResources()
            if (allPrompts.isNotEmpty()) changeListener?.onPromptsChanged(allPrompts)
            if (allResources.isNotEmpty()) changeListener?.onResourcesChanged(allResources)
            com.aiassistant.AppLogger.info("MCP 健康检查恢复: ${recovered.joinToString()}")
        }

        return recovered
        } finally {
            healthChecking = false
        }
    }

    /** 恢复单个已崩溃的服务器 */
    private fun recoverServer(
        name: String, config: McpServerConfig, oldClient: McpClient, recovered: MutableList<String>
    ) {
        val retries = restartCount[name] ?: 0
        if (retries >= MAX_RESTART_COUNT) return
        val oldHandler = oldClient.notificationHandler
        val oldSampling = oldClient.samplingHandler
        oldClient.disconnect()
        val newClient = McpClient(config)
        if (newClient.connect()) {
            newClient.notificationHandler = oldHandler
            newClient.samplingHandler = oldSampling
            clients[name] = newClient
            restartCount[name] = retries + 1
            // 重新发现全量工具并通过 listener 更新注册中心（collectAllTools 内部已调用 discoverTools）
            val allTools = collectAllTools()
            if (allTools.isNotEmpty()) {
                changeListener?.onToolsChanged(name, allTools)
            }
            recovered.add(name)
        }
    }

    /** 为新客户端设置通知回调和 sampling 处理器 */
    private fun setupClientCallbacks(client: McpClient, serverName: String) {
        val mgr = this
        client.notificationHandler = object : McpNotificationHandler {
            override fun onToolsChanged() {
                bgExecutor.submit {
                    try { mgr.handleToolsChanged(serverName) } catch (_: Exception) {}
                }
            }
            override fun onPromptsChanged() {
                bgExecutor.submit {
                    try { mgr.handlePromptsChanged() } catch (_: Exception) {}
                }
            }
            override fun onResourcesChanged() {
                bgExecutor.submit {
                    try { mgr.handleResourcesChanged() } catch (_: Exception) {}
                }
            }
        }
        client.samplingHandler = McpSamplingHandler { _ -> null }
    }

    /** 向所有已连接服务器发送取消通知（对齐 Claude Code：用户中断时释放服务器资源） */
    fun cancelAll() {
        for ((_, client) in clients) {
            try { client.cancelPending() } catch (_: Exception) {}
        }
    }

    /**
     * 断开所有 MCP 连接，清理僵尸进程
     */
    fun disconnectAll() {
        // 并行断开所有客户端：每个 disconnect 最多等 10 秒进程终止，串行 5 个客户端 = 50 秒。
        // 并行执行可将总等待时间压缩到最慢客户端的 10 秒。
        val clientList = clients.values.toList()
        clients.clear()
        configs.clear()
        restartCount.clear()
        instances.remove(project.basePath ?: "")
        if (clientList.size > 1) {
            val latch = java.util.concurrent.CountDownLatch(clientList.size)
            for (client in clientList) {
                Thread({
                    try { client.disconnect() } catch (_: Exception) {}
                    latch.countDown()
                }, "mcp-disconnect-${client.hashCode()}").apply { isDaemon = true }.start()
            }
            try { latch.await(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        } else {
            clientList.forEach { try { it.disconnect() } catch (_: Exception) {} }
        }
    }

    fun getConnectedServers(): List<String> = clients.keys.toList()

    /** 汇总所有已连接服务器的 MCP prompts */
    fun collectPrompts(): List<McpPromptDef> {
        return clients.values.flatMap { it.listPrompts() }
    }

    /** 汇总所有已连接服务器的 MCP resources */
    fun collectResources(): List<McpResourceDef> {
        return clients.values.flatMap { it.listResources() }
    }

    /** 按 URI 读取 MCP 资源内容（供 ReadFileTool 路由 resource:// 请求） */
    fun readResource(uri: String): String? {
        for ((_, client) in clients) {
            val content = client.readResource(uri)
            if (content.isNotBlank()) return content
        }
        return null
    }

    /** 按名称获取 MCP prompt 渲染内容（供 McpGetPromptTool 使用） */
    fun getPrompt(name: String, args: Map<String, String>): String? {
        for ((_, client) in clients) {
            val content = client.getPrompt(name, args)
            if (content.isNotBlank()) return content
        }
        return null
    }
}
