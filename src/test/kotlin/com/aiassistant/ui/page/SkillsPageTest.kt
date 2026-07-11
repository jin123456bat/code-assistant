package com.aiassistant.ui.page

import com.intellij.openapi.project.Project
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsPageTest {

    @Test
    fun `skill card maximum height contains all metadata rows`() {
        val root = createTempDirectory()
        val skillDir = root.resolve(".code-assistant/skills/review").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: review
            description: Review code for correctness and maintainability
            command: review
            tools:
              - read
            triggers:
              - review
              - 审查
            ---
            Review the current changes.
            """.trimIndent()
        )
        val page = SkillsPage(projectAt(root.toString()))
        val skillCard = panelsIn(page).first { panel ->
            panel.components.any { child ->
                child is JLabel && child.text.contains("<b>review</b>")
            }
        }

        assertEquals(
            skillCard.preferredSize.height,
            skillCard.maximumSize.height,
            "Skill card maximum height must match its final preferred height"
        )
    }

    private fun panelsIn(container: Container): List<JPanel> =
        container.components.flatMap { child ->
            when (child) {
                is JPanel -> listOf(child) + panelsIn(child)
                is Container -> panelsIn(child)
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
