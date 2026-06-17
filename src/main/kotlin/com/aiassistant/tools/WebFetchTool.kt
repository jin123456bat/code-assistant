package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import kotlin.math.min
import kotlin.math.pow

/**
 * Web 页面获取工具，支持断线重连、编码检测、响应大小限制。
 *
 * 安全说明：此工具从用户本地 IDE 发起 HTTP 请求，与 Claude Code 的
 * 服务端 fetch 不同。回环地址（localhost/127.0.0.1）和内网地址
 * （192.168.x.x/10.x.x.x）均可达，属于预期行为——用户可以访问本机
 * 和内网服务获取内容。不视为 SSRF 漏洞。
 */
class WebFetchTool : AgentTool {

    override val name = "web_fetch"
    override val description = "获取指定 URL 的网页内容并转换为 Markdown 格式。支持自动重试（最多 3 次）、编码检测、HTTPS 重定向。"
    override val parameters = listOf(
        ToolParameter("url", "string", "要获取的网页 URL", required = true),
        ToolParameter("max_retries", "integer", "最大重试次数，默认 3，范围 0-5")
    )

    companion object {
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024 // 5MB
        private const val DEFAULT_MAX_RETRIES = 3
        private const val MAX_RETRIES_LIMIT = 5
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val OUTPUT_TRUNCATE_CHARS = 8000
    }

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val url = params["url"] ?: return ToolResult.err("缺少 url 参数")
        val maxRetries = (params["max_retries"]?.toIntOrNull() ?: DEFAULT_MAX_RETRIES)
            .coerceIn(0, MAX_RETRIES_LIMIT)

        // 校验 URL 格式
        val uri = try {
            URI.create(url)
        } catch (e: Exception) {
            return ToolResult.err("无效的 URL: $url")
        }

        return fetchWithRetry(uri, maxRetries)
    }

    private fun fetchWithRetry(uri: URI, maxRetries: Int): ToolResult {
        var lastError: String? = null
        var redirectedUri = uri

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                // 指数退避：1s, 2s, 4s...
                val delayMs = (2.0.pow(attempt - 1) * 1000).toLong()
                Thread.sleep(delayMs)
            }

            try {
                val connection = openConnection(redirectedUri)
                val statusCode = connection.responseCode

                when {
                    // 重定向
                    statusCode in 301..308 -> {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location != null) {
                            redirectedUri = URI.create(location)
                            if (attempt < maxRetries) continue
                            return ToolResult.err("重定向次数超出上限: $location")
                        }
                        return ToolResult.err("HTTP $statusCode: 重定向但缺少 Location header")
                    }
                    // 成功
                    statusCode == HttpURLConnection.HTTP_OK -> {
                        val charset = detectCharset(connection)
                        val rawBytes = readLimited(connection, MAX_RESPONSE_BYTES)
                        connection.disconnect()

                        if (rawBytes.isEmpty()) {
                            return ToolResult.err("响应内容为空")
                        }

                        val html = String(rawBytes, charset)
                        val truncated = rawBytes.size >= MAX_RESPONSE_BYTES
                        val markdown = htmlToMarkdown(html, redirectedUri.toString(), OUTPUT_TRUNCATE_CHARS)

                        val note = if (truncated) "\n> ⚠ 响应超过 5MB 已截断，部分内容可能缺失\n" else ""
                        return ToolResult.ok(markdown + note)
                    }
                    // 客户端/服务端错误（可重试）
                    statusCode in 429..599 -> {
                        connection.disconnect()
                        lastError = "HTTP $statusCode（服务端错误，第 ${attempt + 1} 次尝试）"
                    }
                    // 其他错误
                    else -> {
                        connection.disconnect()
                        return ToolResult.err("HTTP $statusCode: 无法获取页面")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "连接超时（第 ${attempt + 1} 次尝试）"
            } catch (e: java.net.ConnectException) {
                lastError = "无法连接到服务器: ${e.message}"
            } catch (e: java.net.UnknownHostException) {
                lastError = "未知主机: ${e.message}"
                // DNS 解析失败不重试
                return ToolResult.err(lastError)
            } catch (e: java.io.IOException) {
                lastError = "网络错误: ${e.message}（第 ${attempt + 1} 次尝试）"
            } catch (e: Exception) {
                return ToolResult.err("获取页面失败: ${e.message}")
            }
        }

        return ToolResult.err(lastError ?: "获取页面失败，已达最大重试次数")
    }

    private fun openConnection(uri: URI): HttpURLConnection {
        // HTTP → HTTPS 自动升级
        val finalUri = if (uri.scheme.equals("http", ignoreCase = true)) {
            URI.create(uri.toString().replaceFirst("http://", "https://"))
        } else uri

        return (finalUri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false // 手动处理重定向以支持跨协议
            setRequestProperty("User-Agent", "CodeAssistant/1.0")
            setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
        }
    }

    /** 从 Content-Type header 检测编码，默认 UTF-8 */
    private fun detectCharset(connection: HttpURLConnection): Charset {
        val contentType = connection.contentType ?: return Charsets.UTF_8
        val charsetMatch = Regex("(?i)charset\\s*=\\s*([^;\\s]+)").find(contentType)
        return if (charsetMatch != null) {
            try {
                Charset.forName(charsetMatch.groupValues[1].trim())
            } catch (_: Exception) {
                Charsets.UTF_8
            }
        } else {
            Charsets.UTF_8
        }
    }

    /** 限制响应大小，避免 OOM */
    private fun readLimited(connection: HttpURLConnection, maxBytes: Int): ByteArray {
        val buffer = ByteArray(min(8192, maxBytes))
        val output = java.io.ByteArrayOutputStream(min(maxBytes, 1024 * 1024))
        var totalRead = 0

        connection.inputStream.use { input ->
            while (totalRead < maxBytes) {
                val toRead = min(buffer.size, maxBytes - totalRead)
                val bytesRead = input.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
        }
        return output.toByteArray()
    }

    private fun htmlToMarkdown(html: String, url: String, maxChars: Int): String {
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
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        if (md.length > maxChars) {
            md = md.take(maxChars) + "\n\n... (内容已截断至 ${maxChars} 字符)"
        }

        sb.append(md)
        return sb.toString()
    }
}
