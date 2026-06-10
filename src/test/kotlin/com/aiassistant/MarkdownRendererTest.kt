package com.aiassistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

class MarkdownRendererTest {

    private val renderer = MarkdownRenderer()

    @Test
    fun `should render plain text`() {
        val panel = renderer.render("Hello world")
        assertNotNull(panel)
        assertTrue(panel is JPanel)
    }

    @Test
    fun `should render bold markdown`() {
        val panel = renderer.render("**bold text**")
        assertNotNull(panel)
    }

    @Test
    fun `should render italic markdown`() {
        val panel = renderer.render("*italic text*")
        assertNotNull(panel)
    }

    @Test
    fun `should render inline code`() {
        val panel = renderer.render("use `println()` to output")
        assertNotNull(panel)
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
    }

    @Test
    fun `should handle truncated code fence`() {
        // Incomplete ``` token — should not crash
        val panel = renderer.render("""```kot""")
        assertNotNull(panel)
    }

    @Test
    fun `should handle truncated bold marker`() {
        val panel = renderer.render("**incomplete bold")
        assertNotNull(panel)
    }

    @Test
    fun `should handle empty string`() {
        val panel = renderer.render("")
        assertNotNull(panel)
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
    }

    @Test
    fun `should handle Chinese characters`() {
        val panel = renderer.render("你好世界 **加粗中文**")
        assertNotNull(panel)
    }

    @Test
    fun `should render for streaming with parent background`() {
        val panel = renderer.renderForStreaming("streaming content...", java.awt.Color.WHITE)
        assertNotNull(panel)
    }

    @Test
    fun `should create code block panel`() {
        val panel = renderer.createCodeBlockPanel("kotlin", "val x = 1")
        assertNotNull(panel)
    }
}
