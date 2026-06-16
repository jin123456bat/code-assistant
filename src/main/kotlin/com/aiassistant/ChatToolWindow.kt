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

    /** MCP 管理器，dispose 时断开所有 MCP 连接，防止进程泄漏。 */
    private val mcpManager: McpManager = McpManager(project)

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
        mcpManager.disconnectAll()
        instances.remove(project, this)
    }

    // ---- conversation header ----
    private val newSessionBtn = JLabel("+").apply {
        font = ChatTheme.largeFont.deriveFont(18f)
        foreground = ChatTheme.codeLangFg
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "新会话"
        border = JBUI.Borders.empty(2, 6, 2, 6)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { needFullRebuild = true; viewModel.clearConversation(); viewModel.messageRefChips.clear(); refChips.clear(); selectionRefChip = null; rebuildChips(); planBar.updatePlan(null); rebuildConversation() }
            override fun mouseEntered(e: MouseEvent) { foreground = ChatTheme.accentHover }
            override fun mouseExited(e: MouseEvent) { foreground = ChatTheme.codeLangFg }
        })
    }
    private val conversationHeader = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 4, 8)
        add(JLabel("对话").apply {
            font = ChatTheme.headerFont
            foreground = ChatTheme.headerTitleFg
        }, BorderLayout.WEST)
        add(newSessionBtn, BorderLayout.EAST)
    }

    // ---- conversation area ----
    // conversationContainer 直接作为 JBScrollPane 视图。
    // JBScrollPane(HORIZONTAL_SCROLLBAR_NEVER) 强制视图宽度 = 视口宽，
    // 确保内部 X_AXIS 行的水平 glue 有足够空间把气泡推到正确对侧。
    private val conversationContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = ChatTheme.winBg
        border = JBUI.Borders.empty(4, 4, 8, 4)
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
    /** @ 文件引用弹出菜单（懒初始化） */
    private var fileRefPopup: JPopupMenu? = null

    /**
     * 全局键位分发器，在 JTextArea 的 Keymap 之前拦截弹窗导航键，
     * 统一接管 UP/DOWN（列表导航）、ENTER（确认选择）、ESC（关闭弹窗）。
     */
    private val popupKeyDispatcher = KeyEventDispatcher { e ->
        // 仅在聊天窗口聚焦时拦截键盘事件，避免干扰 IDE 编辑器中的快捷键（如 Ctrl+C）
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focusOwner == null || !SwingUtilities.isDescendingFrom(focusOwner, panel)) {
            return@KeyEventDispatcher false
        }
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
    private val inputArea = object : JTextArea(3, 20) {
        private val placeholder = "问点什么，或粘贴代码…"
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            if (text.isEmpty() && !isFocusOwner) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = ChatTheme.textMuted
                g2.font = font
                val fm = g2.fontMetrics
                g2.drawString(placeholder, insets.left + 1, fm.ascent + insets.top + 1)
                g2.dispose()
            }
        }
    }.apply {
        lineWrap = true
        wrapStyleWord = true
        font = ChatTheme.bodyFont
        background = ChatTheme.inputBg
        foreground = ChatTheme.textPrimary
        caretColor = ChatTheme.textPrimary
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
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { repaint(); hideError() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { repaint(); hideError() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { repaint(); hideError() }
        })
    }

    /** 收集项目文件列表 */
    /** 使文件缓存失效，下次 @ 引用搜索时重新扫描 */
    private fun invalidateFileCache() {
        projectFilesCache = emptyList()
    }

    /** 收集项目内所有文件（排除 VCS 忽略和大型生成目录）。用于 @ 文件引用搜索。 */
    private fun collectProjectFiles(): List<String> {
        val basePath = project.basePath ?: return emptyList()
        val files = mutableListOf<String>()
        val dir = File(basePath)
        if (!dir.exists()) return files
        // 排除目录：版本控制、IDE、构建产物、依赖、缓存
        val ignoreDirs = setOf(
            ".git", ".idea", ".gradle", "build", "node_modules",
            ".code-assistant", "vendor", "target", "out", "dist",
            "__pycache__", ".venv", "venv", ".tox", ".mypy_cache",
            ".pytest_cache", ".ruff_cache", "coverage", ".next",
            ".nuxt", ".output", "public/build"
        )
        // 排除文件名模式（用于跳过自动生成的大文件）
        val ignoreNames = setOf(".DS_Store", "Thumbs.db")
        try {
            dir.walkTopDown()
                .filter { f ->
                    if (!f.isFile) return@filter true  // 继续遍历目录，不跳过
                    val relative = f.relativeTo(dir).path.replace('\\', '/')
                    val parts = relative.split('/')
                    // 跳过忽略目录内的所有文件和忽略文件名
                    parts.none { it in ignoreDirs } && f.name !in ignoreNames
                }
                .forEach { f ->
                    if (f.isFile) {
                        files.add(f.relativeTo(dir).path)
                    }
                }
        } catch (_: Exception) {
            // 权限不足等异常时返回空列表，不崩溃
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
    private val chipPanel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 6)).apply {
        isOpaque = false
        isVisible = false  // 初始无 chip，高度为 0
        border = JBUI.Borders.empty(2, 0, 4, 0)
    }

    private fun rebuildChips() {
        chipPanel.removeAll()
        // 图片芯片
        for ((idx, img) in pastedImages.withIndex()) {
            val chipComp = object : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = ChatTheme.chipBg; g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    g2.color = ChatTheme.chipBorder; g2.stroke = java.awt.BasicStroke(1f); g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    g2.dispose()
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(1, 6, 1, 4)
                toolTipText = "已粘贴图片 (${img.mediaType})"
            }
            chipComp.add(JLabel("图片 ${idx + 1}").apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.chipFg
            })
            val removeBtn = JLabel("×").apply {
                font = ChatTheme.metaFont.deriveFont(Font.BOLD)
                foreground = ChatTheme.submitBtnFg
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
            val chipComp = object : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = ChatTheme.chipBg; g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    g2.color = ChatTheme.chipBorder; g2.stroke = java.awt.BasicStroke(1f); g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    g2.dispose()
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(1, 6, 1, 4)
                toolTipText = chip.fullPath
            }
            // 只显示文件名，完整路径在 tooltip 中
            val fileName = chip.fullPath.substringAfterLast("/").ifEmpty { chip.fullPath }
            val displayText = if (chip.startLine > 0) {
                val lineInfo = if (chip.startLine == chip.endLine || chip.endLine == 0) "${chip.startLine}" else "${chip.startLine}-${chip.endLine}"
                "<html>$fileName <span style='color:#999'>$lineInfo</span></html>"
            } else {
                fileName
            }
            chipComp.add(JLabel(displayText).apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.chipFg
            })
            val removeBtn = JLabel("×").apply {
                font = ChatTheme.metaFont.deriveFont(Font.BOLD)
                foreground = ChatTheme.submitBtnFg
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
        chipPanel.isVisible = chipPanel.componentCount > 0  // 无 chip 时高度为 0
        chipPanel.revalidate()
        composerBox.revalidate()
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
        val chipRow = JPanel(WrapLayout(FlowLayout.LEFT, 2, 2)).apply {
            isOpaque = false
            maximumSize = Dimension(maxWidth, Int.MAX_VALUE)
        }
        for (ref in refs) {
            val fileName = ref.fullPath.substringAfterLast('/')
            val lineSuffix = if (ref.startLine > 0) ":$ref.startLine" else ""
            val chipText = "📄 $fileName$lineSuffix"
            val chip = object : JLabel(chipText) {
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = ChatTheme.chipBg; g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
                    g2.color = ChatTheme.chipBorder; g2.stroke = java.awt.BasicStroke(1f); g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                    g2.dispose()
                    super.paintComponent(g)
                }
            }.apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.textSecondary
                border = JBUI.Borders.empty(2, 8, 2, 8)
                isOpaque = false
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
                    labelRef.foreground = ChatTheme.accentHover
                    labelRef.background = ChatTheme.chipHoverBg
                }
                override fun mouseExited(e: MouseEvent) {
                    labelRef.foreground = ChatTheme.textSecondary
                    labelRef.background = ChatTheme.chipBg
                }
            })
            chipRow.add(chip)
        }
        return chipRow
    }

    // ---- plus button（对齐设计稿 .iconbtn：圆角 6px + hover 灰色背景 + 可见边框）----
    private var plusBtnHovered = false
    private val plusButton = object : JLabel("+") {
        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            if (plusBtnHovered) {
                g2.color = ChatTheme.chipHoverBg; g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
                g2.color = ChatTheme.chipBorder; g2.stroke = java.awt.BasicStroke(1f); g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
            } else {
                g2.color = ChatTheme.inputBg; g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        font = ChatTheme.bodyFont.deriveFont(Font.BOLD, 18f)
        foreground = ChatTheme.textMuted
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        isOpaque = false
        border = null
        minimumSize = Dimension(26, 26)
        preferredSize = Dimension(26, 26)
        maximumSize = Dimension(26, 26)
        toolTipText = "添加文件引用"
    }
    // hover + click 全部挂在 label 上（Swing 父子组件 mouse 事件不冒泡）
    init { plusButton.addMouseListener(object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            plusBtnHovered = true; plusButton.foreground = ChatTheme.textSecondary; plusButton.repaint()
        }
        override fun mouseExited(e: MouseEvent) {
            plusBtnHovered = false; plusButton.foreground = ChatTheme.textMuted; plusButton.repaint()
        }
        override fun mouseClicked(e: MouseEvent) {
            val pos = inputArea.caretPosition
            inputArea.insert("@", pos)
            inputArea.caretPosition = pos + 1
            inputArea.requestFocus()
        }
    })}
    // 仅用于布局占位
    private val plusButtonWrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
        minimumSize = Dimension(26, 26)
        preferredSize = Dimension(26, 26)
        maximumSize = Dimension(26, 26)
        add(plusButton, BorderLayout.CENTER)
    }

    // ---- composer box 边框（对齐设计稿：圆角+内边距+聚焦蓝切换）----
    private val composerBorder = BorderFactory.createCompoundBorder(
        roundedBorder(ChatTheme.inputBorder),
        BorderFactory.createEmptyBorder(8, 10, 8, 10)
    )
    private val composerBorderFocused = BorderFactory.createCompoundBorder(
        roundedBorder(ChatTheme.inputFocus),
        BorderFactory.createEmptyBorder(8, 10, 8, 10)
    )

    // ---- submit/stop button (arrow → stop) ----
    private val lingmaSubmitBtn = object : JLabel("→") {
        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ChatTheme.userBg
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        font = ChatTheme.bodyFont.deriveFont(Font.BOLD, 15f)
        foreground = ChatTheme.userFg
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        isOpaque = false
        border = null
        toolTipText = "发送 (Enter)"
        minimumSize = Dimension(28, 28)
        preferredSize = Dimension(28, 28)
        maximumSize = Dimension(28, 28)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (viewModel.isStreaming) viewModel.stopGeneration() else sendMessage()
            }
        })
    }

    // ---- main input panel (Lingma style) ----
    private val lingmaInputBorder = BorderFactory.createCompoundBorder(
        roundedBorder(ChatTheme.inputBorder),
        BorderFactory.createEmptyBorder(7, 10, 7, 10)
    )
    private val lingmaInputBorderFocused = BorderFactory.createCompoundBorder(
        roundedBorder(ChatTheme.inputFocus),  // 1px 同默认，仅颜色切换，面板尺寸不变消除跳动
        BorderFactory.createEmptyBorder(7, 10, 7, 10)
    )

    // composer box: 包裹 chips + textarea + toolbar 的圆角边框容器
    private val composerBox = object : JPanel(BorderLayout(0, 0)) {
        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ChatTheme.inputBg
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        border = composerBorder

        add(chipPanel, BorderLayout.NORTH)

        // 文本输入区
        val scrollInput = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            minimumSize = Dimension(100, 40)
        }
        add(scrollInput, BorderLayout.CENTER)

        // 工具栏：加号 + 发送按钮
        val toolbar = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 0, 0, 0)
            add(plusButtonWrapper)
            add(Box.createHorizontalGlue())
            add(lingmaSubmitBtn)
        }
        add(toolbar, BorderLayout.SOUTH)

        // 聚焦时边框变蓝，失焦恢复
        fun updateBorder(focused: Boolean) {
            border = if (focused) composerBorderFocused else composerBorder
        }
        // textarea 的 focus 事件触发 box 边框切换
        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { updateBorder(true); inputArea.repaint() }
            override fun focusLost(e: FocusEvent) { updateBorder(false); inputArea.repaint() }
        })
    }

    private val inputPanel = object : JPanel(BorderLayout(0, 0)) {
        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()
            return Dimension(maxOf(natural.width, 200), maxOf(natural.height, 80))
        }
    }.apply {
        minimumSize = Dimension(150, 80)
        isOpaque = true
        background = ChatTheme.inputPanelBg
        border = JBUI.Borders.empty(4, 4, 4, 4)
        add(composerBox, BorderLayout.CENTER)
    }

    // ---- error/warning banner（双独立标签，支持 error + warning 互不覆盖）----
    private val errorBannerPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }
    private val errorLabel = object : JLabel() {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, super.getMaximumSize().height)
    }.apply {
        isVisible = false
        isOpaque = true
        border = JBUI.Borders.empty(6, 12)
        font = ChatTheme.bodyFont
        horizontalAlignment = SwingConstants.CENTER
        background = ChatTheme.errorBannerBg
        foreground = ChatTheme.errorBannerFg
    }
    private val warningLabel = object : JLabel() {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, super.getMaximumSize().height)
    }.apply {
        isVisible = false
        isOpaque = true
        border = JBUI.Borders.empty(6, 12)
        font = ChatTheme.bodyFont
        horizontalAlignment = SwingConstants.CENTER
        background = ChatTheme.warningBannerBg
        foreground = ChatTheme.warningBannerFg
    }

    private var welcomePanel: JPanel? = null
    private val bubbleSizeConstraints = mutableListOf<Pair<JPanel, JComponent>>()
    // 流式气泡增量更新
    private var streamingBubble: JPanel? = null
    private var streamingContentPane: JComponent? = null
    private var streamingThinkingRow: JPanel? = null
    private var streamingThinkingTextArea: JTextArea? = null

    /** 滚动节流器：50ms 内多次请求只执行一次实际滚动，避免流式 token 高频排队 EDT */
    private val scrollThrottle = javax.swing.Timer(50) {
        val bar = conversationScrollPane.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 80
        if (atBottom) bar.value = bar.maximum
    }.apply { isRepeats = false }

    // 自动引用去重
    private var lastAutoInsertedHash: Int = 0
    private var lastAutoInsertTime: Long = 0
    /** 通过 SelectionListener 自动添加的文件引用（最多一个，选区变化时更新而非新增） */
    private var selectionRefChip: RefChip? = null
    /** EditorFactoryListener 的父 Disposable，dispose 时自动移除监听器 */
    private val editorListenerDisposable = com.intellij.openapi.Disposable { }
    /** 已注册 SelectionListener 的编辑器集合，用于 dispose 时清理 */
    private val trackedEditors = java.util.concurrent.ConcurrentHashMap.newKeySet<com.intellij.openapi.editor.Editor>()
    private val editorListeners = java.util.concurrent.ConcurrentHashMap<com.intellij.openapi.editor.Editor, com.intellij.openapi.editor.event.SelectionListener>()

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
        isOpaque = true
        background = ChatTheme.winBg
        add(northStack, BorderLayout.NORTH)
        add(conversationScrollPane, BorderLayout.CENTER)
    }

    // ---- main panel ----
    // 注意：不能混用绝对定位(SOUTH)和相对定位(PAGE_END)，相对定位会覆盖绝对定位
    val panel = JPanel(BorderLayout()).apply {
        errorBannerPanel.add(errorLabel)
        errorBannerPanel.add(warningLabel)
        add(errorBannerPanel, BorderLayout.NORTH)
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

        // MCP 延迟加载（需 COMPONENTS_LOADED 之后，后台线程避免阻塞 EDT）
        ApplicationManager.getApplication().invokeLater {
            Thread {
                viewModel.setupMcpChangeListener(mcpManager)
                val mcpTools = try { mcpManager.loadAndConnect() } catch (_: Exception) { emptyList() }
                // 采集 MCP prompts + resources 注入 Agent 上下文（对齐 Claude Code）
                val prompts = try { mcpManager.collectPrompts() } catch (_: Exception) { emptyList() }
                val resources = try { mcpManager.collectResources() } catch (_: Exception) { emptyList() }
                // 回 EDT 更新 ViewModel
                ApplicationManager.getApplication().invokeLater {
                    viewModel.addMcpTools(mcpTools)
                    viewModel.addMcpPrompts(prompts)
                    viewModel.addMcpResources(resources)
                }
            }.start()
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
            background = ChatTheme.inputPanelBg
            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            val titleLabel = JLabel(AiAssistantBundle.message("chat.welcome.title")).apply {
                font = ChatTheme.largeFont
                foreground = ChatTheme.chipFg
                alignmentX = Component.CENTER_ALIGNMENT
            }
            val descLabel = JLabel(AiAssistantBundle.message("chat.welcome.desc")).apply {
                font = ChatTheme.bodyFont
                foreground = ChatTheme.headerTitleFg
                alignmentX = Component.CENTER_ALIGNMENT
            }
            val poweredLabel = JLabel(AiAssistantBundle.message("chat.welcome.powered")).apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.welcomeMutedFg
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
                // 发送/停止按钮样式保持一致，仅文字切换
                if (!streaming) {
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
        // onToolExecute / onToolResult 已由 onMessagesChanged 统一驱动 rebuildConversation，
        // 不再单独设置 needFullRebuild（新增消息由版本号增量检测，原地更新由 version 递增触发重新渲染）
        viewModel.onToolExecute = { _, _ -> }
        viewModel.onToolResult = { _, _ -> }
        viewModel.onConfirmTool = { _, _, _, _ ->
            ApplicationManager.getApplication().invokeLater { rebuildConversation() }
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
        conversationContainer.add(card, maxOf(0, conversationContainer.componentCount - 1))
        conversationContainer.revalidate()
        conversationContainer.repaint()
        scrollToBottom(force = true)
    }

    /** 已渲染的消息版本：msgId → version，用于增量更新变更检测 */
    private val renderedMsgVersions = mutableMapOf<Long, Int>()

    /** 消息 ID → 已渲染组件的映射，用于原地更新时移除旧组件 */
    private val msgIdToComponent = mutableMapOf<Long, JComponent>()

    /** 消息列表变化时的清空标记：消息被删除或 clearConversation 时设 true，下次 rebuild 全量重建 */
    private var needFullRebuild = true

    private fun rebuildConversation() {
        val displayMessages = viewModel.messages.filter { it.role != "system" }
        val hasMessages = displayMessages.isNotEmpty() || viewModel.streamingContent.isNotEmpty() || viewModel.streamingThinking.isNotEmpty()

        // 消息数减少（clear 或删除）→ 全量重建
        if (needFullRebuild || (hasMessages && renderedMsgVersions.size > displayMessages.size)) {
            conversationContainer.removeAll()
            bubbleSizeConstraints.clear()
            renderedMsgVersions.clear()
            msgIdToComponent.clear()
            needFullRebuild = false
        }

        if (hasMessages) {
            // 从空态 → 有消息的转换：清理旧的 hintPanel，防止与新增消息并存
            if (renderedMsgVersions.isEmpty()) {
                conversationContainer.removeAll()
                msgIdToComponent.clear()
            }

            // 无条件清理所有流式组件：消息已固化到 messages 列表，
            // 流式组件是冗余的——无论 streamingContent 是否为空都做清理（防御性兜底）
            streamingBubble?.let { conversationContainer.remove(it) }
            streamingBubble = null
            streamingContentPane = null
            streamingThinkingRow?.let { conversationContainer.remove(it) }
            streamingThinkingRow = null
            streamingThinkingTextArea = null

            // 增量渲染：只渲染版本号变更的消息（新增消息 version=0 不在 map 中，原地更新 version 递增触发重渲染）
            for (msg in displayMessages) {
                val lastVersion = renderedMsgVersions[msg.id]
                if (lastVersion != null && lastVersion == msg.version) continue

                // 移除旧组件（原地更新场景）
                msgIdToComponent.remove(msg.id)?.let { oldComp ->
                    conversationContainer.remove(oldComp)
                    // 同步清理 bubbleSizeConstraints 中对应的旧条目，防止增量模式下内存泄漏
                    bubbleSizeConstraints.removeAll { (bubble, _) ->
                        !bubble.isDisplayable || bubble.parent == null
                    }
                }

                renderedMsgVersions[msg.id] = msg.version
                val component = if (msg.role == "user") {
                    val refs = viewModel.messageRefChips[msg.id]
                    if (refs != null && refs.isNotEmpty()) {
                        createUserBubbleWithFooter(msg, buildRefChipFooter(refs))
                    } else {
                        createMessageBubble(msg)
                    }
                } else {
                    createMessageBubble(msg)
                }
                msgIdToComponent[msg.id] = component
                conversationContainer.add(component)
            }

            // 流式思考行 / 流式气泡：先移除旧组件再重新创建（增量渲染不会 removeAll，需显式清理避免重复）
            if (viewModel.streamingThinking.isNotEmpty()) {
                streamingThinkingRow?.let { conversationContainer.remove(it) }
                val textAreaRef = java.util.concurrent.atomic.AtomicReference<JTextArea>()
                val row = toolRowFactory.thinkingRow(viewModel.streamingThinking, initiallyExpanded = true, streaming = true, textAreaRef = textAreaRef)
                streamingThinkingRow = row
                streamingThinkingTextArea = textAreaRef.get()
                conversationContainer.add(row)
            }
            if (viewModel.streamingContent.isNotEmpty()) {
                // 移除旧的流式气泡组件和大小约束
                streamingBubble?.let { conversationContainer.remove(it) }
                streamingContentPane = null
                val sizeBeforeAdd = bubbleSizeConstraints.size
                val newBubble = createMessageBubble(AgentMessage("assistant", viewModel.streamingContent))
                conversationContainer.add(newBubble)
                // streamingBubble 存 row（直接加到 container 的组件），不是 ChatBubble（row 的子组件）
                streamingBubble = newBubble
                if (bubbleSizeConstraints.size > sizeBeforeAdd) {
                    val (_, c) = bubbleSizeConstraints.last()
                    streamingContentPane = c
                }
            }
        } else {
            conversationContainer.removeAll()
            renderedMsgVersions.clear()
            msgIdToComponent.clear()
            val hintPanel = JPanel(GridBagLayout())
            hintPanel.add(
                JLabel(AiAssistantBundle.message("chat.empty.hint")).apply {
                    font = font.deriveFont(Font.PLAIN, 13f)
                    foreground = ChatTheme.emptyHintFg
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
    private val bubbleFactory = BubbleFactory(conversationScrollPane, project)
    private val toolRowFactory = ToolRowFactory({ conversationScrollPane.viewport.width }, project)

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
            // streamingBubble 必须存 row（container 的直接子组件），不能存 ChatBubble（row 的子组件），
            // 否则 conversationContainer.remove(streamingBubble) 找不到直接子组件，流式气泡永远删不掉
            streamingBubble = bubble
            val entry = bubbleSizeConstraints.lastOrNull()
            if (entry != null) {
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
            autoScrollIfAtBottom()
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
                // 注意：不设置 caretPosition，否则触发 scrollRectToVisible 强制滚动
                streamingThinkingRow!!.revalidate()
                streamingThinkingRow!!.repaint()
            }
            autoScrollIfAtBottom()
        }
    }

    private fun createMessageBubble(message: AgentMessage): JPanel {
        return when (message.role) {
            "thinking" -> createCollapsibleThinkingBubble(message.content)
            "tool" -> createToolResultBubble(message)    // 工具调用+结果，可折叠
            "user" -> createUserBubble(message)
            "assistant" -> createAssistantBubble(message)
            else -> createAssistantBubble(message)
        }
    }

    /** 仅绘制圆角边框轮廓 */
    private fun roundedBorder(color: JBColor, thickness: Int = 1, radius: Int = 12): javax.swing.border.Border {
        return object : javax.swing.border.AbstractBorder() {
            override fun paintBorder(c: Component, g: java.awt.Graphics, x: Int, y: Int, w: Int, h: Int) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = java.awt.BasicStroke(thickness.toFloat())
                g2.drawRoundRect(x, y, w - 1, h - 1, radius, radius)
                g2.dispose()
            }
        }
    }

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
            Cmd("/new",   "新会话") { needFullRebuild = true; viewModel.clearConversation(); viewModel.messageRefChips.clear(); refChips.clear(); selectionRefChip = null; rebuildChips(); planBar.updatePlan(null); rebuildConversation() },
            Cmd("/plan",  "创建执行计划") { sendQuick("请为当前任务创建执行计划。先分析需求，然后调用 create_plan 工具。") },
            Cmd("/init",  "初始化项目文档") { sendQuick("请分析当前项目结构，创建 CLAUDE.md 文档，包含项目概述、常用命令、架构说明和关键约定。") },
            Cmd("/review","审查当前改动") { sendQuick("请审查当前分支的代码改动，分析潜在问题并给出修复建议。") },
            Cmd("/test",  "运行测试") { sendQuick("请运行 ./gradlew test，分析测试结果并修复失败的测试。") },
            Cmd("/stop",    "停止生成") { viewModel.stopGeneration() },
            Cmd("/compact", "压缩对话释放 token") {
                val apiKey = try { AppSettingsService.getInstance().getApiKey() } catch (_: Exception) { null }
                if (apiKey.isNullOrBlank()) { showWarning("请先配置 API Key"); return@Cmd }
                viewModel.compactConversation(apiKey)
            },
            Cmd("/clear",   "清空输入") { /* 已在 executeCommand 中清空 */ }
        )

        fun executeCommand(cmd: Cmd) {
            popup.isVisible = false
            inputListenerGuard.set(true)
            try { inputArea.text = "" } finally { inputListenerGuard.set(false) }
            cmd.action()
        }

        val skills = viewModel.getSkills().sortedBy { it.name }
        val allEntries: List<Pair<String, String>> = commands.map { it.name to it.desc } + skills.map { "/${it.name}" to it.description }
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
                    if (idx == 0) background = ChatTheme.menuSelectedBg
                }
                if (cmd != null) item.addActionListener { executeCommand(cmd) }
                else item.addActionListener {
                    closePopup()
                    // 填充到输入框而非直接发送，允许用户补充描述后再发送
                    inputListenerGuard.set(true)
                    try { inputArea.text = "/${name.removePrefix("/")} " } finally { inputListenerGuard.set(false) }
                    inputArea.requestFocus()
                }
                popup.add(item)
            }
        }
        rebuildItems(inputArea.text)

        fun moveSelection(delta: Int) {
            val items = popup.components.filterIsInstance<JMenuItem>()
            if (items.isEmpty()) return
            selectedIndex = (selectedIndex + delta).coerceIn(0, items.lastIndex)
            items.forEachIndexed { i, it -> it.background = if (i == selectedIndex) ChatTheme.menuSelectedBg else null }
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
        // 每次都重新扫描，确保新建/删除的文件能反映在列表中
        projectFilesCache = collectProjectFiles()
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
                    if (idx == 0) background = ChatTheme.menuSelectedBg
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
            items.forEachIndexed { i, it -> it.background = if (i == selectedIndex) ChatTheme.menuSelectedBg else null }
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
                // 纯引用无文字时用 refContent 作为消息正文（避免 sendMessage 内 content.isBlank() 拦截）
                val msgText = textContent.ifBlank { if (refContent.isNotEmpty()) refContent else "" }
                if (msgText.isEmpty() && images.isEmpty()) {
                    showWarning(AiAssistantBundle.message("chat.error.empty"))
                    return@invokeLater
                }
                // msgText 已包含 refContent 时不再重复拼接
                val effectiveRef = if (msgText == refContent) "" else refContent
                if (refChips.isNotEmpty()) {
                    viewModel.sendMessage(apiKey, msgText, images.ifEmpty { null }, effectiveRef, refChips.toList())
                } else {
                    viewModel.sendMessage(apiKey, msgText, images.ifEmpty { null }, effectiveRef)
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
        // 移除旧的 welcomePanel（如果存在），防止多次创建叠加
        welcomePanel?.let { panel.remove(it) }
        val welcome = createWelcomePanel()
        welcomePanel = welcome
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
        // 不调用 activateToolWindow()：划词选中是静默操作，不应抢走编辑器焦点
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
        errorLabel.text = message
        errorLabel.isVisible = true
        errorBannerPanel.isVisible = true
        // 互斥：显示 error 时隐藏 warning
        warningLabel.isVisible = false
    }

    private fun showWarning(message: String) {
        warningLabel.text = message
        warningLabel.isVisible = true
        errorBannerPanel.isVisible = true
        // 互斥：显示 warning 时隐藏 error
        errorLabel.isVisible = false
    }

    private fun hideError() {
        errorLabel.isVisible = false
        warningLabel.isVisible = false
        errorBannerPanel.isVisible = false
    }

    /** 流式更新专用：立即检查用户是否在底部附近，仅在底部时才滚动。
     *  相比 scrollThrottle 的优势是即时响应（不等待 50ms 节流），
     *  确保流式输出时用户能看到新内容持续出现；同时严格守卫「用户不在底部时不滚动」，
     *  让用户能自由向上翻阅历史记录。 */
    private fun autoScrollIfAtBottom() {
        val bar = conversationScrollPane.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 80
        if (atBottom) bar.value = bar.maximum
    }

    /** 仅当用户已在底部附近时才自动滚动，避免打断浏览历史消息 */
    private fun scrollToBottom(force: Boolean = false) {
        if (force) {
            // 强制滚动（发送消息后）立即执行，不节流
            SwingUtilities.invokeLater {
                conversationScrollPane.verticalScrollBar.value = conversationScrollPane.verticalScrollBar.maximum
            }
        } else {
            // 节流：50ms 内多次调用只执行最后一次，减少 EDT 排队
            scrollThrottle.restart()
        }
    }
}
