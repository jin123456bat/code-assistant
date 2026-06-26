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
        manager.setEnabled("review", false)

        assertFalse(manager.loadSkills().single().enabled)
        assertFalse(manager.getSystemPromptExtension().contains("/review"))

        manager.setEnabled("review", true)
        assertTrue(manager.loadSkills().single().enabled)
    }

    @Test
    fun `returns only enabled slash commands`() {
        val root = createTempDirectory()
        writeSkill(root, "review", "review")
        writeSkill(root, "test", "test")

        val manager = SkillManager(projectAt(root.toString()))
        manager.setEnabled("test", false)

        assertEquals(listOf("/review"), manager.enabledSlashCommands())
    }

    private fun writeSkill(root: java.nio.file.Path, name: String, command: String) {
        val skillDir = root.resolve(".code-assistant/skills/$name").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: $name
            description: $name skill
            command: $command
            ---
            $name body.
            """.trimIndent()
        )
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
