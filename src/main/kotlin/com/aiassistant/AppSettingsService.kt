package com.aiassistant

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class AppSettingsService {

    private val credentialAttributes = CredentialAttributes("$SERVICE_NAME.API_KEY")
    @Volatile
    private var cachedApiKey: String? = null
    @Volatile
    private var apiKeyLoaded = false

    init {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            if (!apiKeyLoaded) {
                cachedApiKey = PasswordSafe.instance.get(credentialAttributes)
                    ?.getPasswordAsString(); apiKeyLoaded = true
            }
        }
    }

    companion object {
        private const val SERVICE_NAME = "AI_Coding_Assistant"
        private const val MODEL_KEY = "$SERVICE_NAME.MODEL"
        private const val PROMPT_KEY = "$SERVICE_NAME.PROMPT"
        private const val COMPLETION_ENABLED_KEY = "$SERVICE_NAME.COMPLETION.ENABLED"
        private const val COMPLETION_MAX_TOKENS_KEY = "$SERVICE_NAME.COMPLETION.MAX_TOKENS"
        private const val TOKEN_DISPLAY_KEY = "$SERVICE_NAME.TOKEN_DISPLAY"

        val AVAILABLE_MODELS = listOf(
            "deepseek-v4-flash" to "DeepSeek V4 Flash",
            "deepseek-v4-pro" to "DeepSeek V4 Pro"
        )

        val DEFAULT_COMMIT_PROMPT =
            """Generate a concise git commit message following Conventional Commits (feat:/fix:/refactor:/chore:/docs:/test:). Output ONLY the commit message, no explanations. The diff is provided in the user message.""".trimIndent()

        val DEFAULT_COMMIT_PROMPT_ZH =
            """根据以下 git diff 生成简洁的中文 git commit message，遵循 Conventional Commits 规范（feat:/fix:/refactor:/chore:/docs:/test:）。只输出 commit message，不要解释。Diff 在用户消息中提供。""".trimIndent()

        fun getInstance(): AppSettingsService = service()

        fun isTokenDisplayEnabled(): Boolean {
            return com.intellij.ide.util.PropertiesComponent.getInstance()
                .getBoolean(TOKEN_DISPLAY_KEY, false)
        }
    }

    fun getApiKey(): String? {
        if (!apiKeyLoaded) {
            cachedApiKey = PasswordSafe.instance.get(credentialAttributes)
                ?.getPasswordAsString(); apiKeyLoaded = true
        }
        return cachedApiKey?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(key: String) {
        cachedApiKey = key.takeIf { it.isNotBlank() }; apiKeyLoaded = true
        PasswordSafe.instance.set(credentialAttributes, Credentials(null, key))
    }

    fun getModel(): String = com.intellij.ide.util.PropertiesComponent.getInstance()
        .getValue(MODEL_KEY, "deepseek-v4-pro") ?: "deepseek-v4-pro"

    fun setModel(model: String) =
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(MODEL_KEY, model)

    fun getModelDisplayName(): String =
        AVAILABLE_MODELS.firstOrNull { it.first == getModel() }?.second ?: getModel()

    fun getPrompt(): String =
        com.intellij.ide.util.PropertiesComponent.getInstance().getValue(PROMPT_KEY) ?: ""

    fun setPrompt(prompt: String) =
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(PROMPT_KEY, prompt)

    fun isCompletionEnabled(): Boolean {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_ENABLED_KEY)
        return raw == null || raw.toBooleanStrictOrNull() != false
    }
    fun setCompletionEnabled(enabled: Boolean) =
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMPLETION_ENABLED_KEY, enabled.toString())

    fun getCompletionMaxTokens(): Int {
        // 补全延迟目标 <500ms，maxTokens 直接影响生成时间。
        // 256 tokens 足够覆盖 ~50 行代码，同时保持低延迟。
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_MAX_TOKENS_KEY)
        return raw?.toIntOrNull()?.coerceIn(1, 1024) ?: 256
    }
}
