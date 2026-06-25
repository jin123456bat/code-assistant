package com.aiassistant.ui

import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.border.AbstractBorder

// 圆角边框 — 支持 IntelliJ 亮/暗主题
class RoundedBorder(
    private val radius: Int = 12,
    private val color: Color = JBColor(0xE5E7EB, 0x374151),
    private val thickness: Int = 1
) : AbstractBorder() {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.stroke = BasicStroke(thickness.toFloat())
        g2.drawRoundRect(
            x + thickness / 2,
            y + thickness / 2,
            width - thickness,
            height - thickness,
            radius,
            radius
        )
        g2.dispose()
    }

    // thickness + 3：保证圆角渲染时不裁切，同时内容与边框有合理间距
    override fun getBorderInsets(c: Component?) =
        Insets(thickness + 3, thickness + 3, thickness + 3, thickness + 3)

    override fun isBorderOpaque() = false
}
