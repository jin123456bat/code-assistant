package com.aiassistant.hooks

import java.util.UUID

class HookEventBus(
    private val config: HookConfig,
    private val executor: HookExecutor
) {
    private val sessionId = UUID.randomUUID().toString()

    fun fire(event: String, context: Map<String, Any?>? = null): HookDecision {
        val entries = config.hooks[event] ?: return HookDecision()
        val matchedEntries = mutableListOf<HookEntry>()

        for (me in entries) {
            if (HookMatcher.matches(me.matcher, context?.get("tool_name")?.toString())) {
                matchedEntries.addAll(me.hooks)
            }
        }

        if (matchedEntries.isEmpty()) return HookDecision()

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
