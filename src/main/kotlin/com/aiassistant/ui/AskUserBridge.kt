package com.aiassistant.ui

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ask_user 工具的 UI 桥接单例（M5-A）。
 *
 * 设计要点：
 * - 工具层（背景线程）调用 [request]，它通过 invokeLater 将弹卡逻辑分发到 EDT，
 *   同时用 CountDownLatch 阻塞背景线程，等待用户点击。
 * - 5 分钟超时兜底，防止 Agent 无限挂起。
 * - handler 为 null（UI 尚未就绪或插件测试中）时，直接返回第一个选项。
 *
 * 使用方式：
 * ```kotlin
 * // UI 侧（EDT）在 ChatToolWindow.init 内注册 handler：
 * AskUserBridge.handler = { question, options, latch, result ->
 *     val card = SelectionCard.build(question, options) { chosen ->
 *         result.set(chosen)
 *         latch.countDown()
 *     }
 *     conversationContainer.add(card, conversationContainer.componentCount - 1)
 *     conversationContainer.revalidate()
 *     conversationContainer.repaint()
 *     scrollToBottom(force = true)
 * }
 *
 * // 工具侧（背景线程）调用：
 * val choice = AskUserBridge.request("你想怎么做？", listOf("方案 A", "方案 B"))
 * ```
 */
object AskUserBridge {

    /**
     * UI 注册的处理器，在 EDT 上调用，用于把选择卡插入会话区。
     * 实现侧必须：在用户点击后将结果写入 [AtomicReference]，再调用 [CountDownLatch.countDown]。
     */
    @Volatile
    var handler: ((
        question: String,
        options: List<String>,
        latch: CountDownLatch,
        result: AtomicReference<String>
    ) -> Unit)? = null

    /**
     * 从工具的背景线程调用，阻塞直到用户做出选择（或超时/中断）。
     *
     * @param question 要展示给用户的问题
     * @param options  选项列表（至少一项）
     * @return 用户选中的选项文本；超时或选项为空时返回第一项（或空字符串）
     */
    fun request(question: String, options: List<String>): String {
        // handler 未注册时安全降级，不阻塞
        val h = handler ?: return options.firstOrNull() ?: ""

        val latch = CountDownLatch(1)
        val result = AtomicReference("")

        // 把 UI 工作切到 EDT
        ApplicationManager.getApplication().invokeLater {
            h(question, options, latch, result)
        }

        // 阻塞背景线程，最多等 5 分钟，避免 Agent 永久挂起
        try {
            latch.await(5, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        return result.get().ifEmpty { options.firstOrNull() ?: "" }
    }
}
