package com.aiassistant.hooks

import com.google.gson.Gson

object McpHookRunner {
    fun run(toolName: String, context: HookEventContext, mcpManager: com.aiassistant.mcp.McpManager?): HookDecision? {
        if (mcpManager == null) return null
        return try {
            // McpManager 目前不暴露 callTool 方法，返回 null 以安全降级
            // 后续需要实现时可通过 McpClient.callToolRaw 调用
            null
        } catch (_: Exception) { null }
    }
}
