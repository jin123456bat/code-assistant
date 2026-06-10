package com.aiassistant

import com.aiassistant.ui.DiffKind
import com.aiassistant.ui.SimpleDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleDiffTest {

    @Test
    fun `identical text produces all CTX lines`() {
        val text = "line1\nline2\nline3"
        val result = SimpleDiff.diff(text, text)
        assertEquals(3, result.size)
        assertTrue(result.all { it.kind == DiffKind.CTX })
        assertEquals(listOf("line1", "line2", "line3"), result.map { it.text })
    }

    @Test
    fun `single line change produces DEL then ADD`() {
        val old = "line1\noldLine\nline3"
        val new = "line1\nnewLine\nline3"
        val result = SimpleDiff.diff(old, new)
        // Should have: CTX line1, DEL oldLine, ADD newLine, CTX line3
        val del = result.filter { it.kind == DiffKind.DEL }
        val add = result.filter { it.kind == DiffKind.ADD }
        val ctx = result.filter { it.kind == DiffKind.CTX }
        assertEquals(1, del.size)
        assertEquals(1, add.size)
        assertEquals(2, ctx.size)
        assertEquals("oldLine", del[0].text)
        assertEquals("newLine", add[0].text)
        // DEL must come before ADD in the output
        val delIndex = result.indexOf(del[0])
        val addIndex = result.indexOf(add[0])
        assertTrue(delIndex < addIndex)
    }

    @Test
    fun `pure additions from empty old produces all ADD`() {
        val old = ""
        val new = "line1\nline2\nline3"
        val result = SimpleDiff.diff(old, new)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.kind == DiffKind.ADD })
        assertEquals(listOf("line1", "line2", "line3"), result.map { it.text })
    }

    @Test
    fun `pure deletions to empty new produces all DEL`() {
        val old = "line1\nline2\nline3"
        val new = ""
        val result = SimpleDiff.diff(old, new)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.kind == DiffKind.DEL })
        assertEquals(listOf("line1", "line2", "line3"), result.map { it.text })
    }

    @Test
    fun `both empty produces empty list`() {
        val result = SimpleDiff.diff("", "")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `add lines at end`() {
        val old = "line1"
        val new = "line1\nline2\nline3"
        val result = SimpleDiff.diff(old, new)
        val ctx = result.filter { it.kind == DiffKind.CTX }
        val add = result.filter { it.kind == DiffKind.ADD }
        assertEquals(1, ctx.size)
        assertEquals("line1", ctx[0].text)
        assertEquals(2, add.size)
    }

    @Test
    fun `delete lines from start`() {
        val old = "line1\nline2\nline3"
        val new = "line3"
        val result = SimpleDiff.diff(old, new)
        val del = result.filter { it.kind == DiffKind.DEL }
        val ctx = result.filter { it.kind == DiffKind.CTX }
        assertEquals(2, del.size)
        assertEquals(1, ctx.size)
        assertEquals("line3", ctx[0].text)
    }
}
