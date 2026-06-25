package com.aiassistant.ui

import com.aiassistant.ui.ChatToolWindow.Page
import com.intellij.ui.JBColor
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

// Tab 切换栏 — 支持亮/暗主题

class TabBar(
    private val onSelect: (Page) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {

    private data class Tab(val page: Page, val label: String, val enabled: Boolean)

    private var selected: Page = Page.CHAT
    private val labels = mutableListOf<JLabel>()

    private val tabs = listOf(
        Tab(Page.WELCOME, "🏠 Welcome", true),
        Tab(Page.CHAT, "💬 Chat", true),
        Tab(Page.SESSIONS, "📁 Sessions", true),
        Tab(Page.TOKEN_USAGE, "📊 Usage", true),
        Tab(Page.MCP, "🔌 MCP", true),
        Tab(Page.SKILLS, "🎯 Skills", true),
        Tab(Page.SETTINGS, "⚙ Settings", true),
    )

    // 主题色
    private val activeColor = JBColor(0x3B82F6, 0x60A5FA)
    private val inactiveColor = JBColor(0x6B7280, 0x9CA3AF)
    private val hoverBg = JBColor(0xF3F4F6, 0x374151)
    private val borderColor = JBColor(0xE5E7EB, 0x374151)

    init {
        tabs.forEach { tab ->
            val lbl = JLabel(" ${tab.label} ").apply {
                font = font.deriveFont(13f); preferredSize = Dimension(120, 32)
                foreground = inactiveColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(0x00000000, 0x00000000)),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (tab.enabled) onSelect(tab.page)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        if (tab.enabled && tab.page != selected) background = hoverBg
                    }

                    override fun mouseExited(e: MouseEvent) {
                        if (tab.page != selected) background = JBColor(0x00000000, 0x00000000)
                    }
                })
            }
            add(lbl)
            labels.add(lbl)
        }

        border = BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor)
        updateSelection()
    }

    fun setSelected(page: Page) {
        selected = page; updateSelection()
    }

    fun setBadge(page: Page, text: String?) { /* ponytail: badge overlay later */
    }

    fun setEnabled(page: Page, enabled: Boolean) { /* ponytail: disable tabs later */
    }

    private fun updateSelection() {
        labels.forEachIndexed { i, lbl ->
            if (tabs[i].page == selected) {
                lbl.foreground = activeColor
                lbl.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, activeColor),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
                lbl.font = lbl.font.deriveFont(Font.BOLD)
            } else {
                lbl.foreground = inactiveColor
                lbl.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(0x00000000, 0x00000000)),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
                lbl.font = lbl.font.deriveFont(Font.PLAIN)
            }
        }
    }
}
