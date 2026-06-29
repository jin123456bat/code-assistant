package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import javax.swing.*

/**
 * 选项组件 — 当 Agent 需要用户做选择时，以选项列表形式内嵌在对话流中呈现。
 *
 * 对齐文档 docs/ui/chat.md §九：
 * - 提问标题 + 选项行（A/B/C）+ 操作栏
 * - 单选模式：圆圈选中态 [蓝左边框 + 蓝实心圆圈]
 * - 多选模式：复选框选中态
 * - 操作栏：[确认选择] 按钮 + 已选数量提示
 * - 阻塞 Agent Loop 等待用户操作（CountDownLatch）
 */
class OptionsBlock : JPanel(BorderLayout()) {

    /** 单个选项的数据模型 */
    data class OptionItem(
        val label: String,
        val description: String
    )

    /** 用户选择结果 */
    data class SelectionResult(
        val selectedLabels: List<String>
    )

    /** 单个选项行的 UI 组件 */
    private inner class OptionRow(
        val item: OptionItem,
        private val isMultiSelect: Boolean
    ) : JPanel(BorderLayout()) {

        private val indicator = JLabel()
        private val labelDescPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val labelArea = JTextArea().apply {
            text = "${item.label}  ${item.description.split("\n").firstOrNull() ?: ""}"
            font = font.deriveFont(13f)
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = AppColors.gray700
            border = BorderFactory.createEmptyBorder()
        }
        private val descArea = JTextArea().apply {
            text = item.description
            font = font.deriveFont(12f)
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = AppColors.textSecondary
            border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
            // 如果 description 只有一行（已在 label 中显示），则隐藏
            isVisible = item.description.contains("\n")
        }

        var isSelected = false
            private set

        init {
            isOpaque = true
            background = AppColors.cardBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(10, 16, 10, 12)
            )

            indicator.apply {
                font = font.deriveFont(Font.PLAIN, 14f)
                text = if (isMultiSelect) "☐" else "○"
                foreground = AppColors.gray400
                border = BorderFactory.createEmptyBorder(0, 0, 0, 12)
            }

            labelArea.apply {
                // 将标题和简短描述分开：首行为标题，后续为描述
                val lines = item.description.split("\n")
                if (lines.size == 1) {
                    text = "<html><b>${item.label}</b>&nbsp;&nbsp;${escapeHtml(lines[0])}</html>"
                    descArea.isVisible = false
                } else {
                    text = "<html><b>${item.label}</b>&nbsp;&nbsp;${escapeHtml(lines[0])}</html>"
                    descArea.text = lines.drop(1).joinToString(" ")
                    descArea.isVisible = true
                }
            }

            labelDescPanel.add(labelArea)
            labelDescPanel.add(descArea)

            add(indicator, BorderLayout.WEST)
            add(labelDescPanel, BorderLayout.CENTER)

            // 点击整行选中/取消
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled) {
                        if (isMultiSelect) {
                            toggle()
                        } else {
                            // 单选模式：通知父组件切换
                            onOptionSelected(this@OptionRow)
                        }
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    if (isEnabled && !isSelected) {
                        background = AppColors.hoverBg
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (!isSelected) {
                        background = AppColors.cardBg
                    }
                }
            })

            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        fun toggle() {
            isSelected = !isSelected
            updateAppearance()
        }

        fun setSelected(selected: Boolean) {
            isSelected = selected
            updateAppearance()
        }

        private fun updateAppearance() {
            if (isSelected) {
                indicator.text = if (isMultiSelect) "☑" else "●"
                indicator.foreground = AppColors.primary
                background = AppColors.primaryLight
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 1, 0, AppColors.primary),
                    BorderFactory.createEmptyBorder(10, 13, 10, 12)
                )
                labelArea.foreground = AppColors.gray900
            } else {
                indicator.text = if (isMultiSelect) "☐" else "○"
                indicator.foreground = AppColors.gray400
                background = AppColors.cardBg
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                    BorderFactory.createEmptyBorder(10, 16, 10, 12)
                )
                labelArea.foreground = AppColors.gray700
            }
            revalidate()
            repaint()
        }
    }

    // ── OptionsBlock 主体 ──
    private val questionLabel = JLabel()
    private val optionsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val actionBar = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 8))
    private val confirmButton =
        JButton("确认选择").apply { accessibleContext.accessibleDescription = "确认当前选择" }
    private val selectionHint = JLabel()

    private val optionRows = mutableListOf<OptionRow>()
    private var isMultiSelect = false
    private var onOptionSelected: ((OptionRow) -> Unit) = {}
    private var onConfirm: ((List<String>) -> Unit)? = null

    /** CountDownLatch 用于阻塞 Agent Loop 等待用户选择 */
    private var latch: CountDownLatch? = null

    init {
        isOpaque = true
        background = AppColors.cardBg
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.primary),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )

        // 标题
        questionLabel.apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = AppColors.gray900
            border = BorderFactory.createEmptyBorder(12, 16, 8, 16)
        }
        add(questionLabel, BorderLayout.NORTH)

        // 选项列表
        add(optionsContainer, BorderLayout.CENTER)

        // 操作栏
        actionBar.apply {
            isOpaque = true
            background = AppColors.cardBg
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, AppColors.border)
        }

        confirmButton.apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = Color.WHITE
            background = AppColors.primary
            isOpaque = true
            isContentAreaFilled = true
            border = BorderFactory.createEmptyBorder(6, 16, 6, 16)
            isFocusPainted = false
            addActionListener {
                val selected = optionRows.filter { it.isSelected }.map { it.item.label }
                latch?.countDown()
                onConfirm?.invoke(selected)
            }
        }

        selectionHint.apply {
            font = font.deriveFont(12f)
            foreground = AppColors.textSecondary
        }

        actionBar.add(selectionHint)
        actionBar.add(confirmButton)
        add(actionBar, BorderLayout.SOUTH)
    }

    /**
     * 设置问题与选项列表。
     *
     * @param question 提问标题文本
     * @param options 选项列表（A/B/C...）
     * @param multiSelect 是否允许多选
     * @param latch 用于阻塞 Agent Loop 的 CountDownLatch，用户确认后 countDown
     * @param onConfirm 用户确认后的回调，参数为选中的 label 列表
     */
    fun configure(
        question: String,
        options: List<OptionItem>,
        multiSelect: Boolean = false,
        latch: CountDownLatch? = null,
        onConfirm: ((List<String>) -> Unit)? = null
    ) {
        this.isMultiSelect = multiSelect
        this.latch = latch
        this.onConfirm = onConfirm

        questionLabel.text = question

        // 清除旧的选项行
        optionRows.clear()
        optionsContainer.removeAll()

        // 单选模式：点击一行时取消其他行的选中
        onOptionSelected = { row ->
            if (!multiSelect) {
                optionRows.forEach { it.setSelected(it == row) }
            } else {
                row.toggle()
            }
            updateSelectionHint()
        }

        // 创建选项行
        options.forEach { item ->
            val row = OptionRow(item, multiSelect)
            optionRows.add(row)
            optionsContainer.add(row)
        }

        updateSelectionHint()
        confirmButton.isEnabled = false
        optionsContainer.revalidate()
        optionsContainer.repaint()
    }

    /**
     * 获取当前选中的选项标签列表。
     */
    fun getSelectedLabels(): List<String> {
        return optionRows.filter { it.isSelected }.map { it.item.label }
    }

    /**
     * 设置确认按钮的回调。
     */
    fun setOnConfirm(callback: (List<String>) -> Unit) {
        this.onConfirm = callback
    }

    private fun updateSelectionHint() {
        val selected = optionRows.filter { it.isSelected }
        confirmButton.isEnabled = selected.isNotEmpty()
        if (selected.isEmpty()) {
            selectionHint.text = if (isMultiSelect) "请选择（可多选）" else "请选择一个选项"
        } else {
            val labels = selected.joinToString("、") { it.item.label }
            selectionHint.text = "已选: $labels"
        }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
