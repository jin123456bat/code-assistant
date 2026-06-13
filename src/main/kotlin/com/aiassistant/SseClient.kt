package com.aiassistant

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * SSE streaming client using HttpURLConnection.
 * Parses Server-Sent Events and delivers them via callback.
 */
open class SseClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 120_000  // V4 Pro 思考模式可能需要较长时间
    }

    private var connection: HttpURLConnection? = null
    private var reader: BufferedReader? = null
    @Volatile private var cancelled: Boolean = false

    open fun connect(
        url: String,
        apiKey: String,
        requestBody: String,
        callback: SseCallback
    ) {
        cancelled = false
        val startTime = System.currentTimeMillis()
        Thread {
            try {
                val uri = URI.create(url)
                val maskedKey = if (apiKey.length > 8) apiKey.take(4) + "..." + apiKey.takeLast(4) else "***"
                AppLogger.info("SSE 连接开始: url=${uri.host}, apiKey=$maskedKey, bodySize=${requestBody.length}")

                connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                }

                connection!!.outputStream.use { os ->
                    os.write(requestBody.toByteArray())
                    os.flush()
                }

                val statusCode = connection!!.responseCode
                if (statusCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection!!.errorStream?.let {
                        BufferedReader(InputStreamReader(it)).readText()
                    } ?: ""
                    AppLogger.requestFailed(statusCode, errorBody)
                    callback.onError(statusCode, errorBody)
                    return@Thread
                }
                AppLogger.info("SSE 连接成功: status=$statusCode, latency=${System.currentTimeMillis() - startTime}ms")

                reader = BufferedReader(InputStreamReader(connection!!.inputStream))
                val currentData = StringBuilder()

                while (!cancelled) {
                    val line = reader!!.readLine() ?: break
                    when {
                        line.startsWith("data: ") -> {
                            val data = line.removePrefix("data: ")
                            if (data == "[DONE]") {
                                callback.onDone()
                                break
                            }
                            currentData.append(data)
                        }
                        line.isEmpty() && currentData.isNotEmpty() -> {
                            callback.onData(currentData.toString())
                            currentData.setLength(0)
                        }
                    }
                }
                // EOF（服务器正常关流但未发 [DONE]）也视为完成，避免调用方 120s 超时等待
                if (!cancelled) callback.onDone()
            } catch (e: Exception) {
                if (!cancelled) {
                    AppLogger.requestFailed(0, "${e.javaClass.simpleName}: ${e.message}")
                    callback.onError(0, e.message ?: "Connection failed")
                }
            } finally {
                close()
            }
        }.start()
    }

    open fun cancel() {
        cancelled = true
        close()
    }

    @Synchronized
    private fun close() {
        try { reader?.close() } catch (_: Exception) {}
        try { connection?.disconnect() } catch (_: Exception) {}
    }
}

interface SseCallback {
    fun onData(content: String)
    fun onDone()
    fun onError(httpCode: Int, message: String)
}
