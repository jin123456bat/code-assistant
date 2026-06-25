package com.aiassistant.ui

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.ChatToolWindow.Page
import com.intellij.ui.JBColor
import java.awt.Cursor
import java.awt.FlowLayout
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

    init {
        tabs.forEach { tab ->
            val lbl = JLabel(" ${tab.label} ").apply {
                font = font.deriveFont(13f)
                foreground = AppColors.textSecondary
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, AppColors.borderTransparent),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (tab.enabled) onSelect(tab.page)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        if (tab.enabled && tab.page != selected) background = AppColors.hoverBg
                    }

                    override fun mouseExited(e: MouseEvent) {
                        if (tab.page != selected) background = AppColors.borderTransparent
                    }
                })
            }
            add(lbl)
            labels.add(lbl)
        }

        border = BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border)
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
                lbl.foreground = AppColors.primary
                lbl.background = AppColors.hoverBg
                lbl.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, AppColors.primary),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
                lbl.isOpaque = true
            } else {
                lbl.foreground = AppColors.textSecondary
                lbl.background = AppColors.borderTransparent
                lbl.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, AppColors.borderTransparent),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                )
                lbl.isOpaque = true
            }
        }
    }
}
