package com.aiassistant

import com.aiassistant.agent_v3.AgentMessage
import com.aiassistant.mcp.McpManager
import com.aiassistant.shared.JsonUtils
import com.aiassistant.ui.AskUserBridge
import com.aiassistant.ui.BubbleFactory
import com.aiassistant.ui.ChatTheme
import com.aiassistant.ui.DiffLine
import com.aiassistant.ui.PermissionCard
import com.aiassistant.ui.PlanBar
import com.aiassistant.ui.SelectionCard
import com.aiassistant.ui.SimpleDiff
import com.aiassistant.ui.ToolRowFactory
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
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
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
            override fun mouseClicked(e: MouseEvent) { viewModel.clearConversation(); planBar.updatePlan(null); rebuildConversation() }
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

    /** DocumentListener 内修改文档时防止递归触发补全逻辑的重入标志 */
    private val inputListenerGuard = AtomicBoolean(false)

    /** 斜杠命令弹出菜单（懒初始化） */
    private var slashCommandPopup: JPopupMenu? = null

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

    // ---- plan bar（置顶，不随消息滚动）----
    private val planBar = PlanBar().also { it.updatePlan(null) }

    /**
     * conversationPanel 布局：
     *   NORTH  → northStack（conversationHeader + planBar，纵向堆叠）
     *   CENTER → conversationScrollPane（消息列表，可滚动）
     *
     * planBar 位于 northStack 内，因此它固定在滚动区域之上，不会随消息滚动。
     */
    private val northStack = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(conversationHeader)
        add(planBar)
    }

    private val conversationPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(northStack, BorderLayout.NORTH)
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

        // M5-A: 注册 ask_user 选择卡 handler（EDT 上执行；multiple=true 时多选模式）
        AskUserBridge.handler = { question, options, multiple, latch, result ->
            showSelectionCard(question, options, multiple, latch, result)
        }

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

        // M5-B: 输入框 / 与 @ 补全菜单
        setupInputCompletions()

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
        viewModel.onPlanUpdate = { plan ->
            ApplicationManager.getApplication().invokeLater { planBar.updatePlan(plan) }
        }
    }

    /** 内联工具权限确认卡 — 选项列表风格（M3-A），确认后卡片保留并展示已选状态 */
    private fun showConfirmationBar(name: String, args: String, latch: CountDownLatch, userChoice: AtomicBoolean) {
        // 仅 write_file 需要计算 diff，其余工具 diffLines = null
        val diffLines: List<DiffLine>? = if (name == "write_file") {
            computeWriteFileDiff(args)
        } else {
            null
        }

        val card = PermissionCard.build(
            toolName = name,
            args = args,
            onAllowOnce = {
                userChoice.set(true)
                latch.countDown()
            },
            onAlwaysAllow = {
                AppSettingsService.getInstance().addToolToWhitelist(name)
                userChoice.set(true)
                latch.countDown()
            },
            onReject = {
                userChoice.set(false)
                latch.countDown()
            },
            diffLines = diffLines
        )
        conversationContainer.add(card, conversationContainer.componentCount - 1)
        conversationContainer.revalidate()
        conversationContainer.repaint()
        scrollToBottom(force = true)
    }

    /**
     * ask_user 工具选择卡（M5-A / M5-B 多选扩展）。
     *
     * 在 EDT 上调用：构建 [SelectionCard] 并插入会话区。
     * 用户点击/确认后回调会 set result + countDown，工具背景线程随即解除阻塞。
     *
     * @param multiple true = 多选模式（复选框 + 确认按钮）；false = 单选模式（点击即提交）
     */
    private fun showSelectionCard(
        question: String,
        options: List<String>,
        multiple: Boolean,
        latch: CountDownLatch,
        result: AtomicReference<String>
    ) {
        val card = SelectionCard.build(
            question = question,
            options = options,
            multiSelect = multiple,
            onConfirm = { choices ->
                // 单选：choices 大小为 1；多选：choices 可为多项，用 ", " 连接
                result.set(choices.joinToString(", "))
                latch.countDown()
            }
        )
        conversationContainer.add(card, conversationContainer.componentCount - 1)
        conversationContainer.revalidate()
        conversationContainer.repaint()
        scrollToBottom(force = true)
    }

    /**
     * 解析 write_file 的 args JSON，读取旧文件，计算行级 diff。
     *
     * WriteFileTool 的参数：path（文件路径，相对项目根），content（文件内容）。
     * args 是原始 JSON 字符串，如：{"path":"src/Foo.kt","content":"line1\nline2"}
     *
     * content 值可能包含嵌入的换行转义（\n），使用 JsonUtils.unescapeJson 还原。
     * 任何异常均被吞掉，返回 null 使卡片降级为无 diff 模式。
     */
    private fun computeWriteFileDiff(args: String): List<DiffLine>? {
        return try {
            // ---- 1. 提取 path 字段 ----
            // 匹配 "path":"value" 中的 value（不含转义引号）
            val pathRegex = Regex(""""path"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val relativePath = pathRegex.find(args)?.groupValues?.get(1)
                ?.let { JsonUtils.unescapeJson(it) }
                ?: return null   // 找不到 path → 降级

            // ---- 2. 提取 content 字段 ----
            // content 值可能很长，且包含 \n 等转义序列。
            // 策略：找到 "content": 之后的第一个非转义结束引号。
            val newContent = extractJsonStringValue(args, "content") ?: return null

            // ---- 3. 读取旧文件（若不存在视为空文件） ----
            val basePath = project.basePath ?: return null
            val oldContent: String = try {
                val file = File(basePath, relativePath)
                if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else ""
            } catch (_: Exception) {
                ""
            }

            // ---- 4. 计算 diff ----
            SimpleDiff.diff(oldContent, newContent)
        } catch (_: Exception) {
            // 任何解析/IO 异常均安全降级
            null
        }
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值，并使用 JsonUtils.unescapeJson 还原转义。
     *
     * 简单实现：找到 `"key":` 后，扫描第一个 `"` 到第一个未转义的 `"` 之间的内容。
     * 不依赖完整 JSON 解析器，但可处理常见的 \n \t \" \\ 转义序列。
     *
     * @return 还原后的字符串，若未找到返回 null
     */
    private fun extractJsonStringValue(json: String, key: String): String? {
        // 找 "key": 的起始位置（key 本身也可能被转义，但 path/content 均为纯 ASCII）
        val keyPattern = "\"$key\""
        val keyStart = json.indexOf(keyPattern)
        if (keyStart < 0) return null

        // 跳过 key + 冒号 + 可能的空白
        var pos = keyStart + keyPattern.length
        while (pos < json.length && json[pos] in " \t\r\n") pos++
        if (pos >= json.length || json[pos] != ':') return null
        pos++ // 跳过 ':'
        while (pos < json.length && json[pos] in " \t\r\n") pos++
        if (pos >= json.length || json[pos] != '"') return null
        pos++ // 跳过开头 '"'

        // 扫描字符串值直到未转义的 '"'
        val sb = StringBuilder()
        while (pos < json.length) {
            val ch = json[pos]
            when {
                ch == '\\' && pos + 1 < json.length -> {
                    // 保留原始转义序列，由 unescapeJson 统一处理
                    sb.append('\\')
                    sb.append(json[pos + 1])
                    pos += 2
                }
                ch == '"' -> {
                    // 字符串结束
                    pos++
                    break
                }
                else -> {
                    sb.append(ch)
                    pos++
                }
            }
        }
        return JsonUtils.unescapeJson(sb.toString())
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
            // 思考/执行中指示器：agent 运行期间持续显示，避免 currentToolName 在
            // null 间隙（思考↔执行切换）导致指示器闪烁。
            val thinking = viewModel.currentToolName
            if (thinking != null) {
                if (thinking.contains("分析") || thinking.contains("思考")) {
                    conversationContainer.add(createThinkingBubble(thinking))
                } else {
                    conversationContainer.add(createToolRunningBubble(thinking))
                }
            } else if (viewModel.isStreaming && viewModel.streamingContent.isEmpty()) {
                // 运行中但暂无具体状态文字、且尚未开始输出 → 稳定显示"思考中"兜底
                conversationContainer.add(createThinkingBubble("思考中..."))
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
    private val toolRowFactory = ToolRowFactory()

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
     * 工具调用行 — 委托给 ToolRowFactory（单色折叠行，替代紫色气泡）
     */
    private fun createToolCallBubble(message: AgentMessage): JPanel {
        return toolRowFactory.toolCallRow(message)
    }

    /** 工具结果行 — 委托给 ToolRowFactory（默认折叠，替代绿色气泡） */
    private fun createToolResultBubble(message: AgentMessage): JPanel {
        return toolRowFactory.toolResultRow(message)
    }

    /** 思考中轻量指示器 — textMuted 斜体 */
    private fun createThinkingBubble(text: String): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
        }
        val label = JLabel(text).apply {
            font = ChatTheme.metaFont.deriveFont(java.awt.Font.ITALIC)
            foreground = ChatTheme.textMuted
            border = JBUI.Borders.empty(2, 4)
        }
        panel.add(label)
        panel.add(Box.createHorizontalGlue())
        return panel
    }

    /** 可折叠的思考内容行 — 委托给 ToolRowFactory（替代薰衣草色气泡） */
    private fun createCollapsibleThinkingBubble(content: String): JPanel {
        return toolRowFactory.thinkingRow(content)
    }

    /** 工具执行中行 — 委托给 ToolRowFactory（替代黄色气泡） */
    private fun createToolRunningBubble(toolName: String): JPanel {
        return toolRowFactory.runningRow(toolName)
    }

    // ---- M5-B: 输入框补全菜单（/ 命令 + @ 文件引用）----

    /**
     * 为 inputArea 挂载 DocumentListener，实现：
     *  - 输入框为空（或纯空白）时输入 `/` → 显示斜杠命令菜单
     *  - 在行首或空白符后输入 `@` → 打开文件选择器
     *
     * 使用 [inputListenerGuard]（AtomicBoolean）防止在 invokeLater 内修改文档时触发递归。
     * 触发条件（只在以下情况响应，不劫持句中的 / 或 @）：
     *  - `/`：输入后整个文本 trim 后恰好等于 "/"（即输入框实际上只有这一个字符）
     *  - `@`：新插入的字符为 `@`，且其前一位是行首/空白（或位于文本起始）
     */
    private fun setupInputCompletions() {
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = checkTrigger(e)
            override fun removeUpdate(e: DocumentEvent) = Unit
            override fun changedUpdate(e: DocumentEvent) = Unit

            private fun checkTrigger(e: DocumentEvent) {
                // 重入保护：若当前正在 invokeLater 内修改文档，直接跳过
                if (inputListenerGuard.get()) return

                // 只关心单字符插入（避免粘贴大段文本时误触）
                if (e.length != 1) return

                val text = try { inputArea.text } catch (_: Exception) { return }
                val insertPos = e.offset
                val insertedChar = try { e.document.getText(insertPos, 1) } catch (_: Exception) { return }

                when (insertedChar) {
                    "/" -> {
                        // 仅当输入框此时只含一个 "/" 字符（trim 后）时才弹出命令菜单
                        if (text.trim() == "/") {
                            SwingUtilities.invokeLater { showSlashCommandMenu() }
                        }
                    }
                    "@" -> {
                        // 仅当 @ 出现在行首或空白之后（即合理的文件引用触发位置）
                        val beforeAt = if (insertPos > 0) {
                            try { e.document.getText(insertPos - 1, 1) } catch (_: Exception) { " " }
                        } else {
                            " " // 位于文本最开头，视为空白后
                        }
                        if (beforeAt.isBlank()) {
                            SwingUtilities.invokeLater {
                                // 移除触发字符 @ 以获得更干净的体验，再打开文件选择器
                                inputListenerGuard.set(true)
                                try {
                                    // 找到并删除刚插入的 @（用最新 text 重新定位）
                                    val currentText = inputArea.text
                                    val atIdx = currentText.lastIndexOf('@')
                                    if (atIdx >= 0) {
                                        inputArea.document.remove(atIdx, 1)
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    inputListenerGuard.set(false)
                                }
                                showFilePicker()
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * 显示斜杠命令弹出菜单。
     *
     * 支持的命令（均绑定到现有真实操作）：
     *  - `/new`   — 新会话：清空对话 + 重建视图
     *  - `/stop`  — 停止生成：调用 viewModel.stopGeneration()
     *  - `/clear` — 清空输入：清除 inputArea 文本
     *
     * 弹出位置：紧贴 inputPanel 上方左对齐。
     * 键盘：Up/Down 选项，Enter 确认，Esc 关闭。
     * 选择后：先清除输入框中的 "/"，再执行对应操作。
     */
    private fun showSlashCommandMenu() {
        // 若当前正在流式输出，/stop 以外的命令通常无害，但仍按正常菜单展示
        val popup = JPopupMenu()

        data class Cmd(val name: String, val desc: String, val action: () -> Unit)
        val commands = listOf(
            Cmd("/new",   "新会话") {
                viewModel.clearConversation()
                planBar.updatePlan(null)
                rebuildConversation()
            },
            Cmd("/stop",  "停止生成") {
                viewModel.stopGeneration()
            },
            Cmd("/clear", "清空输入") {
                // 输入框已在 executeCommand 中清空，此处无需重复
            }
        )

        /** 清除输入框中的触发字符并执行命令 */
        fun executeCommand(cmd: Cmd) {
            popup.isVisible = false
            inputListenerGuard.set(true)
            try {
                inputArea.text = ""
            } finally {
                inputListenerGuard.set(false)
            }
            cmd.action()
        }

        for (cmd in commands) {
            val item = JMenuItem().apply {
                // 命令名用等宽字体，描述用普通字体，通过 HTML 排版
                text = "<html><tt style='font-size:12pt'>${cmd.name}</tt>" +
                        "&nbsp;&nbsp;<span style='color:gray;font-size:10pt'>${cmd.desc}</span></html>"
                addActionListener { executeCommand(cmd) }
            }
            popup.add(item)
        }

        // 键盘支持：Esc 关闭
        popup.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) popup.isVisible = false
            }
        })

        // 定位：显示在 inputPanel 正上方
        val popupHeight = popup.preferredSize.height
            .coerceAtLeast(commands.size * 32 + 8) // 估算高度（首次 preferredSize 可能为 0）
        popup.show(inputPanel, 0, -popupHeight)

        slashCommandPopup = popup
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
