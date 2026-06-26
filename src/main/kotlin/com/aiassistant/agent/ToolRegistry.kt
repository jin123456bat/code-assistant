package com.aiassistant.agent

// ponytail: tool registry — 统一管理工具注册、元数据和 system prompt 生成

object ToolRegistry {

    data class ToolInfo(
        val name: String,
        val description: String,
        val usage: String  // system prompt 中的使用提示
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
    fun listAll(): List<Class<*>> = tools.values.toList()
    fun listNames(): List<String> = tools.keys.toList()
    fun listBuiltin(): List<Class<*>> = tools.filter { !it.key.startsWith("mcp/") }.values.toList()
    fun listMcp(): List<Class<*>> = tools.filter { it.key.startsWith("mcp/") }.values.toList()
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
            "Task",
            Task::class.java,
            ToolInfo(
                "Task",
                "启动子代理处理独立子任务",
                "子代理有独立上下文窗口，不会污染当前上下文。用于并行处理独立任务。"
            )
        )
    }

    init {
        registerBuiltins()
    }
}
