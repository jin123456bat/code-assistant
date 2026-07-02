package com.aiassistant.session

import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.ContentType
import com.aiassistant.agent.Message
import com.aiassistant.agent.PlanExecutor
import com.aiassistant.agent.Role
import com.aiassistant.agent.ToolCallRecord
import com.aiassistant.agent.ToolCallState
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Path
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

    @Test
    fun `loading session resets executing plan state to paused`() {
        val project = projectAt(createTempDirectory().toString())
        val session = AgentSession(id = "session-with-running-plan")
        session.plan = PlanExecutor.Plan(
            summary = "Half finished plan",
            status = PlanExecutor.Plan.Status.EXECUTING,
            plans = mutableListOf(
                PlanExecutor.PlanItem(
                    description = "Running step",
                    tool = "Read",
                    files = emptyList(),
                    status = PlanExecutor.PlanItem.ItemStatus.EXECUTING
                )
            )
        )

        val store = SessionStore(project)
        store.save(session)

        val restoredPlan = assertNotNull(store.load(session.id)?.plan)
        assertEquals(PlanExecutor.Plan.Status.PAUSED, restoredPlan.status)
        assertEquals(PlanExecutor.PlanItem.ItemStatus.PAUSED, restoredPlan.plans.single().status)
    }

    @Test
    fun `persists approved mcp servers`() {
        val project = projectAt(createTempDirectory().toString())
        val session = AgentSession(id = "session-with-mcp-approval")
        session.approvedMcpServers.add("github")

        val store = SessionStore(project)
        store.save(session)

        val restored = assertNotNull(store.load(session.id))
        assertEquals(setOf("github"), restored.approvedMcpServers)
    }

    @Test
    fun `loading legacy approved tools marks first use done`() {
        val root = createTempDirectory()
        val project = projectAt(root.toString())
        writeSessionJson(
            root,
            "legacy-approved-tools",
            """
            {
              "id": "legacy-approved-tools",
              "title": "Legacy",
              "createdAt": "2026-01-01T00:00:00Z",
              "updatedAt": "2026-01-01T00:00:00Z",
              "state": "IDLE",
              "approvedTools": ["Read"],
              "messages": []
            }
            """.trimIndent()
        )

        val restored = assertNotNull(SessionStore(project).load("legacy-approved-tools"))

        assertContains(restored.firstToolUseDone, "Read")
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

    private fun writeSessionJson(root: Path, id: String, json: String) {
        val dir = root.resolve(".code-assistant/sessions").toFile()
        dir.mkdirs()
        dir.resolve("$id.json").writeText(json)
    }
}
