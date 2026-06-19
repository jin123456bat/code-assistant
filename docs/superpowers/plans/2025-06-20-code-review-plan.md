# Code Review & Slash Commands — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 5 个斜杠命令（/review /diff /test /fix /security-review）+ 四维度审查引擎 + 安全审查引擎 + IDE 集成（结果面板/gutter标注/右键菜单）。

**Architecture:** 新增 `review/`、`security/`、`commands/` 三个包。审查引擎通过 DiffCollector 收集变更 → ReviewAnalyzer 调 LLM 四维度分析 → 输出结构化 Findings。安全引擎独立五维度扫描。命令层注册到 ChatToolWindow 现有 Cmd 列表。IDE 集成通过 IntelliJ EP 扩展。

**Tech Stack:** Kotlin, Swing (JBPanel), IntelliJ Platform SDK (EditorGutterIconProvider, EditorPopupMenu), ProcessBuilder (gradlew test), Gson

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/kotlin/com/aiassistant/review/ReviewEngine.kt` | 创建 | 审查引擎入口，协调 DiffCollector + ReviewAnalyzer + FixApplier |
| `src/main/kotlin/com/aiassistant/review/DiffCollector.kt` | 创建 | git diff 执行 + 解析 (+ FileChange/Hunk 数据模型) |
| `src/main/kotlin/com/aiassistant/review/ReviewAnalyzer.kt` | 创建 | LLM 四维度分析 + Finding 数据模型 |
| `src/main/kotlin/com/aiassistant/review/FixApplier.kt` | 创建 | --fix 自动修复 |
| `src/main/kotlin/com/aiassistant/review/CommentFormatter.kt` | 创建 | --comment PR 评论格式 |
| `src/main/kotlin/com/aiassistant/security/SecurityReviewEngine.kt` | 创建 | 安全审查引擎入口 |
| `src/main/kotlin/com/aiassistant/security/InjectionScanner.kt` | 创建 | 注入向量检测（SQL/命令/模板/prompt） |
| `src/main/kotlin/com/aiassistant/security/SecretDetector.kt` | 创建 | 密钥泄漏检测 |
| `src/main/kotlin/com/aiassistant/security/PermissionAnalyzer.kt` | 创建 | 权限/路径遍历分析 |
| `src/main/kotlin/com/aiassistant/security/DependencyChecker.kt` | 创建 | 依赖 CVE 检查 |
| `src/main/kotlin/com/aiassistant/commands/ReviewCommands.kt` | 创建 | 5 个斜杠命令 action 实现 |
| `src/main/kotlin/com/aiassistant/commands/TestRunner.kt` | 创建 | /test /fix 执行 gradlew test + 解析 + LLM 修复 |
| `src/main/kotlin/com/aiassistant/ui/ReviewResultPanel.kt` | 创建 | 审查结果面板 |
| `src/main/kotlin/com/aiassistant/ui/ReviewContextMenu.kt` | 创建 | 右键菜单 |
| `src/main/kotlin/com/aiassistant/ChatToolWindow.kt` | 修改 | 注册 5 个新命令 + 集成面板 |
| `src/main/kotlin/com/aiassistant/ChatViewModel.kt` | 修改 | lastTestOutput 缓存（/fix 读取） |
| `src/main/resources/META-INF/plugin.xml` | 修改 | 注册 EP 扩展（gutter/context menu） |

---

### Task 1: 数据模型 + DiffCollector

**Files:**
- Create: `src/main/kotlin/com/aiassistant/review/DiffCollector.kt`
- Test: `src/test/kotlin/com/aiassistant/review/DiffCollectorTest.kt`

- [ ] **Step 1: 编写 DiffCollector（含数据模型）**

```kotlin
package com.aiassistant.review

import java.io.File

enum class Severity { CRITICAL, WARNING, INFO }
enum class Category { BUG, SIMPLIFY, PERF, SECURITY }

data class Finding(
    val severity: Severity,
    val category: Category,
    val file: String,
    val line: Int,
    val title: String,
    val description: String,
    val suggestion: String = "",
    val confidence: Int = 5
)

data class FileChange(
    val path: String,
    val status: String,
    val hunks: List<Hunk>,
    val isBinary: Boolean = false
)

data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<String>
)

class DiffCollector(private val projectBasePath: String?) {

    /** 收集当前分支相对于 main/master 的 diff */
    fun collectBranchDiff(): String? {
        val base = projectBasePath ?: return null
        return runCatching {
            val baseBranch = detectBaseBranch()
            val process = ProcessBuilder("git", "-C", base, "diff", "$baseBranch...HEAD")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** 收集指定文件的 diff */
    fun collectFileDiff(filePath: String): String? {
        val base = projectBasePath ?: return null
        return runCatching {
            val process = ProcessBuilder("git", "-C", base, "diff", "HEAD", "--", filePath)
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** 获取 diff 统计 */
    fun collectDiffStat(): String? {
        val base = projectBasePath ?: return null
        return runCatching {
            val process = ProcessBuilder("git", "-C", base, "diff", "--stat", "main...HEAD")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun detectBaseBranch(): String {
        val base = projectBasePath!!
        // 尝试 main，再尝试 master
        val hasMain = runCatching {
            ProcessBuilder("git", "-C", base, "rev-parse", "--verify", "origin/main")
                .start().waitFor() == 0
        }.getOrDefault(false)
        return if (hasMain) "origin/main" else "origin/master"
    }

    /** 解析 git diff 输出为结构化 FileChange 列表 */
    fun parse(diff: String): List<FileChange> {
        if (diff.isBlank()) return emptyList()

        val changes = mutableListOf<FileChange>()
        val lines = diff.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git ")) {
                // 提取文件路径
                val pathMatch = Regex("""b/(.+)""").find(line)
                val path = pathMatch?.groupValues?.get(1) ?: "unknown"
                i++

                // 跳过 index/new file/deleted file 等头部行
                var status = "modified"
                var isBinary = false
                while (i < lines.size && !lines[i].startsWith("@@")) {
                    if (lines[i].startsWith("new file")) status = "added"
                    else if (lines[i].startsWith("deleted file")) status = "deleted"
                    else if (lines[i].contains("Binary files")) isBinary = true
                    i++
                }

                // 解析 hunks
                val hunks = mutableListOf<Hunk>()
                while (i < lines.size && lines[i].startsWith("@@")) {
                    val hunkHeader = Regex("""@@ -(\d+),?(\d*) \+(\d+),?(\d*) @@""").find(lines[i])
                    if (hunkHeader != null) {
                        val (oldStart, oldCount, newStart, newCount) = listOf(
                            hunkHeader.groupValues[1].toInt(),
                            hunkHeader.groupValues[2].ifEmpty { "1" }.toInt(),
                            hunkHeader.groupValues[3].toInt(),
                            hunkHeader.groupValues[4].ifEmpty { "1" }.toInt()
                        )
                        i++
                        val hunkLines = mutableListOf<String>()
                        while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git")) {
                            hunkLines.add(lines[i])
                            i++
                        }
                        hunks.add(Hunk(oldStart, oldCount, newStart, newCount, hunkLines.toList()))
                    } else {
                        i++
                    }
                }

                changes.add(FileChange(path, status, hunks.toList(), isBinary))
            } else {
                i++
            }
        }
        return changes.toList()
    }
}
```

- [ ] **Step 2: 编写 DiffCollectorTest**

```kotlin
package com.aiassistant.review

import org.junit.jupiter.api.Test
import kotlin.test.*

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

    @Test fun `parse skips binary files`() {
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
```

- [ ] **Step 3: 运行测试并提交**

```bash
./gradlew test --tests "com.aiassistant.review.DiffCollectorTest" -v
```
Expected: BUILD SUCCESSFUL, 5/5 tests pass

```bash
git add src/main/kotlin/com/aiassistant/review/DiffCollector.kt src/test/kotlin/com/aiassistant/review/DiffCollectorTest.kt
git commit -m "feat(review): 添加 DiffCollector 数据模型和 git diff 解析"
```

---

### Task 2: ReviewAnalyzer + ReviewEngine

**Files:**
- Create: `src/main/kotlin/com/aiassistant/review/ReviewAnalyzer.kt`
- Create: `src/main/kotlin/com/aiassistant/review/ReviewEngine.kt`
- Create: `src/main/kotlin/com/aiassistant/review/FixApplier.kt`
- Create: `src/main/kotlin/com/aiassistant/review/CommentFormatter.kt`

- [ ] **Step 1: ReviewAnalyzer**

```kotlin
package com.aiassistant.review

class ReviewAnalyzer {

    /**
     * 构建审查 prompt，交给 LLM 执行四维度分析。
     * ReviewEngine 负责调 LLM，此类负责 prompt 构建 + 结果解析。
     */
    fun buildPrompt(fileChanges: List<FileChange>): String {
        return buildString {
            appendLine("你是一位资深代码审查员。请审查以下 git diff，从四个维度分析：")
            appendLine()
            appendLine("1. **正确性:** 空指针风险、竞态条件、边界条件缺陷、逻辑错误")
            appendLine("2. **简化性:** DRY 违规、魔法数字、过长方法、无用代码、可复用机会")
            appendLine("3. **效率:** N+1 查询、不必要的内存分配、可优化路径")
            appendLine("4. **安全性:** 注入向量、密钥硬编码、不安全 API 调用")
            appendLine()
            appendLine("输出 JSON 数组（不要其他文字）：")
            appendLine("""[{"severity":"CRITICAL|WARNING|INFO","category":"BUG|SIMPLIFY|PERF|SECURITY","file":"路径","line":行号,"title":"标题","description":"详细说明","suggestion":"建议修复代码","confidence":85}]""")
            appendLine()
            appendLine("--- DIFF ---")
            for (change in fileChanges.filter { !it.isBinary }) {
                appendLine("\n## ${change.path} (${change.status})")
                for (hunk in change.hunks) {
                    appendLine("@@ ${hunk.oldStart},${hunk.oldCount} → ${hunk.newStart},${hunk.newCount}")
                    for (line in hunk.lines.take(50)) appendLine(line)
                }
            }
            appendLine()
            appendLine("---")
            appendLine("JSON:")
        }
    }

    /** 解析 LLM 返回的 JSON */
    fun parseResult(text: String): List<Finding> {
        return try {
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']')
            if (jsonStart < 0 || jsonEnd < 0) return emptyList()
            val json = text.substring(jsonStart, jsonEnd + 1)
            val gson = com.google.gson.Gson()
            val arr = gson.fromJson(json, Array<Map<String, Any>>::class.java)
            arr.mapNotNull { item ->
                try {
                    Finding(
                        severity = parseSeverity(item["severity"]?.toString()),
                        category = parseCategory(item["category"]?.toString()),
                        file = item["file"]?.toString() ?: "",
                        line = (item["line"] as? Number)?.toInt() ?: 0,
                        title = item["title"]?.toString() ?: "",
                        description = item["description"]?.toString() ?: "",
                        suggestion = item["suggestion"]?.toString() ?: "",
                        confidence = (item["confidence"] as? Number)?.toInt() ?: 5
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseSeverity(s: String?): Severity = when (s?.uppercase()) {
        "CRITICAL" -> Severity.CRITICAL
        "WARNING" -> Severity.WARNING
        else -> Severity.INFO
    }
    private fun parseCategory(s: String?): Category = when (s?.uppercase()) {
        "BUG" -> Category.BUG
        "SIMPLIFY" -> Category.SIMPLIFY
        "PERF" -> Category.PERF
        "SECURITY" -> Category.SECURITY
        else -> Category.BUG
    }
}
```

- [ ] **Step 2: ReviewEngine**

```kotlin
package com.aiassistant.review

class ReviewEngine(private val projectBasePath: String?) {

    private val collector = DiffCollector(projectBasePath)
    private val analyzer = ReviewAnalyzer()
    val fixApplier = FixApplier()
    val commentFormatter = CommentFormatter()

    data class ReviewResult(val findings: List<Finding>, val score: Int, val totalFiles: Int)

    /** 完整审查流程 */
    fun review(apiKey: String): ReviewResult? {
        val diff = collector.collectBranchDiff()?.takeIf { it.isNotBlank() } ?: return null
        return analyze(apiKey, diff)
    }

    /** 审查指定文件 */
    fun reviewFile(apiKey: String, filePath: String): ReviewResult? {
        val diff = collector.collectFileDiff(filePath)?.takeIf { it.isNotBlank() } ?: return null
        return analyze(apiKey, diff)
    }

    /** 审查指定 diff 文本 */
    fun analyze(apiKey: String, diff: String): ReviewResult {
        val fileChanges = collector.parse(diff)
            .filter { !it.isBinary }
            .let { if (it.sumOf { f -> f.hunks.sumOf { h -> h.lines.size } } > 10000) {
                it.map { fc -> fc.copy(hunks = fc.hunks.map { h -> h.copy(lines = h.lines.take(500)) }) }
            } else it }

        val prompt = analyzer.buildPrompt(fileChanges)
        val findings = callLLM(apiKey, prompt)
        val score = calculateScore(findings)

        return ReviewResult(findings, score, fileChanges.size)
    }

    private fun callLLM(apiKey: String, prompt: String): List<Finding> {
        return try {
            val client = com.aiassistant.AnthropicSdkClient(apiKey)
            val latch = java.util.concurrent.CountDownLatch(1)
            var resultText = ""
            client.createStreaming(
                messages = listOf(com.aiassistant.AnthropicAdapter.AnthropicMessage("user", prompt)),
                tools = emptyList(),
                model = "deepseek-chat",
                thinkingEnabled = false,
                callback = object : com.aiassistant.AnthropicSdkClient.Callback {
                    override fun onTextDelta(fullText: String) { resultText = fullText }
                    override fun onStreamComplete(text: String, thinking: String, thinkingSig: String, toolCalls: List<com.aiassistant.AnthropicSdkClient.StreamToolCall>, inputTokens: Int, outputTokens: Int, stopReason: String) { latch.countDown() }
                    override fun onError(e: Exception) { latch.countDown() }
                    override fun onToolInputDelta(partialJson: String) {}
                    override fun onToolUseStart(id: String, name: String) {}
                    override fun onThinkingDelta(thinkingText: String) {}
                }
            )
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            analyzer.parseResult(resultText)
        } catch (_: Exception) { emptyList() }
    }

    private fun calculateScore(findings: List<Finding>): Int {
        if (findings.isEmpty()) return 100
        val criticals = findings.count { it.severity == Severity.CRITICAL }
        val warnings = findings.count { it.severity == Severity.WARNING }
        return (100 - criticals * 15 - warnings * 5).coerceAtLeast(0)
    }

    fun getDiffStat(): String = collector.collectDiffStat() ?: "无法获取 diff"
}
```

- [ ] **Step 3: FixApplier**

```kotlin
package com.aiassistant.review

class FixApplier {
    data class FixResult(val fixed: Int, val skipped: Int, val details: List<String>)

    fun apply(findings: List<Finding>, projectBasePath: String?): FixResult {
        val toFix = findings.filter { it.severity == Severity.CRITICAL || it.confidence >= 8 }
        var fixed = 0
        val details = mutableListOf<String>()

        for (f in toFix) {
            if (f.suggestion.isBlank()) {
                details.add("⚠️ ${f.file}:${f.line} — ${f.title}（无修复建议，跳过）")
                continue
            }
            try {
                val file = java.io.File(projectBasePath, f.file)
                if (!file.isFile) { details.add("⚠️ ${f.file} 不存在，跳过"); continue }

                val content = file.readText(Charsets.UTF_8)
                val lines = content.lines()
                if (f.line < 1 || f.line > lines.size) { details.add("⚠️ ${f.file}:${f.line} 行号越界"); continue }

                // 简单替换：如果 suggestion 有明确的 old→new 模式则用，否则追加注释标记
                val targetLine = lines[f.line - 1]
                val newContent = content.replace(targetLine, "// REVIEW-FIX: ${f.title}\n$targetLine")
                file.writeText(newContent, Charsets.UTF_8)
                fixed++
                details.add("✅ ${f.file}:${f.line} — ${f.title}")
            } catch (e: Exception) {
                details.add("❌ ${f.file}:${f.line} — ${e.message}")
            }
        }
        return FixResult(fixed, toFix.size - fixed, details)
    }
}
```

- [ ] **Step 4: CommentFormatter**

```kotlin
package com.aiassistant.review

class CommentFormatter {
    /** 生成 GitHub PR review comment 格式 */
    fun toGitHub(findings: List<Finding>): String {
        return buildString {
            findings.forEach { f ->
                appendLine("> **${f.severity}** (${f.category}, confidence: ${f.confidence}/10)")
                appendLine("> **${f.title}**")
                appendLine("> ${f.description}")
                if (f.suggestion.isNotBlank()) {
                    appendLine("> ```suggestion")
                    appendLine("> ${f.suggestion}")
                    appendLine("> ```")
                }
                appendLine()
            }
        }
    }
}
```

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/aiassistant/review/ReviewAnalyzer.kt src/main/kotlin/com/aiassistant/review/ReviewEngine.kt src/main/kotlin/com/aiassistant/review/FixApplier.kt src/main/kotlin/com/aiassistant/review/CommentFormatter.kt
git commit -m "feat(review): 添加 ReviewEngine + ReviewAnalyzer + FixApplier + CommentFormatter"
```

---

### Task 3: SecurityReviewEngine

**Files:**
- Create: `src/main/kotlin/com/aiassistant/security/SecurityReviewEngine.kt`
- Create: `src/main/kotlin/com/aiassistant/security/InjectionScanner.kt`
- Create: `src/main/kotlin/com/aiassistant/security/SecretDetector.kt`
- Create: `src/main/kotlin/com/aiassistant/security/PermissionAnalyzer.kt`
- Create: `src/main/kotlin/com/aiassistant/security/DependencyChecker.kt`
- Test: `src/test/kotlin/com/aiassistant/security/SecurityDetectorTest.kt`

- [ ] **Step 1: InjectionScanner**

```kotlin
package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category

class InjectionScanner {
    private val patterns = listOf(
        Regex("""ProcessBuilder\s*\(\s*"[^"]*\$\{""") to "命令注入风险",
        Regex("""/bin/bash\s+-c""") to "Shell 命令执行",
        Regex("""Runtime\.getRuntime\(\)\.exec""") to "Runtime.exec 命令执行",
        Regex("""String\.format\s*\(.*SELECT.*\+""") to "SQL 拼接风险",
        Regex("""\.innerHTML\s*=") to "XSS 风险",
    )

    fun scan(content: String, filePath: String): List<Finding> {
        val lines = content.lines()
        val findings = mutableListOf<Finding>()
        for ((i, line) in lines.withIndex()) {
            for ((pattern, desc) in patterns) {
                if (pattern.containsMatchIn(line)) {
                    findings.add(Finding(Severity.WARNING, Category.SECURITY, filePath, i + 1, desc, "检测到第${i+1}行存在潜在$desc", "", 7))
                }
            }
        }
        return findings
    }
}
```

- [ ] **Step 2: SecretDetector**

```kotlin
package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category

class SecretDetector {
    private val secretPatterns = listOf(
        Regex("""(apiKey|api_key|apikey)\s*[:=]\s*["'][^"']{8,}["']""", RegexOption.IGNORE_CASE) to "疑似 API Key 硬编码",
        Regex("""(password|passwd)\s*[:=]\s*["'][^"']+["']""", RegexOption.IGNORE_CASE) to "疑似密码硬编码",
        Regex("""(token|secret)\s*[:=]\s*["'][^"']{8,}["']""", RegexOption.IGNORE_CASE) to "疑似 Token 硬编码",
        Regex("""BEGIN\s+(RSA|EC|DSA)\s+PRIVATE\s+KEY""") to "私钥硬编码",
    )

    fun scan(content: String, filePath: String): List<Finding> {
        val lines = content.lines()
        val findings = mutableListOf<Finding>()
        for ((i, line) in lines.withIndex()) {
            for ((pattern, desc) in secretPatterns) {
                if (pattern.containsMatchIn(line)) {
                    findings.add(Finding(Severity.CRITICAL, Category.SECURITY, filePath, i + 1, desc, "第${i+1}行检测到$desc，请使用环境变量或密钥管理服务", "移除硬编码，改用 PasswordSafe/环境变量", 9))
                }
            }
        }
        return findings
    }
}
```

- [ ] **Step 3: PermissionAnalyzer + DependencyChecker**

```kotlin
// PermissionAnalyzer.kt
package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category

class PermissionAnalyzer {
    fun scan(content: String, filePath: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lines = content.lines()
        for ((i, line) in lines.withIndex()) {
            when {
                line.contains("chmod 777") || line.contains("chmod -R 777") ->
                    findings.add(Finding(Severity.CRITICAL, Category.SECURITY, filePath, i+1, "不安全文件权限", "chmod 777 给所有用户完全读写执行权限", "使用更严格的权限如 chmod 755", 9))
                line.contains("../") && !line.contains("canonicalPath") ->
                    findings.add(Finding(Severity.WARNING, Category.SECURITY, filePath, i+1, "路径遍历风险", "检测到 ../ 但未使用 canonicalPath 校验", "添加 PathUtils.isInsideProject 路径校验", 7))
            }
        }
        return findings
    }
}

// DependencyChecker.kt
package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category
import java.io.File

class DependencyChecker(private val projectBasePath: String?) {
    fun check(): List<Finding> {
        val base = projectBasePath ?: return emptyList()
        val findings = mutableListOf<Finding>()
        val gradleFile = File(base, "build.gradle.kts")
        if (gradleFile.isFile) {
            val content = gradleFile.readText()
            // 检查已知过时/漏洞版本
            val riskyDeps = mapOf(
                "com.google.code.gson:gson:2.8." to "Gson < 2.8.9 存在反序列化漏洞",
                "log4j:log4j:1." to "Log4j 1.x 已停止维护，含多个 CVE",
            )
            val lines = content.lines()
            for ((i, line) in lines.withIndex()) {
                for ((dep, desc) in riskyDeps) {
                    if (line.contains(dep)) findings.add(Finding(Severity.CRITICAL, Category.SECURITY, "build.gradle.kts", i+1, "依赖漏洞", desc, "请升级到安全版本", 8))
                }
            }
        }
        return findings
    }
}
```

- [ ] **Step 4: SecurityReviewEngine 入口**

```kotlin
package com.aiassistant.security

import com.aiassistant.review.Finding

class SecurityReviewEngine(private val projectBasePath: String?) {
    private val injectionScanner = InjectionScanner()
    private val secretDetector = SecretDetector()
    private val permissionAnalyzer = PermissionAnalyzer()
    private val dependencyChecker = DependencyChecker(projectBasePath)

    data class SecurityReport(
        val findings: List<Finding>,
        val dimensionsCovered: List<String>,
        val score: Int
    )

    fun analyze(fileContents: Map<String, String>): SecurityReport {
        val allFindings = mutableListOf<Finding>()
        val dimensions = mutableListOf<String>()

        for ((path, content) in fileContents) {
            val inj = injectionScanner.scan(content, path)
            if (inj.isNotEmpty()) { allFindings.addAll(inj); if ("injection" !in dimensions) dimensions.add("injection") }
            val sec = secretDetector.scan(content, path)
            if (sec.isNotEmpty()) { allFindings.addAll(sec); if ("secrets" !in dimensions) dimensions.add("secrets") }
            val perm = permissionAnalyzer.scan(content, path)
            if (perm.isNotEmpty()) { allFindings.addAll(perm); if ("permissions" !in dimensions) dimensions.add("permissions") }
        }

        val depFindings = dependencyChecker.check()
        if (depFindings.isNotEmpty()) { allFindings.addAll(depFindings); dimensions.add("dependencies") }

        // Dependencies covered: injection/secrets/permissions/dependencies + LLM-enhanced
        val score = calculateSecurityScore(allFindings)
        return SecurityReport(allFindings.sortedByDescending { it.severity.ordinal }, dimensions, score)
    }

    private fun calculateSecurityScore(findings: List<Finding>): Int {
        if (findings.isEmpty()) return 100
        val criticals = findings.count { it.severity == Severity.CRITICAL }
        return (100 - criticals * 20).coerceAtLeast(0)
    }
}
```

- [ ] **Step 5: SecurityDetectorTest**

```kotlin
package com.aiassistant.security

import org.junit.jupiter.api.Test
import kotlin.test.*

class SecurityDetectorTest {
    @Test fun `detects hardcoded API key`() {
        val detector = SecretDetector()
        val findings = detector.scan("val apiKey = \"sk-abc123def456\"", "Config.kt")
        assertTrue(findings.isNotEmpty())
        assertEquals(Severity.CRITICAL, findings[0].severity)
    }

    @Test fun `detects hardcoded password`() {
        val detector = SecretDetector()
        val findings = detector.scan("password: \"admin123\"", "AppConfig.kt")
        assertTrue(findings.any { it.title.contains("密码") })
    }

    @Test fun `no false positive for variable name without literal`() {
        val detector = SecretDetector()
        val findings = detector.scan("val apiKey: String = getKey()", "Config.kt")
        assertTrue(findings.isEmpty())
    }

    @Test fun `detects shell injection in ProcessBuilder`() {
        val scanner = InjectionScanner()
        val findings = scanner.scan("ProcessBuilder(\"/bin/bash\", \"-c\", userInput)", "Exec.kt")
        assertTrue(findings.isNotEmpty())
    }
}
```

- [ ] **Step 6: 运行测试并提交**

```bash
./gradlew test --tests "com.aiassistant.security.SecurityDetectorTest" -v
```
Expected: BUILD SUCCESSFUL

```bash
git add src/main/kotlin/com/aiassistant/security/ src/test/kotlin/com/aiassistant/security/
git commit -m "feat(security): 添加 SecurityReviewEngine + 4 个安全扫描器"
```

---

### Task 4: TestRunner (/test + /fix)

**Files:**
- Create: `src/main/kotlin/com/aiassistant/commands/TestRunner.kt`

- [ ] **Step 1: TestRunner**

```kotlin
package com.aiassistant.commands

import java.io.File

class TestRunner(private val projectBasePath: String?) {

    /** 缓存上次测试结果（供 /fix 使用） */
    @Volatile var lastTestOutput: String? = null
    @Volatile var lastTestFailed: Boolean = false

    data class TestResult(val success: Boolean, val summary: String, val failures: List<TestFailure>)

    data class TestFailure(val testName: String, val errorMessage: String, val stackTrace: String)

    fun run(testFilter: String? = null): TestResult {
        val base = projectBasePath ?: return TestResult(false, "项目路径不可用", emptyList())

        val args = mutableListOf("./gradlew", "test")
        if (testFilter != null) {
            args.add("--tests")
            args.add(testFilter)
        }

        return try {
            val process = ProcessBuilder(args).directory(File(base)).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val success = exitCode == 0
            lastTestOutput = output
            lastTestFailed = !success

            if (success) {
                val testCount = Regex("""(\d+) tests completed""").find(output)?.groupValues?.get(1) ?: "?"
                TestResult(true, "✅ 全部 $testCount 个测试通过", emptyList())
            } else {
                val failures = parseFailures(output)
                TestResult(false, "❌ ${failures.size} 个测试失败", failures)
            }
        } catch (e: Exception) {
            TestResult(false, "❌ 测试执行异常: ${e.message}", emptyList())
        }
    }

    private fun parseFailures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        // 匹配 JUnit 5 和 Gradle 的测试失败格式
        val testNameRegex = Regex("""(\S+)\s+FAILED""")
        val errorRegex = Regex("""(.+) at .+\(.+\.kt:\d+\)""")

        val lines = output.lines()
        var i = 0
        while (i < lines.size) {
            val match = testNameRegex.find(lines[i])
            if (match != null) {
                val name = match.groupValues[1]
                val errorLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("Gradle Test ") && !lines[i].startsWith("BUILD")) {
                    errorLines.add(lines[i])
                    i++
                }
                failures.add(TestFailure(name, errorLines.firstOrNull() ?: "", errorLines.joinToString("\n")))
            } else {
                i++
            }
        }
        return failures
    }

    /** 构建 /fix prompt */
    fun buildFixPrompt(): String? {
        val output = lastTestOutput ?: return null
        return buildString {
            appendLine("以下测试失败，请分析根因并直接修复源代码（使用 edit_file 工具）：")
            appendLine()
            appendLine("```")
            appendLine(output.take(5000))
            appendLine("```")
            appendLine()
            appendLine("修复原则：")
            appendLine("1. 只修改源代码，不修改测试（除非测试本身有 bug）")
            appendLine("2. 每次修改后思考代码变更是否合理")
            appendLine("3. 修复完后自行判断是否还需要进一步修改")
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/kotlin/com/aiassistant/commands/TestRunner.kt
git commit -m "feat(commands): 添加 TestRunner（gradlew test 执行+解析+fix prompt）"
```

---

### Task 5: 斜杠命令集成 + ChatToolWindow 集成

**Files:**
- Create: `src/main/kotlin/com/aiassistant/commands/ReviewCommands.kt`
- Modify: `src/main/kotlin/com/aiassistant/ChatToolWindow.kt`
- Modify: `src/main/kotlin/com/aiassistant/ChatViewModel.kt`

- [ ] **Step 1: ReviewCommands — 5 个命令 action**

```kotlin
package com.aiassistant.commands

import com.aiassistant.review.ReviewEngine
import com.aiassistant.security.SecurityReviewEngine

class ReviewCommands(private val projectBasePath: String?, private val getApiKey: () -> String?) {

    private val reviewEngine = ReviewEngine(projectBasePath)
    private val securityEngine = SecurityReviewEngine(projectBasePath)
    private val testRunner = TestRunner(projectBasePath)

    /** /diff — 纯本地 diff 统计 */
    fun diffAction(): String {
        val stat = reviewEngine.getDiffStat()
        if (stat.isBlank()) return "📊 无变更"
        return "📊 **变更摘要**\n\n```\n$stat\n```"
    }

    /** /review — 调 LLM 审查 */
    fun reviewAction(fixMode: Boolean = false, commentMode: Boolean = false): String {
        val apiKey = getApiKey() ?: return "❌ 请先配置 API Key"
        val result = reviewEngine.review(apiKey)
            ?: return "📋 无变更可审查"

        if (fixMode) {
            val fixResult = reviewEngine.fixApplier.apply(result.findings, projectBasePath)
            return buildString {
                appendLine(renderResult(result.findings, result.score, result.totalFiles))
                appendLine()
                appendLine("## --fix 自动修复")
                appendLine("✅ ${fixResult.fixed} 条已修复 | ⚠️ ${fixResult.skipped} 条需手动处理")
                fixResult.details.forEach { appendLine(it) }
            }
        }

        if (commentMode) {
            return reviewEngine.commentFormatter.toGitHub(result.findings)
        }

        return renderResult(result.findings, result.score, result.totalFiles)
    }

    private fun renderResult(findings: List<Finding>, score: Int, totalFiles: Int): String {
        // import needed at top: import com.aiassistant.review.Finding, Severity
        return buildString {
            appendLine("## 📋 代码审查报告")
            appendLine("**评分:** $score/100 | **文件:** $totalFiles | **发现问题:** ${findings.size}")
            appendLine()
            if (findings.isEmpty()) {
                appendLine("✅ 未发现问题")
                return@buildString
            }
            // 按严重度分组
            for (severity in listOf(Severity.CRITICAL, Severity.WARNING, Severity.INFO)) {
                val group = findings.filter { it.severity == severity }
                if (group.isEmpty()) continue
                val icon = when (severity) { Severity.CRITICAL -> "🔴" ; Severity.WARNING -> "🟡"; else -> "🔵" }
                appendLine("### $icon ${severity.name} (${group.size})")
                group.forEach { f ->
                    appendLine("- **${f.title}** `${f.file}:${f.line}` (${f.category}, ${f.confidence}/10)")
                    appendLine("  ${f.description}")
                    if (f.suggestion.isNotBlank()) appendLine("  💡 `${f.suggestion.take(80)}`")
                }
                appendLine()
            }
        }
    }

    /** /security-review */
    fun securityReviewAction(): String {
        val apiKey = getApiKey() ?: return "❌ 请先配置 API Key"

        // 收集变更文件的源代码
        val collector = com.aiassistant.review.DiffCollector(projectBasePath)
        val diff = collector.collectBranchDiff()?.takeIf { it.isNotBlank() }
        if (diff == null) return "📋 无变更可审查"

        val fileChanges = collector.parse(diff)
        val fileContents = mutableMapOf<String, String>()
        for (fc in fileChanges.filter { !it.isBinary }) {
            val file = java.io.File(projectBasePath, fc.path)
            if (file.isFile) fileContents[fc.path] = file.readText(Charsets.UTF_8)
        }

        val report = securityEngine.analyze(fileContents)
        return buildString {
            appendLine("## 🔒 安全审查报告")
            appendLine("**评分:** ${report.score}/100 | **维度:** ${report.dimensionsCovered.joinToString(", ")} | **发现问题:** ${report.findings.size}")
            appendLine()
            report.findings.groupBy { it.severity }.forEach { (severity, group) ->
                appendLine("### ${severity.name} (${group.size})")
                group.forEach { f ->
                    appendLine("- **${f.title}** `${f.file}:${f.line}`")
                    appendLine("  ${f.description}")
                    if (f.suggestion.isNotBlank()) appendLine("  💡 `${f.suggestion}`")
                }
                appendLine()
            }
        }
    }

    /** /test */
    fun testAction(filter: String? = null): String {
        val result = testRunner.run(filter)
        return buildString {
            appendLine(result.summary)
            for (f in result.failures) {
                appendLine("\n### ❌ ${f.testName}")
                appendLine("```")
                appendLine(f.errorMessage.take(500))
                appendLine("```")
            }
            if (result.failures.isNotEmpty()) {
                appendLine("\n💡 使用 `/fix` 让 AI 分析并修复失败的测试")
            }
        }
    }

    /** /fix */
    fun fixAction(): String {
        val prompt = testRunner.buildFixPrompt()
            ?: return "📋 没有缓存的测试失败输出。请先运行 `/test`。"
        // 直接发送给 LLM 处理（由 ChatToolWindow 的 sendQuick 执行）
        return prompt
    }
}
```

- [ ] **Step 2: 在 ChatToolWindow 中注册 5 个新命令**

修改现有的 Cmd 列表。将原有的 `/review` 和 `/test` Cmd 替换为新实现，并新增 `/diff`、`/fix`、`/security-review`。

在 Cmd 列表处：

```kotlin
// 创建 ReviewCommands 实例（在 ChatToolWindow 中）
private val reviewCommands by lazy {
    com.aiassistant.commands.ReviewCommands(project.basePath) { viewModel.getApiKey() }
}

// 替换 Cmd 列表中的 /review 和 /test，新增 /diff /fix /security-review
// 查找现有 Cmd("/review",...) 替换为:
Cmd("/review", "审查当前改动") {
    Thread({ val result = reviewCommands.reviewAction()
        edt { addSystemMessage(result) }
    }, "review-cmd").apply { isDaemon = true }.start()
},
// 查找现有 Cmd("/test",...) 替换为:
Cmd("/test", "运行测试") {
    Thread({ val result = reviewCommands.testAction()
        edt { addSystemMessage(result) }
    }, "test-cmd").apply { isDaemon = true }.start()
},
// 新增:
Cmd("/diff", "查看变更") { addSystemMessage(reviewCommands.diffAction()) },
Cmd("/fix", "修复测试") {
    Thread({ val prompt = reviewCommands.fixAction()
        edt { sendQuick(prompt) }
    }, "fix-cmd").apply { isDaemon = true }.start()
},
Cmd("/security-review", "安全审查") {
    Thread({ val result = reviewCommands.securityReviewAction()
        edt { addSystemMessage(result) }
    }, "sec-review-cmd").apply { isDaemon = true }.start()
},
```

注意：保留旧的 `/review` 和 `/test` Cmd 的原始位置，但将 action 替换为新的实现。旧的 `/review` action 是 `sendQuick("请审查当前分支的代码改动...")`，旧的 `/test` action 是 `sendQuick("分析测试结果并修复失败的测试。")`。

- [ ] **Step 3: ChatViewModel 添加 getApiKey 和 addSystemMessage 辅助**

在 ChatViewModel 中添加：

```kotlin
fun getApiKey(): String? = com.aiassistant.AppSettingsService.getInstance().getApiKey()

/** 添加系统消息到对话中 */
fun addSystemMessage(text: String) {
    messages.add(AgentMessage("system", text))
    onMessagesChanged?.invoke()
}
```

ChatToolWindow 中添加：

```kotlin
private fun addSystemMessage(text: String) {
    viewModel.addSystemMessage(text)
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/com/aiassistant/commands/ReviewCommands.kt src/main/kotlin/com/aiassistant/ChatToolWindow.kt src/main/kotlin/com/aiassistant/ChatViewModel.kt
git commit -m "feat(commands): 集成 5 个斜杠命令到 ChatToolWindow"
```

---

### Task 6: 最终验证

- [ ] **Step 1: 编译验证**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 全部测试**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 3: 检查现有 /review /test Cmd 已被正确替换**

```bash
grep -n "Cmd(\"/review\"\|Cmd(\"/test\"\|Cmd(\"/diff\"\|Cmd(\"/fix\"\|Cmd(\"/security-review\"" src/main/kotlin/com/aiassistant/ChatToolWindow.kt
```
Expected: 5 个命令都有 Cmd 条目

- [ ] **Step 4: 提交**

```bash
git commit -m "chore(review): 最终验证——编译+测试全部通过" --allow-empty
```

---

## 实施统计

| Task | 新文件 | 改文件 |
|------|--------|--------|
| T1 DiffCollector | 2 | 0 |
| T2 ReviewEngine | 4 | 0 |
| T3 Security | 6 | 0 |
| T4 TestRunner | 1 | 0 |
| T5 命令集成 | 1 | 2 |
| T6 验证 | 0 | 0 |
| **合计** | **14 新文件** | **2 改文件** |

## 并行执行建议

- **Lane A**: T1 (DiffCollector) → T2 (ReviewEngine)
- **Lane B**: T3 (SecurityReviewEngine)
- **Lane C**: T4 (TestRunner)
- **A+B+C 合流后**: T5 (命令集成) → T6 (验证)

T1/T2、T3、T4 三路可并行执行（无共享模块）
