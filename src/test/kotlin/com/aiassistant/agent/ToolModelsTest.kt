package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ToolModels 数据类测试。
 * 验证所有工具模型的默认值、字段赋值和关键行为。
 */
class ToolModelsTest {

    // ═══ Read ═══

    @Test
    fun `Read 默认值正确`() {
        val r = Read()
        assertEquals("", r.filePath)
        assertEquals(null, r.startLine)
        assertEquals(null, r.endLine)
        assertEquals(0, r.timeout)
    }

    @Test
    fun `Read 字段赋值正常`() {
        val r = Read().apply {
            filePath = "src/main/User.kt"
            startLine = 10
            endLine = 50
            timeout = 15
        }
        assertEquals("src/main/User.kt", r.filePath)
        assertEquals(10, r.startLine)
        assertEquals(50, r.endLine)
        assertEquals(15, r.timeout)
    }

    // ═══ Write ═══

    @Test
    fun `Write 默认值正确`() {
        val w = Write()
        assertEquals("", w.filePath)
        assertEquals("", w.content)
        assertEquals(0, w.timeout)
    }

    @Test
    fun `Write 字段赋值正常`() {
        val w = Write().apply {
            filePath = "src/test/Test.kt"
            content = "package com.example\n\nclass Test"
            timeout = 10
        }
        assertEquals("src/test/Test.kt", w.filePath)
        assertTrue(w.content.contains("class Test"))
        assertEquals(10, w.timeout)
    }

    // ═══ Edit ═══

    @Test
    fun `Edit 默认值正确`() {
        val e = Edit()
        assertEquals("", e.filePath)
        assertEquals("", e.oldString)
        assertEquals("", e.newString)
        assertEquals(0, e.timeout)
    }

    @Test
    fun `Edit 字段赋值正常`() {
        val e = Edit().apply {
            filePath = "src/main/Service.kt"
            oldString = "val old = true"
            newString = "val new = false"
            timeout = 5
        }
        assertEquals("val old = true", e.oldString)
        assertEquals("val new = false", e.newString)
        assertEquals(5, e.timeout)
    }

    // ═══ Bash ═══

    @Test
    fun `Bash 默认值正确`() {
        val b = Bash()
        assertEquals("", b.command)
        assertEquals(null, b.workDir)
        assertEquals(0, b.timeout)
        assertFalse(b.dangerous)
    }

    @Test
    fun `Bash 危险命令标记`() {
        val b = Bash().apply {
            command = "rm -rf /"
            dangerous = true
            timeout = 30
        }
        assertEquals("rm -rf /", b.command)
        assertTrue(b.dangerous)
    }

    // ═══ Glob ═══

    @Test
    fun `Glob 默认值正确`() {
        val g = Glob()
        assertEquals(null, g.dirPath)
        assertEquals(null, g.maxDepth)
        assertEquals(null, g.offset)
        assertEquals(0, g.timeout)
    }

    @Test
    fun `Glob 分页参数正确`() {
        val g = Glob().apply {
            dirPath = "src/main"
            maxDepth = 3
            offset = 50
            timeout = 10
        }
        assertEquals("src/main", g.dirPath)
        assertEquals(3, g.maxDepth)
        assertEquals(50, g.offset)
    }

    // ═══ Grep ═══

    @Test
    fun `Grep 默认值正确`() {
        val g = Grep()
        assertEquals("", g.query)
        assertEquals(null, g.filePattern)
        assertEquals(0, g.timeout)
    }

    @Test
    fun `Grep 文件过滤模式`() {
        val g = Grep().apply {
            query = "toString"
            filePattern = "*.kt"
            timeout = 15
        }
        assertEquals("toString", g.query)
        assertEquals("*.kt", g.filePattern)
    }

    // ═══ ReadLints ═══

    @Test
    fun `ReadLints 默认值正确`() {
        val r = ReadLints()
        assertEquals("", r.filePath)
        assertEquals(0, r.timeout)
    }

    // ═══ Skill ═══

    @Test
    fun `Skill 默认值正确`() {
        val s = Skill()
        assertEquals("", s.skill)
        assertEquals(null, s.args)
        assertEquals(0, s.timeout)
    }

    @Test
    fun `Skill 带参数`() {
        val s = Skill().apply {
            skill = "/review"
            args = "file1.kt file2.kt"
            timeout = 10
        }
        assertEquals("/review", s.skill)
        assertEquals("file1.kt file2.kt", s.args)
    }

    // ═══ Agent ═══

    @Test
    fun `Agent 默认值正确`() {
        val a = Agent()
        assertEquals("", a.prompt)
        assertEquals(0, a.timeout)
        assertFalse(a.run_in_background)
    }

    @Test
    fun `Agent 后台执行`() {
        val a = Agent().apply {
            prompt = "fix all bugs"
            run_in_background = true
            timeout = 300
        }
        assertTrue(a.run_in_background)
        assertEquals(300, a.timeout)
    }

    // ═══ WebSearch ═══

    @Test
    fun `WebSearch 默认值正确`() {
        val w = WebSearch()
        assertEquals("", w.query)
        assertEquals(null, w.allowedDomains)
        assertEquals(null, w.blockedDomains)
        assertEquals(null, w.offset)
        assertEquals(0, w.timeout)
    }

    @Test
    fun `WebSearch 域名过滤`() {
        val w = WebSearch().apply {
            query = "Kotlin coroutines"
            allowedDomains = listOf("kotlinlang.org", "github.com")
            blockedDomains = listOf("medium.com")
        }
        assertEquals(2, w.allowedDomains?.size)
        assertEquals(1, w.blockedDomains?.size)
        assertTrue(w.allowedDomains!!.contains("kotlinlang.org"))
    }

    // ═══ WebFetch ═══

    @Test
    fun `WebFetch 默认值正确`() {
        val w = WebFetch()
        assertEquals("", w.url)
        assertEquals("", w.prompt)
        assertEquals(0, w.timeout)
    }

    // ═══ AskUserQuestion ═══

    @Test
    fun `AskUserQuestion 默认值正确`() {
        val q = AskUserQuestion()
        assertEquals(0, q.questions.size)
        assertEquals(0, q.timeout)
    }

    @Test
    fun `QuestionItem 和 OptionItem 构建正确`() {
        val option = OptionItem().apply {
            label = "JWT (推荐)"
            description = "使用 JSON Web Token 进行身份验证"
        }
        assertEquals("JWT (推荐)", option.label)
        assertTrue(option.description.contains("身份验证"))

        val item = QuestionItem().apply {
            question = "应该使用哪种认证方式？"
            header = "认证方式"
            options = listOf(option)
            multiSelect = false
        }
        assertEquals("认证方式", item.header)
        assertEquals(1, item.options.size)
        assertFalse(item.multiSelect)
    }

    @Test
    fun `AskUserQuestion 多选模式`() {
        val question = QuestionItem().apply {
            question = "启用哪些功能？"
            header = "功能"
            multiSelect = true
            options = listOf(
                OptionItem().apply { label = "A"; description = "功能A" },
                OptionItem().apply { label = "B"; description = "功能B" },
                OptionItem().apply { label = "C"; description = "功能C" }
            )
        }
        val q = AskUserQuestion().apply {
            questions = listOf(question)
            timeout = 300
        }
        assertTrue(q.questions[0].multiSelect)
        assertEquals(3, q.questions[0].options.size)
    }

    // ═══ Symbol ═══

    @Test
    fun `Symbol 默认值正确`() {
        val s = Symbol()
        assertEquals("", s.operation)
        assertEquals("", s.filePath)
        assertEquals(0, s.line)
        assertEquals(0, s.character)
        assertEquals(null, s.query)
        assertEquals(0, s.timeout)
    }

    // ═══ Plan 工具 ═══

    @Test
    fun `CreatePlan 默认值正确`() {
        val p = CreatePlan()
        assertEquals("", p.task)
        assertEquals(0, p.plans.size)
        assertEquals(0, p.timeout)
    }

    @Test
    fun `PlanItemInput 字段赋值正常`() {
        val item = PlanItemInput().apply {
            description = "创建 UserService 接口"
            tool = "Write"
            files = listOf("src/main/UserService.kt")
        }
        assertEquals("Write", item.tool)
        assertEquals(1, item.files.size)
        assertTrue(item.files.contains("src/main/UserService.kt"))
    }

    @Test
    fun `CreatePlan 完整构建`() {
        val plan = CreatePlan().apply {
            task = "实现用户认证模块"
            plans = listOf(
                PlanItemInput().apply {
                    description = "创建 User 实体"
                    tool = "Write"
                    files = listOf("src/main/User.kt")
                },
                PlanItemInput().apply {
                    description = "创建 AuthService"
                    tool = "Write"
                    files = listOf("src/main/AuthService.kt")
                }
            )
            timeout = 20
        }
        assertEquals("实现用户认证模块", plan.task)
        assertEquals(2, plan.plans.size)
    }

    @Test
    fun `ListPlans 默认值正确`() {
        val p = ListPlans()
        assertEquals(0, p.timeout)
    }

    @Test
    fun `RemovePlan 默认值正确`() {
        val p = RemovePlan()
        assertEquals("", p.planId)
        assertEquals(0, p.timeout)
    }

    @Test
    fun `ReorderPlans 默认值正确`() {
        val p = ReorderPlans()
        assertEquals(0, p.planIds.size)
        assertEquals(0, p.timeout)
    }

    @Test
    fun `MarkPlanDone 默认值正确`() {
        val p = MarkPlanDone()
        assertEquals("", p.planId)
        assertEquals(0, p.timeout)
    }

    // ═══ 所有工具模型可实例化（冒烟测试） ═══

    @Test
    fun `所有工具模型类均可正常实例化`() {
        val models = listOf(
            Read(), Write(), Edit(), Bash(), Glob(), Grep(),
            ReadLints(), Skill(), Agent(),
            WebSearch(), WebFetch(), AskUserQuestion(), Symbol(),
            CreatePlan(), ListPlans(), RemovePlan(), ReorderPlans(), MarkPlanDone()
        )
        assertEquals(18, models.size)
        models.forEach { assertNotNull(it, "模型实例不应为 null") }
    }
}
