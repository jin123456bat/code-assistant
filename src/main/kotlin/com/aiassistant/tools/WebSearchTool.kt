package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Web 搜索工具。使用 DuckDuckGo Lite 搜索，无需 API Key。
 * 只读操作，自动放行。
 */
class WebSearchTool : AgentTool {
    override val name = "web_search"
    override val description = "搜索互联网获取最新信息。用于查找文档、解决方案、技术资料等。"
    override val parameters = listOf(
        ToolParameter("query", "string", "搜索关键词", required = true),
        ToolParameter("max_results", "integer", "最大结果数，默认 10")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val query = params["query"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("query 不能为空")
        val maxResults = params["max_results"]?.toIntOrNull() ?: 10

        return try {
            val url = "https://lite.duckduckgo.com/lite/?q=${URLEncoder.encode(query, "UTF-8")}"
            val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "CodeAssistant/1.0")
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val results = parseResults(html, maxResults)
            if (results.isEmpty()) {
                ToolResult.ok("未找到 \"$query\" 的搜索结果")
            } else {
                ToolResult.ok(results)
            }
        } catch (e: Exception) {
            ToolResult.err("搜索失败: ${e.message}")
        }
    }

    private fun parseResults(html: String, max: Int): String {
        val sb = StringBuilder()
        // DuckDuckGo Lite: each result has a link and a snippet
        val linkRegex = Regex("""<a[^>]*class="result-link"[^>]*href="([^"]*)"[^>]*>([^<]*)</a>""")
        val snippetRegex = Regex("""<td class="result-snippet"[^>]*>(.*?)</td>""")
        val links = linkRegex.findAll(html).take(max).toList()
        val snippets = snippetRegex.findAll(html).take(max).toList()

        for (i in links.indices) {
            val title = links[i].groupValues[2].trim()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            val href = links[i].groupValues[1].trim()
                .replace("&amp;", "&")
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.trim()?.let { s ->
                s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            } ?: ""
            sb.append("${i + 1}. **$title**\n")
            sb.append("   $href\n")
            if (snippet.isNotBlank()) sb.append("   $snippet\n")
            sb.append("\n")
        }

        return sb.toString().trim()
    }
}
