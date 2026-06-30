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
