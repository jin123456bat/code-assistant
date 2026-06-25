package com.aiassistant.mcp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.io.File

class McpManager(private val project: Project) {

    data class McpServerConfig(
        val id: String, val command: String, val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(), val transport: String = "stdio",
        val url: String? = null, val enabled: Boolean = true
    )

    enum class State { CONFIGURED, INITIALIZING, RUNNING, CRASHED, STOPPED, DISCONNECTED, ERROR }

    data class McpServer(
        val config: McpServerConfig, var state: State = State.CONFIGURED,
        val tools: MutableList<String> = mutableListOf(), var process: Process? = null
    )

    private val gson = Gson()
    private val configFile: File get() = File(project.basePath, ".code-assistant/mcp-config.json")
    private val servers = mutableMapOf<String, McpServer>()

    init {
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

    fun removeServer(id: String) {
        disconnect(id)
        servers.remove(id)
        persist()
    }

    fun connect(id: String): Boolean {
        val server = servers[id] ?: return false
        try {
            server.state = State.INITIALIZING
            val pb = ProcessBuilder(server.config.command, *server.config.args.toTypedArray())
            pb.directory(File(project.basePath ?: "."))
            server.config.env.forEach { (k, v) -> pb.environment()[k] = v }
            server.process = pb.start()
            server.state = State.RUNNING
            return true
        } catch (e: Exception) {
            server.state = State.ERROR
            return false
        }
    }

    fun disconnect(id: String) {
        val server = servers[id] ?: return
        server.process?.let {
            it.destroy()
            try {
                it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            if (it.isAlive) it.destroyForcibly()
        }
        server.state = State.STOPPED
        server.process = null
    }

    fun testConnection(id: String): String {
        val server = servers[id] ?: return "Server 不存在"
        return when (server.state) {
            State.RUNNING -> "🟢 连接正常"
            State.INITIALIZING -> "🟡 初始化中"
            State.CRASHED -> "🔴 已崩溃"
            State.STOPPED, State.DISCONNECTED -> "⛔ 已断开"
            else -> "⚪ 未连接"
        }
    }

    fun getServer(id: String): McpServer? = servers[id]

    fun getAllServers(): List<McpServer> = servers.values.toList()

    fun dispose() {
        servers.keys.forEach { disconnect(it) }
    }

    private fun persist() {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(servers.values.map { it.config }))
    }
}
