package com.aiassistant.session

import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.Message
import com.aiassistant.agent.Role
import com.aiassistant.agent.TokenDelta
import com.aiassistant.agent.TokenUsage
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionManagerTest {

    @Test
    fun `including child tokens does not double count child sessions`() {
        val project = projectAt(createTempDirectory().toString())
        val manager = SessionManager(project)
        val parent = AgentSession(id = "parent-session", title = "Parent")
        parent.totalTokens = TokenUsage(inputTokens = 10, outputTokens = 5)
        parent.addMessage(
            Message(
                role = Role.ASSISTANT,
                content = "parent",
                tokenUsage = TokenDelta(inputTokens = 10, outputTokens = 5)
            )
        )
        val child = AgentSession(id = "child-session", title = "Child", parentId = parent.id)
        child.totalTokens = TokenUsage(inputTokens = 7, outputTokens = 3)
        child.addMessage(
            Message(
                role = Role.ASSISTANT,
                content = "child",
                tokenUsage = TokenDelta(inputTokens = 7, outputTokens = 3)
            )
        )

        manager.saveSession(parent)
        manager.saveSession(child)
        assertEquals(10, manager.aggregateChildTokens(parent.id))
        assertEquals(10, manager.getAllSessions().single { it.id == parent.id }.parentTotalTokens)

        manager.saveSession(parent)
        assertEquals(10, manager.getAllSessions().single { it.id == parent.id }.parentTotalTokens)

        val usage = manager.getTotalTokenUsage(TokenRange.ALL, includeChildren = true)

        assertEquals(25, usage.grandTotal)
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
