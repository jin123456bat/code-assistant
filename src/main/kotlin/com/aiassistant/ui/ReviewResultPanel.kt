package com.aiassistant.ui

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import javax.swing.*
import javax.swing.border.EmptyBorder

class ReviewResultPanel : JPanel(BorderLayout()) {

    private val titleLabel = JLabel("📋 审查结果").apply {
        font = ChatTheme.metaFont.deriveFont(14f)
        border = EmptyBorder(6, 10, 4, 10)
    }
    private val findingsList = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scrollPane = JScrollPane(findingsList).apply {
        border = null; preferredSize = Dimension(Int.MAX_VALUE, 180)
    }

    var onNavigateToFile: ((String, Int) -> Unit)? = null
    var onFixRequest: ((Finding) -> Unit)? = null

    init {
        add(titleLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        isVisible = false
    }

    fun showResults(findings: List<Finding>, score: Int) {
        findingsList.removeAll()
        titleLabel.text = "📋 审查结果 — $score/100 (${findings.size} 个问题)"

        for (f in findings) {
            val icon = when (f.severity) { Severity.CRITICAL -> "🔴"; Severity.WARNING -> "🟡"; else -> "🔵" }
            val row = JPanel(BorderLayout()).apply {
                border = EmptyBorder(3, 10, 3, 10); maximumSize = Dimension(Int.MAX_VALUE, 24)
            }
            val label = JLabel("$icon ${f.title}  ${f.file}:${f.line}")
            label.font = ChatTheme.metaFont
            label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            label.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { onNavigateToFile?.invoke(f.file, f.line) }
            })
            row.add(label, BorderLayout.CENTER)
            if (f.suggestion.isNotBlank()) {
                val fixBtn = JButton("修复").apply {
                    font = ChatTheme.metaFont; margin = Insets(0, 6, 0, 6)
                }
                fixBtn.addActionListener { onFixRequest?.invoke(f) }
                row.add(fixBtn, BorderLayout.EAST)
            }
            findingsList.add(row)
        }
        findingsList.revalidate(); findingsList.repaint()
        isVisible = true
    }

    fun hideResults() { isVisible = false }
}
