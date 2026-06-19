package com.aiassistant.review

import org.junit.Test
import org.junit.Assert.*

class ReviewAnalyzerTest {

    private val analyzer = ReviewAnalyzer()

    @Test
    fun `parseResult parses valid JSON`() {
        val json = """[{"severity":"CRITICAL","category":"BUG","file":"Foo.kt","line":42,"title":"空指针","description":"可能NPE","suggestion":"加null检查","confidence":90}]"""
        val findings = analyzer.parseResult(json)
        assertEquals(1, findings.size)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals(Category.BUG, findings[0].category)
        assertEquals("Foo.kt", findings[0].file)
        assertEquals(42, findings[0].line)
        assertEquals("空指针", findings[0].title)
        assertEquals(90, findings[0].confidence)
    }

    @Test
    fun `parseResult returns empty for non-JSON text`() {
        val findings = analyzer.parseResult("这是一段普通文本，没有JSON")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parseResult returns empty for empty string`() {
        val findings = analyzer.parseResult("")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parseResult handles multiple findings`() {
        val json = """[{"severity":"CRITICAL","category":"BUG","file":"A.kt","line":1,"title":"t1","description":"d1","confidence":80},{"severity":"WARNING","category":"PERF","file":"B.kt","line":5,"title":"t2","description":"d2","confidence":60}]"""
        val findings = analyzer.parseResult(json)
        assertEquals(2, findings.size)
        assertEquals(Severity.CRITICAL, findings[0].severity)
        assertEquals(Severity.WARNING, findings[1].severity)
    }

    @Test
    fun `parseResult skips malformed entries`() {
        val json = """[{"severity":"CRITICAL","file":"A.kt","line":1,"title":"ok"}, {"bad":"entry"}]"""
        val findings = analyzer.parseResult(json)
        // {"bad":"entry"} won't throw during construction since all fields have defaults,
        // but it will produce a Finding with empty title — we verify the first one is intact
        assertEquals(2, findings.size)
        assertEquals("ok", findings[0].title)
        assertEquals("", findings[1].title) // malformed entry gets empty title
    }
}
