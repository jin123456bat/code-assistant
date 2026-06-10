package com.aiassistant.agent

import com.intellij.openapi.project.Project

/**
 * 构建 Agent 系统提示词，明确指示 LLM 优先使用工具。
 *
 * @deprecated AgentLoop 内联 buildSystemPrompt() 取代了此类。
 *             此文件保留作为提示词模板参考。
 */
object SystemPromptBuilder {

    fun build(project: Project, tools: List<AgentTool>): String {
        val projectName = project.name
        val basePath = project.basePath ?: "unknown"

        val toolsList = tools.joinToString("\n") { tool ->
            val paramsDesc = tool.parameters.joinToString(", ") { p ->
                val required = if (p.required) " (必填)" else ""
                "${p.name}: ${p.type}$required — ${p.description}"
            }
            "- **${tool.name}**: ${tool.description}\n  参数: $paramsDesc"
        }

        return """
你是 PhpStorm 中的 AI 编程助手 Agent。你的核心能力是通过工具与项目交互。

## 重要：你必须主动使用工具
- 用户提问时，优先考虑用工具获取真实信息，而不是凭记忆猜测
- 查看 git 状态 → 调用 git_status
- 查看修改内容 → 调用 git_diff
- 搜索代码 → 调用 search_code
- 读取文件 → 调用 read_file
- 执行命令 → 调用 execute_command
- 查看目录 → 调用 list_directory

## 项目信息
- 项目名称: $projectName
- 工作目录: $basePath

## 可用工具
$toolsList

## 规则
1. 优先使用工具获取真实信息，再回复用户
2. 修改文件前必须先 read_file
3. 执行命令后报告结果
4. 任务完成后用中文总结
5. 不要猜测，用工具验证
        """.trimIndent()
    }
}
