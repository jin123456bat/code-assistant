package com.aiassistant.completion

import com.aiassistant.AppSettingsService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * FIM API 异常，携带 HTTP 状态码用于重试判断。
 */
class FimApiException(val statusCode: Int, message: String) : IOException(message)

/**
 * DeepSeek FIM API 客户端。封装 `/beta/completions` 调用，非流式，带超时和重试。
 *
 * v2.0: 网络层迁移到 OkHttp，获得连接池复用、HTTP/2、指数退避重试等能力。
 */
class DeepSeekFimClient(
    private val settings: AppSettingsService = AppSettingsService.getInstance()
) {
    companion object {
        private const val FIM_ENDPOINT = "https://api.deepseek.com/beta/completions"
        // 补全延迟目标 <500ms，连接超时 2s、读取超时 3s 已足够覆盖 2 次重试（200ms+400ms）
        private const val CONNECT_TIMEOUT_S = 2L
        private const val READ_TIMEOUT_S = 3L
        private const val MAX_RETRIES = 2
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    /**
     * OkHttpClient 单例：连接池 5 个空闲连接保持 5 分钟，连接/读取超时，支持 HTTP/2。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(
                maxIdleConnections = 5,
                keepAliveDuration = 5,
                timeUnit = TimeUnit.MINUTES
            ))
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            // 请求级超时：整个 HTTP 调用（含连接+读取+重定向）上限 3s，
            // 单次调用超时后由 executeWithRetry 的重试逻辑接管
            .callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)  // 关闭 SDK 内置重试，由 executeWithRetry 自行控制指数退避
            .build()
    }

    @Volatile
    private var activeCall: okhttp3.Call? = null

    // ---- Request/Response data classes ----

    data class FimRequest(
        val model: String,
        val prompt: String,
        val suffix: String?,
        @SerializedName("max_tokens") val maxTokens: Int,
        val temperature: Double = 0.0
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
            maxTokens = settings.getCompletionMaxTokens()
        )
        return executeWithRetry(request, apiKey)
    }

    /** 取消进行中的请求 */
    fun cancel() {
        activeCall?.cancel()
    }

    // ---- Internal ----

    /**
     * 带指数退避的重试循环。
     * 退避策略：200ms → 400ms → 800ms（第 1-3 次尝试，即 0/1/2 次重试）。
     * 4xx 错误不重试（客户端错误），其余 IOException 重试。
     */
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

            // 指数退避：200ms * 2^attempt
            if (attempt < MAX_RETRIES) {
                val delayMs = 200L * (1L shl attempt)
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw lastError ?: IOException("Retry interrupted")
                }
            }
        }
        throw lastError ?: IOException("Unknown error")
    }

    /**
     * 单次 HTTP 调用，使用 OkHttp [call.execute()] 替代 HttpURLConnection。
     */
    private fun execute(request: FimRequest, apiKey: String): FimResponse {
        val jsonBody = gson.toJson(request)
        val httpRequest = Request.Builder()
            .url(FIM_ENDPOINT)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .build()

        val call = httpClient.newCall(httpRequest)
        activeCall = call
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw FimApiException(response.code, "FIM API error ${response.code}: $errorBody")
            }
            val responseBody = response.body?.string() ?: ""
            val responseType = object : com.google.gson.reflect.TypeToken<FimResponse>() {}.type
            return gson.fromJson(responseBody, responseType)
        } finally {
            activeCall = null
        }
    }
}
