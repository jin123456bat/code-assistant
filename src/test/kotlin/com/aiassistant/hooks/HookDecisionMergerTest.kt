package com.aiassistant.hooks

import org.junit.Test
import org.junit.Assert.*

class HookDecisionMergerTest {
    @Test fun `merge returns empty decision for empty list`() {
        val result = HookDecisionMerger.merge(emptyList())
        assertEquals("allow", result.permissionDecision)
        assertNull(result.content)
    }

    @Test fun `merge deny overrides all`() {
        val decisions = listOf(
            HookDecision("allow", "ok"),
            HookDecision("deny", "blocked"),
            HookDecision("allow", "also ok")
        )
        val result = HookDecisionMerger.merge(decisions)
        assertEquals("deny", result.permissionDecision)
        assertEquals("blocked", result.content)
    }

    @Test fun `merge collects contents when all allow`() {
        val decisions = listOf(
            HookDecision("allow", "msg1"),
            HookDecision("allow", "msg2")
        )
        val result = HookDecisionMerger.merge(decisions)
        assertEquals("allow", result.permissionDecision)
        assertTrue(result.content!!.contains("msg1"))
        assertTrue(result.content!!.contains("msg2"))
    }

    @Test fun `merge filters null decisions`() {
        val decisions = listOf(null, HookDecision("allow", "msg"), null)
        val result = HookDecisionMerger.merge(decisions)
        assertEquals("allow", result.permissionDecision)
        assertEquals("msg", result.content)
    }

    @Test fun `merge skips blank content`() {
        val decisions = listOf(HookDecision("allow", ""), HookDecision("allow", "  "))
        val result = HookDecisionMerger.merge(decisions)
        assertNull(result.content)
    }
}
