package com.aiassistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsServiceTest {

    @Test
    fun `AVAILABLE_MODELS 只包含固定 deepseek-v4-pro`() {
        val models = AppSettingsService.AVAILABLE_MODELS
        assertEquals(listOf("deepseek-v4-pro" to "DeepSeek V4 Pro"), models)
    }

    @Test
    fun `AVAILABLE_MODELS 条目包含 ID 和显示名`() {
        val proModel = AppSettingsService.AVAILABLE_MODELS.first { it.first == "deepseek-v4-pro" }
        assertEquals("DeepSeek V4 Pro", proModel.second)
    }

    @Test
    fun `Agent 并发上限允许 0 表示不限`() {
        assertEquals(0, AppSettingsService.normalizeAgentMaxConcurrency(0))
    }

    @Test
    fun `DEFAULT_COMMIT_PROMPT 非空且包含 Conventional Commits 关键字`() {
        val prompt = AppSettingsService.DEFAULT_COMMIT_PROMPT
        assertTrue(prompt.isNotBlank(), "默认 commit prompt 不应为空")
        assertTrue(prompt.contains("Conventional Commits"), "应包含 Conventional Commits")
    }

    @Test
    fun `DEFAULT_COMMIT_PROMPT_ZH 非空且为中文`() {
        val promptZh = AppSettingsService.DEFAULT_COMMIT_PROMPT_ZH
        assertTrue(promptZh.isNotBlank(), "默认中文 commit prompt 不应为空")
        assertTrue(promptZh.contains("Conventional Commits"), "应包含 Conventional Commits")
    }

}
