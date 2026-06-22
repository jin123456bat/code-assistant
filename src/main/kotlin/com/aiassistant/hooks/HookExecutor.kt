package com.aiassistant.hooks

import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HookExecutor(private val mcpManager: com.aiassistant.mcp.McpManager?) {

    private val gson = Gson()

    fun execute(entries: List<HookEntry>, context: HookEventContext): List<HookDecision> {
        if (entries.isEmpty()) return emptyList()
        val decisions = java.util.Collections.synchronizedList(mutableListOf<HookDecision>())
        val latch = CountDownLatch(entries.size)
        val threads = mutableListOf<Thread>()

        for (entry in entries) {
            threads.add(Thread({
                try { executeOne(entry, context)?.let { decisions.add(it) } }
                catch (_: Exception) { }
                finally { latch.countDown() }
            }, "hook-${entry.type}").apply { isDaemon = true; start() })
        }

        val allDone = latch.await(entries.maxOfOrNull { it.timeout }?.toLong() ?: 60, TimeUnit.SECONDS)
        if (!allDone) {
            threads.forEach { it.interrupt() }
        }
        return decisions.toList()
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
