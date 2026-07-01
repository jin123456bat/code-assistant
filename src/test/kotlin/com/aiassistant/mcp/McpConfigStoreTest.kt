package com.aiassistant.mcp

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpConfigStoreTest {

    @Test
    fun `loads documented servers wrapper format`() {
        withIsolatedHome {
            val root = createTempDirectory()
            root.resolve(".code-assistant").createDirectories()
            root.resolve(".code-assistant/mcp-config.json").writeText(
                """
                {
                  "servers": [
                    {
                      "id": "docs",
                      "command": "npx",
                      "args": ["-y", "docs-server"],
                      "env": { "TOKEN": "secret" },
                      "transport": "stdio",
                      "enabled": true
                    }
                  ]
                }
                """.trimIndent()
            )

            val config = McpConfigStore(projectAt(root.toString())).load()

            assertEquals(1, config.servers.size)
            val server = config.servers.single()
            assertEquals("docs", server.id)
            assertEquals("npx", server.command)
            assertEquals(listOf("-y", "docs-server"), server.args)
            assertEquals(mapOf("TOKEN" to "secret"), server.env)
        }
    }

    @Test
    fun `loads Claude mcpServers compatibility format`() {
        withIsolatedHome {
            val root = createTempDirectory()
            root.resolve(".mcp.json").writeText(
                """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem"],
                      "env": { "ROOT": "/tmp" }
                    }
                  }
                }
                """.trimIndent()
            )

            val config = McpConfigStore(projectAt(root.toString())).load()

            val server = config.servers.single()
            assertEquals("filesystem", server.id)
            assertEquals("npx", server.command)
            assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem"), server.args)
            assertEquals(mapOf("ROOT" to "/tmp"), server.env)
            assertEquals("stdio", server.transport)
        }
    }

    @Test
    fun `saves documented servers wrapper format`() {
        val root = createTempDirectory()
        val store = McpConfigStore(projectAt(root.toString()))

        store.save(
            McpConfigStore.McpConfig(
                servers = listOf(McpManager.McpServerConfig(id = "docs", command = "npx"))
            )
        )

        val saved = root.resolve(".code-assistant/mcp-config.json").toFile().readText()
        assertTrue(saved.contains("\"servers\""), saved)
        assertTrue(saved.contains("\"id\":\"docs\""), saved)
    }

    @Test
    fun `project main config overrides dot mcp config with same id`() {
        withIsolatedHome {
            val root = createTempDirectory()
            root.resolve(".code-assistant").createDirectories()
            root.resolve(".mcp.json").writeText(
                """
                {
                  "mcpServers": {
                    "docs": {
                      "command": "from-dot-mcp"
                    }
                  }
                }
                """.trimIndent()
            )
            root.resolve(".code-assistant/mcp-config.json").writeText(
                """
                {
                  "servers": [
                    {
                      "id": "docs",
                      "command": "from-main-config"
                    }
                  ]
                }
                """.trimIndent()
            )

            val config = McpConfigStore(projectAt(root.toString())).load()

            assertEquals("from-main-config", config.servers.single { it.id == "docs" }.command)
        }
    }

    private fun withIsolatedHome(block: () -> Unit) {
        val oldHome = System.getProperty("user.home")
        System.setProperty("user.home", createTempDirectory().toString())
        try {
            block()
        } finally {
            System.setProperty("user.home", oldHome)
        }
    }

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "TestProject"
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === (args?.getOrNull(0))
                else -> null
            }
        } as Project
}
