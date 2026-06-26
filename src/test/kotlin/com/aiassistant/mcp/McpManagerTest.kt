package com.aiassistant.mcp

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project
}
