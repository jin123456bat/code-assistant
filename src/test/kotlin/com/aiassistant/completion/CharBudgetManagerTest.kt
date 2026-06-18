package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class CharBudgetManagerTest {
    @Test
    fun `should have correct max chars constant`() {
        assertEquals(16_384, CharBudgetManager.MAX_CHARS)
    }

    @Test
    fun `should have prefix ratio of 2 to 3`() {
        assertEquals(2.0 / 3.0, CharBudgetManager.PREFIX_RATIO, 0.01)
    }

    @Test
    fun `should have suffix ratio of 1 to 3`() {
        assertEquals(1.0 / 3.0, CharBudgetManager.SUFFIX_RATIO, 0.01)
    }

    @Test
    fun `prefix and suffix ratios should sum to 1`() {
        assertEquals(1.0, CharBudgetManager.PREFIX_RATIO + CharBudgetManager.SUFFIX_RATIO, 0.01)
    }
}
