package com.aiassistant.ui.page

import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.Message
import com.aiassistant.agent.Role
import com.aiassistant.agent.TokenDelta
import com.aiassistant.session.SessionStore
import com.intellij.openapi.project.Project
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JTable
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class TokenUsagePageTest {

    @Test
    fun `renders session usage table`() {
        val root = createTempDirectory()
        val project = projectAt(root.toString())
        val session = AgentSession(id = "s1", title = "Usage")
        session.addMessage(
            Message(
                role = Role.ASSISTANT,
                content = "ok",
                tokenUsage = TokenDelta(inputTokens = 1000, outputTokens = 2000)
            )
        )
        SessionStore(project).save(session)

        val page = TokenUsagePage(project)

        assertTrue(tablesIn(page).isNotEmpty())
    }

    private fun tablesIn(container: Container): List<JTable> =
        container.components.flatMap { child ->
            when (child) {
                is JTable -> listOf(child)
                is Container -> tablesIn(child)
                else -> emptyList()
            }
        }

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "TestProject"
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project
}
