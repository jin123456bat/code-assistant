package com.aiassistant.ui.chat

import com.aiassistant.AppLogger
import com.aiassistant.agent.FileRef
import com.aiassistant.agent.ImageRef
import com.aiassistant.ui.AppColors
import com.aiassistant.skills.SkillManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatInputArea(
    private val onSend: (String) -> Unit,
    private val onStop: (() -> Unit)? = null,
    private val onNewSession: (() -> Unit)? = null,
    private val onSendWithImages: ((String, List<ImageRef>) -> Unit)? = null,
    /** 输入内容变化回调（文本内容），用于 ChatViewModel 更新 InputState.tokenCount */
    private val onInputChanged: ((text: String) -> Unit)? = null,
    /** 获取上一条用户消息文本的回调，用于 ↑ 在空输入框时填充历史消息（对齐 docs/ui/pages.md §十） */
    private val onFillPreviousMessage: (() -> String?)? = null,
    /** 剪贴板内容提供器。固定读取一次快照，避免逐个 flavor 查询时剪贴板内容发生变化。 */
    private val clipboardContentsProvider: () -> Transferable? = {
        Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    },
    /** 测试用打开回调；生产环境为空时使用 IntelliJ FileEditorManager。 */
    private val fileOpenHandler: ((String) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    private val textArea = JTextArea(3, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = font.deriveFont(12f)
        background = AppColors.pageBg
        // 对齐 ui-prototype.html .input-area textarea: padding: 10px 12px
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
    }

    /** 当前显示的 @file /command 弹窗，hidePopup() 关闭 */
    private var activePopup: javax.swing.Popup? = null
    private val popupMenuItems = mutableListOf<JMenuItem>()
    private val ideShortcutActions = mutableListOf<AnAction>()

    /** Tags 行（FlowLayout，文件+图片混合排列，位于输入框上方），对齐 docs/ui/chat.md §七 + §十四 tagsRow */
    private val tagsPanel = object : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
        init { isOpaque = true; background = AppColors.pageBg }

        override fun getPreferredSize(): Dimension {
            val singleRow = super.getPreferredSize()
            val containerWidth = (parent?.width ?: width).takeIf { it > 0 } ?: return singleRow
            if (componentCount == 0) return singleRow
            val hgap = (layout as FlowLayout).hgap
            val vgap = (layout as FlowLayout).vgap
            var x = 0; var rowH = 0; var totalH = 0
            for (i in 0 until componentCount) {
                val c = getComponent(i)
                val cw = c.preferredSize.width
                val ch = c.preferredSize.height
                if (x + cw > containerWidth && x > 0) {
                    totalH += rowH + vgap; x = 0; rowH = 0
                }
                x += cw + hgap
                rowH = maxOf(rowH, ch)
            }
            totalH += rowH
            return Dimension(singleRow.width, totalH.coerceAtLeast(singleRow.height))
        }
    }

    /** 项目文件缓存，后台预加载，避免 EDT 访问 PSI index */
    @Volatile
    private var cachedFiles: List<ProjectFileEntry>? = null
    private val maxCachedProjectFiles = 10_000

    /** 手动 @file 引用（可多个），对齐 docs/ui/chat.md §十二 InputState.manualRefs */
    private val manualFileRefs = mutableListOf<FileRef>()

    /** 编辑器选中代码引用（仅一个），对齐 docs/ui/chat.md §十二 InputState.selectionRef */
    private var selectionFileRef: FileRef? = null

    /** 粘贴的图片引用（可多个），对齐 docs/ui/chat.md §十二 InputState.images */
    private val imageRefs = mutableListOf<ImageRef>()
    /** 图片预览按需落盘；路径只属于 UI，不进入发送给 LLM 的 ImageRef。 */
    private val imagePreviewPaths = mutableMapOf<String, Path>()
    @Volatile
    private var disposed = false
    private val addFileButton = JButton("+").apply {
        accessibleContext.accessibleDescription = "添加文件引用"
        font = font.deriveFont(Font.PLAIN, 16f)
        preferredSize = Dimension(24, 24)
        isContentAreaFilled = true
        isOpaque = true
        background = AppColors.cardBg
        foreground = AppColors.textSecondary
        border = BorderFactory.createLineBorder(AppColors.gray300, 1)
        isFocusPainted = false
        addActionListener {
            val caret = textArea.caretPosition
            textArea.document.insertString(caret, "@", null)
        }
    }
    private val sendButton = JButton("→").apply {
        accessibleContext.accessibleDescription = "发送消息"
        font = font.deriveFont(Font.PLAIN, 14f)
        preferredSize = Dimension(28, 28)
        addActionListener { doSend() }
        isOpaque = true
        foreground = Color.WHITE
        background = AppColors.primary
        border = BorderFactory.createEmptyBorder()
        isFocusPainted = false
    }
    private val stopButton = JButton("⏹").apply {
        accessibleContext.accessibleDescription = "停止生成"
        font = font.deriveFont(Font.PLAIN, 12f)
        preferredSize = Dimension(28, 28)
        addActionListener { onStop?.invoke() }
        isOpaque = true
        foreground = Color.WHITE
        background = AppColors.error
        border = BorderFactory.createEmptyBorder()
        isFocusPainted = false
        isVisible = false
    }


    /** 对齐 docs/ui/components.md §3.1 Primary Button Loading 状态：bg=#3B82F6, text="", 文字位置显示 spinner, 不可点击 */
    private var loadingAnimator: javax.swing.Timer? = null
    private val spinnerChars = charArrayOf('◐', '◓', '◑', '◒') // ◐◓◑◒
    private var spinnerIndex = 0

    /** 文字位置显示 spinner 的自定义 Icon，对齐 docs/ui/components.md §3.1 Loading 行"文字位置显示 spinner" */
    private inner class SpinnerIcon : Icon {
        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            g2.font = c.font
            g2.color = c.foreground
            val fm = g2.fontMetrics
            val charStr = spinnerChars[spinnerIndex].toString()
            val charWidth = fm.stringWidth(charStr)
            val charHeight = fm.ascent
            // 居中绘制 spinner 字符
            g2.drawString(
                charStr,
                x + (iconWidth - charWidth) / 2,
                y + (iconHeight - charHeight) / 2 + fm.ascent
            )
            g2.dispose()
        }

        override fun getIconWidth(): Int = 20
        override fun getIconHeight(): Int = sendButton.font.size + 4
    }

    // 对齐 ui-prototype.html: textarea border=none，仅 input-area 容器有 border-top
    private val inputScrollPane: JScrollPane
    private val defaultInputBorder =
        BorderFactory.createLineBorder(AppColors.border, 1)     // 对齐 docs: #D1D5DB 边框
    private val focusInputBorder =
        BorderFactory.createLineBorder(AppColors.primary, 2)    // 对齐 docs: Focus 2px #3B82F6
    private val disabledInputBorder =
        BorderFactory.createLineBorder(AppColors.hoverBg)       // disabled 时浅灰背景

    /**
     * 对齐 docs/ui/components.md §3.2 Overflow：高度自适应 2-10 行。
     * 根据文本内容行数动态调整 JTextArea 行高，超过 10 行时 JScrollPane 垂直滚动条自动出现。
     */
    private fun adjustTextAreaRows() {
        val fontMetrics = textArea.getFontMetrics(textArea.font)
        val textWidth = textArea.width
        if (textWidth <= 0) {
            textArea.rows = 3
            return
        }

        val text = textArea.text
        val lines = text.split("\n")
        // 计算实际所需行数（含自动换行）
        val totalLines = lines.sumOf { line ->
            if (line.isEmpty()) 1
            else maxOf(1, (fontMetrics.stringWidth(line) + textWidth - 1) / textWidth)
        }.coerceIn(3, 10)

        textArea.rows = totalLines
        textArea.revalidate()
    }

    /**
     * 对齐 docs/ui/components.md §3.2 Overflow "JScrollPane border 跟随 input 边框"。
     * 根据当前输入状态（Focus/Disabled/Default）计算并设置 JScrollPane 应使用的边框。
     */
    private fun updateInputBorder() {
        inputScrollPane.border = when {
            !textArea.isEnabled -> disabledInputBorder
            textArea.hasFocus() -> focusInputBorder
            else -> defaultInputBorder
        }
    }

    /** 对齐 docs/ui/components.md §3.2 Error 状态：消息发送失败时短暂标红 500ms */
    fun showError() {
        // 取消上一次未触发的 error timer，避免多次快速调用导致前一个 timer 提前清除后一个 error 的红色边框
        errorRecoveryTimer?.stop()
        val errorBorder = BorderFactory.createLineBorder(AppColors.error)
        inputScrollPane.border = errorBorder
        errorRecoveryTimer = javax.swing.Timer(500) {
            // 对齐 docs/ui/components.md §3.2 Overflow "JScrollPane border 跟随 input 边框"
            // 恢复时根据输入当前状态正确还原边框，而非无条件恢复为 defaultInputBorder
            updateInputBorder()
        }.apply { isRepeats = false; start() }
    }

    private var errorRecoveryTimer: javax.swing.Timer? = null

    private var projectRef: com.intellij.openapi.project.Project? = null

    fun setProject(project: com.intellij.openapi.project.Project) {
        if (projectRef !== project) {
            cachedFiles = null
        }
        projectRef = project
        // 项目注入后立即预热文件索引，避免用户首次输入 @ 时只能等待异步加载。
        preloadProjectFiles()
    }

    /**
     * 项目文件条目（文件名 + 相对路径），用于 @file Popup 按子目录分组展示。
     * 对齐 docs/ui/chat.md §八：📁 子目录分组头部 + 文件列表。
     */
    private data class ProjectFileEntry(
        val fileName: String,
        /** 相对于项目根目录的路径，如 "src/main/kotlin/service/UserService.kt" */
        val relativePath: String
    )

    /**
     * 获取项目中的文件列表（含相对路径），用于 @file Popup 按子目录分组展示。
     * 对齐 docs/ui/chat.md §八：按子目录分组显示文件，最大 8 行可见 + 滚动条。
     * 缓存项目内容文件，Popup 根据当前过滤词最多展示 50 个结果。
     */
    private fun getProjectFiles(filter: String): List<ProjectFileEntry> {
        val project = projectRef ?: return emptyList()
        val basePath = project.basePath ?: return emptyList()
        val entries = ArrayList<ProjectFileEntry>()
        val seenPaths = HashSet<String>()
        val fileIndex = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).fileIndex

        // 直接遍历项目 content，避免 FilenameIndex.getAllFilenames() 混入 SDK/依赖文件后提前截断。
        fileIndex.iterateContent { vFile ->
            if (vFile.isDirectory || !vFile.name.contains(filter, ignoreCase = true)) {
                return@iterateContent true
            }
            val absPath = vFile.path
            val relPath = if (absPath.startsWith(basePath)) {
                absPath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
            } else {
                vFile.name
            }
            if (seenPaths.add(relPath)) {
                entries.add(ProjectFileEntry(fileName = vFile.name, relativePath = relPath))
            }
            entries.size < maxCachedProjectFiles
        }
        return entries.sortedBy { it.relativePath.lowercase() }
    }

    /** 计算当前 Tags 行展示用的扁平化标签列表（FileRef + ImageRef 混合，按添加顺序） */
    private fun buildDisplayTags(): List<TagItem> {
        val items = mutableListOf<TagItem>()
        // 选中引用排最前（不可关闭，对齐 docs/ui/chat.md §七：选中 tag 不用 ✕，取消选中自动消失）
        selectionFileRef?.let { items.add(TagItem.FileTag(it, closable = false)) }
        // 手动 @file 引用
        manualFileRefs.forEach { items.add(TagItem.FileTag(it)) }
        // 图片引用
        imageRefs.forEach { items.add(TagItem.ImageTag(it)) }
        return items
    }

    /** Tags 行展示项（文件+图片混合排列），对齐 docs/ui/chat.md §七 TagsRow */
    private sealed class TagItem {
        abstract val displayName: String
        abstract val closable: Boolean

        data class FileTag(val ref: FileRef, override val closable: Boolean = true) : TagItem() {
            override val displayName: String get() = ref.displayName
        }

        data class ImageTag(val ref: ImageRef) : TagItem() {
            override val displayName: String get() = ref.fileName
            override val closable: Boolean get() = true
        }
    }

    init {
        inputScrollPane = JScrollPane(textArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = defaultInputBorder
        }

        // 对齐 docs/ui/components.md §3.2 Default 状态 placeholder="输入你的问题..." + Focus 状态
        // JTextArea 不支持原生 placeholder，使用 FocusListener 模拟：未聚焦且为空时显示灰色提示文本
        val placeholderText = "输入你的问题，@ 选择文件或 Ctrl+V 粘贴图片..."
        val placeholderFg = AppColors.textSecondary
        val defaultFg = textArea.foreground

        fun showPlaceholder() {
            textArea.foreground = placeholderFg
            textArea.text = placeholderText
        }

        fun hidePlaceholder() {
            if (textArea.foreground == placeholderFg && textArea.text == placeholderText) {
                textArea.foreground = defaultFg
                textArea.text = ""
            }
        }

        fun restorePlaceholderIfEmpty() {
            if (textArea.text.isEmpty()) {
                showPlaceholder()
            }
        }

        // 初始状态显示 placeholder
        showPlaceholder()

        // 通过 InputMap/ActionMap 显式绑定导航、发送和粘贴，避免 IDE 默认文本动作绕过自定义逻辑。
        val inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = textArea.actionMap
        val upKey = KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)
        val downKey = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)
        val originalCaretUp = inputMap.get(upKey)?.let(actionMap::get) ?: actionMap.get("caret-up")
        val originalCaretDown = inputMap.get(downKey)?.let(actionMap::get) ?: actionMap.get("caret-down")

        val navigateUp: (java.awt.event.ActionEvent) -> Unit = { event ->
            if (activePopup != null) {
                selectPopupItem(-1)
            } else if (isTextAreaEmpty()) {
                onFillPreviousMessage?.invoke()?.let { prevMsg ->
                    textArea.text = prevMsg
                    textArea.caretPosition = prevMsg.length
                    if (textArea.foreground == placeholderFg) {
                        textArea.foreground = defaultFg
                    }
                }
            } else {
                originalCaretUp?.actionPerformed(event)
            }
        }
        val navigateDown: (java.awt.event.ActionEvent) -> Unit = { event ->
            if (activePopup != null) {
                selectPopupItem(1)
            } else {
                originalCaretDown?.actionPerformed(event)
            }
        }

        inputMap.put(upKey, "chatNavigateUp")
        actionMap.put("chatNavigateUp", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                navigateUp(e)
            }
        })
        inputMap.put(downKey, "chatNavigateDown")
        actionMap.put("chatNavigateDown", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                navigateDown(e)
            }
        })

        // IntelliJ 的 Action 系统先于 Swing InputMap 处理全局快捷键，组件级 Action 必须显式抢占 ↑↓。
        registerIdeShortcut(upKey) {
            navigateUp(java.awt.event.ActionEvent(textArea, ActionEvent.ACTION_PERFORMED, "chatNavigateUp"))
        }
        registerIdeShortcut(downKey) {
            navigateDown(java.awt.event.ActionEvent(textArea, ActionEvent.ACTION_PERFORMED, "chatNavigateDown"))
        }

        // Ctrl+V / Cmd+V 必须走 InputMap；仅监听 KeyListener 会被 JTextArea 默认 Paste Action 绕过。
        val handlePaste = {
            if (!pasteImage()) {
                textArea.paste()
            }
        }
        val pasteAction = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                handlePaste()
            }
        }
        val ctrlPasteKey = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
        val metaPasteKey = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK)
        inputMap.put(ctrlPasteKey, "chatPaste")
        inputMap.put(metaPasteKey, "chatPaste")
        actionMap.put("chatPaste", pasteAction)
        registerIdeShortcut(ctrlPasteKey, handlePaste)
        registerIdeShortcut(metaPasteKey, handlePaste)

        // Paste Action、菜单粘贴和拖放最终都经过 TransferHandler，图片优先，普通文本回退原处理器。
        val originalTransferHandler = textArea.transferHandler
        textArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                supportsImageTransfer(support.transferable) || originalTransferHandler?.canImport(support) == true

            override fun importData(support: TransferSupport): Boolean {
                if (pasteImagesFromTransferable(support.transferable)) return true
                return originalTransferHandler?.importData(support) == true
            }

            override fun canImport(comp: JComponent, transferFlavors: Array<DataFlavor>): Boolean =
                transferFlavors.any(::isSupportedImageFlavor) ||
                    originalTransferHandler?.canImport(comp, transferFlavors) == true

            override fun importData(comp: JComponent, transferable: Transferable): Boolean {
                if (pasteImagesFromTransferable(transferable)) return true
                return originalTransferHandler?.importData(comp, transferable) == true
            }
        }

        // 仅覆盖纯 Enter 键，Shift+Enter 保持默认插入换行行为
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chatSend")
        actionMap.put("chatSend", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (activePopup != null) {
                    clickSelectedPopupItem()
                } else {
                    doSend()
                }
            }
        })

        // 对齐 docs/ui/components.md §3.2 Focus 状态：边框加粗到 2px，颜色 #3B82F6
        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) {
                hidePlaceholder()
                updateInputBorder()
            }

            override fun focusLost(e: FocusEvent?) {
                updateInputBorder()
                restorePlaceholderIfEmpty()
            }
        })

        // 初始布局后首次计算行高（组件获得宽度后触发）
        textArea.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = adjustTextAreaRows()
        })

        // 对齐 docs/ui/components.md §3.2 Overflow：高度自适应 2-10 行，超过 10 行出现垂直滚动条
        // 同时通知输入变化以更新 token 计数
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                adjustTextAreaRows()
                notifyInputChanged()
            }

            override fun removeUpdate(e: DocumentEvent?) {
                adjustTextAreaRows()
                notifyInputChanged()
            }

            override fun changedUpdate(e: DocumentEvent?) {
                adjustTextAreaRows()
                notifyInputChanged()
            }
        })
        // UP/DOWN/ENTER/粘贴已通过 InputMap/ActionMap 处理，KeyListener 只保留 Escape/Ctrl+Shift+N。
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> {
                        hidePopup()
                    }
                }
                // Ctrl+Shift+N = new session
                if (e.keyCode == KeyEvent.VK_N && (e.isControlDown || e.isMetaDown) && e.isShiftDown) {
                    e.consume(); onNewSession?.invoke()
                }
            }
        })

        // @file /command detection
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleTriggerCheck()
            override fun removeUpdate(e: DocumentEvent?) = scheduleTriggerCheck()
            override fun changedUpdate(e: DocumentEvent?) = scheduleTriggerCheck()
        })

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = true; background = AppColors.pageBg
        }
        topPanel.add(tagsPanel, BorderLayout.NORTH)
        topPanel.add(inputScrollPane, BorderLayout.CENTER)

        add(topPanel, BorderLayout.CENTER)
        val buttonBar = JPanel().apply {
            isOpaque = true; background = AppColors.pageBg
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 8, 6, 8)
            add(addFileButton)
            add(Box.createHorizontalStrut(8))
            add(JLabel("输入 @ 选择文件").apply {
                font = font.deriveFont(11f)
                foreground = AppColors.textSecondary
            })
            add(Box.createHorizontalGlue())
            add(stopButton)
            add(Box.createHorizontalStrut(8))
            add(sendButton)
        }
        add(buttonBar, BorderLayout.SOUTH)
        // 对齐 ui-prototype.html .input-area: border-top=1px solid #E5E7EB
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, AppColors.border)
    }

    /**
     * 检测输入框是否为空（考虑 placeholder 文本）。
     * placeholder 文本在失去焦点时显示，此时 textArea.text 不为空但实际无用户输入。
     */
    private fun isTextAreaEmpty(): Boolean {
        val text = textArea.text
        return text.isEmpty() || (text == "输入你的问题，@ 选择文件或 Ctrl+V 粘贴图片..." && textArea.foreground == AppColors.textSecondary)
    }

    /** 通知输入变化，用于 ChatViewModel 更新 InputState.tokenCount */
    private fun notifyInputChanged() {
        onInputChanged?.invoke(textArea.text)
    }

    private var triggerCheckScheduled = false

    /** DocumentListener 触发时 caret 尚未保证移动完成，延迟到本轮 EDT 事件结束后再判断 @ 和 /。 */
    private fun scheduleTriggerCheck() {
        if (triggerCheckScheduled) return
        triggerCheckScheduled = true
        SwingUtilities.invokeLater {
            triggerCheckScheduled = false
            checkTriggers()
        }
    }

    private fun checkTriggers() {
        val text = textArea.text
        val caret = textArea.caretPosition
        val before = text.substring(0, caret.coerceAtMost(text.length))

        // @file trigger
        val fileMatch = Regex("""(?:^|\s)@(\S*)$""").find(before)
        if (fileMatch != null) {
            val filter = fileMatch.groupValues[1]
            if (cachedFiles == null) {
                // 缓存未就绪：异步加载，避免 EDT 上 ReadAction.compute 死锁
                preloadProjectFiles()
                hidePopup()
                return
            }
            showPopup(filter, cachedFiles ?: emptyList())
            return
        }
        // /command trigger
        val cmdMatch = Regex("""(?:^|\s)/(\S*)$""").find(before)
        if (cmdMatch != null) {
            showCommandPopup(
                cmdMatch.groupValues[1],
                commandSuggestions()
            )
            return
        }
        hidePopup()
    }

    @Volatile
    private var projectFilesLoading = false

    /** 异步预加载项目文件列表，避免 EDT 上 ReadAction.compute 死锁 */
    private fun preloadProjectFiles() {
        val project = projectRef ?: return
        if (projectFilesLoading) return
        projectFilesLoading = true
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val files = try {
                com.intellij.openapi.application.ReadAction.compute<List<ProjectFileEntry>, Throwable> {
                    getProjectFiles("")
                }
            } catch (exception: Exception) {
                // 索引初始化期间可能暂时不可读；不缓存空结果，让下一次 @ 触发可以重试。
                AppLogger.warn("Project file preload failed: ${exception.message}")
                null
            }
            SwingUtilities.invokeLater {
                projectFilesLoading = false
                if (projectRef === project) {
                    if (!files.isNullOrEmpty()) {
                        cachedFiles = files
                        // 加载完成后重新检查，若用户仍在输入 @ 则弹出
                        checkTriggers()
                    } else if (files != null) {
                        // 项目模型尚未就绪时可能得到空 content；不缓存，下一次 @ 可重新加载。
                        cachedFiles = null
                    }
                }
            }
        }
    }

    private fun commandSuggestions(): List<String> {
        val builtIns = listOf("/plan", "/clear", "/new")
        val project = projectRef ?: return builtIns
        return (builtIns + SkillManager(project).enabledSlashCommands()).distinct()
    }

    /** 显示 @file Popup（按子目录分组），对齐 docs/ui/chat.md §八 */
    private fun showPopup(filter: String, fileEntries: List<ProjectFileEntry>) {
        val filtered = fileEntries
            .filter { it.fileName.contains(filter, ignoreCase = true) }
            .take(50)
        if (filtered.isEmpty()) {
            hidePopup(); return
        }

        // 按父目录分组（相对于项目根目录）
        val grouped: Map<String, List<ProjectFileEntry>> = filtered.groupBy { entry ->
            val dir = entry.relativePath.substringBeforeLast("/", "")
            dir.ifEmpty { "." }
        }

        // 对齐 docs/ui/chat.md §八：最大 8 行可见 + 滚动条，按子目录分组
        popupMenuItems.clear()
        val menuPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        grouped.forEach { (dir, files) ->
            // 目录分组头部：📁 src/main/kotlin/service/
            val headerLabel = JLabel("📁 $dir/").apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = AppColors.textSecondary
                border = BorderFactory.createEmptyBorder(2, 4, 1, 4)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            menuPanel.add(headerLabel)

            files.forEach { entry ->
                // 文件项显示：文件名 + 简短目录提示
                val displayText = "  ${entry.fileName}  $dir/"
                val mi = JMenuItem(displayText).apply {
                    font = font.deriveFont(12f)
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    addActionListener {
                        // 使用相对路径作为 @file 引用，对齐 docs/ui/chat.md §七 @file 引用格式
                        addFileReference("@${entry.relativePath}")
                        hidePopup()
                    }
                }
                popupMenuItems.add(mi)
                menuPanel.add(mi)
            }
        }

        // 对齐 docs/ui/chat.md §八：最大 8 行可见 + 滚动条
        // 按实际组件高度累加前 8 行，fallback 基于字体行高估算
        val maxVisibleRows = 8
        val popupHeight = calculatePopupHeight(menuPanel, maxVisibleRows)
        val scrollPane = JScrollPane(menuPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            preferredSize = Dimension(
                menuPanel.preferredSize.width + verticalScrollBar.preferredSize.width,
                popupHeight
            )
        }
        scrollPane.border = BorderFactory.createLineBorder(AppColors.border)
        // 弹窗显示在输入框上方，紧贴文本框
        val anchor = runCatching { inputScrollPane.locationOnScreen }.getOrNull() ?: run {
            hidePopup()
            return
        }
        val x = anchor.x
        val y = anchor.y - scrollPane.preferredSize.height
        val factory = javax.swing.PopupFactory.getSharedInstance()
        activatePopup(factory.getPopup(inputScrollPane, scrollPane, x, y))
    }

    /** 计算 Popup 高度：累加前 maxRows 个组件的 preferredSize.height，确保 8 行可见约束准确 */
    private fun calculatePopupHeight(menuPanel: JPanel, maxRows: Int): Int {
        val componentCount = menuPanel.componentCount
        if (componentCount == 0) {
            // 无组件时基于字体行高估算，避免硬编码 200px
            return font.deriveFont(12f).let { f ->
                val fm = menuPanel.getFontMetrics(f)
                fm.height * maxRows + fm.descent
            }
        }
        val rows = minOf(componentCount, maxRows)
        var height = 0
        for (i in 0 until rows) {
            height += menuPanel.getComponent(i).preferredSize.height
        }
        return height.coerceAtLeast(1)
    }

    /** 显示指令 Popup（/command），对齐 docs/ui/chat.md §八 */
    private fun showCommandPopup(filter: String, items: List<String>) {
        val filtered = items.filter { it.contains(filter, ignoreCase = true) }.take(8)
        if (filtered.isEmpty()) {
            hidePopup(); return
        }

        popupMenuItems.clear()
        val menuPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        filtered.forEach { item ->
            val mi = JMenuItem(item).apply {
                font = font.deriveFont(12f)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addActionListener {
                    insertAtCaret(item + " ")
                    hidePopup()
                }
            }
            popupMenuItems.add(mi)
            menuPanel.add(mi)
        }

        // 对齐 docs/ui/chat.md §八：最大 8 行可见 + 滚动条
        val maxVisibleRows = 8
        val popupHeight = calculatePopupHeight(menuPanel, maxVisibleRows)
        val scrollPane = JScrollPane(menuPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createLineBorder(AppColors.border)
        }
        scrollPane.preferredSize = Dimension(
            maxOf(
                menuPanel.preferredSize.width + scrollPane.verticalScrollBar.preferredSize.width,
                200
            ),
            popupHeight
        )
        val anchor = runCatching { inputScrollPane.locationOnScreen }.getOrNull() ?: run {
            hidePopup()
            return
        }
        val y = anchor.y - scrollPane.preferredSize.height
        val factory = javax.swing.PopupFactory.getSharedInstance()
        activatePopup(factory.getPopup(inputScrollPane, scrollPane, anchor.x, y))
    }

    /** 先关闭旧 Popup，再设置首项选中，避免 hidePopup() 把新 Popup 的选中索引重置。 */
    private fun activatePopup(popup: javax.swing.Popup) {
        hidePopup()
        activePopup = popup
        selectFirstPopupItem()
        SwingUtilities.invokeLater {
            if (activePopup === popup) {
                popup.show()
                ensureSelectedPopupItemVisible()
            }
        }
    }

    private fun hidePopup() {
        activePopup?.hide()
        activePopup = null
        popupIndex = -1
    }

    private var popupIndex = -1
    private fun selectPopupItem(direction: Int) {
        val count = popupMenuItems.size
        if (count == 0) return
        // 对齐 docs/ui/chat.md §八：↑↓ 移动高亮（循环）
        popupIndex = ((popupIndex + direction) % count + count) % count
        for (i in 0 until count) {
            val selected = i == popupIndex
            popupMenuItems[i].apply {
                isArmed = selected
                // JMenuItem 在普通 JPanel 中 isArmed 无视觉反馈，需显式设背景色
                background = if (selected) AppColors.primaryLight else AppColors.cardBg
                isOpaque = true
            }
        }
        ensureSelectedPopupItemVisible()
    }

    /** 让键盘高亮项始终处于 Popup 的 JViewport 可视区域内。 */
    private fun ensureSelectedPopupItemVisible() {
        val selectedItem = popupMenuItems.getOrNull(popupIndex) ?: return
        val itemContainer = selectedItem.parent as? JComponent ?: return
        itemContainer.scrollRectToVisible(selectedItem.bounds)
    }

    private fun selectFirstPopupItem() {
        if (popupMenuItems.isEmpty()) {
            popupIndex = -1
            return
        }
        popupIndex = 0
        for (i in popupMenuItems.indices) {
            val selected = i == 0
            popupMenuItems[i].apply {
                isArmed = selected
                background = if (selected) AppColors.primaryLight else AppColors.cardBg
                isOpaque = true
            }
        }
    }

    private fun clickSelectedPopupItem() {
        if (popupIndex >= 0 && popupIndex < popupMenuItems.size) {
            popupMenuItems[popupIndex].doClick()
        }
    }

    private fun insertAtCaret(s: String) {
        val caret = textArea.caretPosition
        val before = textArea.text.substring(0, caret)
        // Replace the @xxx or /xxx part
        val newBefore = before.replace(Regex("""[@/]\S*$"""), s)
        textArea.text = newBefore + textArea.text.substring(caret)
        textArea.caretPosition = newBefore.length
    }

    // ponytail: clipboard image paste — 构建 ImageRef 模型对象，对齐 docs/ui/chat.md §七剪贴板图片粘贴 + §十二 ImageRef
    private fun pasteImage(): Boolean {
        val transferable = try {
            clipboardContentsProvider()
        } catch (e: Exception) {
            AppLogger.warn("Clipboard image paste failed: ${e.message}")
            return false
        }
        return transferable?.let(::pasteImagesFromTransferable) ?: false
    }

    private fun supportsImageTransfer(transferable: Transferable): Boolean =
        transferable.transferDataFlavors.any(::isSupportedImageFlavor)

    private fun isSupportedImageFlavor(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.imageFlavor ||
            flavor == DataFlavor.javaFileListFlavor ||
            flavor.primaryType.equals("image", ignoreCase = true)

    private fun registerIdeShortcut(keyStroke: KeyStroke, handler: () -> Unit) {
        val action = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = handler()
        }
        action.registerCustomShortcutSet(CustomShortcutSet(keyStroke), textArea)
        ideShortcutActions.add(action)
    }

    /**
     * 从剪贴板快照读取并添加图片。提取逻辑独立于系统剪贴板，便于覆盖浏览器 Image、文件列表等真实 flavor。
     */
    internal fun pasteImagesFromTransferable(transferable: Transferable): Boolean {
        return try {
            val clipboardImages = ClipboardImageReader.read(transferable)
            if (clipboardImages.isEmpty()) return false

            var pasted = false
            for (clipboardImage in clipboardImages) {
                if (!checkImageLimit()) break
                val (formatName, extension, mimeType) = detectImageFormat(clipboardImage.sourceFileName)
                if (
                    processClipboardImage(
                        img = clipboardImage.image,
                        sourceFileName = clipboardImage.sourceFileName,
                        formatName = formatName,
                        extension = extension,
                        mimeType = mimeType
                    )
                ) {
                    pasted = true
                }
            }
            if (pasted) refreshTags()
            pasted
        } catch (e: Exception) {
            AppLogger.warn("Clipboard image processing failed: ${e.message}")
            false
        }
    }

    /** 检查图片数量是否已达上限（20 张，对齐 Anthropic API 限制），超限时弹出提示 */
    private fun checkImageLimit(): Boolean {
        if (imageRefs.size >= 20) {
            JOptionPane.showMessageDialog(
                this,
                "单次粘贴最多 20 张图片，请先移除部分已粘贴的图片后再试。",
                "图片数量超限",
                JOptionPane.WARNING_MESSAGE
            )
            return false
        }
        return true
    }

    /** 处理单张图片：缩放、编码、构建 ImageRef 并添加到 imageRefs */
    private fun processClipboardImage(
        img: BufferedImage,
        sourceFileName: String?,
        formatName: String,
        extension: String,
        mimeType: String
    ): Boolean {
        // Scale down（长边 max 2048px，保持比例），对齐 docs/ui/chat.md §七
        val maxDim = 2048
        val scaled = if (img.width > maxDim || img.height > maxDim) {
            val ratio = maxDim.toDouble() / maxOf(img.width, img.height)
            BufferedImage(
                (img.width * ratio).toInt(),
                (img.height * ratio).toInt(),
                BufferedImage.TYPE_INT_ARGB  // 保留 Alpha 通道，避免透明区域变黑
            ).apply {
                val g = createGraphics()
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
                )
                g.drawImage(img, 0, 0, width, height, null)
                g.dispose()
            }
        } else img
        // Encode to base64
        val baos = ByteArrayOutputStream()
        var encodedFormatName = formatName
        val writeOk = ImageIO.write(scaled, encodedFormatName, baos)
        // ImageIO.write() 失败时回退到 PNG 重试
        if (!writeOk || baos.size() == 0) {
            baos.reset()
            encodedFormatName = "png"
            val pngOk = ImageIO.write(scaled, encodedFormatName, baos)
            if (!pngOk || baos.size() == 0) {
                JOptionPane.showMessageDialog(
                    this,
                    "图片编码失败，不支持的图片格式。",
                    "编码失败",
                    JOptionPane.WARNING_MESSAGE
                )
                return false
            }
        }
        // 单张上限 5MB，对齐 docs/ui/chat.md §七
        if (baos.size() > 5 * 1024 * 1024) {
            val sizeMB = String.format("%.1f", baos.size() / (1024.0 * 1024.0))
            JOptionPane.showMessageDialog(
                this,
                "图片大小为 ${sizeMB}MB，超过单张上限 5MB，请压缩后再试。",
                "图片大小超限",
                JOptionPane.WARNING_MESSAGE
            )
            return false
        }
        val timestamp =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        // 如果因格式不支持回退到了 PNG，fileName 和 mimeType 也同步修正
        val actualMimeType = if (encodedFormatName == "png" && formatName != "png") "image/png" else mimeType
        val actualExtension = if (encodedFormatName == "png" && formatName != "png") "png" else extension
        val actualFileName = sourceFileName
            ?.substringBeforeLast('.', sourceFileName)
            ?.let { "$it.$actualExtension" }
            ?: "paste_$timestamp.$actualExtension"
        val imageRef = ImageRef(
            id = UUID.randomUUID().toString(),
            fileName = actualFileName,
            base64Data = Base64.getEncoder().encodeToString(baos.toByteArray()),
            mimeType = actualMimeType,
            width = scaled.width,
            height = scaled.height,
            sizeBytes = baos.size().toLong()
        )
        imageRefs.add(imageRef)
        return true
    }

    /** 检测图片的原始格式（PNG/JPEG/GIF/WebP/BMP），默认 PNG */
    private fun detectImageFormat(fileName: String?): Triple<String, String, String> {
        // BMP 不在 Anthropic API 支持列表中，不列入 supportedFormats，自动回退为 PNG
        val supportedFormats = listOf(
            Triple("png", "png", "image/png"),
            Triple("jpeg", "jpg", "image/jpeg"),
            Triple("jpg", "jpg", "image/jpeg"),
            Triple("gif", "gif", "image/gif"),
            Triple("webp", "webp", "image/webp"),
        )
        val normalizedFileName = fileName?.lowercase() ?: ""
        for ((fmt, ext, mime) in supportedFormats) {
            if (normalizedFileName.endsWith(".$fmt")) {
                val writerNames = ImageIO.getWriterFormatNames()
                return if (writerNames.any { it.equals(fmt, ignoreCase = true) }) {
                    Triple(fmt, ext, mime)
                } else {
                    // ImageIO 不支持该格式写入（如无 WebP 插件），统一回退为 PNG
                    Triple("png", "png", "image/png")
                }
            }
        }
        return Triple("png", "png", "image/png")
    }

    /** 添加 @file 手动引用，构建 FileRef 模型对象，对齐 docs/ui/chat.md §十二 InputState.manualRefs */
    private fun addFileReference(ref: String) {
        val path = ref.removePrefix("@")
        // 去重：同一文件路径不重复添加
        if (manualFileRefs.any { it.path == path }) return
        manualFileRefs.add(FileRef(path = path, lines = null, content = null))
        insertAtCaret("")
        refreshTags()
    }

    /** 设置/更新编辑器选中代码引用，对齐 docs/ui/chat.md §七 IDE 代码选中即时引用 + §十二 InputState.selectionRef */
    fun setSelectionReference(
        fileName: String?,
        lineRange: String? = null,
        content: String? = null
    ) {
        selectionFileRef = if (fileName != null && fileName.isNotBlank()) {
            FileRef(path = fileName, lines = lineRange, content = content)
        } else {
            null
        }
        refreshTags()
    }

    /** 取消选中时清除选中引用，对齐 docs/ui/chat.md §十二 clearSelectionRef() */
    fun clearSelectionReference() {
        selectionFileRef = null
        refreshTags()
    }

    private fun refreshTags() {
        tagsPanel.removeAll()
        buildDisplayTags().forEach { tagItem -> tagsPanel.add(createAttachmentChip(tagItem)) }
        tagsPanel.revalidate(); tagsPanel.repaint()
    }

    /** 文件与图片共用同一个单行芯片，只在名称、提示和打开目标上区分。 */
    private fun createAttachmentChip(tagItem: TagItem): JComponent {
        val displayName = when (tagItem) {
            is TagItem.FileTag -> tagItem.displayName
            is TagItem.ImageTag -> "📎 ${compactFileName(tagItem.ref.fileName)}"
        }
        val tooltip = when (tagItem) {
            is TagItem.FileTag -> tagItem.ref.path
            is TagItem.ImageTag -> with(tagItem.ref) {
                "$fileName · ${imageFormatLabel(mimeType)} · ${formatFileSize(sizeBytes)} · ${width}×${height}"
            }
        }
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0)).apply {
            isOpaque = true
            background = AppColors.tagBg
            border = BorderFactory.createLineBorder(AppColors.tagBorder)
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            accessibleContext.accessibleDescription = "打开附件 $displayName"
        }

        val openListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) openAttachment(tagItem)
            }
        }
        chip.addMouseListener(openListener)
        chip.add(JLabel(displayName).apply {
            foreground = AppColors.gray900
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(openListener)
        })

        if (tagItem.closable) {
            chip.add(JButton("×").apply {
                accessibleContext.accessibleDescription = "移除附件 $displayName"
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = AppColors.textSecondary
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                margin = Insets(0, 2, 0, 2)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { removeAttachment(tagItem) }
            })
        }

        return chip
    }

    private fun removeAttachment(tagItem: TagItem) {
        when (tagItem) {
            is TagItem.FileTag -> {
                manualFileRefs.remove(tagItem.ref)
                if (selectionFileRef == tagItem.ref) selectionFileRef = null
            }

            is TagItem.ImageTag -> {
                imageRefs.remove(tagItem.ref)
                deleteImagePreview(tagItem.ref.id)
            }
        }
        refreshTags()
    }

    private fun openAttachment(tagItem: TagItem) {
        when (tagItem) {
            is TagItem.FileTag -> openFileReference(tagItem.ref)
            is TagItem.ImageTag -> openImageReference(tagItem.ref)
        }
    }

    /** 文件引用已经是项目相对路径，解析为本地 VirtualFile 后交给 IDEA 编辑器。 */
    private fun openFileReference(fileRef: FileRef) {
        fileOpenHandler?.let { handler ->
            handler(fileRef.path)
            return
        }
        val project = projectRef ?: return
        val absolutePath = runCatching {
            val path = Path.of(fileRef.path)
            if (path.isAbsolute) path else Path.of(project.basePath ?: return).resolve(path).normalize()
        }.getOrElse {
            AppLogger.warn("Open attachment failed: ${it.message}")
            return
        }
        openVirtualFile(project, absolutePath)
    }

    /** 图片仅在首次点击时解码并写入临时文件，避免粘贴阶段增加磁盘 I/O。 */
    private fun openImageReference(imageRef: ImageRef) {
        val testHandler = fileOpenHandler
        if (testHandler != null) {
            getOrCreateImagePreviewPath(imageRef)?.let { testHandler(it.toString()) }
            return
        }
        val project = projectRef ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val previewPath = getOrCreateImagePreviewPath(imageRef) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                val imageStillAttached = imageRefs.any { it.id == imageRef.id }
                if (!disposed && imageStillAttached && !project.isDisposed) {
                    openVirtualFile(project, previewPath)
                } else {
                    // 预览生成期间图片可能已删除或输入区域已销毁，不能再打开残留文件。
                    deleteImagePreview(imageRef.id)
                }
            }
        }
    }

    private fun getOrCreateImagePreviewPath(imageRef: ImageRef): Path? {
        synchronized(imagePreviewPaths) {
            if (disposed) return null
            imagePreviewPaths[imageRef.id]?.takeIf(Files::exists)?.let { return it }
            return runCatching {
                val imageBytes = Base64.getDecoder().decode(imageRef.base64Data)
                val previewDirectory = Files.createTempDirectory("code-assistant-image-")
                val safeFileName = imagePreviewFileName(imageRef)
                val previewPath = previewDirectory.resolve(safeFileName)
                try {
                    Files.write(previewPath, imageBytes)
                } catch (exception: Exception) {
                    // 写入可能已经创建了部分文件；清理失败不能覆盖原始写入异常。
                    runCatching { Files.deleteIfExists(previewPath) }
                    runCatching { Files.deleteIfExists(previewDirectory) }
                    throw exception
                }
                imagePreviewPaths[imageRef.id] = previewPath
                previewPath
            }.getOrElse {
                AppLogger.warn("Create image preview failed: ${it.message}")
                null
            }
        }
    }

    /** 清理跨平台非法文件名，并强制使用实际 MIME 对应扩展名，确保 IDEA 选择正确的预览器。 */
    private fun imagePreviewFileName(imageRef: ImageRef): String {
        val sourceName = imageRef.fileName.substringAfterLast('/').substringAfterLast('\\')
        val sourceBaseName = sourceName.substringBeforeLast('.', sourceName)
        val sanitizedBaseName = sourceBaseName
            .map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
            }
            .joinToString("")
            .trim('_')
            .take(80)
            .ifBlank { "preview" }
        val windowsReservedNames = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )
        val portableBaseName = if (sanitizedBaseName.uppercase() in windowsReservedNames) {
            "image_$sanitizedBaseName"
        } else {
            sanitizedBaseName
        }
        return "$portableBaseName.${imageExtension(imageRef.mimeType)}"
    }

    private fun deleteImagePreview(imageId: String) {
        val previewPath = synchronized(imagePreviewPaths) { imagePreviewPaths.remove(imageId) } ?: return
        deleteImagePreviewPath(previewPath)
    }

    private fun clearImagePreviews() {
        val previewPaths = synchronized(imagePreviewPaths) {
            imagePreviewPaths.values.toList().also { imagePreviewPaths.clear() }
        }
        previewPaths.forEach(::deleteImagePreviewPath)
    }

    private fun deleteImagePreviewPath(previewPath: Path) {
        runCatching {
            Files.deleteIfExists(previewPath)
            previewPath.parent?.let(Files::deleteIfExists)
        }.onFailure {
            // Windows 文件占用或杀毒软件短暂锁定时，至少在 IDE 退出阶段再次清理。
            previewPath.parent?.toFile()?.deleteOnExit()
            previewPath.toFile().deleteOnExit()
            AppLogger.warn("Delete image preview failed: ${it.message}")
        }
    }

    private fun openVirtualFile(project: com.intellij.openapi.project.Project, path: Path) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun imageExtension(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun imageFormatLabel(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "JPEG"
        "image/gif" -> "GIF"
        "image/webp" -> "WEBP"
        else -> "PNG"
    }

    /** 长文件名保留扩展名并截断，避免单个芯片占满输入区；完整名称仍可通过 tooltip 查看。 */
    private fun compactFileName(fileName: String, maxLength: Int = 32): String {
        if (fileName.length <= maxLength) return fileName
        val extension = fileName.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        val prefixLength = (maxLength - suffix.length - 1).coerceAtLeast(8)
        return fileName.take(prefixLength) + "…" + suffix
    }

    private fun formatFileSize(sizeBytes: Long): String = when {
        sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))
        sizeBytes >= 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
        else -> "$sizeBytes B"
    }

    private var mode = "Agent" // ponytail: default Agent mode

    private fun doSend() {
        // 对齐 docs/ui/components.md §3.1 Loading 状态：不可点击/不可发送
        if (!sendButton.isEnabled) return
        val text = if (isTextAreaEmpty()) "" else textArea.text.trim()
        val hasTags =
            manualFileRefs.isNotEmpty() || selectionFileRef != null || imageRefs.isNotEmpty()
        if (text.isNotEmpty() || hasTags) {
            val prefix = when (mode) {
                "Chat" -> ""; "Plan" -> "/plan "; else -> ""
            }
            // 构建 @file 引用文本部分（保持兼容现有 onSend 签名）
            val fileRefText = manualFileRefs.joinToString(" ") { "@${it.path}" }
            val message = listOf(fileRefText, text)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val images = imageRefs.toList()
            if (onSendWithImages != null) {
                onSendWithImages.invoke(prefix + message, images)
            } else {
                onSend(prefix + message)
            }
            textArea.text = ""
            manualFileRefs.clear()
            selectionFileRef = null
            imageRefs.clear()
            clearImagePreviews()
            refreshTags()
        }
    }

    /**
     * 对齐 docs/ui/components.md §3.1 Primary Button Loading 状态：
     * loading=true 时按钮 bg=#3B82F6, text="", 文字位置显示旋转 spinner, 不可点击；
     * loading=false 时恢复 Default 状态。
     */
    fun setLoading(loading: Boolean) {
        // ponytail: stopButton 显示 ⏹ 足够清晰，去除无效 spinner 动画
        loadingAnimator?.stop()
        loadingAnimator = null
        if (loading) {
            sendButton.isVisible = false
            sendButton.isEnabled = false
            stopButton.isVisible = true
        } else {
            sendButton.isVisible = true
            sendButton.isEnabled = true
            stopButton.isVisible = false
        }
        textArea.isEnabled = !loading
        addFileButton.isEnabled = !loading
    }

    /**
     * 对齐 docs/ui/components.md §3.2 Disabled 状态：bg=#F3F4F6, cursor=default。
     * Agent 执行中不可输入，设置 textArea 背景色和光标为不可用状态，
     * 并更新 JScrollPane border 以跟随 input 边框（对齐 §3.2 Overflow）。
     */
    fun setInputEnabled(enabled: Boolean) {
        setLoading(!enabled)
        if (enabled) {
            textArea.background = AppColors.pageBg
            textArea.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        } else {
            textArea.background = AppColors.hoverBg
            textArea.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }
        // 对齐 docs/ui/components.md §3.2 Overflow "JScrollPane border 跟随 input 边框"
        updateInputBorder()
    }

    override fun dispose() {
        disposed = true
        ideShortcutActions.forEach { it.unregisterCustomShortcutSet(textArea) }
        ideShortcutActions.clear()
        loadingAnimator?.stop()
        loadingAnimator = null
        errorRecoveryTimer?.stop()
        errorRecoveryTimer = null
        hidePopup()
        projectRef = null
        cachedFiles = null
        clearImagePreviews()
    }
}
