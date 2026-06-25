package com.aiassistant.ui.page

import com.aiassistant.skills.SkillManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class SkillsPage(project: Project) : JPanel(BorderLayout()) {

    private val manager = SkillManager(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        val header = JPanel(FlowLayout(FlowLayout.LEFT))
        header.add(JLabel("<html><b style='font-size:16px'>🎯 Skills</b></html>"))
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
        val toggle = JCheckBox().apply {
            isSelected = skill.enabled
            addActionListener { skill.enabled = isSelected; refreshList() }
        }
        card.add(toggle, BorderLayout.WEST)

        val greenHex = AppColors.success.toHtmlColor()
        val dimHex = AppColors.textSecondary.toHtmlColor()
        val errHex = AppColors.error.toHtmlColor()
        val status =
            if (skill.enabled) "<span style='color:$greenHex'>✅</span>" else "<span style='color:$dimHex'>❌</span>"
        val warning =
            if (skill.missingTools.isNotEmpty()) " <span style='color:$errHex'>⚠ 工具缺失</span>" else ""
        card.add(
            JLabel("<html>$status <b>${skill.name}</b>$warning &nbsp;<span style='color:$dimHex'>调用: /${skill.command}</span><br><span style='font-size:11px'>${skill.description}</span></html>"),
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
