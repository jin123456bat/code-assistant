package com.aiassistant.ui

import java.awt.Toolkit
import java.awt.event.ActionListener
import javax.swing.Timer

/**
 * 统一动效工具类，对齐 docs/ui/design-system.md §七 动效规范。
 *
 * 提供 Timing 枚举映射设计系统中所有动效参数：
 * - Toast 滑入 (300ms ease-out) / 滑出 (200ms ease-in)
 * - 审批弹窗出现 (200ms ease-out) / 消失 (150ms ease-in)
 * - 消息气泡出现 (150ms ease-out)
 * - 工具卡片展开/折叠 (200ms ease-out)
 * - hover 高亮 (100ms ease-out)
 *
 * 如果系统启用了 prefers-reduced-motion，所有动画时长为 0ms（即时切换）。
 * 提供 EasingFunction 接口和 easeOut/easeIn 标准实现。
 */
object AppAnimations {

    /** 缓动函数接口：输入 0~1 progress，返回 0~1 eased progress */
    fun interface EasingFunction {
        fun apply(progress: Float): Float
    }

    /** ease-out: 1 - (1-t)^2 */
    val EASE_OUT = EasingFunction { t -> 1f - (1f - t) * (1f - t) }

    /** ease-in: t^2 */
    val EASE_IN = EasingFunction { t -> t * t }

    /**
     * 动效参数枚举，对齐 docs/ui/design-system.md §七 动效规范表格。
     */
    enum class Timing(
        val durationMs: Int,
        val easing: EasingFunction
    ) {
        /** Toast 滑入 - 300ms ease-out */
        TOAST_SLIDE_IN(300, EASE_OUT),

        /** Toast 滑出 - 200ms ease-in */
        TOAST_SLIDE_OUT(200, EASE_IN),

        /** 审批弹窗出现 - 200ms ease-out */
        APPROVAL_APPEAR(200, EASE_OUT),

        /** 审批弹窗消失 - 150ms ease-in */
        APPROVAL_DISAPPEAR(150, EASE_IN),

        /** 消息气泡出现 - 150ms ease-out */
        BUBBLE_APPEAR(150, EASE_OUT),

        /** 工具卡片展开/折叠 - 200ms ease-out */
        TOOL_CARD_TOGGLE(200, EASE_OUT),

        /** hover 高亮 - 100ms ease-out */
        HOVER_HIGHLIGHT(100, EASE_OUT);

        /**
         * 创建 Timer，每 frameMs tick 一次，回调接收 eased progress (0~1)。
         * 动画完成后自动 stop。
         * 如果系统启用了 reduced-motion，duration 降为 0ms，直接回调 progress=1f。
         */
        fun animate(
            frameMs: Int = 16,
            onFrame: (progress: Float) -> Unit,
            onEnd: (() -> Unit)? = null
        ): Timer {
            if (isReducedMotionEnabled()) {
                onFrame(1f)
                onEnd?.invoke()
                return Timer(0) { }  // 返回一个已停止的空 Timer
            }
            val frames = (durationMs / frameMs).coerceAtLeast(1)
            return object : Timer(frameMs, null) {
                var tick = 0
                override fun addActionListener(listener: ActionListener?) {
                    super.addActionListener {
                        tick++
                        val raw = (tick.toFloat() / frames).coerceAtMost(1f)
                        val eased = easing.apply(raw)
                        onFrame(eased)
                        if (tick >= frames) {
                            stop()
                            onEnd?.invoke()
                        }
                    }
                }
            }.apply {
                addActionListener {}
                start()
            }
        }

        /**
         * 创建一次性延迟 + animate Timer 组合，延迟 [delayMs] 后开始动画。
         */
        fun animateAfter(
            delayMs: Int,
            frameMs: Int = 16,
            onFrame: (progress: Float) -> Unit,
            onEnd: (() -> Unit)? = null
        ) {
            Timer(delayMs) {
                animate(frameMs, onFrame, onEnd)
            }.apply { isRepeats = false; start() }
        }
    }

    /**
     * 检测系统是否启用了"减少动效"（prefers-reduced-motion）。
     * 通过 Toolkit desktop property 检测，macOS 上会读取相关 AWT 属性。
     */
    fun isReducedMotionEnabled(): Boolean {
        val toolkit = Toolkit.getDefaultToolkit()
        val propertyNames = arrayOf(
            "awt.dynamicLayoutSupported",
            "apple.awt.reduceMotion"
        )
        for (name in propertyNames) {
            try {
                val prop = toolkit.getDesktopProperty(name)
                if (prop is Boolean && !prop) return true
            } catch (_: Exception) {
                // 忽略不支持的属性
            }
        }
        val osName = System.getProperty("os.name", "").lowercase()
        if (osName.contains("mac")) {
            try {
                val reduceMotion = toolkit.getDesktopProperty("awt.dynamicLayoutSupported")
                if (reduceMotion is Boolean && !reduceMotion) return true
            } catch (_: Exception) {
                // ignore
            }
        }
        return false
    }
}
