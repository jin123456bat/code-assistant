package com.aiassistant.agent
import com.aiassistant.AppLogger

import java.io.File

/**
 * Rules 引擎 — 从 .claude/rules/ 加载规则文件。
 * 对齐 Claude Code：支持 YAML frontmatter（paths 条件匹配）+ Markdown 正文。
 */
object RulesEngine {

    private val FRONTMATTER = Regex("^---\\s*\\R(.*?)\\R---\\s*\\R(.*)", setOf(RegexOption.DOT_MATCHES_ALL))

    fun loadAll(projectBasePath: String): List<AgentContext.RuleDef> {
        val rules = mutableListOf<AgentContext.RuleDef>()
        val home = System.getProperty("user.home")
        // 加载顺序：先全局后项目，项目规则追加到末尾（优先级更高体现在靠后）
        if (home != null) loadFromDir(File(home, ".claude/rules"), rules)
        loadFromDir(File(projectBasePath, ".claude/rules"), rules)
        return rules
    }

    private fun loadFromDir(dir: File, rules: MutableList<AgentContext.RuleDef>) {
        if (!dir.exists()) return
        try {
            dir.walkTopDown().maxDepth(1).forEach { file ->
                if (file.extension != "md" || !file.isFile || file.length() > 100_000) return@forEach
                try {
                    val content = file.readText()
                    val rule = parseRule(file.nameWithoutExtension, content)
                    if (rule != null) rules.add(rule)
                } catch (e: Exception) { AppLogger.warn("RulesEngine: 解析 ${file.name} 失败: ${e.message}") }
            }
        } catch (e: Exception) { AppLogger.warn("RulesEngine: 扫描 rules 目录失败: ${e.message}") }
    }

    private fun parseRule(name: String, content: String): AgentContext.RuleDef? {
        val match = FRONTMATTER.find(content)
        val (frontmatter, body) = if (match != null) {
            match.groupValues[1] to match.groupValues[2].trim()
        } else {
            "" to content
        }
        if (body.isBlank()) return null

        val fields = if (frontmatter.isNotBlank()) {
            try {
                val yaml = org.yaml.snakeyaml.Yaml()
                @Suppress("UNCHECKED_CAST")
                yaml.load(frontmatter) as? Map<String, Any> ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val description = fields["description"]?.toString()?.trim() ?: name
        val paths = fields["paths"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

        // paths 支持逗号分隔的多模式
        val normalizedPaths = paths?.split(",")?.map { it.trim() }?.joinToString(",")

        return AgentContext.RuleDef(name, description, normalizedPaths, body)
    }

    /** 检查规则是否匹配给定的当前文件路径 */
    fun matchesFile(rule: AgentContext.RuleDef, filePath: String?): Boolean {
        if (rule.paths == null) return true  // 无 paths = 始终生效
        if (filePath == null) return false    // 有 paths 但无当前文件 = 不生效
        return rule.paths.split(",").any { pattern ->
            globMatch(pattern.trim(), filePath)
        }
    }

    /** 简单的 glob 匹配：支持 ** 和 * */
    private fun globMatch(pattern: String, path: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
        val regex = Regex(
            pattern.trim()
                .replace('\\', '/')
                .replace(".", "\\.")
                .replace("**", "___DOUBLESTAR___")
                .replace("*", "[^/]*")
                .replace("___DOUBLESTAR___", ".*")
        )
        return regex.matches(normalizedPath)
    }
}
