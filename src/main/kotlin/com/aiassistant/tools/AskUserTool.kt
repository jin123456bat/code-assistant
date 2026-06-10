package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.ui.AskUserBridge
import com.intellij.openapi.project.Project

/**
 * ask_user 工具（M5-A / M5-B 多选扩展）。
 *
 * 当 AI 需要用户在多个明确选项中做出决策时使用：
 * - 调用后，聊天区会弹出带选项列表的选择卡。
 * - 工具调用线程阻塞，直到用户点击某个选项（或 5 分钟超时）。
 * - 返回用户所选选项的文字，供 AI 继续处理。
 *
 * 注意：options 参数使用逗号分隔的字符串，而非 JSON 数组，
 * 因为 AgentLoop 通过正则把参数值预解析为 Map<String,String>。
 *
 * 多选模式：将 multiple="true" 传入时，选择卡会以复选框 + 确认按钮形式呈现，
 * 返回值为用户勾选的所有选项，用 ", " 连接。
 */
class AskUserTool : AgentTool {

    override val name = "ask_user"

    override val description = """
        当需要用户在多个明确选项中做出决策时调用。
        弹出选项让用户选择，返回所选项文本。
        仅在有限且清晰的选项集合时使用；若需自由输入则改用文字说明。
        options 用英文逗号分隔，如 "选项A,选项B,选项C"。
        若用户可能需要同时选择多个选项，请将 multiple 设为 "true"；
        此时返回值为所有已选项用 ", " 连接的字符串。
        默认 multiple="false"，即单选模式。
    """.trimIndent()

    override val parameters = listOf(
        ToolParameter(
            name = "question",
            type = "string",
            description = "向用户提出的问题或说明，清楚描述需要做什么决定",
            required = true
        ),
        ToolParameter(
            name = "options",
            type = "string",
            description = "逗号分隔的选项列表，如 \"方案A,方案B,方案C\"，至少提供两个选项",
            required = true
        ),
        ToolParameter(
            name = "multiple",
            type = "string",
            description = "是否允许多选。\"true\" 时展示复选框 + 确认按钮，用户可选多项；\"false\"（默认）为单选，点击即提交",
            required = false
        )
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val question = params["question"]?.trim()
            ?: return ToolResult.err("缺少 question 参数")

        val rawOptions = params["options"]
            ?: return ToolResult.err("缺少 options 参数")

        // 按逗号分割，去除首尾空白，过滤空串
        val options = rawOptions.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (options.isEmpty()) {
            return ToolResult.err("options 不能为空")
        }

        // 解析多选标志，默认 false（单选）
        val multiple = params["multiple"]?.trim()?.lowercase() == "true"

        // 阻塞背景线程直到用户选择（或超时），AskUserBridge 负责 EDT 调度
        val choice = AskUserBridge.request(question, options, multiple)

        return ToolResult.ok("用户选择: $choice")
    }
}
