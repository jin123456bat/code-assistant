package com.aiassistant.completion

import com.aiassistant.AppSettingsService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * FIM API 异常，携带 HTTP 状态码用于重试判断。
 */
class FimApiException(val statusCode: Int, message: String) : IOException(message)

/**
 * DeepSeek FIM API 客户端。封装 `/beta/completions` 调用，非流式，带超时和重试。
 */
class DeepSeekFimClient(
    private val settings: AppSettingsService = AppSettingsService.getInstance()
) {
    companion object {
        private const val FIM_ENDPOINT = "https://api.deepseek.com/beta/completions"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 3_000
        private const val MAX_RETRIES = 1
    }

    private val gson = Gson()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    // ---- Request/Response data classes ----

    data class FimRequest(
        val model: String,
        val prompt: String,
        val suffix: String?,
        @SerializedName("max_tokens") val maxTokens: Int,
        val n: Int,
        val temperature: Double = 0.0,
        val stop: List<String> = listOf("\n\n\n"),
        val stream: Boolean = false
    )

    data class FimChoice(
        val text: String,
        val index: Int,
        @SerializedName("finish_reason") val finishReason: String?
    )

    data class FimUsage(
        @SerializedName("prompt_tokens") val promptTokens: Int,
        @SerializedName("completion_tokens") val completionTokens: Int
    )

    data class FimResponse(
        val id: String?,
        val `object`: String?,
        val choices: List<FimChoice>?,
        val usage: FimUsage?
    )

    // ---- Public API ----

    /**
     * 发送 FIM 请求，返回原始响应。
     * @throws IOException 网络错误
     * @return null 表示 API Key 未设置
     */
    fun complete(prompt: String, suffix: String?): FimResponse? {
        val apiKey = settings.getApiKey() ?: return null
        val request = FimRequest(
            model = settings.getModel(),
            prompt = prompt,
            suffix = suffix,
            maxTokens = settings.getCompletionMaxTokens(),
            n = settings.getCompletionNumCandidates()
        )
        return executeWithRetry(request, apiKey)
    }

    /** 取消进行中的请求 */
    fun cancel() {
        activeConnection?.disconnect()
    }

    // ---- Internal ----

    private fun executeWithRetry(request: FimRequest, apiKey: String): FimResponse? {
        var lastError: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return execute(request, apiKey)
            } catch (e: FimApiException) {
                if (e.statusCode in 400..499) break  // 4xx 不重试
                lastError = e
            } catch (e: IOException) {
                lastError = e
                // 网络错误继续重试
            }
        }
        throw lastError ?: IOException("Unknown error")
    }

    private fun execute(request: FimRequest, apiKey: String): FimResponse {
        val jsonBody = gson.toJson(request)
        val url = URI.create(FIM_ENDPOINT).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        activeConnection = connection
        try {
            // 写入请求体
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
                throw FimApiException(responseCode, "FIM API error $responseCode: $errorBody")
            }

            val responseBody = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            // 使用 TypeToken 方式避免 Kotlin 中 fromJson(String, Class) 的重载歧义
            val responseType = object : com.google.gson.reflect.TypeToken<FimResponse>() {}.type
            return gson.fromJson(responseBody, responseType)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }
}
