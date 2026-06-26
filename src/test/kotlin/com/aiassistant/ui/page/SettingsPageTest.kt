package com.aiassistant.ui.page

import java.awt.Container
import javax.swing.JLabel
import javax.swing.JPasswordField
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPageTest {

    @Test
    fun `renders about page without inline api key form`() {
        val page = SettingsPage()
        val labels = labelsIn(page).map { it.text }

        assertFalse(hasPasswordField(page))
        assertTrue(labels.any { it.contains("IDE Settings") || it.contains("IDE 设置") })
        assertTrue(labels.any { it.contains("Code Assistant v2.0.0") })
    }

    private fun labelsIn(container: Container): List<JLabel> =
        container.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child)
                is Container -> labelsIn(child)
                else -> emptyList()
            }
        }

    private fun hasPasswordField(container: Container): Boolean =
        container.components.any { child ->
            child is JPasswordField || (child is Container && hasPasswordField(child))
        }
}
