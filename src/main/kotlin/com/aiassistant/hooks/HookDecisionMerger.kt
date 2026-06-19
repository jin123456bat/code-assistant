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
