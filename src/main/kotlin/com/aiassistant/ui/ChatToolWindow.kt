package com.aiassistant.ui

import com.aiassistant.AppSettingsService
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.page.*
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

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

    private val tabBar = TabBar { navigateTo(it) }

    init {
        pages.add(welcomePage, Page.WELCOME.id)
        pages.add(chatPage, Page.CHAT.id)

        add(tabBar, BorderLayout.NORTH)
        add(pages, BorderLayout.CENTER)

        // Auto-restore: open last active session if API key is set
        if (settings.getApiKey() != null) {
            val store = SessionStore(project)
            val last = store.listAll().maxByOrNull { it.updatedAt }
            if (last != null) {
                pages.remove(chatPage)
                chatPage = ChatPage(project, last.id)
                pages.add(chatPage, Page.CHAT.id)
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
        (pages.layout as CardLayout).show(pages, target.id)
        tabBar.setSelected(target)
    }

    private fun ensurePage(page: Page) {
        if (page in loadedPages) return
        val component = when (page) {
            Page.SESSIONS -> SessionsPage(
                project,
                onRestore = { id -> replaceChatPage(ChatPage(project, id)) },
                onNewSession = { replaceChatPage(ChatPage(project)) }
            )

            Page.TOKEN_USAGE -> TokenUsagePage(project)
            Page.MCP -> McpPage(project)
            Page.SKILLS -> SkillsPage(project)
            Page.SETTINGS -> SettingsPage()
            Page.WELCOME, Page.CHAT -> return
        }
        pages.add(component, page.id)
        loadedPages.add(page)
    }

    private fun replaceChatPage(next: ChatPage) {
        pages.remove(chatPage)
        chatPage = next
        pages.add(chatPage, Page.CHAT.id)
        navigateTo(Page.CHAT)
    }
}
