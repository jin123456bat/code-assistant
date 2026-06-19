# Hooks 事件系统 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现事件驱动的 hook 系统，对齐 Claude Code Hooks 规范。4 种 hook 类型（command/http/mcp/prompt），13 个事件，同步阻塞 PreToolUse 可阻断工具执行。

**Architecture:** 新增 `hooks/` 包（8 文件：配置模型+加载+匹配+执行+合并+4 种 Runner）。HookEventBus 单例作为事件总线，AgentLoop/ChatViewModel 等集成点 fire 事件。配置从 settings.json + .claude/hooks.yaml 加载合并。

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Gson, ProcessBuilder, HttpURLConnection, MCP (复用 McpManager)

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/kotlin/com/aiassistant/hooks/HookConfig.kt` | 创建 | HookConfig/HookMatcherEntry/HookEntry 数据模型 |
| `src/main/kotlin/com/aiassistant/hooks/HookEventContext.kt` | 创建 | HookEventContext/HookDecision 事件模型 |
| `src/main/kotlin/com/aiassistant/hooks/HookConfigLoader.kt` | 创建 | JSON/YAML 加载 + 合并（settings.json + .claude/hooks.yaml） |
| `src/main/kotlin/com/aiassistant/hooks/HookMatcher.kt` | 创建 | 正则匹配工具名/事件名 |
| `src/main/kotlin/com/aiassistant/hooks/HookEventBus.kt` | 创建 | 事件总线单例：注册→分发→执行→合并结果 |
| `src/main/kotlin/com/aiassistant/hooks/HookExecutor.kt` | 创建 | 调度 4 种 Runner，stdin JSON 构建，并发执行 |
| `src/main/kotlin/com/aiassistant/hooks/HookDecisionMerger.kt` | 创建 | 多 hook 结果合并（deny 优先） |
| `src/main/kotlin/com/aiassistant/hooks/CommandHookRunner.kt` | 创建 | ProcessBuilder 执行本地脚本 |
| `src/main/kotlin/com/aiassistant/hooks/HttpHookRunner.kt` | 创建 | HTTP POST |
| `src/main/kotlin/com/aiassistant/hooks/McpHookRunner.kt` | 创建 | MCP 工具调用 |
| `src/main/kotlin/com/aiassistant/hooks/PromptHookRunner.kt` | 创建 | 变量替换 + prompt 返回 |
| `src/main/kotlin/com/aiassistant/agent/AgentLoop.kt` | 修改 | PreToolUse/PostToolUse fire + deny 处理 |
| `src/main/kotlin/com/aiassistant/ChatViewModel.kt` | 修改 | SessionStart/SessionEnd/Stop fire |
| `src/main/kotlin/com/aiassistant/agent/SubAgentRegistry.kt` | 修改 | SubagentStart/Stop fire |

---

### Task 1: 数据模型 (HookConfig + HookEventContext)

**Files:**
- Create: `src/main/kotlin/com/aiassistant/hooks/HookConfig.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/HookEventContext.kt`

- [ ] **Step 1: HookConfig.kt**

```kotlin
package com.aiassistant.hooks

data class HookConfig(
    val hooks: Map<String, List<HookMatcherEntry>> = emptyMap()
)

data class HookMatcherEntry(
    val matcher: String? = null,
    val hooks: List<HookEntry> = emptyList()
)

data class HookEntry(
    val type: String,
    val command: String? = null,
    val url: String? = null,
    val method: String = "POST",
    val tool: String? = null,
    val prompt: String? = null,
    val timeout: Int = 60
)
```

- [ ] **Step 2: HookEventContext.kt**

```kotlin
package com.aiassistant.hooks

data class HookEventContext(
    val event: String,
    val tool_name: String? = null,
    val tool_input: Map<String, Any>? = null,
    val tool_result: String? = null,
    val session_id: String,
    val project_dir: String? = null,
    val transcript_path: String? = null
)

data class HookDecision(
    val permissionDecision: String? = null,
    val content: String? = null
)
```

- [ ] **Step 3: 提交**

```bash
git add src/main/kotlin/com/aiassistant/hooks/HookConfig.kt src/main/kotlin/com/aiassistant/hooks/HookEventContext.kt
git commit -m "feat(hooks): 添加 HookConfig 和 HookEventContext 数据模型"
```

---

### Task 2: HookConfigLoader — 配置加载+合并

**Files:**
- Create: `src/main/kotlin/com/aiassistant/hooks/HookConfigLoader.kt`
- Test: `src/test/kotlin/com/aiassistant/hooks/HookConfigLoaderTest.kt`

- [ ] **Step 1: HookConfigLoader.kt**

```kotlin
package com.aiassistant.hooks

import com.google.gson.Gson
import java.io.File

object HookConfigLoader {

    private val gson = Gson()

    /** 加载并合并全局+项目配置。项目 matcher 覆盖全局同 matcher 条目 */
    fun load(projectBasePath: String?): HookConfig {
        val global = loadJson(File(System.getProperty("user.home") ?: ".", ".claude/settings.json"))
        val project = projectBasePath?.let { loadYaml(File(it, ".claude/hooks.yaml")) }
        return merge(global, project)
    }

    private fun loadJson(file: File): HookConfig {
        if (!file.isFile) return HookConfig()
        return try {
            val root = gson.fromJson(file.readText(Charsets.UTF_8), Map::class.java) as? Map<*, *> ?: return HookConfig()
            parseHooksSection(root["hooks"])
        } catch (_: Exception) { HookConfig() }
    }

    private fun loadYaml(file: File): HookConfig {
        if (!file.isFile) return HookConfig()
        return try {
            // 简单 YAML 解析：读取所有行，手工解析（不引入第三方 YAML 库）
            val text = file.readText(Charsets.UTF_8)
            parseHookYaml(text)
        } catch (_: Exception) { HookConfig() }
    }

    /** 合并：项目级覆盖全局级（同 matcher 的 hook 列表替换） */
    private fun merge(global: HookConfig, project: HookConfig?): HookConfig {
        if (project == null) return global
        val merged = global.hooks.toMutableMap()
        for ((event, entries) in project.hooks) {
            val globalEntries = global.hooks[event] ?: emptyList()
            val mergedEntries = globalEntries.toMutableList()
            for (pe in entries) {
                val idx = mergedEntries.indexOfFirst { it.matcher == pe.matcher }
                if (idx >= 0) mergedEntries[idx] = pe else mergedEntries.add(pe)
            }
            merged[event] = mergedEntries
        }
        return HookConfig(merged)
    }

    /** 从 settings.json 的 hooks 字段解析 */
    private fun parseHooksSection(hooksObj: Any?): HookConfig {
        if (hooksObj !is Map<*, *>) return HookConfig()
        val hooks = mutableMapOf<String, List<HookMatcherEntry>>()
        for ((eventKey, entriesList) in hooksObj) {
            val event = eventKey.toString()
            if (entriesList !is List<*>) continue
            val entries = entriesList.mapNotNull { parseMatcherEntry(it) }
            hooks[event] = entries
        }
        return HookConfig(hooks)
    }

    private fun parseMatcherEntry(obj: Any?): HookMatcherEntry? {
        if (obj !is Map<*, *>) return null
        val matcher = obj["matcher"]?.toString()
        val hooksList = obj["hooks"] as? List<*> ?: return null
        val hooks = hooksList.mapNotNull { parseHookEntry(it) }
        return HookMatcherEntry(matcher, hooks)
    }

    private fun parseHookEntry(obj: Any?): HookEntry? {
        if (obj !is Map<*, *>) return null
        val type = obj["type"]?.toString() ?: return null
        return HookEntry(
            type = type,
            command = obj["command"]?.toString(),
            url = obj["url"]?.toString(),
            method = obj["method"]?.toString() ?: "POST",
            tool = obj["tool"]?.toString(),
            prompt = obj["prompt"]?.toString(),
            timeout = (obj["timeout"] as? Number)?.toInt() ?: 60
        )
    }

    /** 手工解析 .claude/hooks.yaml（轻量，对齐 Claude Code 格式） */
    private fun parseHookYaml(text: String): HookConfig {
        val hooks = mutableMapOf<String, List<HookMatcherEntry>>()
        var currentEvent: String? = null
        var currentMatcher: String? = null
        val currentHookEntries = mutableListOf<HookEntry>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "hooks:") continue

            when {
                trimmed.endsWith(":") && !trimmed.startsWith("-") -> {
                    flushEntries(hooks, currentEvent, currentMatcher, currentHookEntries)
                    currentEvent = trimmed.removeSuffix(":").trim()
                    currentMatcher = null
                    currentHookEntries.clear()
                }
                trimmed.startsWith("- matcher:") -> {
                    flushEntries(hooks, currentEvent, currentMatcher, currentHookEntries)
                    currentMatcher = trimmed.removePrefix("- matcher:").trim().removeSurrounding("\"")
                    currentHookEntries.clear()
                }
                trimmed.startsWith("- type:") -> {
                    currentHookEntries.add(parseYamlHookLine(trimmed))
                }
            }
        }
        flushEntries(hooks, currentEvent, currentMatcher, currentHookEntries)
        return HookConfig(hooks)
    }

    private fun flushEntries(hooks: MutableMap<String, List<HookMatcherEntry>>, event: String?, matcher: String?, entries: MutableList<HookEntry>) {
        if (event == null || entries.isEmpty()) return
        val list = hooks.getOrPut(event) { mutableListOf() }.toMutableList()
        list.add(HookMatcherEntry(matcher, entries.toList()))
        hooks[event] = list
        entries.clear()
    }

    private fun parseYamlHookLine(line: String): HookEntry {
        val type = line.removePrefix("- type:").trim().removeSurrounding("\"")
        return HookEntry(type = type)
    }
}
```

- [ ] **Step 2: HookConfigLoaderTest.kt**

```kotlin
package com.aiassistant.hooks

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class HookConfigLoaderTest {
    @Test fun `load returns empty for no config files`() {
        val config = HookConfigLoader.load("/nonexistent/path")
        assertNotNull(config)
    }

    @Test fun `parse YAML extracts event names`() {
        val yaml = """
hooks:
  PreToolUse:
    - matcher: "write_file"
      hooks:
        - type: command
          command: "echo test"
  SessionStart:
    - hooks:
        - type: http
          url: "https://example.com"
""".trim()
        val config = HookConfigLoader.parseHookYaml(yaml)
        assertTrue(config.hooks.containsKey("PreToolUse"))
        assertTrue(config.hooks.containsKey("SessionStart"))
    }

    @Test fun `merge project over global`() {
        val global = HookConfig(
            mapOf("PreToolUse" to listOf(
                HookMatcherEntry("write_file", listOf(HookEntry("command", command = "global.sh")))
            ))
        )
        val project = HookConfig(
            mapOf("PreToolUse" to listOf(
                HookMatcherEntry("write_file", listOf(HookEntry("command", command = "project.sh")))
            ))
        )
        val merged = HookConfigLoader.merge(global, project)
        val entries = merged.hooks["PreToolUse"]!!
        assertEquals(1, entries.size)
        assertEquals("project.sh", entries[0].hooks[0].command)
    }
}
```

注意：parseHookYaml 是 private 方法，测试需要改为 `internal` 可见。
在代码中改为 `internal fun parseHookYaml(text: String): HookConfig`。

- [ ] **Step 3: 运行测试并提交**

```bash
./gradlew test --tests "com.aiassistant.hooks.HookConfigLoaderTest" -v
```
Expected: BUILD SUCCESSFUL

```bash
git add src/main/kotlin/com/aiassistant/hooks/HookConfigLoader.kt src/test/kotlin/com/aiassistant/hooks/HookConfigLoaderTest.kt
git commit -m "feat(hooks): 添加 HookConfigLoader 配置加载+合并"
```

---

### Task 3: HookMatcher + HookEventBus + HookDecisionMerger

**Files:**
- Create: `src/main/kotlin/com/aiassistant/hooks/HookMatcher.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/HookEventBus.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/HookDecisionMerger.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/HookExecutor.kt`

- [ ] **Step 1: HookMatcher.kt**

```kotlin
package com.aiassistant.hooks

object HookMatcher {
    /** 匹配工具名：空 matcher 匹配所有，否则正则匹配 */
    fun matches(matcher: String?, toolName: String?): Boolean {
        if (matcher.isNullOrBlank()) return true
        if (toolName == null) return false
        return try { Regex(matcher).containsMatchIn(toolName) } catch (_: Exception) { false }
    }
}
```

- [ ] **Step 2: HookDecisionMerger.kt**

```kotlin
package com.aiassistant.hooks

object HookDecisionMerger {
    fun merge(decisions: List<HookDecision?>): HookDecision {
        val valid = decisions.filterNotNull()
        val deny = valid.find { it.permissionDecision == "deny" }
        if (deny != null) return deny
        val contents = valid.mapNotNull { it.content }.filter { it.isNotBlank() }
        return HookDecision(
            permissionDecision = "allow",
            content = if (contents.isNotEmpty()) contents.joinToString("\n\n") else null
        )
    }
}
```

- [ ] **Step 3: HookExecutor.kt**

```kotlin
package com.aiassistant.hooks

import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HookExecutor(private val mcpManager: com.aiassistant.mcp.McpManager?) {

    private val gson = Gson()

    fun execute(entries: List<HookEntry>, context: HookEventContext): List<HookDecision> {
        if (entries.isEmpty()) return emptyList()
        val decisions = mutableListOf<HookDecision?>()
        val latch = CountDownLatch(entries.size)

        for (entry in entries) {
            Thread({
                try {
                    decisions.add(executeOne(entry, context))
                } catch (_: Exception) { decisions.add(null) }
                finally { latch.countDown() }
            }, "hook-${entry.type}").start()
        }

        latch.await(entries.maxOfOrNull { it.timeout }?.toLong() ?: 60, TimeUnit.SECONDS)
        return decisions.filterNotNull()
    }

    private fun executeOne(entry: HookEntry, context: HookEventContext): HookDecision? {
        val contextJson = gson.toJson(context)
        return when (entry.type) {
            "command" -> CommandHookRunner.run(entry.command ?: return null, contextJson, entry.timeout)
            "http" -> HttpHookRunner.run(entry.url ?: return null, contextJson, entry.timeout)
            "mcp" -> McpHookRunner.run(entry.tool ?: return null, context, mcpManager)
            "prompt" -> PromptHookRunner.run(entry.prompt ?: return null, context)
            else -> null
        }
    }
}
```

- [ ] **Step 4: HookEventBus.kt**

```kotlin
package com.aiassistant.hooks

import java.util.UUID

class HookEventBus(
    private val config: HookConfig,
    private val executor: HookExecutor
) {
    private val sessionId = UUID.randomUUID().toString()

    /** 触发事件：匹配 → 执行 → 合并 → 返回决策 */
    fun fire(event: String, context: Map<String, Any?>? = null): HookDecision {
        val entries = config.hooks[event] ?: return HookDecision()
        val matchedEntries = mutableListOf<HookEntry>()

        for (me in entries) {
            if (HookMatcher.matches(me.matcher, context?.get("tool_name")?.toString())) {
                matchedEntries.addAll(me.hooks)
            }
        }

        val fullContext = HookEventContext(
            event = event,
            tool_name = context?.get("tool_name")?.toString(),
            tool_input = context?.get("tool_input") as? Map<String, Any>,
            tool_result = context?.get("tool_result")?.toString(),
            session_id = sessionId,
            project_dir = context?.get("project_dir")?.toString(),
            transcript_path = context?.get("transcript_path")?.toString()
        )

        return HookDecisionMerger.merge(executor.execute(matchedEntries, fullContext))
    }

    fun getSessionId() = sessionId
}
```

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/aiassistant/hooks/HookMatcher.kt src/main/kotlin/com/aiassistant/hooks/HookEventBus.kt src/main/kotlin/com/aiassistant/hooks/HookDecisionMerger.kt src/main/kotlin/com/aiassistant/hooks/HookExecutor.kt
git commit -m "feat(hooks): 添加 HookMatcher/EventBus/Executor/DecisionMerger 核心引擎"
```

---

### Task 4: 4 个 Hook Runner

**Files:**
- Create: `src/main/kotlin/com/aiassistant/hooks/CommandHookRunner.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/HttpHookRunner.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/McpHookRunner.kt`
- Create: `src/main/kotlin/com/aiassistant/hooks/PromptHookRunner.kt`

- [ ] **Step 1: CommandHookRunner.kt**

```kotlin
package com.aiassistant.hooks

import java.util.concurrent.TimeUnit

object CommandHookRunner {
    fun run(command: String, stdinJson: String, timeoutSec: Int): HookDecision? {
        return try {
            val shell = if (System.getProperty("os.name").lowercase().contains("win"))
                arrayOf("cmd.exe", "/c", command) else arrayOf("/bin/bash", "-c", command)
            val process = ProcessBuilder(*shell).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(stdinJson); it.newLine() }
            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            parseDecision(stdout)
        } catch (_: Exception) { null }
    }

    private fun parseDecision(output: String): HookDecision? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        return try {
            com.google.gson.Gson().fromJson(trimmed, HookDecision::class.java)
        } catch (_: Exception) {
            HookDecision(content = trimmed)
        }
    }
}
```

- [ ] **Step 2: HttpHookRunner.kt**

```kotlin
package com.aiassistant.hooks

import java.net.HttpURLConnection
import java.net.URI

object HttpHookRunner {
    fun run(url: String, bodyJson: String, timeoutSec: Int): HookDecision? {
        return try {
            val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = (timeoutSec * 1000)
            conn.readTimeout = (timeoutSec * 1000)
            conn.outputStream.bufferedWriter().use { it.write(bodyJson) }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parseHttpResponse(response)
        } catch (_: Exception) { null }
    }

    private fun parseHttpResponse(response: String): HookDecision? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return null
        return try {
            com.google.gson.Gson().fromJson(trimmed, HookDecision::class.java)
        } catch (_: Exception) {
            HookDecision(content = trimmed)
        }
    }
}
```

- [ ] **Step 3: McpHookRunner.kt**

```kotlin
package com.aiassistant.hooks

import com.google.gson.Gson
import com.aiassistant.mcp.McpManager

object McpHookRunner {
    fun run(toolName: String, context: HookEventContext, mcpManager: McpManager?): HookDecision? {
        if (mcpManager == null) return null
        return try {
            val params = mapOf(
                "event" to context.event,
                "tool_name" to (context.tool_name ?: ""),
                "session_id" to context.session_id
            )
            val result = mcpManager.callTool(toolName, Gson().toJson(params))
            if (result.success) parseDecision(result.content) else null
        } catch (_: Exception) { null }
    }

    private fun parseDecision(output: String): HookDecision? {
        return try {
            Gson().fromJson(output, HookDecision::class.java)
        } catch (_: Exception) {
            HookDecision(content = output)
        }
    }
}
```

- [ ] **Step 4: PromptHookRunner.kt**

```kotlin
package com.aiassistant.hooks

object PromptHookRunner {
    fun run(prompt: String, context: HookEventContext): HookDecision {
        val expanded = prompt
            .replace("\$TOOL_NAME", context.tool_name ?: "")
            .replace("\$PROJECT_DIR", context.project_dir ?: "")
            .replace("\$SESSION_ID", context.session_id)
        return HookDecision(content = expanded)
    }
}
```

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/aiassistant/hooks/CommandHookRunner.kt src/main/kotlin/com/aiassistant/hooks/HttpHookRunner.kt src/main/kotlin/com/aiassistant/hooks/McpHookRunner.kt src/main/kotlin/com/aiassistant/hooks/PromptHookRunner.kt
git commit -m "feat(hooks): 添加 4 个 Hook Runner（command/http/mcp/prompt）"
```

---

### Task 5: 集成——AgentLoop + ChatViewModel + SubAgentRegistry

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/agent/AgentLoop.kt`
- Modify: `src/main/kotlin/com/aiassistant/ChatViewModel.kt`
- Modify: `src/main/kotlin/com/aiassistant/agent/SubAgentRegistry.kt`

- [ ] **Step 1: AgentContext.kt 添加 hookEventBus**

```kotlin
// 在 AgentContext 中添加（放在 memoryEngine 字段附近）:
@Volatile var hookEventBus: com.aiassistant.hooks.HookEventBus? = null
```

- [ ] **Step 2: AgentLoop.kt — PreToolUse/PostToolUse fire**

在工具执行处添加（找到 tc.name 获取后、toolRegistry.executeTool 之前）。PreToolUse 返回 deny 则阻止执行：

```kotlin
// PreToolUse: fire hook 事件，可能 deny 工具执行
val hookBus = ctx.hookEventBus
if (hookBus != null) {
    val decision = hookBus.fire("PreToolUse", mapOf(
        "tool_name" to tc.name,
        "tool_input" to com.google.gson.Gson().fromJson(tc.arguments, Map::class.java),
        "project_dir" to project.basePath
    ))
    if (decision.permissionDecision == "deny") {
        AppLogger.info("HookBus: PreToolUse 阻止执行 ${tc.name}")
        val hookResult = ToolResult.err("Hook 阻止执行: ${decision.content ?: "无原因说明"}")
        // 跳过工具执行，直接注入 tool_result
        synchronized(ctx.historyLock) {
            history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments))
            history.add(AnthropicMessage("user", "Hook 阻止: ${decision.content ?: "无"}", toolCallId = tc.id, groupId = roundGroupId))
        }
        edt { onToolResult?.invoke(tc.name, hookResult.content) }
        return // 跳过此工具
    }
    // 注入 content 到上下文
    if (decision.content != null) {
        ctx.pendingDiagnostics = "${ctx.pendingDiagnostics ?: ""}\n${decision.content}"
    }
}
```

PostToolUse 在工具执行后（获取 toolResult 后）:
```kotlin
// PostToolUse: 通知 hook，不阻断
hookBus?.fire("PostToolUse", mapOf(
    "tool_name" to tc.name,
    "tool_input" to com.google.gson.Gson().fromJson(tc.arguments, Map::class.java),
    "tool_result" to resultText,
    "project_dir" to project.basePath
))
```

- [ ] **Step 3: ChatViewModel.kt — 生命周期事件 fire**

在 `initialize()` 末尾（agent 创建后）:
```kotlin
// SessionStart
val hookBus = agent?.ctx?.hookEventBus
if (hookBus != null) {
    hookBus.fire("SessionStart", mapOf("project_dir" to project.basePath))
}
```

在 `clearConversation()` 末尾:
```kotlin
// SessionEnd
hookBus?.fire("SessionEnd", mapOf(
    "project_dir" to project?.basePath,
    "transcript_path" to "sessions/$autoSaveSessionId"
))
```

在 `stopGeneration()` 末尾:
```kotlin
// Stop
hookBus?.fire("Stop", mapOf("project_dir" to project?.basePath))
```

- [ ] **Step 4: ChatToolWindow.kt — 初始化 HookEventBus**

在 `init` 块中（viewModel.initialize 之前或附近）:
```kotlin
// 加载 hooks 配置并创建 HookEventBus
val hookConfig = com.aiassistant.hooks.HookConfigLoader.load(project.basePath)
val hookExecutor = com.aiassistant.hooks.HookExecutor(mcpManager)
val hookBus = com.aiassistant.hooks.HookEventBus(hookConfig, hookExecutor)
// 注入到 AgentContext
viewModel.agent?.ctx?.hookEventBus = hookBus
```

需要先确认 viewModel 的 agent 在此时存在，或改用 Thread-init 后注入。

- [ ] **Step 5: SubAgentRegistry.kt — SubagentStart/Stop fire**

在 `register()` 方法中添加:
```kotlin
// SubagentStart——通过首个注册的 AgentContext 的 hookBus fire（子代理共享）
```

在 `drainCompleted()` 中:
```kotlin
// SubagentStop——子代理完成后 fire
```

- [ ] **Step 6: 编译验证 + 提交**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

```bash
git add src/main/kotlin/com/aiassistant/agent/AgentLoop.kt src/main/kotlin/com/aiassistant/agent/AgentContext.kt src/main/kotlin/com/aiassistant/ChatViewModel.kt src/main/kotlin/com/aiassistant/ChatToolWindow.kt
git commit -m "feat(hooks): 集成 HookEventBus 到 AgentLoop/ChatViewModel/ChatToolWindow"
```

---

### Task 6: 最终验证

- [ ] **Step 1: clean compile**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 全部测试**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git commit -m "chore(hooks): 最终验证——编译+测试通过" --allow-empty
```

---

## 实施统计

| Task | 新文件 | 改文件 |
|------|--------|--------|
| T1 数据模型 | 2 | 0 |
| T2 ConfigLoader | 2 | 0 |
| T3 核心引擎 | 4 | 0 |
| T4 Hook Runner | 4 | 0 |
| T5 集成 | 1 | 4 |
| T6 验证 | 0 | 0 |
| **合计** | **13 新文件** | **4 改文件** |

## 并行执行建议

- **Lane A**: T1 → T3 (数据模型→引擎)
- **Lane B**: T2 (ConfigLoader 独立)
- **Lane C**: T4 (Hook Runner 独立)
- **合流**: T5 (集成，依赖 T1-T4) → T6 (验证)
