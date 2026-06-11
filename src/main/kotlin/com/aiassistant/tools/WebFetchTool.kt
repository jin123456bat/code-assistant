package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.URI

/**
 * Web 页面获取工具。获取指定 URL 的内容并转换为 Markdown。
 */
class WebFetchTool : AgentTool {
    override val name = "web_fetch"
    override val description = "获取指定 URL 的网页内容，转换为 Markdown 格式返回。用于阅读在线文档。"
    override val parameters = listOf(
        ToolParameter("url", "string", "要获取的网页 URL", required = true)
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val url = params["url"] ?: return ToolResult.err("缺少 url 参数")

        return try {
            val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "CodeAssistant/1.0")
            }

            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return ToolResult.err("HTTP $statusCode: 无法获取页面")
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val markdown = htmlToMarkdown(html, url)
            ToolResult.ok(markdown)
        } catch (e: Exception) {
            ToolResult.err("获取页面失败: ${e.message}")
        }
    }

    private fun htmlToMarkdown(html: String, url: String): String {
        val sb = StringBuilder()

        // Title
        val title = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim() ?: url
        sb.append("# $title\n\n")
        sb.append("> 来源: $url\n\n")

        // Remove scripts, styles, comments
        val opt = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val cleaned = html
            .replace(Regex("<script[^>]*>.*?</script>", opt), "")
            .replace(Regex("<style[^>]*>.*?</style>", opt), "")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<head[^>]*>.*?</head>", opt), "")
            .replace(Regex("<nav[^>]*>.*?</nav>", opt), "")
            .replace(Regex("<footer[^>]*>.*?</footer>", opt), "")
            .replace(Regex("<header[^>]*>.*?</header>", opt), "")

        // Extract body content
        val body = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(cleaned)?.groupValues?.get(1)?.trim() ?: cleaned

        // Convert HTML to markdown
        var md = body
            // Headings
            .replace(Regex("</?h1[^>]*>", RegexOption.IGNORE_CASE), "\n\n# ")
            .replace(Regex("</?h2[^>]*>", RegexOption.IGNORE_CASE), "\n\n## ")
            .replace(Regex("</?h3[^>]*>", RegexOption.IGNORE_CASE), "\n\n### ")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<pre[^>]*><code[^>]*>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("</code></pre>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("<code[^>]*>", RegexOption.IGNORE_CASE), "`")
            .replace(Regex("</code>", RegexOption.IGNORE_CASE), "`")
            .replace(Regex("<a[^>]*href=\"([^\"]*)\"[^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)) { mr ->
                val h = mr.groupValues[1]
                val t = mr.groupValues[2].trim()
                if (h == t) h else "[$t]($h)"
            }
            .replace(Regex("</?(strong|b)>", RegexOption.IGNORE_CASE), "**")
            .replace(Regex("</?(em|i)>", RegexOption.IGNORE_CASE), "*")
            .replace(Regex("</?li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
            .replace(Regex("</?ul[^>]*>", RegexOption.IGNORE_CASE), "\n")
            // Strip remaining tags
            .replace(Regex("<[^>]*>"), "")
            // Decode entities
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            // Clean up whitespace
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        // Truncate to 8000 chars
        if (md.length > 8000) md = md.take(8000) + "\n\n... (内容已截断)"

        sb.append(md)
        return sb.toString()
    }
}
