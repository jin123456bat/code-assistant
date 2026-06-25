package com.aiassistant.skills

import com.intellij.openapi.project.Project
import java.io.File

// ponytail: skill system — scans .code-assistant/skills/ and .claude/skills/ for SKILL.md

class SkillManager(private val project: Project) {

    data class Skill(
        val name: String,
        val description: String,
        val command: String,
        val content: String,
        var enabled: Boolean = true,
        val missingTools: List<String> = emptyList()
    )

    fun loadSkills(): List<Skill> {
        val skills = mutableListOf<Skill>()
        val dirs = listOf(
            File(project.basePath, ".code-assistant/skills"),
            File(project.basePath, ".claude/skills"),
        )

        dirs.filter { it.isDirectory }.forEach { dir ->
            dir.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                val md = File(skillDir, "SKILL.md")
                if (md.exists()) {
                    val parsed = parseSkill(md)
                    if (parsed != null) skills.add(parsed)
                }
            }
        }
        return skills
    }

    private fun parseSkill(file: File): Skill? {
        try {
            val text = file.readText()
            val frontmatter = extractYaml(text)
            val name = frontmatter["name"] ?: file.parentFile.name
            val description = frontmatter["description"] ?: ""
            val command = frontmatter["command"] ?: name
            val body = text.substringAfter("---\n").substringAfter("---\n").trim()
            return Skill(name = name, description = description, command = command, content = body)
        } catch (_: Exception) {
            return null
        }
    }

    private fun extractYaml(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val match = Regex("""^---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(text)
        if (match != null) {
            match.groupValues[1].lines().forEach { line ->
                val kv = line.split(":", limit = 2)
                if (kv.size == 2) map[kv[0].trim()] = kv[1].trim()
            }
        }
        return map
    }

    fun getSystemPromptExtension(): String {
        val skills = loadSkills().filter { it.enabled }
        if (skills.isEmpty()) return ""
        return "\n## 可用 Skills\n" + skills.joinToString("\n") { "- ${it.command}: ${it.description}" }
    }
}
