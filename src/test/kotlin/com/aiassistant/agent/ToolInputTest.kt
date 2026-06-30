package com.aiassistant.agent

import com.anthropic.core.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolInputTest {

    @Test
    fun `extracts values from Anthropic JsonValue input`() {
        val input = JsonValue.from(
            mapOf(
                "filePath" to "src/main/kotlin/App.kt",
                "startLine" to 12
            )
        )

        assertEquals("src/main/kotlin/App.kt", ToolInput.string(input, "filePath"))
        assertEquals(12, ToolInput.int(input, "startLine"))
    }

    @Test
    fun `extracts values from plain map input`() {
        val input = mapOf(
            "command" to "./gradlew test",
            "maxDepth" to "3"
        )

        assertEquals("./gradlew test", ToolInput.string(input, "command"))
        assertEquals(3, ToolInput.int(input, "maxDepth"))
    }

    @Test
    fun `extracts string lists from Anthropic JsonValue input`() {
        val input = JsonValue.from(
            mapOf(
                "allowedDomains" to listOf("example.com", "docs.example.com")
            )
        )

        assertEquals(
            listOf("example.com", "docs.example.com"),
            ToolInput.stringList(input, "allowedDomains")
        )
    }
}
