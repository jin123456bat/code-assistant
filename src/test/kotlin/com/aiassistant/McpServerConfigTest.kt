package com.aiassistant.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerConfigTest {

    @Test
    fun `should parse valid mcp config json`() {
        val json = """
        {
          "mcpServers": {
            "filesystem": {
              "command": "npx",
              "args": ["-y", "@anthropic/server-filesystem", "/tmp"]
            },
            "sqlite": {
              "command": "uvx",
              "args": ["mcp-server-sqlite"]
            }
          }
        }
        """.trimIndent()
        val configs = McpServerConfig.parseConfigs(json)
        assertEquals(2, configs.size)
        assertEquals("filesystem", configs[0].name)
        assertEquals("npx", configs[0].command)
        assertEquals(3, configs[0].args.size)
        assertEquals("sqlite", configs[1].name)
        assertEquals("uvx", configs[1].command)
    }

    @Test
    fun `should return empty list for invalid json`() {
        val configs = McpServerConfig.parseConfigs("not json at all")
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `should serialize configs to json`() {
        val config = McpServerConfig(
            name = "test", transport = "stdio",
            command = "npx", args = listOf("server"), env = mapOf("KEY" to "val")
        )
        val json = McpServerConfig.toJson(listOf(config))
        assertTrue(json.contains(""""mcpServers":"""))
        assertTrue(json.contains(""""test":"""))
        assertTrue(json.contains(""""command": "npx""""))
    }
}
