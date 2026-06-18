package com.aiassistant.agent

// Agent 类型定义，预置类型对齐 Claude Code。
// 每种类型预设了工具白名单/黑名单、权限策略、模型、循环限制。
//
// 【设计决策】autoApprove 默认 true：
// 子 Agent 在隔离上下文中执行，其操作由主 Agent 通过 task/workflow 工具的 prompt 控制。
// autoApprove=true 让子 Agent 可以自主执行工具而无需用户逐次点击审批，
// 这与 Claude Code 的子代理行为一致。安全边界由以下机制保障：
// 1. 子 Agent 不暴露元工具（Skill/EnterPlanMode/ExitPlanMode/TaskCreate 等），
//    防止子 Agent 创建孙 Agent 或进入 Plan Mode
// 2. 子 Agent 的 maxLoops 受限（MAX_SUB_LOOPS=20），防止无限循环
// 3. 子 Agent 结果以 tool_result 返回给主 Agent，不直接呈现给用户
// 4. 主 Agent 的审批机制和 SAFE_TOOLS 约束仍适用于主 Agent 自身的操作
// 5. 自定义 Agent 类型（.claude/agents/）可通过 autoApprove=false 覆盖此行为
// 若需更严格的子 Agent 管控，使用 EXPLORE（只读）或 PLAN（不执行工具）类型。
data class AgentType(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val allowedTools: Set<String>?,
    val deniedTools: Set<String> = emptySet(),
    val autoApprove: Boolean = true,
    val defaultModel: String? = null,
    val maxLoops: Int = AgentLoop.MAX_SUB_LOOPS,
    val maxFailures: Int = AgentLoop.MAX_FAILURES,
    // 隔离模式：worktree = 独立 git worktree，null = in-process（共享工作区）
    val isolation: String? = null,
    // Fork 上下文继承：子 Agent 继承父对话 history（复用 prompt cache）
    val fork: Boolean = false
)

// 预置 Agent 类型，对齐 Claude Code sub-agent 类型
object AgentTypes {
    // 通用型：全部工具，适合大部分任务
    val GENERAL_PURPOSE = AgentType(
        name = "general-purpose",
        description = "通用子代理，拥有全部工具权限，适合各类独立任务",
        systemPrompt = "你是一个通用子代理。高效完成分配给你的任务，只返回结果，不要询问用户。",
        allowedTools = null,
        autoApprove = true
    )

    // 只读探索型：仅搜索/阅读工具，适合代码探索
    val EXPLORE = AgentType(
        name = "Explore",
        description = "只读探索代理，仅拥有搜索和阅读类工具，适合代码库调查",
        systemPrompt = "你是一个代码探索代理。搜索和阅读代码来回答问题，不修改任何文件。",
        allowedTools = setOf(
            "search_code", "read_file", "list_directory",
            "git_diff", "git_log", "git_status",
            "web_search", "web_fetch",
            "code_intelligence"
        ),
        deniedTools = emptySet(),
        autoApprove = true
    )

    // 计划型：不执行任何工具，仅输出文字
    val PLAN = AgentType(
        name = "Plan",
        description = "纯规划代理，不执行任何工具，仅输出计划和分析",
        systemPrompt = "你是一个规划代理。分析任务并给出详细的执行计划，不要调用任何工具。",
        allowedTools = emptySet(),
        autoApprove = true,
        maxLoops = 1
    )

    // 自定义 Agent 定义（从 .claude/agents/ 目录加载）
    @Volatile
    private var customDefs: Map<String, AgentLoader.CustomAgentDef> = emptyMap()

    // 加载自定义 Agent 定义（项目级 + 用户级），与 Claude Code 格式兼容
    fun loadCustom(projectBasePath: String?) {
        customDefs = AgentLoader.loadAll(projectBasePath)
    }

    // 按名称查找，优先级：内置 > 自定义，找不到返回 GENERAL_PURPOSE
    fun find(name: String?): AgentType {
        val key = name?.lowercase()
        // 内置优先
        val builtIn = when (key) {
            "general-purpose", "general_purpose", null -> GENERAL_PURPOSE
            "explore" -> EXPLORE
            "plan" -> PLAN
            else -> null
        }
        if (builtIn != null) return builtIn
        // 自定义（不区分大小写）
        val custom = customDefs[name] ?: customDefs[key]
            ?: customDefs.entries.firstOrNull { it.key.lowercase() == key }?.value
        if (custom != null) return AgentLoader.toAgentType(custom)
        return GENERAL_PURPOSE
    }
}
