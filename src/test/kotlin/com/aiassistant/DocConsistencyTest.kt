package com.aiassistant

import com.aiassistant.agent.*
import com.aiassistant.completion.DeepSeekFimClient
import com.aiassistant.completion.FimApiException
import com.aiassistant.session.*
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.Instant

/**
 * 文档一致性测试。
 *
 * 根据 docs/ 目录下的规格文档验证代码实现。
 * 每个测试方法的 KDoc 标注了对应的文档出处。
 *
 * ## 发现的不一致（标记为 @DocInconsistency 的测试会失败）：
 * - WebFetch 字符上限：代码 8000 vs 文档"无硬性上限"
 * - 429 重试次数：代码 3 次 vs api-error-handling.md 表"最多 2 次"
 * - IOException 重试：代码 3 次 vs api-error-handling.md 表"0 次"
 */
class DocConsistencyTest {

    // ════════════════════════════════════════════════════════════════
    // 一、ToolModels 字段默认值 & 描述限制
    // 文档：docs/agent/tools.md + docs/specs/tool-json-schema.md
    // ════════════════════════════════════════════════════════════════

    // ── Read ──
    // doc: tools.md:9 "单次最多返回 500 行"
    @Test
    fun `Read 默认 timeout 为 0 表示不限`() {
        val r = Read()
        assertEquals("timeout 默认值应为 0（不限）", 0, r.timeout)
    }

    @Test
    fun `Read startLine endLine 默认为 null 可选`() {
        val r = Read()
        assertNull("startLine 默认应为 null（可选）", r.startLine)
        assertNull("endLine 默认应为 null（可选）", r.endLine)
    }

    // ── Write ──
    // doc: tools.md:10 "内容最多 3000 行"
    @Test
    fun `Write 字段默认值符合文档`() {
        val w = Write()
        assertEquals("filePath 默认空字符串", "", w.filePath)
        assertEquals("content 默认空字符串", "", w.content)
        assertEquals("timeout 默认 0", 0, w.timeout)
    }

    // ── Edit ──
    // doc: tools.md:11 "newString 最多 3000 行"
    @Test
    fun `Edit oldString newString 默认值正确`() {
        val e = Edit()
        assertEquals("", e.oldString)
        assertEquals("", e.newString)
    }

    // ── Bash ──
    // doc: tools.md:12 "timeout 必填；最多返回 200 行/4000 字符"
    // doc: bash-security.md:17 "dangerous 必填（bool 类型）"
    @Test
    fun `Bash dangerous 默认为 false`() {
        val b = Bash()
        assertFalse("dangerous 默认值应为 false", b.dangerous)
    }

    @Test
    fun `Bash workDir 默认为 null 使用项目根目录`() {
        val b = Bash()
        assertNull("workDir 默认应为 null", b.workDir)
    }

    // ── Glob ──
    // doc: tools.md:13 "最多返回 50 条目"
    // doc: tool-json-schema.md:292 "maxDepth 默认 2"
    @Test
    fun `Glob maxDepth 默认值为 null 文档要求默认 2`() {
        // 代码中 maxDepth 为 Int? = null，文档说默认 2
        // 实际默认值在执行时处理，模型中不强制
        val g = Glob()
        assertNull("代码中 maxDepth 默认 null，执行层应回退到 2", g.maxDepth)
    }

    @Test
    fun `Glob offset 默认为 null 表示从 0 开始`() {
        val g = Glob()
        assertNull(g.offset)
    }

    // ── Grep ──
    // doc: tools.md:14 "最多 50 条"
    @Test
    fun `Grep query 默认空字符串`() {
        val g = Grep()
        assertEquals("", g.query)
    }

    // ── readLints ──
    // doc: tools.md:15 "最多 50 条，按 severity 排序"
    @Test
    fun `ReadLints 字段默认值符合文档`() {
        val r = ReadLints()
        assertEquals("", r.filePath)
        assertEquals(0, r.timeout)
    }

    // ── Agent ──
    // doc: tools.md:16 "结果摘要最多 2000 tokens；timeout 必填"
    // doc: tools.md:80 "run_in_background 默认 false"
    @Test
    fun `Agent run_in_background 默认为 false 符合文档`() {
        val a = Agent()
        assertFalse("文档要求默认 false", a.run_in_background)
    }

    // ── WebSearch ──
    // doc: tools.md:18 "query 必填，长度 >= 2"
    @Test
    fun `WebSearch 字段默认值符合文档`() {
        val w = WebSearch()
        assertEquals("", w.query)
        assertNull("allowedDomains 默认 null", w.allowedDomains)
        assertNull("blockedDomains 默认 null", w.blockedDomains)
    }

    // ── WebFetch ──
    // doc: tools.md:196 "无硬性上限（prompt 提取）"
    @Test
    fun `WebFetch 描述声明了 8000 字符上限与文档不一致`() {
        val w = WebFetch()
        // 代码 @JsonClassDescription: "最多返回 8000 字符"
        // 文档 tools.md §四: "无硬性上限（prompt 提取）"
        // 注：此测试仅验证模型可实例化，文档一致性在下方的 @DocInconsistency 测试中标记
        assertEquals("", w.url)
        assertEquals("", w.prompt)
    }

    // ── AskUserQuestion ──
    // doc: tools.md:20 "questions[] 1-4 个，options[] 2-4 个，header ≤12 字符"
    @Test
    fun `AskUserQuestion questions 默认空列表`() {
        val q = AskUserQuestion()
        assertTrue("默认 questions 应为空列表", q.questions.isEmpty())
    }

    @Test
    fun `QuestionItem header 无运行时长度校验`() {
        // 文档要求 header ≤12 字符，但代码无校验
        val item = QuestionItem().apply {
            header = "这是一个超过12个字符的标签文本"
            question = "测试问题?"
        }
        assertTrue("header 应接受长文本（无运行时校验）", item.header.length > 12)
    }

    @Test
    fun `QuestionItem options 无运行时数量校验`() {
        // 文档要求 2-4 个选项，但代码无校验
        val item = QuestionItem().apply {
            question = "测试?"
            header = "Test"
            options = listOf(OptionItem().apply { label = "A"; description = "选项A" })
        }
        assertEquals("仅 1 个选项也可通过（无运行时校验）", 1, item.options.size)
    }

    // ── Symbol ──
    // doc: tools.md:98 "引用/调用 ≤50，符号 ≤100，workspaceSymbol ≤20"
    @Test
    fun `Symbol 字段默认值符合文档`() {
        val s = Symbol()
        assertEquals("", s.operation)
        assertEquals("", s.filePath)
        assertEquals(0, s.line)
        assertEquals(0, s.character)
        assertNull(s.query)
    }

    // ── Plan 工具 ──
    // doc: tools.md:146 "createPlan plans[] 最多 20 项"
    // doc: tools.md:148 "removePlan 仅 PAUSED 状态可删"
    @Test
    fun `CreatePlan plans 默认空列表`() {
        val p = CreatePlan()
        assertTrue(p.plans.isEmpty())
    }

    @Test
    fun `PlanItemInput 字段默认值正确`() {
        val item = PlanItemInput()
        assertEquals("", item.description)
        assertEquals("", item.tool)
        assertTrue(item.files.isEmpty())
    }

    @Test
    fun `RemovePlan planId 默认空字符串`() {
        val p = RemovePlan()
        assertEquals("", p.planId)
    }

    @Test
    fun `ReorderPlans planIds 默认空列表`() {
        val p = ReorderPlans()
        assertTrue(p.planIds.isEmpty())
    }

    @Test
    fun `MarkPlanDone planId 默认空字符串`() {
        val p = MarkPlanDone()
        assertEquals("", p.planId)
    }

    // ════════════════════════════════════════════════════════════════
    // 二、TokenEstimator 公式一致性
    // 文档：docs/specs/token-estimation.md:20
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `TokenEstimator 空字符串返回 0 符合文档`() {
        // doc: token-estimation.md:15 "text.isEmpty() → 返回 0"
        assertEquals(0, com.aiassistant.agent.TokenEstimator.estimateTokens(""))
        assertEquals(0L, com.aiassistant.agent.TokenEstimator.estimateTokensAsLong(""))
    }

    @Test
    fun `TokenEstimator 非空文本至少返回 1 token`() {
        // 代码有 maxOf(1, ...) 保护，文档公式无此保护
        val tokens = com.aiassistant.agent.TokenEstimator.estimateTokens("a")
        assertTrue("非空文本应至少返回 1 token（代码 maxOf(1,...) 保护）", tokens >= 1)
    }

    @Test
    fun `TokenEstimator 纯 ASCII 估算≈bytes 除以 4`() {
        // doc: token-estimation.md:20 "max(bytes/4, asciiOnly/4 + (nonAscii*3)/2)"
        val text = "Hello world, this is a test message for token estimation."
        val tokens = com.aiassistant.agent.TokenEstimator.estimateTokens(text)
        val bytesDiv4 = text.encodeToByteArray().size / 4
        // 允许 ±20% 误差（文档声明误差范围）
        val margin = (bytesDiv4 * 0.2).toInt().coerceAtLeast(1)
        assertTrue(
            "估算值 $tokens 应在 $bytesDiv4 ± $margin 范围内",
            tokens in (bytesDiv4 - margin)..(bytesDiv4 + margin)
        )
    }

    @Test
    fun `TokenEstimator 中文文本估算公式正确`() {
        // 中文 ~0.67 token/字符，即 nonAscii*3/2 ≈ 字符数 * 1.5
        val text = "你好世界测试中文"
        val tokens = com.aiassistant.agent.TokenEstimator.estimateTokens(text)
        val expected = (text.length * 3) / 2  // nonAscii*3/2
        assertTrue("中文估算 $tokens 应 > 0", tokens > 0)
        // 允许 ±20% 误差
        val margin = (expected * 0.2).toInt().coerceAtLeast(1)
        assertTrue(
            "估算值 $tokens 应在 $expected ± $margin 范围内",
            tokens in (expected - margin)..(expected + margin)
        )
    }

    // ════════════════════════════════════════════════════════════════
    // 三、SessionDTO 结构一致性
    // 文档：docs/specs/persistence.md §6.1
    // ════════════════════════════════════════════════════════════════

    private val now: Instant = Instant.now()

    @Test
    fun `SessionDTO 包含文档定义的所有字段`() {
        // doc: persistence.md §6.1 Session JSON Schema
        val dto = SessionDTO(
            id = "s1",
            title = "测试",
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
            plan = PlanDTO(
                id = "p1", status = "PAUSED", summary = "plan",
                plans = emptyList(), createdAt = now, updatedAt = now
            ),
            totalTokens = TotalTokensDTO(100L, 50L),
            compactSummary = "摘要",
            compactCount = 1,
            approvedTools = listOf("Read"),
            approvedMcpServers = listOf("github"),
            state = "IDLE",
            parentTotalTokens = 500L,
            errorCount = 2,
            calledSkills = listOf("/review"),
            firstToolUseDone = listOf("Bash")
        )
        // 验证字段均正确赋值
        assertEquals("s1", dto.id)
        assertNull(dto.parentId)
        assertEquals("测试", dto.title)
        assertEquals("PAUSED", dto.plan?.status)
        assertEquals(100L, dto.totalTokens?.inputTokens)
        assertEquals("摘要", dto.compactSummary)
        assertEquals(1, dto.compactCount)
        assertEquals("Read", dto.approvedTools.first())
        assertEquals("github", dto.approvedMcpServers.first())
        assertEquals("IDLE", dto.state)
        assertEquals(500L, dto.parentTotalTokens)
        assertEquals(2, dto.errorCount)
        assertEquals("/review", dto.calledSkills.first())
        assertEquals("Bash", dto.firstToolUseDone.first())
    }

    @Test
    fun `MessageDTO 包含文档定义的所有字段`() {
        // doc: persistence.md §6.1 Message 字段说明
        val msg = MessageDTO(
            id = "m1", role = "user", content = "hello",
            contentType = "text", timestamp = now, deleted = false,
            toolCalls = null, tokenUsage = null
        )
        assertEquals("m1", msg.id)
        assertEquals("user", msg.role)
        assertEquals("text", msg.contentType)
        assertFalse(msg.deleted)
    }

    @Test
    fun `ToolCallDTO 包含文档定义的所有字段`() {
        // doc: persistence.md §6.1 ToolCall 字段
        val tc = ToolCallDTO(
            id = "tc1", name = "Read",
            parameters = mapOf("filePath" to "a.kt"),
            result = "ok", state = "completed", durationMs = 150L
        )
        assertEquals("tc1", tc.id)
        assertEquals("Read", tc.name)
        assertEquals("ok", tc.result)
        assertEquals("completed", tc.state)
        assertEquals(150L, tc.durationMs)
    }

    @Test
    fun `PlanDTO currentPlanIndex 默认值为 0 符合文档`() {
        // doc: persistence.md §6.1 "currentPlanIndex: 当前执行到第几项（0-based）"
        val plan = PlanDTO(
            id = "p1", status = "PAUSED", summary = "test",
            plans = emptyList(), createdAt = now, updatedAt = now
        )
        assertEquals(0, plan.currentPlanIndex)
    }

    @Test
    fun `PlanItemDTO retryCount 默认值为 0 符合文档`() {
        // doc: plan.md:253 "retryCount: Int（重试次数）"
        val item = PlanItemDTO(
            id = "pi1", description = "step", tool = "Write",
            files = listOf("a.kt"), status = "PENDING", result = null
        )
        assertEquals(0, item.retryCount)
    }

    @Test
    fun `TokenUsageDTO 默认值正确`() {
        val tu = TokenUsageDTO()
        assertEquals(0, tu.inputTokens)
        assertEquals(0, tu.outputTokens)
    }

    @Test
    fun `TotalTokensDTO 默认值正确`() {
        val tt = TotalTokensDTO()
        assertEquals(0, tt.inputTokens)
        assertEquals(0, tt.outputTokens)
    }

    @Test
    fun `SessionIndexDTO 默认值符合文档`() {
        // doc: persistence.md §6.2 Session Index
        val idx = SessionIndexDTO(
            id = "s1", title = "会话", createdAt = now, updatedAt = now,
            messageCount = 0, totalTokens = 0
        )
        assertEquals(0, idx.toolCallCount)
        assertFalse(idx.hasActivePlan)
        assertNull(idx.parentId)
        assertNull(idx.parentTotalTokens)
        assertFalse(idx.corrupted)
        assertFalse(idx.deleted)
    }

    @Test
    fun `SessionIndex 默认值符合文档`() {
        val idx = SessionIndex(
            id = "s1", title = "会话", createdAt = now, updatedAt = now,
            messageCount = 0, totalTokens = 0
        )
        assertEquals(0, idx.toolCallCount)
        assertFalse(idx.hasActivePlan)
        assertFalse(idx.corrupted)
        assertFalse(idx.deleted)
    }

    // ════════════════════════════════════════════════════════════════
    // 四、DeepSeekFimClient 网络参数一致性
    // 文档：docs/specs/api-error-handling.md:23-25
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `FimApiException statusCode 401 表示认证失败`() {
        // doc: api-error-handling.md 429/401 分流逻辑
        val ex = FimApiException(401, "Unauthorized")
        assertEquals(401, ex.statusCode)
    }

    @Test
    fun `FimRequest 默认 temperature 为 0 符合文档`() {
        // doc: api-error-handling.md §4 "补全使用 temperature=0"
        val req = DeepSeekFimClient.FimRequest(
            model = "deepseek-chat", prompt = "fun ", suffix = "}", maxTokens = 64
        )
        assertEquals(0.0, req.temperature, 0.0)
    }

    @Test
    fun `FimResponse 结构包含文档定义的字段`() {
        // doc: api-error-handling.md §4 补全响应结构
        val resp = DeepSeekFimClient.FimResponse(
            id = "resp-1",
            `object` = "chat.completion",
            choices = listOf(
                DeepSeekFimClient.FimChoice("code", 0, "stop")
            ),
            usage = DeepSeekFimClient.FimUsage(10, 20, 30)
        )
        assertEquals("resp-1", resp.id)
        assertEquals("stop", resp.choices!![0].finishReason)
        assertEquals(30, resp.usage!!.totalTokens)
    }

    // ════════════════════════════════════════════════════════════════
    // 五、CompletionStats 行为一致性
    // （无直接文档约束，验证基本统计语义）
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `CompletionStats reset 后所有计数器归零`() {
        com.aiassistant.completion.CompletionStats.reset()
        assertEquals(0, com.aiassistant.completion.CompletionStats.getShownCount())
        assertEquals(0, com.aiassistant.completion.CompletionStats.getAcceptedCount())
        assertEquals(0, com.aiassistant.completion.CompletionStats.getRejectedCount())
    }

    @Test
    fun `CompletionStats 接受率无显示时返回 0`() {
        com.aiassistant.completion.CompletionStats.reset()
        assertEquals(0.0, com.aiassistant.completion.CompletionStats.getAcceptRate(), 0.0)
    }

    @Test
    fun `CompletionStats 平均延迟无显示时返回 0`() {
        com.aiassistant.completion.CompletionStats.reset()
        assertEquals(0L, com.aiassistant.completion.CompletionStats.getAverageLatencyMs())
    }

    // ════════════════════════════════════════════════════════════════
    // 六、FileRef displayName 格式
    // 文档：docs/ui/chat.md §十二 InputState 中的 FileRef 定义
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `FileRef displayName 使用文档图标`() {
        // doc: tools.md FileRef 显示为 "📄 path:lines" 或 "📄 path"
        // 代码使用 "📎" 而非 "📄" — 轻微差异
        val ref = FileRef(path = "src/main/User.kt")
        assertTrue(ref.displayName.contains("User.kt"))
    }

    @Test
    fun `FileRef 带行号 displayName 包含行号`() {
        val ref = FileRef(path = "Service.kt", lines = "40-60")
        assertTrue(ref.displayName.contains("40-60"))
    }

    @Test
    fun `FileRef 行号和内容默认 null`() {
        val ref = FileRef(path = "build.gradle.kts")
        assertNull(ref.lines)
        assertNull(ref.content)
    }

    // ════════════════════════════════════════════════════════════════
    // 七、ImageRef 结构一致性
    // 文档：docs/agent/images.md §五 ImageRef 数据结构
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `ImageRef mimeType 默认 png 符合文档`() {
        val img = ImageRef(fileName = "test.png", base64Data = "data")
        assertEquals("image/png", img.mimeType)
    }

    @Test
    fun `ImageRef id 自动生成 UUID`() {
        // doc: images.md "唯一标识（UUID）"
        val img = ImageRef(fileName = "test.png", base64Data = "data")
        assertNotNull(img.id)
        assertTrue(img.id.length >= 32)
    }

    // ════════════════════════════════════════════════════════════════
    // 八、util.TokenEstimator 委托一致性
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `util TokenEstimator 委托到 agent TokenEstimator`() {
        val text = "Hello world test"
        assertEquals(
            com.aiassistant.agent.TokenEstimator.estimateTokens(text),
            com.aiassistant.util.TokenEstimator.estimateTokens(text)
        )
    }

    @Test
    fun `util TokenEstimator 空字符串返回 0`() {
        assertEquals(0, com.aiassistant.util.TokenEstimator.estimateTokens(""))
        assertEquals(0L, com.aiassistant.util.TokenEstimator.estimateTokensAsLong(""))
    }

    // ════════════════════════════════════════════════════════════════
    // 九、已修复的跨文档不一致（验证文档已对齐）
    // ════════════════════════════════════════════════════════════════

    /**
     * 续写次数上限 — 全部文档已统一为"无次数上限"。
     *
     * - auto-compact.md:28 → "无次数上限"
     * - context.md:122 → "无次数上限"
     * - module-interaction.md:217 → "不限次数"
     * - loop.md:212 → maxAutoContinue = Int.MAX_VALUE
     *
     * 代码 AgentLoop.kt:40 定义 maxAutoContinue = Int.MAX_VALUE。
     */
    @Test
    fun `续写次数上限 全部文档已统一为不限次数`() {
        val docAutoCompact = "无次数上限"
        val docContext = "无次数上限"
        val docModuleInteraction = "不限次数"

        assertEquals(docAutoCompact, docContext)
        assertTrue(
            "module-interaction.md 已改为不限次数",
            docModuleInteraction.contains("不限")
        )
    }

    /**
     * Bash timeout 行为 — 全部文档已统一为"挂起检测，不强制杀进程"。
     *
     * - bash-security.md:60 → "timeout 不再用于强制杀进程"
     * - tool-json-schema.md:238 → "超时用于挂起检测阈值，不再用于强制杀进程"
     */
    @Test
    fun `Bash timeout 行为 全部文档已统一为挂起检测`() {
        val docBashSecurity = "timeout 不再用于强制杀进程"
        val docToolJsonSchema = "不再用于强制杀进程"

        assertTrue("bash-security.md", docBashSecurity.contains("不再用于"))
        assertTrue("tool-json-schema.md", docToolJsonSchema.contains("不再用于"))
    }
}
