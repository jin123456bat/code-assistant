package com.aiassistant.agent

// ponytail: tool registry — allows dynamic tool registration (MCP tools, skill tools)

object ToolRegistry {

    private val tools = mutableMapOf<String, Class<*>>()

    fun register(name: String, toolClass: Class<*>) {
        tools[name] = toolClass
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): Class<*>? = tools[name]
    fun listAll(): List<Class<*>> = tools.values.toList()
    fun listNames(): List<String> = tools.keys.toList()
    fun listBuiltin(): List<Class<*>> = tools.filter { !it.key.startsWith("mcp/") }.values.toList()
    fun listMcp(): List<Class<*>> = tools.filter { it.key.startsWith("mcp/") }.values.toList()
    fun toToolDefinitions(): List<String> = tools.keys.toList()

    // Register all built-in tools
    fun registerBuiltins() {
        register("readFile", ReadFile::class.java)
        register("writeFile", WriteFile::class.java)
        register("editFile", EditFile::class.java)
        register("runShell", RunShell::class.java)
        register("listFiles", ListFiles::class.java)
        register("searchContent", SearchContent::class.java)
        register("readLints", ReadLints::class.java)
        register("spawnAgent", SpawnAgent::class.java)
    }

    init {
        registerBuiltins()
    }
}
