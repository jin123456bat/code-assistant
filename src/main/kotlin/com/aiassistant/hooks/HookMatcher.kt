package com.aiassistant.hooks

object HookMatcher {
    fun matches(matcher: String?, toolName: String?): Boolean {
        if (matcher.isNullOrBlank()) return true
        if (toolName == null) return false
        return try { Regex(matcher).containsMatchIn(toolName) } catch (_: Exception) { false }
    }
}
