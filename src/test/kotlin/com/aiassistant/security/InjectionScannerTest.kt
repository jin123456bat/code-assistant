package com.aiassistant.security

import com.aiassistant.review.Severity
import org.junit.Test
import org.junit.Assert.*

class InjectionScannerTest {
    private val scanner = InjectionScanner()

    @Test fun `detects ProcessBuilder with variable`() {
        val findings = scanner.scan("ProcessBuilder(\"\${userInput}\")", "Exec.kt")
        assertTrue(findings.isNotEmpty())
    }

    @Test fun `detects Runtime exec`() {
        val findings = scanner.scan("Runtime.getRuntime().exec(cmd)", "Exec.kt")
        assertTrue(findings.isNotEmpty())
        assertEquals(Severity.WARNING, findings[0].severity)
    }

    @Test fun `no findings for safe code`() {
        val findings = scanner.scan("val x = 1 + 1", "Safe.kt")
        assertTrue(findings.isEmpty())
    }

    @Test fun `detects bash -c usage`() {
        val findings = scanner.scan("val cmd = \"/bin/bash -c script.sh\"", "Script.kt")
        assertTrue(findings.any { it.title.contains("Shell") })
    }

    @Test fun `returns empty for blank content`() {
        assertTrue(scanner.scan("", "Empty.kt").isEmpty())
    }
}
