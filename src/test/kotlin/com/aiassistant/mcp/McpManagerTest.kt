package com.aiassistant.mcp

import com.aiassistant.agent.ToolRegistry
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun `connects http mcp server and registers tools`() {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val request = JsonParser.parseString(body).asJsonObject
            val id = request.get("id")?.asString
            val response = when (request.get("method")?.asString) {
                "initialize" -> """{"jsonrpc":"2.0","id":"$id","result":{}}"""
                "tools/list" -> """
                    {
                      "jsonrpc": "2.0",
                      "id": "$id",
                      "result": {
                        "tools": [
                          {
                            "name": "search",
                            "description": "Search remote docs",
                            "inputSchema": {
                              "type": "object",
                              "properties": {
                                "query": { "type": "string" }
                              }
                            }
                          }
                        ]
                      }
                    }
                """.trimIndent()

                else -> ""
            }
            if (response.isBlank()) {
                exchange.sendResponseHeaders(204, -1)
            } else {
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        httpServer.start()
        val manager = McpManager(projectAt(createTempDirectory().toString()))
        manager.addServer(
            McpManager.McpServerConfig(
                id = "remote",
                command = "",
                transport = "http",
                url = "http://127.0.0.1:${httpServer.address.port}/"
            )
        )

        try {
            assertTrue(manager.connect("remote"))
            assertEquals(McpManager.State.RUNNING, manager.getServer("remote")?.state)
            assertNotNull(ToolRegistry.getToolInfo("remote/search"))
        } finally {
            ToolRegistry.unregister("remote/search")
            manager.dispose()
            httpServer.stop(0)
        }
    }

    @Test
    fun `http mcp consumes first sse data event without waiting for connection close`() {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.executor = Executors.newCachedThreadPool()
        httpServer.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val request = JsonParser.parseString(body).asJsonObject
            val id = request.get("id")?.asString
            val response = when (request.get("method")?.asString) {
                "initialize" -> """{"jsonrpc":"2.0","id":"$id","result":{}}"""
                "tools/list" -> """{"jsonrpc":"2.0","id":"$id","result":{"tools":[{"name":"stream_search","description":"Search streamed docs","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}]}}"""
                else -> ""
            }
            val bytes = "data: $response\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(bytes)
            exchange.responseBody.flush()
            Thread.sleep(1500)
            exchange.responseBody.close()
        }
        httpServer.start()
        val manager = McpManager(projectAt(createTempDirectory().toString()))
        manager.addServer(
            McpManager.McpServerConfig(
                id = "stream",
                command = "",
                transport = "http",
                url = "http://127.0.0.1:${httpServer.address.port}/"
            )
        )

        try {
            val startedAt = System.currentTimeMillis()
            assertTrue(manager.connect("stream"))
            val elapsedMs = System.currentTimeMillis() - startedAt
            assertTrue(elapsedMs < 1000, "connect waited ${elapsedMs}ms for SSE connection close")
            assertNotNull(ToolRegistry.getToolInfo("stream/stream_search"))
        } finally {
            ToolRegistry.unregister("stream/stream_search")
            manager.dispose()
            httpServer.stop(0)
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
