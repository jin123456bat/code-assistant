package com.aiassistant.ui.chat

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SelectionReferenceResolverTest {

    @Test
    fun `expands selected code after user text`() {
        val expanded = SelectionReferenceResolver.expand(
            text = "explain this",
            displayName = "UserService.kt:40-42",
            content = "fun getUser() = user\n"
        )

        assertContains(expanded, "explain this")
        assertContains(expanded, "[Selection from UserService.kt:40-42]")
        assertContains(expanded, "fun getUser() = user")
        assertContains(expanded, "[/Selection]")
    }

    @Test
    fun `keeps text unchanged for blank selection`() {
        assertEquals(
            "explain this",
            SelectionReferenceResolver.expand("explain this", "App.kt:1-1", "   ")
        )
    }
}
