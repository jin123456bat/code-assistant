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
