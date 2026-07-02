package com.aiassistant

import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek API Key 校验。
 * 通过简单的 HTTP GET 请求 /v1/models 端点验证 key 有效性。
 */
object ApiKeyValidator {

    enum class ApiKeyState { VALID, INVALID, UNKNOWN }

    fun validate(key: String): ApiKeyState {
        return try {
            val url = URL("https://api.deepseek.com/v1/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            conn.disconnect()
            when (code) {
                200 -> ApiKeyState.VALID
                401 -> ApiKeyState.INVALID
                else -> ApiKeyState.UNKNOWN
            }
        } catch (e: Exception) {
            ApiKeyState.UNKNOWN
        }
    }
}
