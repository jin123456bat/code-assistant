package com.aiassistant.ui.chat

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    @Test
    fun `does not duplicate selection when full file is already attached`() {
        val text = """
            review @UserService.kt

            [File: src/UserService.kt (10 lines)]
            class UserService
            [/File]
        """.trimIndent()

        val expanded = SelectionReferenceResolver.expand(
            text = text,
            displayName = "UserService.kt:2-3",
            content = "class UserService"
        )

        assertFalse(expanded.contains("[Selection from UserService.kt:2-3]"))
    }
}
