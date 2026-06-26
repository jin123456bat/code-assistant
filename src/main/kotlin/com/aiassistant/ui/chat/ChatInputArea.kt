package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.skills.SkillManager
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatInputArea(
    private val onSend: (String) -> Unit,
    private val onStop: (() -> Unit)? = null,
    private val onNewSession: (() -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val textArea = JTextArea(3, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = font.deriveFont(13f)
    }
    private val popup = JPopupMenu()
    private val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
    private val tags = mutableListOf<String>()
    private val sendButton = JButton("发送").apply { addActionListener { doSend() } }

    private var projectRef: com.intellij.openapi.project.Project? = null

    fun setProject(project: com.intellij.openapi.project.Project) {
        projectRef = project
    }

    private fun getProjectFiles(filter: String): List<String> {
        val project = projectRef ?: return emptyList()
        return try {
            com.intellij.psi.search.FilenameIndex.getAllFilenames(project)
                .filter { it.contains(filter, ignoreCase = true) }
                .take(8)
        } catch (_: Exception) {
            emptyList()
        }
    }

    init {
        val scrollPane = JScrollPane(textArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = BorderFactory.createEmptyBorder()
        }
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

                    KeyEvent.VK_ESCAPE -> {
                        if (popup.isVisible) popup.isVisible = false
                        else onStop?.invoke()
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

        val topPanel = JPanel(BorderLayout())
        topPanel.add(tagsPanel, BorderLayout.NORTH)
        topPanel.add(scrollPane, BorderLayout.CENTER)

        add(topPanel, BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)
    }

    private fun checkTriggers() {
        val text = textArea.text
        val caret = textArea.caretPosition
        val before = text.substring(0, caret.coerceAtMost(text.length))

        // @file trigger
        val fileMatch = Regex("""(?:^|\s)@(\S*)$""").find(before)
        if (fileMatch != null) {
            showPopup(
                fileMatch.groupValues[1],
                getProjectFiles(fileMatch.groupValues[1]).map { "@$it" }
            )
            return
        }
        // /command trigger
        val cmdMatch = Regex("""(?:^|\s)/(\S*)$""").find(before)
        if (cmdMatch != null) {
            showPopup(
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

    private fun showPopup(filter: String, items: List<String>) {
        popup.removeAll()
        val filtered = items.filter { it.contains(filter, ignoreCase = true) }.take(8)
        if (filtered.isEmpty()) {
            popup.isVisible = false; return
        }

        filtered.forEach { item ->
            val mi = JMenuItem(item).apply {
                font = font.deriveFont(12f)
                addActionListener {
                    insertAtCaret(item + " ")
                    popup.isVisible = false
                }
            }
            popup.add(mi)
        }

        try {
            val caretPos = textArea.getCaret().magicCaretPosition ?: Point(0, 0)
            popup.show(textArea, caretPos.x + 10, caretPos.y + 20)
        } catch (_: Exception) {
            popup.show(textArea, 10, 30)
        }
    }

    private var popupIndex = -1
    private fun selectPopupItem(direction: Int) {
        val count = popup.componentCount
        if (count == 0) return
        popupIndex = (popupIndex + direction).coerceIn(-1, count - 1)
        for (i in 0 until count) {
            (popup.getComponent(i) as? JMenuItem)?.isArmed = (i == popupIndex)
        }
    }

    private fun clickSelectedPopupItem() {
        if (popupIndex >= 0) {
            (popup.getComponent(popupIndex) as? JMenuItem)?.doClick()
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

    // ponytail: clipboard image paste
    private fun pasteImage() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                val img = clipboard.getData(DataFlavor.imageFlavor) as BufferedImage
                // Scale down
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
                // Encode to base64
                val baos = ByteArrayOutputStream()
                ImageIO.write(scaled, "png", baos)
                val sizeKB = baos.size() / 1024
                addTag("🖼 clipboard.png ${sizeKB}KB")
            }
        } catch (_: Exception) { /* clipboard access error */
        }
    }

    private fun addTag(label: String) {
        tags.add(label)
        refreshTags()
    }

    private fun refreshTags() {
        tagsPanel.removeAll()
        tags.forEach { tag ->
            val t = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                isOpaque = true; background = AppColors.tagBg; border =
                BorderFactory.createLineBorder(AppColors.tagBorder)
            }
            t.add(JLabel(tag))
            val x = JLabel(" ✕").apply {
                foreground = AppColors.textSecondary; cursor =
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        tags.remove(tag); refreshTags()
                    }
                })
            }
            t.add(x)
            tagsPanel.add(t)
        }
        tagsPanel.revalidate(); tagsPanel.repaint()
    }

    private var mode = "Agent" // ponytail: default Agent mode

    private fun doSend() {
        val text = textArea.text.trim()
        if (text.isNotEmpty() || tags.isNotEmpty()) {
            val prefix = when (mode) {
                "Chat" -> ""; "Plan" -> "/plan "; else -> ""
            }
            onSend(prefix + text)
            textArea.text = ""; tags.clear(); refreshTags()
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        textArea.isEnabled = enabled
        sendButton.isEnabled = enabled
    }
}
