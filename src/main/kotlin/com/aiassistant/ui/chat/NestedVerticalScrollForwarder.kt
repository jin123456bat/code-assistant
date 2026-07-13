package com.aiassistant.ui.chat

import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * 在嵌套滚动区域到达垂直边界时，将滚轮事件交还给最近的外层 JScrollPane。
 *
 * 对不提供垂直滚动条的区域（例如横向滚动的代码块），普通垂直滚轮始终属于外层；
 * Shift + 滚轮仍保留给内层横向滚动，避免破坏代码块的水平浏览能力。
 */
internal class NestedVerticalScrollForwarder(
    private val scrollPane: JScrollPane
) : MouseWheelListener {

    init {
        // JScrollPane 在禁用垂直滚动时会把普通滚轮降级为横向滚动。
        // 代码块需要“普通滚轮交给外层、Shift + 滚轮横向滚动”，因此由本监听器独占处理。
        if (verticalScrollingDisabled()) {
            scrollPane.isWheelScrollingEnabled = false
        }
    }

    override fun mouseWheelMoved(event: MouseWheelEvent) {
        if (verticalScrollingDisabled()) {
            if (event.isShiftDown) {
                scrollHorizontally(event)
            } else {
                forwardToOuterScrollPane(event)
            }
            return
        }

        if (event.isShiftDown) return

        val scrollBar = scrollPane.verticalScrollBar
        val atBottom =
            event.wheelRotation > 0 && scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum
        val atTop = event.wheelRotation < 0 && scrollBar.value <= scrollBar.minimum
        if (!atBottom && !atTop) return

        forwardToOuterScrollPane(event)
    }

    private fun verticalScrollingDisabled(): Boolean =
        scrollPane.verticalScrollBarPolicy == JScrollPane.VERTICAL_SCROLLBAR_NEVER

    private fun scrollHorizontally(event: MouseWheelEvent) {
        val scrollBar = scrollPane.horizontalScrollBar
        val delta = if (event.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            event.wheelRotation * scrollBar.blockIncrement
        } else {
            event.unitsToScroll * scrollBar.unitIncrement
        }
        val maximumValue = (scrollBar.maximum - scrollBar.visibleAmount)
            .coerceAtLeast(scrollBar.minimum)
        scrollBar.value = (scrollBar.value + delta).coerceIn(scrollBar.minimum, maximumValue)
        event.consume()
    }

    private fun forwardToOuterScrollPane(event: MouseWheelEvent) {
        val outerScrollPane = findOuterScrollPane() ?: return
        outerScrollPane.dispatchEvent(
            SwingUtilities.convertMouseEvent(scrollPane, event, outerScrollPane)
        )
        event.consume()
    }

    private fun findOuterScrollPane(): JScrollPane? {
        var parent = scrollPane.parent
        while (parent != null) {
            if (parent is JScrollPane) return parent
            parent = parent.parent
        }
        return null
    }
}
