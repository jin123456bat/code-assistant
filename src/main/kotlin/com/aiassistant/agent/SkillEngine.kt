package com.aiassistant.agent

import java.io.File

/**
 * Skill 引擎 — 从 .claude/skills/ 和项目目录加载 skill 定义。
 * 对齐 Claude Code：skill 不作为独立工具注册，而是通过统一的 Skill 元工具激活。
 */
object SkillEngine {

    /** Skill 定义 — 对齐 Claude Code SKILL.md 格式 */
    data class SkillDef(
        val name: String,
        val description: String,
        val prompt: String,
        val preferredModel: String? = null
    )

    fun loadProjectSkills(projectBasePath: String): List<SkillDef> {
        val defs = mutableListOf<SkillDef>()
        // 从项目 .claude/skills/ 目录加载
        val skillsDir = File(projectBasePath, ".claude/skills")
        val globalSkillsDir = File(System.getProperty("user.home"), ".claude/skills")

        // 加载顺序：先全局后项目，项目同名 skill 覆盖全局（项目优先）
        loadFromDir(globalSkillsDir, defs)
        loadFromDir(skillsDir, defs)

        return defs
    }

    private fun loadFromDir(dir: File, defs: MutableList<SkillDef>) {
        if (!dir.exists()) return
        // 遍历全深度目录树（用户 skill 可能嵌套在子目录中，如 gstack/review/SKILL.md）
        // 跳过隐藏目录（.git、.hermes 等）中的文件
        dir.walkTopDown().forEach { file ->
            val relativePath = file.relativeTo(dir).path
            val pathParts = relativePath.split(File.separator)
            // 跳过隐藏目录内的所有文件
            if (pathParts.any { it.startsWith(".") }) return@forEach
            if (file.name == "SKILL.md" && file.isFile && file.length() < 200_000) {
                try {
                    val content = file.readText()
                    val skill = parseSkill(file.parentFile.name, content)
                    if (skill != null) {
                        // 项目 skill 覆盖全局同名 skill
                        defs.removeAll { it.name == skill.name }
                        defs.add(skill)
                    }
                } catch (e: Exception) {
                    // 单个 skill 解析失败不影响其他 skill 加载
                }
            }
        }
    }

    /** 解析 SKILL.md 的 YAML front matter 和正文内容 */
    private fun parseSkill(name: String, content: String): SkillDef? {
        // 提取 YAML front matter（--- 包裹的部分），限制 2000 字符防止正则回溯溢出
        val head = content.take(2000)
        val nameMatch = Regex("""^name:\s*(.+)$""", RegexOption.MULTILINE).find(head)
        val descMatch = Regex("""^description:\s*(.+)$""", RegexOption.MULTILINE).find(head)
        val modelMatch = Regex("""^model:\s*(.+)$""", RegexOption.MULTILINE).find(head)
        val skillName = nameMatch?.groupValues?.get(1)?.trim() ?: name.replace('-', ' ')
        val description = descMatch?.groupValues?.get(1)?.trim() ?: "Skill: $skillName"
        val preferredModel = modelMatch?.groupValues?.get(1)?.trim()
        // 提取 YAML front matter 之后的正文（跳过元数据字段，LLM 不需要 version/preamble-tier 等）
        val bodyContent = extractBody(content)
        return SkillDef(skillName, description, bodyContent, preferredModel)
    }

    /** 提取 SKILL.md 中 YAML front matter（---...---）之后的内容，过滤元数据噪声 */
    private fun extractBody(content: String): String {
        val match = Regex("""---\s*\n.*?\n---\s*\n""", RegexOption.DOT_MATCHES_ALL).find(content)
        return if (match != null) {
            content.substring(match.range.last + 1).trim()
        } else {
            content  // 没有 YAML front matter，全文作为 prompt
        }
    }
}
