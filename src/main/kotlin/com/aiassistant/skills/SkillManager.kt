package com.aiassistant.skills

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.io.File

// ponytail: skill system — scans .code-assistant/skills/、.claude/skills/、~/.claude/skills/、.codex/skills/、~/.codex/skills/ for SKILL.md。同名 Skill 按扫描顺序优先级覆盖

class SkillManager(private val project: Project) {
    private val gson = Gson()
    private val stateFile: File get() = File(project.basePath, ".code-assistant/skills-state.json")

    data class Skill(
        val name: String,
        val description: String,
        val command: String,
        val requiredTools: List<String> = emptyList(),
        val content: String,
        var enabled: Boolean = true,
        val hasMissingTools: Boolean = false,
        val missingTools: List<String> = emptyList(),  // 具体缺失的工具名称，供 UI 展示
        val triggerWords: List<String> = emptyList()  // 触发词列表，供 UI 展示
    )

    fun loadSkills(): List<Skill> {
        val skills = mutableListOf<Skill>()
        val disabledNames = readDisabledNames()
        val dirs = listOf(
            File(project.basePath, ".code-assistant/skills"),
            File(project.basePath, ".claude/skills"),
            File(System.getProperty("user.home"), ".claude/skills"),
            File(project.basePath, ".codex/skills"),
            File(System.getProperty("user.home"), ".codex/skills"),
        )

        dirs.filter { it.isDirectory }.forEach { dir ->
            dir.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                val md = File(skillDir, "SKILL.md")
                if (md.exists()) {
                    val parsed = parseSkill(md)
                    if (parsed != null) {
                        // 同名 Skill 按优先级覆盖：先扫到的优先，已存在则跳过
                        if (skills.any { it.name == parsed.name }) {
                            return@forEach
                        }
                        // 工具交叉验证：声明的工具是否全部存在于 ToolRegistry
                        val allToolNames = com.aiassistant.agent.ToolRegistry.listNames().toSet()
                        val missingTools = parsed.requiredTools.filter { it !in allToolNames }
                        val hasMissingTools = missingTools.isNotEmpty()
                        skills.add(
                            parsed.copy(
                                enabled = parsed.name !in disabledNames,
                                hasMissingTools = hasMissingTools,
                                missingTools = missingTools
                            )
                        )
                    }
                }
            }
        }

        // command 冲突检测：同名 command 双方禁用
        val commandCounts = skills.groupBy { it.command }
        val finalSkills = skills.map { skill ->
            val conflict = (commandCounts[skill.command]?.size ?: 0) > 1
            if (conflict) skill.copy(enabled = false) else skill
        }

        return finalSkills
    }

    /**
     * 强制重新加载 Skill 文件（对齐 docs/agent/skills.md §四 /reload-skill 命令）。
     * 清除内存缓存，下次 loadSkills() 会重新从磁盘读取。
     */
    fun reloadSkills() {
        loadSkills()
    }

    fun getEnabledSkills(): List<Skill> =
        loadSkills().filter { it.enabled && !it.hasMissingTools }

    fun enableSkill(name: String) {
        val disabledNames = readDisabledNames().toMutableSet()
        disabledNames.remove(name)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(SkillStateDTO(disabledNames.sorted())))
    }

    fun disableSkill(name: String) {
        val disabledNames = readDisabledNames().toMutableSet()
        disabledNames.add(name)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(SkillStateDTO(disabledNames.sorted())))
    }

    /** 按传入的 Skill 列表获取对应的 SKILL.md 正文，作为消息注入 conversation */
    fun getTriggeredContent(skills: List<Skill>): String =
        skills.joinToString("\n\n---\n\n") { it.content }

    fun setEnabled(name: String, enabled: Boolean) {
        val disabledNames = readDisabledNames().toMutableSet()
        if (enabled) disabledNames.remove(name) else disabledNames.add(name)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(SkillStateDTO(disabledNames.sorted())))
    }

    fun enabledSlashCommands(): List<String> {
        val skills = loadSkills()
        // 排除 command 冲突项：同一 command 被多个 Skill 声明时，按文档要求均不加载
        val commandCounts = skills.groupBy { it.command }
        val conflictCommands = commandCounts.filter { it.value.size > 1 }.keys
        return skills
            .filter { it.enabled && !it.hasMissingTools && it.command !in conflictCommands }
            .map { "/${it.command}" }
            .distinct()
            .sorted()
    }

    private fun parseSkill(file: File): Skill? {
        try {
            val text = file.readText()
            val frontmatter = extractYaml(text)
            val name = frontmatter["name"]?.firstOrNull() ?: file.parentFile.name
            val description = frontmatter["description"]?.firstOrNull() ?: ""
            val command = frontmatter["command"]?.firstOrNull() ?: name
            // 解析 YAML `tools` 字段：支持 YAML 列表格式（- Read / - Bash）
            val requiredTools = frontmatter["tools"] ?: emptyList()
            // 解析 YAML `triggers` 字段（可选）：触发词列表，供 UI 展示
            val triggerWords = frontmatter["triggers"] ?: emptyList()
            val body = text.substringAfter("---\n").substringAfter("---\n").trim()
            return Skill(
                name = name,
                description = description,
                command = command,
                requiredTools = requiredTools,
                content = body,
                triggerWords = triggerWords
            )
        } catch (_: Exception) {
            return null
        }
    }

    /** 解析 YAML frontmatter，支持单值（key: value）和列表值（key:\n  - item1\n  - item2） */
    private fun extractYaml(text: String): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        val match = Regex("""^---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(text)
        if (match != null) {
            val frontmatter = match.groupValues[1]
            val lines = frontmatter.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val kv = line.split(":", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim()
                    val value = kv[1].trim()
                    if (value.isEmpty()) {
                        // 可能是列表：检查后续行是否以 "- " 开头
                        val listItems = mutableListOf<String>()
                        var j = i + 1
                        while (j < lines.size && lines[j].trimStart().startsWith("-")) {
                            listItems.add(lines[j].trimStart().removePrefix("-").trim())
                            j++
                        }
                        if (listItems.isNotEmpty()) {
                            map[key] = listItems
                            i = j
                            continue
                        }
                    }
                    map[key] = listOf(value)
                }
                i++
            }
        }
        return map
    }

    fun getSystemPromptExtension(): String {
        // hasMissingTools=true 的 Skill 不可调用，与未启用的一起过滤
        val skills = loadSkills().filter { it.enabled && !it.hasMissingTools }
        if (skills.isEmpty()) return ""
        return "\n## 可用 Skills\n" + skills.joinToString("\n") { "- ${it.command}: ${it.description}" }
    }

    private fun readDisabledNames(): Set<String> {
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
