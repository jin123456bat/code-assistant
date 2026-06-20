package com.aiassistant.hooks

import org.junit.Test
import org.junit.Assert.*

class HookMatcherTest {
    @Test fun `null matcher matches all`() {
        assertTrue(HookMatcher.matches(null, "write_file"))
        assertTrue(HookMatcher.matches(null, null))
    }

    @Test fun `blank matcher matches all`() {
        assertTrue(HookMatcher.matches("", "execute_command"))
    }

    @Test fun `regex matches tool name`() {
        assertTrue(HookMatcher.matches("write_file|edit_file", "write_file"))
    }

    @Test fun `regex does not match different tool`() {
        assertFalse(HookMatcher.matches("write_file", "execute_command"))
    }

    @Test fun `null tool name with non-null matcher returns false`() {
        assertFalse(HookMatcher.matches("write_file", null))
    }

    @Test fun `invalid regex returns false`() {
        assertFalse(HookMatcher.matches("[invalid(", "any_tool"))
    }
}
