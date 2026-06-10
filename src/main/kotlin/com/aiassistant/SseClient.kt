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
        private const val READ_TIMEOUT_MS = 30_000
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
        Thread {
            try {
                val uri = URI.create(url)
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
                    callback.onError(statusCode, errorBody)
                    return@Thread
                }

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
            } catch (e: Exception) {
                if (!cancelled) {
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
