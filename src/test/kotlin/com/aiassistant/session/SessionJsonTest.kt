package com.aiassistant.session

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.fail

class SessionJsonTest {

    @Test
    fun `serializes and reads Instant fields`() {
        val dto = SessionDTO(
            id = "session-1",
            title = "测试会话",
            createdAt = Instant.parse("2026-06-25T08:00:00Z"),
            updatedAt = Instant.parse("2026-06-25T08:01:00Z"),
            messages = listOf(
                MessageDTO(
                    id = "message-1",
                    role = "USER",
                    content = "你好",
                    timestamp = Instant.parse("2026-06-25T08:00:30Z")
                )
            )
        )

        val json = try {
            SessionJson.gson.toJson(dto)
        } catch (throwable: Throwable) {
            fail("Session JSON 不应因 Instant 反射访问失败: ${throwable.message}")
        }

        assertContains(json, "2026-06-25T08:00:00Z")
        val restored = SessionJson.gson.fromJson(json, SessionDTO::class.java)
        assertEquals(dto.createdAt, restored.createdAt)
        assertEquals(dto.messages[0].timestamp, restored.messages[0].timestamp)
    }
}
