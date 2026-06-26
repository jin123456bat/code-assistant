package com.aiassistant.ui.chat

import java.awt.Container
import javax.swing.JButton
import kotlin.test.Test
import kotlin.test.assertTrue

class PlanCardTest {

    @Test
    fun `renders retry and skip controls`() {
        var retried = false
        var skipped = false
        val card = PlanCard(
            onResume = {},
            onPause = {},
            onRetry = { retried = true },
            onSkip = { skipped = true },
            onAbort = {}
        )

        buttonsIn(card).single { it.text == "↻ 重试" }.doClick()
        buttonsIn(card).single { it.text == "⏭ 跳过" }.doClick()

        assertTrue(retried)
        assertTrue(skipped)
    }

    private fun buttonsIn(container: Container): List<JButton> =
        container.components.flatMap { child ->
            when (child) {
                is JButton -> listOf(child)
                is Container -> buttonsIn(child)
                else -> emptyList()
            }
        }
}
