package com.aiassistant

import com.aiassistant.agent_v3.AgentMessage
import com.aiassistant.shared.JsonUtils
import java.io.File

/**
 * 对话持久化 — 自动保存/加载到 .code-assistant/conversations/current.json。
 */
object ConversationStore {

    fun save(projectBasePath: String, messages: List<AgentMessage>) {
        try {
            val dir = File(projectBasePath, ".code-assistant/conversations")
            dir.mkdirs()
            val file = File(dir, "current.json")
            val json = messages.joinToString(",") { msg ->
                """{"role":"${msg.role}","content":"${JsonUtils.escapeJson(msg.content)}"}"""
            }.let { "[$it]" }
            file.writeText(json)
        } catch (_: Exception) {}
    }

    fun load(projectBasePath: String): List<AgentMessage> {
        return try {
            val file = File(projectBasePath, ".code-assistant/conversations/current.json")
            if (!file.exists()) return emptyList()
            val json = file.readText()
            parseMessages(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(projectBasePath: String) {
        try {
            File(projectBasePath, ".code-assistant/conversations/current.json").delete()
        } catch (_: Exception) {}
    }

    private fun parseMessages(json: String): List<AgentMessage> {
        val messages = mutableListOf<AgentMessage>()
        val entryPattern = Regex("""\{"role":"([^"]*)","content":"((?:[^"\\]|\\.)*)"\}""")
        for (match in entryPattern.findAll(json)) {
            val role = match.groupValues[1]
            val content = JsonUtils.unescapeJson(match.groupValues[2])
            messages.add(AgentMessage(role, content))
        }
        return messages
    }
}
