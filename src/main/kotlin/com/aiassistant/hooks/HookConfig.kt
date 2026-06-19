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
