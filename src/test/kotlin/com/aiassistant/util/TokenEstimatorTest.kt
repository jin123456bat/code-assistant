package com.aiassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * util.TokenEstimator 委托类测试。
 * 验证委托给 agent.TokenEstimator 的行为一致性。
 */
class TokenEstimatorTest {

    @Test
    fun `委托 estimateTokens 返回正确值`() {
        val tokens = TokenEstimator.estimateTokens("Hello world")
        assertTrue(tokens > 0, "估算值应大于 0")
    }

    @Test
    fun `委托 estimateTokensAsLong 返回 Long 类型`() {
        val tokens: Long = TokenEstimator.estimateTokensAsLong("Hello world")
        assertTrue(tokens > 0L, "Long 估算值应大于 0")
    }

    @Test
    fun `空字符串委托返回 0`() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
        assertEquals(0L, TokenEstimator.estimateTokensAsLong(""))
    }

    @Test
    fun `委托结果与 agent TokenEstimator 一致`() {
        val text = "import kotlin.test.Test; class Foo { fun bar() = 42 }"
        assertEquals(
            com.aiassistant.agent.TokenEstimator.estimateTokens(text),
            TokenEstimator.estimateTokens(text),
            "委托类应与 agent.TokenEstimator 返回相同结果"
        )
        assertEquals(
            com.aiassistant.agent.TokenEstimator.estimateTokensAsLong(text),
            TokenEstimator.estimateTokensAsLong(text),
            "委托类 estimateTokensAsLong 应与 agent.TokenEstimator 返回相同结果"
        )
    }

    @Test
    fun `中文文本委托估算正确`() {
        val text = "这是一个测试中文文本的令牌估算"
        val tokens = TokenEstimator.estimateTokens(text)
        assertTrue(tokens > 0, "中文文本应返回正数 token")
    }

    @Test
    fun `大文本委托估算不溢出`() {
        val text = "hello ".repeat(1000) // ~6000 字符
        val tokens = TokenEstimator.estimateTokens(text)
        val tokensLong = TokenEstimator.estimateTokensAsLong(text)
        assertTrue(tokens > 0, "大文本估算应返回正数")
        assertTrue(tokensLong > 0, "大文本 Long 估算应返回正数")
        assertEquals(tokens.toLong(), tokensLong, "两种返回类型应该一致")
    }
}
