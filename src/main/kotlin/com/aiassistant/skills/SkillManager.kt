package com.aiassistant.skills

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.io.File

// ponytail: skill system — scans .code-assistant/skills/ and .claude/skills/ for SKILL.md

class SkillManager(private val project: Project) {
    private val gson = Gson()
    private val stateFile: File get() = File(project.basePath, ".code-assistant/skills-state.json")

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
        val disabledCommands = readDisabledCommands()
        val dirs = listOf(
            File(project.basePath, ".code-assistant/skills"),
            File(project.basePath, ".claude/skills"),
        )

        dirs.filter { it.isDirectory }.forEach { dir ->
            dir.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                val md = File(skillDir, "SKILL.md")
                if (md.exists()) {
                    val parsed = parseSkill(md)
                    if (parsed != null) skills.add(
                        parsed.copy(enabled = parsed.command !in disabledCommands)
                    )
                }
            }
        }
        return skills
    }

    fun setEnabled(command: String, enabled: Boolean) {
        val disabledCommands = readDisabledCommands().toMutableSet()
        if (enabled) disabledCommands.remove(command) else disabledCommands.add(command)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(SkillStateDTO(disabledCommands.sorted())))
    }

    fun enabledSlashCommands(): List<String> =
        loadSkills()
            .filter { it.enabled }
            .map { "/${it.command}" }
            .distinct()
            .sorted()

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

    private fun readDisabledCommands(): Set<String> {
        if (!stateFile.exists()) return emptySet()
        return try {
            val type = object : TypeToken<SkillStateDTO>() {}.type
            gson.fromJson<SkillStateDTO>(stateFile.readText(), type).disabledCommands.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private data class SkillStateDTO(val disabledCommands: List<String> = emptyList())
}
