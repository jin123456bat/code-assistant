package com.aiassistant.session

import com.aiassistant.agent.AgentMessage
import com.google.gson.Gson
import java.io.File
import java.time.Instant

object SessionStore {

    private val gson = Gson()

    data class SessionMeta(val id: String, val name: String, val createdAt: Long,
                           val updatedAt: Long, val messageCount: Int)
    data class SessionData(val meta: SessionMeta, val messages: List<MessageDTO>,
                           val tokenStats: TokenStatsDTO? = null)
    data class MessageDTO(val role: String, val content: String, val toolName: String? = null,
                          val timestamp: Long = System.currentTimeMillis())
    data class TokenStatsDTO(val totalInput: Long, val totalOutput: Long, val roundCount: Int,
                             val perRound: List<RoundTokenDTO>)
    data class RoundTokenDTO(val inputTokens: Int, val outputTokens: Int, val timestamp: Long)

    private fun dir(projectBasePath: String): File =
        File(projectBasePath, ".code-assistant/sessions").also { it.mkdirs() }

    fun list(projectBasePath: String): List<SessionMeta> {
        val d = dir(projectBasePath)
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> try { gson.fromJson(f.readText(), SessionData::class.java).meta }
                catch (_: Exception) { null } }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    fun save(projectBasePath: String, id: String, name: String, messages: List<AgentMessage>,
             tokenStats: TokenStatsDTO? = null) {
        val dto = SessionData(
            meta = SessionMeta(id, name, Instant.now().toEpochMilli(), Instant.now().toEpochMilli(), messages.size),
            messages = messages.map { MessageDTO(it.role, it.content.take(5000), it.toolName) },
            tokenStats = tokenStats
        )
        val target = File(dir(projectBasePath), "$id.json")
        val tmp = File(target.path + ".tmp")
        tmp.writeText(gson.toJson(dto))
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun load(projectBasePath: String, id: String): SessionData? {
        val f = File(dir(projectBasePath), "$id.json")
        if (!f.exists()) return null
        return try { gson.fromJson(f.readText(), SessionData::class.java) } catch (_: Exception) { null }
    }

    fun delete(projectBasePath: String, id: String) { File(dir(projectBasePath), "$id.json").delete() }
}
