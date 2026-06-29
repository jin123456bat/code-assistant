package com.aiassistant.session

import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.ContentType
import com.aiassistant.agent.Message
import com.aiassistant.agent.Role
import com.aiassistant.agent.ToolCallRecord
import com.aiassistant.agent.ToolCallState
import com.aiassistant.agent.TokenDelta
import com.aiassistant.agent.TokenUsage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

// ponytail: JSON file-based session persistence, atomic write via tmp+rename + FileChannel.tryLock()

internal object SessionJson {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(
            Instant::class.java,
            JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) }
        )
        .registerTypeAdapter(
            Instant::class.java,
            JsonDeserializer { json, _, _ -> Instant.parse(json.asString) }
        )
        .create()

    val prettyGson: Gson = GsonBuilder()
        .registerTypeAdapter(
            Instant::class.java,
            JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) }
        )
        .registerTypeAdapter(
            Instant::class.java,
            JsonDeserializer { json, _, _ -> Instant.parse(json.asString) }
        )
        .setPrettyPrinting()
        .create()
}

/**
 * 映射旧版 PlanStep 状态值到新版枚举（向后兼容）。
 * 旧值: PENDING→PAUSED, DONE→COMPLETED, ERROR→CANCELLED, SKIPPED→CANCELLED
 */
private fun mapLegacyStepStatus(status: String): com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus {
    return when (status.uppercase()) {
        "PENDING" -> com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.PAUSED
        "DONE" -> com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.COMPLETED
        "ERROR", "SKIPPED" -> com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.CANCELLED
        else -> com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.valueOf(status)
    }
}

class SessionStore(private val project: Project) {

    private val gson = SessionJson.gson
    private val dir: File
        get() {
            val d = File(project.basePath!!, ".code-assistant/sessions")
            d.mkdirs()
            return d
        }

    private val indexFile: File get() = File(dir, "index.json")

    fun save(session: AgentSession) {
        val planDto = session.plan?.let { plan ->
            PlanDTO(
                id = plan.id,
                summary = plan.summary,
                status = plan.status.name,
                currentPlanIndex = plan.currentPlanIndex,
                createdAt = plan.createdAt,
                updatedAt = plan.updatedAt,
                plans = plan.plans.map { item ->
                    PlanItemDTO(
                        id = item.id, description = item.description,
                        tool = item.tool, files = item.files,
                        status = item.status.name, result = item.result,
                        retryCount = item.retryCount
                    )
                }
            )
        }
        val dto = SessionDTO(
            id = session.id, title = session.title,
            createdAt = session.createdAt, updatedAt = session.updatedAt,
            parentId = session.parentId,
            compactSummary = session.compactSummary,
            compactCount = session.compactCount,
            state = session.state.name,
            totalTokens = TotalTokensDTO(
                inputTokens = session.totalTokens.inputTokens,
                outputTokens = session.totalTokens.outputTokens
            ),
            approvedTools = session.approvedTools.toList(),
            errorCount = session.errorCount,
            calledSkills = session.calledSkills.toList(),
            firstToolUseDone = session.firstToolUseDone.toList(),
            plan = planDto,
            messages = session.messages.map { msg ->
                MessageDTO(
                    id = msg.id,
                    role = msg.role.name,
                    content = msg.content,
                    contentType = msg.contentType?.name,
                    timestamp = msg.timestamp,
                    deleted = msg.deleted,
                    toolCalls = msg.toolCalls?.map { tc ->
                        ToolCallDTO(
                            id = tc.id, name = tc.name,
                            parameters = tc.parameters,
                            state = tc.state.name,
                            result = tc.result, durationMs = tc.durationMs
                        )
                    },
                    tokenUsage = msg.tokenUsage?.let {
                        TokenUsageDTO(inputTokens = it.inputTokens, outputTokens = it.outputTokens)
                    },
                    feedback = msg.feedback
                )
            }
        )

        val sessionFile = File(dir, "${session.id}.json")
        val lock = acquireLock("${session.id}.json")
        try {
            val tmp = File(dir, "${session.id}.json.tmp")
            tmp.writeText(gson.toJson(dto))
            Files.move(
                tmp.toPath(),
                sessionFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            updateIndex(dto)
        } finally {
            lock?.release()
        }
    }

    fun load(id: String): AgentSession? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null

        return try {
            val dto = gson.fromJson(file.readText(), SessionDTO::class.java)
            val session = AgentSession(dto.id, dto.title)
            session.parentId = dto.parentId
            session.compactSummary = dto.compactSummary
            session.compactCount = dto.compactCount
            session.totalTokens = dto.totalTokens?.let {
                TokenUsage(inputTokens = it.inputTokens, outputTokens = it.outputTokens)
            } ?: TokenUsage()
            session.approvedTools.addAll(dto.approvedTools)
            session.errorCount = dto.errorCount
            session.calledSkills.addAll(dto.calledSkills)
            session.firstToolUseDone.addAll(dto.firstToolUseDone)
            session.plan = dto.plan?.let { plan ->
                com.aiassistant.agent.PlanExecutor.Plan(
                    id = plan.id,
                    summary = plan.summary,
                    status = com.aiassistant.agent.PlanExecutor.Plan.Status.valueOf(plan.status),
                    currentPlanIndex = plan.currentPlanIndex,
                    createdAt = plan.createdAt,
                    updatedAt = plan.updatedAt,
                    plans = plan.plans.map { step ->
                        com.aiassistant.agent.PlanExecutor.PlanItem(
                            id = step.id,
                            description = step.description,
                            tool = step.tool,
                            files = step.files,
                            status = mapLegacyStepStatus(step.status),
                            result = step.result,
                            retryCount = step.retryCount
                        )
                    }.toMutableList()
                )
            }
            dto.messages.forEach { msg ->
                session.addMessage(
                    Message(
                        id = msg.id,
                        role = try {
                            Role.valueOf(msg.role)
                        } catch (_: Exception) {
                            Role.SYSTEM
                        },
                        content = msg.content,
                        contentType = msg.contentType?.let {
                            try {
                                ContentType.valueOf(it)
                            } catch (_: Exception) {
                                when (msg.role) {
                                    "TOOL_CALL" -> ContentType.TOOL_USE
                                    "TOOL_RESULT" -> ContentType.TOOL_RESULT
                                    else -> null
                                }
                            }
                        },
                        timestamp = msg.timestamp,
                        deleted = msg.deleted,
                        toolCalls = msg.toolCalls?.map { tc ->
                            ToolCallRecord(
                                id = tc.id,
                                name = tc.name,
                                parameters = tc.parameters,
                                state = ToolCallState.valueOf(tc.state),
                                result = tc.result,
                                durationMs = tc.durationMs
                            )
                        },
                        tokenUsage = msg.tokenUsage?.let {
                            TokenDelta(it.inputTokens, it.outputTokens)
                        },
                        feedback = msg.feedback
                    )
                )
            }
            session
        } catch (e: Exception) {
            null // ponytail: corrupted file -> skip
        }
    }

    /**
     * 更新指定 session 的标题（Session JSON + index.json 双重更新）。
     * 对齐 docs/agent/session.md §三：标题生成后需持久化到两处。
     */
    fun updateTitle(sessionId: String, title: String) {
        // 1) 更新 Session JSON 文件中的 title
        val sessionFile = File(dir, "$sessionId.json")
        if (!sessionFile.exists()) return
        val lock = acquireLock("$sessionId.json")
        try {
            val dto = runCatching {
                gson.fromJson(
                    sessionFile.readText(),
                    SessionDTO::class.java
                )
            }.getOrNull()
            if (dto != null) {
                val updatedDto = dto.copy(title = title)
                val tmp = File(dir, "$sessionId.json.tmp")
                tmp.writeText(gson.toJson(updatedDto))
                Files.move(
                    tmp.toPath(),
                    sessionFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                // 2) 更新 index.json 中的 title
                updateIndexTitle(sessionId, title)
            }
        } finally {
            lock?.release()
        }
    }

    private fun updateIndexTitle(sessionId: String, title: String) {
        val lock = acquireLock("index.json")
        try {
            val list = readIndex().toMutableList()
            val idx = list.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(title = title)
                indexFile.writeText(gson.toJson(list))
            }
        } finally {
            lock?.release()
        }
    }

    fun delete(id: String) {
        val lock = acquireLock("${id}.json")
        try {
            File(dir, "$id.json").delete()
            removeFromIndex(id)
        } finally {
            lock?.release()
        }
    }

    fun deleteAll(sessionIds: List<String>) {
        val idSet = sessionIds.toSet()
        for (id in sessionIds) {
            val lock = acquireLock("${id}.json")
            try {
                File(dir, "$id.json").delete()
            } finally {
                lock?.release()
            }
        }
        val list = readIndex().filter { it.id !in idSet }
        writeIndexWithLock(list)
    }

    /** 清空所有会话（对齐 docs/ui/pages.md §四 [🗑 清空] 按钮） */
    fun clear() {
        deleteAll(listAll().map { it.id })
    }

    /**
     * 读取 index.json 并返回所有 SessionIndex。
     * 对每个在 index 中有条目但 JSON 文件损坏的会话，标记 corrupted=true。
     * 对齐 docs/agent/session.md §一 "损坏文件用户感知"。
     */
    fun listAll(): List<SessionIndex> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<SessionIndexDTO>>() {}.type
            val list: List<SessionIndexDTO> = gson.fromJson(indexFile.readText(), type)
            list.map { dto ->
                // 检查对应 JSON 文件是否存在且可解析
                val corrupted = checkCorrupted(dto.id)
                SessionIndex(
                    dto.id,
                    dto.title,
                    dto.createdAt,
                    dto.updatedAt,
                    dto.messageCount,
                    dto.totalTokens,
                    dto.toolCallCount,
                    dto.hasActivePlan,
                    dto.parentId,
                    dto.parentTotalTokens,
                    corrupted = corrupted
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 检测指定 id 的 Session JSON 文件是否损坏（文件存在但 JSON 解析失败）。
     * @return true 表示文件损坏，false 表示文件不存在或解析成功
     */
    private fun checkCorrupted(id: String): Boolean {
        val file = File(dir, "$id.json")
        if (!file.exists()) return false
        return runCatching { gson.fromJson(file.readText(), SessionDTO::class.java) }.isFailure
    }

    fun exportJson(ids: Collection<String>): String {
        val selectedIds = ids.toSet()
        val sessions = readIndex()
            .filter { it.id in selectedIds }
            .mapNotNull { entry ->
                val file = File(dir, "${entry.id}.json")
                if (!file.exists()) return@mapNotNull null
                runCatching { gson.fromJson(file.readText(), SessionDTO::class.java) }.getOrNull()
            }

        return SessionJson.prettyGson.toJson(
            SessionExportDTO(
                exportedAt = Instant.now(),
                sessionCount = sessions.size,
                sessions = sessions
            )
        )
    }

    /**
     * 获取 OS 级排他锁（跨 JVM 有效），对齐 docs/agent/session.md 一。
     * 使用 FileChannel.tryLock()，获取失败等 100ms 重试最多 3 次。
     */
    fun acquireLock(fileName: String): FileLock? {
        val lockFile = File(dir, "$fileName.lock")
        for (attempt in 1..3) {
            try {
                val raf = RandomAccessFile(lockFile, "rw")
                val channel = raf.channel
                val lock = channel.tryLock()
                if (lock != null) return lock
            } catch (_: Exception) {
                // 获取锁失败，继续重试
            }
            if (attempt < 3) {
                Thread.sleep(100)
            }
        }
        return null
    }

    fun releaseLock(lock: FileLock) {
        try {
            lock.release()
            lock.channel().close()
        } catch (_: Exception) {
            // 锁释放失败，忽略
        }
    }

    /**
     * 带锁保护的 index.json 原子写入：.tmp -> ATOMIC_MOVE + FileLock，
     * 对齐 docs/agent/session.md 一 "Session Index 同样保护"。
     */
    private fun updateIndex(dto: SessionDTO) {
        val list = readIndex().toMutableList()
        val existing = list.indexOfFirst { it.id == dto.id }
        val entry = SessionIndexDTO(
            id = dto.id, title = dto.title,
            createdAt = dto.createdAt, updatedAt = dto.updatedAt,
            messageCount = dto.messages.size,
            totalTokens = dto.messages.sumOf {
                (it.tokenUsage?.inputTokens ?: 0L) + (it.tokenUsage?.outputTokens ?: 0L)
            },
            toolCallCount = dto.messages.sumOf { it.toolCalls?.size ?: 0 },
            hasActivePlan = dto.plan != null && dto.plan.status != "COMPLETED" && dto.plan.status != "CANCELLED",
            parentId = dto.parentId,
            parentTotalTokens = dto.parentTotalTokens
        )
        if (existing >= 0) list[existing] = entry else list.add(entry)
        writeIndexWithLock(list)
    }

    private fun removeFromIndex(id: String) {
        val list = readIndex().filter { it.id != id }
        writeIndexWithLock(list)
    }

    private fun writeIndexWithLock(list: List<SessionIndexDTO>) {
        val lock = acquireLock("index.json")
        try {
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(gson.toJson(list))
            Files.move(
                tmp.toPath(),
                indexFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            lock?.release()
        }
    }

    private fun readIndex(): List<SessionIndexDTO> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<SessionIndexDTO>>() {}.type
            gson.fromJson(indexFile.readText(), type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
