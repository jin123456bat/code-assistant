package com.aiassistant.skills

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillManagerTest {

    @Test
    fun `persists disabled skills by command`() {
        val root = createTempDirectory()
        val skillDir = root.resolve(".code-assistant/skills/review").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: review
            description: Review code
            command: review
            ---
            Review the current changes.
            """.trimIndent()
        )

        val manager = SkillManager(projectAt(root.toString()))
        manager.disableSkill("review")

        val disabledSkill = manager.loadSkills().first { it.name == "review" }
        assertFalse(disabledSkill.enabled)
        assertFalse(manager.getSystemPromptExtension().contains("/review"))

        manager.enableSkill("review")
        val enabledSkill = manager.loadSkills().first { it.name == "review" }
        assertTrue(enabledSkill.enabled)
    }

    @Test
    fun `returns only enabled slash commands`() {
        val root = createTempDirectory()
        writeSkill(root, "review", "review")
        writeSkill(root, "test", "test")

        val manager = SkillManager(projectAt(root.toString()))
        manager.disableSkill("test")

        val commands = manager.enabledSlashCommands()
        assertTrue("/review" in commands, "Expected /review to be enabled")
        assertFalse("/test" in commands, "Expected /test to be disabled")
    }

    @Test
    fun `accepts documented lowercase plan tool names`() {
        val root = createTempDirectory()
        writeSkill(root, "planner", "planner", tools = listOf("createPlan", "listPlans"))

        val skill =
            SkillManager(projectAt(root.toString())).loadSkills().single { it.name == "planner" }

        assertFalse(skill.hasMissingTools)
        assertEquals(emptyList(), skill.missingTools)
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
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project
}
