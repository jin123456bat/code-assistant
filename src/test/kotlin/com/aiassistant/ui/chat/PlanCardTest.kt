package com.aiassistant.ui.chat

import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanCardTest {

    @Test
    fun `does not render global execution controls`() {
        val card = PlanCard()

        val buttonTexts = buttonsIn(card).map { it.text }

        assertFalse("▶ 继续" in buttonTexts)
        assertFalse("↻ 重试" in buttonTexts)
        assertFalse("⏭ 跳过" in buttonTexts)
        assertFalse("⏸ 暂停" in buttonTexts)
        assertFalse("✕ 终止" in buttonTexts)
    }

    @Test
    fun `clicking paused step delete marker reports step id`() {
        val deleted = mutableListOf<String>()
        val card = PlanCard(
            onDeleteStep = { deleted.add(it) }
        )
        card.setPlan(
            summary = "测试计划",
            planSteps = listOf(PlanCard.StepRow("step-1", "读取文件", "工具: Read"))
        )
        card.setExpanded(true)

        labelsIn(card).first { it.text.contains("[✕]") }.dispatchEvent(
            java.awt.event.MouseEvent(
                labelsIn(card).first { it.text.contains("[✕]") },
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                10,
                10,
                1,
                false
            )
        )

        assertEquals(listOf("step-1"), deleted)
    }

    private fun buttonsIn(container: Container): List<JButton> =
        container.components.flatMap { child ->
            when (child) {
                is JButton -> listOf(child)
                is Container -> buttonsIn(child)
                else -> emptyList()
            }
        }

    private fun labelsIn(container: Container): List<JLabel> =
        container.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child)
                is Container -> labelsIn(child)
                else -> emptyList()
            }
        }
}
