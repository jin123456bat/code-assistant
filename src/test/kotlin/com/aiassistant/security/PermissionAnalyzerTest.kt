package com.aiassistant.security

import org.junit.Test
import org.junit.Assert.*

class PermissionAnalyzerTest {
    private val analyzer = PermissionAnalyzer()

    @Test fun `detects chmod 777`() {
        val findings = analyzer.scan("Runtime.getRuntime().exec(\"chmod 777 /tmp/file\")", "Script.kt")
        assertTrue(findings.any { it.title.contains("不安全文件权限") })
    }

    @Test fun `detects path traversal in File()`() {
        val findings = analyzer.scan("val f = File(\"../etc/passwd\")", "Read.kt")
        assertTrue(findings.any { it.title.contains("路径遍历") })
    }

    @Test fun `no false positive with canonicalPath guard`() {
        val findings = analyzer.scan("val f = File(\"../data\").canonicalPath", "Safe.kt")
        assertFalse(findings.any { it.title.contains("路径遍历") })
    }

    @Test fun `empty content returns no findings`() {
        assertTrue(analyzer.scan("", "Empty.kt").isEmpty())
    }
}
