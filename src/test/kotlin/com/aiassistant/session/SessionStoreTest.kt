package com.aiassistant.session

import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.ContentType
import com.aiassistant.agent.Message
import com.aiassistant.agent.Role
import com.aiassistant.agent.ToolCallRecord
import com.aiassistant.agent.ToolCallState
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

class SessionStoreTest {

    @Test
    fun `loads persisted tool call records`() {
        val project = projectAt(createTempDirectory().toString())
        val session = AgentSession(id = "session-with-tool")
        session.addMessage(
            Message(
                role = Role.SYSTEM,
                contentType = ContentType.TOOL_USE,
                content = "",
                toolCalls = listOf(
                    ToolCallRecord(
                        id = "tool-1",
                        name = "Read",
                        parameters = mapOf("filePath" to "README.md"),
                        state = ToolCallState.DONE,
                        result = "ok",
                        durationMs = 12
                    )
                )
            )
        )

        val store = SessionStore(project)
        store.save(session)

        val restored = assertNotNull(store.load(session.id))
        val restoredToolCall = assertNotNull(restored.messages.single().toolCalls).single()
        assertEquals("Read", restoredToolCall.name)
        assertEquals("README.md", restoredToolCall.parameters["filePath"])
        assertEquals(ToolCallState.DONE, restoredToolCall.state)
        assertEquals("ok", restoredToolCall.result)
        assertEquals(12, restoredToolCall.durationMs)
    }

    @Test
    fun `exports selected sessions as json`() {
        val project = projectAt(createTempDirectory().toString())
        val first = AgentSession(id = "first-session", title = "First")
        first.addMessage(Message(role = Role.USER, content = "export me"))
        val second = AgentSession(id = "second-session", title = "Second")
        second.addMessage(Message(role = Role.USER, content = "do not export me"))

        val store = SessionStore(project)
        store.save(first)
        store.save(second)

        val exported = store.exportJson(listOf(first.id))

        assertContains(exported, """"sessionCount": 1""")
        assertContains(exported, """"id": "first-session"""")
        assertFalse(exported.contains("second-session"))
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
