package com.aiassistant.agent

import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `images serialize as Anthropic SDK image content blocks`() {
        val image = ImageRef(
            fileName = "browser-shot.png",
            base64Data = "iVBORw0KGgo=",
            mimeType = "image/png",
            width = 12,
            height = 8,
            sizeBytes = 8
        )

        val params = AgentLoop(
            project = projectAt(createTempDirectory().toString()),
            session = AgentSession(),
            modelProvider = { "deepseek-v4-pro" }
        ).buildRequestParamsForTest(
            userMessage = "分析这张图片",
            images = listOf(image),
            mode = AgentLoop.AgentMode.CHAT
        )

        val blocks = params.messages().last().content().asBetaContentBlockParams()
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].isText())
        assertContains(blocks[0].asText().text(), "[Image: browser-shot.png]")
        assertTrue(blocks[1].isImage())
        val source = blocks[1].asImage().source().asBase64()
        assertEquals("iVBORw0KGgo=", source.data())
        assertEquals("image/png", source.mediaType().toString())
    }

    @Test
    fun `cancelled session returns error instead of empty success`() {
        val session = AgentSession().apply { cancel() }

        val result = AgentLoop(
            project = projectAt(createTempDirectory().toString()),
            session = session,
            apiKeyProvider = { "sk-test" },
            modelProvider = { "deepseek-v4-pro" }
        ).run("执行计划项")

        assertIs<AgentLoop.Result.Error>(result)
        assertContains(result.message, "已取消")
    }

    @Test
    fun `resumed session does not stay cancelled forever`() {
        val session = AgentSession().apply {
            cancel()
            resume()
            finishTurn()
        }

        val result = AgentLoop(
            project = projectAt(createTempDirectory().toString()),
            session = session,
            apiKeyProvider = { null },
            modelProvider = { "deepseek-v4-pro" }
        ).run("继续")

        assertIs<AgentLoop.Result.Error>(result)
        assertContains(result.message, "请先配置 DeepSeek API Key")
    }

    @Test
    fun `agent loop does not create java util timer threads`() {
        val source = File("src/main/kotlin/com/aiassistant/agent/AgentLoop.kt").readText()

        assertFalse(source.contains("java.util.Timer"))
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
