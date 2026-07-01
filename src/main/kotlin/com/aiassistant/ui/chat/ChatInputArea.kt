package com.aiassistant.ui.chat

import com.aiassistant.agent.FileRef
import com.aiassistant.agent.ImageRef
import com.aiassistant.ui.AppColors
import com.aiassistant.skills.SkillManager
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
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
    /** 输入内容变化回调（文本内容），用于 ChatViewModel 更新 InputState.tokenCount */
    private val onInputChanged: ((text: String) -> Unit)? = null,
    /** 获取上一条用户消息文本的回调，用于 ↑ 在空输入框时填充历史消息（对齐 docs/ui/pages.md §十） */
    private val onFillPreviousMessage: (() -> String?)? = null
) : JPanel(BorderLayout()) {

    private val textArea = JTextArea(2, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = font.deriveFont(11f)
        background = AppColors.pageBg
    }
    private val popup = JPopupMenu()
    private val popupMenuItems = mutableListOf<JMenuItem>()

    /** Tags 行（FlowLayout，文件+图片混合排列，位于输入框上方），对齐 docs/ui/chat.md §七 + §十四 tagsRow */
    private val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = true; background = AppColors.pageBg
    }

    /** 项目文件缓存，后台预加载，避免 EDT 访问 PSI index */
    @Volatile
    private var cachedFiles: List<ProjectFileEntry>? = null

    /** 手动 @file 引用（可多个），对齐 docs/ui/chat.md §十二 InputState.manualRefs */
    private val manualFileRefs = mutableListOf<FileRef>()

    /** 编辑器选中代码引用（仅一个），对齐 docs/ui/chat.md §十二 InputState.selectionRef */
    private var selectionFileRef: FileRef? = null

    /** 粘贴的图片引用（可多个），对齐 docs/ui/chat.md §十二 InputState.images */
    private val imageRefs = mutableListOf<ImageRef>()
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
        BorderFactory.createEmptyBorder()                       // 无可见边框
    private val focusInputBorder =
        BorderFactory.createEmptyBorder()                       // focus 无边框变化
    private val disabledInputBorder =
        BorderFactory.createLineBorder(AppColors.hoverBg)       // disabled 时浅灰背景

    /**
     * 对齐 docs/ui/components.md §3.2 Overflow：高度自适应 2-10 行。
     * 根据文本内容行数动态调整 JTextArea 行高，超过 10 行时 JScrollPane 垂直滚动条自动出现。
     */
    private fun adjustTextAreaRows() {
        val fontMetrics = textArea.getFontMetrics(textArea.font)
        val textWidth = textArea.width
        if (textWidth <= 0) return  // 组件尚未布局，跳过

        val text = textArea.text
        val lines = text.split("\n")
        // 计算实际所需行数（含自动换行）
        val totalLines = lines.sumOf { line ->
            if (line.isEmpty()) 1
            else maxOf(1, (fontMetrics.stringWidth(line) + textWidth - 1) / textWidth)
        }.coerceIn(2, 8)

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
        projectRef = project
        // ponytail: 后台预加载文件列表，避免 EDT 访问 PSI index
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            cachedFiles = try {
                com.intellij.openapi.application.ReadAction.compute<List<ProjectFileEntry>, Throwable> {
                    getProjectFiles("")
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
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
     * 上限 50 个文件，对齐 docs/agent.md 已知限制。
     */
    private fun getProjectFiles(filter: String): List<ProjectFileEntry> {
        val project = projectRef ?: return emptyList()
        val basePath = project.basePath ?: return emptyList()
        return try {
            val allFilenames = com.intellij.psi.search.FilenameIndex.getAllFilenames(project)
            val matched = allFilenames
                .filter { it.contains(filter, ignoreCase = true) }
                .take(50)

            // 按文件名查找 VirtualFile 获取完整路径
            matched.mapNotNull { fileName ->
                val vFiles = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
                    project,
                    fileName,
                    com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                )
                val vFile = vFiles.firstOrNull() ?: return@mapNotNull null
                val absPath = vFile.path
                // 计算相对路径（去掉项目根目录前缀）
                val relPath = if (absPath.startsWith(basePath)) {
                    absPath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
                } else {
                    fileName // 回退：无法计算相对路径时只用文件名
                }
                ProjectFileEntry(fileName = fileName, relativePath = relPath)
            }
        } catch (_: Exception) {
            emptyList()
        }
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
            override val displayName: String get() = "🖼 ${ref.fileName}"
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
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Popup navigation (handled before Enter/Escape for popup mode)
                if (popup.isVisible) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> {
                            selectPopupItem(1); e.consume(); return
                        }

                        KeyEvent.VK_UP -> {
                            selectPopupItem(-1); e.consume(); return
                        }

                        KeyEvent.VK_ENTER -> {
                            clickSelectedPopupItem(); e.consume(); return
                        }
                    }
                }
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        if (!e.isShiftDown) {
                            e.consume(); doSend()
                        }
                    }

                    KeyEvent.VK_UP -> {
                        // 对齐 docs/ui/pages.md §十 快捷键：↑（在空输入框）→ 填充上一条消息
                        if (isTextAreaEmpty()) {
                            onFillPreviousMessage?.invoke()?.let { prevMsg ->
                                textArea.text = prevMsg
                                textArea.caretPosition = prevMsg.length
                                // 移除 placeholder 状态，恢复为正常文本颜色
                                if (textArea.foreground == AppColors.textSecondary) {
                                    textArea.foreground = defaultFg
                                }
                            }
                            e.consume()
                        }
                    }

                    KeyEvent.VK_ESCAPE -> {
                        // 对齐 docs/ui/pages.md §十 快捷键 + docs/ui/chat.md §八：Escape 仅关闭 Popup
                        // 不中断 Agent、LLM、流式生成或工具执行
                        if (popup.isVisible) popup.isVisible = false
                    }
                }
                // Ctrl+Shift+N = new session
                if (e.keyCode == KeyEvent.VK_N && (e.isControlDown || e.isMetaDown) && e.isShiftDown) {
                    e.consume(); onNewSession?.invoke()
                }
                // Ctrl/Cmd+V with image in clipboard → paste as image
                if (e.keyCode == KeyEvent.VK_V && (e.isMetaDown || e.isControlDown)) {
                    pasteImage()
                }
            }
        })

        // @file /command detection
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = checkTriggers()
            override fun removeUpdate(e: DocumentEvent?) = checkTriggers()
            override fun changedUpdate(e: DocumentEvent?) = checkTriggers()
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

    private fun checkTriggers() {
        val text = textArea.text
        val caret = textArea.caretPosition
        val before = text.substring(0, caret.coerceAtMost(text.length))

        // @file trigger
        val fileMatch = Regex("""(?:^|\s)@(\S*)$""").find(before)
        if (fileMatch != null) {
            val filter = fileMatch.groupValues[1]
            // ponytail: 使用缓存文件列表在 EDT 同步过滤+显示，避免 PSI 慢操作
            val files = cachedFiles ?: emptyList()
            showPopup(filter, files)
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
        popup.isVisible = false
    }

    private fun commandSuggestions(): List<String> {
        val builtIns = listOf("/plan", "/clear")
        val project = projectRef ?: return builtIns
        return (builtIns + SkillManager(project).enabledSlashCommands()).distinct()
    }

    /** 显示 @file Popup（按子目录分组），对齐 docs/ui/chat.md §八 */
    private fun showPopup(filter: String, fileEntries: List<ProjectFileEntry>) {
        val filtered = fileEntries
            .filter { it.fileName.contains(filter, ignoreCase = true) }
            .take(50)
        if (filtered.isEmpty()) {
            popup.isVisible = false; return
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
                        popup.isVisible = false
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
        popup.removeAll()
        popup.add(scrollPane)

        // ponytail: textArea 在 JScrollPane viewport 内，popup.show(textArea, ...) 会被裁剪
        // 改为相对 inputScrollPane 弹出，确保完全可见
        popup.show(inputScrollPane, 0, inputScrollPane.height)
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
        popup.removeAll()
        val filtered = items.filter { it.contains(filter, ignoreCase = true) }.take(8)
        if (filtered.isEmpty()) {
            popup.isVisible = false; return
        }

        popupMenuItems.clear()
        val menuPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        filtered.forEach { item ->
            val mi = JMenuItem(item).apply {
                font = font.deriveFont(12f)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addActionListener {
                    insertAtCaret(item + " ")
                    popup.isVisible = false
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
            border = null
            preferredSize = Dimension(
                menuPanel.preferredSize.width + verticalScrollBar.preferredSize.width,
                popupHeight
            )
        }
        popup.add(scrollPane)

        // ponytail: textArea 在 JScrollPane viewport 内，popup.show(textArea, ...) 会被裁剪
        // 改为相对 inputScrollPane 弹出，确保完全可见
        popup.show(inputScrollPane, 0, inputScrollPane.height)
    }

    private var popupIndex = -1
    private fun selectPopupItem(direction: Int) {
        val count = popupMenuItems.size
        if (count == 0) return
        // 对齐 docs/ui/chat.md §八：↑↓ 移动高亮（循环）
        popupIndex = ((popupIndex + direction) % count + count) % count
        for (i in 0 until count) {
            popupMenuItems[i].isArmed = (i == popupIndex)
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
    private fun pasteImage() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return
            // 单次粘贴最多 5 张，对齐 docs/ui/chat.md §七
            if (imageRefs.size >= 5) {
                JOptionPane.showMessageDialog(
                    this,
                    "单次粘贴最多 5 张图片，请先移除部分已粘贴的图片后再试。",
                    "图片数量超限",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            val img = clipboard.getData(DataFlavor.imageFlavor) as BufferedImage
            // Scale down（长边 max 2048px，保持比例），对齐 docs/ui/chat.md §七
            val maxDim = 2048
            val scaled = if (img.width > maxDim || img.height > maxDim) {
                val ratio = maxDim.toDouble() / maxOf(img.width, img.height)
                BufferedImage(
                    (img.width * ratio).toInt(),
                    (img.height * ratio).toInt(),
                    BufferedImage.TYPE_INT_RGB
                ).apply {
                    graphics.drawImage(
                        img.getScaledInstance(width, height, Image.SCALE_SMOOTH),
                        0,
                        0,
                        null
                    )
                }
            } else img
            // 检测剪贴板中图片的原始格式，默认 PNG，对齐 docs/ui/chat.md §七 支持格式：PNG、JPEG、GIF、WebP、BMP
            val (formatName, extension, mimeType) = detectImageFormat(clipboard)
            // Encode to base64
            val baos = ByteArrayOutputStream()
            ImageIO.write(scaled, formatName, baos)
            // 单张上限 5MB，对齐 docs/ui/chat.md §七
            if (baos.size() > 5 * 1024 * 1024) {
                val sizeMB = String.format("%.1f", baos.size() / (1024.0 * 1024.0))
                JOptionPane.showMessageDialog(
                    this,
                    "图片大小为 ${sizeMB}MB，超过单张上限 5MB，请压缩后再试。",
                    "图片大小超限",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            val timestamp =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "paste_$timestamp.$extension"
            // 生成 48x48 缩略图，对齐 docs/ui/chat.md §七「48x48 缩略图 tag」
            val thumbnail = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB).apply {
                val g = graphics as Graphics2D
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                g.drawImage(scaled.getScaledInstance(48, 48, Image.SCALE_SMOOTH), 0, 0, null)
                g.dispose()
            }
            val imageRef = ImageRef(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                base64Data = Base64.getEncoder().encodeToString(baos.toByteArray()),
                mimeType = mimeType,
                thumbnail = thumbnail,
                width = scaled.width,
                height = scaled.height,
                sizeBytes = baos.size().toLong()
            )
            imageRefs.add(imageRef)
            refreshTags()
        } catch (_: Exception) { /* clipboard access error */
        }
    }

    /** 检测剪贴板中图片的原始格式（PNG/JPEG/GIF/WebP/BMP），默认 PNG */
    private fun detectImageFormat(clipboard: java.awt.datatransfer.Clipboard): Triple<String, String, String> {
        val supportedFormats = listOf(
            Triple("png", "png", "image/png"),
            Triple("jpeg", "jpg", "image/jpeg"),
            Triple("jpg", "jpg", "image/jpeg"),
            Triple("gif", "gif", "image/gif"),
            Triple("webp", "webp", "image/webp"),
            Triple("bmp", "bmp", "image/bmp"),
        )
        // 尝试从 DataFlavor.javaFileListFlavor 获取文件名推断格式
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                val fileName = files?.firstOrNull()?.name?.lowercase() ?: ""
                for ((fmt, ext, mime) in supportedFormats) {
                    if (fileName.endsWith(".$fmt")) {
                        // 验证 ImageIO 是否能写出该格式，否则回退到 PNG
                        val writerNames = ImageIO.getWriterFormatNames()
                        return if (writerNames.any { it.equals(fmt, ignoreCase = true) }) {
                            Triple(fmt, ext, mime)
                        } else {
                            // 格式不受 ImageIO 支持（如无 WebP 插件），编码为 PNG 但保留原始 MIME 扩展名标识
                            Triple("png", ext, mime)
                        }
                    }
                }
            }
        } catch (_: Exception) { /* fall through to default */
        }
        // 默认回退到 PNG
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
        val displayTags = buildDisplayTags()
        displayTags.forEach { tagItem ->
            val isImage = tagItem is TagItem.ImageTag
            val t = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0)).apply {
                isOpaque = true
                // 对齐 ui-prototype: 文件 tag bg=#EFF6FF border=#BFDBFE, 图片 tag bg=#F3F4F6 border=#D1D5DB
                background = if (isImage) AppColors.hoverBg else AppColors.tagBg
                border =
                    if (isImage) BorderFactory.createLineBorder(AppColors.gray300) else BorderFactory.createLineBorder(
                        AppColors.tagBorder
                    )
            }
            // 对齐 docs/ui/chat.md §七「48x48 缩略图 tag」：ImageTag 有缩略图时展示缩略图
            if (tagItem is TagItem.ImageTag && tagItem.ref.thumbnail != null) {
                val thumbLabel = JLabel(ImageIcon(tagItem.ref.thumbnail)).apply {
                    toolTipText = tagItem.displayName
                }
                t.add(thumbLabel)
            } else {
                t.add(JLabel(tagItem.displayName))
            }
            // 可关闭的 tag 显示 ✕ 按钮
            if (tagItem.closable) {
                val x = JLabel(" ✕").apply {
                    foreground = AppColors.textSecondary; cursor =
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            when (tagItem) {
                                is TagItem.FileTag -> {
                                    manualFileRefs.remove(tagItem.ref)
                                    if (selectionFileRef == tagItem.ref) selectionFileRef = null
                                }

                                is TagItem.ImageTag -> imageRefs.remove(tagItem.ref)
                            }
                            refreshTags()
                        }
                    })
                }
                t.add(x)
            }
            tagsPanel.add(t)
        }
        tagsPanel.revalidate(); tagsPanel.repaint()
    }

    private var mode = "Agent" // ponytail: default Agent mode

    private fun doSend() {
        // 对齐 docs/ui/components.md §3.1 Loading 状态：不可点击/不可发送
        if (!sendButton.isEnabled) return
        val text = textArea.text.trim()
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
            onSend(prefix + message)
            textArea.text = ""
            manualFileRefs.clear()
            selectionFileRef = null
            imageRefs.clear()
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

}
