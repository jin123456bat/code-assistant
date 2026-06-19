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
                try { decisions.add(executeOne(entry, context)) }
                catch (_: Exception) { decisions.add(null) }
                finally { latch.countDown() }
            }, "hook-${entry.type}").apply { isDaemon = true }.start()
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
