package com.aiassistant.ui

import com.aiassistant.AppSettingsService
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.page.*
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ChatToolWindow(private val project: Project) : JPanel(BorderLayout()) {

    enum class Page(val id: String) {
        WELCOME("welcome"), CHAT("chat"), SESSIONS("sessions"),
        TOKEN_USAGE("usage"), MCP("mcp"), SKILLS("skills"), SETTINGS("settings")
    }

    private val settings = AppSettingsService.getInstance()
    private val pages = JPanel(CardLayout())
    private val welcomePage = WelcomePage(project) { navigateTo(Page.CHAT) }
    private var chatPage = ChatPage(project)
    private val loadedPages = mutableSetOf(Page.WELCOME, Page.CHAT)

    /**
     * 页面工厂注册表，对齐 docs/ui/pages.md §十二。
     * key=Page.id（"welcome"/"chat"/...），value=创建页面的工厂函数。
     */
    private val pageFactories = mutableMapOf<String, () -> JPanel>()

    private val tabBar = TabBar { navigateTo(it) }

    /**
     * 动态注册页面，对齐 docs/ui/pages.md §十二。
     * @param pageId 页面标识
     * @param factory 创建 Page 实例的工厂函数
     */
    fun registerPage(pageId: Page, factory: () -> JPanel) {
        pageFactories[pageId.id] = factory
    }

    /**
     * 页面切换回调，对齐 docs/ui/pages.md §十二。
     * 每次 navigateTo() 切换到新页面时触发。
     */
    var onPageChanged: ((Page) -> Unit)? = null

    init {
        // 注册硬编码页面的工厂函数，对齐 docs/ui/pages.md §二（懒加载）
        registerPage(Page.WELCOME) { welcomePage }
        registerPage(Page.CHAT) { chatPage }
        registerPage(Page.SESSIONS) {
            SessionsPage(
                project,
                onRestore = { id -> replaceChatPage(id) }
            )
        }
        registerPage(Page.TOKEN_USAGE) { TokenUsagePage(project) }
        registerPage(Page.MCP) { McpPage(project) }
        registerPage(Page.SKILLS) { SkillsPage(project) }
        registerPage(Page.SETTINGS) { SettingsPage() }

        pages.add(welcomePage, Page.WELCOME.id)
        pages.add(chatPage, Page.CHAT.id)

        add(tabBar, BorderLayout.NORTH)
        add(pages, BorderLayout.CENTER)

        // 面板宽度变化监听：更新所有气泡的最大宽度（docs/ui/design-system.md §八）
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (chatPage.isShowing) {
                    chatPage.updateBubbleMaxWidths(floating = isFloatingMode())
                }
            }
        })

        // 浮动/停靠模式切换监听：ToolWindow 浮动模式下气泡使用紧凑比例（95%/95%）
        addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(e: HierarchyEvent) {
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && chatPage.isShowing) {
                    chatPage.updateBubbleMaxWidths(floating = isFloatingMode())
                }
            }
        })

        // Auto-restore: open last active session if API key is set
        // 对齐 docs/ui/pages.md §二：CardLayout 不销毁隐藏页面，ChatPage 复用同一实例
        if (settings.getApiKey() != null) {
            val store = SessionStore(project)
            val last = store.listAll().maxByOrNull { it.updatedAt }
            if (last != null) {
                chatPage.restoreSession(last.id)
                navigateTo(Page.CHAT)
            } else {
                navigateTo(Page.CHAT)
            }
        } else {
            navigateTo(Page.WELCOME)
        }
    }

    fun navigateTo(page: Page) {
        val hasApiKey = settings.getApiKey() != null
        tabBar.setApiKeyConfigured(hasApiKey)
        val target = when {
            !hasApiKey -> Page.WELCOME
            page == Page.WELCOME -> Page.CHAT
            else -> page
        }
        ensurePage(target)
        val previous = currentPage
        (pages.layout as CardLayout).show(pages, target.id)
        currentPage = target
        tabBar.setSelected(target)
        if (target != previous) {
            onPageChanged?.invoke(target)
        }
    }

    /** 当前可见页面，对齐 docs/ui/pages.md §十二 */
    var currentPage: Page = Page.WELCOME
        private set

    private fun ensurePage(page: Page) {
        if (page in loadedPages) return
        val factory = pageFactories[page.id] ?: return
        val component = factory()
        pages.add(component, page.id)
        loadedPages.add(page)
    }

    /**
     * 切换 ChatPage 会话（对齐 docs/ui/pages.md §二：CardLayout 不销毁隐藏页面，
     * ChatPage 复用同一 JPanel 实例，仅切换内部 session）。
     * @param sessionId 要恢复的会话 ID，null 表示创建新会话
     */
    private fun replaceChatPage(sessionId: String?) {
        chatPage.restoreSession(sessionId)
        navigateTo(Page.CHAT)
    }

    /**
     * 判断当前 ToolWindow 是否处于浮动模式。
     * 浮动时 ToolWindow 位于独立的 JDialog 中，停靠时位于 IDE 主 JFrame 中。
     * 对齐 docs/ui/design-system.md §八：ToolWindow 浮动模式 → 同 < 250px 档位。
     */
    private fun isFloatingMode(): Boolean {
        val window = SwingUtilities.getWindowAncestor(this)
        return window is JDialog
    }
}
