package com.aiassistant.security

import com.aiassistant.review.Severity
import org.junit.Test
import org.junit.Assert.*

class SecurityDetectorTest {
    @Test fun `detects hardcoded API key`() {
        val detector = SecretDetector()
        val findings = detector.scan("val apiKey = \"sk-abc123def456\"", "Config.kt")
        assertTrue("should detect API key", findings.isNotEmpty())
        assertEquals(Severity.CRITICAL, findings[0].severity)
    }

    @Test fun `detects hardcoded password`() {
        val detector = SecretDetector()
        val findings = detector.scan("password: \"admin123\"", "AppConfig.kt")
        assertTrue("should detect password", findings.any { it.title.contains("密码") })
    }

    @Test fun `no false positive for variable name without literal`() {
        val detector = SecretDetector()
        val findings = detector.scan("val apiKey: String = getKey()", "Config.kt")
        assertTrue("no false positive", findings.isEmpty())
    }

    @Test fun `detects shell injection`() {
        val scanner = InjectionScanner()
        val findings = scanner.scan("Runtime.getRuntime().exec(userInput)", "Exec.kt")
        assertTrue("should detect injection", findings.isNotEmpty())
    }
}
