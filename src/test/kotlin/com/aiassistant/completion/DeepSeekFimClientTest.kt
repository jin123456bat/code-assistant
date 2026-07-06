package com.aiassistant.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DeepSeekFimClient 单元测试。
 * 聚焦于 Request/Response 数据类和 FimApiException，不测试实际 HTTP 调用。
 */
class DeepSeekFimClientTest {

    // ═══ FimApiException ═══

    @Test
    fun `FimApiException 携带 statusCode 和 message`() {
        val ex = FimApiException(401, "Unauthorized")
        assertEquals(401, ex.statusCode)
        assertTrue(ex.message!!.contains("Unauthorized"))
    }

    @Test
    fun `FimApiException 继承 IOException`() {
        val ex: java.io.IOException = FimApiException(500, "Server Error")
        assertEquals(500, (ex as FimApiException).statusCode)
    }

    // ═══ FimRequest ═══

    @Test
    fun `FimRequest 默认值正确`() {
        val req = DeepSeekFimClient.FimRequest(
            model = "deepseek-chat",
            prompt = "fun main()",
            suffix = "}",
            maxTokens = 64
        )
        assertEquals("deepseek-chat", req.model)
        assertEquals("fun main()", req.prompt)
        assertEquals("}", req.suffix)
        assertEquals(64, req.maxTokens)
        assertEquals(0.0, req.temperature)
        assertNull(req.stop)
    }

    @Test
    fun `FimRequest 自定义 stop 和 temperature`() {
        val req = DeepSeekFimClient.FimRequest(
            model = "deepseek-v3",
            prompt = "class Foo {",
            suffix = "}",
            maxTokens = 128,
            temperature = 0.5,
            stop = listOf("\n\n", "# End")
        )
        assertEquals(0.5, req.temperature)
        assertEquals(2, req.stop!!.size)
        assertTrue(req.stop!!.contains("\n\n"))
    }

    // ═══ FimChoice ═══

    @Test
    fun `FimChoice 字段正确`() {
        val choice = DeepSeekFimClient.FimChoice(
            text = "val x = 42\nreturn x",
            index = 0,
            finishReason = "stop"
        )
        assertEquals("val x = 42\nreturn x", choice.text)
        assertEquals(0, choice.index)
        assertEquals("stop", choice.finishReason)
    }

    @Test
    fun `FimChoice finishReason 可为 null`() {
        val choice = DeepSeekFimClient.FimChoice(
            text = "partial response",
            index = 0,
            finishReason = null
        )
        assertNull(choice.finishReason)
    }

    // ═══ FimUsage ═══

    @Test
    fun `FimUsage 统计字段正确`() {
        val usage = DeepSeekFimClient.FimUsage(
            promptTokens = 10,
            completionTokens = 20,
            totalTokens = 30
        )
        assertEquals(10, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(30, usage.totalTokens)
    }

    // ═══ FimResponse ═══

    @Test
    fun `FimResponse 构建完整`() {
        val response = DeepSeekFimClient.FimResponse(
            id = "resp-001",
            `object` = "chat.completion",
            choices = listOf(
                DeepSeekFimClient.FimChoice(
                    text = "val result = a + b",
                    index = 0,
                    finishReason = "stop"
                )
            ),
            usage = DeepSeekFimClient.FimUsage(5, 10, 15)
        )
        assertEquals("resp-001", response.id)
        assertEquals("chat.completion", response.`object`)
        assertEquals(1, response.choices!!.size)
        assertEquals(5, response.usage!!.promptTokens)
        assertEquals(15, response.usage!!.totalTokens)
    }

    @Test
    fun `FimResponse 各字段可为 null`() {
        val response = DeepSeekFimClient.FimResponse(
            id = null,
            `object` = null,
            choices = null,
            usage = null
        )
        assertNull(response.id)
        assertNull(response.choices)
        assertNull(response.usage)
    }

    // DeepSeekFimClient 构造依赖 IntelliJ Platform 运行时（AppSettingsService.getInstance()），
    // 不在纯单元测试中测试实例化。HTTP 调用逻辑由集成测试覆盖。

    @Test
    fun `FimApiException statusCode 范围涵盖常见 HTTP 错误`() {
        val badRequest = FimApiException(400, "Bad Request")
        val unauthorized = FimApiException(401, "Unauthorized")
        val serverError = FimApiException(500, "Internal Server Error")
        assertEquals(400, badRequest.statusCode)
        assertEquals(401, unauthorized.statusCode)
        assertEquals(500, serverError.statusCode)
    }
}
