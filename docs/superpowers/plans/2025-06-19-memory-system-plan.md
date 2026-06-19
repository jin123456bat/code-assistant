# Memory 记忆系统 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现跨会话 Memory 记忆系统，对齐 Claude Code 文件格式，支持 LLM 主动读写 + 会话结束自动提取 + 对话开始自动注入。

**Architecture:** 新增 `agent/memory/` 包（MemoryEngine + MemoryStore + MemoryRelevance + MemoryAutoExtract），4 个 AgentTool（memory_write/read/list/delete），集成到 ToolRegistryV3、AgentLoop、ChatViewModel、ChatToolWindow。存储复用 Claude Code 的 `~/.claude/memory/` 和 `<project>/.claude/memory/` 目录结构和文件格式。

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Gson (JSON/YAML frontmatter parsing), Java NIO (File/Path 原子写入)

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/kotlin/com/aiassistant/agent/memory/MemoryEngine.kt` | 创建 | 入口，整合 Store + Relevance + AutoExtract，对外暴露统一接口 |
| `src/main/kotlin/com/aiassistant/agent/memory/MemoryStore.kt` | 创建 | 文件读写、YAML frontmatter 解析/生成、MEMORY.md 索引维护 |
| `src/main/kotlin/com/aiassistant/agent/memory/MemoryRelevance.kt` | 创建 | 关键词提取 + 相关度匹配 + 排序截断 |
| `src/main/kotlin/com/aiassistant/agent/memory/MemoryAutoExtract.kt` | 创建 | 会话结束自动提取：调 LLM → 结构化 JSON → memory_write |
| `src/main/kotlin/com/aiassistant/tools/MemoryWriteTool.kt` | 创建 | memory_write AgentTool |
| `src/main/kotlin/com/aiassistant/tools/MemoryReadTool.kt` | 创建 | memory_read AgentTool |
| `src/main/kotlin/com/aiassistant/tools/MemoryListTool.kt` | 创建 | memory_list AgentTool |
| `src/main/kotlin/com/aiassistant/tools/MemoryDeleteTool.kt` | 创建 | memory_delete AgentTool |
| `src/main/kotlin/com/aiassistant/agent/ToolRegistryV3.kt` | 修改 | registerBuiltIn 注册 4 个 memory 工具 |
| `src/main/kotlin/com/aiassistant/agent/AgentLoop.kt` | 修改 | SAFE_TOOLS 加 memory_read/list；buildSystemPrompt 注入相关记忆；buildSdkToolDefs 处理 memory 工具 |
| `src/main/kotlin/com/aiassistant/agent/AgentContext.kt` | 修改 | 新增 `memoryEngine: MemoryEngine` 属性 |
| `src/main/kotlin/com/aiassistant/ChatViewModel.kt` | 修改 | clearConversation 触发 MemoryAutoExtract.extract() |
| `src/main/kotlin/com/aiassistant/ChatToolWindow.kt` | 修改 | 注册 `/memory` 斜杠命令 |
| `src/main/kotlin/com/aiassistant/AppSettingsService.kt` | 修改 | 新增 `memoryEnabled` 开关 |

---

### Task 1: MemoryStore — 文件读写 + YAML frontmatter + MEMORY.md 索引

**Files:**
- Create: `src/main/kotlin/com/aiassistant/agent/memory/MemoryStore.kt`
- Test: `src/test/kotlin/com/aiassistant/memory/MemoryStoreTest.kt`

- [ ] **Step 1: 编写 MemoryStore 数据模型**

MemoryStore 不需要额外的 public 数据类——直接复用记忆文件格式的字段（name/description/content/type/scope）。内部使用简单的 data class：

```kotlin
package com.aiassistant.agent.memory

data class MemoryEntry(
    val name: String,           // kebab-case 文件名（不含 .md）
    val description: String,    // 一句话描述
    val content: String,        // 记忆正文
    val type: String,           // user | feedback | project | reference
    val scope: String = "project"  // user | project
)
```

- [ ] **Step 2: 编写 MemoryStore 类结构（write/read/list/delete + 辅助方法）**

参考 `SessionStore.kt` 的原子写入模式：

```kotlin
package com.aiassistant.agent.memory

import com.google.gson.Gson
import java.io.File

class MemoryStore(private val projectBasePath: String?) {

    companion object {
        /** 全局记忆目录（对齐 Claude Code），无项目时回退 */
        private val USER_MEMORY_DIR: String by lazy {
            File(System.getProperty("user.home") ?: ".", ".claude/memory").also { it.mkdirs() }.absolutePath
        }
        private const val MEMORY_INDEX = "MEMORY.md"
    }

    /** 获取项目记忆目录 */
    private fun projectMemoryDir(): File? {
        val base = projectBasePath ?: return null
        return File(base, ".claude/memory").also { it.mkdirs() }
    }

    /** 根据 scope 解析目录 */
    private fun resolveDir(scope: String): File {
        return when (scope) {
            "user" -> File(USER_MEMORY_DIR)
            else -> projectMemoryDir() ?: File(USER_MEMORY_DIR)
        }
    }
}
```

- [ ] **Step 3: 实现 `write()` —— 写记忆文件 + 更新 MEMORY.md 索引**

```kotlin
    /**
     * 写入单条记忆。同名文件覆盖，原子写入（tmp + ATOMIC_MOVE）。
     */
    fun write(entry: MemoryEntry): Result<Unit> {
        return try {
            val dir = resolveDir(entry.scope)
            dir.mkdirs()

            // 1. 写入 .md 文件（tmp + 原子移动）
            val mdFile = File(dir, "${entry.name}.md")
            val tmpFile = File(dir, "${entry.name}.md.tmp")
            val yaml = buildFrontmatter(entry.name, entry.description, entry.type)
            tmpFile.writeText(yaml + "\n" + entry.content, Charsets.UTF_8)
            java.nio.file.Files.move(
                tmpFile.toPath(), mdFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )

            // 2. 更新 MEMORY.md 索引
            updateIndex(dir, entry.name, entry.description)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

- [ ] **Step 4: 实现 `read()` —— 读取单条记忆**

```kotlin
    /**
     * 按精确 name 读取记忆。先查项目目录，若无再查全局目录。
     */
    fun read(name: String): MemoryEntry? {
        return try {
            val entry = readFromDir(projectMemoryDir(), name)
                ?: readFromDir(File(USER_MEMORY_DIR), name)
            entry
        } catch (_: Exception) { null }
    }

    private fun readFromDir(dir: File?, name: String): MemoryEntry? {
        if (dir == null || !dir.isDirectory) return null
        val file = File(dir, "${name}.md")
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        return parseMemoryFile(text, name)
    }
```

- [ ] **Step 5: 实现 `list()` —— 解析 MEMORY.md 索引**

```kotlin
    /**
     * 返回所有记忆索引（项目 + 全局，项目优先，同名去重）。
     * 解析 MEMORY.md 的 `- [Title](file.md) — description` 行。
     */
    fun list(): List<IndexEntry> {
        val result = mutableListOf<IndexEntry>()
        val seen = mutableSetOf<String>()

        // 项目记忆先加入
        projectMemoryDir()?.let { parseIndex(it, "project", result, seen) }
        // 全局记忆补充（同名跳过）
        parseIndex(File(USER_MEMORY_DIR), "user", result, seen)

        return result
    }

    data class IndexEntry(val name: String, val description: String, val scope: String)

    private val indexLineRegex = Regex("""^- \[(.+?)\]\((.+?)\.md\) — (.+)$""")

    private fun parseIndex(dir: File, scope: String, result: MutableList<IndexEntry>, seen: MutableSet<String>) {
        val indexFile = File(dir, MEMORY_INDEX)
        if (!indexFile.isFile) return
        for (line in indexFile.readLines()) {
            val match = indexLineRegex.find(line.trim()) ?: continue
            val name = match.groupValues[2]
            val desc = match.groupValues[3]
            if (seen.add(name)) {
                result.add(IndexEntry(name, desc, scope))
            }
        }
    }
```

- [ ] **Step 6: 实现 `delete()` —— 删除记忆 + 清理索引**

```kotlin
    /**
     * 删除记忆文件并从索引中移除。
     * 先查项目目录，再查全局目录。
     */
    fun delete(name: String): Result<Unit> {
        return try {
            var deleted = false
            for (dir in listOfNotNull(projectMemoryDir(), File(USER_MEMORY_DIR))) {
                val file = File(dir, "${name}.md")
                if (file.isFile) {
                    file.delete()
                    removeFromIndex(dir, name)
                    deleted = true
                    break
                }
            }
            if (deleted) Result.success(Unit)
            else Result.failure(NoSuchFileException("记忆不存在: $name"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

- [ ] **Step 7: 实现 YAML frontmatter 生成**

```kotlin
    /** 生成 YAML frontmatter，对齐 Claude Code 格式 */
    private fun buildFrontmatter(name: String, description: String, type: String): String {
        return buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            appendLine("metadata:")
            appendLine("  type: $type")
            appendLine("---")
        }
    }
```

- [ ] **Step 8: 实现 YAML frontmatter 解析**

```kotlin
    /** 解析记忆文件，提取 frontmatter + body */
    private fun parseMemoryFile(text: String, name: String): MemoryEntry? {
        return try {
            val parts = text.split("---", limit = 3)
            if (parts.size < 3) return null

            val yamlBlock = parts[1].trim()
            val body = parts[2].trim()

            // 手动解析 YAML frontmatter（不引入第三方 YAML 库）
            val fields = mutableMapOf<String, String>()
            for (line in yamlBlock.lines()) {
                if (line.startsWith("  ")) {  // nested under metadata:
                    val kv = line.trim().split(":", limit = 2)
                    if (kv.size == 2) fields[kv[0].trim()] = kv[1].trim()
                } else {
                    val kv = line.split(":", limit = 2)
                    if (kv.size == 2) fields[kv[0].trim()] = kv[1].trim()
                }
            }

            MemoryEntry(
                name = fields["name"] ?: name,
                description = fields["description"] ?: "",
                content = body,
                type = fields["type"] ?: "project",
                scope = "project"  // 从存储路径推断
            )
        } catch (_: Exception) { null }
    }
```

- [ ] **Step 9: 实现索引更新辅助方法**

```kotlin
    /** 更新 MEMORY.md 索引：已有同名行则替换 description，否则追加 */
    private fun updateIndex(dir: File, name: String, description: String) {
        val indexFile = File(dir, MEMORY_INDEX)
        val lines = if (indexFile.isFile) indexFile.readLines().toMutableList() else mutableListOf()
        val pattern = """^- \[(.+?)\]\(${name}\.md\) — .+$""".toRegex()
        val existingIdx = lines.indexOfFirst { pattern.matches(it.trim()) }
        val newLine = "- [${name.replace("-", " ").replaceFirstChar { it.uppercaseChar() }}](${name}.md) — $description"

        if (existingIdx >= 0) {
            lines[existingIdx] = newLine
        } else {
            lines.add(newLine)
        }
        // 清理无效索引行（文件已被手动删除的）
        val validLines = lines.filter { line ->
            val match = indexLineRegex.find(line.trim())
            if (match != null) {
                File(dir, "${match.groupValues[2]}.md").isFile
            } else true
        }
        indexFile.writeText(validLines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    /** 从索引中移除一行（delete 时使用） */
    private fun removeFromIndex(dir: File, name: String) {
        val indexFile = File(dir, MEMORY_INDEX)
        if (!indexFile.isFile) return
        val lines = indexFile.readLines()
        val pattern = """^- \[.+?\]\(${name}\.md\) — .+$""".toRegex()
        val filtered = lines.filterNot { pattern.matches(it.trim()) }
        indexFile.writeText(filtered.joinToString("\n") + "\n", Charsets.UTF_8)
    }
```

- [ ] **Step 10: 编写 MemoryStoreTest**

```kotlin
package com.aiassistant.memory

import com.aiassistant.agent.memory.MemoryStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MemoryStoreTest {

    @TempDir lateinit var tempDir: File

    @Test fun `write creates md file and MEMORY md index`() {
        val store = MemoryStore(tempDir.absolutePath)  // 需调整为接受 projectPath 参数
        val entry = com.aiassistant.agent.memory.MemoryEntry(
            name = "test-preference",
            description = "用户偏好测试",
            content = "喜欢用中文\n**Why:** 个人偏好\n**How to apply:** 始终用中文回复",
            type = "user"
        )
        val result = store.write(entry)
        assertTrue(result.isSuccess)
        val memDir = File(tempDir, ".claude/memory")
        assertTrue(File(memDir, "test-preference.md").isFile)
        val index = File(memDir, "MEMORY.md")
        assertTrue(index.readText().contains("test-preference.md"))
    }

    @Test fun `read returns parsed MemoryEntry`() {
        val store = MemoryStore(tempDir.absolutePath)
        store.write(com.aiassistant.agent.memory.MemoryEntry("test", "desc", "body", "project"))
        val entry = store.read("test")
        assertNotNull(entry)
        assertEquals("desc", entry.description)
        assertEquals("body", entry.content)
    }

    @Test fun `list returns entries from index`() {
        val store = MemoryStore(tempDir.absolutePath)
        store.write(com.aiassistant.agent.memory.MemoryEntry("a", "desc a", "body a", "project"))
        store.write(com.aiassistant.agent.memory.MemoryEntry("b", "desc b", "body b", "project"))
        val entries = store.list()
        assertEquals(2, entries.size)
    }

    @Test fun `delete removes file and index entry`() {
        val store = MemoryStore(tempDir.absolutePath)
        store.write(com.aiassistant.agent.memory.MemoryEntry("to-delete", "desc", "body", "project"))
        assertTrue(store.delete("to-delete").isSuccess)
        assertNull(store.read("to-delete"))
        assertTrue(store.list().none { it.name == "to-delete" })
    }

    @Test fun `write overwrites existing same-name memory`() {
        val store = MemoryStore(tempDir.absolutePath)
        store.write(com.aiassistant.agent.memory.MemoryEntry("dup", "first", "body1", "project"))
        store.write(com.aiassistant.agent.memory.MemoryEntry("dup", "second", "body2", "project"))
        val entry = store.read("dup")
        assertEquals("second", entry?.description)
        assertEquals("body2", entry?.content)
    }

    @Test fun `read returns null for non-existent memory`() {
        val store = MemoryStore(tempDir.absolutePath)
        assertNull(store.read("nonexistent"))
    }

    @Test fun `list handles empty MEMORY md gracefully`() {
        val store = MemoryStore(tempDir.absolutePath)
        assertTrue(store.list().isEmpty())
    }
}
```

- [ ] **Step 11: 调整 MemoryStore 构造函数以接受可注入的路径**

实际使用中 projectBasePath 来自 `project.basePath`。为便于测试，需要能传入自定义路径：

```kotlin
// MemoryStore 构造函数改为：
class MemoryStore(
    private val projectBasePath: String?,
    private val userHome: String = System.getProperty("user.home") ?: "."
) {
    private val userMemoryDir: File get() = File(userHome, ".claude/memory").also { it.mkdirs() }
    // ...
}
```

测试中：`MemoryStore(tempDir.absolutePath, tempDir.absolutePath)`

- [ ] **Step 12: 运行 MemoryStoreTest 验证全部通过**

```bash
./gradlew test --tests "com.aiassistant.memory.MemoryStoreTest" -v
```
Expected: BUILD SUCCESSFUL, all tests PASS

- [ ] **Step 13: 提交 MemoryStore**

```bash
git add src/main/kotlin/com/aiassistant/agent/memory/MemoryStore.kt src/test/kotlin/com/aiassistant/memory/MemoryStoreTest.kt
git commit -m "feat(memory): 添加 MemoryStore 文件存储层（读写删/YAML解析/MEMORY.md索引）"
```

---

### Task 2: MemoryRelevance — 关键词提取 + 相关度匹配

**Files:**
- Create: `src/main/kotlin/com/aiassistant/agent/memory/MemoryRelevance.kt`
- Test: `src/test/kotlin/com/aiassistant/memory/MemoryRelevanceTest.kt`

- [ ] **Step 1: 编写 MemoryRelevance 类**

```kotlin
package com.aiassistant.agent.memory

class MemoryRelevance {

    companion object {
        /** 最大注入记忆数 */
        const val MAX_MEMORIES = 5
        /** 最大注入总字符数 */
        const val MAX_CHARS = 2000
    }

    /**
     * 给定对话上下文，返回相关记忆列表（排序 + 截断）。
     * @param context 当前对话内容（通常取最近几条消息）
     * @param memories 所有可用记忆的索引
     * @return 排序后的相关记忆名称列表
     */
    fun match(context: String, memories: List<MemoryStore.IndexEntry>): List<MemoryStore.IndexEntry> {
        if (memories.isEmpty() || context.isBlank()) return emptyList()

        val contextKeywords = extractKeywords(context)

        // 计算每条记忆的相关度分数
        val scored = memories.map { entry ->
            val text = "${entry.description} ${entry.name}"
            val score = scoreRelevance(contextKeywords, text)
            entry to score
        }.filter { it.second > 0 }

        // 按分数降序排序，取 top N
        return scored
            .sortedByDescending { it.second }
            .take(MAX_MEMORIES)
            .map { it.first }
    }

    /**
     * 从文本中提取关键词（中文按字符 bigram，英文按单词，去停用词）
     */
    fun extractKeywords(text: String): Set<String> {
        val cleaned = text.lowercase()
            .replace(Regex("""[，。！？、；：""''「」【】《》（）\n\r\t]"""), " ")
            .replace(Regex("""[,.!?;:'\"()\[\]{}<>]"""), " ")

        val words = cleaned.split(Regex("""\s+"""))
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            .toSet()

        // 补充中文 bigram（双字词匹配）
        val chineseBigrams = Regex("""[一-鿿]{2}""")
            .findAll(cleaned).map { it.value }.toSet()

        return words + chineseBigrams
    }

    /** 计算文本与关键词集的匹配分数 */
    private fun scoreRelevance(keywords: Set<String>, text: String): Int {
        val lower = text.lowercase()
        var score = 0
        for (kw in keywords) {
            if (lower.contains(kw)) {
                // 完全匹配权重更高，部分匹配也计分
                score += if (kw.length >= 3) 3 else 1
            }
        }
        return score
    }

    /** 最小停用词集（中文 + 英文） */
    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "in", "on", "at", "to", "for", "of", "and", "or", "but",
        "it", "this", "that", "with", "from", "as", "by",
        "的", "了", "是", "在", "我", "有", "和", "就", "不",
        "人", "都", "一", "一个", "上", "也", "很", "到", "说",
        "要", "去", "你", "会", "着", "没有", "看", "好", "自己",
        "这", "他", "她", "它", "们"
    )
}
```

- [ ] **Step 2: 编写 MemoryRelevanceTest**

```kotlin
package com.aiassistant.memory

import com.aiassistant.agent.memory.MemoryRelevance
import com.aiassistant.agent.memory.MemoryStore
import org.junit.jupiter.api.Test
import kotlin.test.*

class MemoryRelevanceTest {

    @Test fun `extractKeywords filters stop words and short tokens`() {
        val relevance = MemoryRelevance()
        val keywords = relevance.extractKeywords("我在使用 Kotlin 开发 IntelliJ 插件")
        assertTrue(keywords.contains("kotlin"))
        assertTrue(keywords.contains("intellij"))  // lowercase 后
        assertFalse(keywords.contains("在"))        // stop word
        assertFalse(keywords.contains("我"))        // stop word
    }

    @Test fun `extractKeywords includes Chinese bigrams`() {
        val relevance = MemoryRelevance()
        val keywords = relevance.extractKeywords("代码审查功能")
        assertTrue(keywords.contains("代码"))
        assertTrue(keywords.contains("审查"))
        assertTrue(keywords.contains("功能"))
        // 中文 bigram
        assertTrue(keywords.contains("代码审查") || keywords.contains("审查功能"))
    }

    @Test fun `match returns empty for blank context`() {
        val relevance = MemoryRelevance()
        val memories = listOf(MemoryStore.IndexEntry("test", "desc", "project"))
        assertTrue(relevance.match("", memories).isEmpty())
    }

    @Test fun `match ranks by relevance score`() {
        val relevance = MemoryRelevance()
        val memories = listOf(
            MemoryStore.IndexEntry("java-config", "Java 编译配置", "project"),
            MemoryStore.IndexEntry("kotlin-style", "Kotlin 代码风格约定", "project"),
            MemoryStore.IndexEntry("python-notes", "Python 脚本笔记", "project"),
        )
        val result = relevance.match("Kotlin 的扩展函数怎么写", memories)
        assertTrue(result.isNotEmpty())
        assertEquals("kotlin-style", result.first().name)  // 最佳匹配排第一
    }

    @Test fun `match truncates to MAX_MEMORIES`() {
        val relevance = MemoryRelevance()
        val memories = (1..10).map {
            MemoryStore.IndexEntry("memory-$it", "memory $it description", "project")
        }
        val result = relevance.match("memory description", memories)
        assertTrue(result.size <= MemoryRelevance.MAX_MEMORIES)
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
./gradlew test --tests "com.aiassistant.memory.MemoryRelevanceTest" -v
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交 MemoryRelevance**

```bash
git add src/main/kotlin/com/aiassistant/agent/memory/MemoryRelevance.kt src/test/kotlin/com/aiassistant/memory/MemoryRelevanceTest.kt
git commit -m "feat(memory): 添加 MemoryRelevance 关键词匹配与相关度排序"
```

---

### Task 3: Memory Engine — 入口整合

**Files:**
- Create: `src/main/kotlin/com/aiassistant/agent/memory/MemoryEngine.kt`

- [ ] **Step 1: 编写 MemoryEngine 类**

```kotlin
package com.aiassistant.agent.memory

class MemoryEngine(projectBasePath: String?) {

    val store = MemoryStore(projectBasePath)
    private val relevance = MemoryRelevance()

    /** 记忆索引缓存，避免频繁文件 I/O */
    @Volatile private var cachedIndex: List<MemoryStore.IndexEntry>? = null

    fun getRelevantMemories(context: String): List<MemoryEntry> {
        val index = getIndex()
        val matched = relevance.match(context, index)
        return matched.mapNotNull { store.read(it.name) }
    }

    fun getIndex(): List<MemoryStore.IndexEntry> {
        cachedIndex = store.list()
        return cachedIndex!!
    }

    fun invalidateCache() {
        cachedIndex = null
    }

    // 代理方法，便于外部调用
    fun write(entry: MemoryEntry) = store.write(entry)
    fun read(name: String) = store.read(name)
    fun delete(name: String) = store.delete(name)
    fun list() = store.list()
}
```

- [ ] **Step 2: 提交 MemoryEngine**

```bash
git add src/main/kotlin/com/aiassistant/agent/memory/MemoryEngine.kt
git commit -m "feat(memory): 添加 MemoryEngine 入口整合层"
```

---

### Task 4: 四个 Agent Tool

**Files:**
- Create: `src/main/kotlin/com/aiassistant/tools/MemoryWriteTool.kt`
- Create: `src/main/kotlin/com/aiassistant/tools/MemoryReadTool.kt`
- Create: `src/main/kotlin/com/aiassistant/tools/MemoryListTool.kt`
- Create: `src/main/kotlin/com/aiassistant/tools/MemoryDeleteTool.kt`

- [ ] **Step 1: MemoryWriteTool**

```kotlin
package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.aiassistant.agent.memory.MemoryEntry
import com.intellij.openapi.project.Project

class MemoryWriteTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_write"
    override val description = "写入一条记忆。跨会话持久化用户偏好、项目约定、决策记录等。存在同名记忆时覆盖更新。"
    override val parameters = listOf(
        ToolParameter("name", "string", "记忆文件名（kebab-case）", required = true),
        ToolParameter("description", "string", "一句话描述，用于检索", required = true),
        ToolParameter("content", "string", "记忆正文。必须包含 **Why:** 和 **How to apply:** 段落", required = true),
        ToolParameter("type", "string", "记忆类型：user/feedback/project/reference", required = true,
            enum = listOf("user", "feedback", "project", "reference")),
        ToolParameter("scope", "string", "作用域：user(全局跨项目)/project(当前项目)，默认 project",
            enum = listOf("user", "project"))
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val name = params["name"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("name 不能为空")
        val description = params["description"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("description 不能为空")
        val content = params["content"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("content 不能为空")
        val type = params["type"]?.takeIf {
            it in setOf("user", "feedback", "project", "reference")
        } ?: return ToolResult.err("type 必须是 user/feedback/project/reference 之一")
        val scope = params["scope"] ?: "project"

        // 校验 name 格式
        if (!name.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$"))) {
            return ToolResult.err("name 必须符合 kebab-case 格式（如 my-preference）")
        }

        val entry = MemoryEntry(name, description, content, type, scope)
        return engine.write(entry).fold(
            onSuccess = {
                ToolResult.ok("记忆已保存: $name ($description)")
            },
            onFailure = { e ->
                ToolResult.err("写入记忆失败: ${e.message}")
            }
        )
    }
}
```

- [ ] **Step 2: MemoryReadTool**

```kotlin
package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.intellij.openapi.project.Project

class MemoryReadTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_read"
    override val description = "读取记忆。可按 name 精确读取或按 query 关键词搜索相关记忆。"
    override val parameters = listOf(
        ToolParameter("query", "string", "搜索关键词（匹配 description 和正文）"),
        ToolParameter("name", "string", "按精确文件名读取（如 my-preference）"),
        ToolParameter("type", "string", "按类型过滤：user/feedback/project/reference",
            enum = listOf("user", "feedback", "project", "reference")),
        ToolParameter("scope", "string", "搜索范围：user/project/both，默认 both",
            enum = listOf("user", "project", "both"))
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val name = params["name"]?.takeIf { it.isNotBlank() }
        val query = params["query"]?.takeIf { it.isNotBlank() }

        // 按 name 精确读取
        if (name != null) {
            val entry = engine.read(name)
            return if (entry != null) {
                ToolResult.ok("""
                    |## ${entry.name}
                    |
                    |**类型:** ${entry.type}
                    |**描述:** ${entry.description}
                    |
                    |${entry.content}
                """.trimMargin())
            } else {
                ToolResult.err("未找到记忆: $name")
            }
        }

        // 按 query 搜索
        if (query != null) {
            val index = engine.list()
            val relevance = com.aiassistant.agent.memory.MemoryRelevance()
            val matched = relevance.match(query, index)
            if (matched.isEmpty()) return ToolResult.ok("未找到与「$query」相关的记忆")
            val results = matched.mapNotNull { engine.read(it.name) }
            return ToolResult.ok(buildString {
                appendLine("搜索「$query」找到 ${results.size} 条相关记忆：")
                results.forEachIndexed { i, entry ->
                    appendLine("\n### ${i + 1}. ${entry.name} (${entry.type})")
                    appendLine("${entry.description}\n")
                    appendLine(entry.content)
                }
            })
        }

        // 无参数 → 返回全部索引
        val index = engine.list()
        if (index.isEmpty()) return ToolResult.ok("暂无记忆")
        return ToolResult.ok(buildString {
            appendLine("全部记忆（共 ${index.size} 条）：")
            appendLine()
            index.forEach { entry ->
                appendLine("- **${entry.name}** (${entry.scope}/${entry.type}): ${entry.description}")
            }
        })
    }
}
```

- [ ] **Step 3: MemoryListTool**

```kotlin
package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.intellij.openapi.project.Project

class MemoryListTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_list"
    override val description = "列出所有已存储的记忆（索引列表）。"
    override val parameters = listOf(
        ToolParameter("scope", "string", "过滤范围：user/project/both，默认 both",
            enum = listOf("user", "project", "both"))
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val index = engine.list()
        if (index.isEmpty()) return ToolResult.ok("暂无记忆")

        val scope = params["scope"]
        val filtered = when (scope) {
            "user" -> index.filter { it.scope == "user" }
            "project" -> index.filter { it.scope == "project" }
            else -> index
        }

        return ToolResult.ok(buildString {
            appendLine("记忆列表（共 ${filtered.size} 条）：")
            appendLine()
            filtered.forEachIndexed { i, entry ->
                appendLine("${i + 1}. **${entry.name}** (${entry.scope}/${getTypeTag(entry.name)})")
                appendLine("   ${entry.description}")
            }
        })
    }

    private fun getTypeTag(name: String): String {
        val entry = engine.read(name)
        return entry?.type ?: "unknown"
    }
}
```

- [ ] **Step 4: MemoryDeleteTool**

```kotlin
package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.intellij.openapi.project.Project

class MemoryDeleteTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_delete"
    override val description = "删除一条记忆。"
    override val parameters = listOf(
        ToolParameter("name", "string", "要删除的记忆文件名", required = true)
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val name = params["name"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("name 不能为空")

        return engine.delete(name).fold(
            onSuccess = { ToolResult.ok("记忆已删除: $name") },
            onFailure = { e -> ToolResult.err("删除失败: ${e.message}") }
        )
    }
}
```

- [ ] **Step 5: 提交四个工具**

```bash
git add src/main/kotlin/com/aiassistant/tools/MemoryWriteTool.kt src/main/kotlin/com/aiassistant/tools/MemoryReadTool.kt src/main/kotlin/com/aiassistant/tools/MemoryListTool.kt src/main/kotlin/com/aiassistant/tools/MemoryDeleteTool.kt
git commit -m "feat(memory): 添加 memory_write/read/list/delete Agent Tools"
```

---

### Task 5: 集成——ToolRegistryV3 + AgentContext + AgentLoop

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/agent/ToolRegistryV3.kt`
- Modify: `src/main/kotlin/com/aiassistant/agent/AgentContext.kt`
- Modify: `src/main/kotlin/com/aiassistant/agent/AgentLoop.kt`

- [ ] **Step 1: 在 ToolRegistryV3 中注册 Memory 工具**

在 `registerBuiltIn()` 的 `all` 列表末尾添加：

```kotlin
// src/main/kotlin/com/aiassistant/agent/ToolRegistryV3.kt

// 修改 registerBuiltIn 方法，接受 MemoryEngine 参数：
fun registerBuiltIn(
    memoryEngine: com.aiassistant.agent.memory.MemoryEngine? = null,
    allowedTools: Set<String>? = null,
    deniedTools: Set<String> = emptySet()
) {
    val allTools = mutableListOf<AgentTool>(
        ReadFileTool(), WriteFileTool(), EditTool(), SearchCodeTool(), ListDirectoryTool(),
        ExecuteCommandTool(), GitDiffTool(), GitLogTool(), GitStatusTool(),
        AskUserTool(), WebSearchTool(), WebFetchTool(), NotebookEditTool(), TaskTool(),
        CodeIntelligenceTool(), McpGetPromptTool(), WorkflowTool()
    )
    // 有 MemoryEngine 时才注册 Memory 工具
    if (memoryEngine != null) {
        allTools.addAll(listOf(
            MemoryWriteTool(memoryEngine),
            MemoryReadTool(memoryEngine),
            MemoryListTool(memoryEngine),
            MemoryDeleteTool(memoryEngine)
        ))
    }
    allTools.forEach { ... }
}
```

- [ ] **Step 2: 在 AgentContext 中添加 memoryEngine**

```kotlin
// src/main/kotlin/com/aiassistant/agent/AgentContext.kt

// 在现有字段后添加：
val memoryEngine: com.aiassistant.agent.memory.MemoryEngine? = null  // 可选，null 时 Memory 功能禁用
```

用 lazy 初始化——project 已知后才创建：

```kotlin
// 在 AgentContext 中添加：
lateinit var memoryEngine: com.aiassistant.agent.memory.MemoryEngine
    private set

fun initMemory() {
    if (!::memoryEngine.isInitialized) {
        memoryEngine = com.aiassistant.agent.memory.MemoryEngine(project.basePath)
    }
}
```

- [ ] **Step 3: 修改 AgentLoop 的 SAFE_TOOLS + buildSdkToolDefs**

```kotlin
// SAFE_TOOLS 添加 memory_read 和 memory_list（只读工具自动执行）
val SAFE_TOOLS = setOf(
    "search_code", "read_file", "list_directory",
    "git_diff", "git_log", "git_status", "web_search",
    "web_fetch", "task", "ask_user", "code_intelligence",
    "memory_read", "memory_list"    // ← 新增
)
```

`memory_write` 和 `memory_delete` 不在 SAFE_TOOLS 中，默认需审批（合理——写/删操作应该确认）。

- [ ] **Step 4: 修改 AgentLoop.initialize() 初始化 MemoryEngine**

```kotlin
// initialize() 方法中，在 toolRegistry.registerBuiltIn() 之前：
ctx.initMemory()
ctx.toolRegistry.registerBuiltIn(
    memoryEngine = ctx.memoryEngine,
    allowedTools = allowedTools,
    deniedTools = deniedTools
)
```

- [ ] **Step 5: 在 buildSystemPrompt 中注入相关记忆**

在 `buildSystemPrompt()` 末尾追加：

```kotlin
// 在 system prompt 的 buildString 最后，return 之前：
val memEngine = ctx.memoryEngine
if (memEngine != null) {
    val context = ctx.conversationHistory.takeLast(3)
        .joinToString("\n") { "${it.role}: ${it.content.take(200)}" }
    val relevant = memEngine.getRelevantMemories(context)
    if (relevant.isNotEmpty()) {
        appendLine()
        appendLine("## 记忆")
        appendLine("以下是从过往对话中提取的相关信息，请参考应用：")
        relevant.forEach { mem ->
            appendLine()
            appendLine("### ${mem.name}")
            appendLine(mem.content.take(400))
        }
    }
}
```

- [ ] **Step 6: 提交集成变更**

```bash
git add src/main/kotlin/com/aiassistant/agent/ToolRegistryV3.kt src/main/kotlin/com/aiassistant/agent/AgentContext.kt src/main/kotlin/com/aiassistant/agent/AgentLoop.kt
git commit -m "feat(memory): 集成 MemoryEngine 到 ToolRegistry/AgentContext/AgentLoop"
```

---

### Task 6: 自动提取 + UI 集成

**Files:**
- Create: `src/main/kotlin/com/aiassistant/agent/memory/MemoryAutoExtract.kt`
- Modify: `src/main/kotlin/com/aiassistant/ChatViewModel.kt`
- Modify: `src/main/kotlin/com/aiassistant/ChatToolWindow.kt`
- Modify: `src/main/kotlin/com/aiassistant/AppSettingsService.kt`

- [ ] **Step 1: MemoryAutoExtract**

```kotlin
package com.aiassistant.agent.memory

class MemoryAutoExtract(private val engine: MemoryEngine) {

    /**
     * 从对话历史中自动提取记忆。
     * @param conversationHistory 完整对话历史
     * @param apiKey DeepSeek API Key
     * @return 成功提取的记忆数量
     */
    fun extract(conversationHistory: List<com.aiassistant.AnthropicAdapter.AnthropicMessage>,
                apiKey: String): Int {
        if (conversationHistory.isEmpty()) return 0

        // 构造提取 prompt
        val convoText = conversationHistory.takeLast(20)
            .joinToString("\n\n") { "[${it.role}]: ${it.content.take(500)}" }
        val prompt = buildExtractPrompt(convoText)

        return try {
            // 调 LLM 提取（复用 AnthropicSdkClient，单次同步调用）
            val client = com.aiassistant.AnthropicSdkClient(apiKey)
            val result = client.createSimpleCompletion(prompt, listOf(), 2000)
            val extracted = parseResult(result)
            var count = 0
            for (entry in extracted) {
                if (entry.name.isNotBlank() && entry.content.isNotBlank()) {
                    engine.write(entry).onSuccess { count++ }
                }
            }
            if (count > 0) {
                com.aiassistant.AppLogger.info("MemoryAutoExtract: 自动提取了 $count 条记忆")
            }
            count
        } catch (e: Exception) {
            com.aiassistant.AppLogger.warn("MemoryAutoExtract: 自动提取失败: ${e.message}")
            0
        }
    }

    private fun buildExtractPrompt(convoText: String): String {
        return """
            |分析以下对话，提取值得跨会话记忆的关键信息。
            |输出 JSON 数组（不要其他文字），每项包含：name(kebab-case), description, content(含 Why 和 How to apply), type(user/feedback/project/reference), scope(user/project)
            |
            |提取规则：
            |1. 用户明确表达的偏好、习惯 → type=user
            |2. 用户给你的反馈、纠正你的行为 → type=feedback
            |3. 项目架构约定、已做决策 → type=project
            |4. 外部资料/文档引用 → type=reference
            |5. 不要提取一次性问题答案、简单询问、临时代码片段
            |6. content 中必须包含 **Why:** 和 **How to apply:** 两行
            |
            |对话原文：
            |$convoText
            |
            |JSON:
        """.trimMargin()
    }

    private fun parseResult(text: String): List<MemoryEntry> {
        return try {
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']')
            if (jsonStart < 0 || jsonEnd < 0) return emptyList()
            val json = text.substring(jsonStart, jsonEnd + 1)
            val gson = com.google.gson.Gson()
            val arr = gson.fromJson(json, Array<Map<String, String>>::class.java)
            arr.mapNotNull { item ->
                try {
                    MemoryEntry(
                        name = item["name"] ?: "",
                        description = item["description"] ?: "",
                        content = item["content"] ?: "",
                        type = item["type"] ?: "project",
                        scope = item["scope"] ?: "project"
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}
```

- [ ] **Step 2: 修改 ChatViewModel.clearConversation() 触发自动提取**

```kotlin
// 在 clearConversation() 末尾，runOnEdt 之前：
// 自动提取记忆（异步，不阻塞）
val memEngine = agent?.ctx?.memoryEngine
val apiKey = AppSettingsService.getInstance().getApiKey()
if (memEngine != null && apiKey.isNotBlank() && AppSettingsService.getInstance().isMemoryEnabled()) {
    val history = agent?.ctx?.conversationHistory?.toList() ?: emptyList()
    if (history.isNotEmpty()) {
        Thread({
            MemoryAutoExtract(memEngine).extract(history, apiKey)
        }, "memory-auto-extract").apply { isDaemon = true }.start()
    }
}
```

- [ ] **Step 3: 在 AppSettingsService 中添加开关**

```kotlin
// src/main/kotlin/com/aiassistant/AppSettingsService.kt

private const val MEMORY_ENABLED_KEY = "$SERVICE_NAME.MEMORY_ENABLED"

fun isMemoryEnabled(): Boolean {
    val raw = PropertiesComponent.getInstance().getValue(MEMORY_ENABLED_KEY)
    return raw == null || raw.toBooleanStrictOrNull() != false  // 默认开
}

fun setMemoryEnabled(enabled: Boolean) {
    PropertiesComponent.getInstance().setValue(MEMORY_ENABLED_KEY, enabled.toString())
}
```

- [ ] **Step 4: 在 ChatToolWindow 中注册 /memory 命令**

在 `setupInputCompletions()` 的 Cmd 列表中追加：

```kotlin
// 在现有 Cmd 列表末尾添加：
Cmd("/memory", "查看或管理记忆") {
    val memEngine = viewModel.agent?.ctx?.memoryEngine ?: return@Cmd
    val index = memEngine.list()
    if (index.isEmpty()) {
        addSystemMessage("📝 暂无记忆")
    } else {
        val sb = StringBuilder()
        sb.appendLine("📝 **记忆列表** (${index.size} 条)\n")
        index.forEachIndexed { i, entry ->
            sb.appendLine("${i + 1}. **${entry.name}** (${entry.scope}) — ${entry.description}")
        }
        sb.appendLine("\n`/memory <名称>` 查看详情  |  `/memory delete <名称>` 删除记忆")
        addSystemMessage(sb.toString())
    }
    onMessagesChanged?.invoke()
}
```

- [ ] **Step 5: 提交自动提取 + UI 集成**

```bash
git add src/main/kotlin/com/aiassistant/agent/memory/MemoryAutoExtract.kt src/main/kotlin/com/aiassistant/ChatViewModel.kt src/main/kotlin/com/aiassistant/ChatToolWindow.kt src/main/kotlin/com/aiassistant/AppSettingsService.kt
git commit -m "feat(memory): 添加自动提取 + /memory 命令 + 设置开关"
```

---

### Task 7: 最终验证 & 集成测试

**Files:**
- Create: `src/test/kotlin/com/aiassistant/memory/MemoryIntegrationTest.kt`

- [ ] **Step 1: 编写集成测试**

```kotlin
package com.aiassistant.memory

import com.aiassistant.agent.memory.MemoryEngine
import com.aiassistant.agent.memory.MemoryEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MemoryIntegrationTest {

    @TempDir lateinit var tempDir: File

    @Test fun `full write-read-update-delete cycle`() {
        val engine = MemoryEngine(tempDir.absolutePath)

        // Write
        val entry = MemoryEntry("test-cycle", "测试", "正文\n**Why:** 因为\n**How to apply:** 这样", "project")
        assertTrue(engine.write(entry).isSuccess)

        // Read
        val read = engine.read("test-cycle")
        assertNotNull(read)
        assertEquals("测试", read.description)

        // Update (overwrite)
        val updated = MemoryEntry("test-cycle", "更新描述", "新正文\n**Why:** 因为重要\n**How to apply:** 更新方式", "project")
        assertTrue(engine.write(updated).isSuccess)
        assertEquals("更新描述", engine.read("test-cycle")?.description)

        // List
        val list = engine.list()
        assertTrue(list.any { it.name == "test-cycle" })

        // Delete
        assertTrue(engine.delete("test-cycle").isSuccess)
        assertNull(engine.read("test-cycle"))
    }

    @Test fun `written files are Claude Code compatible`() {
        val engine = MemoryEngine(tempDir.absolutePath)
        engine.write(MemoryEntry("compat-test", "兼容性测试", "**Why:** 测试\n**How to apply:** 验证", "user", "user"))

        // 检查文件格式
        val memFile = File(tempDir, ".claude/memory/compat-test.md")
        val content = memFile.readText()
        assertTrue(content.contains("---"))
        assertTrue(content.contains("name: compat-test"))
        assertTrue(content.contains("type: user"))
        assertTrue(content.contains("**Why:**"))
        assertTrue(content.contains("**How to apply:**"))
    }
}
```

- [ ] **Step 2: 运行全部测试**

```bash
./gradlew test -v
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 最终提交**

```bash
git add src/test/kotlin/com/aiassistant/memory/MemoryIntegrationTest.kt
git commit -m "test(memory): 添加集成测试（完整读写删 + Claude Code 格式兼容）"
```

---

## 实施统计

| Task | 内容 | 新文件 | 改文件 |
|------|------|--------|--------|
| T1 | MemoryStore + 测试 | 2 | 0 |
| T2 | MemoryRelevance + 测试 | 2 | 0 |
| T3 | MemoryEngine 入口 | 1 | 0 |
| T4 | 4 个 AgentTool | 4 | 0 |
| T5 | ToolRegistry + AgentContext + AgentLoop | 0 | 3 |
| T6 | MemoryAutoExtract + UI + 设置 | 1 | 3 |
| T7 | 验证 + 集成测试 | 1 | 0 |
| **合计** | | **11 新文件** | **6 改文件** |

## 并行执行建议

- **Lane A**: T1 (MemoryStore) → T3 (MemoryEngine) → T5 (集成)
- **Lane B**: T2 (MemoryRelevance) → T4 (AgentTools) → T5 (集成)
- **Lane A + B 合流后**: T6 (自动提取 + UI) → T7 (验证)

T1 和 T2 可并行（无共享模块）。T5 合并两路后推进 T6。
