package com.aiassistant.completion

/**
 * 输入上下文字符预算管理。以 16K 字符为上限，去掉 token 估算。
 */
object CharBudgetManager {
    /** 16K 字符上限 */
    const val MAX_CHARS = 16_384

    /** prefix 占比 2/3 */
    const val PREFIX_RATIO = 2.0 / 3.0

    /** suffix 占比 1/3 */
    const val SUFFIX_RATIO = 1.0 / 3.0
}
