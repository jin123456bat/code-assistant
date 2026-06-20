package com.aiassistant.hooks

import com.google.gson.Gson
import java.io.File

object HookConfigLoader {

    private val gson = Gson()

    fun load(projectBasePath: String?): HookConfig {
        val global = loadJsonFile(File(System.getProperty("user.home") ?: ".", ".claude/settings.json"))
        val project = projectBasePath?.let { loadYamlFile(File(it, ".claude/hooks.yaml")) }
        return merge(global, project)
    }

    private fun loadJsonFile(file: File): HookConfig {
        if (!file.isFile) return HookConfig()
        return try {
            val root = gson.fromJson(file.readText(Charsets.UTF_8), Map::class.java) as? Map<*, *> ?: return HookConfig()
            parseHooksSection(root["hooks"])
        } catch (_: Exception) {
            HookConfig()
        }
    }

    private fun loadYamlFile(file: File): HookConfig {
        if (!file.isFile) return HookConfig()
        return try {
            parseHookYaml(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            HookConfig()
        }
    }

    private fun parseHooksSection(hooksObj: Any?): HookConfig {
        if (hooksObj !is Map<*, *>) return HookConfig()
        val hooks = mutableMapOf<String, List<HookMatcherEntry>>()
        for ((eventKey, entriesList) in hooksObj) {
            if (entriesList !is List<*>) continue
            val entries = entriesList.mapNotNull { parseMatcherEntry(it) }
            hooks[eventKey.toString()] = entries
        }
        return HookConfig(hooks)
    }

    private fun parseMatcherEntry(obj: Any?): HookMatcherEntry? {
        if (obj !is Map<*, *>) return null
        val hooksList = obj["hooks"] as? List<*> ?: return null
        val hooks = hooksList.mapNotNull { parseHookEntry(it) }
        return HookMatcherEntry(obj["matcher"]?.toString(), hooks)
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

    internal fun merge(global: HookConfig, project: HookConfig?): HookConfig {
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

    internal fun parseHookYaml(text: String): HookConfig {
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
                trimmed.startsWith("- type:") -> currentHookEntries.add(parseYamlHookLine(trimmed))
                // 缩进行的 key-value 对，如 "  command: echo hello"
                trimmed.startsWith("  ") && trimmed.contains(":") && !trimmed.startsWith("  -") -> {
                    if (currentHookEntries.isNotEmpty()) {
                        val kv = trimmed.split(":", limit = 2)
                        if (kv.size == 2) {
                            val key = kv[0].trim()
                            val value = kv[1].trim().removeSurrounding("\"")
                            val lastEntry = currentHookEntries.last()
                            currentHookEntries[currentHookEntries.lastIndex] = when (key) {
                                "command" -> lastEntry.copy(command = value)
                                "url" -> lastEntry.copy(url = value)
                                "method" -> lastEntry.copy(method = value)
                                "tool" -> lastEntry.copy(tool = value)
                                "prompt" -> lastEntry.copy(prompt = value)
                                "timeout" -> lastEntry.copy(timeout = value.toIntOrNull() ?: 60)
                                else -> lastEntry
                            }
                        }
                    }
                }
            }
        }
        flushEntries(hooks, currentEvent, currentMatcher, currentHookEntries)
        return HookConfig(hooks)
    }

    private fun flushEntries(
        hooks: MutableMap<String, List<HookMatcherEntry>>,
        event: String?,
        matcher: String?,
        entries: List<HookEntry>
    ) {
        if (event == null || entries.isEmpty()) return
        val list = hooks.getOrPut(event) { mutableListOf() }.toMutableList()
        list.add(HookMatcherEntry(matcher, entries.toList()))
        hooks[event] = list
    }

    private fun parseYamlHookLine(line: String): HookEntry {
        val type = line.removePrefix("- type:").trim().removeSurrounding("\"")
        return HookEntry(type = type)
    }
}
