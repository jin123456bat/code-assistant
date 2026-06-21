package com.aiassistant

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Application-level service for securely storing the DeepSeek API key
 * via IntelliJ PasswordSafe / CredentialStore.
 */
@Service(Service.Level.APP)
class AppSettingsService {

    private val credentialAttributes = CredentialAttributes(
        "$SERVICE_NAME.API_KEY"
    )

    @Volatile
    private var cachedApiKey: String? = null

    @Volatile
    private var apiKeyLoaded = false

    init {
        // 后台预加载 API Key，避免 EDT 调用 PasswordSafe 触发慢操作告警
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            if (!apiKeyLoaded) {
                cachedApiKey = PasswordSafe.instance.get(credentialAttributes)?.getPasswordAsString()
                apiKeyLoaded = true
            }
        }
    }

    companion object {
        private const val SERVICE_NAME = "AI_Coding_Assistant"
        private const val PROMPT_KEY = "$SERVICE_NAME.PROMPT"
        private const val WHITELIST_KEY = "$SERVICE_NAME.COMMAND_WHITELIST"
        private const val MODEL_KEY = "$SERVICE_NAME.MODEL"
        private const val THINKING_KEY = "$SERVICE_NAME.THINKING"
        private const val COMPACT_RATIO_KEY = "$SERVICE_NAME.COMPACT_RATIO"
        private const val COMPLETION_ENABLED_KEY = "$SERVICE_NAME.COMPLETION.ENABLED"
        private const val COMPLETION_MAX_TOKENS_KEY = "$SERVICE_NAME.COMPLETION.MAX_TOKENS"
        private const val COMPLETION_DEBOUNCE_MS_KEY = "$SERVICE_NAME.COMPLETION.DEBOUNCE_MS"
        private const val COMPLETION_NUM_CANDIDATES_KEY = "$SERVICE_NAME.COMPLETION.NUM_CANDIDATES"
        private const val COMPLETION_MANUAL_SHORTCUT_KEY = "$SERVICE_NAME.COMPLETION.MANUAL_SHORTCUT"
        private const val COMPLETION_PREV_CANDIDATE_KEY = "$SERVICE_NAME.COMPLETION.PREV_CANDIDATE"
        private const val COMPLETION_NEXT_CANDIDATE_KEY = "$SERVICE_NAME.COMPLETION.NEXT_CANDIDATE"
        private const val MEMORY_ENABLED_KEY = "$SERVICE_NAME.MEMORY_ENABLED"
        private const val TOKEN_DISPLAY_KEY = "$SERVICE_NAME.TOKEN_DISPLAY"

        fun isTokenDisplayEnabled(): Boolean =
            com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(TOKEN_DISPLAY_KEY, true)

        fun setTokenDisplayEnabled(enabled: Boolean) {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(TOKEN_DISPLAY_KEY, enabled, true)
        }

        fun isMemoryEnabled(): Boolean {
            val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(MEMORY_ENABLED_KEY)
            return raw == null || raw.toBooleanStrictOrNull() != false
        }

        fun setMemoryEnabled(enabled: Boolean) {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(MEMORY_ENABLED_KEY, enabled.toString())
        }

        val AVAILABLE_MODELS = listOf(
            "deepseek-v4-flash" to "DeepSeek V4 Flash (快速/工具调用)",
            "deepseek-v4-pro" to "DeepSeek V4 Pro (复杂编码/深度推理)"
        )

        fun getInstance(): AppSettingsService = service()

        val DEFAULT_COMMIT_PROMPT = """
You are an expert Git commit message generator. Analyze the git diff provided in the user message (including changed files list, stat summary, and recent commit history) and create an ACCURATE, SPECIFIC commit message following conventional commit standards.

## CRITICAL — Accuracy Rules
- ONLY describe changes that actually appear in the diff. Never invent or guess.
- Reference specific function names, class names, variable names, or file names that were ACTUALLY changed.
- If you see "Deleted line: someFunction()", mention "remove someFunction". Be concrete.
- Do NOT use vague phrases like "update code", "improve functionality", "fix issues".
- The scope MUST match the actual module/directory/component being modified (infer from file paths).

## Analysis Steps
1. Look at "Changed files" — what modules/components were affected? This determines scope.
2. Look at "Changes summary" — which files had the most changes? Focus on those.
3. Read the actual diff — what specific functions/classes/variables were added, removed, or modified?
4. Determine WHY: was this a bug fix (handling edge case, null check), a new feature (new function/endpoint), or refactoring (extracting, renaming)?
5. Match style with "Recent commits" — use similar tone and structure.

## Commit Structure
```
<type>(<scope>): <specific subject line>

<body — list concrete changes, reference actual identifiers>
```

## Types
feat | fix | chore | docs | style | refactor | test | perf | build | ci

## Anti-Patterns (NEVER DO THIS)
- "update code" — too vague
- "fix bug" — what bug? how?
- "improve performance" — what was optimized?
- "refactor" — what was refactored?
- Adding changes that don't exist in the diff

## Examples
✅ Good: "fix(auth): handle null session token in refreshToken()"
✅ Good: "feat(api): add rate limiting to /users endpoint with Redis counter"
❌ Bad: "update auth module"
❌ Bad: "fix bugs and improve code"

Output ONLY the commit message. No markdown fences, no explanations.

{diff}
        """.trimIndent()

        val DEFAULT_COMMIT_PROMPT_ZH = """
你是一位 Git 提交信息生成专家。请分析用户消息中提供的 git diff（包含修改文件列表、变更统计、最近提交记录），生成一条准确、具体的 commit message，遵循 conventional commit 规范。

## 关键 — 准确度规则
- 只描述 diff 中实际出现的改动，绝不编造或猜测
- 引用 diff 中实际出现的函数名、类名、变量名、文件名
- 如果看到"删除行: handleTimeout()"，就写"移除 handleTimeout()"，要具体
- 禁止使用模糊描述："更新代码"、"改进功能"、"修复bug"、"优化"
- scope 必须匹配实际被修改的模块/目录/组件（从文件路径推断）

## 分析步骤
1. 看"Changed files" — 哪些模块/组件被修改？决定 scope
2. 看"Changes summary" — 哪些文件改动量最大？重点关注
3. 仔细读 diff 内容 — 具体添加/删除/修改了哪些函数、类、变量？
4. 判断目的：是修 bug（空指针处理、边界条件）？新功能（新增函数/端点）？还是重构（提取、重命名）？
5. 参考 "Recent commits" 的提交风格

## 提交结构
```
<type>(<scope>): <具体的主题行>

<正文 — 列出具体改动，引用实际的标识符名>
```

## 类型说明
feat(新功能) | fix(修复) | chore(杂项) | docs(文档) | style(格式) | refactor(重构) | test(测试) | perf(性能) | build(构建) | ci(持续集成)

## 反面示例（绝对不要这样写）
- "更新代码" — 太模糊
- "修复bug" — 修了什么bug？怎么修的？
- "优化性能" — 优化了什么？
- "重构" — 重构了什么？
- 不要在正文中编造 diff 里不存在的改动

## 示例
✅ 好："fix(auth): 修复 refreshToken() 中 session 为 null 的问题"
✅ 好："feat(api): 为 /users 接口添加基于 Redis 计数器的限流"
✅ 好："refactor(payment): 将价格计算逻辑提取到 PriceCalculator"
❌ 差："更新 auth 模块"
❌ 差："修复一些 bug"

只输出 commit message，不要 markdown 代码块，不要任何额外解释。

{diff}
        """.trimIndent()
    }

    fun getApiKey(): String? {
        // 如果后台预加载已完成，直接返回缓存（EDT 安全）
        if (apiKeyLoaded) return cachedApiKey
        // 预加载未完成时（极少发生：仅在 init 后极短时间内），
        // 同步获取以确保 Settings 对话框正确显示已配置的 Key
        val key = PasswordSafe.instance.get(credentialAttributes)?.getPasswordAsString()
        cachedApiKey = key
        apiKeyLoaded = true
        return key
    }

    fun setApiKey(apiKey: String) {
        cachedApiKey = apiKey
        val credentials = Credentials(null, apiKey)
        PasswordSafe.instance.set(credentialAttributes, credentials)
    }

    fun clearApiKey() {
        cachedApiKey = null
        PasswordSafe.instance.set(credentialAttributes, null)
    }

    fun getPrompt(): String? {
        return com.intellij.ide.util.PropertiesComponent.getInstance().getValue(PROMPT_KEY)
    }

    /** 返回生效的 prompt：有自定义用自定义，否则根据系统语言选择中/英文默认 */
    fun getEffectivePrompt(): String {
        val custom = getPrompt()
        if (custom != null) return custom
        val lang = java.util.Locale.getDefault().language
        return if (lang.equals("zh", ignoreCase = true)) DEFAULT_COMMIT_PROMPT_ZH else DEFAULT_COMMIT_PROMPT
    }

    fun setPrompt(prompt: String?) {
        if (prompt.isNullOrBlank()) {
            com.intellij.ide.util.PropertiesComponent.getInstance().unsetValue(PROMPT_KEY)
        } else {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(PROMPT_KEY, prompt)
        }
    }

    // ---- Tool Whitelist ----

    private val TOOL_WHITELIST_KEY = "$SERVICE_NAME.TOOL_WHITELIST"

    /**
     * 获取工具白名单。
     * 存储格式为逗号分隔，内置工具名均不含逗号。
     * MCP 工具名不可控，含逗号时输出警告日志。
     */
    @Synchronized
    fun getToolWhitelist(): Set<String> {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(TOOL_WHITELIST_KEY) ?: ""
        val result = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        result.forEach { if (it.contains(",")) AppLogger.warn("白名单工具名含逗号: $it") }
        return result
    }

    @Synchronized
    fun addToolToWhitelist(tool: String) {
        val current = getToolWhitelist().toMutableSet()
        current.add(tool.trim())
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(TOOL_WHITELIST_KEY, current.sorted().joinToString(","))
    }

    @Synchronized
    fun removeToolFromWhitelist(tool: String) {
        val current = getToolWhitelist().toMutableSet()
        current.remove(tool.trim())
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(TOOL_WHITELIST_KEY, current.sorted().joinToString(","))
    }

    // ---- Command Whitelist ----

    @Synchronized
    fun getCommandWhitelist(): Set<String> {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(WHITELIST_KEY) ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    @Synchronized
    fun addCommandToWhitelist(command: String) {
        val current = getCommandWhitelist().toMutableSet()
        current.add(command.trim())
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(WHITELIST_KEY, current.sorted().joinToString(","))
    }

    @Synchronized
    fun removeCommandFromWhitelist(command: String) {
        val current = getCommandWhitelist().toMutableSet()
        current.remove(command.trim())
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(WHITELIST_KEY, current.sorted().joinToString(","))
    }

    fun getModel(): String {
        return com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(MODEL_KEY, "deepseek-v4-pro")
    }

    fun setModel(model: String) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(MODEL_KEY, model)
    }

    /** 思考模式开关，默认开启 */
    fun isThinkingEnabled(): Boolean {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(THINKING_KEY)
        return raw == null || raw.toBooleanStrictOrNull() != false
    }

    fun setThinkingEnabled(enabled: Boolean) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(THINKING_KEY, enabled.toString())
    }

    /** 自动 Compact 触发比例（0.1-1.0），默认 0.9 = 占上下文窗口 90% 时触发 */
    fun getCompactRatio(): Double {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPACT_RATIO_KEY)
        return raw?.toDoubleOrNull()?.coerceIn(0.1, 1.0) ?: 0.9
    }

    fun setCompactRatio(ratio: Double) {
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMPACT_RATIO_KEY, ratio.coerceIn(0.1, 1.0).toString())
    }

    // ---- 补全设置 ----

    fun isCompletionEnabled(): Boolean {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_ENABLED_KEY)
        return raw == null || raw.toBooleanStrictOrNull() != false
    }

    fun setCompletionEnabled(enabled: Boolean) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_ENABLED_KEY, enabled.toString())
    }

    fun getCompletionMaxTokens(): Int {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_MAX_TOKENS_KEY)
        return raw?.toIntOrNull()?.coerceIn(1, 1024) ?: 1024
    }

    fun setCompletionMaxTokens(tokens: Int) {
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMPLETION_MAX_TOKENS_KEY, tokens.coerceIn(1, 1024).toString())
    }

    fun getCompletionDebounceMs(): Int {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_DEBOUNCE_MS_KEY)
        return raw?.toIntOrNull()?.coerceIn(100, 2000) ?: 300
    }

    fun setCompletionDebounceMs(ms: Int) {
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMPLETION_DEBOUNCE_MS_KEY, ms.coerceIn(100, 2000).toString())
    }

    fun getCompletionNumCandidates(): Int {
        val raw = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(COMPLETION_NUM_CANDIDATES_KEY)
        return raw?.toIntOrNull()?.coerceIn(1, 10) ?: 10
    }

    fun setCompletionNumCandidates(n: Int) {
        com.intellij.ide.util.PropertiesComponent.getInstance()
            .setValue(COMPLETION_NUM_CANDIDATES_KEY, n.coerceIn(1, 10).toString())
    }

    fun getCompletionManualShortcut(): String {
        val default = if (System.getProperty("os.name").lowercase().contains("mac")) "meta P" else "alt P"
        return com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(COMPLETION_MANUAL_SHORTCUT_KEY, default)
    }

    fun setCompletionManualShortcut(shortcut: String) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_MANUAL_SHORTCUT_KEY, shortcut)
    }

    fun getCompletionPrevCandidateKey(): String {
        return com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(COMPLETION_PREV_CANDIDATE_KEY, "UP")
    }

    fun setCompletionPrevCandidateKey(key: String) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_PREV_CANDIDATE_KEY, key)
    }

    fun getCompletionNextCandidateKey(): String {
        return com.intellij.ide.util.PropertiesComponent.getInstance()
            .getValue(COMPLETION_NEXT_CANDIDATE_KEY, "DOWN")
    }

    fun setCompletionNextCandidateKey(key: String) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(COMPLETION_NEXT_CANDIDATE_KEY, key)
    }
}
