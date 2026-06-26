package com.aiassistant.agent

object ToolApprovalPolicy {

    private val approvalRequiredTools = setOf("writeFile", "editFile", "runShell", "spawnAgent")

    fun requiresApproval(toolName: String): Boolean = toolName in approvalRequiredTools

    fun describe(toolName: String, input: Any?): String {
        val lines = mutableListOf<String>()
        lines.add("工具: $toolName")
        when (toolName) {
            "runShell" -> {
                ToolInput.string(input, "command")?.let { lines.add("命令: $it") }
                ToolInput.string(input, "workDir")?.let { lines.add("目录: $it") }
            }

            "writeFile", "editFile" -> {
                ToolInput.string(input, "filePath")?.let { lines.add("文件: $it") }
            }

            "spawnAgent" -> {
                ToolInput.string(input, "task")?.let { lines.add("任务: $it") }
            }
        }
        lines.add("")
        lines.add("允许 Code Assistant 执行这个操作吗？")
        return lines.joinToString("\n")
    }
}
