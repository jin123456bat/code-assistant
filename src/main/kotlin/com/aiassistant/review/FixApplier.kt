package com.aiassistant.review

/**
 * FixApplier：/review --fix 时生成修复 prompt，发给 Agent 用 edit_file 工具修复。
 * 不再直接改写源文件——所有写操作走 AgentLoop 的 Edit 工具审批流程。
 */
class FixApplier {

    data class FixResult(val fixed: Int, val skipped: Int, val details: List<String>)

    /**
     * 生成修复 prompt。高置信度 findings 拼成结构化指令，交给 Agent 逐个修复。
     * Agent 使用自身的 edit_file 工具，经过正常的工具审批机制。
     */
    fun buildPrompt(findings: List<Finding>): String? {
        val toFix = findings.filter { it.severity == Severity.CRITICAL || it.confidence >= 8 }
        if (toFix.isEmpty()) return null

        return buildString {
            appendLine("请根据以下审查发现修复代码。每修复一项后确认结果。")
            appendLine()
            toFix.forEachIndexed { i, f ->
                appendLine("### ${i + 1}. ${f.title}")
                appendLine("- 文件: `${f.file}`")
                appendLine("- 行号: ${f.line}")
                appendLine("- 类型: ${f.severity} / ${f.category}")
                appendLine("- 描述: ${f.description}")
                if (f.suggestion.isNotBlank()) appendLine("- 建议: ${f.suggestion}")
                appendLine()
            }
            appendLine("使用 edit_file 工具逐一修复上述问题。修复原则:")
            appendLine("1. 只修改源代码，不改测试（除非测试本身有 bug）")
            appendLine("2. 每次修改后确认代码编译通过")
            appendLine("3. 跳过有歧义或无法确定的问题")
        }
    }
}
