package com.aiassistant.ui.chat

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FileReferenceResolverTest {

    @Test
    fun `expands exact relative file reference`() {
        val root = createTempDirectory()
        val file = root.resolve("src/App.kt")
        file.parent.createDirectories()
        file.createFile()
        file.writeText("fun main() = Unit\n")

        val expanded = FileReferenceResolver.expand("review @src/App.kt", root.toString())

        assertContains(expanded, "review @src/App.kt")
        assertContains(expanded, "[File: src/App.kt (1 lines)]")
        assertContains(expanded, "fun main() = Unit")
        assertContains(expanded, "[/File]")
    }

    @Test
    fun `expands unique filename reference`() {
        val root = createTempDirectory()
        Files.writeString(root.resolve("README.md"), "hello\n")

        val expanded = FileReferenceResolver.expand("summarize @README.md", root.toString())

        assertContains(expanded, "[File: README.md (1 lines)]")
        assertContains(expanded, "hello")
    }

    @Test
    fun `keeps input unchanged when reference is missing`() {
        val root = createTempDirectory()

        assertEquals(
            "open @Missing.kt",
            FileReferenceResolver.expand("open @Missing.kt", root.toString())
        )
    }
}
