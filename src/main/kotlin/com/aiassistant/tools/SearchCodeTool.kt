package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在项目中搜索代码文本的工具
 */
class SearchCodeTool : AgentTool {
    override val name = "search_code"
    override val description = "在项目文件中搜索指定文本或正则表达式。返回匹配的文件路径和行内容。"
    override val parameters = listOf(
        ToolParameter("query", "string", "搜索的文本或正则表达式", required = true),
        ToolParameter("file_pattern", "string", "文件名匹配模式，如 *.kt, *.xml（可选）"),
        ToolParameter("case_sensitive", "boolean", "是否区分大小写，默认 false"),
        ToolParameter("max_results", "integer", "最大结果数，默认 30")
    )

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val query = params["query"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("query 不能为空")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
        val filePattern = params["file_pattern"]
        val caseSensitive = params["case_sensitive"]?.toBoolean() ?: false
        val maxResults = params["max_results"]?.toIntOrNull() ?: 30

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) searchJava(query, basePath, filePattern, caseSensitive, maxResults)
               else searchGrep(query, basePath, filePattern, caseSensitive, maxResults)
    }

    /** grep 搜索（Unix/macOS） */
    private fun searchGrep(query: String, basePath: String, filePattern: String?, caseSensitive: Boolean, maxResults: Int): ToolResult {
        return try {
            val args = mutableListOf("grep", "-rn")
            if (filePattern != null) args.add("--include=$filePattern")
            if (!caseSensitive) args.add("-i")
            args.addAll(listOf("-m", maxResults.toString(), query, "."))

            val process = ProcessBuilder(args)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(10, TimeUnit.SECONDS); if (!finished) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }

            formatResults(output, query, maxResults)
        } catch (e: Exception) {
            ToolResult.err("搜索失败: ${e.message}")
        }
    }

    /** Java 文件遍历搜索（Windows 回退） */
    private fun searchJava(query: String, basePath: String, filePattern: String?, caseSensitive: Boolean, maxResults: Int): ToolResult {
        return try {
            val target = if (caseSensitive) query else query.lowercase()
            val patternRegex = filePattern?.let {
                Regex(it.replace(".", "\\.").replace("*", ".*"))
            }
            val results = mutableListOf<String>()
            val dir = File(basePath); if (!dir.exists()) return ToolResult.err("项目路径不可用")
            val ignoreDirs = setOf(".git", ".idea", ".gradle", "build", "node_modules", "out", "target",
                ".code-assistant", "vendor", "dist", "__pycache__", ".venv", "venv", ".next", ".nuxt")
            val maxFileSize = 1_000_000L
            val maxFiles = 50000
            var fileCount = 0
            for (f in dir.walkTopDown().onEnter { !ignoreDirs.contains(it.name) }) {
                if (!f.isFile) continue
                if (results.size >= maxResults || fileCount >= maxFiles) break
                if (f.length() > maxFileSize) continue
                fileCount++
                if (patternRegex != null && !patternRegex.matches(f.name)) continue
                val relativePath = f.relativeTo(dir).path.replace('\\', '/')
                try {
                    f.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            if (results.size >= maxResults) return@forEachLine
                            val check = if (caseSensitive) line else line.lowercase()
                            if (check.contains(target)) {
                                results.add("$relativePath:${line.trim().take(200)}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            formatResults(results.joinToString("\n"), query, maxResults)
        } catch (e: Exception) {
            ToolResult.err("搜索失败: ${e.message}")
        }
    }

    private fun formatResults(output: String, query: String, maxResults: Int): ToolResult {
        val lines = output.lines().filter { it.isNotBlank() }.take(maxResults)
        if (lines.isEmpty()) {
            return ToolResult.ok("未找到匹配 \"$query\" 的结果")
        }
        return ToolResult.ok(lines.joinToString("\n") { line ->
            val parts = line.split(":", limit = 3)
            if (parts.size >= 3) "${parts[0]}:${parts[1]} — ${parts[2].trim()}" else line
        })
    }
}
