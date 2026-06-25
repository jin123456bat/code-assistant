package com.aiassistant.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.*
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue

/**
 * Phase 0 Pre-Spike: Anthropic Java SDK 2.43.0 + DeepSeek /anthropic 兼容性验证。
 */
class SdkCompatibilitySpikeTest {

    private val model = "deepseek-v4-pro"
    private lateinit var client: AnthropicClient

    @Before
    fun setUp() {
        assumeTrue(
            "未设置 DEEPSEEK_API_KEY，跳过在线 DeepSeek 兼容性 spike",
            System.getenv("DEEPSEEK_API_KEY") != null
        )
        client = AnthropicOkHttpClient.builder()
            .baseUrl("https://api.deepseek.com/anthropic")
            .apiKey(System.getenv("DEEPSEEK_API_KEY"))
            .build()
    }

    // === 验证①: 基础连接 ===

    @Test
    fun `test basic connection`() {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(100)
            .addUserMessage("用中文回复：你好")
            .build()

        val response = client.beta().messages().create(params)
        val text = response.content().filter { it.text().isPresent }
            .joinToString("") { it.text().get().text() }

        println("stopReason: ${response.stopReason()}")
        // basic connection: 只要能收到回复就算通过（不限 stop reason 格式）
        val hasContent = response.content().isNotEmpty()
        assertTrue("① 失败: 响应内容为空", hasContent)
        println("回复: $text")
        println("✅ 验证① 通过: 基础连接正常 (stopReason=${response.stopReason()})")
    }

    // === 验证②: Tool Use 触发 ===

    @Test
    fun `test tool use triggered`() {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024)
            .addTool(ReadFileSpike::class.java)
            .addUserMessage("请读取 Config.kt 文件的内容")
            .build()

        val response = client.beta().messages().create(params)

        println("stopReason: ${response.stopReason()}")
        response.content().forEach { block ->
            block.text().ifPresent { println("  文本: ${it.text().take(200)}") }
            block.toolUse().ifPresent { use ->
                println("  🔧 toolUse: name=${use.name()}  input=${use._input()}")
            }
        }

        val hasToolUse = response.content().any { it.toolUse().isPresent }
        assertTrue(
            "② 失败: 无 tool_use。DeepSeek 可能不支持此 SDK 版本的 tool calling 格式",
            hasToolUse
        )
        println("✅ 验证② 通过: Tool use 正常触发")
    }

    // === 验证③: Tool Result 回传 + 继续对话 ===

    @Test
    fun `test tool result feedback loop`() {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024)
            .addTool(ReadFileSpike::class.java)
            .addUserMessage("请读取 Config.kt，然后告诉我文件里有什么")

        val resp1 = client.beta().messages().create(builder.build())
        val toolUses = resp1.content().filter { it.toolUse().isPresent }.map { it.toolUse().get() }

        assertFalse("③-1 失败: 第一轮无 tool_use", toolUses.isEmpty())
        println("第 1 轮 tool: ${toolUses[0].name()}")

        // 模拟 tool 执行 + 回传结果
        val tool = toolUses[0]
        builder
            // DeepSeek thinking mode requires replaying the full assistant turn,
            // including thinking/signature blocks, before sending tool_result.
            .addMessage(resp1)
            .addUserMessageOfBetaContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofToolResult(
                        BetaToolResultBlockParam.builder()
                            .toolUseId(tool.id())
                            .contentAsJson(ReadFileSpike().execute()).build()
                    )
                )
            )

        val resp2 = client.beta().messages().create(builder.build())
        val text = resp2.content().filter { it.text().isPresent }
            .joinToString("") { it.text().get().text() }

        // 第 2 轮: LLM 可能输出文本，也可能继续调用工具(正常 Agent 行为)
        val hasResponse = resp2.content().any {
            it.text().isPresent || it.toolUse().isPresent
        }
        assertTrue(
            "③-2 失败: 回传 tool_result 后无任何响应。stopReason=${resp2.stopReason()}",
            hasResponse
        )
        println("第 2 轮 stopReason: ${resp2.stopReason()}")
        if (text.isNotEmpty()) println("第 2 轮文本: $text")
        resp2.content().filter { it.toolUse().isPresent }.forEach {
            println("第 2 轮 tool_use: ${it.toolUse().get().name()}")
        }
        println("✅ 验证③ 通过: Tool result 回传后 LLM 正常响应")
    }
}

// --- Tool 定义（@JsonClassDescription 由 SDK 自动生成 JSON Schema）---

@JsonClassDescription("读取项目内指定文件的内容")
class ReadFileSpike {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""

    @JsonPropertyDescription("起始行号，1-based，可选")
    var startLine: Int? = null

    @JsonPropertyDescription("结束行号，1-based，可选")
    var endLine: Int? = null

    fun execute(): String = """
文件: Config.kt (42 行)

const val APP_NAME = "CodeAssistant"
const val VERSION = "2.0.0"

data class AppConfig(
    val maxTokens: Int = 2048,
    val model: String = "deepseek-v4-pro"
)
文件结束: Config.kt
    """.trimIndent()
}
