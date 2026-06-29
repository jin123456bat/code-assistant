package com.aiassistant.agent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

/**
 * 多选问题对话框。
 * 文档要求：MODAL dialog，阻塞 Agent Loop 等待用户操作。
 * 使用 DialogWrapper 实现模态对话框，支持多选选项。
 */
internal class MultiSelectQuestionDialog(
    project: Project?,
    private val header: String,
    private val question: String,
    private val options: List<OptionData>
) : DialogWrapper(project, true) {  // true = modal

    private val checkBoxes = mutableListOf<JCheckBox>()
    private var selectedLabels: List<String> = emptyList()

    init {
        title = header
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10)).apply {
            border = JBUI.Borders.empty(10)
        }

        // 问题描述
        val questionLabel = JBLabel("<html><b>$question</b></html>")
        panel.add(questionLabel, BorderLayout.NORTH)

        // 选项列表
        val optionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        options.forEach { option ->
            val checkBox = JCheckBox().apply {
                val text = buildString {
                    append("<html><b>${option.label}</b>")
                    if (option.description.isNotBlank()) {
                        append("<br><span style='color:gray; font-size:smaller;'>&nbsp;&nbsp;&nbsp;&nbsp;${option.description}</span>")
                    }
                    append("</html>")
                }
                setText(text)
                isFocusPainted = false
            }
            checkBoxes.add(checkBox)
            optionsPanel.add(checkBox)
        }

        val scrollPane = JBScrollPane(optionsPanel).apply {
            border = JBUI.Borders.empty()
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        selectedLabels = checkBoxes
            .filter { it.isSelected }
            .mapIndexed { index, _ -> options[index].label }
        super.doOKAction()
    }

    override fun doCancelAction() {
        selectedLabels = emptyList()
        super.doCancelAction()
    }

    /**
     * 显示对话框并返回用户选择的标签列表。
     * @return 用户选中的选项 label 列表，取消时返回空列表
     */
    fun showAndWait(): List<String> {
        val ok = showAndGet()
        return if (ok) selectedLabels else emptyList()
    }
}

/** AskUserQuestion 解析后的选项数据（对齐 docs/agent/tools.md §六） */
data class OptionData(
    val label: String,
    val description: String
)
