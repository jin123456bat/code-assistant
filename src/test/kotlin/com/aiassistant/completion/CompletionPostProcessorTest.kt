package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class CompletionPostProcessorTest {
    @Test
    fun `should trim suffix overlap`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("function getUser() {\n    return null;\n}\n", 0, "stop")
        )
        val suffix = "function getUser() {\n    return null;\n}\n"
        val processed = CompletionPostProcessor.process(choices, "", suffix)
        assertTrue(processed.isEmpty())
    }

    @Test
    fun `should filter short results`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("ab", 0, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertTrue(processed.isEmpty())
    }

    @Test
    fun `should filter blank results`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("   \n  \n ", 0, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertTrue(processed.isEmpty())
    }

    @Test
    fun `should keep valid completion`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("    return userRepository.findById(id);\n}", 0, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertEquals(1, processed.size)
        assertEquals("    return userRepository.findById(id);\n}", processed[0])
    }

    @Test
    fun `should deduplicate identical candidates`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("return x;", 0, "stop"),
            DeepSeekFimClient.FimChoice("return x;", 1, "stop")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertEquals(1, processed.size)
    }

    @Test
    fun `should filter content_filter`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("blocked content", 0, "content_filter")
        )
        val processed = CompletionPostProcessor.process(choices, "", "")
        assertTrue(processed.isEmpty())
    }

    @Test
    fun `should trim prefix overlap`() {
        val choices = listOf(
            DeepSeekFimClient.FimChoice("return x;\n    .filter { it > 0 }\n}", 0, "stop")
        )
        val prefix = "return x;"
        val processed = CompletionPostProcessor.process(choices, prefix, "")
        assertEquals(1, processed.size)
        assertEquals("\n    .filter { it > 0 }\n}", processed[0])
    }
}
