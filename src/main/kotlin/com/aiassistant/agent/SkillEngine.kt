package com.aiassistant.agent

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Skill 引擎 — 从 .claude/skills/ 和项目目录加载 skill 定义。
 * 对齐 Claude Code：skill 不作为独立工具注册，而是通过统一的 Skill 元工具激活。
 * 支持实时文件变更检测（WatchService），新增/修改/删除 SKILL.md 后自动重载。
 */
object SkillEngine {

    /** .claude/skills/ 目录名（相对于 user.home 或 project 根目录） */
    const val SKILLS_DIR = ".claude/skills"

    /** Skill 定义 — 对齐 Claude Code SKILL.md 格式 */
    data class SkillDef(
        val name: String,
        val description: String,
        val prompt: String,
        val preferredModel: String? = null,
        val allowedTools: List<String> = emptyList(),
        val invokeFor: String? = null  // null=两者皆可, "user"=仅命令, "agent"=仅LLM
    )

    fun loadProjectSkills(projectBasePath: String): List<SkillDef> {
        val defs = mutableListOf<SkillDef>()
        val skillsDir = File(projectBasePath, SKILLS_DIR)
        val globalSkillsDir = File(System.getProperty("user.home"), SKILLS_DIR)

        // 加载顺序：先全局后项目，项目同名 skill 覆盖全局（项目优先）
        loadFromDir(globalSkillsDir, defs)
        loadFromDir(skillsDir, defs)

        return defs
    }

    private fun loadFromDir(dir: File, defs: MutableList<SkillDef>) {
        if (!dir.exists()) return
        try {
            // 遍历全深度目录树（用户 skill 可能嵌套在子目录中，如 gstack/review/SKILL.md）
            // 跳过隐藏目录（.git、.hermes 等）中的文件
            dir.walkTopDown().forEach { file ->
                val relativePath = file.relativeTo(dir).path
                val pathParts = relativePath.split(File.separator)
                // 跳过隐藏目录内的所有文件
                if (pathParts.any { it.startsWith(".") }) return@forEach
                if (file.name == "SKILL.md" && file.isFile && file.length() < 200_000) {
                    try {
                        val content = file.readText()
                        val skill = parseSkill(file.parentFile.name, content)
                        if (skill != null) {
                            // 项目 skill 覆盖全局同名 skill
                            defs.removeAll { it.name == skill.name }
                            defs.add(skill)
                        }
                    } catch (e: Exception) {
                        // 单个 skill 解析失败不影响其他 skill 加载
                    }
                }
            }
        } catch (e: Exception) {
            // walkTopDown 遇权限/符号链接异常不中断初始化
        }
    }

    // YAML frontmatter 分隔符（\R 匹配 \n / \r\n / \r，兼容 Windows CRLF）
    private val FRONTMATTER = Regex("^---\\s*\\R(.*?)\\R---\\s*\\R(.*)", setOf(RegexOption.DOT_MATCHES_ALL))

    /** 解析 SKILL.md 的 YAML front matter 和正文内容（使用 SnakeYAML） */
    private fun parseSkill(name: String, content: String): SkillDef? {
        val match = FRONTMATTER.find(content)
        val (frontmatter, body) = if (match != null) {
            match.groupValues[1] to match.groupValues[2].trim()
        } else {
            "" to content
        }

        val yaml = org.yaml.snakeyaml.Yaml()
        @Suppress("UNCHECKED_CAST")
        val fields = if (frontmatter.isNotBlank()) {
            try { yaml.load(frontmatter) as? Map<String, Any> ?: emptyMap() }
            catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val skillName = fields["name"]?.toString()?.trim() ?: name.replace('-', ' ')
        val description = fields["description"]?.toString()?.trim() ?: "Skill: $skillName"
        val preferredModel = fields["model"]?.toString()?.trim()
        val allowedTools = (fields["allowed-tools"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val invokeFor = fields["invoke-for"]?.toString()?.trim()?.takeIf { it == "user" || it == "agent" }
        return SkillDef(skillName, description, body, preferredModel, allowedTools, invokeFor)
    }

    // ---- 文件变更监听 ----

    /** 活跃的 SkillWatcher 实例（按 projectBasePath 索引），供 stopWatching 查找 */
    private val watchers = ConcurrentHashMap<String, SkillWatcher>()

    /**
     * 启动 skills 目录的实时文件监听。
     * @param projectBasePath 项目根路径
     * @param globalHome 全局 home 路径（默认 user.home）
     * @param onChange 变更回调（在 daemon 线程中调用，调用方需自行处理线程安全）
     */
    fun startWatching(
        projectBasePath: String,
        globalHome: String = System.getProperty("user.home"),
        onChange: () -> Unit
    ) {
        val key = projectBasePath
        // 幂等：已有 watcher 则先停止
        stopWatching(key)
        val watcher = SkillWatcher(onChange)
        val globalDir = File(globalHome, SKILLS_DIR)
        val projectDir = File(projectBasePath, SKILLS_DIR)
        watcher.start()  // 先启动 WatchService，再注册目录
        if (globalDir.exists()) watcher.register(globalDir.toPath())
        if (projectDir.exists()) watcher.register(projectDir.toPath())
        watchers[key] = watcher
    }

    /** 停止指定项目的文件监听 */
    fun stopWatching(projectBasePath: String) {
        watchers.remove(projectBasePath)?.stop()
    }

    /**
     * 基于 WatchService 的技能文件监听器。
     * 监控目录下 SKILL.md 的创建/修改/删除 + 子目录的增删（重扫注册）。
     */
    private class SkillWatcher(private val onChange: () -> Unit) {

        private var watchService: WatchService? = null
        private var thread: Thread? = null
        @Volatile private var running = false

        /** 已注册监听的目录路径集合 */
        private val registeredDirs = ConcurrentHashMap.newKeySet<Path>()

        fun register(dir: Path) {
            if (!dir.toFile().isDirectory) return
            val ws = watchService ?: return
            // 注册根目录 + 所有非隐藏子目录
            try {
                dir.toFile().walkTopDown().forEach { sub ->
                    if (sub.isDirectory && !sub.name.startsWith(".")) {
                        val path = sub.toPath()
                        if (registeredDirs.add(path)) {
                            path.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
                        }
                    }
                }
            } catch (_: Exception) { /* 目录不可访问，跳过 */ }
        }

        fun start() {
            if (running) return
            val ws = FileSystems.getDefault().newWatchService()
            watchService = ws
            running = true
            thread = Thread({
                try {
                    while (running) {
                        val key: WatchKey = try {
                            ws.poll(1, TimeUnit.SECONDS) ?: continue
                        } catch (_: InterruptedException) { break }

                        val hasRelevantChange = key.pollEvents().any { event ->
                            val name = event.context()?.toString() ?: return@any false
                            // 只关心 SKILL.md 变更、目录变更、或任何在子目录中的变更
                            name == "SKILL.md" || name.endsWith("/SKILL.md") || !name.contains(".")
                        }

                        key.reset()

                        if (hasRelevantChange) {
                            // 简单防抖：等待 500ms 合并连续事件
                            Thread.sleep(500)
                            // 清空积压事件，避免重复触发
                            drainPendingEvents(ws)
                            try { onChange() }
                            catch (_: Exception) { /* 回调异常不中断监听 */ }
                        }
                    }
                } catch (_: InterruptedException) { /* 正常退出 */ }
                catch (_: Exception) { /* 监听异常，静默退出 */ }
                finally {
                    try { ws.close() } catch (_: Exception) {}
                }
            }, "skill-watcher").apply { isDaemon = true; start() }
        }

        /** 排空 WatchService 中的积压事件 */
        private fun drainPendingEvents(ws: WatchService) {
            while (true) {
                val key = ws.poll(100, TimeUnit.MILLISECONDS) ?: break
                key.pollEvents()
                key.reset()
            }
        }

        fun stop() {
            running = false
            thread?.interrupt()
            try { watchService?.close() } catch (_: Exception) {}
            registeredDirs.clear()
        }
    }
}
