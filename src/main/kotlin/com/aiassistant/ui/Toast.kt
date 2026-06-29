package com.aiassistant.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * Toast — 右下角弹出提示，3s 后自动消失。
 *
 * 三种类型对应的颜色:
 * - SUCCESS: bg=#22C55E, fg=#FFFFFF
 * - ERROR:   bg=#EF4444, fg=#FFFFFF
 * - WARNING: bg=#F59E0B, fg=#FFFFFF
 *
 * 视觉效果: 圆角 8px，从底部滑入 (300ms ease-out)，3s 后向上滑出消失 (200ms ease-in)
 * 使用 AppAnimations 统一动效系统，对齐 docs/ui/design-system.md §七。
 */
object Toast {

    enum class Type { SUCCESS, ERROR, WARNING }

    private var currentWindow: JWindow? = null
    private var hideTimer: Timer? = null
    private var slideInTimer: Timer? = null

    fun show(owner: Window, message: String, type: Type = Type.SUCCESS) {
        // 移除旧的 Toast，避免重叠
        dismiss()

        val bgColor = when (type) {
            Type.SUCCESS -> AppColors.success
            Type.ERROR -> AppColors.error
            Type.WARNING -> AppColors.warning
        }
        val fgColor = Color.WHITE
        val icon = when (type) {
            Type.SUCCESS -> "✅"
            Type.ERROR -> "❌"
            Type.WARNING -> "⚠"
        }

        val label = JLabel("$icon $message").apply {
            foreground = fgColor
            font = font.deriveFont(13f)
            isOpaque = false
            horizontalAlignment = SwingConstants.LEFT
        }

        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )
                g2.color = bgColor
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(10, 16, 10, 16)
            add(label, BorderLayout.CENTER)
        }

        val window = JWindow(owner).apply {
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
        }
        window.add(panel)
        window.pack()

        // 定位: 右下角，距右 24px，距底 48px
        val ownerBounds = owner.bounds
        val x = ownerBounds.x + ownerBounds.width - window.width - 24
        val targetY = ownerBounds.y + ownerBounds.height - window.height - 48
        window.setLocation(x, targetY)

        // 初始位置偏移到底部以下，实现滑入效果
        window.setLocation(x, targetY + window.height)
        window.isVisible = true
        currentWindow = window

        // 滑入动画：使用 AppAnimations 统一动效系统（300ms ease-out）
        slideInTimer = AppAnimations.Timing.TOAST_SLIDE_IN.animate(
            frameMs = 16,
            onFrame = { eased ->
                window.setLocation(x, targetY + ((1f - eased) * window.height).toInt())
            },
            onEnd = {
                window.setLocation(x, targetY)
            }
        )

        // 3s 后开始滑出 + 销毁
        hideTimer = Timer(3000) {
            slideInTimer?.stop()
            // 滑出动画：使用 AppAnimations 统一动效系统（200ms ease-in），向上滑出消失
            AppAnimations.Timing.TOAST_SLIDE_OUT.animate(
                frameMs = 16,
                onFrame = { eased ->
                    window.setLocation(x, targetY - (eased * window.height).toInt())
                },
                onEnd = {
                    window.dispose()
                    if (currentWindow == window) currentWindow = null
                }
            )
        }.apply { isRepeats = false; start() }
    }

    private fun dismiss() {
        hideTimer?.stop(); hideTimer = null
        slideInTimer?.stop(); slideInTimer = null
        currentWindow?.dispose(); currentWindow = null
    }
}
