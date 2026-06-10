package com.aiassistant.ui

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ask_user 工具的 UI 桥接单例（M5-A / M5-B 多选扩展）。
 *
 * 设计要点：
 * - 工具层（背景线程）调用 [request]，它通过 invokeLater 将弹卡逻辑分发到 EDT，
 *   同时用 CountDownLatch 阻塞背景线程，等待用户点击。
 * - 5 分钟超时兜底，防止 Agent 无限挂起。
 * - handler 为 null（UI 尚未就绪或插件测试中）时，直接返回第一个选项（单选）或空字符串（多选）。
 * - [multiple] 为 true 时，用户可勾选多个选项，结果为所有已选项用 ", " 连接的字符串。
 *
 * 使用方式：
 * ```kotlin
 * // UI 侧（EDT）在 ChatToolWindow.init 内注册 handler：
 * AskUserBridge.handler = { question, options, multiple, latch, result ->
 *     showSelectionCard(question, options, multiple, latch, result)
 * }
 *
 * // 工具侧（背景线程）调用（单选）：
 * val choice = AskUserBridge.request("你想怎么做？", listOf("方案 A", "方案 B"), false)
 *
 * // 工具侧（背景线程）调用（多选）：
 * val choices = AskUserBridge.request("请选择要处理的功能：", listOf("功能A", "功能B", "功能C"), true)
 * // choices 可能为 "功能A, 功能C"
 * ```
 */
object AskUserBridge {

    /**
     * UI 注册的处理器，在 EDT 上调用，用于把选择卡插入会话区。
     * 实现侧必须：在用户点击/确认后将结果写入 [AtomicReference]，再调用 [CountDownLatch.countDown]。
     *
     * 参数说明：
     * - question: 向用户展示的问题
     * - options: 选项列表
     * - multiple: true = 多选（复选框 + 确认按钮）；false = 单选（点击即提交）
     * - latch: 用户操作后调用 countDown 解除工具线程阻塞
     * - result: 将最终选择文本写入此处（多选时用 ", " 连接）
     */
    @Volatile
    var handler: ((
        question: String,
        options: List<String>,
        multiple: Boolean,
        latch: CountDownLatch,
        result: AtomicReference<String>
    ) -> Unit)? = null

    /**
     * 从工具的背景线程调用，阻塞直到用户做出选择（或超时/中断）。
     *
     * @param question 要展示给用户的问题
     * @param options  选项列表（至少一项）
     * @param multiple true 时展示多选模式，返回逗号连接的多项；false 时单选
     * @return 用户选中的选项文本；
     *         单选超时返回第一项；多选超时返回空字符串；
     *         handler 未注册时同上降级
     */
    fun request(question: String, options: List<String>, multiple: Boolean = false): String {
        // handler 未注册时安全降级，不阻塞
        val h = handler ?: return if (multiple) "" else options.firstOrNull() ?: ""

        val latch = CountDownLatch(1)
        val result = AtomicReference("")

        // 把 UI 工作切到 EDT
        ApplicationManager.getApplication().invokeLater {
            h(question, options, multiple, latch, result)
        }

        // 阻塞背景线程，最多等 5 分钟，避免 Agent 永久挂起
        try {
            latch.await(5, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 超时或结果为空时的降级：单选返回第一项，多选返回空字符串
        return result.get().ifEmpty {
            if (multiple) "" else options.firstOrNull() ?: ""
        }
    }
}
