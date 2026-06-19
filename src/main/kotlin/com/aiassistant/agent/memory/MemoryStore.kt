package com.aiassistant.agent.memory

import com.aiassistant.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * MemoryEntry 数据类：表示一条记忆，对齐 Claude Code memory 模块。
 */
data class MemoryEntry(
    val name: String,           // kebab-case 文件名
    val description: String,    // 一句话描述
    val content: String,        // 记忆正文
    val type: String,           // user | feedback | project | reference
    val scope: String = "project"  // user | project
)

/**
 * IndexEntry：MEMORY.md 索引中解析出的条目。
 */
data class IndexEntry(
    val name: String,
    val description: String,
    val scope: String
)

/**
 * MemoryStore：记忆文件的底层存储层。
 *
 * 负责读写 .claude/memory/ 目录下的 .md 文件，
 * 并维护 MEMORY.md 索引文件。
 *
 * 文件写入使用 tmp + ATOMIC_MOVE 原子写入策略（对齐 SessionStore 的实现）。
 */
class MemoryStore(
    private val projectBasePath: String?,
    private val userHome: String = System.getProperty("user.home") ?: System.getenv("HOME") ?: "."
) {

    /** 全局记忆目录 */
    private val globalMemoryDir: File
        get() = File(userHome, ".claude/memory").also { it.mkdirs() }

    /** 项目记忆目录（需要 projectBasePath） */
    private fun projectMemoryDir(): File? {
        val base = projectBasePath ?: return null
        return File(base, ".claude/memory").also { it.mkdirs() }
    }

    // ---- YAML frontmatter 生成 ----

    private fun generateMdContent(entry: MemoryEntry): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${entry.name}")
            appendLine("description: ${entry.description}")
            appendLine("metadata:")
            appendLine("  type: ${entry.type}")
            appendLine("---")
            appendLine()
            append(entry.content)
        }
    }

    // ---- YAML frontmatter 解析 ----

    private fun parseMdContent(content: String, name: String, scope: String): MemoryEntry? {
        val parts = content.split("---", limit = 3)
        if (parts.size < 3) return null

        val frontmatter = parts[1]
        val body = parts[2].trimStart()

        val nameField = extractField(frontmatter, "name") ?: name
        val description = extractField(frontmatter, "description") ?: ""
        val type = extractYamlNested(frontmatter, "metadata", "type") ?: "project"

        return MemoryEntry(
            name = nameField,
            description = description,
            content = body,
            type = type,
            scope = scope
        )
    }

    private fun extractField(yaml: String, key: String): String? {
        // 用预编译正则匹配 "key: value" 格式的顶层字段
        for (line in yaml.lines()) {
            val match = yamlFieldRegex.find(line.trim())
            if (match != null && match.groupValues[1] == key) {
                return match.groupValues[2].trim()
            }
        }
        return null
    }

    private fun extractYamlNested(yaml: String, parent: String, key: String): String? {
        val lines = yaml.lines()
        var inParent = false
        for (line in lines) {
            if (line.trimStart().startsWith("$parent:")) {
                inParent = true
                continue
            }
            if (inParent) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("$key:")) {
                    return trimmed.substringAfter("$key:").trim()
                }
                // 如果下一行不是以缩进开头，说明 metadata 块结束
                if (trimmed.isNotEmpty() && !trimmed.startsWith("  ") && !trimmed.startsWith("\t")) {
                    inParent = false
                }
            }
        }
        return null
    }

    // ---- MEMORY.md 索引操作 ----

    /** YAML 顶层字段正则 */
    private val yamlFieldRegex = """^(\w+):\s*(.+)$""".toRegex(RegexOption.MULTILINE)

    /** 索引行正则: - [Title](file.md) — description */
    private val indexLineRegex = Regex("^- \\[(.+?)\\]\\((.+?)\\.md\\) — (.+)$")

    /**
     * 更新 MEMORY.md 索引：同名行替换 description，或追加新行。
     * 自动清理指向不存在文件的无效索引行。
     */
    private fun updateIndex(dir: File, name: String, description: String) {
        val indexFile = File(dir, "MEMORY.md")
        dir.mkdirs()

        val lines = if (indexFile.exists()) indexFile.readLines() else emptyList()
        val mutable = lines.toMutableList()

        // 查找同名的现有行并替换
        var found = false
        for (i in mutable.indices) {
            val match = indexLineRegex.find(mutable[i])
            if (match != null && match.groupValues[1] == name) {
                mutable[i] = "- [$name]($name.md) — $description"
                found = true
                break
            }
        }

        if (!found) {
            mutable.add("- [$name]($name.md) — $description")
        }

        // 清理无效索引行（指向不存在的 .md 文件）
        val validLines = mutable.filter { line ->
            val match = indexLineRegex.find(line)
            if (match != null) {
                val refName = match.groupValues[2]
                val refFile = File(dir, "$refName.md")
                refFile.exists()
            } else {
                true // 保留非索引行（如注释、空行）
            }
        }

        atomicWrite(indexFile, validLines.joinToString("\n") + "\n")
    }

    /**
     * 从 MEMORY.md 索引中移除指定名称的条目。
     */
    private fun removeFromIndex(dir: File, name: String) {
        val indexFile = File(dir, "MEMORY.md")
        if (!indexFile.exists()) return

        val lines = indexFile.readLines().toMutableList()
        lines.removeAll { line ->
            val match = indexLineRegex.find(line)
            match != null && match.groupValues[1] == name
        }

        atomicWrite(indexFile, lines.joinToString("\n"))
    }

    /**
     * 解析 MEMORY.md 索引文件，返回 IndexEntry 列表。
     */
    private fun parseIndex(file: File): List<IndexEntry> {
        if (!file.exists()) return emptyList()
        val entries = mutableListOf<IndexEntry>()
        for (line in file.readLines()) {
            val match = indexLineRegex.find(line)
            if (match != null) {
                val entryName = match.groupValues[1]
                val entryDesc = match.groupValues[3]
                entries.add(IndexEntry(name = entryName, description = entryDesc, scope = "project"))
            }
        }
        return entries
    }

    // ---- 原子写入 ----

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")

        try {
            tmp.writeText(content)
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                val bak = File(target.path + ".bak")
                try {
                    if (target.exists()) {
                        Files.move(target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    bak.delete()
                } catch (e: Exception) {
                    if (bak.exists() && !target.exists()) {
                        try { Files.move(bak.toPath(), target.toPath()) } catch (_: Exception) {}
                    }
                    if (!tmp.delete()) tmp.deleteOnExit()
                    throw e
                }
            }
        } catch (e: Exception) {
            if (!tmp.delete()) tmp.deleteOnExit()
            AppLogger.warn("MemoryStore: 文件写入失败 ${target.name}: ${e.message}")
        }
    }

    // ---- 公共 API ----

    /**
     * 写入记忆：
     * 1. 写 .md 文件（tmp + ATOMIC_MOVE）
     * 2. 更新 MEMORY.md 索引
     */
    fun write(entry: MemoryEntry): Result<Unit> {
        return try {
            val dir = projectMemoryDir() ?: globalMemoryDir
            val mdFile = File(dir, "${entry.name}.md")
            val content = generateMdContent(entry)
            atomicWrite(mdFile, content)
            updateIndex(dir, entry.name, entry.description)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.warn("MemoryStore: 写入记忆失败 ${entry.name}: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 读取记忆：先查项目目录，再查全局目录。
     */
    fun read(name: String): MemoryEntry? {
        // 先查项目目录
        val projectDir = projectMemoryDir()
        if (projectDir != null) {
            val projectFile = File(projectDir, "$name.md")
            if (projectFile.exists()) {
                return parseMdContent(projectFile.readText(), name, "project")
            }
        }
        // 再查全局目录
        val globalFile = File(globalMemoryDir, "$name.md")
        if (globalFile.exists()) {
            return parseMdContent(globalFile.readText(), name, "user")
        }
        return null
    }

    /**
     * 列出所有记忆条目：项目目录优先，同名去重。
     */
    fun list(): List<IndexEntry> {
        val projectDir = projectMemoryDir()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<IndexEntry>()

        // 先看项目目录
        if (projectDir != null) {
            val projectIndex = File(projectDir, "MEMORY.md")
            if (projectIndex.exists()) {
                for (entry in parseIndex(projectIndex)) {
                    seen.add(entry.name)
                    result.add(entry.copy(scope = "project"))
                }
            }
        }

        // 再看全局目录，跳过同名的
        val globalIndex = File(globalMemoryDir, "MEMORY.md")
        if (globalIndex.exists()) {
            for (entry in parseIndex(globalIndex)) {
                if (seen.add(entry.name)) {
                    result.add(entry.copy(scope = "user"))
                }
            }
        }

        return result
    }

    /**
     * 删除记忆：删文件 + 从索引移除。
     */
    fun delete(name: String): Result<Unit> {
        return try {
            val projectDir = projectMemoryDir()
            if (projectDir != null) {
                val mdFile = File(projectDir, "$name.md")
                if (mdFile.exists()) {
                    mdFile.delete()
                }
                removeFromIndex(projectDir, name)
            }
            // 也检查全局目录
            val globalFile = File(globalMemoryDir, "$name.md")
            if (globalFile.exists()) {
                globalFile.delete()
                removeFromIndex(globalMemoryDir, name)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.warn("MemoryStore: 删除记忆失败 $name: ${e.message}")
            Result.failure(e)
        }
    }
}
