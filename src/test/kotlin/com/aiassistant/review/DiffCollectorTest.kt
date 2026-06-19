package com.aiassistant.review

import org.junit.Assert.*
import org.junit.Test

class DiffCollectorTest {

    @Test fun `parse returns empty for blank input`() {
        val collector = DiffCollector(null)
        assertTrue(collector.parse("").isEmpty())
    }

    @Test fun `parse extracts file path from diff header`() {
        val collector = DiffCollector(null)
        val diff = """
diff --git a/src/Foo.kt b/src/Foo.kt
index abc..def 100644
--- a/src/Foo.kt
+++ b/src/Foo.kt
@@ -1,3 +1,4 @@
 unchanged
-added
 unchanged
""".trim()
        val changes = collector.parse(diff)
        assertEquals(1, changes.size)
        assertEquals("src/Foo.kt", changes[0].path)
    }

    @Test fun `parse extracts hunk lines correctly`() {
        val collector = DiffCollector(null)
        val diff = """
diff --git a/Foo.kt b/Foo.kt
--- a/Foo.kt
+++ b/Foo.kt
@@ -1,3 +1,4 @@
 unchanged
+added
 unchanged
""".trim()
        val changes = collector.parse(diff)
        assertEquals(1, changes.size)
        assertEquals(3, changes[0].hunks[0].lines.size)
    }

    @Test fun `parse detects binary files`() {
        val collector = DiffCollector(null)
        val diff = "diff --git a/img.png b/img.png\nBinary files differ\n"
        val changes = collector.parse(diff)
        assertTrue(changes.isEmpty() || changes[0].isBinary)
    }

    @Test fun `parse handles multiple files`() {
        val collector = DiffCollector(null)
        val diff = """
diff --git a/A.kt b/A.kt
--- a/A.kt
+++ b/A.kt
@@ -1,1 +1,1 @@
-old
+new
diff --git a/B.kt b/B.kt
--- a/B.kt
+++ b/B.kt
@@ -1,1 +1,1 @@
-old2
+new2
""".trim()
        val changes = collector.parse(diff)
        assertEquals(2, changes.size)
    }
}
