package com.aiassistant.mcp

import com.aiassistant.agent.ToolRegistry
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class McpManagerTest {

    @Test
    fun `updates server config without dropping args and env`() {
        val manager = McpManager(projectAt(createTempDirectory().toString()))
        manager.addServer(
            McpManager.McpServerConfig(
                id = "docs",
                command = "old-command",
                args = listOf("--stdio"),
                env = mapOf("TOKEN" to "secret")
            )
        )

        manager.updateServer("docs") { it.copy(command = "new-command") }

        val updated = manager.getServer("docs")!!.config
        assertEquals("new-command", updated.command)
        assertEquals(listOf("--stdio"), updated.args)
        assertEquals(mapOf("TOKEN" to "secret"), updated.env)
    }

    @Test
    fun `loadServers preserves existing runtime state`() {
        val manager = McpManager(projectAt(createTempDirectory().toString()))
        manager.addServer(McpManager.McpServerConfig(id = "docs", command = "npx"))
        manager.getServer("docs")!!.state = McpManager.State.RUNNING

        val loaded = manager.loadServers().single { it.config.id == "docs" }

        assertEquals(McpManager.State.RUNNING, loaded.state)
        assertEquals(McpManager.State.RUNNING, manager.getServer("docs")!!.state)
    }

    @Test
    fun `registers mcp tool with prefixed name and input schema`() {
        val manager = McpManager(projectAt(createTempDirectory().toString()))
        val server = McpManager.McpServer(McpManager.McpServerConfig(id = "docs", command = "npx"))
        val schema = JsonParser.parseString(
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "Search query" }
              },
              "required": ["query"]
            }
            """.trimIndent()
        ).asJsonObject

        try {
            manager.registerMcpToolForTest(server, "search", "Search docs", schema)

            val info = assertNotNull(ToolRegistry.getToolInfo("docs/search"))
            val betaTool = assertNotNull(info.betaTool)
            assertEquals("docs/search", betaTool.name())
            assertEquals("Search docs", betaTool.description().get())
            assertEquals(listOf("query"), betaTool.inputSchema().required().get())
        } finally {
            ToolRegistry.unregister("docs/search")
        }
    }

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === (args?.getOrNull(0))
                else -> null
            }
        } as Project
}
