package com.aiassistant.memory

import com.aiassistant.agent.memory.MemoryAutoExtract
import com.aiassistant.agent.memory.MemoryEngine
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryAutoExtractTest {

    @Rule
    @JvmField
    val tempDir = TemporaryFolder()

    @Test
    fun `extract returns 0 for empty history`() {
        val engine = MemoryEngine(tempDir.newFolder().absolutePath)
        val extractor = MemoryAutoExtract(engine)
        val count = extractor.extract(emptyList(), "")
        assertEquals(0, count)
    }
}
