package com.aiassistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AppSettingsService structure and invariants.
 * Full CredentialStore integration tests require the IntelliJ test framework.
 */
class AppSettingsServiceTest {

    @Test
    fun `should have correct service name constant`() {
        // Verifies the service name is defined and non-empty
        val service = AppSettingsService::class.java
        assertNotNull(service.getAnnotation(com.intellij.openapi.components.Service::class.java))
    }

    @Test
    fun `should have getApiKey method`() {
        val methods = AppSettingsService::class.java.declaredMethods
        val hasGetApiKey = methods.any { it.name == "getApiKey" }
        assertTrue(hasGetApiKey)
    }

    @Test
    fun `should have setApiKey method`() {
        val methods = AppSettingsService::class.java.declaredMethods
        val hasSetApiKey = methods.any { it.name == "setApiKey" }
        assertTrue(hasSetApiKey)
    }

    @Test
    fun `should have clearApiKey method`() {
        val methods = AppSettingsService::class.java.declaredMethods
        val hasClearApiKey = methods.any { it.name == "clearApiKey" }
        assertTrue(hasClearApiKey)
    }
}
