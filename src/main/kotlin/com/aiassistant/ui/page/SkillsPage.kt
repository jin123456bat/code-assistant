package com.aiassistant.ui.page

import com.aiassistant.skills.SkillManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.*

class SkillsPage(project: Project) : JPanel(BorderLayout()) {

    private val manager = SkillManager(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val skillsDir = File(project.basePath, ".code-assistant/skills")

    init {
        // 标题行
        val titleRow = JPanel(BorderLayout())
        titleRow.add(
            JLabel("<html><b style='font-size:16px'>🎯 Skills</b></html>"),
            BorderLayout.WEST
        )
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        actions.add(JButton("📂 打开目录").apply {
            addActionListener { openSkillsDir() }
        })
        actions.add(JButton("➕ 新建 Skill").apply {
            addActionListener { createNewSkill() }
        })
        titleRow.add(actions, BorderLayout.EAST)
        titleRow.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)

        // 目录说明行
        val dimHex = AppColors.textTertiary.toHtmlColor()
        val dirRow = JPanel(BorderLayout())
        dirRow.add(
            JLabel("<html><span style='font-size:11px;color:$dimHex'>目录: .code-assistant/skills/ · 兼容 .claude/skills/ · .codex/skills/</span></html>"),
            BorderLayout.WEST
        )
        dirRow.border = BorderFactory.createEmptyBorder(0, 8, 4, 8)

        val topPanel = JPanel(BorderLayout())
        topPanel.add(titleRow, BorderLayout.NORTH)
        topPanel.add(dirRow, BorderLayout.SOUTH)
        add(topPanel, BorderLayout.NORTH)

        add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
        }, BorderLayout.CENTER)

        refreshList()
    }

    private fun openSkillsDir() {
        try {
            skillsDir.mkdirs()
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(skillsDir)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "无法打开目录: ${e.message}",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun createNewSkill() {
        val nameField = JTextField(20)
        val descField = JTextField(30)
        val cmdField = JTextField(15)
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("名称（英文，目录名）:"))
            add(nameField)
            add(Box.createVerticalStrut(8))
            add(JLabel("描述:"))
            add(descField)
            add(Box.createVerticalStrut(8))
            add(JLabel("命令（/ 前缀，如 review）:"))
            add(cmdField)
        }
        if (JOptionPane.showConfirmDialog(
                this,
                panel,
                "新建 Skill",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            ) != JOptionPane.OK_OPTION || nameField.text.isBlank()
        ) return
        val skillName = nameField.text.trim()
        val cmdRaw = cmdField.text.trim().ifBlank { skillName }
        val command = if (cmdRaw.startsWith("/")) cmdRaw else "/$cmdRaw"
        val description = descField.text.trim()
        val skillDir = File(skillsDir, skillName)
        if (skillDir.exists()) {
            JOptionPane.showMessageDialog(
                this,
                "Skill '$skillName' 已存在",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }
        try {
            skillDir.mkdirs()
            File(
                skillDir,
                "SKILL.md"
            ).writeText("---\nname: $skillName\ndescription: $description\ncommand: $command\n---\n\n")
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(skillDir)
            refreshList()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "创建 Skill 失败: ${e.message}",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    fun refreshList() {
        listContainer.removeAll()
        val skills = manager.loadSkills()
        if (skills.isEmpty()) {
            val dimHex = AppColors.textSecondary.toHtmlColor()
            listContainer.add(
                JLabel(
                    "<html><div style='text-align:center;padding:40px;color:$dimHex'>🎯<br><br>还没有 Skill<br><span style='font-size:11px'>在 skills 目录下创建 SKILL.md 来扩展 Agent 能力</span></div></html>",
                    SwingConstants.CENTER
                )
            )
        } else {
            skills.forEach { listContainer.add(renderCard(it)) }
        }
        listContainer.revalidate(); listContainer.repaint()
    }

    private fun renderCard(skill: SkillManager.Skill): JPanel {
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val greenHex = AppColors.success.toHtmlColor()
        val dimHex = AppColors.textSecondary.toHtmlColor()
        val errHex = AppColors.error.toHtmlColor()

        // Toggle + 详情按钮
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val toggle = JCheckBox().apply {
            isSelected = skill.enabled && !skill.hasMissingTools
            isEnabled = !skill.hasMissingTools
            toolTipText =
                if (skill.hasMissingTools) "缺少工具: ${skill.missingTools.joinToString(", ")}" else "启用/禁用此 Skill"
            addActionListener {
                if (isSelected) manager.enableSkill(skill.name) else manager.disableSkill(skill.name)
                refreshList()
            }
        }
        leftPanel.add(toggle)

        // 构建信息 HTML
        val sb = StringBuilder("<html>")
        val status =
            if (skill.enabled && !skill.hasMissingTools) "<span style='color:$greenHex'>✅</span>"
            else "<span style='color:$dimHex'>❌</span>"
        sb.append("$status <b>${skill.name}</b>")

        if (skill.hasMissingTools) {
            sb.append(
                " <span style='color:$errHex;font-size:10px'>⚠ 工具缺失: ${
                    skill.missingTools.joinToString(
                        ", "
                    )
                }</span>"
            )
        }
        sb.append("<br><span style='font-size:11px;color:$dimHex'>调用: /${skill.command} · ${skill.description}</span>")

        if (skill.triggerWords.isNotEmpty()) {
            sb.append(
                "<br><span style='font-size:10px;color:$dimHex'>触发词: ${
                    skill.triggerWords.joinToString(
                        ", "
                    )
                }</span>"
            )
        }
        if (skill.requiredTools.isNotEmpty()) {
            sb.append(
                "<br><span style='font-size:10px;color:$dimHex'>所需工具: ${
                    skill.requiredTools.joinToString(
                        ", "
                    )
                }</span>"
            )
        }
        sb.append("</html>")

        card.add(leftPanel, BorderLayout.WEST)
        card.add(JLabel(sb.toString()), BorderLayout.CENTER)

        // 详情按钮
        val detailBtn = JButton("详情").apply {
            font = font.deriveFont(11f)
            isContentAreaFilled = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            addActionListener { showSkillDetail(skill) }
        }
        card.add(detailBtn, BorderLayout.EAST)
        return card
    }

    private fun showSkillDetail(skill: SkillManager.Skill) {
        val textArea = JTextArea(skill.content).apply {
            font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 12)
            isEditable = false
            lineWrap = true; wrapStyleWord = true
            rows = 20; columns = 60
        }
        val scrollPane = JScrollPane(textArea)
        scrollPane.preferredSize = Dimension(520, 320)
        JOptionPane.showMessageDialog(
            this,
            scrollPane,
            "Skill 详情: ${skill.name}",
            JOptionPane.PLAIN_MESSAGE
        )
    }
}
