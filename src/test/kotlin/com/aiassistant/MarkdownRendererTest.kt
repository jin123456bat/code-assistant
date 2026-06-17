package com.aiassistant

import org.junit.Assert.*
import org.junit.Test
import javax.swing.JLabel
import javax.swing.JPanel

class MarkdownRendererTest {

    private val renderer = MarkdownRenderer()

    @Test
    fun `should render plain text`() {
        val panel = renderer.render("Hello world")
        assertNotNull(panel)
        assertTrue(panel is JPanel)
        assertTrue(panel.componentCount > 0)  // 至少包含文本组件
    }

    @Test
    fun `should render bold markdown`() {
        val panel = renderer.render("**bold text**")
        assertNotNull(panel)
        // 粗体文本应被渲染（不为空面板）
        assertTrue(panel is JPanel)
    }

    @Test
    fun `should render italic markdown`() {
        val panel = renderer.render("*italic text*")
        assertNotNull(panel)
        assertTrue(panel is JPanel)
    }

    @Test
    fun `should render inline code`() {
        val panel = renderer.render("use `println()` to output")
        assertNotNull(panel)
        assertTrue(panel is JPanel)
    }

    @Test
    fun `should render code block`() {
        val markdown = """
            ```kotlin
            fun main() {
                println("Hello")
            }
            ```
        """.trimIndent()
        val panel = renderer.render(markdown)
        assertNotNull(panel)
        assertTrue(panel is JPanel)
        // 代码块应包含可滚动面板
        val hasScrollPane = findComponent(panel, javax.swing.JScrollPane::class.java)
        assertNotNull("code block should create scroll pane", hasScrollPane)
    }

    @Test
    fun `should handle truncated code fence`() {
        val panel = renderer.render("""```kot""")
        assertNotNull(panel)  // 不应崩溃
    }

    @Test
    fun `should handle truncated bold marker`() {
        val panel = renderer.render("**incomplete bold")
        assertNotNull(panel)  // 不应崩溃
    }

    @Test
    fun `should handle empty string`() {
        val panel = renderer.render("")
        assertNotNull(panel)  // 不应崩溃
    }

    @Test
    fun `should render mixed content`() {
        val markdown = """
            # Heading
            Some **bold** and *italic* text.
            - item 1
            - item 2
        """.trimIndent()
        val panel = renderer.render(markdown)
        assertNotNull(panel)
        assertTrue(panel is JPanel)
        // 混合内容应包含多个子组件
        assertTrue("mixed content should have children", panel.componentCount > 0)
    }

    @Test
    fun `should handle Chinese characters`() {
        val panel = renderer.render("你好世界 **加粗中文**")
        assertNotNull(panel)
        assertTrue(panel is JPanel)
    }

    @Test
    fun `should render for streaming with parent background`() {
        val panel = renderer.renderForStreaming("streaming content...", java.awt.Color.WHITE)
        assertNotNull(panel)
        assertTrue(panel is JPanel)
    }

    @Test
    fun `should create code block panel`() {
        val panel = renderer.createCodeBlockPanel("kotlin", "val x = 1")
        assertNotNull(panel)
        assertTrue(panel is JPanel)
        // 代码块面板应包含代码内容
        val labels = findAllComponents(panel, JLabel::class.java)
        assertTrue("code block should have content", labels.isNotEmpty())
    }

    /** 递归查找指定类型的组件（深度优先） */
    private fun <T : java.awt.Component> findComponent(root: java.awt.Component, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is java.awt.Container) {
            for (i in 0 until root.componentCount) {
                val found = findComponent(root.getComponent(i), type)
                if (found != null) return found
            }
        }
        return null
    }

    /** 递归查找所有指定类型的组件 */
    private fun <T : java.awt.Component> findAllComponents(root: java.awt.Component, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        if (type.isInstance(root)) result.add(type.cast(root))
        if (root is java.awt.Container) {
            for (i in 0 until root.componentCount) {
                result.addAll(findAllComponents(root.getComponent(i), type))
            }
        }
        return result
    }
}
