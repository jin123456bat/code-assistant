package com.aiassistant.session

import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.Message
import com.aiassistant.agent.Role
import com.aiassistant.agent.ToolCallRecord
import com.aiassistant.agent.ToolCallState
import com.aiassistant.agent.TokenDelta
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

// ponytail: JSON file-based session persistence, atomic write via tmp+rename

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
                summary = plan.summary,
                status = plan.status.name,
                currentStep = plan.currentStepIndex,
                steps = plan.steps.map { step ->
                    PlanStepDTO(
                        id = step.id, description = step.description,
                        tool = step.tool, files = step.files,
                        status = step.status.name, result = step.result,
                        fileStamps = step.fileStamps, retryCount = step.retryCount
                    )
                }
            )
        }
        val dto = SessionDTO(
            id = session.id, title = session.title,
            createdAt = session.createdAt, updatedAt = session.updatedAt,
            plan = planDto,
            messages = session.messages.map { msg ->
                MessageDTO(
                    id = msg.id,
                    role = msg.role.name,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    toolCalls = msg.toolCalls?.map { tc ->
                        ToolCallDTO(
                            id = tc.id, name = tc.name,
                            parameters = tc.parameters,
                            state = tc.state.name,
                            result = tc.result, durationMs = tc.durationMs
                        )
                    },
                    inputTokens = msg.tokenUsage?.inputTokens,
                    outputTokens = msg.tokenUsage?.outputTokens
                )
            }
        )

        val sessionFile = File(dir, "${session.id}.json")
        val tmp = File(dir, "${session.id}.json.tmp")
        tmp.writeText(gson.toJson(dto))
        Files.move(
            tmp.toPath(),
            sessionFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        updateIndex(dto)
    }

    fun load(id: String): AgentSession? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null

        return try {
            val dto = gson.fromJson(file.readText(), SessionDTO::class.java)
            val session = AgentSession(dto.id, dto.title)
            session.plan = dto.plan?.let { plan ->
                com.aiassistant.agent.PlanExecutor.Plan(
                    summary = plan.summary,
                    status = com.aiassistant.agent.PlanExecutor.Plan.Status.valueOf(plan.status),
                    currentStepIndex = plan.currentStep,
                    steps = plan.steps.map { step ->
                        com.aiassistant.agent.PlanExecutor.PlanStep(
                            id = step.id,
                            description = step.description,
                            tool = step.tool,
                            files = step.files,
                            status = com.aiassistant.agent.PlanExecutor.PlanStep.StepStatus.valueOf(
                                step.status
                            ),
                            result = step.result,
                            fileStamps = step.fileStamps.toMutableMap(),
                            retryCount = step.retryCount
                        )
                    }
                )
            }
            dto.messages.forEach { msg ->
                session.addMessage(
                    Message(
                        id = msg.id,
                        role = Role.valueOf(msg.role),
                        content = msg.content,
                        timestamp = msg.timestamp,
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
                        tokenUsage = if (msg.inputTokens != null || msg.outputTokens != null)
                            TokenDelta(msg.inputTokens ?: 0, msg.outputTokens ?: 0) else null
                    )
                )
            }
            session
        } catch (e: Exception) {
            null // ponytail: corrupted file → skip
        }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        removeFromIndex(id)
    }

    fun listAll(): List<SessionIndex> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<SessionIndexDTO>>() {}.type
            val list: List<SessionIndexDTO> = gson.fromJson(indexFile.readText(), type)
            list.map {
                SessionIndex(
                    it.id,
                    it.title,
                    it.createdAt,
                    it.updatedAt,
                    it.messageCount,
                    it.totalTokens,
                    it.toolCallCount,
                    it.hasActivePlan
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
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

    private fun updateIndex(dto: SessionDTO) {
        val list = readIndex().toMutableList()
        val existing = list.indexOfFirst { it.id == dto.id }
        val entry = SessionIndexDTO(
            id = dto.id, title = dto.title,
            createdAt = dto.createdAt, updatedAt = dto.updatedAt,
            messageCount = dto.messages.size,
            totalTokens = dto.messages.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) },
            toolCallCount = dto.messages.sumOf { it.toolCalls?.size ?: 0 },
            hasActivePlan = dto.plan != null && dto.plan.status != "COMPLETED" && dto.plan.status != "CANCELLED"
        )
        if (existing >= 0) list[existing] = entry else list.add(entry)
        indexFile.writeText(gson.toJson(list))
    }

    private fun removeFromIndex(id: String) {
        val list = readIndex().filter { it.id != id }
        indexFile.writeText(gson.toJson(list))
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

data class SessionIndex(
    val id: String, val title: String, val createdAt: Instant, val updatedAt: Instant,
    val messageCount: Int, val totalTokens: Long,
    val toolCallCount: Int = 0, val hasActivePlan: Boolean = false
)

// Internal DTOs for JSON serialization
data class SessionDTO(
    val id: String, val title: String, val createdAt: Instant, val updatedAt: Instant,
    val messages: List<MessageDTO>,
    val plan: PlanDTO? = null
)

data class SessionExportDTO(
    val exportedAt: Instant,
    val sessionCount: Int,
    val sessions: List<SessionDTO>
)

data class PlanDTO(
    val summary: String, val status: String, val currentStep: Int,
    val steps: List<PlanStepDTO>
)

data class PlanStepDTO(
    val id: String, val description: String, val tool: String,
    val files: List<String>, val status: String, val result: String?,
    val fileStamps: Map<String, Long> = emptyMap(), val retryCount: Int = 0
)

data class MessageDTO(
    val id: String, val role: String, val content: String, val timestamp: Instant,
    val toolCalls: List<ToolCallDTO>? = null,
    val inputTokens: Long? = null, val outputTokens: Long? = null
)

data class ToolCallDTO(
    val id: String, val name: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val state: String,
    val result: String? = null, val durationMs: Long? = null
)

data class SessionIndexDTO(
    val id: String, val title: String,
    val createdAt: Instant, val updatedAt: Instant,
    val messageCount: Int, val totalTokens: Long,
    val toolCallCount: Int = 0, val hasActivePlan: Boolean = false
)
