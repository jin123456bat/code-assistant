package com.aiassistant.memory

import com.aiassistant.agent.memory.IndexEntry
import com.aiassistant.agent.memory.MemoryEntry
import com.aiassistant.agent.memory.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class MemoryStoreTest {

    private val tempDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "MemoryStoreTest-${System.currentTimeMillis()}")
        dir.mkdirs()
        // 注册 JVM shutdown hook 清理测试目录
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        dir
    }

    private fun createStore(): MemoryStore {
        return MemoryStore(projectBasePath = tempDir.absolutePath)
    }

    private fun sampleEntry(): MemoryEntry {
        return MemoryEntry(
            name = "test-memory",
            description = "测试记忆",
            content = "这是一条测试记忆内容。",
            type = "user",
            scope = "project"
        )
    }

    // 测试 1: write 创建 md 文件和 MEMORY.md 索引
    @Test
    fun `write creates md file and MEMORY md index`() {
        val store = createStore()
        val entry = sampleEntry()

        val result = store.write(entry)

        assertTrue(result.isSuccess, "写入应该成功")
        // 验证 md 文件存在
        val mdFile = File(tempDir, ".claude/memory/test-memory.md")
        assertTrue(mdFile.exists(), "test-memory.md 文件应该存在")
        // 验证内容包含 YAML frontmatter
        val content = mdFile.readText()
        assertTrue(content.contains("---"), "应该包含 YAML frontmatter")
        assertTrue(content.contains("name: test-memory"), "应该包含 name 字段")
        assertTrue(content.contains("description: 测试记忆"), "应该包含 description 字段")
        assertTrue(content.contains("type: user"), "应该包含 type 字段")
        assertTrue(content.contains("这是一条测试记忆内容。"), "应该包含正文内容")
        // 验证 MEMORY.md 索引
        val indexFile = File(tempDir, ".claude/memory/MEMORY.md")
        assertTrue(indexFile.exists(), "MEMORY.md 索引文件应该存在")
        val indexContent = indexFile.readText()
        assertTrue(indexContent.contains("[test-memory](test-memory.md) — 测试记忆"), "索引应该包含正确条目")
    }

    // 测试 2: read 返回解析后的 MemoryEntry
    @Test
    fun `read returns parsed MemoryEntry`() {
        val store = createStore()
        val written = sampleEntry()
        store.write(written)

        val read = store.read("test-memory")

        assertNotNull(read, "读取结果不应该为 null")
        assertEquals(written.name, read.name, "name 应该一致")
        assertEquals(written.description, read.description, "description 应该一致")
        assertEquals(written.content, read.content, "content 应该一致")
        assertEquals(written.type, read.type, "type 应该一致")
        assertEquals(written.scope, read.scope, "scope 应该一致")
    }

    // 测试 3: list 返回索引中的条目
    @Test
    fun `list returns entries from index`() {
        val store = createStore()
        store.write(sampleEntry())
        store.write(MemoryEntry(
            name = "another-memory",
            description = "另一条记忆",
            content = "另一条内容。",
            type = "feedback",
            scope = "project"
        ))

        val entries = store.list()

        // 不做严格数量断言（因为可能包含全局记忆），仅验证项目条目存在
        assertTrue(entries.any { it.name == "test-memory" }, "应该包含 test-memory")
        assertTrue(entries.any { it.name == "another-memory" }, "应该包含 another-memory")
        // 同名条目不应重复
        val testMemoryEntries = entries.filter { it.name == "test-memory" }
        assertEquals(1, testMemoryEntries.size, "同名条目不应该重复")
    }

    // 测试 4: delete 删除文件和索引条目
    @Test
    fun `delete removes file and index entry`() {
        val store = createStore()
        store.write(sampleEntry())

        val result = store.delete("test-memory")

        assertTrue(result.isSuccess, "删除应该成功")
        val mdFile = File(tempDir, ".claude/memory/test-memory.md")
        assertTrue(!mdFile.exists(), "md 文件应该被删除")
        // 索引中不应再包含该条目
        val indexFile = File(tempDir, ".claude/memory/MEMORY.md")
        val indexContent = indexFile.readText()
        assertTrue(!indexContent.contains("[test-memory]"), "索引不应该再包含 test-memory")
    }

    // 测试 5: write 覆盖同名的现有记忆
    @Test
    fun `write overwrites existing same-name memory`() {
        val store = createStore()
        val original = sampleEntry()
        store.write(original)

        val updated = MemoryEntry(
            name = "test-memory",
            description = "更新后的描述",
            content = "更新后的内容。",
            type = "feedback",
            scope = "project"
        )
        store.write(updated)

        // 验证文件被更新
        val read = store.read("test-memory")
        assertNotNull(read, "读取结果不应该为 null")
        assertEquals("更新后的描述", read.description, "description 应该被更新")
        assertEquals("更新后的内容。", read.content, "content 应该被更新")
        assertEquals("feedback", read.type, "type 应该被更新")
        // 索引中的描述也应该更新
        val indexContent = File(tempDir, ".claude/memory/MEMORY.md").readText()
        assertTrue(indexContent.contains("更新后的描述"), "索引中的描述应该被更新")
        assertTrue(!indexContent.contains("测试记忆"), "旧的描述不应该存在")
    }

    // 测试 6: list 优雅处理空 MEMORY.md
    @Test
    fun `list handles empty MEMORY md gracefully`() {
        val store = createStore()
        // 确保目录存在但 MEMORY.md 为空
        val memoryDir = File(tempDir, ".claude/memory")
        memoryDir.mkdirs()
        File(memoryDir, "MEMORY.md").writeText("")

        val entries = store.list()

        assertTrue(entries.isEmpty(), "空 MEMORY.md 应该返回空列表")
    }
}
