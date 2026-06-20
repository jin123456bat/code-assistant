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
                          val toolCallId: String? = null, val toolCalls: List<ToolCallDTO>? = null,
                          val timestamp: Long = System.currentTimeMillis(),
                          val id: Long = 0, val version: Int = 0,
                          val inputTokens: Int = 0, val outputTokens: Int = 0,
                          val images: List<ImageDTO>? = null)
    data class ToolCallDTO(val id: String, val name: String, val arguments: String)
    data class ImageDTO(val mediaType: String, val data: String)
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

    @Synchronized
    fun save(projectBasePath: String, id: String, name: String, messages: List<AgentMessage>,
             tokenStats: TokenStatsDTO? = null) {
        val target = File(dir(projectBasePath), "$id.json")
        // 保留原始创建时间：若已有会话文件则沿用其 createdAt，避免每次保存覆盖
        val existingCreatedAt = try {
            if (target.exists()) gson.fromJson(target.readText(), SessionData::class.java).meta.createdAt
            else Instant.now().toEpochMilli()
        } catch (_: Exception) { Instant.now().toEpochMilli() }

        val dto = SessionData(
            meta = SessionMeta(id, name, existingCreatedAt, Instant.now().toEpochMilli(), messages.size),
            messages = messages.map { msg ->
                MessageDTO(
                    role = msg.role,
                    content = msg.content.take(5000),
                    toolName = msg.toolName,
                    toolCallId = msg.toolCallId,
                    toolCalls = msg.toolCalls?.map { ToolCallDTO(it.id, it.name, it.arguments) },
                    id = msg.id,
                    version = msg.version,
                    inputTokens = msg.inputTokens,
                    outputTokens = msg.outputTokens,
                    images = msg.images?.map { ImageDTO(it.mediaType, it.data) }
                )
            },
            tokenStats = tokenStats
        )
        val json = gson.toJson(dto)
        val tmp = File(target.path + ".tmp")
        val bak = File(target.path + ".bak")

        try {
            // 步骤 1：写入临时文件并确保落盘
            tmp.writeText(json)
            // 步骤 2：原子移动（大多数现代文件系统支持）
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                // 确保 bak 文件最终被清理
                try { bak.delete() } catch (_: Exception) {}
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // 降级路径：先备份旧文件，再非原子替换。若替换中途崩溃，可从 .bak 恢复。
                try {
                    if (target.exists()) {
                        java.nio.file.Files.move(
                            target.toPath(), bak.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                    java.nio.file.Files.move(
                        tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    // 替换成功，清理备份
                    try { bak.delete() } catch (_: Exception) {}
                } catch (e: Exception) {
                    // 非原子 move 失败：尝试回滚 target（从 bak 恢复），并清理 tmp
                    if (bak.exists() && !target.exists()) {
                        try {
                            java.nio.file.Files.move(bak.toPath(), target.toPath())
                        } catch (_: Exception) {
                            // 回滚失败时保留 bak 作为最后副本，不 delete
                        }
                    }
                    // 只有回滚成功（target 已恢复）时才删除 bak
                    if (target.exists()) bak.delete()
                    if (!tmp.delete()) tmp.deleteOnExit()
                    throw e
                }
            }
        } catch (e: Exception) {
            // 写入完全失败：清理临时文件并记录日志
            if (!tmp.delete()) tmp.deleteOnExit()
            com.aiassistant.AppLogger.warn("SessionStore: 会话保存失败 id=$id: ${e.message}")
        }
    }

    fun load(projectBasePath: String, id: String): SessionData? {
        val f = File(dir(projectBasePath), "$id.json")
        if (!f.exists()) return null
        return try { gson.fromJson(f.readText(), SessionData::class.java) } catch (_: Exception) { null }
    }

    fun delete(projectBasePath: String, id: String) { File(dir(projectBasePath), "$id.json").delete() }
}
