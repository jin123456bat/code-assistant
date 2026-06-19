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
