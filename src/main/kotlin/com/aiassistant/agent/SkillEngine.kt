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
        val preferredModel: String? = null,
        val allowedTools: List<String> = emptyList(),
        val invokeFor: String? = null  // null=两者皆可, "user"=仅命令, "agent"=仅LLM
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
        try {
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
        } catch (e: Exception) {
            // walkTopDown 遇权限/符号链接异常不中断初始化
        }
    }

    // YAML frontmatter 分隔符（\R 匹配 \n / \r\n / \r，兼容 Windows CRLF）
    private val FRONTMATTER = Regex("^---\\s*\\R(.*?)\\R---\\s*\\R(.*)", setOf(RegexOption.DOT_MATCHES_ALL))

    /** 解析 SKILL.md 的 YAML front matter 和正文内容（使用 SnakeYAML） */
    private fun parseSkill(name: String, content: String): SkillDef? {
        val match = FRONTMATTER.find(content)
        val (frontmatter, body) = if (match != null) {
            match.groupValues[1] to match.groupValues[2].trim()
        } else {
            "" to content
        }

        val yaml = org.yaml.snakeyaml.Yaml()
        @Suppress("UNCHECKED_CAST")
        val fields = if (frontmatter.isNotBlank()) {
            try { yaml.load(frontmatter) as? Map<String, Any> ?: emptyMap() }
            catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val skillName = fields["name"]?.toString()?.trim() ?: name.replace('-', ' ')
        val description = fields["description"]?.toString()?.trim() ?: "Skill: $skillName"
        val preferredModel = fields["model"]?.toString()?.trim()
        val allowedTools = (fields["allowed-tools"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val invokeFor = fields["invoke-for"]?.toString()?.trim()?.takeIf { it == "user" || it == "agent" }
        return SkillDef(skillName, description, body, preferredModel, allowedTools, invokeFor)
    }
}
