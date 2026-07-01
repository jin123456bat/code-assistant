package com.aiassistant.agent

import com.anthropic.models.beta.messages.BetaTool

// ponytail: tool registry — 统一管理工具注册、元数据和 system prompt 生成

object ToolRegistry {

    data class ToolInfo(
        val name: String,
        val description: String,
        val usage: String,  // system prompt 中的使用提示
        val betaTool: BetaTool? = null
    )

    data class RegisteredTool(
        val name: String,
        val toolClass: Class<*>,
        val info: ToolInfo
    )

    private val tools = mutableMapOf<String, Class<*>>()
    private val infoMap = mutableMapOf<String, ToolInfo>()

    fun register(name: String, toolClass: Class<*>, info: ToolInfo) {
        tools[name] = toolClass
        infoMap[name] = info
    }

    /** 便捷重载：从 @JsonClassDescription 注解自动提取描述，usage 为空字符串 */
    fun register(name: String, toolClass: Class<*>) {
        val desc = toolClass.annotations
            .filterIsInstance<com.fasterxml.jackson.annotation.JsonClassDescription>()
            .firstOrNull()?.value ?: ""
        tools[name] = toolClass
        infoMap[name] = ToolInfo(name, desc, "")
    }

    fun unregister(name: String) {
        tools.remove(name)
        infoMap.remove(name)
    }

    fun get(name: String): Class<*>? = tools[name]
    fun getToolInfo(name: String): ToolInfo? = infoMap[name]
    fun listAll(): List<Class<*>> = tools.values.toList()
    fun listRegistered(): List<RegisteredTool> =
        tools.mapNotNull { (name, clazz) ->
            infoMap[name]?.let { RegisteredTool(name, clazz, it) }
        }
    fun listNames(): List<String> = tools.keys.toList()
    private fun isMcpTool(name: String): Boolean = infoMap[name]?.betaTool != null
    fun listBuiltin(): List<String> = tools.filter { !isMcpTool(it.key) }.keys.toList()
    fun listMcp(): List<Class<*>> = tools.filter { isMcpTool(it.key) }.values.toList()
    fun toToolDefinitions(): List<String> = tools.keys.toList()

    /** 动态生成 system prompt 中的工具使用指南章节 */
    fun buildSystemPromptTools(): String {
        if (infoMap.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("## 工具使用指南")
        infoMap.values.sortedBy { it.name }.forEach { info ->
            sb.appendLine("- **${info.name}** — ${info.usage}")
        }
        sb.appendLine("- **并行调用** — 多个独立的读取/搜索操作可以同时发起，无需等待。")
        return sb.toString()
    }

    // Register all built-in tools
    fun registerBuiltins() {
        register(
            "Read",
            Read::class.java,
            ToolInfo(
                "Read",
                "读取项目内指定文件的内容",
                "先确认路径存在。大文件用 startLine/endLine 分页读取。"
            )
        )
        register(
            "Write",
            Write::class.java,
            ToolInfo(
                "Write",
                "覆盖写入整个文件",
                "仅用于创建新文件或需要大范围修改时。小修改请用 Edit。"
            )
        )
        register(
            "Edit",
            Edit::class.java,
            ToolInfo(
                "Edit",
                "精确替换文件中的部分内容",
                "小范围修改的首选，比 Write 更安全。oldString 必须唯一且精确匹配。"
            )
        )
        register(
            "Bash",
            Bash::class.java,
            ToolInfo(
                "Bash",
                "执行 Shell 命令",
                "工作目录默认是项目根目录。构建、测试等长时间命令是正常的。"
            )
        )
        register(
            "Glob",
            Glob::class.java,
            ToolInfo(
                "Glob",
                "列出项目目录结构",
                "用 maxDepth 控制深度，默认 2 层。"
            )
        )
        register(
            "Grep",
            Grep::class.java,
            ToolInfo(
                "Grep",
                "在项目中搜索文本内容",
                "结果上限 50 条。关键词太泛会被截断，用更精确的搜索词。"
            )
        )
        register(
            "readLints",
            ReadLints::class.java,
            ToolInfo(
                "readLints",
                "读取文件的 IDE 诊断信息",
                "修改代码后检查是否有新错误/警告。"
            )
        )
        register(
            "Agent",
            Agent::class.java,
            ToolInfo(
                "Agent",
                "启动子代理处理独立子任务",
                "子代理有独立上下文窗口，不会污染当前上下文。用于并行处理独立任务。"
            )
        )
        register(
            "WebSearch",
            WebSearch::class.java,
            ToolInfo(
                "WebSearch",
                "搜索网页，返回标题和 URL 列表",
                "≤ 10 条/页，超出时用 offset 参数翻页。不支持缓存。"
            )
        )
        register(
            "WebFetch",
            WebFetch::class.java,
            ToolInfo(
                "WebFetch",
                "抓取 URL 内容并按提示提取信息",
                "HTTP 自动升级为 HTTPS。不支持需认证的页面。"
            )
        )
        register(
            "AskUserQuestion",
            AskUserQuestion::class.java,
            ToolInfo(
                "AskUserQuestion",
                "向用户发起问题以澄清需求",
                "一次 1-4 个问题，每题 2-4 个选项。支持多选。"
            )
        )
        register(
            "Symbol",
            Symbol::class.java,
            ToolInfo(
                "Symbol",
                "基于 IDE PSI 的语义导航（8 种操作）",
                "goToDefinition/goToImplementation/findReferences/hover/documentSymbol/workspaceSymbol/incomingCalls/outgoingCalls"
            )
        )
        register(
            "Skill",
            Skill::class.java,
            ToolInfo(
                "Skill",
                "执行指定 Skill，将 SKILL.md 内容作为消息注入 conversation",
                "LLM 根据用户需求自主判断触发时机。skill 参数为 Skill 名称或命令。"
            )
        )
        register(
            "createPlan",
            CreatePlan::class.java,
            ToolInfo(
                "createPlan",
                "创建/更新执行计划",
                "任务涉及 3+ 文件或预计 5 轮以上完成时，在执行关键修改前先创建计划。执行中可随时用 listPlans/removePlan/reorderPlans/markPlanDone 管理。"
            )
        )
        register(
            "listPlans",
            ListPlans::class.java,
            ToolInfo(
                "listPlans",
                "查看当前计划状态",
                "返回所有计划项及其当前状态。"
            )
        )
        register(
            "removePlan",
            RemovePlan::class.java,
            ToolInfo(
                "removePlan",
                "删除指定计划项（仅 PAUSED 状态可删）",
                "传入 planId 删除对应计划项。"
            )
        )
        register(
            "reorderPlans",
            ReorderPlans::class.java,
            ToolInfo(
                "reorderPlans",
                "重排 PAUSED 计划项的顺序",
                "传入新的 planId 序列，调整剩余 PAUSED 项的执行顺序。"
            )
        )
        register(
            "markPlanDone",
            MarkPlanDone::class.java,
            ToolInfo(
                "markPlanDone",
                "将指定计划项标记为完成",
                "传入 planId，将对应计划项标记为 COMPLETED。"
            )
        )
    }

    init {
        registerBuiltins()
    }
}
