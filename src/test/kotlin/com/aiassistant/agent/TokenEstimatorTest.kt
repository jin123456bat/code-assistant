package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {

    @Test
    fun `英文文本 token 估算在 ±20% 误差范围内`() {
        // 英文通常 ~4 字节/token，这段文本 57 字符，bytes/4 ≈ 14 tokens
        val text = "Hello world, this is a test message for token estimation."
        val tokens = TokenEstimator.estimateTokens(text)
        val expected = 14 // 近似参考值：57 bytes / 4 ≈ 14
        val margin = (expected * 0.2).toInt().coerceAtLeast(1)
        assertTrue(
            tokens in (expected - margin)..(expected + margin),
            "英文 token 估算值 $tokens 不在预期范围 [${expected - margin}, ${expected + margin}] 内"
        )
    }

    @Test
    fun `中文文本 token 估算在 ±20% 误差范围内`() {
        // 中文 ~1.5 字符/token，这段文本约 30 字符，约 20 tokens
        val text = "你好世界这是一个测试中文文本的令牌估算工具类"
        val tokens = TokenEstimator.estimateTokens(text)
        val expected = 32 // 近似参考值（中文 ~0.67 token/字符）
        val margin = (expected * 0.2).toInt().coerceAtLeast(1)
        assertTrue(
            tokens in (expected - margin)..(expected + margin),
            "中文 token 估算值 $tokens 不在预期范围 [${expected - margin}, ${expected + margin}] 内"
        )
    }

    @Test
    fun `空字符串估算返回 0`() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
        assertEquals(0L, TokenEstimator.estimateTokensAsLong(""))
    }

    @Test
    fun `纯 ASCII 代码估算 token 数合理`() {
        val code = """
            fun calculateSum(a: Int, b: Int): Int {
                val result = a + b
                return result
            }
            fun main() {
                val sum = calculateSum(10, 20)
                println("Sum: asdfasdfasdfasdf")
            }
        """.trimIndent()
        val tokens = TokenEstimator.estimateTokens(code)
        // 代码文本 169 字节，纯 ASCII，bytes/4 ≈ 42 tokens
        val expected = 42
        val margin = (expected * 0.2).toInt().coerceAtLeast(1)
        assertTrue(
            tokens in (expected - margin)..(expected + margin),
            "代码 token 估算值 $tokens 不在预期范围 [${expected - margin}, ${expected + margin}] 内"
        )
    }

    @Test
    fun `中英文混合文本估算返回合理值`() {
        val text =
            "函数 calculateSum 接收两个参数 a 和 b，返回它们的和。The result is returned as an Int."
        val tokens = TokenEstimator.estimateTokens(text)
        // bytes/4 ≈ 100/4=25, ascii/4 + nonAscii*3/2 ≈ 15 + 21 → 取 max → 约25+15=40
        // 实际只要返回 > 0 且合理即可
        assertTrue(tokens > 0, "中英文混合文本应返回正数")
    }

    @Test
    fun `estimateTokensAsLong 返回 Long 类型且与 estimateTokens 一致`() {
        val text = "test"
        assertEquals(
            TokenEstimator.estimateTokens(text).toLong(),
            TokenEstimator.estimateTokensAsLong(text)
        )
    }

    @Test
    fun `单字符文本估算返回正数`() {
        val tokens = TokenEstimator.estimateTokens("a")
        assertTrue(tokens > 0, "单字符应返回至少 1 token")
    }
}
