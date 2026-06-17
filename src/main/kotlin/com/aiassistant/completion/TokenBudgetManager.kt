package com.aiassistant.completion

/**
 * 根据用户设置的 maxTokens 动态分配 prefix/suffix/smartContext 的输入预算。
 * 总上下文窗口: 16K tokens，字符估算: 1 token ≈ 4 chars。
 */
class TokenBudgetManager(private val userMaxTokens: Int) {

    companion object {
        private const val TOTAL_WINDOW_TOKENS = 16_384   // 16K
        private const val CHARS_PER_TOKEN = 4

        /** prefix 占 50% */
        private const val PREFIX_RATIO = 0.5
        /** suffix 占 25% */
        private const val SUFFIX_RATIO = 0.25
        /** smartContext 占 25% */
        private const val SMART_CTX_RATIO = 0.25
    }

    /** 可用的输入 token 总数 */
    val availableInputTokens: Int = (TOTAL_WINDOW_TOKENS - userMaxTokens).coerceAtLeast(1)

    /** prefix 字符上限 */
    val maxPrefixChars: Int = (availableInputTokens * PREFIX_RATIO * CHARS_PER_TOKEN).toInt()

    /** suffix 字符上限 */
    val maxSuffixChars: Int = (availableInputTokens * SUFFIX_RATIO * CHARS_PER_TOKEN).toInt()

    /** smartContext 字符上限 */
    val maxSmartContextChars: Int = (availableInputTokens * SMART_CTX_RATIO * CHARS_PER_TOKEN).toInt()
}
