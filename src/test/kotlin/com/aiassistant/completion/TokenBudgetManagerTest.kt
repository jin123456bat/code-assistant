package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class TokenBudgetManagerTest {
    @Test
    fun `should allocate budget inversely to maxTokens`() {
        val small = TokenBudgetManager(128)
        val large = TokenBudgetManager(1024)
        assertTrue(small.availableInputTokens > large.availableInputTokens)
        assertTrue(small.maxPrefixChars > large.maxPrefixChars)
        assertTrue(small.maxSuffixChars > large.maxSuffixChars)
    }

    @Test
    fun `should not produce negative budget`() {
        val max = TokenBudgetManager(16000)
        assertTrue(max.availableInputTokens >= 1)
        assertTrue(max.maxPrefixChars >= 1)
    }

    @Test
    fun `should maintain 2 to 1 prefix to suffix ratio`() {
        val budget = TokenBudgetManager(512)
        val ratio = budget.maxPrefixChars.toDouble() / budget.maxSuffixChars.toDouble()
        assertEquals(2.0, ratio, 0.2)
    }
}
