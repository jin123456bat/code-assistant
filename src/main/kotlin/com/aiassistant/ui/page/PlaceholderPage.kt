package com.aiassistant.ui.page

import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

// ponytail: placeholder for TokenUsage, MCP, Skills pages — implement in later phases
class PlaceholderPage(title: String, description: String = "") : JPanel(BorderLayout()) {
    init {
        val text =
            if (description.isNotEmpty()) "<html><h2>$title</h2><p style='color:#6B7280'>$description</p></html>"
            else "<html><h2>$title</h2></html>"
        add(JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER)
    }
}
