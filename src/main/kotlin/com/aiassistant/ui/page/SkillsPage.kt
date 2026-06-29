package com.aiassistant.ui.page

import com.aiassistant.skills.SkillManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.io.File
import javax.swing.*

class SkillsPage(project: Project) : JPanel(BorderLayout()) {

    private val manager = SkillManager(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val skillsDir = File(project.basePath, ".code-assistant/skills")

    init {
        // Header: 目录路径 + 操作按钮（对齐 docs/ui/pages.md §七 布局）
        val header = JPanel(BorderLayout())
        val dirLabel =
            JLabel("<html><span style='font-size:13px'>Skill 目录: .code-assistant/skills/</span></html>")
        dirLabel.border = BorderFactory.createEmptyBorder(0, 0, 0, 12)
        header.add(dirLabel, BorderLayout.WEST)
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        actions.add(JButton("📂 打开目录").apply {
            addActionListener { openSkillsDir() }
        })
        actions.add(JButton("➕ 新建 Skill").apply {
            addActionListener { createNewSkill() }
        })
        header.add(actions, BorderLayout.EAST)
        header.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        add(header, BorderLayout.NORTH)
        add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
        val footerHex = AppColors.textSecondary.toHtmlColor()
        add(
            JLabel("<html><span style='color:$footerHex;font-size:11px'>目录: .code-assistant/skills/ · 兼容 .claude/skills/</span></html>"),
            BorderLayout.SOUTH
        )
        refreshList()
    }

    /** 在系统文件管理器中打开 skills 目录 */
    private fun openSkillsDir() {
        try {
            skillsDir.mkdirs()
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(skillsDir)
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "无法打开目录: ${e.message}",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    /** 弹出对话框创建新 Skill（创建目录 + SKILL.md 模板） */
    private fun createNewSkill() {
        val nameField = JTextField(20)
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("Skill 名称（英文，用于目录名和命令）:"))
            add(nameField)
        }
        val result = JOptionPane.showConfirmDialog(
            this, panel, "新建 Skill",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION || nameField.text.isBlank()) return
        val skillName = nameField.text.trim()
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
            val mdFile = File(skillDir, "SKILL.md")
            mdFile.writeText(
                """---
name: $skillName
description:
command: $skillName
---

"""
            )
            // 打开文件管理器定位到新创建的目录
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(skillDir)
            }
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
            listContainer.add(renderEmpty())
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
        }
        // hasMissingTools 的 Skill 不可调用，禁用 toggle
        val toggle = JCheckBox().apply {
            isSelected = skill.enabled && !skill.hasMissingTools
            isEnabled = !skill.hasMissingTools
            addActionListener {
                if (isSelected) {
                    manager.enableSkill(skill.name)
                } else {
                    manager.disableSkill(skill.name)
                }
                refreshList()
            }
        }
        card.add(toggle, BorderLayout.WEST)

        val greenHex = AppColors.success.toHtmlColor()
        val dimHex = AppColors.textSecondary.toHtmlColor()
        val errHex = AppColors.error.toHtmlColor()
        val status = if (skill.enabled && !skill.hasMissingTools)
            "<span style='color:$greenHex'>✅</span>" else "<span style='color:$dimHex'>❌</span>"

        // 构建警告信息
        val warnings = mutableListOf<String>()
        if (skill.hasMissingTools) {
            warnings.add("⚠ 声明了不存在的工具: ${skill.missingTools.joinToString(", ")}")
        }
        val warningHtml = if (warnings.isNotEmpty())
            "<br><span style='color:$errHex;font-size:11px'>${warnings.joinToString("<br>")}</span>" else ""

        // 显示触发词（对应 docs/ui/pages.md §七「Skill 卡片」布局）
        val triggersHtml = if (skill.triggerWords.isNotEmpty())
            "<br><span style='font-size:10px;color:$dimHex'>触发词: ${
                skill.triggerWords.joinToString(
                    ", "
                )
            }</span>"
        else ""

        // 显示依赖工具列表
        val toolsHtml = if (skill.requiredTools.isNotEmpty())
            "<br><span style='font-size:10px;color:$dimHex'>所需工具: ${
                skill.requiredTools.joinToString(
                    ", "
                )
            }</span>"
        else ""

        card.add(
            JLabel("<html>$status <b>${skill.name}</b>&nbsp;<span style='color:$dimHex'>调用: /${skill.command}</span><br><span style='font-size:11px'>${skill.description}</span>$triggersHtml$toolsHtml$warningHtml</html>"),
            BorderLayout.CENTER
        )
        return card
    }

    private fun renderEmpty(): JPanel {
        val p = JPanel(BorderLayout())
        val dimHex = AppColors.textSecondary.toHtmlColor()
        p.add(
            JLabel(
                "<html><div style='text-align:center;padding:40px;color:$dimHex'>🎯<br><br>还没有 Skill<br><span style='font-size:11px'>在 .code-assistant/skills/ 下创建 SKILL.md 来扩展 Agent 能力</span></div></html>",
                SwingConstants.CENTER
            )
        )
        return p
    }
}
