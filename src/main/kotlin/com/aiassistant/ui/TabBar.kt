package com.aiassistant.ui

import com.aiassistant.ui.ChatToolWindow.Page
import com.intellij.icons.AllIcons
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingConstants

// Tab 切换栏 — 支持亮/暗主题

class TabBar(
    private val onSelect: (Page) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

    private data class Tab(
        val page: Page,
        val icon: Icon,
        val tooltip: String,
        val enabled: Boolean
    )

    private var selected: Page = Page.CHAT
    private val labels = mutableListOf<JLabel>()
    private val badgeLabels = mutableMapOf<Page, JLabel>()
    private val enabledPages = mutableMapOf<Page, Boolean>()

    // 用 JLayeredPane 包裹每个 tab，以便在右上角叠加 badge
    private val tabWrappers = mutableListOf<JLayeredPane>()

    private val tabs = listOf(
        Tab(Page.WELCOME, AllIcons.Nodes.HomeFolder, "Welcome", true),
        Tab(Page.CHAT, AllIcons.Toolwindows.ToolWindowMessages, "Chat", true),
        Tab(Page.SESSIONS, AllIcons.Actions.ListFiles, "Sessions", true),
        Tab(Page.TOKEN_USAGE, AllIcons.Actions.Show, "Usage", true),
        Tab(Page.MCP, AllIcons.Nodes.Plugin, "MCP", true),
        Tab(Page.SKILLS, AllIcons.Nodes.Favorite, "Skills", true),
        Tab(Page.SETTINGS, AllIcons.General.Gear, "Settings", true),
    )

    init {
        tabs.forEach { tab ->
            enabledPages[tab.page] = tab.enabled
            val lbl = JLabel(tab.icon, SwingConstants.CENTER).apply {
                font = font.deriveFont(13f)
                foreground = AppColors.textSecondary
                preferredSize = Dimension(44, 32)
                toolTipText = tab.tooltip
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, AppColors.borderTransparent),
                    BorderFactory.createEmptyBorder(6, 0, 6, 0)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (isPageEnabled(tab.page)) onSelect(tab.page)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        if (isPageEnabled(tab.page) && tab.page != selected) background =
                            Color(0xF3F4F6)
                    }

                    override fun mouseExited(e: MouseEvent) {
                        if (tab.page != selected) background = AppColors.borderTransparent
                    }
                })
            }

            // 用 JLayeredPane 包裹 label，以便在右上角叠加 badge
            val wrapper = JLayeredPane().apply {
                preferredSize = Dimension(44, 32)
                layout = null
                lbl.setBounds(0, 0, 44, 32)
                add(lbl, JLayeredPane.DEFAULT_LAYER)
            }
            add(wrapper)
            labels.add(lbl)
            tabWrappers.add(wrapper)
        }

        border = BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border)
        updateSelection()
    }

    fun setSelected(page: Page) {
        selected = page; updateSelection()
    }

    fun setBadge(page: Page, text: String?) {
        val wrapperIndex = tabs.indexOfFirst { it.page == page }
        if (wrapperIndex < 0) return
        val wrapper = tabWrappers[wrapperIndex]

        // 移除旧 badge
        badgeLabels[page]?.let { wrapper.remove(it) }
        badgeLabels.remove(page)

        if (text.isNullOrBlank()) {
            updateBadgeLabelStyles()
            wrapper.repaint()
            return
        }

        // 格式化数字 > 99 → "99+"
        val displayText = text.toIntOrNull()?.let { num ->
            if (num > 99) "99+" else num.toString()
        } ?: text

        val badge = JLabel(displayText, SwingConstants.CENTER).apply {
            // 右上角角标，圆形 badge：最小 16px（半径 8px），数字超过一位时等比加宽加高保持圆形
            val badgeSize = if (displayText.length <= 2) 16 else (8 * displayText.length)
            setBounds(44 - badgeSize - 1, 1, badgeSize, badgeSize)
            isOpaque = true
            background = AppColors.badgeBg
            foreground = AppColors.badgeFg
            font = Font("SansSerif", Font.BOLD, 9)
            border = BorderFactory.createLineBorder(AppColors.badgeBg, badgeSize / 2)
        }

        badgeLabels[page] = badge
        wrapper.add(badge, JLayeredPane.PALETTE_LAYER)
        updateBadgeLabelStyles()
        wrapper.repaint()
    }

    fun setEnabled(page: Page, enabled: Boolean) {
        val index = tabs.indexOfFirst { it.page == page }
        if (index < 0) return
        enabledPages[page] = enabled
        labels[index].isEnabled = enabled
        labels[index].cursor = Cursor.getPredefinedCursor(
            if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
        )
        updateSelection()
    }

    fun setApiKeyConfigured(configured: Boolean) {
        tabWrappers.forEachIndexed { i, wrapper ->
            wrapper.isVisible =
                if (configured) tabs[i].page != Page.WELCOME else tabs[i].page == Page.WELCOME
        }
        revalidate()
        repaint()
    }

    private fun updateSelection() {
        labels.forEachIndexed { i, lbl ->
            if (tabs[i].page == selected) {
                lbl.foreground =
                    if (isPageEnabled(tabs[i].page)) AppColors.primary else AppColors.textSecondary
                lbl.background = AppColors.borderTransparent
                lbl.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, AppColors.primary),
                    BorderFactory.createEmptyBorder(6, 0, 6, 0)
                )
                lbl.isOpaque = true
            } else {
                lbl.foreground = AppColors.textSecondary
                lbl.background = AppColors.borderTransparent
                lbl.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, AppColors.borderTransparent),
                    BorderFactory.createEmptyBorder(6, 0, 6, 0)
                )
                lbl.isOpaque = true
            }
        }
        updateBadgeLabelStyles()
    }

    /** 同步 badge 颜色以适配亮/暗主题（颜色按 AppColors 更新，圆角厚度保持不变） */
    private fun updateBadgeLabelStyles() {
        badgeLabels.forEach { (_, badge) ->
            badge.background = AppColors.badgeBg
            badge.foreground = AppColors.badgeFg
            // border 圆角厚度在 setBadge() 中已根据 badge 尺寸动态设置，此处只同步颜色
            val currentThickness = (badge.border as? javax.swing.border.LineBorder)?.thickness ?: 8
            badge.border = BorderFactory.createLineBorder(AppColors.badgeBg, currentThickness)
        }
    }

    private fun isPageEnabled(page: Page): Boolean = enabledPages[page] ?: true
}
