package com.aiassistant

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.MessageCreateParams

/**
 * DeepSeek API Key 校验。
 * 返回 "valid" / "invalid" / "unknown"（网络不可达）。
 */
object ApiKeyValidator {

    fun validate(key: String): String {
        return try {
            val client = AnthropicOkHttpClient.builder()
                .baseUrl("https://api.deepseek.com/anthropic")
                .apiKey(key)
                .build()
            val params = MessageCreateParams.builder()
                .model("deepseek-v4-pro")
                .maxTokens(1)
                .addUserMessage("hi")
                .build()
            client.beta().messages().create(params)
            "valid"
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) "invalid"
            else "unknown"
        }
    }
}
