package com.aiassistant

import com.aiassistant.agent.AgentMessage
import com.aiassistant.agent.ImageData
import com.aiassistant.mcp.McpManager
import com.aiassistant.shared.JsonUtils
import com.aiassistant.ui.ApprovalActions
import com.aiassistant.ui.AskUserBridge
import com.aiassistant.ui.BubbleFactory
import com.aiassistant.ui.ChatTheme
import com.aiassistant.ui.PlanBar
import com.aiassistant.ui.SelectionCard
import com.aiassistant.ui.ToolRowFactory
import com.aiassistant.ui.WrapLayout
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
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
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
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
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatWindow = ChatToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(chatWindow.panel, "", false)
        // 工具窗关闭/项目关闭时清理 handler 与 agent，避免泄漏
        content.setDisposer(com.intellij.openapi.Disposable { chatWindow.dispose() })
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
        fun removeRefreshListener(listener: () -> Unit) {
            refreshListeners.remove(listener)
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

    private val checkEmptyListener: () -> Unit = { checkEmptyState() }
    private val viewModel = ChatViewModel()

    /** 本窗口注册到 AskUserBridge 的 handler 引用，dispose 时据此安全解绑。 */
    private var askUserHandler: ((String, List<String>, Boolean, CountDownLatch, AtomicReference<String>) -> Unit)? = null

    /** 上次重算气泡时的 viewport 宽度，用于仅在宽度真正变化时重算（避免无谓重排）。 */
    private var lastViewportWidth = -1

    /**
     * viewport 就绪/变化后让所有气泡重测尺寸。
     * 气泡是自测量组件（[com.aiassistant.ui.ChatBubble]），尺寸在其
     * getPreferredSize/getMaximumSize 中按当前 viewport 宽度实时计算，
     * 因此这里只需触发一次布局失效即可，无需手动重算/冻结。
     */
    private fun refitAllBubbles() {
        conversationContainer.revalidate()
        conversationContainer.repaint()
    }

    /** 工具窗关闭时调用：解绑全局 handler，停止 agent，避免回调打到失效 UI / 线程泄漏。 */
    fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(popupKeyDispatcher)
        removeRefreshListener(checkEmptyListener)
        // 清理所有 editor selection listeners
        editorListeners.forEach { (editor, listener) ->
            editor.selectionModel.removeSelectionListener(listener)
        }
        editorListeners.clear()
        com.intellij.openapi.util.Disposer.dispose(editorListenerDisposable)
        trackedEditors.clear()
        if (askUserHandler != null && AskUserBridge.handler === askUserHandler) {
            AskUserBridge.handler = null
        }
        askUserHandler = null
        viewModel.stopGeneration()
        instances.remove(project, this)
    }

    // ---- conversation header ----
    private val newSessionBtn = JLabel("+").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        foreground = JBColor(0x888888, 0x999999)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "新会话"
        border = JBUI.Borders.empty(2, 6, 2, 6)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { viewModel.clearConversation(); messageRefChips.clear(); planBar.updatePlan(null); rebuildConversation() }
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
        border = JBUI.Borders.empty(4, 4, 8, 4)
    }
    // 外套：BoxLayout.Y_AXIS 无 glue → 内容天然顶部对齐，同时拉伸子组件到满宽。
    // 横向：BoxLayout 将唯一子组件 conversationContainer 拉伸到 wrapper 宽度（=视口宽），
    //       确保内部 X_AXIS 行的水平 glue 有足够空间把气泡推到正确对侧。
    // 纵向：无 glue → BoxLayout 只用子组件的 preferred 高度，多余空间留白在底部。
    private val conversationWrapper = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(conversationContainer)
    }
    private val conversationScrollPane = JBScrollPane(
        conversationWrapper,
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
    /** @ 文件引用弹出菜单（懒初始化） */
    private var fileRefPopup: JPopupMenu? = null

    /**
     * 全局键位分发器，在 JTextArea 的 Keymap 之前拦截弹窗导航键，
     * 统一接管 UP/DOWN（列表导航）、ENTER（确认选择）、ESC（关闭弹窗）。
     */
    private val popupKeyDispatcher = KeyEventDispatcher { e ->
        if (e.id == KeyEvent.KEY_PRESSED) {
            val code = e.keyCode
            when (code) {
                KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                    val delta = if (code == KeyEvent.VK_UP) -1 else 1
                    if (slashCommandPopup?.isVisible == true) {
                        slashCommandMoveSelection?.invoke(delta)
                        return@KeyEventDispatcher true
                    }
                    if (fileRefPopup?.isVisible == true) {
                        fileRefMoveSelection?.invoke(delta)
                        return@KeyEventDispatcher true
                    }
                }
                KeyEvent.VK_ENTER -> {
                    if (e.modifiersEx == 0 && slashCommandPopup?.isVisible == true) {
                        slashCommandDoSelect?.invoke()
                        return@KeyEventDispatcher true
                    }
                    if (e.modifiersEx == 0 && fileRefPopup?.isVisible == true) {
                        fileRefDoSelect?.invoke()
                        return@KeyEventDispatcher true
                    }
                }
                KeyEvent.VK_ESCAPE -> {
                    if (slashCommandPopup?.isVisible == true) {
                        slashCommandPopup?.isVisible = false
                        return@KeyEventDispatcher true
                    }
                    if (fileRefPopup?.isVisible == true) {
                        fileRefPopup?.isVisible = false
                        return@KeyEventDispatcher true
                    }
                }
            }
        }
        false
    }

    // ---- input area ----
    private val inputArea = JTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        border = JBUI.Borders.empty(4, 4)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_UP || e.keyCode == KeyEvent.VK_DOWN) {
                    if (slashCommandPopup?.isVisible == true) { e.consume(); slashCommandMoveSelection?.invoke(if (e.keyCode == KeyEvent.VK_UP) -1 else 1); return }
                    if (fileRefPopup?.isVisible == true) { e.consume(); fileRefMoveSelection?.invoke(if (e.keyCode == KeyEvent.VK_UP) -1 else 1); return }
                }
                if (e.keyCode == KeyEvent.VK_ENTER && e.modifiersEx == 0) {
                    e.consume()
                    if (slashCommandPopup?.isVisible == true) { slashCommandDoSelect?.invoke(); return }
                    if (fileRefPopup?.isVisible == true) { fileRefDoSelect?.invoke(); return }
                    sendMessage()
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
    /** 文件引用芯片：仅存路径和行号，发送给 LLM 时才读取文件内容。快捷跳转使用 fullPath + startLine。 */
    data class RefChip(val label: String, val fullPath: String, val startLine: Int = 0, val endLine: Int = 0) {
        val displayName: String get() = when {
            startLine > 0 && endLine > 0 && startLine != endLine -> "$label $startLine-$endLine"
            startLine > 0 -> "$label $startLine"
            else -> label
        }
    }
    private val refChips = mutableListOf<RefChip>()
    /** 待发送的粘贴图片（Claude 原生 image 块，非 Markdown data URL） */
    private val pastedImages = mutableListOf<ImageData>()
    private val chipPanel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
    }

    private fun rebuildChips() {
        chipPanel.removeAll()
        // 图片芯片
        for ((idx, img) in pastedImages.withIndex()) {
            val chipComp = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                background = JBColor(0xE3E8EE, 0x3A3E48)
                border = BorderFactory.createCompoundBorder(
                    roundedBorder(JBColor(0xC0C8D0, 0x505560)),
                    BorderFactory.createEmptyBorder(1, 6, 1, 4)
                )
                toolTipText = "已粘贴图片 (${img.mediaType})"
            }
            chipComp.add(JLabel("图片 ${idx + 1}").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                foreground = JBColor(0x333333, 0xCCCCCC)
            })
            val removeBtn = JLabel("×").apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                foreground = JBColor(0x888888, 0xAAAAAA)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = BorderFactory.createEmptyBorder(0, 2, 0, 2)
                toolTipText = "移除图片"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        pastedImages.removeAt(idx)
                        rebuildChips()
                    }
                })
            }
            chipComp.add(removeBtn)
            chipPanel.add(chipComp)
        }
        // 文件引用芯片
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
                        if (chip === selectionRefChip) selectionRefChip = null
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

    fun addRefChip(label: String, fullPath: String, startLine: Int = 0, endLine: Int = 0) {
        // 去重：完全相同的文件+行号范围才跳过
        if (refChips.any { it.fullPath == fullPath && it.startLine == startLine && it.endLine == endLine }) return
        refChips.add(RefChip(label, fullPath, startLine, endLine))
        rebuildChips()
    }

    /** 仅告知模型引用了哪些文件，让模型按需调用 read_file 读取，避免请求体膨胀 */
    private fun buildRefContent(): String {
        if (refChips.isEmpty()) return ""
        return buildString {
            append("用户引用了以下文件：\n")
            for (chip in refChips) {
                val lineInfo = when {
                    chip.startLine > 0 && chip.endLine > 0 && chip.startLine != chip.endLine ->
                        " 第 ${chip.startLine}-${chip.endLine} 行"
                    chip.startLine > 0 ->
                        " 第 ${chip.startLine} 行"
                    else -> ""
                }
                append("- `${chip.fullPath}`$lineInfo\n")
            }
            append("\n如需查看文件内容，请使用 read_file 工具。")
        }
    }

    /** 创建引用文件 chips 面板（嵌入用户气泡底部，无外层 glue——气泡已处理右对齐） */
    private fun buildRefChipFooter(refs: List<RefChip>): JPanel {
        val viewportWidth = conversationScrollPane.viewport.width
        val maxWidth = (viewportWidth * ChatTheme.USER_FRACTION).toInt()
        val chipRow = JPanel(WrapLayout(FlowLayout.RIGHT, 2, 2)).apply {
            isOpaque = false
            maximumSize = Dimension(maxWidth, Int.MAX_VALUE)
        }
        for (ref in refs) {
            val fileName = ref.fullPath.substringAfterLast('/')
            val lineSuffix = if (ref.startLine > 0) ":$ref.startLine" else ""
            val chipText = "📄 $fileName$lineSuffix"
            val chip = JLabel(chipText).apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.textSecondary
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(0xC0C8D0, 0x505560), 1),
                    JBUI.Borders.empty(2, 8, 2, 8)
                )
                isOpaque = true
                background = JBColor(0xE3E8EE, 0x3A3E48)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                toolTipText = "点击打开: ${ref.fullPath}${if (ref.startLine > 0) " (第${ref.startLine}行)" else ""}"
            }
            val labelRef = chip
            val file = File(project.basePath ?: "", ref.fullPath)
            chip.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (file.exists()) {
                        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file)
                        if (vf != null) {
                            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
                            if (ref.startLine > 0) {
                                ApplicationManager.getApplication().invokeLater {
                                    val textEditor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                                        .selectedTextEditor
                                    if (textEditor != null) {
                                        val doc = textEditor.document
                                        val startOffset = doc.getLineStartOffset((ref.startLine - 1).coerceAtLeast(0))
                                        val endOffset = if (ref.endLine > 0 && ref.endLine != ref.startLine) {
                                            doc.getLineEndOffset((ref.endLine - 1).coerceAtMost(doc.lineCount - 1))
                                        } else {
                                            startOffset
                                        }
                                        textEditor.selectionModel.setSelection(startOffset, endOffset)
                                        textEditor.caretModel.moveToOffset(startOffset)
                                        textEditor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                                    }
                                }
                            }
                        }
                    }
                }
                override fun mouseEntered(e: MouseEvent) {
                    labelRef.foreground = JBColor(0x2674B4, 0x5A9FD4)
                    labelRef.background = JBColor(0xD0D8E8, 0x4A4E58)
                }
                override fun mouseExited(e: MouseEvent) {
                    labelRef.foreground = ChatTheme.textSecondary
                    labelRef.background = JBColor(0xE3E8EE, 0x3A3E48)
                }
            })
            chipRow.add(chip)
        }
        return chipRow
    }

    // ---- plus button（点击后在输入框插入 @，复用 @ 文件引用弹窗及筛选机制）----
    private val plusButton = JLabel("+").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        foreground = JBColor(0x888888, 0x999999)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        toolTipText = "添加文件引用"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 在光标位置插入 @，触发文件引用弹窗
                val pos = inputArea.caretPosition
                inputArea.insert("@", pos)
                inputArea.caretPosition = pos + 1
                inputArea.requestFocus()
            }
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

    private val inputPanel = object : JPanel(BorderLayout(0, 0)) {
        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()  // 由 BorderLayout + 子组件实际高度计算
            return Dimension(maxOf(natural.width, 200), maxOf(natural.height, 80))
        }
    }.apply {
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
    private var streamingThinkingRow: JPanel? = null
    private var streamingThinkingTextArea: JTextArea? = null
    // 自动引用去重
    private var lastAutoInsertedHash: Int = 0
    private var lastAutoInsertTime: Long = 0
    /** 通过 SelectionListener 自动添加的文件引用（最多一个，选区变化时更新而非新增） */
    private var selectionRefChip: RefChip? = null
    /** EditorFactoryListener 的父 Disposable，dispose 时自动移除监听器 */
    private val editorListenerDisposable = com.intellij.openapi.Disposable { }
    /** 已注册 SelectionListener 的编辑器集合，用于 dispose 时清理 */
    private val trackedEditors = mutableSetOf<com.intellij.openapi.editor.Editor>()
    private val editorListeners = mutableMapOf<com.intellij.openapi.editor.Editor, com.intellij.openapi.editor.event.SelectionListener>()

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
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(popupKeyDispatcher)
        // v3: 注册内置工具+Skills（同步安全），首条消息即可调用
        viewModel.initialize(project)

        // M5-A: 注册 ask_user 选择卡 handler（EDT 上执行；multiple=true 时多选模式）。
        // 保存引用，dispose() 时仅在仍是自己注册的 handler 时清空，避免泄露到失效 UI。
        val myAskUserHandler: (String, List<String>, Boolean, CountDownLatch, AtomicReference<String>) -> Unit =
            { question, options, multiple, latch, result ->
                showSelectionCard(question, options, multiple, latch, result)
            }
        askUserHandler = myAskUserHandler
        AskUserBridge.handler = myAskUserHandler

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
        addRefreshListener(checkEmptyListener)
        ApplicationManager.getApplication().invokeLater { checkEmptyState() }

        // viewport 宽度变化时重算气泡：仅在宽度真正改变时触发。这样首次 viewport
        // 未就绪导致的错误宽度会在就绪后被修正（防止气泡超出窗口被裁），
        // 拖窗口也能正确换行。hug content 测量已正确，重算不会让短气泡乱变。
        conversationScrollPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = conversationScrollPane.viewport.width
                if (w > 10 && w != lastViewportWidth) {
                    lastViewportWidth = w
                    ApplicationManager.getApplication().invokeLater { refitAllBubbles() }
                }
            }
        })

        // M5-B: 输入框 / 与 @ 补全菜单
        setupInputCompletions()

        // 注册编辑器文本选区监听——捕获用户在编辑器内选中文本的动作
        com.intellij.openapi.editor.EditorFactory.getInstance().addEditorFactoryListener(
            object : com.intellij.openapi.editor.event.EditorFactoryListener {
                override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                    val editor = event.editor
                    trackedEditors.add(editor)
                    val sl = object : com.intellij.openapi.editor.event.SelectionListener {
                        override fun selectionChanged(e: com.intellij.openapi.editor.event.SelectionEvent) {
                            if (e.newRange != null && e.newRange!!.length > 0) {
                                if (System.currentTimeMillis() - lastAutoInsertTime < 100) return
                                ApplicationManager.getApplication().invokeLater {
                                    autoInsertSelectedCode()
                                }
                            } else {
                                // 取消选中 → 移除选区芯片
                                ApplicationManager.getApplication().invokeLater {
                                    removeSelectionChip()
                                }
                            }
                        }
                    }
                    editor.selectionModel.addSelectionListener(sl)
                    editorListeners[editor] = sl
                }
                override fun editorReleased(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                    editorListeners.remove(event.editor)?.let {
                        event.editor.selectionModel.removeSelectionListener(it)
                    }
                    trackedEditors.remove(event.editor)
                }
            },
            editorListenerDisposable
        )
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
        // ChatViewModel 回调已在 EDT 执行，不包 invokeLater 避免事件重排队导致重复渲染
        viewModel.onMessagesChanged = {
            rebuildConversation()
            checkEmptyState()
        }
        viewModel.onStreamingUpdate = { updateStreamingBubble() }
        viewModel.onStreamingThinkingChanged = { updateStreamingThinking() }
        viewModel.onStreamingStateChanged = { streaming ->
            ApplicationManager.getApplication().invokeLater {
                inputArea.isEnabled = !streaming
                lingmaSubmitBtn.text = if (streaming) "■" else "→"
                lingmaSubmitBtn.toolTipText = if (streaming) "停止" else "发送 (Enter)"
                if (!streaming) {
                    lingmaSubmitBtn.foreground = JBColor(0x888888, 0xAAAAAA)
                    // 流式结束，触发一次布局失效；自测量气泡会按最终内容/宽度重测。
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
                rebuildConversation()  // 工具块内已自带审批按钮，只需刷新
            }
        }
        viewModel.onPlanUpdate = { plan -> planBar.updatePlan(plan) }
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

    private fun rebuildConversation() {
        val scLen = viewModel.streamingContent.length
        val stLen = viewModel.streamingThinking.length
        val msgRoles = viewModel.messages.filter { it.role != "system" }.joinToString(",") { "${it.role}(${it.content.length})" }
        AppLogger.info("rebuildConversation scLen=$scLen stLen=$stLen msgs=[$msgRoles] streamingB=$streamingBubble streamingT=$streamingThinkingRow")
        conversationContainer.removeAll()
        bubbleSizeConstraints.clear()
        // 过滤掉 system 消息
        val displayMessages = viewModel.messages.filter { it.role != "system" }
        val hasMessages = displayMessages.isNotEmpty() || viewModel.streamingContent.isNotEmpty() || viewModel.streamingThinking.isNotEmpty()
        if (hasMessages) {
            for (msg in displayMessages) {
                // 用户消息带引用 chips 时，chips 嵌入气泡底部（与消息文本同属一个气泡）
                if (msg.role == "user") {
                    val refs = messageRefChips[msg.id]
                    if (refs != null && refs.isNotEmpty()) {
                        conversationContainer.add(createUserBubbleWithFooter(msg, buildRefChipFooter(refs)))
                        continue
                    }
                }
                conversationContainer.add(createMessageBubble(msg))
            }

            // 记录当前 bubbleSizeConstraints 条目数，用于后面捕获流式 assistant 气泡引用
            val bubbleCountBefore = bubbleSizeConstraints.size

            // 流式思考行：重建并跟踪引用，供 updateStreamingThinking() 原地更新
            if (viewModel.streamingThinking.isNotEmpty()) {
                val textAreaRef = java.util.concurrent.atomic.AtomicReference<JTextArea>()
                val row = toolRowFactory.thinkingRow(viewModel.streamingThinking, initiallyExpanded = true, streaming = true, textAreaRef = textAreaRef)
                streamingThinkingRow = row
                streamingThinkingTextArea = textAreaRef.get()
                conversationContainer.add(row)
            }

            // 流式 AI 回复气泡：重建并捕获 ChatBubble 引用，供 updateStreamingBubble() 原地更新
            // createMessageBubble("assistant") → createAssistantBubble() 会自动把 (bubble, content)
            // 追加到 bubbleSizeConstraints，我们从末尾取出即可
            if (viewModel.streamingContent.isNotEmpty()) {
                conversationContainer.add(
                    createMessageBubble(AgentMessage("assistant", viewModel.streamingContent))
                )
                if (bubbleSizeConstraints.size > bubbleCountBefore) {
                    val (b, c) = bubbleSizeConstraints.last()
                    streamingBubble = b
                    streamingContentPane = c
                }
            }

            // 状态指示器：执行中/思考中 已内化到对应块中（工具块"执行中..."、思考行"思考中..."）
            // 不再需要独立指示器组件
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
        conversationContainer.revalidate()
        conversationContainer.repaint()
        scrollToBottom(force = true)
    }

    private val markdownRenderer = MarkdownRenderer()
    private val bubbleFactory = BubbleFactory(conversationScrollPane)
    private val toolRowFactory = ToolRowFactory { conversationScrollPane.viewport.width }

    /** 流式更新时原地替换 JTextPane 文本，避免 remove/add 触发布局震荡 */
    private fun updateStreamingBubble() {
        // 防护：双重 invokeLater 可能导致本方法在 completion 之后才执行（此时 streamingContent
        // 已被清空、isStreaming 已置 false），若不加守卫会创建一个空白 AI 气泡形成重复渲染。
        if (!viewModel.isStreaming || viewModel.streamingContent.isEmpty()) return
        // 如果之前持有的 bubble 已被 rebuild 移除（parent 为 null），重置引用走创建路径
        if (streamingBubble != null && streamingBubble!!.parent == null) {
            streamingBubble = null; streamingContentPane = null
        }
        if (streamingBubble == null) {
            // 首次流式：添加流式 AI 气泡。
            // 如果已有 streaming thinking row 在容器中，assistant 气泡必须插入到它之后，
            // 确保思考→回复的自然视觉顺序。
            val bubble = createAssistantBubble(AgentMessage("assistant", viewModel.streamingContent))
            val thinkIdx = streamingThinkingRow?.let { row ->
                conversationContainer.components.indexOfFirst { it === row }.takeIf { it >= 0 }
            }
            if (thinkIdx != null) {
                conversationContainer.add(bubble, thinkIdx + 1)
            } else {
                conversationContainer.add(bubble)
            }
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
                    // 自测量气泡：失效后按新内容自动重测尺寸，无需手动 fitWidth。
                    streamingBubble!!.revalidate()
                }
                streamingBubble!!.repaint()
            }
            // 只在用户已在底部时才跟随
            scrollToBottom(force = false)
        }
    }

    /** 流式思考原地更新：类似 updateStreamingBubble，避免每个 token 触发 removeAll 重建 */
    private fun updateStreamingThinking() {
        // 同 updateStreamingBubble：防护双重 invokeLater 导致的 completion 后执行
        if (!viewModel.isStreaming) return
        val content = viewModel.streamingThinking
        // 如果之前持有的组件已被 rebuild 移除（parent 为 null），重置引用让创建路径重新走一遍
        if (streamingThinkingRow != null && streamingThinkingRow!!.parent == null) {
            streamingThinkingRow = null
            streamingThinkingTextArea = null
        }
        if (content.isEmpty()) return
        if (streamingThinkingRow == null) {
            // 首次：创建思考行（已展开）。
            // 如果 assistant 流式气泡已在容器中，思考行插入到它之前（思考→回复 的自然顺序）
            val textAreaRef = java.util.concurrent.atomic.AtomicReference<JTextArea>()
            val row = toolRowFactory.thinkingRow(content, initiallyExpanded = true, streaming = true, textAreaRef = textAreaRef)
            streamingThinkingRow = row
            streamingThinkingTextArea = textAreaRef.get()
            val assistantIdx = streamingBubble?.let { b ->
                conversationContainer.components.indexOfFirst { it === b }.takeIf { it >= 0 }
            }
            if (assistantIdx != null) {
                conversationContainer.add(row, assistantIdx)
            } else {
                conversationContainer.add(row)
            }
            conversationContainer.revalidate()
            scrollToBottom(force = true)
        } else {
            // 后续：原地更新 JTextArea 文本
            val area = streamingThinkingTextArea
            if (area != null) {
                area.text = content
                area.caretPosition = content.length  // 流式滚动到底部
                streamingThinkingRow!!.revalidate()
                streamingThinkingRow!!.repaint()
            }
            scrollToBottom(force = false)
        }
    }

    private fun createMessageBubble(message: AgentMessage): JPanel {
        return when (message.role) {
            "thinking" -> createCollapsibleThinkingBubble(message.content)
            "tool" -> createToolResultBubble(message)    // 工具调用+结果，可折叠
            "user" -> createUserBubble(message)
            "assistant" -> {
                if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
                    // 文本用 ChatBubble，工具调用仅展示不重复创建工具块
                    // 真正的执行+结果由 onToolExecute/onToolResult → "tool" 消息处理
                    createAssistantBubble(message)
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

    /** 带引用文件 chips 的用户气泡：chips 嵌入气泡底部，与消息文本同属一个气泡 */
    private fun createUserBubbleWithFooter(message: AgentMessage, footer: JComponent): JPanel {
        val (row, bubble, content) = bubbleFactory.userBubbleWithFooter(message, footer)
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

    /** 工具结果行 — 委托给 ToolRowFactory（默认折叠），待审批时附加审批按钮 */
    private fun createToolResultBubble(message: AgentMessage): JPanel {
        val name = message.toolName ?: "tool"
        // 仅当审批尚未完成时才显示按钮（latch 未被 countDown）
        val state = if (message.approvalPending) viewModel.pendingApprovals[name] else null
        val approvals: ApprovalActions? = if (state != null && state.latch.getCount() > 0) {
            ApprovalActions(
                onAllowOnce = { state.userChoice.set(true); state.latch.countDown() },
                onAlwaysAllow = {
                    AppSettingsService.getInstance().addToolToWhitelist(name)
                    state.userChoice.set(true); state.latch.countDown()
                },
                onReject = { state.userChoice.set(false); state.latch.countDown() }
            )
        } else null
        return toolRowFactory.toolResultRow(message, approvals)
    }

    /** 思考中轻量指示器 — textMuted 斜体，FlowLayout 自然左对齐无需 glue */
    private fun createThinkingBubble(text: String): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
        }
        val label = JLabel(text).apply {
            font = ChatTheme.metaFont.deriveFont(java.awt.Font.ITALIC)
            foreground = ChatTheme.textMuted
            border = JBUI.Borders.empty(2, 4)
        }
        panel.add(label)
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
            override fun removeUpdate(e: DocumentEvent) { checkFilter() }
            override fun changedUpdate(e: DocumentEvent) = Unit

            private fun checkFilter() {
                if (inputListenerGuard.get()) return
                val text = try { inputArea.text } catch (_: Exception) { return }
                slashCommandFilter?.invoke(if (text.startsWith("/")) text else "")
                // @ 文件筛选：找到最后一个 @ 位置（行首或空白后），提取筛选文本
                val atIdx = text.indexOfLast { it == '@' }.takeIf { it >= 0 } ?: -1
                if (atIdx >= 0 && (atIdx == 0 || text.getOrElse(atIdx - 1) { ' ' }.isWhitespace())) {
                    fileRefFilter?.invoke(text.substring(atIdx))
                } else {
                    fileRefFilter?.invoke("")
                }
            }

            private fun checkTrigger(e: DocumentEvent) {
                // 重入保护：若当前正在 invokeLater 内修改文档，直接跳过
                if (inputListenerGuard.get()) return

                val text = try { inputArea.text } catch (_: Exception) { return }

                // 斜杠命令：以 "/" 开头时实时筛选弹窗
                if (text.startsWith("/")) {
                    if (text.trim() == "/" && e.length == 1) SwingUtilities.invokeLater { showSlashCommandMenu() }
                    else slashCommandFilter?.invoke(text)
                    return
                }

                // @ 筛选：文本中含 @ 时持续调用 fileRefFilter 做实时过滤（不仅首次触发）
                val atIdx = text.indexOfLast { it == '@' }.takeIf { it >= 0 } ?: -1
                if (atIdx >= 0 && (atIdx == 0 || text.getOrElse(atIdx - 1) { ' ' }.isWhitespace())) {
                    fileRefFilter?.invoke(text.substring(atIdx))
                }

                // 只关心单字符插入（避免粘贴大段文本时误触）
                if (e.length != 1) return

                val insertPos = e.offset
                val insertedChar = try { e.document.getText(insertPos, 1) } catch (_: Exception) { return }

                when (insertedChar) {
                    "@" -> {
                        val beforeAt = if (insertPos > 0) {
                            try { e.document.getText(insertPos - 1, 1) } catch (_: Exception) { " " }
                        } else " "
                        if (beforeAt.isBlank()) {
                            SwingUtilities.invokeLater { showFileRefPopup() }
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
    private var slashCommandFilter: ((String) -> Unit)? = null
    private var slashCommandMoveSelection: ((Int) -> Unit)? = null
    private var slashCommandDoSelect: (() -> Unit)? = null

    private var fileRefFilter: ((String) -> Unit)? = null
    private var fileRefMoveSelection: ((Int) -> Unit)? = null
    private var fileRefDoSelect: (() -> Unit)? = null

    private fun showSlashCommandMenu() {
        val popup = JPopupMenu()

        data class Cmd(val name: String, val desc: String, val action: () -> Unit)
        fun sendQuick(msg: String) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val apiKey = try { AppSettingsService.getInstance().getApiKey() } catch (_: Exception) { null }
                ApplicationManager.getApplication().invokeLater {
                    if (apiKey.isNullOrBlank()) { showError(AiAssistantBundle.message("chat.error.nokey")); return@invokeLater }
                    hideError()
                    viewModel.sendMessage(apiKey, msg)
                }
            }
        }

        val commands = listOf(
            Cmd("/new",   "新会话") { viewModel.clearConversation(); messageRefChips.clear(); planBar.updatePlan(null); rebuildConversation() },
            Cmd("/plan",  "创建执行计划") { sendQuick("请为当前任务创建执行计划。先分析需求，然后调用 create_plan 工具。") },
            Cmd("/init",  "初始化项目文档") { sendQuick("请分析当前项目结构，创建 CLAUDE.md 文档，包含项目概述、常用命令、架构说明和关键约定。") },
            Cmd("/review","审查当前改动") { sendQuick("请审查当前分支的代码改动，分析潜在问题并给出修复建议。") },
            Cmd("/test",  "运行测试") { sendQuick("请运行 ./gradlew test，分析测试结果并修复失败的测试。") },
            Cmd("/stop",  "停止生成") { viewModel.stopGeneration() },
            Cmd("/clear", "清空输入") { /* 已在 executeCommand 中清空 */ }
        )

        fun executeCommand(cmd: Cmd) {
            popup.isVisible = false
            inputListenerGuard.set(true)
            try { inputArea.text = "" } finally { inputListenerGuard.set(false) }
            cmd.action()
        }

        val skills = viewModel.getSkillNames().sorted()
        val allEntries: List<Pair<String, String>> = commands.map { it.name to it.desc } + skills.map { "/$it" to "skill" }
        var selectedIndex = 0

        fun closePopup() { popup.isVisible = false; slashCommandFilter = null; slashCommandMoveSelection = null; slashCommandDoSelect = null }
        fun doSelect() {
            val items = popup.components.filterIsInstance<JMenuItem>()
            if (selectedIndex in items.indices) items[selectedIndex].doClick()
        }

        fun rebuildItems(filter: String) {
            val keep = popup.components.takeWhile { it !is JMenuItem }.size
            for (i in popup.componentCount - 1 downTo keep) popup.remove(i)
            val q = filter.removePrefix("/").lowercase()
            val filtered = allEntries.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }.take(10)
            selectedIndex = 0
            for ((idx, entry) in filtered.withIndex()) {
                val (name, desc) = entry
                val cmd = commands.find { it.name == name }
                val item = JMenuItem("$name  $desc").apply {
                    font = ChatTheme.metaFont; border = JBUI.Borders.empty(4, 10, 4, 10)
                    preferredSize = Dimension(inputPanel.width, 28)
                    minimumSize = Dimension(inputPanel.width, 28)
                    maximumSize = Dimension(inputPanel.width, 28)
                    if (idx == 0) background = JBColor(0xE0E8F0, 0x3A4048)
                }
                if (cmd != null) item.addActionListener { executeCommand(cmd) }
                else item.addActionListener {
                    closePopup()
                    inputListenerGuard.set(true)
                    try { inputArea.text = "" } finally { inputListenerGuard.set(false) }
                    sendQuick("请使用 ${name.removePrefix("/")} skill 执行任务")
                }
                popup.add(item)
            }
        }
        rebuildItems(inputArea.text)

        fun moveSelection(delta: Int) {
            val items = popup.components.filterIsInstance<JMenuItem>()
            if (items.isEmpty()) return
            selectedIndex = (selectedIndex + delta).coerceIn(0, items.lastIndex)
            items.forEachIndexed { i, it -> it.background = if (i == selectedIndex) JBColor(0xE0E8F0, 0x3A4048) else null }
        }

        // 文本框内容变化时实时筛选（先隐后显，确保弹窗高度随内容自适应、始终紧贴输入框）
        slashCommandFilter = { text ->
            if (text.startsWith("/")) {
                rebuildItems(text)
                val h = (popup.components.filterIsInstance<JMenuItem>().size.coerceAtLeast(1) * 28).coerceAtMost(280)
                popup.isVisible = false
                popup.preferredSize = Dimension(inputPanel.width, h)
                if (inputPanel.isShowing) popup.show(inputPanel, 0, -h)
            } else {
                closePopup()
            }
        }

        slashCommandMoveSelection = { delta -> moveSelection(delta) }
        slashCommandDoSelect = { doSelect() }

        val popupHeight = (popup.components.filterIsInstance<JMenuItem>().size.coerceAtLeast(1) * 28).coerceAtMost(280)
        popup.isFocusable = false
        popup.pack()
        popup.preferredSize = Dimension(inputPanel.width, popupHeight)
        if (inputPanel.isShowing) popup.show(inputPanel, 0, -popupHeight)
        slashCommandPopup = popup
    }

    /**
     * 显示 @ 文件引用弹出菜单。
     *
     * 支持文件和目录两种引用：
     * - 文件：读取内容作为代码引用
     * - 目录（以 / 结尾）：生成目录列表，不读取文件内容
     */
    private fun showFileRefPopup() {
        if (projectFilesCache.isEmpty()) projectFilesCache = collectProjectFiles()
        val popup = JPopupMenu()
        var selectedIndex = 0

        fun closePopup() { popup.isVisible = false; fileRefFilter = null; fileRefMoveSelection = null; fileRefDoSelect = null }

        fun doSelect() {
            val items = popup.components.filterIsInstance<JMenuItem>()
            if (selectedIndex !in items.indices) return
            val rawPath = items[selectedIndex].text.removeSuffix("/")
            val isDir = items[selectedIndex].text.endsWith("/")
            closePopup()
            // 删除输入框中的 @text
            inputListenerGuard.set(true)
            try {
                val text = inputArea.text
                val atIdx = text.indexOfLast { it == '@' }
                if (atIdx >= 0) {
                    val endIdx = (atIdx + 1..text.length).firstOrNull { idx ->
                        idx >= text.length || text[idx].isWhitespace()
                    } ?: text.length
                    inputArea.document.remove(atIdx, endIdx - atIdx)
                }
            } finally { inputListenerGuard.set(false) }
            // 添加引用芯片
            val basePath = project.basePath ?: return
            if (isDir) {
                // 目录：仅存路径，发送时再生成文件列表
                if (File(basePath, rawPath).isDirectory) {
                    addRefChip("$rawPath/", rawPath)
                }
            } else {
                // 文件：仅存路径和行号（无选区），发送时再读取内容
                val file = File(basePath, rawPath)
                if (file.isFile && file.length() < 500_000) {
                    addRefChip(rawPath, rawPath)
                }
            }
        }

        fun rebuildItems(filter: String) {
            val keep = popup.components.takeWhile { it !is JMenuItem }.size
            for (i in popup.componentCount - 1 downTo keep) popup.remove(i)
            val q = filter.removePrefix("@").lowercase()
            // 文件和目录混合：目录加上 / 后缀
            val fileMatches = projectFilesCache.filter { it.lowercase().contains(q) }
            // 从文件路径中提取匹配的目录
            val dirMatches = projectFilesCache
                .flatMap { f ->
                    val parts = f.split('/')
                    (1..parts.size).map { parts.take(it).joinToString("/") + "/" }
                }
                .distinct()
                .filter { it.lowercase().contains(q) && !projectFilesCache.contains(it.removeSuffix("/")) }
            val allMatches = (dirMatches + fileMatches).take(50)
            selectedIndex = 0
            for ((idx, entry) in allMatches.withIndex()) {
                val item = JMenuItem(entry).apply {
                    font = ChatTheme.metaFont; border = JBUI.Borders.empty(4, 10, 4, 10)
                    preferredSize = Dimension(inputPanel.width, 28)
                    minimumSize = Dimension(inputPanel.width, 28)
                    maximumSize = Dimension(inputPanel.width, 28)
                    if (idx == 0) background = JBColor(0xE0E8F0, 0x3A4048)
                }
                item.addActionListener { doSelect() }
                popup.add(item)
            }
        }
        rebuildItems(inputArea.text.substringAfterLast('@', ""))

        fun moveSelection(delta: Int) {
            val items = popup.components.filterIsInstance<JMenuItem>()
            if (items.isEmpty()) return
            selectedIndex = (selectedIndex + delta).coerceIn(0, items.lastIndex)
            items.forEachIndexed { i, it -> it.background = if (i == selectedIndex) JBColor(0xE0E8F0, 0x3A4048) else null }
        }

        fileRefFilter = { text ->
            if (text.startsWith("@")) {
                rebuildItems(text)
                val h = (popup.components.filterIsInstance<JMenuItem>().size.coerceAtLeast(1) * 28).coerceAtMost(280)
                popup.isVisible = false
                popup.preferredSize = Dimension(inputPanel.width, h)
                if (inputPanel.isShowing) popup.show(inputPanel, 0, -h)
            } else {
                closePopup()
            }
        }
        fileRefMoveSelection = { delta -> moveSelection(delta) }
        fileRefDoSelect = { doSelect() }

        val popupHeight = (popup.components.filterIsInstance<JMenuItem>().size.coerceAtLeast(1) * 28).coerceAtMost(280)
        popup.isFocusable = false
        popup.pack()
        popup.preferredSize = Dimension(inputPanel.width, popupHeight)
        if (inputPanel.isShowing) popup.show(inputPanel, 0, -popupHeight)
        fileRefPopup = popup
    }

    /** 用户消息对应的引用文件（用于气泡下方显示可点击跳转的 chips），消息 ID → RefChip 列表 */
    private val messageRefChips = mutableMapOf<Long, List<RefChip>>()

    private fun sendMessage() {
        val textContent = inputArea.text.trim()
        val refContent = buildRefContent()
        val images = pastedImages.toList()  // 快照，避免异步清空后丢失
        if (textContent.isEmpty() && refContent.isEmpty() && images.isEmpty()) {
            showWarning(AiAssistantBundle.message("chat.error.empty"))
            return
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
                // 记录引用文件用于气泡下方可点击跳转的 chips 展示
                val msgId = viewModel.sendMessage(apiKey, textContent, images.ifEmpty { null }, refContent)
                if (msgId > 0 && refChips.isNotEmpty()) {
                    messageRefChips[msgId] = refChips.toList() // 快照
                }
                inputArea.text = ""
                refChips.clear()
                selectionRefChip = null
                pastedImages.clear()
                rebuildChips()
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
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val relativePath = if (file != null) {
            val basePath = project.basePath
            if (basePath != null && file.path.startsWith(basePath)) {
                file.path.removePrefix(basePath).removePrefix("/")
            } else file.name
        } else "unknown"
        // 纯文本换行计数计算行号，不依赖 Editor/Document 内部索引
        val docText = editor.document.immutableCharSequence
        val selStart = editor.selectionModel.selectionStart
        val selEnd = editor.selectionModel.selectionEnd
        val startLine = docText.subSequence(0, selStart).count { it == '\n' } + 1
        val endLine = docText.subSequence(0, selEnd).count { it == '\n' } + 1
        // 仅存储文件路径和行号，发送时再读取文件内容
        selectionRefChip?.let { refChips.remove(it) }
        val chip = RefChip(relativePath, relativePath, startLine, endLine)
        selectionRefChip = chip
        refChips.add(chip)
        rebuildChips()
        activateToolWindow()
    }

    private fun insertFileReference() {
        showFileRefPopup()
    }

    /** 取消选中时移除选区芯片 */
    private fun removeSelectionChip() {
        selectionRefChip?.let { refChips.remove(it) }
        selectionRefChip = null
        rebuildChips()
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

            // 存储为 Claude 原生 image 块（而非 Markdown data URL）
            pastedImages.add(ImageData("image/png", base64))
            rebuildChips()
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
        // 双重 invokeLater：确保 revalidate() 触发的 layout 在 EDT 上完成后再读 bar.maximum，
        // 否则滚动基于旧布局高度，导致末尾内容被遮挡。
        SwingUtilities.invokeLater {
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
}
