package com.aiassistant.agent

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AgentLoopHistoryTest {

    @Test
    fun `request params include restored conversation history before current user message`() {
        val session = AgentSession()
        session.addMessage(Message(role = Role.USER, content = "第一轮问题"))
        session.addMessage(Message(role = Role.ASSISTANT, content = "第一轮回答"))

        val params = AgentLoop(
            project = projectAt(createTempDirectory().toString()),
            session = session,
            modelProvider = { "deepseek-v4-pro" }
        )
            .buildRequestParamsForTest("第二轮问题", mode = AgentLoop.AgentMode.CHAT)

        val conversation = params.messages().takeLast(3)
        assertEquals(3, conversation.size)
        assertEquals("user", conversation[0].role().toString())
        assertEquals("assistant", conversation[1].role().toString())
        assertEquals("user", conversation[2].role().toString())
    }

    @Test
    fun `current user message already stored in session is not duplicated`() {
        val session = AgentSession()
        session.addMessage(Message(role = Role.USER, content = "第一轮问题"))
        session.addMessage(Message(role = Role.ASSISTANT, content = "第一轮回答"))
        session.addMessage(Message(role = Role.USER, content = "第二轮问题"))

        val params = AgentLoop(
            project = projectAt(createTempDirectory().toString()),
            session = session,
            modelProvider = { "deepseek-v4-pro" }
        )
            .buildRequestParamsForTest("第二轮问题", mode = AgentLoop.AgentMode.CHAT)

        val conversation = params.messages().takeLast(3)
        assertEquals(listOf("user", "assistant", "user"), conversation.map { it.role().toString() })
    }

    @Test
    fun `slash command injects matching skill body into request params`() {
        val root = createTempDirectory()
        val skillDir = root.resolve(".code-assistant/skills/review").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: review
            description: Review code
            command: review
            ---
            REVIEW_SKILL_BODY_FOR_TEST
            """.trimIndent()
        )

        val params = AgentLoop(
            project = projectAt(root.toString()),
            session = AgentSession(),
            modelProvider = { "deepseek-v4-pro" }
        )
            .buildRequestParamsForTest(
                "/review 检查当前变更",
                slashCommand = "/review",
                mode = AgentLoop.AgentMode.CHAT
            )

        assertContains(params.toString(), "REVIEW_SKILL_BODY_FOR_TEST")
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
