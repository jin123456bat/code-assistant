package com.aiassistant.agent

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Skill 引擎 — 从 .claude/skills/ 和项目目录加载 skill 定义。
 * 将 skill 作为特殊工具注册到 Agent 中。
 */
object SkillEngine {

    private data class SkillDef(
        val name: String,
        val description: String,
        val prompt: String
    )

    fun loadProjectSkills(projectBasePath: String): List<AgentTool> {
        val tools = mutableListOf<AgentTool>()
        // 从项目 .claude/skills/ 目录加载
        val skillsDir = File(projectBasePath, ".claude/skills")
        val globalSkillsDir = File(System.getProperty("user.home"), ".claude/skills")

        loadFromDir(skillsDir, tools)
        loadFromDir(globalSkillsDir, tools)

        return tools
    }

    private fun loadFromDir(dir: File, tools: MutableList<AgentTool>) {
        if (!dir.exists()) return
        dir.walkTopDown().maxDepth(2).forEach { file ->
            if (file.name == "SKILL.md" && file.isFile && file.length() < 200_000) {
                try {
                    val content = file.readText()
                    val skill = parseSkill(file.parentFile.name, content)
                    if (skill != null) {
                        tools.add(SkillTool(skill.name, skill.description, skill.prompt, file.parentFile))
                    }
                } catch (e: Exception) {
                    // 单个 skill 解析失败不影响其他 skill 加载
                }
            }
        }
    }

    private fun parseSkill(name: String, content: String): SkillDef? {
        // 只解析前 2000 字符提取 name/description，跳过巨型文件避免正则回溯溢出
        val head = content.take(2000)
        val nameMatch = Regex("""name:\s*(.+)""").find(head)
        val descMatch = Regex("""description:\s*(.+)""").find(head)
        val skillName = nameMatch?.groupValues?.get(1)?.trim() ?: name.replace('-', ' ')
        val description = descMatch?.groupValues?.get(1)?.trim() ?: "Skill: $skillName"
        return SkillDef(skillName, description, content)
    }
}

/**
 * Skill 作为 AgentTool 的适配器 — 将 skill 内容注入 system prompt。
 */
class SkillTool(
    override val name: String,
    override val description: String,
    val prompt: String,
    val sourceDir: File
) : AgentTool {
    override val parameters: List<ToolParameter> = listOf(
        ToolParameter("input", "string", "传递给 skill 的输入内容")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val input = params["input"] ?: ""
        // Skill 本身只是一个提示词注入，实际执行由 LLM 完成
        return ToolResult.ok("Skill '$name' 已激活。请按照以下指引完成任务:\n\n$prompt\n\n用户输入: $input")
    }

    fun buildSkillSystemPrompt(): String {
        return """
## 可用 Skill: $name
$description

使用方式：当用户请求匹配此 skill 时，按照以下指引执行：
$prompt
        """.trimIndent()
    }
}
