package com.aiassistant.util

import com.aiassistant.AppLogger
import com.aiassistant.agent.AgentSession
import com.aiassistant.ui.MessageBus
import com.aiassistant.ui.Toast
import com.intellij.openapi.wm.WindowManager
import java.awt.Window
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 全局异常处理器，覆盖 EDT/PooledThread/Timer/ProcessHandler/Completion 协程等各线程方案。
 *
 * 对齐文档 docs/specs/api-error-handling.md §九「全局异常处理」。
 *
 * 核心原则：
 * - 各组件不自行 try-catch 吞掉异常，所有异常由本处理器统一处理。
 * - 需要特定降级时向上抛出自定义异常，由本处理器按类型分发。
 * - 日志脱敏由 AppLogger 统一提供。
 *
 * ## 线程覆盖
 *
 * | 线程                                    | 注册方式                                           | 处理策略                                                             |
 * |---------------------------------------|------------------------------------------------|------------------------------------------------------------------|
 * | EDT                                   | Thread.setDefaultUncaughtExceptionHandler      | LoggingUncaughtExceptionHandler → 记录日志 + toast 提示"插件内部错误" + 不中断 IDE |
 * | PooledThread（Agent Loop / 工具执行）       | ExecutorService.execute 时设置                    | 记录日志 + MessageBus.publishSystemError() → ChatPage 错误横幅展示给用户      |
 * | Swing Timer / ProcessHandler listener | 记录日志 + 静默恢复（根据上下文决定是否 toast）                          |                                                                  |
 * | Completion 协程                         | CoroutineExceptionHandler                      | 记录日志 + 静默（无候选，不打扰用户）                                           |
 *
 * ## 降级行为
 *
 * | 异常类型                   | 降级行为                          |
 * |------------------------|-------------------------------|
 * | 未捕获 `RuntimeException` | 记录完整堆栈 + toast "插件内部错误"       |
 * | `OutOfMemoryError`     | 记录日志 + 不做额外操作（让 IDE 自行处理）     |
 * | `Error`（非 OOM）         | 记录日志 + 不捕获（让 IDE 处理，避免隐藏严重问题） |
 */
object GlobalExceptionHandler : Thread.UncaughtExceptionHandler {

    /** 默认的 EDT 异常处理器，注册前保存，用于降级链 */
    private var previousEdtHandler: Thread.UncaughtExceptionHandler? = null

    /** 注册状态标记 */
    @Volatile
    private var registered = false

    // ════════════════════════════════════════════════════════════════
    // 注册 / 注销
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册全局异常处理器到所有线程维度。
     * 应由 GlobalExceptionService（项目级 Service）在 project open 时调用。
     *
     * 幂等：重复调用不会重复注册。
     */
    @Synchronized
    fun register() {
        if (registered) return

        // 1. EDT 线程 — 设置默认未捕获异常处理器
        previousEdtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        AppLogger.info("GlobalExceptionHandler 已注册：EDT default handler 已设置")

        registered = true
        AppLogger.info("GlobalExceptionHandler.register() 完成")
    }

    /**
     * 注销全局异常处理器，恢复之前保存的 EDT handler。
     * 应由 GlobalExceptionService 在项目关闭时调用（dispose）。
     */
    @Synchronized
    fun unregister() {
        if (!registered) return

        // 1. 恢复 EDT handler
        Thread.setDefaultUncaughtExceptionHandler(previousEdtHandler)
        AppLogger.info("GlobalExceptionHandler 已注销：EDT default handler 已恢复")

        previousEdtHandler = null
        registered = false
    }

    /**
     * 为后台线程池中的线程设置异常处理器。
     * 应在每次 submit/execute 到线程池时调用：
     *
     * ```kotlin
     * ApplicationManager.getApplication().executeOnPooledThread {
     *     GlobalExceptionHandler.decoratePooledThread(session)
     *     // ... 业务逻辑
     * }
     * ```
     *
     * @param session 当前 AgentSession，用于在线程名中标记来源；可为 null（Completion/非 Agent 场景）
     */
    fun decoratePooledThread(session: AgentSession? = null) {
        val currentThread = Thread.currentThread()
        currentThread.uncaughtExceptionHandler = this
        // 将 session id 标记到线程名，方便异常日志追踪来源。
        if (session != null) {
            currentThread.name = "${currentThread.name}#agent-${session.id.take(8)}"
        }
    }

    /**
     * 创建适用于 Completion 协程的 CoroutineExceptionHandler。
     * 使用方式：
     *
     * ```kotlin
     * val scope = CoroutineScope(SupervisorJob() + GlobalExceptionHandler.coroutineHandler)
     * scope.launch { ... }
     * ```
     */
    val coroutineHandler: CoroutineExceptionHandler
        get() = CoroutineExceptionHandler { _, throwable ->
            handleCompletionException(throwable)
        }

    // ════════════════════════════════════════════════════════════════
    // 核心分发
    // ════════════════════════════════════════════════════════════════

    override fun uncaughtException(t: Thread, e: Throwable) {
        when {
            // EDT 线程
            SwingUtilities.isEventDispatchThread() -> handleEdtException(t, e)
            // Completion 协程线程（通过线程名检测）
            t.name.contains("Coroutine", ignoreCase = true) ||
                    t.name.contains(
                        "DefaultDispatcher",
                        ignoreCase = true
                    ) -> handleCompletionException(e)
            // PooledThread（IntelliJ 线程池线程名通常包含 "pooled" 或 "worker"）
            t.name.contains("pooled", ignoreCase = true) ||
                    t.name.contains("worker", ignoreCase = true) ||
                    t.name.contains("executor", ignoreCase = true) -> handlePooledThreadException(
                t,
                e
            )
            // Swing Timer / ProcessHandler listener（默认线程为后台线程但非 pooled）
            else -> handleTimerOrProcessException(t, e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 各线程处理策略
    // ════════════════════════════════════════════════════════════════

    /**
     * EDT 线程异常处理。
     * 策略：记录日志 + toast 提示"插件内部错误，请查看日志" + 不中断 IDE。
     */
    private fun handleEdtException(t: Thread, e: Throwable) {
        when (e) {
            is OutOfMemoryError -> {
                AppLogger.error("EDT: OutOfMemoryError — ${e.message}，交由 IDE 自行处理")
                // 不做额外操作，让 IDE 自行处理 OOM
                previousEdtHandler?.uncaughtException(t, e)
            }

            is Error -> {
                // 非 OOM 的 Error（如 StackOverflowError）— 记录日志但不捕获，避免隐藏严重问题
                AppLogger.error("EDT: 严重错误 (${e.javaClass.simpleName}): ${e.message}，交由 IDE 处理")
                previousEdtHandler?.uncaughtException(t, e)
            }

            else -> {
                AppLogger.error("EDT: 未捕获异常 — ${e.javaClass.simpleName}: ${e.message}")
                val stackTrace = e.stackTraceToString().take(2000)
                AppLogger.error("EDT 异常堆栈:\n$stackTrace")

                // Toast 提示用户
                showToastSafely("插件内部错误，请查看日志")
            }
        }
    }

    /**
     * PooledThread 异常处理（Agent Loop / 工具执行）。
     * 策略：记录日志 + MessageBus.publishSystemError() → ChatPage 错误横幅展示给用户。
     */
    private fun handlePooledThreadException(t: Thread, e: Throwable) {
        when (e) {
            is OutOfMemoryError -> {
                AppLogger.error("PooledThread: OutOfMemoryError — ${e.message}，交由 IDE 自行处理")
            }

            is Error -> {
                AppLogger.error("PooledThread: 严重错误 (${e.javaClass.simpleName}): ${e.message}")
            }

            else -> {
                val stackTrace = e.stackTraceToString().take(2000)
                AppLogger.error("PooledThread [${t.name}]: 未捕获异常 — ${e.javaClass.simpleName}: ${e.message}")
                AppLogger.error("PooledThread 异常堆栈:\n$stackTrace")

                // 通过 MessageBus 广播异常事件，由 ChatPage 转换为错误横幅
                try {
                    MessageBus.publishSystemError(
                        "插件内部错误: ${e.javaClass.simpleName}",
                        e.message ?: "未知错误"
                    )
                } catch (_: Exception) {
                    // MessageBus 发布失败不阻塞
                }
            }
        }
    }

    /**
     * Swing Timer / ProcessHandler listener 异常处理。
     * 策略：记录日志 + 静默恢复（根据上下文决定是否 toast）。
     */
    private fun handleTimerOrProcessException(t: Thread, e: Throwable) {
        when (e) {
            is OutOfMemoryError -> {
                AppLogger.error("Timer/Process: OutOfMemoryError — ${e.message}，交由 IDE 自行处理")
            }

            is Error -> {
                AppLogger.error("Timer/Process: 严重错误 (${e.javaClass.simpleName}): ${e.message}")
            }

            else -> {
                AppLogger.error("Timer/Process [${t.name}]: 未捕获异常 — ${e.javaClass.simpleName}: ${e.message}")
                // Timer/Process 异常静默恢复，不打扰用户
            }
        }
    }

    /**
     * Completion 协程异常处理。
     * 策略：记录日志 + 静默（无候选，不打扰用户）。
     */
    private fun handleCompletionException(e: Throwable) {
        when (e) {
            is OutOfMemoryError -> {
                AppLogger.error("Completion: OutOfMemoryError — ${e.message}")
            }

            is Error -> {
                AppLogger.error("Completion: 严重错误 (${e.javaClass.simpleName}): ${e.message}")
            }

            else -> {
                AppLogger.error("Completion: 未捕获异常 — ${e.javaClass.simpleName}: ${e.message}，静默跳过")
                // Completion 协程异常静默：无候选即静默，不打扰用户
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 安全地在 EDT 上弹出 Toast（避免在非 EDT 线程直接操作 UI），
     * 且绕过 Toast 自身的异常处理器以免递归。
     */
    private fun showToastSafely(message: String) {
        try {
            SwingUtilities.invokeLater {
                try {
                    val project =
                        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    val window: Window? = if (project != null) {
                        WindowManager.getInstance().getFrame(project)
                    } else {
                        null
                    }
                    if (window != null) {
                        Toast.show(window, message, Toast.Type.ERROR)
                    }
                } catch (_: Exception) {
                    // Toast 自身异常不影响全局处理器
                }
            }
        } catch (_: Exception) {
            // invokeLater 自身异常不影响全局处理器
        }
    }
}
