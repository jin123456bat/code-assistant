package com.aiassistant.agent

/**
 * 统一的 Token 估算工具。
 *
 * 启发式估算，误差约 ±20%。API 返回的 `usage` 字段为精确值，应优先使用。
 * 估算仅用于"写入前"的场景（compact 阈值判定、输入框预览），持久化时使用 API 返回的精确值。
 *
 * 公式：英文/代码 ~4 字节/token，中文 ~0.67 token/字符（即 1.5 字符/token）
 *   取 max(bytes / 4, asciiOnly / 4 + (nonAscii * 3) / 2)
 *
 * 适用场景及上限（对齐 docs/specs/token-estimation.md）：
 * | 场景                        | 上限                     | 方法                         |
 * |----------------------------|------------------------|-----------------------------|
 * | Auto-Compact 触发判定         | 1M x 0.7 = 700K tokens | `estimateTokens()` 估算      |
 * | 输入框实时 token 预览            | 无上限（仅展示）               | `estimateTokens()` 估算      |
 * | `session.totalTokens` 持久化 | 精确值                    | API `usage` 字段，fallback 估算 |
 * | 子 Agent 结果摘要截断            | 2000 tokens            | `estimateTokens()` 估算截断点   |
 */
object TokenEstimator {

    /**
     * 估算给定文本的 token 数量。
     *
     * 统一启发式：英文字节/4，中文字符*3/2，取 max。
     * 误差 ±20%，持久化时使用 API 返回的精确值。
     *
     * @param text 待估算的文本，空字符串返回 0
     * @return 估算的 token 数量
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val bytes = text.encodeToByteArray().size
        val asciiOnly = text.count { it.code <= 127 }
        val nonAscii = text.length - asciiOnly
        // 英文/代码 ~4 字节/token，中文 ~0.67 token/字符（即 1.5 字符/token）
        // 极短文本整数除法可能归零，非空文本至少返回 1 token
        return maxOf(1, bytes / 4, asciiOnly / 4 + (nonAscii * 3) / 2)
    }

    /**
     * 估算给定文本的 token 数量，返回 Long 类型。
     * 用于与 API 返回的精确 token 数保持类型一致。
     */
    fun estimateTokensAsLong(text: String): Long = estimateTokens(text).toLong()
}
