package com.aiassistant.ui.chat

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatViewModelSlashCommandTest {

    @Test
    fun `resolves enabled skill slash command from user input`() {
        val root = createTempDirectory()
        writeSkill(root, name = "review", command = "review")

        val viewModel = ChatViewModel(projectAt(root.toString()))

        assertEquals("/review", viewModel.resolveSlashCommandForTest("  /review 检查当前变更"))
        assertNull(viewModel.resolveSlashCommandForTest("/clear"))
        assertNull(viewModel.resolveSlashCommandForTest("/plan 修复 bug"))
    }

    @Test
    fun `does not resolve slash command for skill with missing tools`() {
        val root = createTempDirectory()
        writeSkill(root, name = "bad", command = "bad", tools = listOf("NoSuchTool"))

        val viewModel = ChatViewModel(projectAt(root.toString()))

        assertNull(viewModel.resolveSlashCommandForTest("/bad run"))
    }

    @Test
    fun `clear and new session preserve approval trust`() {
        val viewModel = ChatViewModel(projectAt(createTempDirectory().toString()))
        viewModel.session.approvedTools.add("Read")
        viewModel.session.approvedMcpServers.add("github")
        viewModel.session.firstToolUseDone.add("Read")

        viewModel.clearSession()
        assertEquals(setOf("Read"), viewModel.session.approvedTools)
        assertEquals(setOf("github"), viewModel.session.approvedMcpServers)
        assertEquals(setOf("Read"), viewModel.session.firstToolUseDone)

        viewModel.newSession()
        assertEquals(setOf("Read"), viewModel.session.approvedTools)
        assertEquals(setOf("github"), viewModel.session.approvedMcpServers)
        assertEquals(setOf("Read"), viewModel.session.firstToolUseDone)
    }

    @Test
    fun `clear session resets cancelled flag`() {
        val viewModel = ChatViewModel(projectAt(createTempDirectory().toString()))
        viewModel.cancel()

        viewModel.clearSession()

        assertEquals(false, viewModel.session.cancelled)
    }

    @Test
    fun `new slash command creates a new session`() {
        val viewModel = ChatViewModel(projectAt(createTempDirectory().toString()))
        val oldSessionId = viewModel.session.id

        viewModel.sendMessage("/new")

        assertEquals(false, viewModel.messages.any { it.content == "/new" })
        kotlin.test.assertNotEquals(oldSessionId, viewModel.session.id)
    }

    private fun writeSkill(
        root: java.nio.file.Path,
        name: String,
        command: String,
        tools: List<String> = emptyList()
    ) {
        val skillDir = root.resolve(".code-assistant/skills/$name").createDirectories()
        val content = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $name skill")
            appendLine("command: $command")
            if (tools.isNotEmpty()) {
                appendLine("tools:")
                tools.forEach { appendLine("  - $it") }
            }
            appendLine("---")
            appendLine("$name body.")
        }
        skillDir.resolve("SKILL.md").writeText(content)
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
