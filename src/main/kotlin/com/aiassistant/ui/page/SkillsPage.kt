package com.aiassistant.ui.page

import com.aiassistant.skills.SkillManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
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
        add(
            JLabel("<html><span style='color:#6B7280;font-size:11px'>目录: .code-assistant/skills/ · 兼容 .claude/skills/</span></html>"),
            BorderLayout.SOUTH
        )
        refreshList()
    }

    fun refreshList() {
        listContainer.removeAll()
        val skills = manager.loadSkills()
        if (skills.isEmpty()) {
            listContainer.add(JLabel("<html><div style='text-align:center;padding:40px;color:#6B7280'>还没有 Skill<br><span style='font-size:11px'>在 .code-assistant/skills/ 下创建 SKILL.md 来扩展 Agent 能力</span></div></html>"))
        } else {
            skills.forEach { listContainer.add(renderCard(it)) }
        }
        listContainer.revalidate(); listContainer.repaint()
    }

    private fun renderCard(skill: SkillManager.Skill): JPanel {
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xE5E7EB, 0x374151)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
        }
        val toggle = JCheckBox().apply {
            isSelected = skill.enabled
            addActionListener { skill.enabled = isSelected; refreshList() }
        }
        card.add(toggle, BorderLayout.WEST)

        val status =
            if (skill.enabled) "<span style='color:#22C55E'>✅</span>" else "<span style='color:#D1D5DB'>❌</span>"
        val warning =
            if (skill.missingTools.isNotEmpty()) " <span style='color:#EF4444'>⚠ 工具缺失</span>" else ""
        card.add(
            JLabel("<html>$status <b>${skill.name}</b>$warning &nbsp;<span style='color:#6B7280'>调用: /${skill.command}</span><br><span style='font-size:11px'>${skill.description}</span></html>"),
            BorderLayout.CENTER
        )
        return card
    }
}
