package com.aiassistant

import com.aiassistant.agent_v3.AgentMessage
import com.aiassistant.mcp.McpManager
import com.aiassistant.ui.BubbleFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatWindow = ChatToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(chatWindow.panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class ChatToolWindow(private val project: Project) {

    companion object {
        private val refreshListeners = mutableListOf<() -> Unit>()
        private val instances = ConcurrentHashMap<Project, ChatToolWindow>()
        private val pendingTexts = ConcurrentHashMap<Project, String>()

        fun addRefreshListener(listener: () -> Unit) {
            refreshListeners.add(listener)
        }

        fun notifySettingsChanged() {
            refreshListeners.toList().forEach { it.invoke() }
        }

        fun insertText(project: Project, text: String) {
            val instance = instances[project]
            if (instance != null) {
                instance.insertAtCursor(text)
                instance.activateToolWindow()
            } else {
                pendingTexts[project] = text
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow("Code Assistant") ?: return
                toolWindow.activate { /* tool window is now visible */ }
            }
        }

        internal fun register(project: Project, instance: ChatToolWindow) {
            instances[project] = instance
        }
    }

    private val viewModel = ChatViewModel()

    // ---- conversation header ----
    private val newSessionBtn = JLabel("+").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        foreground = JBColor(0x888888, 0x999999)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "新会话"
        border = JBUI.Borders.empty(2, 6, 2, 6)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { viewModel.clearConversation(); rebuildConversation() }
            override fun mouseEntered(e: MouseEvent) { foreground = JBColor(0x2674B4, 0x5A9FD4) }
            override fun mouseExited(e: MouseEvent) { foreground = JBColor(0x888888, 0x999999) }
        })
    }
    private val conversationHeader = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 4, 8)
        add(JLabel("对话").apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            foreground = JBColor(0x666666, 0xAAAAAA)
        }, BorderLayout.WEST)
        add(newSessionBtn, BorderLayout.EAST)
    }

    // ---- conversation area ----
    private val conversationContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 10, 8, 10)
    }
    private val conversationScrollPane = JBScrollPane(
        conversationContainer,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = 16
        minimumSize = Dimension(100, 100)
    }

    private var projectFilesCache: List<String> = emptyList()

    // ---- input area ----
    private val inputArea = JTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        border = JBUI.Borders.empty(4, 4)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.modifiersEx == 0) {
                    e.consume(); sendMessage()
                }
            }
        })
        val originalHandler = transferHandler
        transferHandler = object : TransferHandler() {
            override fun canImport(info: TransferSupport): Boolean {
                if (info.isDataFlavorSupported(DataFlavor.imageFlavor)) return true
                return originalHandler?.canImport(info) ?: false
            }
            override fun importData(support: TransferSupport): Boolean {
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    return handleImagePaste(support.transferable)
                }
                return originalHandler?.importData(support) ?: false
            }
        }
    }

    /** 收集项目文件列表 */
    private fun collectProjectFiles(): List<String> {
        val basePath = project.basePath ?: return emptyList()
        val files = mutableListOf<String>()
        val dir = File(basePath)
        if (!dir.exists()) return files
        val ignoreDirs = setOf(".git", ".idea", ".gradle", "build", "node_modules", ".code-assistant", "vendor", "target", "out")
        dir.walkTopDown().maxDepth(5).forEach { f ->
            if (f.isFile && f.name !in ignoreDirs) {
                val relative = f.relativeTo(dir).path
                if (!ignoreDirs.any { relative.startsWith("$it/") || relative.startsWith("$it\\") }) {
                    files.add(relative)
                }
            }
        }
        return files.sorted()
    }
    // ---- reference chips (selected files/code) ----
    data class RefChip(val label: String, val fullPath: String, val content: String, val startLine: Int = 0, val endLine: Int = 0) {
        val displayName: String get() = when {
            startLine > 0 && endLine > 0 && startLine != endLine -> "$label $startLine-$endLine"
            startLine > 0 -> "$label $startLine"
            else -> label
        }
    }
    private val refChips = mutableListOf<RefChip>()
    private val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
    }

    private fun rebuildChips() {
        chipPanel.removeAll()
        for (chip in refChips) {
            val chipComp = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                background = JBColor(0xE3E8EE, 0x3A3E48)
                border = BorderFactory.createCompoundBorder(
                    roundedBorder(JBColor(0xC0C8D0, 0x505560)),
                    BorderFactory.createEmptyBorder(1, 6, 1, 4)
                )
                toolTipText = chip.fullPath
            }
            val displayText = if (chip.startLine > 0) {
                val lineInfo = if (chip.startLine == chip.endLine || chip.endLine == 0) "${chip.startLine}" else "${chip.startLine}-${chip.endLine}"
                "<html>${chip.label} <span style='color:#999'>$lineInfo</span></html>"
            } else {
                chip.label
            }
            chipComp.add(JLabel(displayText).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                foreground = JBColor(0x333333, 0xCCCCCC)
            })
            val removeBtn = JLabel("×").apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                foreground = JBColor(0x888888, 0xAAAAAA)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = BorderFactory.createEmptyBorder(0, 2, 0, 2)
                toolTipText = "移除引用"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        refChips.remove(chip)
                        rebuildChips()
                    }
                })
            }
            chipComp.add(removeBtn)
            chipPanel.add(chipComp)
        }
        chipPanel.revalidate()
        chipPanel.repaint()
    }

    fun addRefChip(label: String, fullPath: String, content: String, startLine: Int = 0, endLine: Int = 0) {
        // 去重：完全相同的文件+行号范围才跳过
        if (refChips.any { it.fullPath == fullPath && it.startLine == startLine && it.endLine == endLine }) return
        refChips.add(RefChip(label, fullPath, content, startLine, endLine))
        rebuildChips()
    }

    fun buildRefContent(): String {
        if (refChips.isEmpty()) return ""
        return refChips.joinToString("\n\n") { chip ->
            val lang = chip.fullPath.substringAfterLast('.').lowercase().let { if (it.length <= 10) it else "" }
            buildString {
                append("`${chip.fullPath}`\n\n")
                append("```$lang\n")
                append(chip.content)
                append("\n```")
            }
        }
    }

    // ---- file picker popup ----
    private var filePickerPopup: JPopupMenu? = null
    private val filePickerList = JBList(DefaultListModel<String>()).apply {
        visibleRowCount = 10
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) acceptFilePickerSelection()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) { e.consume(); acceptFilePickerSelection() }
                if (e.keyCode == KeyEvent.VK_ESCAPE) { filePickerPopup?.isVisible = false }
            }
        })
    }
    private val filePickerFilter = JBTextField().apply {
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> { e.consume(); acceptFilePickerSelection() }
                    KeyEvent.VK_ESCAPE -> { filePickerPopup?.isVisible = false }
                    KeyEvent.VK_DOWN -> {
                        e.consume()
                        val model = filePickerList.model as DefaultListModel<String>
                        if (model.size > 0) {
                            val i = minOf(filePickerList.selectedIndex + 1, model.size - 1)
                            filePickerList.selectedIndex = i
                            filePickerList.ensureIndexIsVisible(i)
                        }
                    }
                    KeyEvent.VK_UP -> {
                        e.consume()
                        val i = maxOf(filePickerList.selectedIndex - 1, 0)
                        filePickerList.selectedIndex = i
                        filePickerList.ensureIndexIsVisible(i)
                    }
                }
            }
        })
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { updateFileList(text) }
            override fun removeUpdate(e: DocumentEvent?) { updateFileList(text) }
            override fun changedUpdate(e: DocumentEvent?) { updateFileList(text) }
        })
    }

    private fun showFilePicker() {
        if (filePickerPopup == null) {
            val panel = JPanel(BorderLayout())
            val scrollFiles = JBScrollPane(filePickerList).apply { preferredSize = Dimension(400, 250) }
            panel.add(scrollFiles, BorderLayout.CENTER)
            val filterPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
                )
                filePickerFilter.putClientProperty("JTextField.Search.Gap", 4)
                filePickerFilter.putClientProperty("JTextField.Search.Placeholder", "筛选文件或文件夹...")
            }
            filterPanel.add(filePickerFilter)
            panel.add(filterPanel, BorderLayout.SOUTH)
            filePickerPopup = JPopupMenu().apply { add(panel) }
        }
        if (projectFilesCache.isEmpty()) projectFilesCache = collectProjectFiles()
        updateFileList("")
        filePickerList.selectedIndex = 0
        // 弹窗宽度与输入区一致，紧贴加号上方
        val popupWidth = inputPanel.width
        filePickerPopup!!.preferredSize = Dimension(popupWidth, filePickerPopup!!.preferredSize.height)
        val plusPos = SwingUtilities.convertPoint(plusButton, 0, 0, panel)
        filePickerPopup!!.show(panel, 0, plusPos.y - filePickerPopup!!.preferredSize.height)
        SwingUtilities.invokeLater { filePickerFilter.requestFocus() }
    }

    private fun updateFileList(query: String) {
        val model: DefaultListModel<String> = filePickerList.model as DefaultListModel<String>
        model.clear()
        val q = query.lowercase()
        val filtered = if (q.isEmpty()) projectFilesCache.take(50) else projectFilesCache.filter { it.lowercase().contains(q) }.take(50)
        filtered.forEach { model.addElement(it) }
        if (model.size > 0) filePickerList.selectedIndex = 0
    }

    private fun acceptFilePickerSelection() {
        val selected = filePickerList.selectedValue ?: return
        filePickerPopup?.isVisible = false
        filePickerFilter.text = ""
        val basePath = project.basePath ?: return
        val file = File(basePath, selected)
        if (file.isFile && file.length() < 500_000) {
            try {
                val content = file.readText()
                addRefChip(selected, selected, content)
            } catch (_: Exception) {}
        }
    }

    // ---- plus button ----
    private val plusButton = JLabel("+").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        foreground = JBColor(0x888888, 0x999999)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        toolTipText = "添加文件引用"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { showFilePicker() }
        })
    }

    // ---- submit/stop button (arrow → stop) ----
    private val lingmaSubmitBtn = JLabel("→").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        foreground = JBColor(0x888888, 0xAAAAAA)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = BorderFactory.createEmptyBorder(0, 4, 4, 6)
        toolTipText = "发送 (Enter)"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (viewModel.isStreaming) viewModel.stopGeneration() else sendMessage()
            }
            override fun mouseEntered(e: MouseEvent) {
                if (!viewModel.isStreaming) foreground = JBColor(0x2674B4, 0x5A9FD4)
            }
            override fun mouseExited(e: MouseEvent) {
                if (!viewModel.isStreaming) foreground = JBColor(0x888888, 0xAAAAAA)
            }
        })
    }

    // ---- main input panel (Lingma style) ----
    private val lingmaInputBorder = BorderFactory.createCompoundBorder(
        roundedBorder(JBColor(0xD0D0D0, 0x505050)),
        BorderFactory.createEmptyBorder(7, 10, 7, 10)
    )
    private val lingmaInputBorderFocused = BorderFactory.createCompoundBorder(
        roundedBorder(JBColor(0x4A90D9, 0x5A9FD4), 2),
        BorderFactory.createEmptyBorder(6, 9, 6, 9)
    )

    private val inputPanel = JPanel(BorderLayout(0, 0)).apply {
        preferredSize = Dimension(200, 120)
        minimumSize = Dimension(150, 80)
        border = lingmaInputBorder
        isOpaque = true
        background = JBColor(0xFAFBFC, 0x2B2D30)

        // top row: plus + chips (wrapping)
        val topRow = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
            add(plusButton)
            add(chipPanel)
            add(Box.createHorizontalGlue())
        }
        add(topRow, BorderLayout.NORTH)

        // center: text area (no border)
        val scrollInput = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            minimumSize = Dimension(100, 40)
        }
        add(scrollInput, BorderLayout.CENTER)

        // bottom-right: submit button
        val bottomRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false; add(lingmaSubmitBtn) }
        add(bottomRow, BorderLayout.SOUTH)

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { border = lingmaInputBorderFocused }
            override fun focusLost(e: FocusEvent) { border = lingmaInputBorder }
        })
    }

    // ---- error banner ----
    private val errorBanner = JLabel().apply {
        isVisible = false
        isOpaque = true
        border = JBUI.Borders.empty(6, 12)
        font = font.deriveFont(Font.PLAIN, 12f)
        horizontalAlignment = SwingConstants.CENTER
    }

    private var welcomePanel: JPanel? = null
    private val bubbleSizeConstraints = mutableListOf<Pair<JPanel, JComponent>>()
    // 流式气泡增量更新
    private var streamingBubble: JPanel? = null
    private var streamingContentPane: JComponent? = null
    // 自动引用去重
    private var lastAutoInsertedHash: Int = 0
    private var lastAutoInsertTime: Long = 0

    private val conversationPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(conversationHeader, BorderLayout.NORTH)
        add(conversationScrollPane, BorderLayout.CENTER)
    }

    // ---- main panel ----
    // 注意：不能混用绝对定位(SOUTH)和相对定位(PAGE_END)，相对定位会覆盖绝对定位
    val panel = JPanel(BorderLayout()).apply {
        add(errorBanner, BorderLayout.NORTH)
        add(createWelcomePanel(), BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }

    init {
        register(project, this)
        // v3: 注册内置工具+Skills（同步安全），首条消息即可调用
        viewModel.initialize(project)
        // MCP 延迟加载（需 COMPONENTS_LOADED 之后）
        ApplicationManager.getApplication().invokeLater {
            val mcpManager = McpManager(project)
            val mcpTools = try { mcpManager.loadAndConnect() } catch (_: Exception) { emptyList() }
            viewModel.addMcpTools(mcpTools)
        }
        // 处理在工具窗口创建之前就已排队的文本
        val pending = pendingTexts.remove(project)
        if (pending != null) {
            ApplicationManager.getApplication().invokeLater {
                insertAtCursor(pending)
            }
        }
        bindViewModel()
        addRefreshListener { checkEmptyState() }
        ApplicationManager.getApplication().invokeLater { checkEmptyState() }

        // 延迟注册编辑器选区监听，避免在 IDE 启动早期阶段（COMPONENTS_LOADED 之前）访问消息总线
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                        ApplicationManager.getApplication().invokeLater {
                            autoInsertSelectedCode()
                        }
                    }
                }
            )
        }
    }

    private fun createWelcomePanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            background = JBColor(0xFAFBFC, 0x2B2D30)
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            val titleLabel = JLabel(AiAssistantBundle.message("chat.welcome.title")).apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, 20)
                foreground = JBColor(0x333333, 0xCCCCCC)
                alignmentX = Component.CENTER_ALIGNMENT
            }
            val descLabel = JLabel(AiAssistantBundle.message("chat.welcome.desc")).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
                foreground = JBColor(0x666666, 0xAAAAAA)
                alignmentX = Component.CENTER_ALIGNMENT
            }
            val poweredLabel = JLabel(AiAssistantBundle.message("chat.welcome.powered")).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                foreground = JBColor(0x888888, 0x888888)
                alignmentX = Component.CENTER_ALIGNMENT
            }
            inner.add(titleLabel)
            inner.add(Box.createVerticalStrut(4))
            inner.add(descLabel)
            inner.add(Box.createVerticalStrut(8))
            inner.add(poweredLabel)
            inner.add(Box.createVerticalStrut(16))
            val configBtn = JButton(AiAssistantBundle.message("chat.welcome.config")).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                addActionListener {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, "com.aiassistant.settings")
                }
            }
            inner.add(configBtn)
            add(inner, GridBagConstraints())
        }.also { welcomePanel = it }
    }

    private fun bindViewModel() {
        viewModel.onMessagesChanged = {
            ApplicationManager.getApplication().invokeLater {
                rebuildConversation()
                checkEmptyState()
            }
        }
        viewModel.onStreamingUpdate = {
            ApplicationManager.getApplication().invokeLater { updateStreamingBubble() }
        }
        viewModel.onStreamingStateChanged = { streaming ->
            ApplicationManager.getApplication().invokeLater {
                inputArea.isEnabled = !streaming
                lingmaSubmitBtn.text = if (streaming) "■" else "→"
                lingmaSubmitBtn.toolTipText = if (streaming) "停止" else "发送 (Enter)"
                if (!streaming) {
                    lingmaSubmitBtn.foreground = JBColor(0x888888, 0xAAAAAA)
                    // 流式结束，做一次最终布局修正
                    bubbleSizeConstraints.forEach { (b, c) -> bubbleFactory.fitWidth(b, c) }
                    conversationContainer.revalidate()
                    conversationContainer.repaint()
                }
            }
        }
        viewModel.onError = { msg ->
            ApplicationManager.getApplication().invokeLater {
                if (msg != null) showError(msg) else hideError()
            }
        }
        viewModel.onRateLimitCountdown = { countdown ->
            ApplicationManager.getApplication().invokeLater {
                if (countdown > 0) showWarning(AiAssistantBundle.message("chat.error.ratelimit", countdown)) else hideError()
            }
        }
        viewModel.onToolExecute = { toolName, params ->
            ApplicationManager.getApplication().invokeLater { rebuildConversation() }
        }
        viewModel.onToolResult = { toolName, result ->
            ApplicationManager.getApplication().invokeLater { rebuildConversation() }
        }
        viewModel.onConfirmTool = { name, args, latch, result ->
            ApplicationManager.getApplication().invokeLater {
                showConfirmationBar(name, args, latch, result)
            }
        }
    }

    /** 内联工具确认条 — 灰白背景，确认后留作执行记录 */
    private fun showConfirmationBar(name: String, args: String, latch: CountDownLatch, userChoice: AtomicBoolean) {
        val argsPreview = args.take(200).let { if (args.length > 200) "$it..." else it }
        val bar = JPanel(BorderLayout()).apply {
            background = JBColor(0xEEEEEE, 0x3A3A3E)
            border = BorderFactory.createCompoundBorder(
                roundedBorder(JBColor(0xCCCCCC, 0x505050)),
                JBUI.Borders.empty(6, 10)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 40)
        }
        val infoLabel = JLabel("<html><b>$name</b> <span style='color:#888'>$argsPreview</span></html>").apply {
            font = SMALL_FONT
            foreground = JBColor(0x333333, 0xCCCCCC)
        }
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        val alwaysBtn = JButton("始终允许").apply {
            font = SMALL_FONT.deriveFont(Font.BOLD)
            isFocusPainted = false
            toolTipText = "允许执行并记住，以后不再询问"
            addActionListener {
                AppSettingsService.getInstance().addToolToWhitelist(name)
                userChoice.set(true)
                latch.countDown()
                btnPanel.removeAll()
                btnPanel.add(JLabel("已加入白名单 ✓").apply {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                    foreground = JBColor(0x1B5E20, 0x4CAF50)
                })
                btnPanel.revalidate()
                btnPanel.repaint()
            }
        }
        val approveBtn = JButton("允许").apply {
            font = SMALL_FONT
            foreground = JBColor(0x2E7D32, 0x81C784)
            isFocusPainted = false
            addActionListener {
                userChoice.set(true)
                latch.countDown()
                btnPanel.removeAll()
                btnPanel.add(JLabel("已允许 ✓").apply {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                    foreground = JBColor(0x2E7D32, 0x81C784)
                })
                btnPanel.revalidate()
                btnPanel.repaint()
            }
        }
        val rejectBtn = JButton("拒绝").apply {
            font = SMALL_FONT
            foreground = JBColor(0xB00020, 0xFF8080)
            isFocusPainted = false
            addActionListener {
                userChoice.set(false)
                latch.countDown()
                btnPanel.removeAll()
                btnPanel.add(JLabel("已拒绝 ✗").apply {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                    foreground = JBColor(0xB00020, 0xFF8080)
                })
                btnPanel.revalidate()
                btnPanel.repaint()
            }
        }
        btnPanel.add(alwaysBtn)
        btnPanel.add(approveBtn)
        btnPanel.add(rejectBtn)
        bar.add(infoLabel, BorderLayout.CENTER)
        bar.add(btnPanel, BorderLayout.EAST)
        conversationContainer.add(bar, conversationContainer.componentCount - 1)
        conversationContainer.revalidate()
        conversationContainer.repaint()
        scrollToBottom(force = true)
    }

    private fun rebuildConversation() {
        conversationContainer.removeAll()
        bubbleSizeConstraints.clear()
        // 过滤掉 system 消息
        val displayMessages = viewModel.messages.filter { it.role != "system" }
        val hasMessages = displayMessages.isNotEmpty() || viewModel.streamingContent.isNotEmpty()
        if (hasMessages) {
            for (msg in displayMessages) {
                conversationContainer.add(createMessageBubble(msg))
            }
            if (viewModel.streamingContent.isNotEmpty()) {
                conversationContainer.add(
                    createMessageBubble(AgentMessage("assistant", viewModel.streamingContent))
                )
            }
            // 思考/执行中指示器
            val thinking = viewModel.currentToolName
            if (thinking != null) {
                if (thinking.contains("分析") || thinking.contains("思考")) {
                    conversationContainer.add(createThinkingBubble(thinking))
                } else {
                    conversationContainer.add(createToolRunningBubble(thinking))
                }
            }
        } else {
            val hintPanel = JPanel(GridBagLayout())
            hintPanel.add(
                JLabel(AiAssistantBundle.message("chat.empty.hint")).apply {
                    font = font.deriveFont(Font.PLAIN, 13f)
                    foreground = JBColor(0x767676, 0xAAAAAA)
                    horizontalAlignment = SwingConstants.CENTER
                },
                GridBagConstraints()
            )
            conversationContainer.add(hintPanel)
        }
        conversationContainer.add(Box.createVerticalGlue())
        conversationContainer.revalidate()
        conversationContainer.repaint()
        scrollToBottom(force = true)
        streamingBubble = null; streamingContentPane = null
    }

    private val markdownRenderer = MarkdownRenderer()
    private val bubbleFactory = BubbleFactory(conversationScrollPane)

    /** 流式更新时原地替换 JTextPane 文本，避免 remove/add 触发布局震荡 */
    private fun updateStreamingBubble() {
        if (streamingBubble == null) {
            // 首次流式：移除 glue，添加流式气泡，再加 glue
            val glueCount = conversationContainer.components.count { it is Box.Filler }
            if (glueCount > 0) {
                val lastGlue = conversationContainer.components.last { it is Box.Filler }
                conversationContainer.remove(lastGlue)
            }
            val bubble = createAssistantBubble(AgentMessage("assistant", viewModel.streamingContent))
            conversationContainer.add(bubble)
            conversationContainer.add(Box.createVerticalGlue())
            val entry = bubbleSizeConstraints.lastOrNull()
            if (entry != null) {
                streamingBubble = entry.first
                streamingContentPane = entry.second
            }
            conversationContainer.revalidate()
            scrollToBottom(force = true)
        } else {
            // 原地更新 JTextPane 文本，不 remove/add 组件
            val contentPane = streamingContentPane
            if (contentPane is JPanel) {
                val heightChanged = markdownRenderer.updateInPlace(contentPane, viewModel.streamingContent)
                if (heightChanged) {
                    bubbleFactory.fitWidth(streamingBubble!!, contentPane)
                    streamingBubble!!.revalidate()
                }
                streamingBubble!!.repaint()
            }
            // 只在用户已在底部时才跟随
            scrollToBottom(force = false)
        }
    }

    private fun createMessageBubble(message: AgentMessage): JPanel {
        return when (message.role) {
            "thinking" -> createCollapsibleThinkingBubble(message.content)
            "tool" -> createToolResultBubble(message)
            "user" -> createUserBubble(message)
            "assistant" -> {
                if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
                    createToolCallBubble(message)
                } else {
                    createAssistantBubble(message)
                }
            }
            else -> createAssistantBubble(message)
        }
    }

    private fun roundedBorder(color: JBColor, thickness: Int = 1): javax.swing.border.Border {
        return object : javax.swing.border.AbstractBorder() {
            override fun paintBorder(c: Component, g: java.awt.Graphics, x: Int, y: Int, w: Int, h: Int) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = java.awt.BasicStroke(thickness.toFloat())
                g2.drawRoundRect(x, y, w - 1, h - 1, 12, 12)
                g2.dispose()
            }
        }
    }

    private val BODY_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    private val SMALL_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 11)

    private fun createUserBubble(message: AgentMessage): JPanel {
        val (row, bubble, content) = bubbleFactory.userBubble(message)
        bubbleSizeConstraints.add(Pair(bubble, content))
        return row
    }

    private fun createAssistantBubble(message: AgentMessage): JPanel {
        val (row, bubble, content) = bubbleFactory.assistantBubble(message)
        bubbleSizeConstraints.add(Pair(bubble, content))
        return row
    }

    /**
     * 工具调用气泡 — 显示 AI 正在调用什么工具
     */
    private fun createToolCallBubble(message: AgentMessage): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
        }
        val bubble = JPanel(BorderLayout()).apply {
            background = JBColor(0xF3E5F5, 0x2D2435)
            border = BorderFactory.createCompoundBorder(
                roundedBorder(JBColor(0xCE93D8, 0x563D5C)),
                JBUI.Borders.empty(4, 8)
            )
        }
        val toolCalls = message.toolCalls ?: emptyList()
        val headerLabel = JLabel("工具调用 · ${toolCalls.size}").apply {
            font = SMALL_FONT
            foreground = JBColor(0x7B1FA2, 0xCE93D8)
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bubble.background
        }
        for (tc in toolCalls) {
            val argsPreview = tc.arguments.take(100).let { if (tc.arguments.length > 100) "$it..." else it }
            body.add(JLabel("<html><b>${tc.name}</b> <span style='color:#888'>$argsPreview</span></html>").apply {
                font = SMALL_FONT
            })
        }
        bubble.add(headerLabel, BorderLayout.NORTH)
        bubble.add(body, BorderLayout.CENTER)
        panel.add(bubble)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    private fun createToolResultBubble(message: AgentMessage): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
        }
        val toolName = message.toolName ?: "tool"
        val bubble = JPanel(BorderLayout()).apply {
            background = JBColor(0xE8F5E9, 0x1B3A1C)
            border = BorderFactory.createCompoundBorder(
                roundedBorder(JBColor(0xA5D6A7, 0x3C5A3C)),
                JBUI.Borders.empty(4, 8)
            )
        }
        val headerLabel = JLabel("结果 · $toolName").apply {
            font = SMALL_FONT
            foreground = JBColor(0x2E7D32, 0x81C784)
        }
        val resultText = message.content.let {
            if (it.length > 2000) it.take(2000) + "\n... (已截断)" else it
        }
        val contentPane = JTextArea(resultText).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = bubble.background; border = null
            foreground = JBColor(0x333333, 0xCCCCCC)
        }
        bubble.add(headerLabel, BorderLayout.NORTH)
        bubble.add(contentPane, BorderLayout.CENTER)
        panel.add(bubble)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    /** 思考中轻量指示器 */
    private fun createThinkingBubble(text: String): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
        }
        val label = JLabel(text).apply {
            font = Font(Font.SANS_SERIF, Font.ITALIC, 11)
            foreground = JBColor(0x999999, 0x888888)
            border = JBUI.Borders.empty(2, 4)
        }
        panel.add(label)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    /** 可折叠的思考内容气泡 */
    private fun createCollapsibleThinkingBubble(content: String): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 2, 0)
        }
        val bubble = JPanel(BorderLayout()).apply {
            background = JBColor(0xF5F0FF, 0x2A2530)
            border = BorderFactory.createCompoundBorder(
                roundedBorder(JBColor(0xD5CCE0, 0x4A4055)),
                JBUI.Borders.empty(4, 10)
            )
        }
        // 折叠时显示前两行
        val firstTwoLines = content.lines().take(2).joinToString(" ").take(100)
            .let { if (content.length > 100) "$it..." else it }
        val collapsed = AtomicBoolean(true)
        val headerLabel = JLabel(firstTwoLines).apply {
            font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            foreground = JBColor(0x999999, 0x888888)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "点击展开思考过程"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    collapsed.set(!collapsed.get())
                    bubble.removeAll()
                    buildThinkingContent(bubble, content, collapsed.get())
                    bubble.revalidate()
                    bubble.repaint()
                }
            })
        }
        buildThinkingContent(bubble, content, true)
        panel.add(bubble)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    private fun buildThinkingContent(bubble: JPanel, content: String, collapsed: Boolean) {
        if (collapsed) {
            val firstTwoLines = content.lines().take(2).joinToString(" ").take(100)
                .let { if (content.length > 100) "$it..." else it }
            bubble.add(JLabel(firstTwoLines).apply {
                font = SMALL_FONT
                foreground = JBColor(0x999999, 0x888888)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "点击展开思考过程"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val c = (bubble.getClientProperty("collapsed") as? Boolean) ?: true
                        bubble.putClientProperty("collapsed", !c)
                        bubble.removeAll()
                        buildThinkingContent(bubble, content, !c)
                        bubble.revalidate()
                        bubble.repaint()
                    }
                })
            }, BorderLayout.NORTH)
        } else {
            // 展开后灰色切换提示
            bubble.add(JLabel("收起").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                foreground = JBColor(0x999999, 0x888888)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        bubble.putClientProperty("collapsed", true)
                        bubble.removeAll()
                        buildThinkingContent(bubble, content, true)
                        bubble.revalidate()
                        bubble.repaint()
                    }
                })
            }, BorderLayout.NORTH)
            val textArea = JTextArea(content).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true
                font = BODY_FONT
                background = bubble.background; border = null
                foreground = JBColor(0x333333, 0xCCCCCC)
            }
            bubble.add(textArea, BorderLayout.CENTER)
        }
    }

    /**
     * 工具执行中指示器气泡
     */
    private fun createToolRunningBubble(toolName: String): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 2, 0)
        }
        val bubble = JPanel(BorderLayout()).apply {
            background = JBColor(0xFFF8E1, 0x3C3214)
            border = BorderFactory.createCompoundBorder(
                roundedBorder(JBColor(0xFFE082, 0x5C4A1C)),
                JBUI.Borders.empty(6, 10)
            )
        }
        val label = JLabel("执行中 · $toolName").apply {
            font = SMALL_FONT
            foreground = JBColor(0xE65100, 0xFFB74D)
        }
        bubble.add(label, BorderLayout.CENTER)
        panel.add(bubble)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    private fun sendMessage() {
        val textContent = inputArea.text.trim()
        val refContent = buildRefContent()
        if (textContent.isEmpty() && refContent.isEmpty()) {
            showWarning(AiAssistantBundle.message("chat.error.empty"))
            return
        }
        val fullContent = if (refContent.isNotEmpty() && textContent.isNotEmpty()) {
            "$textContent\n\n$refContent"
        } else {
            textContent.ifEmpty { refContent }
        }
        // PasswordSafe 是慢操作，不能阻塞 EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val apiKey = try {
                AppSettingsService.getInstance().getApiKey()
            } catch (e: Exception) { null }
            ApplicationManager.getApplication().invokeLater {
                if (apiKey.isNullOrBlank()) {
                    showError(AiAssistantBundle.message("chat.error.nokey"))
                    return@invokeLater
                }
                hideError()
                inputArea.text = ""
                refChips.clear()
                rebuildChips()
                viewModel.sendMessage(apiKey, fullContent)
            }
        }
    }

    private fun checkEmptyState() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val hasApiKey = try {
                !AppSettingsService.getInstance().getApiKey().isNullOrBlank()
            } catch (_: Exception) { false }
            ApplicationManager.getApplication().invokeLater {
                if (hasApiKey && welcomePanel != null) {
                    switchToConversationView()
                } else if (!hasApiKey && welcomePanel == null) {
                    switchToWelcomeView()
                }
            }
        }
    }

    private fun switchToWelcomeView() {
        if (conversationPanel.parent != null) {
            panel.remove(conversationPanel)
        }
        val welcome = createWelcomePanel()
        panel.add(welcome, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun switchToConversationView() {
        val wp = welcomePanel ?: return
        panel.remove(wp)
        panel.add(conversationPanel, BorderLayout.CENTER)
        rebuildConversation()
        panel.revalidate()
        panel.repaint()
        welcomePanel = null
    }

    fun insertAtCursor(text: String) {
        val current = inputArea.text
        val pos = inputArea.caretPosition
        val separator = if (current.isNotEmpty() && current.last() != '\n') "\n" else ""
        inputArea.insert("$separator$text", pos)
        inputArea.caretPosition = pos + separator.length + text.length
        inputArea.requestFocus()
    }

    private fun insertCodeReference() {
        addSelectionToChips()
    }

    private fun addSelectionToChips() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        if (!editor.selectionModel.hasSelection()) return
        val selectedText = editor.selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val relativePath = if (file != null) {
            val basePath = project.basePath
            if (basePath != null && file.path.startsWith(basePath)) {
                file.path.removePrefix(basePath).removePrefix("/")
            } else file.name
        } else "unknown"
        val doc = editor.document
        val startLine = doc.getLineNumber(editor.selectionModel.selectionStart) + 1
        val endLine = doc.getLineNumber(editor.selectionModel.selectionEnd) + 1
        addRefChip(relativePath, relativePath, selectedText, startLine, endLine)
        activateToolWindow()
    }

    private fun insertFileReference() {
        showFilePicker()
    }

    private fun autoInsertSelectedCode() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Assistant") ?: return
        if (!toolWindow.isVisible) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        if (!editor.selectionModel.hasSelection()) return
        val selectedText = editor.selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return
        val hash = selectedText.hashCode()
        val now = System.currentTimeMillis()
        if (hash == lastAutoInsertedHash && now - lastAutoInsertTime < 3000) return
        lastAutoInsertedHash = hash
        lastAutoInsertTime = now
        addSelectionToChips()
    }

    private fun handleImagePaste(transferable: Transferable): Boolean {
        val image = try {
            transferable.getTransferData(DataFlavor.imageFlavor) as? Image
        } catch (_: Exception) {
            null
        } ?: return false

        try {
            val bufferedImage = BufferedImage(
                image.getWidth(null).takeIf { it > 0 } ?: return false,
                image.getHeight(null).takeIf { it > 0 } ?: return false,
                BufferedImage.TYPE_INT_ARGB
            )
            val g = bufferedImage.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()

            val baos = ByteArrayOutputStream()
            ImageIO.write(bufferedImage, "png", baos)
            val imageBytes = baos.toByteArray()
            val base64 = Base64.getEncoder().encodeToString(imageBytes)

            // 保存到项目目录方便后续查找
            try {
                val imagesDir = File(project.basePath, ".code-assistant/images")
                imagesDir.mkdirs()
                val imageFile = File(imagesDir, "pasted_${System.currentTimeMillis()}.png")
                imageFile.writeBytes(imageBytes)
            } catch (_: Exception) { /* 保存失败不影响粘贴 */ }

            val markdown = "![image](data:image/png;base64,$base64)\n"
            insertAtCursor(markdown)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun activateToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Code Assistant") ?: return
        toolWindow.activate { /* activated */ }
    }

    private fun showError(message: String) {
        errorBanner.text = message
        errorBanner.background = JBColor(0xFFEBEE, 0x462828)
        errorBanner.foreground = JBColor(0xB00020, 0xFFB4B4)
        errorBanner.isVisible = true
    }

    private fun showWarning(message: String) {
        errorBanner.text = message
        errorBanner.background = JBColor(0xFFF3CD, 0x3C3214)
        errorBanner.foreground = JBColor(0x856404, 0xFFE696)
        errorBanner.isVisible = true
    }

    private fun hideError() {
        errorBanner.isVisible = false
    }

    /** 仅当用户已在底部附近时才自动滚动，避免打断浏览历史消息 */
    private fun scrollToBottom(force: Boolean = false) {
        SwingUtilities.invokeLater {
            val bar = conversationScrollPane.verticalScrollBar
            if (force) {
                bar.value = bar.maximum
            } else {
                val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 80
                if (atBottom) bar.value = bar.maximum
            }
        }
    }
}
