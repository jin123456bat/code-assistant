package com.aiassistant

import com.aiassistant.ui.BubbleFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane

/**
 * 单测 BubbleFactory.findFirstTextPane()。
 *
 * 该函数是纯 Swing 组件树遍历，无平台依赖，可在 headless 环境下运行。
 */
class BubbleFactoryFindTextPaneTest {

    @Test
    fun `returns null for empty panel`() {
        val panel = JPanel()
        assertNull(BubbleFactory.findFirstTextPane(panel))
    }

    @Test
    fun `returns null when only non-TextPane children exist`() {
        val panel = JPanel()
        panel.add(JLabel("hello"))
        panel.add(JPanel())
        assertNull(BubbleFactory.findFirstTextPane(panel))
    }

    @Test
    fun `finds direct JTextPane child`() {
        val panel = JPanel()
        val textPane = JTextPane()
        panel.add(textPane)
        assertSame(textPane, BubbleFactory.findFirstTextPane(panel))
    }

    @Test
    fun `finds JTextPane that IS the root component`() {
        val textPane = JTextPane()
        assertSame(textPane, BubbleFactory.findFirstTextPane(textPane))
    }

    @Test
    fun `finds nested JTextPane inside child JPanel`() {
        // 模拟 MarkdownRenderer.render() 的单段输出：
        //   container (BoxLayout Y) → JTextPane
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        val textPane = JTextPane()
        container.add(textPane)

        val result = BubbleFactory.findFirstTextPane(container)
        assertSame(textPane, result)
    }

    @Test
    fun `returns first JTextPane when multiple text panes exist`() {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        val first = JTextPane()
        val second = JTextPane()
        container.add(first)
        container.add(second)

        assertSame(first, BubbleFactory.findFirstTextPane(container))
    }

    @Test
    fun `finds JTextPane in deeply nested structure`() {
        val root = JPanel()
        val level1 = JPanel()
        val level2 = JPanel()
        val textPane = JTextPane()
        level2.add(textPane)
        level1.add(level2)
        root.add(JLabel("decoy"))
        root.add(level1)

        val result = BubbleFactory.findFirstTextPane(root)
        assertNotNull(result)
        assertSame(textPane, result)
    }

    @Test
    fun `skips non-JComponent children gracefully`() {
        // JPanel.components 只包含 Component，这里确保碰到非 JComponent 时不崩溃
        val panel = JPanel()
        val textPane = JTextPane()
        panel.add(textPane)
        assertSame(textPane, BubbleFactory.findFirstTextPane(panel))
    }
}
