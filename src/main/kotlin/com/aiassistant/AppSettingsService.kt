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
        private const val AGENT_MAX_LOOPS_KEY = "$SERVICE_NAME.AGENT.MAX_LOOPS"
        private const val AGENT_MAX_CONCURRENCY_KEY = "$SERVICE_NAME.AGENT.MAX_CONCURRENCY"
        private const val COMMIT_ENABLED_KEY = "$SERVICE_NAME.COMMIT.ENABLED"
        private const val FIXED_MODEL = "deepseek-v4-pro"
        val AVAILABLE_MODELS = listOf(
            FIXED_MODEL to "DeepSeek V4 Pro"
        )

        val DEFAULT_COMMIT_PROMPT_ZH = """请基于以下 git diff 生成一条 Conventional Commits 规范的 commit message。

{diff}

要求：
- 使用中文描述
- 格式：<type>(<scope>): <description>
- type 可选：feat, fix, refactor, chore, docs, test, style, perf
"""

        val DEFAULT_COMMIT_PROMPT = """Generate a concise git commit message following Conventional Commits (feat:/fix:/refactor:/chore:/docs:/test:). Output ONLY the commit message, no explanations.

{diff}
"""

        val DEFAULT_MERGE_COMMIT_PROMPT =
            """Generate a concise merge commit message describing the merged content.""".trimIndent()

        val DEFAULT_MERGE_COMMIT_PROMPT_ZH =
            """生成一个简洁的 merge commit message，描述合并的内容。""".trimIndent()

        fun getInstance(): AppSettingsService = service()
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

    fun getModel(): String {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(MODEL_KEY, FIXED_MODEL)
        return raw?.takeIf { it == FIXED_MODEL } ?: FIXED_MODEL
    }

    fun setModel(model: String) =
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(MODEL_KEY, model.takeIf { it == FIXED_MODEL } ?: FIXED_MODEL)

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

    fun setCompletionMaxTokens(tokens: Int) =
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMPLETION_MAX_TOKENS_KEY, tokens.coerceIn(1, 1024).toString())

    fun getAgentMaxLoops(): Int {
        val raw =
            com.intellij.ide.util.PropertiesComponent.getInstance().getValue(AGENT_MAX_LOOPS_KEY)
        return raw?.toIntOrNull()?.coerceAtLeast(0) ?: 20
    }

    fun setAgentMaxLoops(loops: Int) =
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(AGENT_MAX_LOOPS_KEY, loops.coerceAtLeast(0).toString())

    fun getAgentMaxConcurrency(): Int {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(AGENT_MAX_CONCURRENCY_KEY)
        return raw?.toIntOrNull()?.coerceIn(1, 10) ?: 3
    }

    fun setAgentMaxConcurrency(concurrency: Int) =
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(AGENT_MAX_CONCURRENCY_KEY, concurrency.coerceIn(1, 10).toString())

    /**
     * GenerateCommitAction 启用开关，默认 true。
     * 文档 §九 未要求暴露此开关到 Settings UI，但代码需要此配置项支撑功能。
     */
    fun isCommitEnabled(): Boolean {
        val raw =
            com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMMIT_ENABLED_KEY)
        return raw == null || raw.toBooleanStrictOrNull() != false
    }

    fun setCommitEnabled(enabled: Boolean) =
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMMIT_ENABLED_KEY, enabled.toString())
}
